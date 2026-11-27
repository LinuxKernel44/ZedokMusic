// Zedok Sampler: a single-sample instrument a classic single-sample instrument. One sample is played across the keyboard
// (root note, key tracking on or off), with start / end offsets, forward / ping-pong / sustain loops with a crossfade, reverse, fade in / out,
// normalize, chopping into 2..32 slices (one per key), time handling (Resample = speed and pitch together, Stretch = independent) with
// the sample's own BPM and tempo sync, a volume / filter / pitch envelope (delay, attack, hold, decay, sustain, release), an LFO that
// reaches pitch, volume, pan and cutoff, a resonant filter, glide, mono / legato play and "cut itself".
#pragma once
#include <algorithm>
#include <atomic>
#include <memory>
#include "processor.h"
#include "zdsp.h"
#include "../../app/src/main/cpp/wav.h"

namespace zdsp {

struct SampBuf {
    std::vector<float> L, R; int n = 0; double rate = 48000.0; float peak = 1.f; std::string name; std::vector<float> overview;   // overview: 256 peaks 0..1
    void finish() {
        n = (int)L.size(); peak = 0.f; overview.assign(256, 0.f);
        for (int i = 0; i < n; i++) peak = std::max(peak, std::max(std::fabs(L[i]), std::fabs(R[i])));
        for (int i = 0; i < 256 && n > 0; i++) {
            const int a = (int)((int64_t)n * i / 256), b = std::max(a + 1, (int)((int64_t)n * (i + 1) / 256)); float m = 0.f;
            for (int k = a; k < b && k < n; k++) m = std::max(m, std::max(std::fabs(L[k]), std::fabs(R[k])));
            overview[i] = m;
        }
    }
};

class Sampler : public Processor {
public:
    static constexpr int kMaxVoices = 32;
    static constexpr int kBlock = 16;

