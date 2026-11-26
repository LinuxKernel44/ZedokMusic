// Zedok Grain: a granular synthesizer. A source (your own sample, or one of the built-in textures) is played as a cloud of short
// overlapping grains. Position, size, density, pitch and their randomness shape the cloud; the keyboard plays it polyphonically
// or as a mono / legato voice. Each voice has an amplitude envelope, a filter, a second envelope, two LFOs and a modulation matrix,
// and the instrument ends in a delay and a reverb.
#pragma once
#include <atomic>
#include <memory>
#include <thread>
#include "fx.h"
#include "processor.h"
#include "../../app/src/main/cpp/wav.h"

namespace zdsp {

struct GrainBuf {
    std::vector<float> L, R; int n = 0; double rate = 48000.0; float root = 60.f; std::string name; std::vector<float> overview;   // overview: 256 peak values 0..1
    void normalize(float rms) { double e = 0; for (int i = 0; i < n; i++) e += (double)L[i] * L[i] + (double)R[i] * R[i]; const float cur = n ? (float)std::sqrt(e / (2.0 * n)) : 0.f; if (cur > 1e-6f) { const float g = rms / cur; for (int i = 0; i < n; i++) { L[i] *= g; R[i] *= g; } } finish(); }
    void finish() { n = (int)L.size(); overview.assign(256, 0.f); for (int i = 0; i < 256 && n > 0; i++) { const int a = (int)((int64_t)n * i / 256), b = std::max(a + 1, (int)((int64_t)n * (i + 1) / 256)); float m = 0.f; for (int k = a; k < b && k < n; k++) m = std::max(m, std::max(std::fabs(L[k]), std::fabs(R[k]))); overview[i] = m; } }
};

class Grain : public Processor {
public:
    static constexpr int kMaxVoices = 8;
    static constexpr int kMaxGrains = 48;
    static constexpr int kSlots = 6;
    static constexpr int kBlock = 16;
    enum Src { SrcNone, SrcLfo1, SrcLfo2, SrcEnv2, SrcVelocity, SrcKey, SrcWheel, SrcRandom, kNumSrc };
    enum Dst { DstNone, DstPosition, DstSize, DstDensity, DstPitch, DstPosSpread, DstCutoff, DstRes, DstPanSpread, DstLevel, DstScan, DstSkew, DstPitchJit, kNumDst };

