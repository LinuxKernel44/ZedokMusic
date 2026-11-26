// Zedok Drums: a 16-pad drum machine made of synthesized drum models (kicks, snares, claps, hats, toms, cymbals, percussion).
// Every pad picks a model and shapes it with tune, decay, tone, punch and drive; the kit passes through a glue compressor,
// a master saturator and a small room. Pad n answers to MIDI note 36 + n (C1 ... D#2, the General MIDI drum layout).
#pragma once
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <map>
#include <memory>
#include "fx.h"
#include "processor.h"
#include "../../app/src/main/cpp/wav.h"

namespace zdsp {

class Drums : public Processor {
public:
    static constexpr int kPads = 16;
    static constexpr int kFirstNote = 36;
    enum Model { Kick808, KickPunch, KickSub, Snare909, Snare808, Clap, Rim, HatClosed, HatOpen, HatNoise, Tom, Cowbell, Shaker, Cymbal, PercFM, Click, SampleModel, kNumModels };

    Drums() {
        std::vector<std::string> models = {"808 Kick", "Punch Kick", "Sub Kick", "909 Snare", "808 Snare", "Clap", "Rim", "Closed Hat", "Open Hat", "Noise Hat", "Tom", "Cowbell", "Shaker", "Cymbal", "FM Perc", "Click", "Sample"};
        const int defModel[kPads] = {Kick808, KickPunch, Rim, Snare909, Clap, Tom, HatClosed, Tom, HatClosed, Tom, HatOpen, Cowbell, Cymbal, Shaker, PercFM, Click};
        const float defTune[kPads] = {0, 0, 0, 0, 0, -5, 0, -2, 0, 3, 0, 0, 0, 0, 0, 0};
        const int defChoke[kPads] = {0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0};
        for (int p = 0; p < kPads; p++) {
            const std::string n = "P" + std::to_string(p + 1) + " ";
            const std::string g = "Pad " + std::to_string(p + 1);
            P_[p].model = add(choice(g.c_str(), (n + "Type").c_str(), models, defModel[p]));
            P_[p].tune = add(lin(g.c_str(), (n + "Tune").c_str(), -12.f, 12.f, defTune[p], "st", 1));
            P_[p].decay = add(pw(g.c_str(), (n + "Decay").c_str(), 0.25f, 4.f, 1.f, 1.0f, "x", 2)); specs_[P_[p].decay].curve = Curve::Log;
            P_[p].tone = add(lin(g.c_str(), (n + "Tone").c_str(), 0.f, 1.f, 0.5f));
            P_[p].punch = add(lin(g.c_str(), (n + "Punch").c_str(), 0.f, 1.f, 0.5f));
            P_[p].drive = add(lin(g.c_str(), (n + "Drive").c_str(), 0.f, 1.f, 0.f));
            P_[p].level = add(lin(g.c_str(), (n + "Level").c_str(), 0.f, 1.f, 0.8f));
            P_[p].pan = add(lin(g.c_str(), (n + "Pan").c_str(), -1.f, 1.f, 0.f));
            P_[p].choke = add(choice(g.c_str(), (n + "Choke").c_str(), {"-", "1", "2", "3", "4"}, defChoke[p]));
            P_[p].mode = add(choice(g.c_str(), (n + "Mode").c_str(), {"One-shot", "Gate"}, 0));        // sample pads: play to the end, or stop when the note ends
            P_[p].start = add(lin(g.c_str(), (n + "Start").c_str(), 0.f, 1.f, 0.f));                    // sample pads: where in the sample playback begins
            P_[p].reverse = add(toggle(g.c_str(), (n + "Reverse").c_str(), false));
        }
        ix_.volume = add(lin("Master", "Volume", -24.f, 6.f, 0.f, "dB", 1));
        ix_.glue = add(lin("Master", "Glue", 0.f, 1.f, 0.f));
        ix_.sat = add(lin("Master", "Saturation", 0.f, 1.f, 0.f));
        ix_.room = add(lin("Master", "Room", 0.f, 1.f, 0.f));
        ix_.roomSize = add(lin("Master", "Room Size", 0.f, 1.f, 0.4f));
        ix_.tone = add(lin("Master", "Kit Tone", -1.f, 1.f, 0.f));
        ix_.velSens = add(lin("Master", "Velocity Sens", 0.f, 1.f, 0.7f));
    }
    const char* name() const override { return "Zedok Drums"; }
    bool isInstrument() const override { return true; }
    void prepare(double sr, int maxFrames) override { sr_ = sr; room_.prepare(sr); glue_.prepare(sr); mixL_.assign(maxFrames, 0.f); mixR_.assign(maxFrames, 0.f); reset(); }
    void reset() override { for (auto& v : voices_) v = Voice(); room_.reset(); glue_.reset(); for (auto& x : activity_) x = 0.f; toneL_ = toneR_ = 0.f; satState_[0] = satState_[1] = 0.f; }