    Sampler() {
        ix.root = add(lin("Sample", "Root Note", 0.f, 127.f, 60.f, "", 0));
        ix.start = add(lin("Sample", "Start", 0.f, 1.f, 0.f));
        ix.end = add(lin("Sample", "End", 0.f, 1.f, 1.f));
        ix.loopMode = add(choice("Sample", "Loop Mode", {"Off", "Forward", "Ping-Pong", "Until Release"}, 0));
        ix.loopStart = add(lin("Sample", "Loop Start", 0.f, 1.f, 0.f));
        ix.loopEnd = add(lin("Sample", "Loop End", 0.f, 1.f, 1.f));
        ix.loopFade = add(pw("Sample", "Loop Crossfade", 0.f, 500.f, 10.f, 2.f, "ms", 0));
        ix.reverse = add(toggle("Sample", "Reverse"));
        ix.normalize = add(toggle("Sample", "Normalize"));
        ix.fadeIn = add(pw("Sample", "Fade In", 0.f, 2000.f, 0.f, 2.5f, "ms", 0));
        ix.fadeOut = add(pw("Sample", "Fade Out", 0.f, 2000.f, 0.f, 2.5f, "ms", 0));
        ix.track = add(toggle("Sample", "Key Tracking", true));
        ix.oneShot = add(toggle("Sample", "One Shot"));
        ix.slices = add(choice("Sample", "Slices", {"Off", "2", "4", "8", "16", "32"}, 0));
        ix.timeMode = add(choice("Time", "Time Mode", {"Resample", "Stretch"}, 0));
        ix.stretch = add(logp("Time", "Time Stretch", 0.25f, 4.f, 1.f, "x", 3));
        ix.sbpm = add(lin("Time", "Sample BPM", 0.f, 300.f, 0.f, "BPM", 1));
        ix.sync = add(toggle("Time", "Tempo Sync"));
        ix.grain = add(lin("Time", "Stretch Grain", 20.f, 200.f, 60.f, "ms", 0));
        ix.volume = add(lin("Main", "Volume", -36.f, 6.f, 0.f, "dB", 1));
        ix.pan = add(lin("Main", "Pan", -1.f, 1.f, 0.f));
        ix.velSens = add(lin("Main", "Velocity Sens", 0.f, 1.f, 0.7f));
        ix.coarse = add(lin("Main", "Coarse", -24.f, 24.f, 0.f, "st", 0));
        ix.fine = add(lin("Main", "Fine", -100.f, 100.f, 0.f, "ct", 0));
        ix.bendRange = add(lin("Main", "Bend Range", 1.f, 24.f, 2.f, "st", 0));
        ix.bend = add(lin("Main", "Pitch Bend", -1.f, 1.f, 0.f));
        ix.poly = add(choice("Main", "Polyphony", {"1", "2", "4", "8", "16", "32"}, 4));
        ix.mono = add(choice("Main", "Play Mode", {"Poly", "Mono", "Legato"}, 0));
        ix.glide = add(pw("Main", "Glide", 0.f, 2000.f, 0.f, 2.5f, "ms", 0));
        ix.cutSelf = add(toggle("Main", "Cut Itself"));
        ix.quality = add(choice("Main", "Quality", {"Eco", "High"}, 1));
        ix.fType = add(choice("Filter", "Filter Type", {"Off", "LP 12", "LP 24", "HP 12", "BP 12", "Notch"}, 0));
        ix.fCut = add(logp("Filter", "Cutoff", 20.f, 20000.f, 12000.f, "Hz", 0));
        ix.fRes = add(lin("Filter", "Resonance", 0.f, 1.f, 0.1f));
        ix.fEnv = add(lin("Filter", "Filter Env", -1.f, 1.f, 0.f));
        ix.fKey = add(lin("Filter", "Filter Key Track", 0.f, 1.f, 0.f));
        static const char* en[3] = {"Volume Envelope", "Filter Envelope", "Pitch Envelope"}; static const char* pre[3] = {"Amp ", "Flt ", "Pit "};
        static const float ed[3][6] = {{0.f, 0.5f, 0.f, 5000.f, 1.f, 120.f}, {0.f, 0.5f, 0.f, 400.f, 0.f, 200.f}, {0.f, 0.5f, 0.f, 300.f, 0.f, 200.f}};
        for (int e = 0; e < 3; e++) {
            const std::string pe = pre[e];
            ix.env[e].delay = add(pw(en[e], (pe + "Delay").c_str(), 0.f, 5000.f, ed[e][0], 3.f, "ms", 0));
            ix.env[e].a = add(pw(en[e], (pe + "Attack").c_str(), 0.f, 10000.f, ed[e][1], 3.f, "ms", 1));
            ix.env[e].h = add(pw(en[e], (pe + "Hold").c_str(), 0.f, 5000.f, ed[e][2], 3.f, "ms", 0));
            ix.env[e].d = add(pw(en[e], (pe + "Decay").c_str(), 1.f, 15000.f, ed[e][3], 3.f, "ms", 0));
            ix.env[e].s = add(lin(en[e], (pe + "Sustain").c_str(), 0.f, 1.f, ed[e][4]));
            ix.env[e].r = add(pw(en[e], (pe + "Release").c_str(), 1.f, 20000.f, ed[e][5], 3.f, "ms", 0));
        }
        ix.pitEnv = add(lin("Pitch Envelope", "Pitch Env", -24.f, 24.f, 0.f, "st", 1));
        ix.lfoShape = add(choice("LFO", "LFO Shape", {"Sine", "Triangle", "Saw", "Square", "Sample & Hold", "Smooth Random"}, 0));
        ix.lfoRate = add(logp("LFO", "LFO Rate", 0.03f, 30.f, 4.f, "Hz", 2));
        ix.lfoSync = add(choice("LFO", "LFO Sync", {"Free", "1/1", "1/2", "1/4", "1/8", "1/16"}, 0));
        ix.lfoDelay = add(pw("LFO", "LFO Delay", 0.f, 5000.f, 0.f, 2.f, "ms", 0));
        ix.lfoRetrig = add(toggle("LFO", "LFO Retrigger", true));
        ix.lfoPitch = add(lin("LFO", "LFO Pitch", -12.f, 12.f, 0.f, "st", 1));
        ix.lfoVol = add(lin("LFO", "LFO Volume", 0.f, 1.f, 0.f));
        ix.lfoPan = add(lin("LFO", "LFO Pan", -1.f, 1.f, 0.f));
        ix.lfoCut = add(lin("LFO", "LFO Cutoff", -1.f, 1.f, 0.f));
    }
    const char* name() const override { return "Zedok Sampler"; }
    bool isInstrument() const override { return true; }

    void prepare(double sr, int maxFrames) override { sr_ = sr; mixL_.assign(maxFrames, 0.f); mixR_.assign(maxFrames, 0.f); reset(); }
    void reset() override { for (auto& v : voices_) v = Voice(); monoStack_.clear(); lastNote_ = -1.f; nHead_ = 0; }

