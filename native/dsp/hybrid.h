// Zedok Hybrid: a polyphonic synthesizer with two oscillators that can each be analog (band-limited), wavetable or 2-operator FM,
// a sub oscillator and noise, two filters, three envelopes, three LFOs, an eight-slot modulation matrix, an arpeggiator and
// built-in chorus / delay / reverb.
#pragma once
#include "fx.h"
#include "processor.h"
#include "wavetables.h"

namespace zdsp {

class Hybrid : public Processor {
public:
    static constexpr int kMaxVoices = 16;
    static constexpr int kMaxUni = 8;
    static constexpr int kSlots = 8;
    static constexpr int kBlock = 16;   // control-rate sub-block

    enum Src { SrcNone, SrcLfo1, SrcLfo2, SrcLfo3, SrcEnv2, SrcEnv3, SrcVelocity, SrcKey, SrcWheel, SrcRandom, kNumSrc };
    enum Dst { DstNone, DstPitch, DstAPitch, DstBPitch, DstAPos, DstBPos, DstALevel, DstBLevel, DstAIndex, DstBIndex, DstAPw, DstBPw,
               DstCutoff1, DstRes1, DstCutoff2, DstRes2, DstDrive, DstAmp, DstPan, DstDetune, DstSub, DstNoise, DstLfo1Rate, DstLfo2Rate, kNumDst };