    Grain() {
        static const char* kSrc[] = {"None", "LFO 1", "LFO 2", "Env 2", "Velocity", "Key", "Mod Wheel", "Random"};
        static const char* kDst[] = {"None", "Position", "Size", "Density", "Pitch", "Position Spread", "Cutoff", "Resonance", "Pan Spread", "Volume", "Scan Speed", "Skew", "Pitch Jitter"};
        std::vector<std::string> srcs(kSrc, kSrc + kNumSrc), dsts(kDst, kDst + kNumDst);
        ix.source = add(choice("Source", "Source", {"Sample", "Saw Cloud", "Choir", "Glass", "Noise Texture", "Organ Drone", "Metal Pulses"}, 1));
        ix.root = add(lin("Source", "Root Note", 24.f, 96.f, 60.f, "", 0));
        ix.track = add(lin("Source", "Pitch Track", 0.f, 1.f, 1.f));
        ix.pos = add(lin("Grain", "Position", 0.f, 1.f, 0.3f));
        ix.scan = add(lin("Grain", "Scan", -1.f, 1.f, 0.f, "", 2));
        ix.freeze = add(toggle("Grain", "Freeze"));
        ix.size = add(logp("Grain", "Size", 5.f, 800.f, 120.f, "ms", 0));
        ix.density = add(logp("Grain", "Density", 0.5f, 200.f, 20.f, "/s", 1));
        ix.pitch = add(lin("Grain", "Pitch", -24.f, 24.f, 0.f, "st", 1));
        ix.posSpread = add(lin("Grain", "Position Spread", 0.f, 1.f, 0.1f));
        ix.pitchJit = add(lin("Grain", "Pitch Jitter", 0.f, 12.f, 0.f, "st", 1));
        ix.panSpread = add(lin("Grain", "Pan Spread", 0.f, 1.f, 0.6f));
        ix.timeJit = add(lin("Grain", "Timing Jitter", 0.f, 1.f, 0.3f));
        ix.levelJit = add(lin("Grain", "Level Jitter", 0.f, 1.f, 0.1f));
        ix.dir = add(choice("Grain", "Direction", {"Forward", "Reverse", "Random"}, 0));
        ix.shape = add(choice("Grain", "Shape", {"Hann", "Gauss", "Triangle", "Trapezoid", "Percussive", "Reverse"}, 0));
        ix.skew = add(lin("Grain", "Skew", 0.05f, 0.95f, 0.5f));
        ix.volume = add(lin("Main", "Volume", -24.f, 6.f, 0.f, "dB", 1));
        ix.poly = add(choice("Main", "Polyphony", {"1", "2", "4", "6", "8"}, 3));
        ix.mono = add(choice("Main", "Play Mode", {"Poly", "Mono", "Legato"}, 0));
        ix.glide = add(pw("Main", "Glide", 0.f, 2000.f, 0.f, 2.5f, "ms", 0));
        ix.quality = add(choice("Main", "Quality", {"Eco", "High"}, 1));
        ix.wheel = add(lin("Main", "Mod Wheel", 0.f, 1.f, 0.f));
        ix.bend = add(lin("Main", "Pitch Bend", -1.f, 1.f, 0.f));
        ix.bendRange = add(lin("Main", "Bend Range", 1.f, 24.f, 2.f, "st", 0));
        ix.fType = add(choice("Filter", "Filter Type", {"Off", "LP 12", "LP 24", "HP 12", "BP 12"}, 0));
        ix.fCut = add(logp("Filter", "Cutoff", 20.f, 20000.f, 9000.f, "Hz", 0));
        ix.fRes = add(lin("Filter", "Resonance", 0.f, 1.f, 0.1f));
        ix.fEnv = add(lin("Filter", "Filter Env", -1.f, 1.f, 0.f));
        ix.fKey = add(lin("Filter", "Key Track", 0.f, 1.f, 0.f));
        const char* en[2] = {"Amp", "Env2"}; const char* pre[2] = {"Amp ", "Env2 "}; const float ed[2][4] = {{20.f, 400.f, 0.9f, 500.f}, {20.f, 500.f, 0.3f, 400.f}};
        for (int e = 0; e < 2; e++) {
            ix.env[e].a = add(pw(en[e], (std::string(pre[e]) + "Attack").c_str(), 0.5f, 10000.f, ed[e][0], 3.f, "ms", 1));
            ix.env[e].d = add(pw(en[e], (std::string(pre[e]) + "Decay").c_str(), 1.f, 15000.f, ed[e][1], 3.f, "ms", 0));
            ix.env[e].s = add(lin(en[e], (std::string(pre[e]) + "Sustain").c_str(), 0.f, 1.f, ed[e][2]));
            ix.env[e].r = add(pw(en[e], (std::string(pre[e]) + "Release").c_str(), 1.f, 20000.f, ed[e][3], 3.f, "ms", 0));
        }
        for (int l = 0; l < 2; l++) {
            const std::string p = "LFO" + std::to_string(l + 1) + " "; const std::string g = "LFO " + std::to_string(l + 1);
            ix.lfo[l].shape = add(choice(g.c_str(), (p + "Shape").c_str(), {"Sine", "Triangle", "Saw", "Square", "Sample & Hold", "Smooth Random"}, 0));
            ix.lfo[l].rate = add(logp(g.c_str(), (p + "Rate").c_str(), 0.03f, 30.f, 0.5f, "Hz", 2));
            ix.lfo[l].fade = add(pw(g.c_str(), (p + "Fade In").c_str(), 0.f, 5000.f, 0.f, 2.f, "ms", 0));
            ix.lfo[l].retrig = add(toggle(g.c_str(), (p + "Retrigger").c_str(), false));
        }
        for (int m = 0; m < kSlots; m++) {
            const std::string p = "Mod " + std::to_string(m + 1) + " ";
            ix.slot[m].src = add(choice("Modulation", (p + "Source").c_str(), srcs, 0));
            ix.slot[m].dst = add(choice("Modulation", (p + "Target").c_str(), dsts, 0));
            ix.slot[m].amt = add(lin("Modulation", (p + "Amount").c_str(), -1.f, 1.f, 0.f));
        }
        ix.dlMix = add(lin("Effects", "Delay Mix", 0.f, 1.f, 0.f));
        ix.dlTime = add(choice("Effects", "Delay Time", {"1/4", "1/4 .", "1/8", "1/8 .", "1/8 T", "1/16", "1/16 .", "1/16 T", "1/2"}, 3));
        ix.dlFb = add(lin("Effects", "Delay Feedback", 0.f, 0.95f, 0.4f));
        ix.rvMix = add(lin("Effects", "Reverb Mix", 0.f, 1.f, 0.2f));
        ix.rvSize = add(lin("Effects", "Reverb Size", 0.f, 1.f, 0.6f));
        ix.rvDecay = add(logp("Effects", "Reverb Decay", 0.3f, 12.f, 3.f, "s", 1));
        ix.rvDamp = add(lin("Effects", "Reverb Damping", 0.f, 1.f, 0.4f));
    }
    ~Grain() override { if (builder_.joinable()) builder_.join(); }
    const char* name() const override { return "Zedok Grain"; }
    bool isInstrument() const override { return true; }

    void prepare(double sr, int maxFrames) override {
        sr_ = sr; mixL_.assign(maxFrames, 0.f); mixR_.assign(maxFrames, 0.f);
        delay_.prepare(sr); reverb_.prepare(sr); buildEnvelopes();
        if (!built_) { built_ = true; builder_ = std::thread([this] { buildSources(); }); }   // the textures take a moment: build them off the UI thread; until then they are silent
        reset();
    }
    void reset() override { for (auto& v : voices_) v = Voice(); delay_.reset(); reverb_.reset(); monoStack_.clear(); lastNote_ = -1.f; nSnap_ = 0; }