    // state: "sample" = path of the WAV file
    bool setState(const std::string& key, const std::string& value) override {
        if (key != "sample") return false;
        if (value.empty()) { samplePath_.clear(); std::atomic_store(&buf_, std::shared_ptr<const SampBuf>()); return true; }
        std::vector<float> st; int rate = 0;
        if (!zedok::readWav(value, st, rate) || st.size() < 2 * 16 || rate <= 0) return false;
        auto b = std::make_shared<SampBuf>(); const size_t n = st.size() / 2; b->L.resize(n); b->R.resize(n);
        for (size_t i = 0; i < n; i++) { b->L[i] = st[i * 2]; b->R[i] = st[i * 2 + 1]; }
        b->rate = rate; b->name = value; b->finish();
        keep_[keepW_++ % 3] = std::atomic_load(&buf_);   // the previous sample stays alive a little: the audio thread may still hold it
        std::atomic_store(&buf_, std::shared_ptr<const SampBuf>(b)); samplePath_ = value;
        return true;
    }
    std::string getState(const std::string& key) const override { return key == "sample" ? samplePath_ : ""; }
    std::vector<std::string> stateKeys() const override { return {"sample"}; }

    int telemetry(int what, float* out, int cap) override {
        if (what == 0) { auto b = std::atomic_load(&buf_); if (!b || cap < 256) return 0; for (int i = 0; i < 256; i++) out[i] = b->overview[i]; return 256; }
        if (what == 1) { const int k = std::min(nHead_, cap); for (int i = 0; i < k; i++) out[i] = heads_[i]; return k; }       // playheads, 0..1 of the whole sample
        if (what == 2 && cap >= 2) { auto b = std::atomic_load(&buf_); out[0] = b ? (float)(b->n / b->rate) : 0.f; out[1] = b ? b->peak : 0.f; return 2; }
        return 0;
    }