    Hybrid() {
        tables_ = wt::library();
        static const char* kSrcNames[] = {"None", "LFO 1", "LFO 2", "LFO 3", "Env 2", "Env 3", "Velocity", "Key", "Mod Wheel", "Random"};
        static const char* kDstNames[] = {"None", "Pitch", "Osc A Pitch", "Osc B Pitch", "Osc A Position", "Osc B Position", "Osc A Level", "Osc B Level",
            "Osc A FM Index", "Osc B FM Index", "Osc A Width", "Osc B Width", "Filter 1 Cutoff", "Filter 1 Res", "Filter 2 Cutoff", "Filter 2 Res",
            "Drive", "Volume", "Pan", "Unison Detune", "Sub Level", "Noise Level", "LFO 1 Rate", "LFO 2 Rate"};
        std::vector<std::string> srcs(kSrcNames, kSrcNames + kNumSrc), dsts(kDstNames, kDstNames + kNumDst), tbl;
        for (auto& t : *tables_) tbl.push_back(t.name);
        const std::vector<std::string> divs = {"4 bars", "2 bars", "1 bar", "1/2", "1/4", "1/8", "1/16", "1/4 T", "1/8 T", "1/16 T", "1/4 .", "1/8 ."};

        // -- global
        ix.volume = add(lin("Main", "Volume", -24.f, 6.f, 0.f, "dB", 1));
        ix.poly = add(choice("Main", "Polyphony", {"1", "2", "4", "6", "8", "12", "16"}, 4));
        ix.mono = add(choice("Main", "Play Mode", {"Poly", "Mono", "Legato"}, 0));
        ix.glide = add(pw("Main", "Glide", 0.f, 2000.f, 0.f, 2.5f, "ms", 0));
        ix.wheel = add(lin("Main", "Mod Wheel", 0.f, 1.f, 0.f));
        ix.quality = add(choice("Main", "Quality", {"Eco", "High"}, 1));
        ix.bend = add(lin("Main", "Pitch Bend", -1.f, 1.f, 0.f));
        ix.bendRange = add(lin("Main", "Bend Range", 1.f, 24.f, 2.f, "st", 0));
        // -- oscillators
        for (int o = 0; o < 2; o++) {
            const char* g = o == 0 ? "Osc A" : "Osc B"; const std::string n = o == 0 ? "A " : "B ";
            OscIx& x = osc[o];
            x.type = add(choice(g, (n + "Type").c_str(), {"Analog", "Wavetable", "FM"}, o == 0 ? 0 : 0));
            x.wave = add(choice(g, (n + "Wave").c_str(), {"Saw", "Square", "Triangle", "Sine"}, o == 0 ? 0 : 1));
            x.table = add(choice(g, (n + "Table").c_str(), tbl, 0));
            x.pos = add(lin(g, (n + "Position").c_str(), 0.f, 1.f, 0.f));
            x.pw = add(lin(g, (n + "Width").c_str(), 0.05f, 0.95f, 0.5f, "", 2));
            x.ratio = add(pw(g, (n + "FM Ratio").c_str(), 0.25f, 16.f, 2.f, 2.f, "x", 2));
            x.index = add(pw(g, (n + "FM Index").c_str(), 0.f, 12.f, 2.f, 2.f, "", 2));
            x.fb = add(lin(g, (n + "FM Feedback").c_str(), 0.f, 1.f, 0.f));
            x.level = add(lin(g, (n + "Level").c_str(), 0.f, 1.f, o == 0 ? 0.8f : 0.f));
            x.oct = add(lin(g, (n + "Octave").c_str(), -3.f, 3.f, 0.f, "", 0)); specs_[x.oct].curve = Curve::Choice; specs_[x.oct].labels = {"-3", "-2", "-1", "0", "+1", "+2", "+3"}; specs_[x.oct].def = 3.f; specs_[x.oct].lo = 0; specs_[x.oct].hi = 6;
            x.semi = add(lin(g, (n + "Semi").c_str(), -12.f, 12.f, 0.f, "st", 0));
            x.fine = add(lin(g, (n + "Fine").c_str(), -100.f, 100.f, 0.f, "ct", 0));
            x.pan = add(lin(g, (n + "Pan").c_str(), -1.f, 1.f, 0.f));
            x.uni = add(lin(g, (n + "Unison").c_str(), 1.f, 8.f, 1.f, "", 0)); specs_[x.uni].curve = Curve::Choice; specs_[x.uni].labels = {"1", "2", "3", "4", "5", "6", "7", "8"}; specs_[x.uni].def = 0; specs_[x.uni].lo = 0; specs_[x.uni].hi = 7;
            x.det = add(pw(g, (n + "Detune").c_str(), 0.f, 100.f, 20.f, 2.f, "ct", 0));
            x.spread = add(lin(g, (n + "Spread").c_str(), 0.f, 1.f, 0.6f));
            x.phase = add(lin(g, (n + "Phase").c_str(), 0.f, 1.f, 0.f));
        }
        ix.subLevel = add(lin("Sub", "Sub Level", 0.f, 1.f, 0.f));
        ix.subWave = add(choice("Sub", "Sub Wave", {"Sine", "Triangle", "Square"}, 0));
        ix.subOct = add(choice("Sub", "Sub Octave", {"-1", "-2"}, 0));
        ix.noiseLevel = add(lin("Sub", "Noise Level", 0.f, 1.f, 0.f));
        ix.noiseColor = add(lin("Sub", "Noise Color", 0.f, 1.f, 0.f));
        // -- filters
        ix.f1Type = add(choice("Filter 1", "F1 Type", {"LP 12", "LP 24", "Ladder", "HP 12", "BP 12", "Notch", "Comb"}, 1));
        ix.f1Cut = add(logp("Filter 1", "F1 Cutoff", 20.f, 20000.f, 8000.f, "Hz", 0));
        ix.f1Res = add(lin("Filter 1", "F1 Resonance", 0.f, 1.f, 0.15f));
        ix.f1Drive = add(lin("Filter 1", "F1 Drive", 0.f, 1.f, 0.f));
        ix.f1Env = add(lin("Filter 1", "F1 Env Amount", -1.f, 1.f, 0.f));
        ix.f1Key = add(lin("Filter 1", "F1 Key Track", 0.f, 1.f, 0.f));
        ix.f2Type = add(choice("Filter 2", "F2 Type", {"Off", "LP 12", "HP 12", "BP 12", "Notch"}, 0));
        ix.f2Cut = add(logp("Filter 2", "F2 Cutoff", 20.f, 20000.f, 400.f, "Hz", 0));
        ix.f2Res = add(lin("Filter 2", "F2 Resonance", 0.f, 1.f, 0.1f));
        ix.f2Key = add(lin("Filter 2", "F2 Key Track", 0.f, 1.f, 0.f));
        ix.fRoute = add(choice("Filter 2", "Filter Routing", {"Serial", "Parallel"}, 0));
        // -- envelopes
        const char* envName[3] = {"Amp", "Env 2", "Env 3"};
        const char* envPre[3] = {"Amp ", "Env2 ", "Env3 "};
        const float envDef[3][4] = {{3.f, 250.f, 0.8f, 200.f}, {3.f, 350.f, 0.4f, 250.f}, {3.f, 400.f, 0.f, 300.f}};
        for (int e = 0; e < 3; e++) {
            const std::string p = envPre[e];
            ix.env[e].a = add(pw(envName[e], (p + "Attack").c_str(), 0.5f, 10000.f, envDef[e][0], 3.f, "ms", 1));
            ix.env[e].d = add(pw(envName[e], (p + "Decay").c_str(), 1.f, 15000.f, envDef[e][1], 3.f, "ms", 0));
            ix.env[e].s = add(lin(envName[e], (p + "Sustain").c_str(), 0.f, 1.f, envDef[e][2]));
            ix.env[e].r = add(pw(envName[e], (p + "Release").c_str(), 1.f, 20000.f, envDef[e][3], 3.f, "ms", 0));
        }
        // -- LFOs
        for (int l = 0; l < 3; l++) {
            const std::string p = "LFO" + std::to_string(l + 1) + " "; const std::string g = "LFO " + std::to_string(l + 1);
            ix.lfo[l].shape = add(choice(g.c_str(), (p + "Shape").c_str(), {"Sine", "Triangle", "Saw", "Square", "Sample & Hold", "Smooth Random"}, 0));
            ix.lfo[l].rate = add(logp(g.c_str(), (p + "Rate").c_str(), 0.03f, 40.f, 2.f, "Hz", 2));
            ix.lfo[l].sync = add(toggle(g.c_str(), (p + "Sync").c_str()));
            ix.lfo[l].div = add(choice(g.c_str(), (p + "Division").c_str(), divs, 4));
            ix.lfo[l].fade = add(pw(g.c_str(), (p + "Fade In").c_str(), 0.f, 5000.f, 0.f, 2.f, "ms", 0));
            ix.lfo[l].retrig = add(toggle(g.c_str(), (p + "Retrigger").c_str(), true));
        }
        // -- modulation matrix
        for (int m = 0; m < kSlots; m++) {
            const std::string p = "Mod " + std::to_string(m + 1) + " ";
            ix.slot[m].src = add(choice("Modulation", (p + "Source").c_str(), srcs, 0));
            ix.slot[m].dst = add(choice("Modulation", (p + "Target").c_str(), dsts, 0));
            ix.slot[m].amt = add(lin("Modulation", (p + "Amount").c_str(), -1.f, 1.f, 0.f));
        }
        // -- effects
        ix.chMix = add(lin("Effects", "Chorus Mix", 0.f, 1.f, 0.f));
        ix.chRate = add(logp("Effects", "Chorus Rate", 0.1f, 6.f, 0.6f, "Hz", 2));
        ix.chDepth = add(lin("Effects", "Chorus Depth", 0.f, 1.f, 0.5f));
        ix.dlMix = add(lin("Effects", "Delay Mix", 0.f, 1.f, 0.f));
        ix.dlTime = add(choice("Effects", "Delay Time", {"1/4", "1/4 .", "1/8", "1/8 .", "1/8 T", "1/16", "1/16 .", "1/16 T", "1/2"}, 3));
        ix.dlFb = add(lin("Effects", "Delay Feedback", 0.f, 0.95f, 0.35f));
        ix.dlPing = add(lin("Effects", "Delay Ping-Pong", 0.f, 1.f, 0.5f));
        ix.rvMix = add(lin("Effects", "Reverb Mix", 0.f, 1.f, 0.f));
        ix.rvSize = add(lin("Effects", "Reverb Size", 0.f, 1.f, 0.5f));
        ix.rvDecay = add(logp("Effects", "Reverb Decay", 0.3f, 12.f, 2.5f, "s", 1));
        ix.rvDamp = add(lin("Effects", "Reverb Damping", 0.f, 1.f, 0.4f));
        // -- arpeggiator
        ix.arpOn = add(toggle("Arp", "Arp On"));
        ix.arpMode = add(choice("Arp", "Arp Mode", {"Up", "Down", "Up/Down", "Random", "As Played", "Chord"}, 0));
        ix.arpRate = add(choice("Arp", "Arp Rate", {"1/4", "1/8", "1/8 T", "1/16", "1/16 T", "1/32"}, 3));
        ix.arpOct = add(lin("Arp", "Arp Octaves", 1.f, 4.f, 1.f, "", 0)); specs_[ix.arpOct].curve = Curve::Choice; specs_[ix.arpOct].labels = {"1", "2", "3", "4"}; specs_[ix.arpOct].def = 0; specs_[ix.arpOct].lo = 0; specs_[ix.arpOct].hi = 3;
        ix.arpGate = add(lin("Arp", "Arp Gate", 0.05f, 1.f, 0.6f));
        ix.arpSwing = add(lin("Arp", "Arp Swing", 0.f, 0.6f, 0.f));
        ix.arpHold = add(toggle("Arp", "Arp Hold"));
    }

    const char* name() const override { return "Zedok Hybrid"; }
    bool isInstrument() const override { return true; }