    void process(const ProcessCtx& c, const float* vals) override {
        const int n = c.frames; if (n > (int)mixL_.size()) return;
        std::fill(mixL_.begin(), mixL_.begin() + n, 0.f); std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
        float tune[kPads], decay[kPads], tone[kPads], punch[kPads], drive[kPads], level[kPads], pan[kPads]; int model[kPads], choke[kPads];
        for (int p = 0; p < kPads; p++) {
            model[p] = (int)(real(vals, P_[p].model) + 0.5f); tune[p] = real(vals, P_[p].tune); decay[p] = real(vals, P_[p].decay); tone[p] = real(vals, P_[p].tone);
            mode_[p] = (int)(real(vals, P_[p].mode) + 0.5f); start_[p] = real(vals, P_[p].start); rev_[p] = real(vals, P_[p].reverse) > 0.5f;
            punch[p] = real(vals, P_[p].punch); drive[p] = real(vals, P_[p].drive); level[p] = real(vals, P_[p].level); pan[p] = real(vals, P_[p].pan); choke[p] = (int)(real(vals, P_[p].choke) + 0.5f);
        }
        const float velSens = real(vals, ix_.velSens);
        int ei = 0;
        for (int i = 0; i < n; i++) {
            while (ei < c.nEvents && (int)c.events[ei].offset <= i) {
                const ZkEvent& e = c.events[ei++];
                if (e.type == ZK_ALL_NOTES_OFF) { for (auto& v : voices_) if (v.active) v.fadeOut = 1; continue; }
                if (e.type == ZK_NOTE_OFF || (e.type == ZK_NOTE_ON && e.velocity == 0)) {   // gate-mode sample pads stop with their note
                    const int p = (int)e.pitch - kFirstNote; if (p >= 0 && p < kPads) for (auto& v : voices_) if (v.active && v.pad == p && v.gate) v.fadeOut = 1.f;
                    continue;
                }
                if (e.type != ZK_NOTE_ON) continue;
                const int p = (int)e.pitch - kFirstNote; if (p < 0 || p >= kPads) continue;
                trigger(p, e.velocity / 127.f, model[p], tune[p], decay[p], tone[p], punch[p], drive[p], level[p], pan[p], choke[p], velSens);
            }
            float l = 0.f, r = 0.f;
            for (auto& v : voices_) if (v.active) {
                if (v.model == SampleModel) { tickSample(v); l += v.outL * v.gl; r += v.outR * v.gr; }
                else { const float s = tickVoice(v); l += s * v.gl; r += s * v.gr; }
            }
            mixL_[i] = l; mixR_[i] = r;
        }
        // ---- master section
        float* L = mixL_.data(); float* R = mixR_.data();
        const float glue = real(vals, ix_.glue), sat = real(vals, ix_.sat), room = real(vals, ix_.room), kt = real(vals, ix_.tone);
        if (glue > 0.01f) { Compressor::Params cp; cp.thresh = -6.f - 18.f * glue; cp.ratio = 2.f + 3.f * glue; cp.knee = 8.f; cp.attack = 12.f; cp.release = 120.f; cp.makeup = 3.f * glue; cp.rms = 0.3f; cp.link = 1.f; glue_.set(cp); glue_.process(L, R, n); }
        if (std::fabs(kt) > 0.01f) {   // kit tone: a tilt around 1 kHz (dark <-> bright)
            const float a = std::exp(-kTwoPi * 1000.f / (float)sr_);
            for (int i = 0; i < n; i++) { toneL_ = (1.f - a) * L[i] + a * toneL_; toneR_ = (1.f - a) * R[i] + a * toneR_; const float lo = toneL_, hi = L[i] - toneL_, lo2 = toneR_, hi2 = R[i] - toneR_; const float gl = 1.f - 0.6f * kt, gh = 1.f + 0.8f * kt; L[i] = lo * gl + hi * gh; R[i] = lo2 * gl + hi2 * gh; }
        }
        if (sat > 0.01f) { const float d = 1.f + sat * 5.f; for (int i = 0; i < n; i++) { L[i] = fastTanh(L[i] * d) / std::sqrt(d); R[i] = fastTanh(R[i] * d) / std::sqrt(d); } }
        if (room > 0.005f) { room_.set(Reverb::Room, real(vals, ix_.roomSize), 0.2f + 0.9f * real(vals, ix_.roomSize), 0.5f, 0.8f, 0.3f, 0.f, 150.f, 9000.f, 1.f, 0.f, room * 0.5f); room_.process(L, R, n); }
        const float g = dbToLin(real(vals, ix_.volume));
        for (int i = 0; i < n; i++) {
            c.outL[i] = softOut(L[i] * g); c.outR[i] = softOut(R[i] * g);
        }
        for (auto& a : activity_) a *= 0.9f;
    }
    // 0: how much each pad is sounding (16 values 0..1), for the pad lights
    // 0: how much each pad is sounding (16 values 0..1), for the pad lights    1: number of sample zones on each pad
    int telemetry(int what, float* out, int cap) override {
        if (what == 0 && cap >= kPads) { for (int p = 0; p < kPads; p++) out[p] = activity_[p]; return kPads; }
        if (what == 1 && cap >= kPads) { for (int p = 0; p < kPads; p++) { auto ps = std::atomic_load(&pads_[p]); out[p] = ps ? (float)ps->zones.size() : 0.f; } return kPads; }
        return 0;
    }

private:
    static inline float softOut(float x) { const float a = std::fabs(x); if (a <= 0.9f) return x; const float y = 0.9f + 0.1f * fastTanh((a - 0.9f) * 10.f); return x < 0 ? -y : y; }
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    struct PadIx { int model, tune, decay, tone, punch, drive, level, pan, choke, mode, start, reverse; } P_[kPads];
    struct Ix { int volume, glue, sat, room, roomSize, tone, velSens; } ix_;

