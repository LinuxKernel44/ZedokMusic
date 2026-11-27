// Zedok Piano: a sampled concert grand (Salamander Grand Piano V3, Yamaha C5, recorded by Alexander Holm, CC BY 3.0) with its own engine.
// The 16 velocity layers of every sampled key are cross-faded, notes between samples are re-pitched by at most one semitone, dampers and
// the sustain pedal (a parameter: the host sends no controllers, so it can be automated) shape the release, key-off and pedal noises are
// played, and a bank of string resonators lets the undamped strings ring in sympathy. Tuning (A4, temperaments, stretch), tone, width and
// a small reverb complete it.
//
// The sounds live in one pack (piano.zpk, built by tools/build-piano-pack.py) of Ogg Vorbis files. The pack is memory-mapped; the first
// part of every sound is decoded once and kept (so a note starts at once) and the rest is decoded by a worker thread when a key is struck.
#pragma once
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <deque>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include "fx.h"
#include "processor.h"
#include "zdsp.h"

extern "C" {
#define STB_VORBIS_HEADER_ONLY
#define STB_VORBIS_NO_PUSHDATA_API
#include "third_party/stb_vorbis.c"
}

namespace zdsp {

class Piano : public Processor {
public:
    static constexpr int kMaxVoices = 64;
    static constexpr int kBlock = 16;
    static constexpr int kHead = 12288;            // frames kept decoded for every sound
    static constexpr int kLowKey = 21, kHighKey = 108;   // A0 .. C8
    static constexpr int kNoDamperKey = 89;        // the top of the keyboard has no dampers
    static constexpr size_t kCacheBytes = 96u << 20;