    // ---- state: "sample" = path of a WAV file played by the "Sample" source
    bool setState(const std::string& key, const std::string& value) override {
        if (key != "sample") return false;
        if (value.empty()) { samplePath_.clear(); std::atomic_store(&srcs_[0], std::shared_ptr<const GrainBuf>()); return true; }
        std::vector<float> st; int rate = 0;
        if (!zedok::readWav(value, st, rate) || st.size() < 2 * 64) return false;
        if (rate != (int)sr_ && rate > 0) { st = zedok::resampleStereo(st, rate, (int)sr_); }
        auto b = std::make_shared<GrainBuf>(); const size_t n = st.size() / 2; b->L.resize(n); b->R.resize(n);
        for (size_t i = 0; i < n; i++) { b->L[i] = st[i * 2]; b->R[i] = st[i * 2 + 1]; }
        b->rate = sr_; b->root = 60.f; b->name = value; b->finish();
        keep_[keepW_++ % 3] = std::atomic_load(&srcs_[0]);   // the previous sample stays alive for a few loads: the audio thread may still hold it
        std::atomic_store(&srcs_[0], std::shared_ptr<const GrainBuf>(b)); samplePath_ = value;
        return true;
    }
    std::string getState(const std::string& key) const override { return key == "sample" ? samplePath_ : ""; }
    std::vector<std::string> stateKeys() const override { return {"sample"}; }

    void process(const ProcessCtx& c, const float* vals) override {
        readParams(vals);
        const int n = c.frames; if (n > (int)mixL_.size()) return;
        std::fill(mixL_.begin(), mixL_.begin() + n, 0.f); std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
        tempo_ = c.tempo > 20.0 ? c.tempo : 120.0;
        cur_ = std::atomic_load(&srcs_[std::min(P_.source, 6)]);
        int pos = 0, ei = 0;
        while (pos < n) {
            while (ei < c.nEvents && (int)c.events[ei].offset <= pos) handleEvent(c.events[ei++]);
            int end = n; if (ei < c.nEvents) end = std::min(end, std::max(pos + 1, (int)c.events[ei].offset));
            while (pos < end) { const int len = std::min(end - pos, kBlock); renderBlock(pos, len); pos += len; }
        }
        float* L = mixL_.data(); float* R = mixR_.data();
        if (P_.dlMix > 0.001f) { static const float beats[] = {1.f, 1.5f, 0.5f, 0.75f, 1.f / 3.f, 0.25f, 0.375f, 1.f / 6.f, 2.f}; const float ms = (float)(60000.0 / tempo_) * beats[P_.dlTime]; delay_.set(ms, ms, P_.dlFb, 0.4f, 150.f, 6500.f, 0.f, 0.4f, 0.3f, 0.f, P_.dlMix); delay_.process(L, R, n); }
        if (P_.rvMix > 0.001f) { reverb_.set(Reverb::Hall, P_.rvSize, P_.rvDecay, P_.rvDamp, 0.8f, 0.5f, 10.f, 100.f, 9500.f, 1.f, 0.f, P_.rvMix * 0.7f); reverb_.process(L, R, n); }
        const float g = dbToLin(P_.volume);
        for (int i = 0; i < n; i++) { c.outL[i] = softOut(L[i] * g); c.outR[i] = softOut(R[i] * g); }
        // snapshot of the sounding grains for the display
        int k = 0; const int total = cur_ ? cur_->n : 0;
        if (total > 0) for (auto& v : voices_) if (v.active) for (int gI = 0; gI < v.ng && k < 48; gI++) { snap_[k * 2] = (float)(std::fmod(v.g[gI].pos, (double)total) / total); snap_[k * 2 + 1] = std::fabs(v.g[gI].amp) * 0.f + (float)v.g[gI].len / (float)total; k++; }
        nSnap_ = k;
        if (!voices_[0].active) { /* nothing */ }
        lastScan_ = newestScan();
    }