    void prepare(double sr, int maxFrames) override {
        sr_ = sr; maxFrames_ = maxFrames;
        mixL_.assign(maxFrames, 0.f); mixR_.assign(maxFrames, 0.f);
        chorus_.prepare(sr); delay_.prepare(sr); reverb_.prepare(sr);
        for (int i = 0; i < 4096; i++) sinTab_[i] = std::sin(kTwoPi * i / 4096.f);
        sinTab_[4096] = sinTab_[0];
        reset();
    }
    void reset() override {
        for (auto& v : voices_) v = Voice();
        chorus_.reset(); delay_.reset(); reverb_.reset();
        held_.clear(); arpNotes_.clear(); arpStep_ = 0; arpPhase_ = 0.0; arpSounding_ = -1; arpGateOff_ = -1.0; monoStack_.clear(); lastNote_ = -1.f; age_ = 0;
    }

    void process(const ProcessCtx& c, const float* vals) override {
        readParams(vals);
        const int n = c.frames; if (n > maxFrames_) return;
        for (int i = 0; i < c.nEvents; i++) if (c.events[i].type == ZK_ALL_NOTES_OFF && c.events[i].pitch == 1) kill_ = true;   // "silence": the host asks for a hard stop (a new preset is about to be heard)
        std::fill(mixL_.begin(), mixL_.begin() + n, 0.f); std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
        tempo_ = c.tempo > 20.0 ? c.tempo : 120.0;
        if (P_.arpOn != arpWasOn_) { if (!P_.arpOn) { releaseArp(); } arpWasOn_ = P_.arpOn; }
        int pos = 0, ei = 0;
        while (pos < n) {
            while (ei < c.nEvents && (int)c.events[ei].offset <= pos) handleEvent(c.events[ei++]);
            int end = n;
            if (ei < c.nEvents) end = std::min(end, std::max(pos + 1, (int)c.events[ei].offset));
            while (pos < end) {
                int len = std::min(end - pos, kBlock);
                if (P_.arpOn) runArp(c, len);
                renderBlock(pos, len);
                pos += len;
            }
        }
        // effects
        float* L = mixL_.data(); float* R = mixR_.data();
        if (P_.chMix > 0.001f) { chorus_.set(P_.chRate, P_.chDepth, 3, 14.f, 0.f, 0.9f, P_.chMix * 0.7f, 9000.f); chorus_.process(L, R, n); }
        if (P_.dlMix > 0.001f) {
            static const float beats[] = {1.f, 1.5f, 0.5f, 0.75f, 1.f / 3.f, 0.25f, 0.375f, 1.f / 6.f, 2.f};
            const float ms = (float)(60000.0 / tempo_) * beats[P_.dlTime];
            delay_.set(ms, ms * (1.f + 0.0f), P_.dlFb, P_.dlPing, 120.f, 7000.f, 0.f, 0.4f, 0.2f, 0.f, P_.dlMix);
            delay_.process(L, R, n);
        }
        if (P_.rvMix > 0.001f) { reverb_.set(Reverb::Hall, P_.rvSize, P_.rvDecay, P_.rvDamp, 0.75f, 0.4f, 12.f, 120.f, 9500.f, 1.f, 0.f, P_.rvMix * 0.65f); reverb_.process(L, R, n); }
        // output stage: volume with a transparent soft ceiling
        const float g = dbToLin(P_.volume);
        for (int i = 0; i < n; i++) { c.outL[i] = ceil(L[i] * g); c.outR[i] = ceil(R[i] * g); }
        if (kill_) {   // fade this block out, then forget every voice and effect tail so the next sound starts clean
            for (int i = 0; i < n; i++) { const float f = 1.f - (float)(i + 1) / n; c.outL[i] *= f; c.outR[i] *= f; }
            reset(); kill_ = false;
        }
    }

    int telemetry(int what, float* out, int cap) override {
        if (what == 0 || what == 1) {   // one cycle of oscillator A / B as currently set (unison 1), 256 points
            const int n = std::min(cap, 256); const OscP& o = P_.osc[what];
            for (int i = 0; i < n; i++) out[i] = displayWave(o, (float)i / n);
            return n;
        }
        if (what == 2 && cap >= 1) { int a = 0; for (auto& v : voices_) a += v.active ? 1 : 0; out[0] = (float)a; return 1; }
        return 0;
    }

private:
    bool kill_ = false;
    // ---------------------------------------------------------------- parameters
    struct OscIx { int type, wave, table, pos, pw, ratio, index, fb, level, oct, semi, fine, pan, uni, det, spread, phase; };
    struct EnvIx { int a, d, s, r; };
    struct LfoIx { int shape, rate, sync, div, fade, retrig; };
    struct SlotIx { int src, dst, amt; };
    struct Ix {
        int volume, poly, mono, glide, wheel, quality, bend, bendRange;
        int subLevel, subWave, subOct, noiseLevel, noiseColor;
        int f1Type, f1Cut, f1Res, f1Drive, f1Env, f1Key, f2Type, f2Cut, f2Res, f2Key, fRoute;
        int chMix, chRate, chDepth, dlMix, dlTime, dlFb, dlPing, rvMix, rvSize, rvDecay, rvDamp;
        int arpOn, arpMode, arpRate, arpOct, arpGate, arpSwing, arpHold;
        EnvIx env[3]; LfoIx lfo[3]; SlotIx slot[kSlots];
    } ix;
    OscIx osc[2];