    struct Sample { std::vector<float> L, R; int n = 0; };
    struct Zone { std::shared_ptr<const Sample> s; int vlo = 1, vhi = 127; };
    struct PadSamples { std::vector<Zone> zones; };
    struct Voice {
        bool active = false; int pad = 0, model = 0, choke = 0; float vel = 1.f, ratio = 1.f, decay = 1.f, tone = .5f, punch = .5f, drive = 0.f, gl = .7f, gr = .7f, level = .8f;
        float t = 0.f; float fadeOut = 0.f, fadeGain = 1.f, fadeIn = 0.f; uint64_t age = 0;
        float ph[8] = {}; SVF f1, f2; Rng rng{1234u}; float lp = 0.f, st[4] = {};
        // sample playback
        const Sample* sm = nullptr; double sp = 0.0, send = 0.0, sinc = 1.0; int sdir = 1; bool gate = false; float outL = 0.f, outR = 0.f, tlp[2] = {0.f, 0.f};
    };
    void trigger(int pad, float vel, int model, float tuneSt, float decay, float tone, float punch, float drive, float level, float pan, int choke, float velSens) {
        if (choke > 0) for (auto& v : voices_) if (v.active && v.choke == choke && v.pad != pad) v.fadeOut = 1.f;
        // fade out the previous voice of the same pad (a short crossfade instead of a click) and take a free slot
        Voice* slot = nullptr;
        for (auto& v : voices_) if (v.active && v.pad == pad) v.fadeOut = 1.f;
        for (auto& v : voices_) if (!v.active) { slot = &v; break; }
        if (!slot) { uint64_t oldest = ~0ull; for (auto& v : voices_) if (v.age < oldest) { oldest = v.age; slot = &v; } }
        Voice& v = *slot; v = Voice(); v.active = true; v.pad = pad; v.model = model; v.choke = choke; v.age = ++age_;
        v.vel = 1.f - velSens + velSens * vel; v.ratio = semiToRatio(tuneSt); v.decay = decay; v.tone = clampf(tone, 0.f, 1.f); v.punch = punch; v.drive = drive; v.level = level;
        v.gl = std::cos((pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f; v.gr = std::sin((pan * 0.5f + 0.5f) * 1.5707963f) * 1.4142f;
        v.rng = Rng(0x9E3779B9u + (uint32_t)(age_ * 7919u)); v.fadeIn = 0.f;
        if (model == SampleModel) {
            auto ps = std::atomic_load(&pads_[pad]);
            const Zone* zones[32]; int nz = 0; const int vi = std::max(1, std::min(127, (int)(vel * 127.f + 0.5f)));
            if (ps) for (auto& z : ps->zones) if (z.s && vi >= z.vlo && vi <= z.vhi && nz < 32) zones[nz++] = &z;
            if (nz == 0) { v.active = false; return; }
            const Zone* z = zones[rrCtr_[pad]++ % nz];
            v.sm = z->s.get(); v.gate = mode_[pad] == 1; v.sinc = v.ratio; v.sdir = rev_[pad] ? -1 : 1;
            const double n = (double)v.sm->n; const double st = std::min(0.98, (double)start_[pad]) * (n - 1.0);
            const double keep = std::min(1.0, (double)decay);                       // Decay below 1 shortens the sound
            if (!rev_[pad]) { v.sp = st; v.send = st + (n - 1.0 - st) * keep; }       // forward: from Start to the end
            else { v.sp = n - 1.0; v.send = std::max(st, (n - 1.0) - (n - 1.0 - st) * keep); }   // reverse: from the end back to Start
            activity_[pad] = std::max(activity_[pad], vel);
            return;
        }
        // fixed scale so that every model peaks at a comparable level before the pad level
        activity_[pad] = std::max(activity_[pad], vel);
        const float vb = clampf(vel, 0.f, 1.f); v.tone = clampf(v.tone + (vb - 0.7f) * 0.15f * velSens, 0.f, 1.f);
        for (int k = 0; k < 8; k++) v.ph[k] = 0.f;
        // models with a set of oscillators start at fixed phases (a hit sounds the same every time)
        if (model == HatClosed || model == HatOpen || model == Cymbal) for (int k = 0; k < 6; k++) v.ph[k] = 0.1f * k;
        v.t = 0.f;
        // per-model filter setup is done in tickVoice on the first sample
    }
    // One sample voice: Hermite-interpolated read (pitch = tune), fade in/out, tone tilt and drive. Stereo output in outL/outR.
    void tickSample(Voice& v) {
        const float dt = 1.f / (float)sr_; v.outL = v.outR = 0.f;
        const Sample* s = v.sm; if (!s || s->n < 4) { v.active = false; return; }
        const bool done = v.sdir > 0 ? v.sp >= v.send || v.sp >= s->n - 2 : v.sp <= v.send || v.sp <= 1.0;
        if (done && v.fadeOut <= 0.f) v.fadeOut = 1.f;
        double p = std::min(std::max(v.sp, 1.0), (double)(s->n - 3)); const int i = (int)p; const float f = (float)(p - i);
        auto herm = [&](const std::vector<float>& d) { const float ym1 = d[i - 1], y0 = d[i], y1 = d[i + 1], y2 = d[i + 2];
            const float c1 = 0.5f * (y1 - ym1), c2 = ym1 - 2.5f * y0 + 2.f * y1 - 0.5f * y2, c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1); return ((c3 * f + c2) * f + c1) * f + y0; };
        float l = herm(s->L), r = herm(s->R);
        v.sp += v.sinc * v.sdir;
        // tone: below 0.5 darkens (low-pass sweeping down), above 0.5 brightens (adds the high band)
        const float tn = v.tone;
        if (tn < 0.48f) { const float fc = 400.f * std::pow(50.f, tn * 2.f); const float a = 1.f - std::exp(-kTwoPi * fc * dt); v.tlp[0] += a * (l - v.tlp[0]); v.tlp[1] += a * (r - v.tlp[1]); l = v.tlp[0]; r = v.tlp[1]; }
        else if (tn > 0.52f) { const float a = 1.f - std::exp(-kTwoPi * 1500.f * dt); v.tlp[0] += a * (l - v.tlp[0]); v.tlp[1] += a * (r - v.tlp[1]); const float k = (tn - 0.5f) * 2.f * 1.5f; l += k * (l - v.tlp[0]); r += k * (r - v.tlp[1]); }
        if (v.drive > 0.01f) { const float d = 1.f + v.drive * 7.f, nd = 1.f / std::sqrt(d); l = fastTanh(l * d) * nd; r = fastTanh(r * d) * nd; }
        float g = v.vel * v.level * 1.1f;
        if (v.fadeOut > 0.f) { v.fadeGain -= dt / (v.gate ? 0.02f : 0.004f); if (v.fadeGain <= 0.f) { v.active = false; return; } g *= v.fadeGain; }
        if (v.fadeIn < 1.f) { v.fadeIn = std::min(1.f, v.fadeIn + dt / 0.0004f); g *= v.fadeIn; }
        v.outL = l * g; v.outR = r * g;
    }
    static inline float expEnv(float t, float tau) { return std::exp(-t / std::max(tau, 1e-4f)); }
    float tickVoice(Voice& v) {
        const float dt = 1.f / (float)sr_; const float t = v.t; float y = 0.f;
        const float pn = v.punch, tn = v.tone, dc = v.decay;
        switch (v.model) {
        case Kick808: {
            const float f0 = 46.f * v.ratio * (1.f + 0.25f * tn); const float sweep = 1.f + (2.5f + 5.f * pn) * expEnv(t, 0.035f + 0.02f * (1.f - pn));
            v.ph[0] += f0 * sweep * dt; v.ph[0] -= std::floor(v.ph[0]);
            y = std::sin(kTwoPi * v.ph[0]) * expEnv(t, 0.32f * dc) * (t < 0.0015f ? t / 0.0015f : 1.f);
            if (pn > 0.01f) y += 0.35f * pn * v.rng.bi() * expEnv(t, 0.0025f);
            break; }
        case KickPunch: {
            const float f0 = 52.f * v.ratio; const float sweep = 1.f + (3.f + 5.f * pn) * expEnv(t, 0.018f);
            v.ph[0] += f0 * sweep * dt; v.ph[0] -= std::floor(v.ph[0]); v.ph[1] += f0 * sweep * 2.f * dt; v.ph[1] -= std::floor(v.ph[1]);
            const float body = std::sin(kTwoPi * v.ph[0]) + 0.18f * tn * std::sin(kTwoPi * v.ph[1]);
            y = body * expEnv(t, 0.2f * dc);
            const float click = v.rng.bi() * expEnv(t, 0.0018f); v.lp += 0.45f * (click - v.lp);
            y += (0.9f * pn) * (click - v.lp * 0.3f);
            y = fastTanh(y * (1.2f + 1.5f * tn)) * 0.9f; break; }
        case KickSub: {
            const float f0 = 36.f * v.ratio; const float sweep = 1.f + 1.2f * expEnv(t, 0.06f);
            v.ph[0] += f0 * sweep * dt; v.ph[0] -= std::floor(v.ph[0]);
            y = std::sin(kTwoPi * v.ph[0]) * expEnv(t, 0.7f * dc) * (t < 0.004f ? t / 0.004f : 1.f);
            y += 0.12f * pn * v.rng.bi() * expEnv(t, 0.002f); break; }
        case Snare909: {
            const float f = 185.f * v.ratio;
            v.ph[0] += f * (1.f + 0.4f * expEnv(t, 0.012f)) * dt; v.ph[0] -= std::floor(v.ph[0]); v.ph[1] += f * 1.78f * dt; v.ph[1] -= std::floor(v.ph[1]);
            const float body = (std::sin(kTwoPi * v.ph[0]) * expEnv(t, 0.09f * dc) + 0.55f * std::sin(kTwoPi * v.ph[1]) * expEnv(t, 0.06f * dc)) * (1.f - 0.5f * tn);
            if (t == 0.f) { v.f1.set(sr_, 1800.f + 2500.f * tn, 0.1f); v.f2.set(sr_, 9000.f, 0.f); }
            const auto o = v.f1.tick(v.rng.bi()); const float noise = v.f2.tick(o.hp).lp * expEnv(t, (0.11f + 0.14f * tn) * dc) * (0.55f + 0.7f * tn);
            y = body * 0.9f + noise * 0.8f + 0.5f * pn * v.rng.bi() * expEnv(t, 0.002f); break; }
        case Snare808: {
            const float f = 175.f * v.ratio;
            v.ph[0] += f * dt; v.ph[0] -= std::floor(v.ph[0]); v.ph[1] += f * 1.9f * dt; v.ph[1] -= std::floor(v.ph[1]);
            const float tri1 = 4.f * std::fabs(v.ph[0] - 0.5f) - 1.f, tri2 = 4.f * std::fabs(v.ph[1] - 0.5f) - 1.f;
            if (t == 0.f) v.f1.set(sr_, 1200.f + 3000.f * tn, 0.05f);
            const auto o = v.f1.tick(v.rng.bi());
            if (t == 0.f) v.f2.set(sr_, 7500.f, 0.f);
            y = (tri1 * expEnv(t, 0.07f * dc) + 0.6f * tri2 * expEnv(t, 0.05f * dc)) * (1.f - 0.6f * tn) * 0.8f + v.f2.tick(o.hp).lp * expEnv(t, (0.09f + 0.12f * tn) * dc) * (0.4f + 0.8f * tn);
            break; }
        case Clap: {
            if (t == 0.f) { v.f1.set(sr_, 1100.f * v.ratio + 700.f * tn, 0.45f); }
            const auto o = v.f1.tick(v.rng.bi());
            float env = 0.f; const float gap = 0.0105f;
            for (int k = 0; k < 3; k++) { const float tk = t - k * gap; if (tk >= 0.f) env = std::max(env, expEnv(tk, 0.0035f)); }
            const float tl = t - 3 * gap; if (tl >= 0.f) env = std::max(env, 0.8f * expEnv(tl, (0.09f + 0.1f * pn) * dc));
            y = o.bp * 1.6f * env + 0.15f * o.hp * env; break; }
        case Rim: {
            v.ph[0] += 1650.f * v.ratio * dt; v.ph[0] -= std::floor(v.ph[0]); v.ph[1] += 480.f * v.ratio * dt; v.ph[1] -= std::floor(v.ph[1]);
            y = (std::sin(kTwoPi * v.ph[0]) * 0.6f + std::sin(kTwoPi * v.ph[1]) * 0.8f) * expEnv(t, 0.012f * dc) + 0.5f * v.rng.bi() * expEnv(t, 0.0015f); break; }
        case HatClosed: case HatOpen: case Cymbal: {
            static const float ratios[6] = {2.f, 3.f, 4.16f, 5.43f, 6.79f, 8.21f};
            const float base = (v.model == Cymbal ? 330.f : 205.f) * v.ratio; float s = 0.f;
            for (int k = 0; k < 6; k++) { const float f = base * ratios[k]; v.ph[k] += f * dt; v.ph[k] -= std::floor(v.ph[k]); float sq = v.ph[k] < 0.5f ? 1.f : -1.f; const float d = f * dt; sq += polyBlep(v.ph[k], d); float t2 = v.ph[k] + 0.5f; if (t2 >= 1.f) t2 -= 1.f; sq -= polyBlep(t2, d); s += sq; }
            if (t == 0.f) { v.f1.set(sr_, 6500.f + 3500.f * tn, 0.25f); v.f2.set(sr_, 3500.f + 2500.f * tn, 0.4f); }
            const float hp = v.f1.tick(s * 0.25f).hp; const float bp = v.f2.tick(v.rng.bi()).hp * 0.5f;
            const float tau = v.model == HatClosed ? 0.032f + 0.03f * pn : v.model == HatOpen ? 0.26f : 1.1f;
            const float env = (t < 0.0007f ? t / 0.0007f : 1.f) * expEnv(t, tau * dc);
            y = (hp * (v.model == Cymbal ? 0.55f : 0.9f) + (v.model == Cymbal ? 0.4f : 0.15f) * bp) * env; break; }
        case HatNoise: {
            if (t == 0.f) v.f1.set(sr_, 6500.f + 4500.f * tn, 0.1f);
            const float h = v.f1.tick(v.rng.bi()).hp; y = h * (t < 0.0006f ? t / 0.0006f : 1.f) * expEnv(t, (0.035f + 0.07f * pn) * dc) * 0.9f; break; }
        case Tom: {
            const float f0 = 112.f * v.ratio; const float sweep = 1.f + 1.1f * expEnv(t, 0.06f + 0.04f * pn);
            v.ph[0] += f0 * sweep * dt; v.ph[0] -= std::floor(v.ph[0]);
            y = std::sin(kTwoPi * v.ph[0]) * expEnv(t, 0.28f * dc) + 0.1f * tn * v.rng.bi() * expEnv(t, 0.01f); break; }
        case Cowbell: {
            const float f1 = 540.f * v.ratio, f2 = 800.f * v.ratio;
            v.ph[0] += f1 * dt; v.ph[0] -= std::floor(v.ph[0]); v.ph[1] += f2 * dt; v.ph[1] -= std::floor(v.ph[1]);
            float s = (v.ph[0] < 0.5f ? 1.f : -1.f) + (v.ph[1] < 0.5f ? 1.f : -1.f);
            if (t == 0.f) v.f1.set(sr_, 1900.f + 1500.f * tn, 0.25f);
            y = v.f1.tick(s * 0.5f).bp * 1.2f * (0.35f + 0.65f * expEnv(t, 0.06f)) * expEnv(t, 0.32f * dc); break; }
        case Shaker: {
            if (t == 0.f) v.f1.set(sr_, 5500.f + 3000.f * tn, 0.3f);
            const float n = v.f1.tick(v.rng.bi()).bp; const float a = 0.012f * (1.f + pn);
            y = n * 1.3f * (t < a ? t / a : 1.f) * expEnv(t, (0.045f + 0.05f * pn) * dc); break; }
        case PercFM: {
            const float f = 190.f * v.ratio * (1.f + 0.6f * expEnv(t, 0.03f)); const float idx = (1.5f + 4.f * tn) * expEnv(t, 0.05f * dc);
            v.ph[1] += f * 1.41f * dt; v.ph[1] -= std::floor(v.ph[1]); v.ph[0] += f * dt; v.ph[0] -= std::floor(v.ph[0]);
            y = std::sin(kTwoPi * v.ph[0] + idx * std::sin(kTwoPi * v.ph[1])) * expEnv(t, 0.16f * dc); break; }
        case Click: {
            if (t == 0.f) v.f1.set(sr_, 2800.f * v.ratio + 1500.f * tn, 0.3f);
            v.ph[0] += 1900.f * v.ratio * dt; v.ph[0] -= std::floor(v.ph[0]);
            y = v.f1.tick(v.rng.bi()).bp * expEnv(t, 0.004f * dc) * 1.5f + 0.5f * std::sin(kTwoPi * v.ph[0]) * expEnv(t, 0.008f * dc); break; }
        }
        // per-pad drive, level, fades
        if (v.drive > 0.01f) { const float d = 1.f + v.drive * 7.f; y = fastTanh(y * d) / std::sqrt(d); }
        static const float modelDb[kNumModels] = {0.f, 1.5f, 0.5f, -5.5f, -4.5f, 1.5f, -5.2f, 4.4f, 3.f, -4.4f, 0.f, -1.4f, -0.4f, 1.8f, -2.5f, -0.3f};   // evens out the model levels (peak ~ -6 dB kicks/snares, ~ -12 dB hats)
        float g = v.vel * v.level * 0.7f * dbToLin(modelDb[v.model]);
        if (v.fadeOut > 0.f) { v.fadeGain -= dt / 0.004f; if (v.fadeGain <= 0.f) { v.active = false; return 0.f; } g *= v.fadeGain; }
        if (v.fadeIn < 1.f) { v.fadeIn = std::min(1.f, v.fadeIn + dt / 0.0004f); g *= v.fadeIn; }
        v.t += dt;
        const float limit = (v.model == Cymbal ? 6.f : v.model == KickSub || v.model == Kick808 ? 3.f : 1.2f) * v.decay;
        if (v.t > limit + 0.3f && std::fabs(y) < 1e-4f) v.active = false;
        return y * g;
    }

    // ---- sample pads: each pad has a list of zones (velocity range + round-robin) with the audio loaded as float stereo at the engine rate
    std::shared_ptr<const PadSamples> pads_[kPads]; unsigned rrCtr_[kPads] = {}; int mode_[kPads] = {}; float start_[kPads] = {}; bool rev_[kPads] = {};
    std::map<std::string, std::weak_ptr<const Sample>> sampleCache_;
    std::vector<std::pair<std::chrono::steady_clock::time_point, std::shared_ptr<const PadSamples>>> graveyard_;   // replaced pads stay alive a while: a voice may still be reading them
    std::string kitName_; std::string padText_[kPads]; size_t sampleBytes_ = 0;
    std::shared_ptr<const Sample> loadSample(const std::string& path) {
        auto it = sampleCache_.find(path); if (it != sampleCache_.end()) if (auto sp = it->second.lock()) return sp;
        std::vector<float> st; int rate = 0; if (!zedok::readWav(path, st, rate) || st.size() < 8 || rate <= 0) return nullptr;
        if (rate != (int)sr_) st = zedok::resampleStereo(st, rate, (int)sr_);
        size_t frames = std::min<size_t>(st.size() / 2, (size_t)(12.0 * sr_));            // one-shots: 12 s at most
        auto b = std::make_shared<Sample>(); b->L.resize(frames + 4, 0.f); b->R.resize(frames + 4, 0.f); b->n = (int)frames;
        for (size_t i = 0; i < frames; i++) { b->L[i] = st[i * 2]; b->R[i] = st[i * 2 + 1]; }
        sampleCache_[path] = b; return b;
    }
public:
    // State: "pad1".."pad16" = one zone per line "velLow,velHigh,rr,path" (empty = no sample); "kit" = the kit's name.
    bool setState(const std::string& key, const std::string& value) override {
        const auto now = std::chrono::steady_clock::now();
        for (size_t i = 0; i < graveyard_.size();) { if (now - graveyard_[i].first > std::chrono::seconds(20)) graveyard_.erase(graveyard_.begin() + i); else i++; }
        if (key == "kit") { kitName_ = value; return true; }
        if (key.size() < 4 || key.compare(0, 3, "pad") != 0) return false;
        const int p = std::atoi(key.c_str() + 3) - 1; if (p < 0 || p >= kPads) return false;
        auto ps = std::make_shared<PadSamples>(); size_t pos = 0;
        while (pos < value.size() && ps->zones.size() < 32) {
            size_t e = value.find('\n', pos); if (e == std::string::npos) e = value.size();
            std::string line = value.substr(pos, e - pos); pos = e + 1; if (line.empty()) continue;
            int vlo = 1, vhi = 127, rr = 0, used = 0; if (std::sscanf(line.c_str(), "%d,%d,%d,%n", &vlo, &vhi, &rr, &used) < 3 || used <= 0) continue;
            auto smp = loadSample(line.substr(used)); if (!smp) continue;
            Zone z; z.s = smp; z.vlo = std::max(1, std::min(127, vlo)); z.vhi = std::max(z.vlo, std::min(127, vhi)); ps->zones.push_back(z);
        }
        padText_[p] = value;
        auto old = std::atomic_load(&pads_[p]); if (old) graveyard_.push_back({now, old});
        std::atomic_store(&pads_[p], std::shared_ptr<const PadSamples>(ps));
        return true;
    }
    std::string getState(const std::string& key) const override {
        if (key == "kit") return kitName_;
        if (key.size() >= 4 && key.compare(0, 3, "pad") == 0) { const int p = std::atoi(key.c_str() + 3) - 1; if (p >= 0 && p < kPads) return padText_[p]; }
        return "";
    }
    std::vector<std::string> stateKeys() const override { std::vector<std::string> k = {"kit"}; for (int i = 1; i <= kPads; i++) k.push_back("pad" + std::to_string(i)); return k; }
private:
    Voice voices_[24]; uint64_t age_ = 0; std::vector<float> mixL_, mixR_; Reverb room_; Compressor glue_; float activity_[kPads] = {}; float toneL_ = 0.f, toneR_ = 0.f, satState_[2] = {};
};

}  // namespace zdsp