    int telemetry(int what, float* out, int cap) override {
        if (what == 0) { auto b = std::atomic_load(&srcs_[std::min(P_.source, 6)]); if (!b || cap < 256) return 0; for (int i = 0; i < 256; i++) out[i] = b->overview[i]; return 256; }
        if (what == 1) { const int k = std::min(nSnap_, cap / 2); for (int i = 0; i < k * 2; i++) out[i] = snap_[i]; return k * 2; }
        if (what == 2 && cap >= 2) { int a = 0; for (auto& v : voices_) a += v.active ? 1 : 0; out[0] = lastScan_; out[1] = (float)a; return 2; }
        return 0;
    }

private:
    // ---------------------------------------------------------------- parameters
    struct EnvIx { int a, d, s, r; }; struct LfoIx { int shape, rate, fade, retrig; }; struct SlotIx { int src, dst, amt; };
    struct Ix { int source, root, track, pos, scan, freeze, size, density, pitch, posSpread, pitchJit, panSpread, timeJit, levelJit, dir, shape, skew, volume, poly, mono, glide, quality, wheel, bend, bendRange,
                fType, fCut, fRes, fEnv, fKey, dlMix, dlTime, dlFb, rvMix, rvSize, rvDecay, rvDamp; EnvIx env[2]; LfoIx lfo[2]; SlotIx slot[kSlots]; } ix;
    struct Params {
        int source; float root, track, pos, scan; bool freeze; float size, density, pitch, posSpread, pitchJit, panSpread, timeJit, levelJit; int dir, shape; float skew, volume; int poly, mono; float glide; bool hq; float wheel, bend, bendRange;
        int fType; float fCut, fRes, fEnv, fKey; float env[2][4]; struct L { int shape; float rate, fade; bool retrig; } lfo[2]; struct S { int src, dst; float amt; } slot[kSlots];
        float dlMix; int dlTime; float dlFb, rvMix, rvSize, rvDecay, rvDamp;
    } P_{};
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    int idx(const float* v, int i) const { return (int)(specs_[i].toReal(v[i]) + 0.5f); }
    void readParams(const float* v) {
        Params& p = P_;
        p.source = idx(v, ix.source); p.root = real(v, ix.root); p.track = real(v, ix.track); p.pos = real(v, ix.pos); p.scan = real(v, ix.scan); p.freeze = real(v, ix.freeze) > 0.5f;
        p.size = real(v, ix.size); p.density = real(v, ix.density); p.pitch = real(v, ix.pitch); p.posSpread = real(v, ix.posSpread); p.pitchJit = real(v, ix.pitchJit); p.panSpread = real(v, ix.panSpread);
        p.timeJit = real(v, ix.timeJit); p.levelJit = real(v, ix.levelJit); p.dir = idx(v, ix.dir); p.shape = idx(v, ix.shape); p.skew = real(v, ix.skew); p.volume = real(v, ix.volume);
        static const int polys[] = {1, 2, 4, 6, 8}; p.poly = polys[idx(v, ix.poly)]; p.mono = idx(v, ix.mono); p.glide = real(v, ix.glide); p.hq = idx(v, ix.quality) == 1; if (!p.hq) p.poly = std::min(p.poly, 4);
        p.wheel = real(v, ix.wheel); p.bend = real(v, ix.bend); p.bendRange = real(v, ix.bendRange);
        p.fType = idx(v, ix.fType); p.fCut = real(v, ix.fCut); p.fRes = real(v, ix.fRes); p.fEnv = real(v, ix.fEnv); p.fKey = real(v, ix.fKey);
        for (int e = 0; e < 2; e++) { p.env[e][0] = real(v, ix.env[e].a) * 0.001f; p.env[e][1] = real(v, ix.env[e].d) * 0.001f; p.env[e][2] = real(v, ix.env[e].s); p.env[e][3] = real(v, ix.env[e].r) * 0.001f; }
        for (int l = 0; l < 2; l++) { p.lfo[l].shape = idx(v, ix.lfo[l].shape); p.lfo[l].rate = real(v, ix.lfo[l].rate); p.lfo[l].fade = real(v, ix.lfo[l].fade) * 0.001f; p.lfo[l].retrig = real(v, ix.lfo[l].retrig) > 0.5f; }
        for (int m = 0; m < kSlots; m++) { p.slot[m].src = idx(v, ix.slot[m].src); p.slot[m].dst = idx(v, ix.slot[m].dst); p.slot[m].amt = real(v, ix.slot[m].amt); }
        p.dlMix = real(v, ix.dlMix); p.dlTime = idx(v, ix.dlTime); p.dlFb = real(v, ix.dlFb); p.rvMix = real(v, ix.rvMix); p.rvSize = real(v, ix.rvSize); p.rvDecay = real(v, ix.rvDecay); p.rvDamp = real(v, ix.rvDamp);
    }
    static inline float softOut(float x) { const float a = std::fabs(x); if (a <= 0.9f) return x; const float y = 0.9f + 0.1f * fastTanh((a - 0.9f) * 10.f); return x < 0 ? -y : y; }