    struct OscP { int type, wave, table; float pos, pw, ratio, index, fb, level, oct, semi, fine, pan; int uni; float det, spread, phase; };
    struct EnvP { float a, d, s, r; };
    struct LfoP { int shape; float rate; bool sync; int div; float fade; bool retrig; };
    struct SlotP { int src, dst; float amt; };
    struct Params {
        float volume; int poly, mono; float glide, wheel; bool hq; float bend, bendRange;
        OscP osc[2];
        float subLevel; int subWave, subOct; float noiseLevel, noiseColor;
        int f1Type; float f1Cut, f1Res, f1Drive, f1Env, f1Key; int f2Type; float f2Cut, f2Res, f2Key; int fRoute;
        EnvP env[3]; LfoP lfo[3]; SlotP slot[kSlots];
        float chMix, chRate, chDepth, dlMix; int dlTime; float dlFb, dlPing, rvMix, rvSize, rvDecay, rvDamp;
        bool arpOn; int arpMode, arpRate, arpOct; float arpGate, arpSwing; bool arpHold;
    } P_{};
    bool arpWasOn_ = false;

    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    int idx(const float* v, int i) const { return (int)(specs_[i].toReal(v[i]) + 0.5f); }
    void readParams(const float* v) {
        Params& p = P_;
        p.volume = real(v, ix.volume); p.mono = idx(v, ix.mono); p.glide = real(v, ix.glide); p.wheel = real(v, ix.wheel); p.hq = idx(v, ix.quality) == 1;
        static const int polys[] = {1, 2, 4, 6, 8, 12, 16}; p.poly = polys[idx(v, ix.poly)]; if (!p.hq) p.poly = std::min(p.poly, 8);
        p.bend = real(v, ix.bend); p.bendRange = real(v, ix.bendRange);
        for (int o = 0; o < 2; o++) {
            const OscIx& x = osc[o]; OscP& q = p.osc[o];
            q.type = idx(v, x.type); q.wave = idx(v, x.wave); q.table = idx(v, x.table); q.pos = real(v, x.pos); q.pw = real(v, x.pw); q.ratio = real(v, x.ratio);
            q.index = real(v, x.index); q.fb = real(v, x.fb); q.level = real(v, x.level); q.oct = real(v, x.oct) - 3.f; q.semi = real(v, x.semi); q.fine = real(v, x.fine);
            q.pan = real(v, x.pan); q.uni = idx(v, x.uni) + 1; if (!p.hq) q.uni = std::min(q.uni, 4); q.det = real(v, x.det); q.spread = real(v, x.spread); q.phase = real(v, x.phase);
        }
        p.subLevel = real(v, ix.subLevel); p.subWave = idx(v, ix.subWave); p.subOct = idx(v, ix.subOct); p.noiseLevel = real(v, ix.noiseLevel); p.noiseColor = real(v, ix.noiseColor);
        p.f1Type = idx(v, ix.f1Type); p.f1Cut = real(v, ix.f1Cut); p.f1Res = real(v, ix.f1Res); p.f1Drive = real(v, ix.f1Drive); p.f1Env = real(v, ix.f1Env); p.f1Key = real(v, ix.f1Key);
        p.f2Type = idx(v, ix.f2Type); p.f2Cut = real(v, ix.f2Cut); p.f2Res = real(v, ix.f2Res); p.f2Key = real(v, ix.f2Key); p.fRoute = idx(v, ix.fRoute);
        for (int e = 0; e < 3; e++) { p.env[e].a = real(v, ix.env[e].a) * 0.001f; p.env[e].d = real(v, ix.env[e].d) * 0.001f; p.env[e].s = real(v, ix.env[e].s); p.env[e].r = real(v, ix.env[e].r) * 0.001f; }
        for (int l = 0; l < 3; l++) { LfoP& q = p.lfo[l]; q.shape = idx(v, ix.lfo[l].shape); q.rate = real(v, ix.lfo[l].rate); q.sync = real(v, ix.lfo[l].sync) > 0.5f; q.div = idx(v, ix.lfo[l].div); q.fade = real(v, ix.lfo[l].fade) * 0.001f; q.retrig = real(v, ix.lfo[l].retrig) > 0.5f; }
        for (int m = 0; m < kSlots; m++) { p.slot[m].src = idx(v, ix.slot[m].src); p.slot[m].dst = idx(v, ix.slot[m].dst); p.slot[m].amt = real(v, ix.slot[m].amt); }
        p.chMix = real(v, ix.chMix); p.chRate = real(v, ix.chRate); p.chDepth = real(v, ix.chDepth);
        p.dlMix = real(v, ix.dlMix); p.dlTime = idx(v, ix.dlTime); p.dlFb = real(v, ix.dlFb); p.dlPing = real(v, ix.dlPing);
        p.rvMix = real(v, ix.rvMix); p.rvSize = real(v, ix.rvSize); p.rvDecay = real(v, ix.rvDecay); p.rvDamp = real(v, ix.rvDamp);
        p.arpOn = real(v, ix.arpOn) > 0.5f; p.arpMode = idx(v, ix.arpMode); p.arpRate = idx(v, ix.arpRate); p.arpOct = idx(v, ix.arpOct) + 1; p.arpGate = real(v, ix.arpGate); p.arpSwing = real(v, ix.arpSwing); p.arpHold = real(v, ix.arpHold) > 0.5f;
    }

    static inline float ceil(float x) {   // transparent below 0.9, smooth saturation above
        const float a = std::fabs(x); if (a <= 0.9f) return x;
        const float y = 0.9f + 0.1f * fastTanh((a - 0.9f) * 10.f); return x < 0 ? -y : y;
    }

    // ---------------------------------------------------------------- voices
    struct Uni {   // one oscillator with its unison copies
        float ph[kMaxUni] = {}, mph[kMaxUni] = {}, fbs[kMaxUni] = {}, tri[kMaxUni] = {};
    };
    struct Voice {
        bool active = false, gate = false; int note = 60; float vel = 1.f; uint64_t age = 0;
        float curNote = 60.f; float rnd = 0.f;
        Uni a, b; float subPh = 0.f, subTri = 0.f; float noiseLp = 0.f, noiseB = 0.f;
        Adsr env[3]; float envVal[3] = {};
        float lfoPh[3] = {}, lfoVal[3] = {}, lfoSh[3] = {}, lfoSm[3] = {}, lfoT[3] = {};
        SVF f1[2], f1b[2], f2[2]; Ladder ladder[2]; DelayLine comb[2];
        float f1Cut = 1000.f, f2Cut = 1000.f; bool primed = false;
        float rel = 0.f; NoteMod mod;   // per-note properties of the note that started this voice
        Voice() { comb[0].init(2048); comb[1].init(2048); }
    };

    inline float sinT(float ph) const { const float x = ph * 4096.f; const int i = (int)x; const float f = x - i; return sinTab_[i & 4095] + (sinTab_[(i + 1) & 4095] - sinTab_[i & 4095]) * f; }

    // Evaluates one oscillator sample at phase ph (0..1) for the preview display and for the analog/wavetable paths.
    float wtRead(const WaveTable& t, float pos, float ph, int mip) const {
        const float fp = clampf(pos, 0.f, 1.f) * (kWtFrames - 1); const int f0 = std::min((int)fp, kWtFrames - 2); const float ff = fp - f0;
        const float x = ph * kWtLen; const int i = (int)x; const float fr = x - i;
        const float* a = t.cycle(f0, mip); const float* b = t.cycle(f0 + 1, mip);
        const float va = a[i] + (a[i + 1] - a[i]) * fr, vb = b[i] + (b[i + 1] - b[i]) * fr;
        return va + (vb - va) * ff;
    }
    float displayWave(const OscP& o, float ph) const {
        switch (o.type) {
        case 0:
            switch (o.wave) {
            case 0: return 2.f * ph - 1.f;
            case 1: return ph < o.pw ? 1.f : -1.f;
            case 2: return 4.f * std::fabs(ph - 0.5f) - 1.f;
            default: return std::sin(kTwoPi * ph);
            }
        case 1: return wtRead((*tables_)[std::min(o.table, (int)tables_->size() - 1)], o.pos, ph, 3);
        default: { const float m = std::sin(kTwoPi * ph * o.ratio); return std::sin(kTwoPi * ph + o.index * m * 0.5f); }
        }
    }