    Piano() {
        if (dataDir().empty()) if (const char* d = std::getenv("ZEDOK_DATA")) dataDir() = d;   // desktop test tools
        ix.volume = add(lin("Main", "Volume", -24.f, 6.f, 0.f, "dB", 1));
        ix.pan = add(lin("Main", "Pan", -1.f, 1.f, 0.f));
        ix.width = add(lin("Main", "Width", 0.f, 2.f, 1.15f));
        ix.transpose = add(lin("Main", "Transpose", -24.f, 24.f, 0.f, "st", 0));
        ix.poly = add(choice("Main", "Polyphony", {"8", "16", "32", "48", "64"}, 2));
        ix.quality = add(choice("Main", "Quality", {"Eco", "High"}, 1));
        ix.curve = add(lin("Touch", "Velocity Curve", 0.5f, 2.f, 1.f, "", 2));
        ix.touch = add(lin("Touch", "Touch", -1.f, 1.f, 0.f));
        ix.human = add(lin("Touch", "Humanize", 0.f, 1.f, 0.f));
        ix.pedal = add(lin("Pedal", "Sustain Pedal", 0.f, 1.f, 0.f));
        ix.pedalNoise = add(lin("Pedal", "Pedal Noise", 0.f, 1.f, 0.5f));
        ix.una = add(lin("Pedal", "Una Corda", 0.f, 1.f, 0.f));
        ix.release = add(pw("Release", "Release", 20.f, 4000.f, 220.f, 2.5f, "ms", 0));
        ix.relKey = add(lin("Release", "Release Key Track", 0.f, 1.f, 0.5f));
        ix.keyOff = add(lin("Release", "Key Off Noise", -70.f, -20.f, -40.f, "dB", 0));   // the release noise of the recording sits 37 dB below the notes; -70 = off
        ix.lowTone = add(lin("Tone", "Low Tone", -9.f, 9.f, 0.f, "dB", 1));
        ix.presence = add(lin("Tone", "Presence", -6.f, 6.f, 0.f, "dB", 1));
        ix.highTone = add(lin("Tone", "High Tone", -12.f, 9.f, 0.f, "dB", 1));
        ix.lid = add(lin("Tone", "Lid", 0.f, 1.f, 1.f));
        ix.resonance = add(lin("Resonance", "String Resonance", 0.f, 1.f, 0.35f));
        ix.resDecay = add(logp("Resonance", "Resonance Decay", 0.5f, 8.f, 3.f, "s", 1));
        ix.a4 = add(lin("Tuning", "A4", 415.f, 466.f, 440.f, "Hz", 1));
        ix.fine = add(lin("Tuning", "Fine Tune", -50.f, 50.f, 0.f, "ct", 0));
        ix.temper = add(choice("Tuning", "Temperament", {"Equal", "Just", "Pythagorean", "Werckmeister III", "Kirnberger III", "Meantone"}, 0));
        ix.temperKey = add(choice("Tuning", "Temperament Key", {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"}, 0));
        ix.stretch = add(lin("Tuning", "Stretch", 0.f, 40.f, 10.f, "ct", 0));
        ix.rvMix = add(lin("Space", "Reverb Mix", 0.f, 1.f, 0.12f));
        ix.rvType = add(choice("Space", "Reverb Type", {"Room", "Plate", "Hall"}, 2));
        ix.rvSize = add(lin("Space", "Reverb Size", 0.f, 1.f, 0.7f));
        ix.rvDecay = add(logp("Space", "Reverb Decay", 0.3f, 8.f, 2.2f, "s", 1));
        ix.rvDamp = add(lin("Space", "Reverb Damping", 0.f, 1.f, 0.5f));
    }
    ~Piano() override {
        stop_ = true;
        if (loader_.joinable()) loader_.join();
        if (worker_.joinable()) worker_.join();
        if (map_) munmap(const_cast<uint8_t*>(map_), mapLen_);
    }
    const char* name() const override { return "Zedok Piano"; }
    bool isInstrument() const override { return true; }

    void prepare(double sr, int maxFrames) override {
        sr_ = sr; mixL_.assign(maxFrames, 0.f); mixR_.assign(maxFrames, 0.f); reverb_.prepare(sr);
        if (!started_) { started_ = true; loader_ = std::thread([this] { loadPack(); }); worker_ = std::thread([this] { workerLoop(); }); }
        reset();
    }
    void reset() override {
        for (auto& v : voices_) v = Voice();
        reverb_.reset(); for (auto& b : eq_) for (auto& q : b) q.reset(); bank_.reset(); pedalDown_ = false;
        for (auto& k : held_) k = false;
    }
    int telemetry(int what, float* out, int cap) override {
        if (what == 0 && cap >= 2) { out[0] = loadFrac_.load(); out[1] = (float)state_.load(); return 2; }
        if (what == 1) { int k = 0; for (int n = kLowKey; n <= kHighKey && k < cap; n++) out[k++] = keyLevel_[n - kLowKey]; return k; }   // 88 key levels for a keyboard display
        return 0;
    }

    void process(const ProcessCtx& c, const float* vals) override {
        readParams(vals);
        const int n = c.frames; if (n > (int)mixL_.size()) return;
        std::fill(mixL_.begin(), mixL_.begin() + n, 0.f); std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
        // the sustain pedal is a parameter: act on its transitions
        const bool down = P_.pedal >= 0.5f;
        if (down != pedalDown_) { pedalDown_ = down; onPedal(down); }
        int pos = 0, ei = 0;
        while (pos < n) {
            while (ei < c.nEvents && (int)c.events[ei].offset <= pos) handleEvent(c.events[ei++]);
            int end = n; if (ei < c.nEvents) end = std::min(end, std::max(pos + 1, (int)c.events[ei].offset));
            while (pos < end) { const int len = std::min(end - pos, kBlock); renderBlock(pos, len); pos += len; }
        }
        float* L = mixL_.data(); float* R = mixR_.data();
        // sympathetic string resonance: the dry signal excites the strings that are free to ring
        if (P_.resonance > 0.001f) bank_.process(L, R, n, P_.resonance, held_, pedalDown_ ? 1.f : std::max(0.f, (P_.pedal - 0.15f) * 2.f), P_.resDecay, P_.a4, sr_);
        applyTone(L, R, n);
        if (P_.rvMix > 0.001f) { reverb_.set(P_.rvType == 0 ? Reverb::Room : P_.rvType == 1 ? Reverb::Plate : Reverb::Hall, P_.rvSize, P_.rvDecay, P_.rvDamp, 0.8f, 0.4f, 8.f, 80.f, 9500.f, 1.f, 0.f, P_.rvMix * 0.6f); reverb_.process(L, R, n); }
        const float g = dbToLin(P_.volume) * (1.f - 0.25f * P_.una);
        const float pl = std::cos((P_.pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f, pr = std::sin((P_.pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f;
        for (int i = 0; i < n; i++) {
            const float m = 0.5f * (L[i] + R[i]), s = 0.5f * (L[i] - R[i]) * P_.width;
            c.outL[i] = softOut((m + s) * g * pl); c.outR[i] = softOut((m - s) * g * pr);
        }
        for (auto& k : keyLevel_) k *= 0.9f;
        for (auto& v : voices_) if (v.active && !v.noise && v.note >= kLowKey && v.note <= kHighKey) keyLevel_[v.note - kLowKey] = std::max(keyLevel_[v.note - kLowKey], v.env * v.vel);
    }

private:
    // ------------------------------------------------------------------ parameters
    struct Ix { int volume, pan, width, transpose, poly, quality, curve, touch, human, pedal, pedalNoise, una, release, relKey, keyOff, lowTone, presence, highTone, lid, resonance, resDecay,
                a4, fine, temper, temperKey, stretch, rvMix, rvType, rvSize, rvDecay, rvDamp; } ix;
    struct Params {
        float volume, pan, width, transpose; int poly; bool hq; float curve, touch, human, pedal, pedalNoise, una, release, relKey, keyOff, lowTone, presence, highTone, lid, resonance, resDecay;
        float a4, fine; int temper, temperKey; float stretch, rvMix; int rvType; float rvSize, rvDecay, rvDamp;
    } P_{};
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    int idx(const float* v, int i) const { return (int)(specs_[i].toReal(v[i]) + 0.5f); }
    void readParams(const float* v) {
        Params& p = P_;
        p.volume = real(v, ix.volume); p.pan = real(v, ix.pan); p.width = real(v, ix.width); p.transpose = std::round(real(v, ix.transpose));
        { static const int po[] = {8, 16, 32, 48, 64}; p.poly = po[idx(v, ix.poly)]; } p.hq = idx(v, ix.quality) == 1;
        p.curve = real(v, ix.curve); p.touch = real(v, ix.touch); p.human = real(v, ix.human); p.pedal = real(v, ix.pedal); p.pedalNoise = real(v, ix.pedalNoise); p.una = real(v, ix.una);
        p.release = real(v, ix.release) * 0.001f; p.relKey = real(v, ix.relKey); p.keyOff = real(v, ix.keyOff); /* dB */
        p.lowTone = real(v, ix.lowTone); p.presence = real(v, ix.presence); p.highTone = real(v, ix.highTone); p.lid = real(v, ix.lid);
        p.resonance = real(v, ix.resonance); p.resDecay = real(v, ix.resDecay);
        p.a4 = real(v, ix.a4); p.fine = real(v, ix.fine); p.temper = idx(v, ix.temper); p.temperKey = idx(v, ix.temperKey); p.stretch = real(v, ix.stretch);
        p.rvMix = real(v, ix.rvMix); p.rvType = idx(v, ix.rvType); p.rvSize = real(v, ix.rvSize); p.rvDecay = real(v, ix.rvDecay); p.rvDamp = real(v, ix.rvDamp);
    }
    static inline float softOut(float x) { const float a = std::fabs(x); if (a <= 0.9f) return x; const float y = 0.9f + 0.1f * fastTanh((a - 0.9f) * 10.f); return x < 0 ? -y : y; }

    // ------------------------------------------------------------------ the sound pack
    struct Decoded { std::vector<int16_t> pcm; std::atomic<int> done{0}; };   // stereo interleaved; frames [0, done) are valid
    struct Entry {
        uint8_t kind = 0, key = 0, layer = 0; uint32_t off = 0, size = 0, frames = 0, rate = 48000; int index = 0;
        std::vector<int16_t> head; int headFrames = 0;
        std::shared_ptr<Decoded> dec; std::atomic<bool> requested{false}; int64_t lastUse = 0;
    };
    const uint8_t* map_ = nullptr; size_t mapLen_ = 0;
    std::vector<std::unique_ptr<Entry>> entries_;
    int noteIdx_[128][16]; int relIdx_[128]; int pedalIdx_[2][4]; int nPedal_[2] = {0, 0};
    std::atomic<float> loadFrac_{0.f}; std::atomic<int> state_{0};   // 0 waiting for the file, 1 loading, 2 ready, 3 error
    std::atomic<bool> stop_{false}; bool started_ = false; std::thread loader_, worker_;
    std::atomic<bool> ready_{false};
    int reqBuf_[256]; std::atomic<uint32_t> reqHead_{0}, reqTail_{0};
    std::deque<Entry*> cache_; size_t cacheBytes_ = 0;

    static uint32_t rd32(const uint8_t* p) { uint32_t v; memcpy(&v, p, 4); return v; }
    void loadPack() {
        const std::string path = dataDir() + "/piano.zpk";
        struct stat st {};
        for (int tries = 0; !stop_ && tries < 4800; tries++) { if (stat(path.c_str(), &st) == 0 && st.st_size > 64) break; std::this_thread::sleep_for(std::chrono::milliseconds(250)); }
        if (stop_) return;
        const int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0 || fstat(fd, &st) != 0) { state_ = 3; if (fd >= 0) close(fd); return; }
        // the app copies the pack under a temporary name and renames it, so a file that exists is complete
        void* m = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0); close(fd);
        if (m == MAP_FAILED) { state_ = 3; return; }
        map_ = (const uint8_t*)m; mapLen_ = (size_t)st.st_size;
        if (mapLen_ < 8 || memcmp(map_, "ZPK1", 4) != 0) { state_ = 3; return; }
        state_ = 1;
        const uint32_t count = rd32(map_ + 4); const size_t entBytes = 20;
        if (8 + (size_t)count * entBytes > mapLen_) { state_ = 3; return; }
        for (auto& r : noteIdx_) for (int& x : r) x = -1;
        for (int& x : relIdx_) x = -1;
        for (auto& r : pedalIdx_) for (int& x : r) x = -1;
        entries_.reserve(count);
        for (uint32_t i = 0; i < count && !stop_; i++) {
            const uint8_t* e = map_ + 8 + i * entBytes; auto en = std::make_unique<Entry>();
            en->kind = e[0]; en->key = e[1]; en->layer = e[2]; en->off = rd32(e + 4); en->size = rd32(e + 8); en->frames = rd32(e + 12); en->rate = rd32(e + 16); en->index = (int)i;
            if ((size_t)en->off + en->size > mapLen_) continue;
            int err = 0; stb_vorbis* v = stb_vorbis_open_memory(map_ + en->off, (int)en->size, &err, nullptr);
            if (!v) continue;
            const int want = (int)std::min<uint32_t>(kHead, en->frames); en->head.assign((size_t)want * 2, 0);
            const int got = stb_vorbis_get_samples_short_interleaved(v, 2, en->head.data(), want * 2); en->headFrames = got;
            stb_vorbis_close(v);
            const int k = (int)en->key;
            if (en->kind == 0 && k < 128 && en->layer >= 1 && en->layer <= 16) noteIdx_[k][en->layer - 1] = (int)entries_.size();
            else if (en->kind == 1 && k < 128) relIdx_[k] = (int)entries_.size();
            else if ((en->kind == 2 || en->kind == 3) && nPedal_[en->kind - 2] < 4) pedalIdx_[en->kind - 2][nPedal_[en->kind - 2]++] = (int)entries_.size();
            entries_.push_back(std::move(en));
            if ((i & 15) == 0) loadFrac_ = (float)i / (float)count;
        }
        loadFrac_ = 1.f; ready_.store(true, std::memory_order_release); state_ = 2;
    }

    // Decodes sounds on request (the audio thread only pushes an index). Decoded sounds are kept in a cache of limited size.
    void workerLoop() {
        while (!stop_) {
            uint32_t t = reqTail_.load(std::memory_order_relaxed);
            if (t == reqHead_.load(std::memory_order_acquire)) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
            const int idx = reqBuf_[t % 256]; reqTail_.store(t + 1, std::memory_order_release);
            if (idx < 0 || idx >= (int)entries_.size()) continue;
            Entry& e = *entries_[idx];
            auto d = std::make_shared<Decoded>(); d->pcm.assign((size_t)e.frames * 2, 0);
            std::atomic_store(&e.dec, d);   // voices start reading the part that is decoded
            cache_.push_back(&e); cacheBytes_ += d->pcm.size() * 2;
            int err = 0; stb_vorbis* v = stb_vorbis_open_memory(map_ + e.off, (int)e.size, &err, nullptr);
            int done = 0;
            if (v) {
                while (done < (int)e.frames && !stop_) {
                    const int want = std::min<int>(4096, (int)e.frames - done);
                    const int got = stb_vorbis_get_samples_short_interleaved(v, 2, d->pcm.data() + (size_t)done * 2, want * 2);
                    if (got <= 0) break; done += got; d->done.store(done, std::memory_order_release);
                }
                stb_vorbis_close(v);
            }
            d->done.store((int)e.frames, std::memory_order_release);   // whatever could not be decoded stays silent
            // evict the oldest sounds that no voice is using
            while (cacheBytes_ > kCacheBytes && cache_.size() > 1) {
                Entry* o = nullptr; size_t at = 0;
                for (size_t i = 0; i < cache_.size() - 1; i++) { auto s = std::atomic_load(&cache_[i]->dec); if (s && s.use_count() <= 2) { o = cache_[i]; at = i; break; } }
                if (!o) break;
                cacheBytes_ -= (size_t)o->frames * 4; cache_.erase(cache_.begin() + at); std::atomic_store(&o->dec, std::shared_ptr<Decoded>()); o->requested.store(false);
            }
        }
    }
    void request(Entry& e) {
        if (e.requested.exchange(true)) return;
        const uint32_t h = reqHead_.load(std::memory_order_relaxed);
        if (h - reqTail_.load(std::memory_order_acquire) >= 255) { e.requested = false; return; }
        reqBuf_[h % 256] = e.index; reqHead_.store(h + 1, std::memory_order_release);
    }

    // ------------------------------------------------------------------ voices
    struct Layer { const Entry* e = nullptr; std::shared_ptr<Decoded> dec; double pos = 0.0; float gain = 0.f; double ratio = 1.0; };
    struct Voice {
        bool active = false, gate = false, sustained = false, releasing = false, noise = false;
        int note = 60, nLayers = 0; float vel = 1.f; uint64_t age = 0; Layer L[2]; float env = 1.f, relCoef = 1.f; float cents = 0.f;
    };
    Voice voices_[kMaxVoices]; uint64_t age_ = 0; bool pedalDown_ = false; bool held_[88] = {}; float keyLevel_[88] = {};
    std::vector<float> mixL_, mixR_; Reverb reverb_; Rng rng_{99u}; int noiseLeft_ = 0;

    static constexpr int kLayerCenter[16] = {13, 30, 35, 40, 45, 48, 53, 60, 68, 76, 84, 92, 100, 108, 116, 124};
    void pickLayers(int vel, int& a, int& b, float& wa, float& wb) const {
        float v = std::pow(std::max(1, vel) / 127.f, P_.curve) * 127.f + P_.touch * 30.f; v = std::min(127.f, std::max(1.f, v));
        if (v <= kLayerCenter[0]) { a = b = 0; wa = 1.f; wb = 0.f; return; }
        if (v >= kLayerCenter[15]) { a = b = 15; wa = 1.f; wb = 0.f; return; }
        int i = 0; while (i < 14 && v >= kLayerCenter[i + 1]) i++;
        const float t = (v - kLayerCenter[i]) / (float)(kLayerCenter[i + 1] - kLayerCenter[i]);
        a = i; b = i + 1; wa = std::cos(t * 1.5707963f); wb = std::sin(t * 1.5707963f);
    }
    // cents of detune of a key: temperament, stretch tuning, fine tune
    static const float* temperament(int t) {
        static const float tab[6][12] = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0.f, 11.7f, 3.9f, 15.6f, -13.7f, -2.0f, -9.8f, 2.0f, 13.7f, -15.6f, 17.6f, -11.7f},
            {0.f, -9.8f, 3.9f, -5.9f, 7.8f, -2.0f, 11.7f, 2.0f, -7.8f, 5.9f, -3.9f, 9.8f},
            {0.f, -9.8f, -7.8f, -5.9f, -9.8f, -2.0f, -11.7f, -3.9f, -7.8f, -11.7f, -3.9f, -7.8f},
            {0.f, -9.8f, -6.8f, -5.9f, -13.7f, -2.0f, -9.8f, -3.4f, -7.8f, -10.3f, -3.9f, -11.7f},
            {0.f, -24.f, -6.8f, 10.3f, -13.7f, 3.4f, -20.5f, -3.4f, -27.4f, -10.3f, 6.8f, -17.1f}};
        return tab[t < 0 ? 0 : t > 5 ? 5 : t];
    }
    float keyCents(int key) const {
        const float* tt = temperament(P_.temper); const int pc = ((key - P_.temperKey) % 12 + 12) % 12;
        const float x = (key - 60) / 48.f; const float stretch = P_.stretch * x * std::fabs(x) * 1.6f;
        return tt[pc] + stretch + P_.fine;
    }

    void onPedal(bool down) {
        // the pedal noise, then the dampers fall on the notes that were only held by the pedal
        if (P_.pedalNoise > 0.01f) { const int k = down ? 0 : 1; if (nPedal_[k] > 0) { const int pi = pedalIdx_[k][(int)(rng_.uni() * nPedal_[k]) % nPedal_[k]]; if (pi >= 0 && pi < (int)entries_.size()) startNoise(entries_[pi].get(), 0.2f * P_.pedalNoise, 60); /* the pedal noises of the recording are meant to be 20 dB down */ } }
        if (!down) { noiseLeft_ = 4; for (auto& v : voices_) if (v.active && v.sustained && !v.noise) { v.sustained = false; startRelease(v, true); } }
    }
    void startNoise(const Entry* e, float gain, int note) {
        if (!e || !ready_.load(std::memory_order_acquire)) return;
        Voice* v = allocVoice(true); if (!v) return;
        *v = Voice(); v->active = true; v->noise = true; v->note = note; v->vel = 1.f; v->age = ++age_; v->nLayers = 1;
        v->L[0].e = e; v->L[0].gain = gain; v->L[0].ratio = (double)e->rate / sr_;
        request(const_cast<Entry&>(*e)); v->L[0].dec = std::atomic_load(&e->dec);
    }
    void startRelease(Voice& v, bool byPedal) {
        v.releasing = true;
        float t = P_.release * std::exp2((60.f - (float)v.note) / 12.f * P_.relKey * 1.2f);
        if (P_.pedal > 0.01f && P_.pedal < 0.5f) t *= 1.f + 10.f * P_.pedal;   // half pedal: the dampers only touch the strings
        if (v.note >= kNoDamperKey) t = std::max(t, 4.0f);                      // no dampers at the top of the keyboard
        t = std::max(t, 0.02f);
        v.relCoef = std::pow(10.f, -3.f / (t * (float)sr_));
        if (P_.keyOff > -69.f && v.note < kNoDamperKey && (!byPedal || noiseLeft_ > 0)) {
            if (byPedal) noiseLeft_--;
            const int k = std::min(kHighKey, std::max(kLowKey, v.note + (int)P_.transpose));
            if (k < 128 && relIdx_[k] >= 0) startNoise(entries_[relIdx_[k]].get(), dbToLin(P_.keyOff) * (byPedal ? 0.4f : 1.f) * (0.35f + 0.65f * v.vel), v.note);
        }
    }
    Voice* allocVoice(bool noise) {
        int limit = std::min(P_.poly, kMaxVoices) + (noise ? 8 : 0); limit = std::min(limit, kMaxVoices); int cnt = 0; for (auto& v : voices_) if (v.active) cnt++;
        if (cnt < limit) for (auto& v : voices_) if (!v.active) return &v;
        Voice* best = nullptr;
        for (auto& v : voices_) if (v.active && v.releasing && (!best || v.env < best->env)) best = &v;
        if (!best) for (auto& v : voices_) if (v.active && (!best || v.age < best->age)) best = &v;
        return best;
    }
    void doNoteOn(int note, int vel) {
        if (!ready_.load(std::memory_order_acquire)) return;
        const int key = std::min(kHighKey, std::max(kLowKey, note + (int)P_.transpose));
        // a struck string cuts the previous sound of the same note
        for (auto& o : voices_) if (o.active && !o.noise && o.note == note && !o.releasing) { o.sustained = false; o.gate = false; o.releasing = true; o.relCoef = std::pow(10.f, -3.f / (0.06f * (float)sr_)); }
        Voice* v = allocVoice(false); if (!v) return;
        int a, b; float wa, wb; int vv = vel; if (P_.human > 0.f) vv = std::min(127, std::max(1, vel + (int)(rng_.bi() * 8.f * P_.human)));
        pickLayers(vv, a, b, wa, wb);
        // the nearest sampled key (minor thirds, starting at A0)
        int center = 21 + 3 * (int)std::lround((key - 21) / 3.0); center = std::min(kHighKey, std::max(kLowKey, center));
        *v = Voice(); v->active = true; v->gate = true; v->note = note; v->vel = vel / 127.f; v->age = ++age_; v->cents = keyCents(key) + (P_.human > 0.f ? rng_.bi() * 3.f * P_.human : 0.f);
        const double semis = (key - center) + v->cents / 100.0 + 12.0 * std::log2(P_.a4 / 440.0);
        v->nLayers = (a == b) ? 1 : 2;
        const int ls[2] = {a, b}; const float ws[2] = {wa, wb};
        for (int i = 0; i < v->nLayers; i++) {
            const int ei = noteIdx_[center][ls[i]]; Layer& L = v->L[i];
            if (ei < 0) continue;
            L.e = entries_[ei].get(); L.gain = ws[i]; L.ratio = std::exp2(semis / 12.0) * (double)L.e->rate / sr_;
            request(const_cast<Entry&>(*L.e)); L.dec = std::atomic_load(&L.e->dec);
        }
        if (note >= kLowKey && note <= kHighKey) held_[note - kLowKey] = true;
    }
    void doNoteOff(int note) {
        if (note >= kLowKey && note <= kHighKey) held_[note - kLowKey] = false;
        for (auto& v : voices_) if (v.active && !v.noise && v.gate && v.note == note) {
            v.gate = false;
            if (pedalDown_) v.sustained = true; else startRelease(v, false);
        }
    }
    void handleEvent(const ZkEvent& e) {
        if (e.type == ZK_ALL_NOTES_OFF) {
            for (auto& v : voices_) if (v.active) { v.sustained = false; v.gate = false; if (e.pitch == 1) { v.releasing = true; v.relCoef = std::pow(10.f, -3.f / (0.004f * (float)sr_)); } else if (!v.releasing) startRelease(v, true); }
            for (auto& k : held_) k = false; return;
        }
        if (e.type == ZK_NOTE_ON) { if (e.velocity == 0) doNoteOff(e.pitch); else doNoteOn(e.pitch, e.velocity); } else if (e.type == ZK_NOTE_OFF) doNoteOff(e.pitch);
    }

    void renderBlock(int pos, int len) { for (auto& v : voices_) if (v.active) renderVoice(v, pos, len); }
    static inline float hermite(float y0, float y1, float y2, float y3, float f) {
        const float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * f + c2) * f + c1) * f + y1;
    }
    void renderVoice(Voice& v, int pos, int len) {
        // pick what each layer reads from this block: the decoded copy if there is one, otherwise the always-resident beginning
        const int16_t* src[2] = {nullptr, nullptr}; int avail[2] = {0, 0}; int total[2] = {0, 0}; bool anyLeft = false;
        for (int i = 0; i < v.nLayers; i++) {
            Layer& L = v.L[i]; if (!L.e) continue;
            if (!L.dec) L.dec = std::atomic_load(&L.e->dec);
            total[i] = (int)L.e->frames;
            if (L.dec && L.dec->done.load(std::memory_order_acquire) > L.e->headFrames) { avail[i] = L.dec->done.load(std::memory_order_acquire); src[i] = L.dec->pcm.data(); }
            else { avail[i] = L.e->headFrames; src[i] = L.e->head.data(); }
            if (L.pos < total[i]) anyLeft = true;
        }
        if (!anyLeft) { v.active = false; return; }
        for (int k = 0; k < len; k++) {
            float l = 0.f, r = 0.f;
            for (int i = 0; i < v.nLayers; i++) {
                Layer& L = v.L[i]; if (!src[i] || L.pos >= total[i]) continue;
                const int ix0 = (int)L.pos; const float f = (float)(L.pos - ix0);
                if (ix0 + 3 >= avail[i] && avail[i] < total[i]) continue;   // not decoded yet: wait without moving
                auto at = [&](int j, int ch) -> float { return (j < 0 || j >= total[i]) ? 0.f : src[i][(size_t)j * 2 + ch] * (1.f / 32768.f); };
                float sl, sr2;
                if (P_.hq) { sl = hermite(at(ix0 - 1, 0), at(ix0, 0), at(ix0 + 1, 0), at(ix0 + 2, 0), f); sr2 = hermite(at(ix0 - 1, 1), at(ix0, 1), at(ix0 + 1, 1), at(ix0 + 2, 1), f); }
                else { sl = at(ix0, 0) + (at(ix0 + 1, 0) - at(ix0, 0)) * f; sr2 = at(ix0, 1) + (at(ix0 + 1, 1) - at(ix0, 1)) * f; }
                l += sl * L.gain; r += sr2 * L.gain; L.pos += L.ratio;
            }
            if (v.releasing) { v.env *= v.relCoef; if (v.env < 1e-4f) { v.active = false; return; } }
            mixL_[pos + k] += l * v.env; mixR_[pos + k] += r * v.env;
        }
    }

    // ------------------------------------------------------------------ tone
    Biquad eq_[2][4];   // per channel: low shelf, presence, high shelf, lid low-pass
    float eqKey_[6] = {1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f};
    void applyTone(float* L, float* R, int n) {
        const float key[6] = {P_.lowTone, P_.presence, P_.highTone, P_.lid, P_.una, (float)sr_};
        bool changed = false; for (int i = 0; i < 6; i++) if (key[i] != eqKey_[i]) changed = true;
        if (changed) {
            for (int i = 0; i < 6; i++) eqKey_[i] = key[i];
            const double s = sr_; const BiquadCoef c0 = biquadDesign(BQ::LowShelf, s, 160.0, 0.7, P_.lowTone), c1 = biquadDesign(BQ::Peak, s, 2800.0, 0.8, P_.presence), c2 = biquadDesign(BQ::HighShelf, s, 4200.0, 0.7, P_.highTone);
            const float cut = std::min(20000.f, 2500.f * std::exp2(P_.lid * 3.1f)) * (1.f - 0.6f * P_.una * 0.5f) * (1.f - 0.45f * P_.una);   // lid closed and soft pedal darken the sound
            const BiquadCoef c3 = biquadDesign(BQ::LowPass, s, cut, 0.6);
            for (int ch = 0; ch < 2; ch++) { eq_[ch][0].set(c0); eq_[ch][1].set(c1); eq_[ch][2].set(c2); eq_[ch][3].set(c3); }
        }
        for (int i = 0; i < n; i++) { float a = L[i], b = R[i]; for (int q = 0; q < 4; q++) { a = eq_[0][q].process(a); b = eq_[1][q].process(b); } L[i] = a; R[i] = b; }
    }

    // ------------------------------------------------------------------ string resonance
    // Three partials of every key as high-Q two-pole resonators. A string takes part when its key is held, or when the pedal lifts every damper.
    struct StringBank {
        struct Res { float a1 = 0, a2 = 0, b0 = 0, y1 = 0, y2 = 0; };
        std::vector<Res> res; float cfgDecay = -1.f, cfgA4 = -1.f, cfgSr = -1.f;
        void reset() { for (auto& r : res) r.y1 = r.y2 = 0.f; }
        void setup(float decay, float a4, double sr) {
            if (res.size() != 88 * 3) res.assign(88 * 3, Res());
            if (decay == cfgDecay && a4 == cfgA4 && (float)sr == cfgSr) return;
            cfgDecay = decay; cfgA4 = a4; cfgSr = (float)sr;
            for (int k = 0; k < 88; k++) for (int h = 0; h < 3; h++) {
                const double f = (double)a4 * std::pow(2.0, (k + kLowKey - 69) / 12.0) * (h + 1);
                const double t60 = std::max(0.3, (double)decay * std::pow(2.0, -(k) / 40.0) / (1.0 + h * 0.6));   // high keys and high partials die sooner
                const double rr = std::pow(10.0, -3.0 / (t60 * sr)); const double w = 2.0 * 3.14159265358979 * std::min(f, sr * 0.45) / sr;
                res[k * 3 + h].a1 = (float)(2.0 * rr * std::cos(w)); res[k * 3 + h].a2 = (float)(-rr * rr); res[k * 3 + h].b0 = (float)(1.0 - rr);   // unity gain at the resonance
            }
        }
        void process(float* L, float* R, int n, float amount, const bool* held, float pedal, float decay, float a4, double sr) {
            if (n > 2048) return;
            setup(decay, a4, sr);
            const float gain = amount * 0.035f;
            for (int i = 0; i < n; i++) { in_[i] = 0.5f * (L[i] + R[i]); acc_[i] = 0.f; }
            for (int k = 0; k < 88; k++) {
                const bool free = held[k] || pedal > 0.f; if (!free) { for (int h = 0; h < 3; h++) { Res& r = res[k * 3 + h]; r.y1 *= 0.995f; r.y2 *= 0.995f; } continue; }
                const float g = held[k] ? 1.f : pedal;
                for (int h = 0; h < 3; h++) {
                    Res& r = res[k * 3 + h]; float y1 = r.y1, y2 = r.y2; const float a1 = r.a1, a2 = r.a2, b0 = r.b0, gg = gain * g / (h + 1);
                    for (int i = 0; i < n; i++) { const float y = b0 * in_[i] + a1 * y1 + a2 * y2; y2 = y1; y1 = y; acc_[i] += y * gg; }
                    r.y1 = y1; r.y2 = y2;
                }
            }
            for (int i = 0; i < n; i++) { L[i] += acc_[i]; R[i] += acc_[i]; }
        }
        float in_[2048] = {}, acc_[2048] = {};
    } bank_;
};

}  // namespace zdsp