    // ---------------------------------------------------------------- built-in sources and envelopes
    static constexpr int kEnvN = 512;
    float envTab_[6][kEnvN + 1] = {};
    void buildEnvelopes() {
        for (int i = 0; i <= kEnvN; i++) {
            const float x = (float)i / kEnvN;
            envTab_[0][i] = 0.5f - 0.5f * std::cos(kTwoPi * x);                             // Hann
            envTab_[1][i] = std::exp(-0.5f * std::pow((x - 0.5f) / 0.18f, 2.f));            // Gauss
            envTab_[2][i] = 1.f - std::fabs(2.f * x - 1.f);                                 // Triangle
            envTab_[3][i] = std::min(1.f, std::min(x / 0.25f, (1.f - x) / 0.25f));          // Trapezoid
            envTab_[4][i] = (x < 0.02f ? x / 0.02f : 1.f) * std::exp(-5.f * x);             // Percussive
            envTab_[5][i] = (x > 0.98f ? (1.f - x) / 0.02f : 1.f) * std::exp(-5.f * (1.f - x));   // Reverse percussive
        }
        for (int s = 0; s < 6; s++) { envTab_[s][0] = 0.f; if (s != 4) envTab_[s][kEnvN] = 0.f; }
    }
    // Rotating phasor: sin(w*i + ph) without calling sin() per sample (the sources are built on a background thread, but still, 55 M sin() calls is too many).
    struct Phasor { double c, s, cw, sw; Phasor(double w, double ph) : c(std::cos(ph)), s(std::sin(ph)), cw(std::cos(w)), sw(std::sin(w)) {} inline double next() { const double r = s; const double nc = c * cw - s * sw, ns = s * cw + c * sw; c = nc; s = ns; return r; } };
    void buildSources() {
        const int n = (int)(3.0 * sr_); const double f0 = 130.8128;   // C3
        auto mk = [&](const char* nm, float root) { auto b = std::make_shared<GrainBuf>(); b->L.assign(n, 0.f); b->R.assign(n, 0.f); b->rate = sr_; b->root = root; b->name = nm; return b; };
        auto fit = [&](double f) { return std::round(f * 3.0) / 3.0; };   // whole number of cycles in 3 s: the loop is seamless
        auto publish = [&](int slot, std::shared_ptr<GrainBuf> b) { b->finish(); b->normalize(0.2f); std::atomic_store(&srcs_[slot], std::shared_ptr<const GrainBuf>(b)); };
        // 1. Saw cloud: eight drifting detuned saws, band-limited by additive synthesis
        { auto b = mk("Saw Cloud", 48.f); Rng r(11);
          for (int ch = 0; ch < 2; ch++) for (int k = 0; k < 8; k++) {
              const double f = fit(f0 * std::pow(2.0, ((k - 3.5) * 0.0035 + (ch ? 0.0011 : 0.0)) * 8.0)), ph0 = r.uni() * 6.28; float* o = (ch ? b->R : b->L).data();
              for (int h = 1; h <= 24 && f * h < 9000.0; h++) { Phasor p(kTwoPi * fit(f * h) / sr_, ph0 + h); const float a = 0.12f / h; for (int i = 0; i < n; i++) o[i] += a * (float)p.next(); }
          } publish(1, b); }
        // 2. Choir: a harmonic series through moving a-o-u formants (the formant gain is evaluated every 64 samples and interpolated)
        { auto b = mk("Choir", 48.f); Rng r(22);
          for (int ch = 0; ch < 2; ch++) for (int v = 0; v < 3; v++) { const double f = fit(f0 * (1.0 + (v - 1) * 0.004 + (ch ? 0.002 : 0.0))), ph = r.uni() * 6.28; float* o = (ch ? b->R : b->L).data();
              for (int h = 1; h <= 40 && f * h < 6000.0; h++) { const double hz = fit(f * h); Phasor p(kTwoPi * hz / sr_, ph + h); float g0 = 0.f, g1 = 0.f; const float scale = (float)(0.06 / std::sqrt((double)h));
                  auto gain = [&](int i) { const double t = (double)i / n; const double F1 = 650.0 - 250.0 * (0.5 - 0.5 * std::cos(kTwoPi * t)), F2 = 1100.0 + 500.0 * std::sin(kTwoPi * t * 2.0); return (float)(1.0 / (1.0 + std::pow((hz - F1) / 110.0, 2.0)) + 0.5 / (1.0 + std::pow((hz - F2) / 160.0, 2.0))); };
                  g1 = gain(0); for (int i = 0; i < n; i++) { if ((i & 63) == 0) { g0 = g1; g1 = gain(std::min(n - 1, i + 64)); } const float g = g0 + (g1 - g0) * ((i & 63) / 64.f); o[i] += scale * g * (float)p.next(); } } }
          publish(2, b); }
        // 3. Glass: inharmonic partials with slow independent tremolo
        { auto b = mk("Glass", 60.f); Rng r(33); static const double rat[] = {1.0, 2.76, 5.4, 8.93, 13.34, 18.64, 1.5, 3.9, 6.7};
          for (int ch = 0; ch < 2; ch++) for (int k = 0; k < 9; k++) { const double f = fit(261.63 * rat[k]) * (ch ? 1.002 : 1.0); if (f > 12000.0) continue; const double ph = r.uni() * 6.28; float* o = (ch ? b->R : b->L).data();
              Phasor p(kTwoPi * f / sr_, ph), lf(kTwoPi * fit(0.15 + 0.1 * k) / 3.0 / sr_ * 3.0 / 3.0 * 3.0, ph); for (int i = 0; i < n; i++) o[i] += (float)(0.1 / (1.0 + k * 0.4) * p.next() * (0.6 + 0.4 * lf.next())); }
          publish(3, b); }
        // 4. Noise texture: band-passed noise whose centre wanders
        { auto b = mk("Noise Texture", 60.f); Rng r(44); SVF f1[2];
          for (int i = 0; i < n; i++) for (int ch = 0; ch < 2; ch++) { if ((i & 15) == 0) { const double t = (double)i / n; const float cen = 300.f * std::pow(2.f, 3.f * (0.5f + 0.5f * (float)std::sin(kTwoPi * (t * (ch ? 2 : 3) + ch * 0.2)))); f1[ch].set(sr_, cen, 0.7f); } (ch ? b->R : b->L)[i] = f1[ch].tick(r.bi()).bp * 1.3f; }
          publish(4, b); }
        // 5. Organ drone: drawbars
        { auto b = mk("Organ Drone", 48.f); static const int hs[] = {1, 2, 3, 4, 6, 8}; static const float am[] = {1.f, 0.8f, 0.5f, 0.6f, 0.3f, 0.2f};
          for (int ch = 0; ch < 2; ch++) for (int k = 0; k < 6; k++) { Phasor p(kTwoPi * fit(f0 * hs[k] * (ch ? 1.0015 : 1.0)) / sr_, k * 0.7); float* o = (ch ? b->R : b->L).data(); for (int i = 0; i < n; i++) o[i] += (float)(0.12 * am[k] * p.next()); }
          publish(5, b); }
        // 6. Metal pulses: FM bursts at 5 Hz
        { auto b = mk("Metal Pulses", 60.f); const double rateHz = 5.0, fc = fit(220.0);
          for (int ch = 0; ch < 2; ch++) { Phasor pc(kTwoPi * fc / sr_, 0.0), pm(kTwoPi * fc * 1.41 / sr_, 0.0); float* o = (ch ? b->R : b->L).data();
              for (int i = 0; i < n; i++) { const double ph = std::fmod(i * rateHz / sr_ + (ch ? 0.5 : 0.0), 1.0); const double env = std::exp(-6.0 * ph); const double m = pm.next(); const double c = pc.c * std::cos(4.0 * env * m) + pc.s * std::sin(4.0 * env * m); pc.next(); o[i] = (float)(0.5 * env * c); } }
          publish(6, b); }
    }