    // Renders one sample of an oscillator with unison. Returns false stereo contribution via L/R.
    inline void oscTick(const OscP& o, Uni& u, int nu, const float* dt, const float* gl, const float* gr, float pwv, float posv, float idxv, int mip, const WaveTable* tbl, float& L, float& R) {
        float l = 0.f, r = 0.f;
        for (int i = 0; i < nu; i++) {
            float& ph = u.ph[i]; const float d = dt[i];
            float y;
            switch (o.type) {
            case 0: {
                switch (o.wave) {
                case 0: y = 2.f * ph - 1.f; y -= polyBlep(ph, d); break;
                case 1: { y = ph < pwv ? 1.f : -1.f; float t2 = ph - pwv; if (t2 < 0.f) t2 += 1.f; y += polyBlep(ph, d) - polyBlep(t2, d); y *= 0.85f; break; }
                case 2: { float sq = ph < 0.5f ? 1.f : -1.f; float t2 = ph + 0.5f; if (t2 >= 1.f) t2 -= 1.f; sq += polyBlep(ph, d) - polyBlep(t2, d); u.tri[i] = u.tri[i] * 0.9995f + sq * 4.f * d; y = u.tri[i]; break; }
                default: y = sinT(ph); break;
                }
                break;
            }
            case 1: y = wtRead(*tbl, posv, ph, mip); break;
            default: {
                float& mp = u.mph[i]; const float m = sinT(mp + u.fbs[i] * o.fb * 0.25f);
                u.fbs[i] = m; y = sinT(ph + idxv * m * 0.1591549f);
                mp += d * o.ratio; mp -= std::floor(mp);
                break;
            }
            }
            ph += d; if (ph >= 1.f) ph -= 1.f;
            l += y * gl[i]; r += y * gr[i];
        }
        L = l; R = r;
    }

    void noteOnVoice(Voice& v, int note, int vel, bool retrigger, const NoteMod& mod = NoteMod()) {
        const bool wasActive = v.active; v.mod = mod;
        v.active = true; v.gate = true; v.note = note; v.vel = vel / 127.f; v.age = ++age_;
        const float target = (float)note;
        v.curNote = (P_.glide > 0.5f && lastNote_ >= 0.f) ? lastNote_ : target;
        if (retrigger || !wasActive) {
            v.rnd = rng_.bi();
            for (int e = 0; e < 3; e++) v.env[e].gate(true);
            if (!wasActive) { for (int e = 0; e < 3; e++) v.env[e].v = 0.f; }
            for (int l = 0; l < 3; l++) { v.lfoPh[l] = P_.lfo[l].retrig ? 0.f : freeLfo_[l]; v.lfoT[l] = 0.f; v.lfoSh[l] = rng_.bi(); v.lfoSm[l] = v.lfoSh[l]; }
            // random start phases for unison (free running)
            for (int i = 0; i < kMaxUni; i++) { v.a.ph[i] = rng_.uni() * (1.f - P_.osc[0].phase) + P_.osc[0].phase * 0.f; v.b.ph[i] = rng_.uni() * (1.f - P_.osc[1].phase); v.a.mph[i] = v.b.mph[i] = 0.f; v.a.fbs[i] = v.b.fbs[i] = 0.f; v.a.tri[i] = v.b.tri[i] = 0.f; }
            if (!wasActive) { v.f1b[0].reset(); v.f1b[1].reset(); v.f1[0].reset(); v.f1[1].reset(); v.f2[0].reset(); v.f2[1].reset(); v.ladder[0].reset(); v.ladder[1].reset(); v.comb[0].clear(); v.comb[1].clear(); v.primed = false; v.subPh = 0.f; v.subTri = 0.f; }
        }
        lastNote_ = target;
    }

    Voice* allocVoice() {
        const int limit = std::max(1, std::min(P_.poly, kMaxVoices));
        int activeCount = 0; for (int i = 0; i < kMaxVoices; i++) if (voices_[i].active) activeCount++;
        for (int i = 0; i < kMaxVoices; i++) if (!voices_[i].active && activeCount < limit) return &voices_[i];
        // steal: quietest releasing voice, else the oldest
        Voice* best = nullptr; float bestL = 1e9f;
        for (auto& v : voices_) if (v.active && !v.gate && v.env[0].v < bestL) { bestL = v.env[0].v; best = &v; }
        if (best) return best;
        for (auto& v : voices_) if (v.active && (!best || v.age < best->age)) best = &v;
        return best;
    }

    void doNoteOn(int note, int vel, const NoteMod& mod = NoteMod()) {
        if (P_.mono != 0) {   // mono / legato
            monoStack_.erase(std::remove(monoStack_.begin(), monoStack_.end(), note), monoStack_.end()); monoStack_.push_back(note);
            Voice& v = voices_[0];
            const bool legato = P_.mono == 2 && v.active && v.gate;
            for (int i = 1; i < kMaxVoices; i++) if (voices_[i].active) { voices_[i].gate = false; for (auto& e : voices_[i].env) e.gate(false); }
            noteOnVoice(v, note, vel, !legato, mod);
            return;
        }
        // same note already sounding: retrigger that voice
        for (auto& v : voices_) if (v.active && v.gate && v.note == note) { noteOnVoice(v, note, vel, true, mod); return; }
        if (Voice* v = allocVoice()) { v->env[0].kill(); noteOnVoice(*v, note, vel, true, mod); }
    }
    void doNoteOff(int note) {
        if (P_.mono != 0) {
            monoStack_.erase(std::remove(monoStack_.begin(), monoStack_.end(), note), monoStack_.end());
            Voice& v = voices_[0];
            if (!monoStack_.empty()) { if (v.note == note) { const int prev = monoStack_.back(); noteOnVoice(v, prev, (int)(v.vel * 127), P_.mono != 2, v.mod); } return; }
            if (v.active && v.note == note) { v.gate = false; for (auto& e : v.env) e.gate(false); }
            return;
        }
        for (auto& v : voices_) if (v.active && v.gate && v.note == note) { v.gate = false; for (auto& e : v.env) e.gate(false); }
    }
    void allOff() { for (auto& v : voices_) if (v.active) { v.gate = false; for (auto& e : v.env) e.gate(false); } monoStack_.clear(); }