    void process(const ProcessCtx& c, const float* vals) override {
        readParams(vals);
        const int n = c.frames; if (n > (int)mixL_.size()) return;
        std::fill(mixL_.begin(), mixL_.begin() + n, 0.f); std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
        tempo_ = c.tempo > 20.0 ? c.tempo : 120.0;
        cur_ = std::atomic_load(&buf_);
        int pos = 0, ei = 0;
        while (pos < n) {
            while (ei < c.nEvents && (int)c.events[ei].offset <= pos) handleEvent(c.events[ei++]);
            int end = n; if (ei < c.nEvents) end = std::min(end, std::max(pos + 1, (int)c.events[ei].offset));
            while (pos < end) { const int len = std::min(end - pos, kBlock); renderBlock(pos, len); pos += len; }
        }
        const float g = dbToLin(P_.volume);
        for (int i = 0; i < n; i++) { c.outL[i] = softOut(mixL_[i] * g); c.outR[i] = softOut(mixR_[i] * g); }
        int k = 0; if (cur_ && cur_->n > 0) for (auto& v : voices_) if (v.active && k < 32) heads_[k++] = (float)clampf((float)(v.headFrame / cur_->n), 0.f, 1.f);
        nHead_ = k;
    }

private:
    // ------------------------------------------------------------------ parameters
    struct EnvIx { int delay, a, h, d, s, r; };
    struct Ix { int root, start, end, loopMode, loopStart, loopEnd, loopFade, reverse, normalize, fadeIn, fadeOut, track, oneShot, slices, timeMode, stretch, sbpm, sync, grain,
                volume, pan, velSens, coarse, fine, bendRange, bend, poly, mono, glide, cutSelf, quality, fType, fCut, fRes, fEnv, fKey, pitEnv,
                lfoShape, lfoRate, lfoSync, lfoDelay, lfoRetrig, lfoPitch, lfoVol, lfoPan, lfoCut; EnvIx env[3]; } ix;
    struct EnvP { int delay = 0, hold = 0; float aInc = 1.f, dCoef = 0.01f, sus = 1.f, rCoef = 0.01f; };
    struct Params {
        float root, start, end; int loopMode; float loopStart, loopEnd, loopFade; bool reverse, normalize; float fadeIn, fadeOut; bool track, oneShot; int slices; int timeMode; float stretch, sbpm; bool sync; float grain;
        float volume, pan, velSens, coarse, fine, bendRange, bend; int poly, mono; float glide; bool cutSelf, hq; int fType; float fCut, fRes, fEnv, fKey, pitEnv;
        int lfoShape; float lfoRate; int lfoSync; float lfoDelay; bool lfoRetrig; float lfoPitch, lfoVol, lfoPan, lfoCut; EnvP env[3];
    } P_{};
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    int idx(const float* v, int i) const { return (int)(specs_[i].toReal(v[i]) + 0.5f); }
    void readParams(const float* v) {
        Params& p = P_;
        p.root = real(v, ix.root); p.start = real(v, ix.start); p.end = real(v, ix.end); p.loopMode = idx(v, ix.loopMode); p.loopStart = real(v, ix.loopStart); p.loopEnd = real(v, ix.loopEnd); p.loopFade = real(v, ix.loopFade) * 0.001f;
        p.reverse = real(v, ix.reverse) > 0.5f; p.normalize = real(v, ix.normalize) > 0.5f; p.fadeIn = real(v, ix.fadeIn) * 0.001f; p.fadeOut = real(v, ix.fadeOut) * 0.001f;
        p.track = real(v, ix.track) > 0.5f; p.oneShot = real(v, ix.oneShot) > 0.5f; { static const int sl[] = {0, 2, 4, 8, 16, 32}; p.slices = sl[idx(v, ix.slices)]; }
        p.timeMode = idx(v, ix.timeMode); p.stretch = real(v, ix.stretch); p.sbpm = real(v, ix.sbpm); p.sync = real(v, ix.sync) > 0.5f; p.grain = real(v, ix.grain) * 0.001f;
        p.volume = real(v, ix.volume); p.pan = real(v, ix.pan); p.velSens = real(v, ix.velSens); p.coarse = real(v, ix.coarse); p.fine = real(v, ix.fine); p.bendRange = real(v, ix.bendRange); p.bend = real(v, ix.bend);
        { static const int po[] = {1, 2, 4, 8, 16, 32}; p.poly = po[idx(v, ix.poly)]; } p.mono = idx(v, ix.mono); p.glide = real(v, ix.glide); p.cutSelf = real(v, ix.cutSelf) > 0.5f; p.hq = idx(v, ix.quality) == 1;
        p.fType = idx(v, ix.fType); p.fCut = real(v, ix.fCut); p.fRes = real(v, ix.fRes); p.fEnv = real(v, ix.fEnv); p.fKey = real(v, ix.fKey); p.pitEnv = real(v, ix.pitEnv);
        for (int e = 0; e < 3; e++) {
            EnvP& q = p.env[e]; const float sr = (float)sr_;
            q.delay = (int)(real(v, ix.env[e].delay) * 0.001f * sr); q.hold = (int)(real(v, ix.env[e].h) * 0.001f * sr);
            q.aInc = 1.f / std::max(1.f, real(v, ix.env[e].a) * 0.001f * sr); q.sus = real(v, ix.env[e].s);
            q.dCoef = 1.f - std::exp(-4.6f / std::max(1.f, real(v, ix.env[e].d) * 0.001f * sr)); q.rCoef = 1.f - std::exp(-4.6f / std::max(1.f, real(v, ix.env[e].r) * 0.001f * sr));
        }
        p.lfoShape = idx(v, ix.lfoShape); p.lfoRate = real(v, ix.lfoRate); p.lfoSync = idx(v, ix.lfoSync); p.lfoDelay = real(v, ix.lfoDelay) * 0.001f; p.lfoRetrig = real(v, ix.lfoRetrig) > 0.5f;
        p.lfoPitch = real(v, ix.lfoPitch); p.lfoVol = real(v, ix.lfoVol); p.lfoPan = real(v, ix.lfoPan); p.lfoCut = real(v, ix.lfoCut);
    }
    static inline float softOut(float x) { const float a = std::fabs(x); if (a <= 0.9f) return x; const float y = 0.9f + 0.1f * fastTanh((a - 0.9f) * 10.f); return x < 0 ? -y : y; }