    // ---------------------------------------------------------------- voices and grains
    struct GrainS { double pos = 0.0; float inc = 1.f; int age = 0, len = 1; float amp = 1.f, gl = .7f, gr = .7f, invLen = 1.f, invSkew = 2.f, invRest = 2.f; int shape = 0; float skew = .5f; };
    struct Voice {
        bool active = false, gate = false; int note = 60; float vel = 1.f; uint64_t age = 0; float curNote = 60.f, rnd = 0.f;
        GrainS g[kMaxGrains]; int ng = 0; float nextIn = 0.f; double scan = 0.0; float rawScan = 0.f;
        Adsr env[2]; float env2 = 0.f; float lfoPh[2] = {}, lfoVal[2] = {}, lfoSh[2] = {}, lfoSm[2] = {}, lfoT[2] = {}; SVF f[2], f2[2]; float cut = 1000.f; bool primed = false; Rng rng{777u};
    };
    inline float sinT(float ph) const { return std::sin(kTwoPi * ph); }

    void noteOnVoice(Voice& v, int note, int vel, bool retrigger) {
        const bool was = v.active; v.active = true; v.gate = true; v.note = note; v.vel = vel / 127.f; v.age = ++age_;
        v.curNote = (P_.glide > 0.5f && lastNote_ >= 0.f) ? lastNote_ : (float)note;
        if (retrigger || !was) {
            v.rnd = rng_.bi(); for (int e = 0; e < 2; e++) { v.env[e].gate(true); if (!was) v.env[e].v = 0.f; }
            for (int l = 0; l < 2; l++) { v.lfoPh[l] = P_.lfo[l].retrig ? 0.f : freeLfo_[l]; v.lfoT[l] = 0.f; v.lfoSh[l] = rng_.bi(); v.lfoSm[l] = v.lfoSh[l]; }
            if (!was) { v.ng = 0; v.nextIn = 0.f; v.scan = 0.0; v.f[0].reset(); v.f[1].reset(); v.f2[0].reset(); v.f2[1].reset(); v.primed = false; v.rng = Rng(0x1234567u + (uint32_t)age_ * 977u); }
            else { v.scan = 0.0; }
        }
        lastNote_ = (float)note;
    }
    Voice* allocVoice() {
        const int limit = std::max(1, std::min(P_.poly, kMaxVoices)); int cnt = 0; for (auto& v : voices_) if (v.active) cnt++;
        for (auto& v : voices_) if (!v.active && cnt < limit) return &v;
        Voice* best = nullptr; float bl = 1e9f; for (auto& v : voices_) if (v.active && !v.gate && v.env[0].v < bl) { bl = v.env[0].v; best = &v; } if (best) return best;
        for (auto& v : voices_) if (v.active && (!best || v.age < best->age)) best = &v; return best;
    }
    void doNoteOn(int note, int vel) {
        if (P_.mono != 0) {
            monoStack_.erase(std::remove(monoStack_.begin(), monoStack_.end(), note), monoStack_.end()); monoStack_.push_back(note);
            Voice& v = voices_[0]; const bool legato = P_.mono == 2 && v.active && v.gate;
            for (int i = 1; i < kMaxVoices; i++) if (voices_[i].active) { voices_[i].gate = false; for (auto& e : voices_[i].env) e.gate(false); }
            noteOnVoice(v, note, vel, !legato); return;
        }
        for (auto& v : voices_) if (v.active && v.gate && v.note == note) { noteOnVoice(v, note, vel, true); return; }
        if (Voice* v = allocVoice()) { v->env[0].kill(); noteOnVoice(*v, note, vel, true); }
    }
    void doNoteOff(int note) {
        if (P_.mono != 0) {
            monoStack_.erase(std::remove(monoStack_.begin(), monoStack_.end(), note), monoStack_.end()); Voice& v = voices_[0];
            if (!monoStack_.empty()) { if (v.note == note) noteOnVoice(v, monoStack_.back(), (int)(v.vel * 127), P_.mono != 2); return; }
            if (v.active && v.note == note) { v.gate = false; for (auto& e : v.env) e.gate(false); } return;
        }
        for (auto& v : voices_) if (v.active && v.gate && v.note == note) { v.gate = false; for (auto& e : v.env) e.gate(false); }
    }
    void handleEvent(const ZkEvent& e) {
        if (e.type == ZK_ALL_NOTES_OFF) { for (auto& v : voices_) if (v.active) { v.gate = false; for (auto& x : v.env) x.gate(false); } monoStack_.clear(); return; }
        if (e.type == ZK_NOTE_ON) { if (e.velocity == 0) doNoteOff(e.pitch); else doNoteOn(e.pitch, e.velocity); } else if (e.type == ZK_NOTE_OFF) doNoteOff(e.pitch);
    }
    float newestScan() const { const Voice* b = nullptr; for (auto& v : voices_) if (v.active && (!b || v.age > b->age)) b = &v; return b ? (float)std::fmod(std::max(0.0, (double)P_.pos + b->scan), 1.0) : P_.pos; }

    float lfoShape(int shape, float ph, Voice& v, int l, bool wrapped) {
        switch (shape) { case 0: return sinT(ph); case 1: return 4.f * std::fabs(ph - 0.5f) - 1.f; case 2: return 2.f * ph - 1.f; case 3: return ph < 0.5f ? 1.f : -1.f;
        case 4: if (wrapped) v.lfoSh[l] = rng_.bi(); return v.lfoSh[l];
        default: { if (wrapped) { v.lfoSm[l] = v.lfoSh[l]; v.lfoSh[l] = rng_.bi(); } const float t = ph * ph * (3.f - 2.f * ph); return v.lfoSm[l] + (v.lfoSh[l] - v.lfoSm[l]) * t; } }
    }

