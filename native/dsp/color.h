// Zedok Color: a modulation effect (chorus, ensemble, flanger, phaser) and a distortion section (saturation types, bit crusher),
// in either order, with an overall dry/wet.
#pragma once
#include "fx.h"
#include "processor.h"

namespace zdsp {

class Color : public Processor {
public:
    Color() {
        ix_.modType = add(choice("Modulation", "Mod Type", {"Off", "Chorus", "Ensemble", "Flanger", "Phaser"}, 1));
        ix_.rate = add(logp("Modulation", "Rate", 0.03f, 12.f, 0.6f, "Hz", 2));
        ix_.sync = add(toggle("Modulation", "Mod Sync"));
        ix_.div = add(choice("Modulation", "Mod Division", {"4 bars", "2 bars", "1 bar", "1/2", "1/4", "1/8", "1/16"}, 2));
        ix_.depth = add(lin("Modulation", "Depth", 0.f, 1.f, 0.5f));
        ix_.center = add(logp("Modulation", "Center", 0.3f, 25.f, 10.f, "ms", 1));
        ix_.phCenter = add(logp("Modulation", "Phaser Center", 80.f, 4000.f, 700.f, "Hz", 0));
        ix_.fb = add(lin("Modulation", "Mod Feedback", -0.95f, 0.95f, 0.f));
        ix_.voices = add(choice("Modulation", "Voices", {"1", "2", "3", "4"}, 2));
        ix_.stages = add(choice("Modulation", "Stages", {"2", "4", "6", "8", "12"}, 1));
        ix_.spread = add(lin("Modulation", "Stereo Spread", 0.f, 1.f, 0.8f));
        ix_.modTone = add(logp("Modulation", "Mod Tone", 1000.f, 20000.f, 12000.f, "Hz", 0));
        ix_.modMix = add(lin("Modulation", "Mod Mix", 0.f, 1.f, 0.5f));
        ix_.distOn = add(toggle("Distortion", "Dist On", true));
        ix_.distType = add(choice("Distortion", "Dist Type", {"Soft", "Hard", "Tube", "Tape", "Fold", "Rectify", "Sine"}, 0));
        ix_.drive = add(lin("Distortion", "Drive", 0.f, 48.f, 12.f, "dB", 1));
        ix_.bias = add(lin("Distortion", "Bias", -1.f, 1.f, 0.f));
        ix_.tone = add(logp("Distortion", "Tone", 500.f, 20000.f, 12000.f, "Hz", 0));
        ix_.preHp = add(logp("Distortion", "Pre High-Pass", 20.f, 2000.f, 20.f, "Hz", 0));
        ix_.hq = add(toggle("Distortion", "Oversampling", true));
        ix_.distMix = add(lin("Distortion", "Dist Mix", 0.f, 1.f, 1.f));
        ix_.distOut = add(lin("Distortion", "Dist Output", -18.f, 12.f, 0.f, "dB", 1));
        ix_.crushOn = add(toggle("Crusher", "Crush On", false));
        ix_.bits = add(lin("Crusher", "Bits", 1.f, 16.f, 8.f, "bit", 1));
        ix_.down = add(pw("Crusher", "Downsample", 1.f, 64.f, 1.f, 3.f, "x", 1));
        ix_.jitter = add(lin("Crusher", "Jitter", 0.f, 1.f, 0.f));
        ix_.crushMix = add(lin("Crusher", "Crush Mix", 0.f, 1.f, 1.f));
        ix_.order = add(choice("Main", "Order", {"Mod > Dist", "Dist > Mod"}, 0));
        ix_.master = add(lin("Main", "Dry/Wet", 0.f, 1.f, 1.f));
        ix_.output = add(lin("Main", "Output Gain", -24.f, 12.f, 0.f, "dB", 1));
    }
    const char* name() const override { return "Zedok Color"; }
    bool isInstrument() const override { return false; }
    void prepare(double sr, int maxFrames) override { sr_ = sr; chorus_.prepare(sr); chorus2_.prepare(sr); phaser_.prepare(sr); sat_.prepare(sr); crush_.prepare(sr); dryL_.assign(maxFrames, 0.f); dryR_.assign(maxFrames, 0.f); reset(); }
    void reset() override { chorus_.reset(); chorus2_.reset(); phaser_.reset(); sat_.reset(); crush_.reset(); }
    int latency() const override { return 0; }