    // ------------------------------------------------------------------ voices
    struct EnvS {
        int st = 0, cnt = 0; float v = 0.f; float invRel = 1.f;   // 0 idle, 1 delay, 2 attack, 3 hold, 4 decay, 5 sustain, 6 release; invRel: 1 / the note's release scale
        void on() { st = 1; cnt = 0; }
        void off() { if (st) st = 6; }
        void kill() { st = 0; v = 0.f; cnt = 0; }
        inline float tick(const EnvP& p) {
            switch (st) {
            case 0: return 0.f;
            case 1: if (cnt++ >= p.delay) st = 2; break;
            case 2: v += p.aInc; if (v >= 1.f) { v = 1.f; st = 3; cnt = 0; } break;
            case 3: if (cnt++ >= p.hold) st = 4; break;
            case 4: v += (p.sus - v) * p.dCoef; if (std::fabs(v - p.sus) < 1e-4f) { v = p.sus; st = 5; } break;
            case 5: v = p.sus; break;
            default: v -= v * p.rCoef * invRel; if (v < 1e-5f) { v = 0.f; st = 0; } break;
            }
            return v;
        }
    };
    struct SGrain { double u = 0.0; int age = 0, len = 1; bool on = false; };
    struct Voice {
        bool active = false, gate = false, killing = false; int note = 60, slice = -1; float vel = 1.f; uint64_t age = 0; float curNote = 60.f;
        double u = 0.0; int dir = 1; double ap = 0.0; SGrain g[3]; int spawn = 0; double headFrame = 0.0; float killGain = 1.f;
        EnvS env[3]; float lfoPh = 0.f, lfoSh = 0.f, lfoSm = 0.f, lfoT = 0.f; float envF = 0.f, envP = 0.f; SVF f[2], f2[2]; NoteMod mod; bool sliding = false;
    };
    Voice voices_[kMaxVoices];
    std::vector<int> monoStack_; float lastNote_ = -1.f; uint64_t age_ = 0; float freeLfo_ = 0.f; Rng rng_{4242u};
    std::vector<float> mixL_, mixR_; double tempo_ = 120.0;
    std::shared_ptr<const SampBuf> buf_, cur_, keep_[3]; int keepW_ = 0; std::string samplePath_;
    float heads_[32] = {}; int nHead_ = 0;

    static inline float sinT(float ph) { return std::sin(kTwoPi * ph); }
    float lfoShape(int shape, float ph, Voice& v, bool wrapped) {
        switch (shape) {
        case 0: return sinT(ph); case 1: return 4.f * std::fabs(ph - 0.5f) - 1.f; case 2: return 2.f * ph - 1.f; case 3: return ph < 0.5f ? 1.f : -1.f;
        case 4: if (wrapped) v.lfoSh = rng_.bi(); return v.lfoSh;
        default: { if (wrapped) { v.lfoSm = v.lfoSh; v.lfoSh = rng_.bi(); } const float t = ph * ph * (3.f - 2.f * ph); return v.lfoSm + (v.lfoSh - v.lfoSm) * t; } }
    }