    void renderBlock(int pos, int len) {
        const float dtb = len / (float)sr_;
        for (int l = 0; l < 2; l++) { freeLfo_[l] += P_.lfo[l].rate * dtb; freeLfo_[l] -= std::floor(freeLfo_[l]); }
        for (auto& v : voices_) if (v.active) renderVoice(v, pos, len);
    }

    void spawnGrain(Voice& v, const GrainBuf& b, float basePos, float sizeMs, float density, float pitchSt, float posSpread, float pitchJit, float panSpread, float skew, float levelJit, float semitoneBase) {
        if (v.ng >= kMaxGrains) return;
        GrainS& g = v.g[v.ng++];
        const float sizeS = std::max(0.002f, sizeMs * 0.001f);
        g.len = std::max(16, (int)(sizeS * (float)sr_));
        float p = basePos + posSpread * (v.rng.uni() - 0.5f) * 1.0f; p -= std::floor(p);
        const float jit = pitchJit * (v.rng.uni() * 2.f - 1.f);
        const float ratio = std::pow(2.f, (semitoneBase + pitchSt + jit) / 12.f) * (float)(b.rate / sr_);
        bool rev = P_.dir == 1 || (P_.dir == 2 && v.rng.uni() < 0.5f);
        g.inc = rev ? -ratio : ratio;
        g.pos = (double)p * b.n; if (rev) g.pos += (double)g.len * ratio;
        g.age = 0; g.shape = P_.shape; g.skew = skew; g.invLen = 1.f / (float)g.len; g.invSkew = 0.5f / skew; g.invRest = 0.5f / (1.f - skew);
        const float norm = 1.f / std::sqrt(std::max(1.f, density * sizeS * 0.9f));
        g.amp = norm * (1.f - levelJit * v.rng.uni());
        const float pan = panSpread * (v.rng.uni() * 2.f - 1.f);
        g.gl = std::cos((pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f; g.gr = std::sin((pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f;
    }

    void renderVoice(Voice& v, int pos, int len) {
        const Params& p = P_; const GrainBuf* b = cur_.get();
        const float dtb = len / (float)sr_;
        for (int e = 0; e < 2; e++) v.env[e].set(sr_, p.env[e][0], p.env[e][1], p.env[e][2], p.env[e][3]);
        float srcv[kNumSrc] = {};
        for (int l = 0; l < 2; l++) {
            v.lfoT[l] += dtb; const float old = v.lfoPh[l]; (void)old; v.lfoPh[l] += p.lfo[l].rate * dtb; const bool wr = v.lfoPh[l] >= 1.f; if (wr) v.lfoPh[l] -= std::floor(v.lfoPh[l]);
            const float fade = p.lfo[l].fade > 0.001f ? std::min(1.f, v.lfoT[l] / p.lfo[l].fade) : 1.f; v.lfoVal[l] = lfoShape(p.lfo[l].shape, v.lfoPh[l], v, l, wr) * fade;
        }
        { float x = 0.f; for (int i = 0; i < len; i++) x = v.env[1].tick(); v.env2 = x; }
        srcv[SrcLfo1] = v.lfoVal[0]; srcv[SrcLfo2] = v.lfoVal[1]; srcv[SrcEnv2] = v.env2; srcv[SrcVelocity] = v.vel; srcv[SrcKey] = (v.curNote - 60.f) / 60.f; srcv[SrcWheel] = p.wheel; srcv[SrcRandom] = v.rnd;
        float dst[kNumDst] = {};
        for (int m = 0; m < kSlots; m++) if (p.slot[m].src && p.slot[m].dst) dst[p.slot[m].dst] += srcv[p.slot[m].src] * p.slot[m].amt;
        if (p.glide > 0.5f) { const float a = 1.f - std::exp(-dtb * 1000.f / std::max(p.glide, 1.f) * 3.f); v.curNote += ((float)v.note - v.curNote) * a; } else v.curNote = (float)v.note;

        // scan position
        const float scanSpeed = p.freeze ? 0.f : clampf(p.scan + dst[DstScan], -1.f, 1.f);
        const float lenSec = b ? std::max(0.05f, (float)b->n / (float)sr_) : 1.f;
        v.scan += (double)(scanSpeed * 4.f * dtb / lenSec);   // full scan = 4x real time
        v.scan -= std::floor(v.scan);
        float basePos = p.pos + dst[DstPosition] + (float)v.scan; basePos -= std::floor(basePos);
        const float sizeMs = clampf(p.size * std::pow(2.f, dst[DstSize] * 3.f), 3.f, 1500.f);
        const float density = clampf(p.density * std::pow(2.f, dst[DstDensity] * 3.f), 0.3f, p.hq ? 250.f : 120.f);
        const float pitchSt = p.pitch + dst[DstPitch] * 12.f + p.bend * p.bendRange;
        const float posSpread = clampf(p.posSpread + dst[DstPosSpread], 0.f, 1.f), pitchJit = clampf(p.pitchJit + dst[DstPitchJit] * 6.f, 0.f, 24.f), panSpread = clampf(p.panSpread + dst[DstPanSpread], 0.f, 1.f);
        const float skew = clampf(p.skew + dst[DstSkew] * 0.45f, 0.05f, 0.95f);
        const float keySemis = b ? (v.curNote - (p.source == 0 ? p.root : (p.root))) * p.track : 0.f;
        // grains: spawn on schedule
        if (b && b->n > 16) {
            const float interval = 1.f / density;
            float t = 0.f;
            while (v.nextIn <= t + dtb && v.ng < kMaxGrains) {
                const float jt = 1.f + p.timeJit * (-std::log(std::max(1e-3f, v.rng.uni())) - 1.f) * 0.7f;
                spawnGrain(v, *b, basePos, sizeMs, density, pitchSt, posSpread, pitchJit, panSpread, skew, p.levelJit, keySemis);
                v.nextIn += interval * std::max(0.15f, jt);
                if (v.nextIn <= t) v.nextIn = t + 1e-4f;
            }
            v.nextIn -= dtb; if (v.nextIn < -1.f) v.nextIn = 0.f;
        }
        // filter setup
        const float keyOct = (v.curNote - 60.f) / 12.f;
        float cut = clampf(p.fCut * std::exp2(keyOct * p.fKey + v.env2 * p.fEnv * 6.f + dst[DstCutoff] * 5.f), 20.f, 20000.f);
        const float res = clampf(p.fRes + dst[DstRes] * 0.5f, 0.f, 1.f);
        if (!v.primed) { v.cut = cut; v.primed = true; }
        const float cs = std::pow(cut / v.cut, 1.f / len); float curC = v.cut;
        const float lvl = std::max(0.f, 1.f + dst[DstLevel]); const float velG = 0.4f + 0.6f * v.vel;
        float* oL = mixL_.data() + pos; float* oR = mixR_.data() + pos;
        const float* Lp = b ? b->L.data() : nullptr; const float* Rp = b ? b->R.data() : nullptr; const int nI = b ? b->n : 0; const double nD = (double)nI;
        for (int i = 0; i < len; i++) {
            if ((i & 3) == 0 && p.fType != 0) { for (int c = 0; c < 2; c++) v.f[c].set(sr_, curC, res * 0.92f); if (p.fType == 2) for (int c = 0; c < 2; c++) v.f2[c].set(sr_, curC, res * 0.2f); }
            float l = 0.f, r = 0.f;
            if (nI < 2) v.ng = 0;
            for (int gI = 0; gI < v.ng; gI++) {
                GrainS& g = v.g[gI];
                if (g.age >= g.len) { v.g[gI] = v.g[--v.ng]; gI--; continue; }
                // envelope with skew warp
                float ph = (float)g.age * g.invLen; ph = ph < g.skew ? ph * g.invSkew : 0.5f + (ph - g.skew) * g.invRest;
                const float ef = ph * kEnvN; const int ei = std::min((int)ef, kEnvN - 1); const float* et = envTab_[g.shape]; const float env = et[ei] + (et[ei + 1] - et[ei]) * (ef - ei);
                // read the source (linear interpolation, wrapping)
                double sp = g.pos; if (sp >= nD || sp < 0.0) sp -= std::floor(sp / nD) * nD;   // a grain may outlive a source change or start far outside the buffer
                if (sp >= nD) sp = 0.0; g.pos = sp;
                const int i0 = (int)sp; const float fr = (float)(sp - i0); const int i1 = i0 + 1 >= nI ? 0 : i0 + 1;
                const float sl = Lp[i0] + (Lp[i1] - Lp[i0]) * fr, sr2 = Rp[i0] + (Rp[i1] - Rp[i0]) * fr;
                const float a = env * g.amp; l += sl * a * g.gl * 0.7f; r += sr2 * a * g.gr * 0.7f;
                g.pos += g.inc; g.age++;
            }
            switch (p.fType) {
            case 1: l = v.f[0].tick(l).lp; r = v.f[1].tick(r).lp; break;
            case 2: l = v.f2[0].tick(v.f[0].tick(l).lp).lp; r = v.f2[1].tick(v.f[1].tick(r).lp).lp; break;
            case 3: l = v.f[0].tick(l).hp; r = v.f[1].tick(r).hp; break;
            case 4: { auto a = v.f[0].tick(l); auto c = v.f[1].tick(r); l = a.bp * v.f[0].k * 0.7f; r = c.bp * v.f[1].k * 0.7f; break; }
            default: break;
            }
            const float amp = v.env[0].tick() * velG * lvl;
            oL[i] += l * amp; oR[i] += r * amp; curC *= cs;
        }
        v.cut = cut;
        if (!v.env[0].active()) v.active = false;
    }

    // ---------------------------------------------------------------- state
    std::thread builder_; bool built_ = false;
    std::shared_ptr<const GrainBuf> srcs_[7]; std::shared_ptr<const GrainBuf> cur_; std::shared_ptr<const GrainBuf> keep_[3]; int keepW_ = 0; std::string samplePath_;
    std::vector<float> mixL_, mixR_; Voice voices_[kMaxVoices]; Rng rng_{0xBEEF1u}; uint64_t age_ = 0; float freeLfo_[2] = {}; float lastNote_ = -1.f; double tempo_ = 120.0;
    std::vector<int> monoStack_; Delay delay_; Reverb reverb_; float snap_[96] = {}; int nSnap_ = 0; float lastScan_ = 0.f;
};

}  // namespace zdsp