    void handleEvent(const ZkEvent& e) {
        if (e.type == ZK_ALL_NOTES_OFF) { allOff(); held_.clear(); arpNotes_.clear(); releaseArp(); return; }
        if (P_.arpOn) {
            if (e.type == ZK_NOTE_ON) {
                if (P_.arpHold && !heldNow_) { held_.clear(); }
                if (std::find(held_.begin(), held_.end(), (int)e.pitch) == held_.end()) held_.push_back(e.pitch);
                heldNow_++; velLast_ = e.velocity ? e.velocity : 100; rebuildArp();
            } else if (e.type == ZK_NOTE_OFF) {
                heldNow_ = std::max(0, heldNow_ - 1);
                if (!P_.arpHold) { held_.erase(std::remove(held_.begin(), held_.end(), (int)e.pitch), held_.end()); rebuildArp(); }
            }
            return;
        }
        if (e.type == ZK_NOTE_ON) { if (e.velocity == 0) doNoteOff(e.pitch); else doNoteOn(e.pitch, e.velocity, NoteMod::from(e)); }
        else if (e.type == ZK_NOTE_OFF) doNoteOff(e.pitch);
    }

    // ---------------------------------------------------------------- arpeggiator
    void rebuildArp() {
        arpNotes_.clear();
        std::vector<int> base = held_;
        if (P_.arpMode != 4) std::sort(base.begin(), base.end());
        if (P_.arpMode == 1) std::reverse(base.begin(), base.end());
        for (int o = 0; o < P_.arpOct; o++) for (int n : base) arpNotes_.push_back(n + 12 * o);
        if (P_.arpMode == 1) { std::vector<int> d; for (int o = P_.arpOct - 1; o >= 0; o--) for (int n : base) d.push_back(n + 12 * o); arpNotes_ = d; }
        if (P_.arpMode == 2 && arpNotes_.size() > 2) { std::vector<int> bk(arpNotes_.begin() + 1, arpNotes_.end() - 1); std::reverse(bk.begin(), bk.end()); arpNotes_.insert(arpNotes_.end(), bk.begin(), bk.end()); }
    }
    void releaseArp() { if (arpSounding_ >= 0) { for (int k : arpChord_) doNoteOff(k); arpChord_.clear(); arpSounding_ = -1; } if (!P_.arpOn) { held_.clear(); heldNow_ = 0; arpNotes_.clear(); } }
    void runArp(const ProcessCtx& c, int len) {
        static const double stepBeats[] = {1.0, 0.5, 1.0 / 3.0, 0.25, 1.0 / 6.0, 0.125};
        const double sb = stepBeats[P_.arpRate];
        const double perSample = tempo_ / 60.0 / sr_;
        if (c.playing && c.ppq >= 0.0 && !arpSync_) { arpSync_ = true; }
        // A step boundary is crossed whenever arpPhase_ passes a multiple of the step length.
        const double endPos = arpPhase_ + perSample * len;
        // gate-off
        if (arpSounding_ >= 0 && arpGateOff_ >= 0.0 && endPos >= arpGateOff_) { for (int k : arpChord_) doNoteOff(k); arpChord_.clear(); arpSounding_ = -1; arpGateOff_ = -1.0; }
        const double stepIdx0 = std::floor(arpPhase_ / sb), stepIdx1 = std::floor(endPos / sb);
        if (stepIdx1 > stepIdx0 || (arpFirst_ && !arpNotes_.empty())) {
            arpFirst_ = false;
            if (!arpNotes_.empty()) {
                if (arpSounding_ >= 0) { for (int k : arpChord_) doNoteOff(k); arpChord_.clear(); arpSounding_ = -1; }
                int note;
                if (P_.arpMode == 3) note = arpNotes_[rng_.next() % arpNotes_.size()]; else note = arpNotes_[arpStep_ % arpNotes_.size()];
                if (P_.arpMode == 5) { for (int h : held_) for (int o = 0; o < P_.arpOct; o++) { arpChord_.push_back(h + 12 * (o == 0 ? 0 : o)); break; } }
                else arpChord_.push_back(note);
                const bool odd = ((long long)stepIdx1) & 1;
                const int vel = velLast_;
                for (int k : arpChord_) doNoteOn(k, std::max(1, (int)(vel * (odd ? 0.9f : 1.f))));
                arpSounding_ = note; arpStep_++;
                const double gate = std::max(0.05, (double)P_.arpGate);
                arpGateOff_ = stepIdx1 * sb + sb * gate + (odd ? sb * P_.arpSwing * 0.0 : 0.0);
            } else arpStep_ = 0;
        }
        arpPhase_ = endPos;
        if (c.playing && c.ppq >= 0.0 && std::fabs(arpPhase_ - arpPpqNext_) > 0.5 && false) arpPhase_ = c.ppq;
    }

    // ---------------------------------------------------------------- per-block voice rendering
    static float lfoDivBeats(int d) { static const float b[] = {16.f, 8.f, 4.f, 2.f, 1.f, .5f, .25f, 2.f / 3.f, 1.f / 3.f, 1.f / 6.f, 1.5f, .75f}; return b[d]; }

    float lfoShape(int shape, float ph, Voice& v, int l, bool wrapped) {
        switch (shape) {
        case 0: return sinT(ph);
        case 1: return 4.f * std::fabs(ph - 0.5f) - 1.f;
        case 2: return 2.f * ph - 1.f;
        case 3: return ph < 0.5f ? 1.f : -1.f;
        case 4: if (wrapped) v.lfoSh[l] = rng_.bi(); return v.lfoSh[l];
        default: { if (wrapped) { v.lfoSm[l] = v.lfoSh[l]; v.lfoSh[l] = rng_.bi(); } const float t = ph * ph * (3.f - 2.f * ph); return v.lfoSm[l] + (v.lfoSh[l] - v.lfoSm[l]) * t; }
        }
    }

    void renderBlock(int pos, int len) {
        const float dtBlock = len / (float)sr_;
        for (int l = 0; l < 3; l++) { float inc = (P_.lfo[l].sync ? (float)(tempo_ / 60.0) / lfoDivBeats(P_.lfo[l].div) : P_.lfo[l].rate) * dtBlock; freeLfo_[l] += inc; freeLfo_[l] -= std::floor(freeLfo_[l]); }
        for (auto& v : voices_) if (v.active) renderVoice(v, pos, len);
    }