    bool startVoice(Voice& v, int note, int vel, bool retrigger, const NoteMod& mod = NoteMod()) {
        int slice = -1;
        if (P_.slices > 0) { slice = note - (int)(P_.root + 0.5f); if (slice < 0 || slice >= P_.slices) return false; }
        const bool was = v.active; v.active = true; v.gate = true; v.killing = false; v.killGain = 1.f; v.note = note; v.slice = slice; v.vel = vel / 127.f; v.age = ++age_;
        v.curNote = (P_.glide > 0.5f && lastNote_ >= 0.f) ? lastNote_ : (float)note;
        v.mod = mod; v.sliding = false; for (auto& e : v.env) e.invRel = 1.f / mod.relScale;
        if (retrigger || !was) {
            v.u = 0.0; v.dir = 1; v.ap = 0.0; v.spawn = 0; for (auto& g : v.g) g.on = false;
            for (int e = 0; e < 3; e++) { if (!was) v.env[e].kill(); v.env[e].on(); }
            v.lfoPh = P_.lfoRetrig ? 0.f : freeLfo_; v.lfoT = 0.f; v.lfoSh = rng_.bi(); v.lfoSm = v.lfoSh;
            if (!was) { for (auto& f : v.f) f.reset(); for (auto& f : v.f2) f.reset(); }
        }
        lastNote_ = (float)note;
        return true;
    }
    void releaseVoice(Voice& v) { v.gate = false; for (auto& e : v.env) e.off(); }
    Voice* allocVoice() {
        const int limit = std::max(1, std::min(P_.poly, kMaxVoices)); int cnt = 0; for (auto& v : voices_) if (v.active) cnt++;
        for (auto& v : voices_) if (!v.active && cnt < limit) return &v;
        Voice* best = nullptr; float bl = 1e9f; for (auto& v : voices_) if (v.active && !v.gate && v.env[0].v < bl) { bl = v.env[0].v; best = &v; } if (best) return best;
        for (auto& v : voices_) if (v.active && (!best || v.age < best->age)) best = &v; return best;
    }
    bool supportsSlide() const override { return true; }
    void doNoteOn(int note, int vel, const NoteMod& mod = NoteMod()) {
        if (mod.slide) {   // slide: the most recently started held voice glides to the new pitch (no retrigger)
            Voice* best = nullptr; for (auto& v : voices_) if (v.active && v.gate && (!best || v.age > best->age)) best = &v;
            if (best) { best->note = note; best->sliding = true; lastNote_ = (float)note; return; }
        }
        if (P_.mono != 0) {
            monoStack_.erase(std::remove(monoStack_.begin(), monoStack_.end(), note), monoStack_.end()); monoStack_.push_back(note);
            Voice& v = voices_[0]; const bool legato = P_.mono == 2 && v.active && v.gate;
            for (int i = 1; i < kMaxVoices; i++) if (voices_[i].active) releaseVoice(voices_[i]);
            startVoice(v, note, vel, !legato, mod); return;
        }
        if (P_.cutSelf) for (auto& v : voices_) if (v.active && v.note == note) { v.killing = true; }   // the old voice of this key fades out in a few ms
        if (Voice* v = allocVoice()) { if (v->active && !v->killing) v->env[0].kill(); *v = Voice(); startVoice(*v, note, vel, true, mod); }
    }
    void doNoteOff(int note) {
        const bool ignore = P_.oneShot && P_.loopMode == 0;
        if (P_.mono != 0) {
            monoStack_.erase(std::remove(monoStack_.begin(), monoStack_.end(), note), monoStack_.end()); Voice& v = voices_[0];
            if (!monoStack_.empty()) { if (v.note == note) startVoice(v, monoStack_.back(), (int)(v.vel * 127), P_.mono != 2, v.mod); return; }
            if (v.active && v.note == note && !ignore) releaseVoice(v);
            return;
        }
        if (ignore) return;
        for (auto& v : voices_) if (v.active && v.gate && v.note == note) releaseVoice(v);
    }
    void handleEvent(const ZkEvent& e) {
        if (e.type == ZK_ALL_NOTES_OFF) { for (auto& v : voices_) if (v.active) { if (e.pitch == 1) v.killing = true; else releaseVoice(v); } monoStack_.clear(); return; }
        if (e.type == ZK_NOTE_ON) { if (e.velocity == 0) doNoteOff(e.pitch); else doNoteOn(e.pitch, e.velocity, NoteMod::from(e)); } else if (e.type == ZK_NOTE_OFF) doNoteOff(e.pitch);
    }

    // ------------------------------------------------------------------ rendering
    static inline void readFrame(const SampBuf& b, double p, bool hq, float& l, float& r) {
        const int n = b.n; const int i = (int)std::floor(p); const float f = (float)(p - i);
        auto at = [&](const std::vector<float>& x, int k) { return (k < 0 || k >= n) ? 0.f : x[k]; };
        if (!hq) { const float a0 = at(b.L, i), a1 = at(b.L, i + 1), c0 = at(b.R, i), c1 = at(b.R, i + 1); l = a0 + (a1 - a0) * f; r = c0 + (c1 - c0) * f; return; }
        auto herm = [&](const std::vector<float>& x) {
            const float y0 = at(x, i - 1), y1 = at(x, i), y2 = at(x, i + 1), y3 = at(x, i + 2);
            const float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
            return ((c3 * f + c2) * f + c1) * f + y1;
        };
        l = herm(b.L); r = herm(b.R);
    }

    void renderBlock(int pos, int len) {
        const float dtb = len / (float)sr_;
        freeLfo_ += lfoHz() * dtb; freeLfo_ -= std::floor(freeLfo_);
        for (auto& v : voices_) if (v.active) renderVoice(v, pos, len);
    }
    float lfoHz() const {
        static const float beats[] = {0.f, 4.f, 2.f, 1.f, 0.5f, 0.25f};   // beats per cycle
        return P_.lfoSync == 0 ? P_.lfoRate : (float)(tempo_ / 60.0) / beats[P_.lfoSync];
    }