    void process(const ProcessCtx& c, const float* v) override {
        const int n = c.frames; if (n > (int)dryL_.size()) return;
        float* L = c.outL; float* R = c.outR;
        if (c.inL != L) { std::memcpy(L, c.inL, sizeof(float) * n); std::memcpy(R, c.inR, sizeof(float) * n); }
        std::memcpy(dryL_.data(), L, sizeof(float) * n); std::memcpy(dryR_.data(), R, sizeof(float) * n);
        const int mt = (int)(real(v, ix_.modType) + 0.5f);
        static const float beatsDiv[] = {16.f, 8.f, 4.f, 2.f, 1.f, .5f, .25f};
        float rate = real(v, ix_.rate);
        if (real(v, ix_.sync) > 0.5f) rate = (float)((c.tempo > 20 ? c.tempo : 120.0) / 60.0) / beatsDiv[(int)(real(v, ix_.div) + 0.5f)];
        const float depth = real(v, ix_.depth), mix = real(v, ix_.modMix), spread = real(v, ix_.spread), tone = real(v, ix_.modTone), fb = real(v, ix_.fb);
        const int voices = (int)(real(v, ix_.voices) + 0.5f) + 1;
        static const int stageTab[] = {2, 4, 6, 8, 12};
        // modulation stage settings
        if (mt == 1) chorus_.set(rate, depth, voices, real(v, ix_.center) + 8.f, 0.f, spread, mix, tone);
        else if (mt == 2) { chorus_.set(rate, depth, 4, 14.f, 0.f, spread, mix, tone); chorus2_.set(rate * 0.37f, depth * 0.6f, 4, 22.f, 0.f, spread, 0.5f, tone); }
        else if (mt == 3) chorus_.set(rate, depth, 1, std::max(0.3f, real(v, ix_.center) * 0.35f), fb, spread, mix, tone);
        else if (mt == 4) phaser_.set(stageTab[(int)(real(v, ix_.stages) + 0.5f)], rate, depth, real(v, ix_.phCenter), fb, spread, mix);
        sat_.set((Saturator::Type)(int)(real(v, ix_.distType) + 0.5f), real(v, ix_.drive), real(v, ix_.bias), real(v, ix_.tone), real(v, ix_.hq) > 0.5f, real(v, ix_.distMix), real(v, ix_.distOut), real(v, ix_.preHp));
        crush_.set(real(v, ix_.bits), real(v, ix_.down), real(v, ix_.jitter), real(v, ix_.crushMix));
        auto doMod = [&] { if (mt == 1 || mt == 3) chorus_.process(L, R, n); else if (mt == 2) { chorus_.process(L, R, n); chorus2_.process(L, R, n); } else if (mt == 4) phaser_.process(L, R, n); };
        auto doDist = [&] { if (real(v, ix_.distOn) > 0.5f) sat_.process(L, R, n); if (real(v, ix_.crushOn) > 0.5f) crush_.process(L, R, n); };
        if ((int)(real(v, ix_.order) + 0.5f) == 0) { doMod(); doDist(); } else { doDist(); doMod(); }
        const float wet = real(v, ix_.master), g = dbToLin(real(v, ix_.output));
        for (int i = 0; i < n; i++) { L[i] = (dryL_[i] * (1.f - wet) + L[i] * wet) * g; R[i] = (dryR_[i] * (1.f - wet) + R[i] * wet) * g; }
    }
    // 0: transfer curve of the distortion, 128 points for inputs -1..1
    int telemetry(int what, float* out, int cap) override { if (what == 0 && cap >= 128) { for (int i = 0; i < 128; i++) out[i] = sat_.curve(-1.f + 2.f * i / 127.f); return 128; } return 0; }

private:
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    struct Ix { int modType, rate, sync, div, depth, center, phCenter, fb, voices, stages, spread, modTone, modMix, distOn, distType, drive, bias, tone, preHp, hq, distMix, distOut, crushOn, bits, down, jitter, crushMix, order, master, output; } ix_;
    Chorus chorus_, chorus2_; Phaser phaser_; Saturator sat_; BitCrusher crush_; std::vector<float> dryL_, dryR_;
};

}  // namespace zdsp