    void renderVoice(Voice& v, int pos, int len) {
        const Params& p = P_;
        // ---- modulation sources
        for (int e = 0; e < 3; e++) { v.env[e].set(sr_, p.env[e].a, p.env[e].d, p.env[e].s, p.env[e].r * v.mod.relScale); }
        float srcv[kNumSrc] = {};
        const float dtBlock = len / (float)sr_;
        for (int l = 0; l < 3; l++) {
            float rate = p.lfo[l].sync ? (float)(tempo_ / 60.0) / lfoDivBeats(p.lfo[l].div) : p.lfo[l].rate;
            if (l < 2) { /* LFO rate modulation applied below via dst */ }
            v.lfoT[l] += dtBlock;
            const float old = v.lfoPh[l];
            v.lfoPh[l] += rate * dtBlock; const bool wrapped = v.lfoPh[l] >= 1.f; if (wrapped) v.lfoPh[l] -= std::floor(v.lfoPh[l]);
            (void)old;
            const float fade = p.lfo[l].fade > 0.001f ? std::min(1.f, v.lfoT[l] / p.lfo[l].fade) : 1.f;
            v.lfoVal[l] = lfoShape(p.lfo[l].shape, v.lfoPh[l], v, l, wrapped) * fade;
        }
        for (int e = 1; e < 3; e++) { float x = 0.f; for (int i = 0; i < len; i++) x = v.env[e].tick(); v.envVal[e] = x; }
        srcv[SrcLfo1] = v.lfoVal[0]; srcv[SrcLfo2] = v.lfoVal[1]; srcv[SrcLfo3] = v.lfoVal[2];
        srcv[SrcEnv2] = v.envVal[1]; srcv[SrcEnv3] = v.envVal[2]; srcv[SrcVelocity] = v.vel; srcv[SrcKey] = (v.curNote - 60.f) / 60.f; srcv[SrcWheel] = p.wheel; srcv[SrcRandom] = v.rnd;
        float dst[kNumDst] = {};
        for (int m = 0; m < kSlots; m++) if (p.slot[m].src && p.slot[m].dst) dst[p.slot[m].dst] += srcv[p.slot[m].src] * p.slot[m].amt;
        dst[DstCutoff1] += v.mod.cutoff * 0.6f; dst[DstRes1] += v.mod.res; dst[DstPan] += v.mod.pan;   // per-note properties: cutoff +-3 octaves, resonance +-0.5, pan
        // glide
        if (p.glide > 0.5f) { const float a = 1.f - std::exp(-dtBlock * 1000.f / std::max(p.glide, 1.f) * 3.f); v.curNote += ((float)v.note - v.curNote) * a; } else v.curNote = (float)v.note;

        // ---- oscillator setup
        Uni* uni[2] = {&v.a, &v.b};
        float dt[2][kMaxUni], gl[2][kMaxUni], gr[2][kMaxUni]; int nu[2]; float lvl[2], pwv[2], posv[2], idxv[2]; int mip[2] = {0, 0}; const WaveTable* tbl[2] = {nullptr, nullptr};
        const float bendSemi = p.bend * p.bendRange;
        for (int o = 0; o < 2; o++) {
            const OscP& q = p.osc[o];
            const float semis = v.curNote + bendSemi + q.oct * 12.f + q.semi + q.fine * 0.01f + v.mod.fine * 0.01f + dst[DstPitch] * 12.f + dst[o == 0 ? DstAPitch : DstBPitch] * 12.f;
            const float f0 = midiToHz(semis);
            nu[o] = q.uni;
            const float det = q.det * (1.f + dst[DstDetune]);
            for (int i = 0; i < nu[o]; i++) {
                const float t = nu[o] == 1 ? 0.f : (2.f * i / (nu[o] - 1) - 1.f);
                const float f = f0 * semiToRatio(t * det * 0.01f);
                dt[o][i] = clampf(f / (float)sr_, 1e-5f, 0.45f);
                const float pan = clampf(q.pan + t * q.spread, -1.f, 1.f);
                const float g = 1.f / std::sqrt((float)nu[o]) * (nu[o] > 1 ? 1.0f : 1.f);
                gl[o][i] = g * std::cos((pan * 0.5f + 0.5f) * 1.5707963f); gr[o][i] = g * std::sin((pan * 0.5f + 0.5f) * 1.5707963f);
            }
            lvl[o] = clampf(q.level + dst[o == 0 ? DstALevel : DstBLevel], 0.f, 1.f);
            pwv[o] = clampf(q.pw + dst[o == 0 ? DstAPw : DstBPw] * 0.45f, 0.05f, 0.95f);
            posv[o] = clampf(q.pos + dst[o == 0 ? DstAPos : DstBPos], 0.f, 1.f);
            idxv[o] = clampf(q.index * (1.f + dst[o == 0 ? DstAIndex : DstBIndex] * 2.f), 0.f, 14.f) / (1.f + f0 * 0.0003f);
            if (q.type == 1) {
                tbl[o] = &(*tables_)[std::min(q.table, (int)tables_->size() - 1)];
                const float maxH = std::max(1.f, 0.46f * (float)sr_ / (f0 * (1.f + det * 0.01f * 0.06f)));
                mip[o] = std::min(kWtMips - 1, std::max(0, (int)std::ceil(std::log2(1024.f / maxH))));
            }
        }
        // sub / noise
        const float subRatio = p.subOct == 0 ? 0.5f : 0.25f;
        const float subF = midiToHz(v.curNote + bendSemi + v.mod.fine * 0.01f) * subRatio; const float subDt = subF / (float)sr_;
        const float subL = clampf(p.subLevel + dst[DstSub], 0.f, 1.f), noiL = clampf(p.noiseLevel + dst[DstNoise], 0.f, 1.f);
        // ---- filter setup (per block)
        const float keyOct = (v.curNote - 60.f) / 12.f;
        float cut1 = p.f1Cut * std::exp2(keyOct * p.f1Key + v.envVal[1] * p.f1Env * 6.f + dst[DstCutoff1] * 5.f);
        cut1 = clampf(cut1, 20.f, 20000.f);
        const float res1 = clampf(p.f1Res + dst[DstRes1] * 0.5f, 0.f, 1.f), drv = clampf(p.f1Drive + dst[DstDrive] * 0.5f, 0.f, 1.f);
        float cut2 = clampf(p.f2Cut * std::exp2(keyOct * p.f2Key + dst[DstCutoff2] * 5.f), 20.f, 20000.f);
        const float res2 = clampf(p.f2Res + dst[DstRes2] * 0.5f, 0.f, 1.f);
        // smooth the cutoff between blocks (geometric)
        if (!v.primed) { v.f1Cut = cut1; v.f2Cut = cut2; v.primed = true; }
        const float cs1 = std::pow(cut1 / v.f1Cut, 1.f / len), cs2 = std::pow(cut2 / v.f2Cut, 1.f / len);
        float curC1 = v.f1Cut, curC2 = v.f2Cut;
        const int sub = 4; // coefficient update interval inside the block
        float combDelay = 100.f;
        // gains
        const float ampMod = std::max(0.f, 1.f + dst[DstAmp]);
        const float panM = clampf(dst[DstPan], -1.f, 1.f);
        const float panL = std::cos((panM * 0.5f + 0.5f) * 1.5707963f) * 1.4142f, panR = std::sin((panM * 0.5f + 0.5f) * 1.5707963f) * 1.4142f;
        const float velG = 0.35f + 0.65f * v.vel;
        const float oscG = 0.5f;   // headroom: two oscillators, sub and noise sum together

        float* oL = mixL_.data() + pos; float* oR = mixR_.data() + pos;
        for (int i = 0; i < len; i++) {
            if ((i % sub) == 0) {
                // refresh filter coefficients
                switch (p.f1Type) {
                case 0: for (int c = 0; c < 2; c++) v.f1[c].set(sr_, curC1, res1 * 0.92f); break;
                case 1: for (int c = 0; c < 2; c++) { v.f1[c].set(sr_, curC1, res1 * 0.85f); v.f1b[c].set(sr_, curC1, res1 * 0.2f); } break;
                case 2: for (int c = 0; c < 2; c++) v.ladder[c].set(sr_, curC1, res1, drv); break;
                case 3: case 4: case 5: for (int c = 0; c < 2; c++) v.f1[c].set(sr_, curC1, res1 * 0.92f); break;
                default: combDelay = clampf((float)sr_ / curC1, 2.f, 1900.f); break;
                }
                if (p.f2Type != 0) for (int c = 0; c < 2; c++) v.f2[c].set(sr_, curC2, res2 * 0.92f);
            }
            // oscillators
            float aL = 0.f, aR = 0.f, bL = 0.f, bR = 0.f;
            if (lvl[0] > 0.0005f) oscTick(p.osc[0], *uni[0], nu[0], dt[0], gl[0], gr[0], pwv[0], posv[0], idxv[0], mip[0], tbl[0], aL, aR);
            else advance(*uni[0], nu[0], dt[0]);
            if (lvl[1] > 0.0005f) oscTick(p.osc[1], *uni[1], nu[1], dt[1], gl[1], gr[1], pwv[1], posv[1], idxv[1], mip[1], tbl[1], bL, bR);
            else advance(*uni[1], nu[1], dt[1]);
            float mL = (aL * lvl[0] + bL * lvl[1]) * oscG, mR = (aR * lvl[0] + bR * lvl[1]) * oscG;
            if (subL > 0.0005f) {
                v.subPh += subDt; if (v.subPh >= 1.f) v.subPh -= 1.f;
                float s;
                if (p.subWave == 0) s = sinT(v.subPh);
                else if (p.subWave == 1) s = 4.f * std::fabs(v.subPh - 0.5f) - 1.f;
                else { s = v.subPh < 0.5f ? 1.f : -1.f; float t2 = v.subPh + 0.5f; if (t2 >= 1.f) t2 -= 1.f; s += polyBlep(v.subPh, subDt) - polyBlep(t2, subDt); s *= 0.7f; }
                mL += s * subL * 0.5f; mR += s * subL * 0.5f;
            }
            if (noiL > 0.0005f) {
                float w = rng_.bi();
                // colour: 0 white .. 0.5 pink-ish (low-pass tilt) .. 1 brown-ish
                const float a = 0.02f + p.noiseColor * 0.97f * 0.98f;
                v.noiseLp = v.noiseLp * a + w * (1.f - a) * (1.f + 4.f * a); const float n = lerp(w, v.noiseLp * 1.6f, clampf(p.noiseColor * 1.6f, 0.f, 1.f));
                mL += n * noiL * 0.35f; mR += n * noiL * 0.35f;
            }
            // filters
            float ol = filter1(v, 0, mL, p.f1Type, drv, combDelay, res1), or_ = filter1(v, 1, mR, p.f1Type, drv, combDelay, res1);
            if (p.f2Type != 0) {
                const float l2 = filter2(v, 0, p.f2Type, p.fRoute == 0 ? ol : mL), r2 = filter2(v, 1, p.f2Type, p.fRoute == 0 ? or_ : mR);
                if (p.fRoute == 0) { ol = l2; or_ = r2; } else { ol = 0.5f * (ol + l2); or_ = 0.5f * (or_ + r2); }
            }
            const float amp = v.env[0].tick();
            const float g = amp * velG * ampMod;
            oL[i] += ol * g * panL; oR[i] += or_ * g * panR;
            curC1 *= cs1; curC2 *= cs2;
        }
        v.f1Cut = cut1; v.f2Cut = cut2;
        if (!v.env[0].active()) v.active = false;
    }
    void advance(Uni& u, int nu, const float* dt) { for (int i = 0; i < nu; i++) { u.ph[i] += dt[i]; if (u.ph[i] >= 1.f) u.ph[i] -= 1.f; } }