    void renderVoice(Voice& v, int pos, int len) {
        const Params& p = P_; const SampBuf* b = cur_.get();
        if (!b || b->n < 2) { v.active = false; return; }
        const float dtb = len / (float)sr_;
        // region (virtual coordinates run along the playing direction: 0 .. L)
        double S = (double)p.start * b->n, E = (double)p.end * b->n; if (S > E) std::swap(S, E); if (E - S < 16.0) E = std::min((double)b->n, S + 16.0); if (E - S < 16.0) S = std::max(0.0, E - 16.0);
        if (p.slices > 0 && v.slice >= 0) { const double w = (E - S) / p.slices; S += w * v.slice; E = S + w; }
        const double L = E - S;
        double ls = clampf(p.loopStart, 0.f, 1.f) * L, le = clampf(p.loopEnd, 0.f, 1.f) * L; if (le - ls < 16.0) { ls = 0.0; le = L; }
        const int loopMode = p.slices > 0 ? 0 : p.loopMode;
        const double xf = std::min((double)p.loopFade * b->rate, (le - ls) * 0.5);
        const bool rev = p.reverse;
        // glide
        const float glideMs = v.sliding ? std::max(p.glide, 70.f) : p.glide;   // a slide glides in the Glide time, at least 70 ms
        if (glideMs > 0.5f) { const float c = 1.f - std::exp(-dtb / std::max(0.0005f, glideMs * 0.001f * 0.35f)); v.curNote += ((float)v.note - v.curNote) * c; } else v.curNote = (float)v.note;
        // lfo
        const float hz = lfoHz(); const float prevPh = v.lfoPh; v.lfoPh += hz * dtb; const bool wrapped = v.lfoPh >= 1.f; v.lfoPh -= std::floor(v.lfoPh); (void)prevPh; v.lfoT += dtb;
        const float lfoFade = p.lfoDelay > 0.001f ? std::min(1.f, v.lfoT / p.lfoDelay) : 1.f;
        const float lfo = lfoShape(p.lfoShape, v.lfoPh, v, wrapped) * lfoFade;
        // pitch
        const bool track = p.track && p.slices == 0;
        float semis = (track ? (v.curNote - p.root) : 0.f) + p.coarse + p.fine * 0.01f + v.mod.fine * 0.01f + p.bend * p.bendRange + v.envP * p.pitEnv + lfo * p.lfoPitch;
        const double ratio = std::exp2((double)semis / 12.0);
        double tr = p.stretch; if (p.sync && p.sbpm > 1.f) tr *= tempo_ / p.sbpm;
        const bool stretchMode = p.timeMode == 1;
        const double base = b->rate / sr_;
        const double rate = base * ratio * (stretchMode ? 1.0 : tr);   // read speed in source frames per output sample
        const double apRate = base * tr;                                // stretch mode: speed of the analysis head
        const int gLen = std::max(64, (int)(p.grain * (float)sr_)); const int hop = gLen / 2;
        // filter
        const bool filt = p.fType != 0;
        if (filt) {
            const float keyOct = (v.curNote - 60.f) / 12.f;
            const float cut = clampf(p.fCut * std::exp2(keyOct * p.fKey + v.envF * p.fEnv * 6.f + lfo * p.lfoCut * 3.f + v.mod.cutoff * 3.f), 20.f, 20000.f);
            const float fres = clampf(p.fRes + v.mod.res * 0.5f, 0.f, 1.f);
            for (int c = 0; c < 2; c++) { v.f[c].set(sr_, cut, fres); v.f2[c].set(sr_, cut, fres); }
        }
        // pan, gain
        const float pan = clampf(p.pan + lfo * p.lfoPan + v.mod.pan, -1.f, 1.f);
        const float gl = std::cos((pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f, gr = std::sin((pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f;
        const float velG = (1.f - p.velSens) + p.velSens * v.vel;
        const float normG = p.normalize && b->peak > 1e-4f ? 1.f / b->peak : 1.f;
        const float tremolo = 1.f - p.lfoVol * (0.5f - 0.5f * lfo);
        const float fin = std::max(32.f, p.fadeIn * (float)b->rate), fout = std::max(32.f, p.fadeOut * (float)b->rate);
        const bool ignoreOff = p.oneShot && loopMode == 0;
        const float killStep = 1.f / (0.003f * (float)sr_);
        for (int k = 0; k < len; k++) {
            float l = 0.f, r = 0.f; bool ended = false;
            auto actual = [&](double u) { return rev ? E - 1.0 - u : S + u; };
            auto edge = [&](double u) { return (float)std::min(1.0, std::min(u / fin, (L - u) / fout)); };
            if (!stretchMode) {
                if (v.u >= L || v.u < 0.0) ended = true;
                else {
                    readFrame(*b, actual(v.u), p.hq, l, r);
                    if (loopMode == 1 && xf > 1.0 && v.u > le - xf) { const double w = (v.u - (le - xf)) / xf; float l2, r2; readFrame(*b, actual(std::max(0.0, v.u - (le - ls))), p.hq, l2, r2); l = l * (1.f - (float)w) + l2 * (float)w; r = r * (1.f - (float)w) + r2 * (float)w; }
                    const float e = std::max(0.f, edge(v.u)); l *= e; r *= e; v.headFrame = actual(v.u);
                    v.u += v.dir * rate;
                    if (loopMode == 1 || (loopMode == 3 && v.gate)) { if (v.u >= le) { v.u -= (le - ls); if (v.u >= le) v.u = ls; } }
                    else if (loopMode == 2) { if (v.dir > 0 && v.u >= le) { v.u = 2.0 * le - v.u; v.dir = -1; } else if (v.dir < 0 && v.u < ls) { v.u = 2.0 * ls - v.u; v.dir = 1; } }
                }
            } else {
                if (--v.spawn <= 0) {   // a new grain every half grain, at the analysis head
                    for (auto& g : v.g) if (!g.on) { g.on = true; g.u = v.ap; g.age = 0; g.len = gLen; break; }
                    v.spawn = hop;
                }
                bool any = false;
                for (auto& g : v.g) if (g.on) {
                    any = true; double u = g.u;
                    if (loopMode != 0 && (loopMode != 3 || v.gate) && u >= le) u = ls + std::fmod(u - ls, le - ls);
                    if (u >= 0.0 && u < L) {
                        float gl2, gr2; readFrame(*b, actual(u), p.hq, gl2, gr2);
                        const float w = 0.5f - 0.5f * std::cos(kTwoPi * (float)g.age / (float)g.len); const float e = std::max(0.f, edge(u));
                        l += gl2 * w * e; r += gr2 * w * e;
                    }
                    g.u += rate; if (++g.age >= g.len) g.on = false;
                }
                v.headFrame = actual(clampf((float)v.ap, 0.f, (float)L));
                v.ap += apRate;
                if (loopMode != 0 && (loopMode != 3 || v.gate) && v.ap >= le) v.ap = ls + std::fmod(v.ap - ls, le - ls);
                if (v.ap >= L && !any) ended = true;
                if (v.ap >= L + (double)gLen * rate) ended = true;
            }
            const float a = v.env[0].tick(p.env[0]); v.envF = v.env[1].tick(p.env[1]); v.envP = v.env[2].tick(p.env[2]);
            if (v.env[0].st == 0 || ended) { if (ended || v.env[0].st == 0) { v.active = false; break; } }
            if (v.killing) { v.killGain -= killStep; if (v.killGain <= 0.f) { v.active = false; break; } }
            if (filt) {
                float xl = l, xr = r;
                if (p.fType == 1) { xl = v.f[0].tick(xl).lp; xr = v.f[1].tick(xr).lp; }
                else if (p.fType == 2) { xl = v.f2[0].tick(v.f[0].tick(xl).lp).lp; xr = v.f2[1].tick(v.f[1].tick(xr).lp).lp; }
                else if (p.fType == 3) { xl = v.f[0].tick(xl).hp; xr = v.f[1].tick(xr).hp; }
                else if (p.fType == 4) { xl = v.f[0].tick(xl).bp; xr = v.f[1].tick(xr).bp; }
                else { const auto o0 = v.f[0].tick(xl), o1 = v.f[1].tick(xr); xl = o0.lp + o0.hp; xr = o1.lp + o1.hp; }
                l = xl; r = xr;
            }
            const float g = a * velG * normG * tremolo * (v.killing ? v.killGain : 1.f);
            mixL_[pos + k] += l * g * gl; mixR_[pos + k] += r * g * gr;
        }
        (void)ignoreOff;
    }
};

}  // namespace zdsp