    inline float filter1(Voice& v, int ch, float x, int type, float drive, float combDelay, float res) {
        if (type != 2 && drive > 0.001f) { const float d = 1.f + drive * 4.f; x = fastTanh(x * d) * (1.f / (1.f + drive)); }
        switch (type) {
        case 0: return v.f1[ch].tick(x).lp;
        case 1: { const float y = v.f1[ch].tick(x).lp; return v.f1b[ch].tick(y).lp; }
        case 2: return v.ladder[ch].tick(x);
        case 3: return v.f1[ch].tick(x).hp;
        case 4: { auto o = v.f1[ch].tick(x); return o.bp * v.f1[ch].k * 0.7f; }
        case 5: { auto o = v.f1[ch].tick(x); return o.lp + o.hp; }
        default: {  // comb: feedback comb with the delay set by the cutoff
            DelayLine& d = v.comb[ch]; const float y = x + res * 0.93f * d.read(combDelay); d.push(denorm(y)); return y * (1.f - res * 0.45f);
        }
        }
    }
    inline float filter2(Voice& v, int ch, int type, float x) {
        auto o = v.f2[ch].tick(x);
        switch (type) { case 1: return o.lp; case 2: return o.hp; case 3: return o.bp * v.f2[ch].k * 0.7f; default: return o.lp + o.hp; }
    }

    // ---------------------------------------------------------------- state
    std::shared_ptr<const std::vector<WaveTable>> tables_;
    int maxFrames_ = 128; std::vector<float> mixL_, mixR_;
    Voice voices_[kMaxVoices]; Rng rng_{0xC0FFEEu}; uint64_t age_ = 0; float freeLfo_[3] = {}; float lastNote_ = -1.f; double tempo_ = 120.0;
    std::vector<int> held_, arpNotes_, arpChord_, monoStack_; int heldNow_ = 0, velLast_ = 100; size_t arpStep_ = 0; double arpPhase_ = 0.0, arpGateOff_ = -1.0, arpPpqNext_ = 0.0; int arpSounding_ = -1; bool arpSync_ = false, arpFirst_ = true;
    Chorus chorus_; Delay delay_; Reverb reverb_; float sinTab_[4097];
};

}  // namespace zdsp
