// Zedok Space: a reverb (room, plate, hall, shimmer) and a stereo delay (tempo-synced or free, ping-pong, filtered and saturated feedback, ducking).
#pragma once
#include "fx.h"
#include "processor.h"

namespace zdsp {

class Space : public Processor {
public:
    Space() {
        const std::vector<std::string> divs = {"1/32", "1/16 T", "1/16", "1/16 .", "1/8 T", "1/8", "1/8 .", "1/4 T", "1/4", "1/4 .", "1/2 T", "1/2", "1/2 .", "1 bar"};
        ix_.dlOn = add(toggle("Delay", "Delay On", true));
        ix_.dlSync = add(choice("Delay", "Time Mode", {"Sync", "Free"}, 0));
        ix_.dlL = add(choice("Delay", "Time L", divs, 8));
        ix_.dlR = add(choice("Delay", "Time R", divs, 6));
        ix_.dlFreeL = add(pw("Delay", "Free Time L", 1.f, 2000.f, 300.f, 2.f, "ms", 0));
        ix_.dlFreeR = add(pw("Delay", "Free Time R", 1.f, 2000.f, 375.f, 2.f, "ms", 0));
        ix_.dlFb = add(lin("Delay", "Feedback", 0.f, 1.f, 0.4f));
        ix_.dlPing = add(lin("Delay", "Ping-Pong", 0.f, 1.f, 0.f));
        ix_.dlHp = add(logp("Delay", "Delay Low Cut", 20.f, 2000.f, 100.f, "Hz", 0));
        ix_.dlLp = add(logp("Delay", "Delay High Cut", 500.f, 20000.f, 6500.f, "Hz", 0));
        ix_.dlDrive = add(lin("Delay", "Tape Drive", 0.f, 1.f, 0.f));
        ix_.dlModRate = add(logp("Delay", "Wobble Rate", 0.1f, 8.f, 0.6f, "Hz", 2));
        ix_.dlModDepth = add(pw("Delay", "Wobble Depth", 0.f, 12.f, 0.f, 2.f, "ms", 1));
        ix_.dlDuck = add(lin("Delay", "Duck", 0.f, 1.f, 0.f));
        ix_.dlMix = add(lin("Delay", "Delay Mix", 0.f, 1.f, 0.3f));
        ix_.rvOn = add(toggle("Reverb", "Reverb On", true));
        ix_.rvMode = add(choice("Reverb", "Reverb Mode", {"Room", "Plate", "Hall", "Shimmer"}, 2));
        ix_.rvSize = add(lin("Reverb", "Size", 0.f, 1.f, 0.55f));
        ix_.rvDecay = add(logp("Reverb", "Decay", 0.2f, 20.f, 2.5f, "s", 1));
        ix_.rvPre = add(pw("Reverb", "Pre-Delay", 0.f, 250.f, 12.f, 2.f, "ms", 0));
        ix_.rvDamp = add(lin("Reverb", "Damping", 0.f, 1.f, 0.4f));
        ix_.rvDiff = add(lin("Reverb", "Diffusion", 0.f, 1.f, 0.75f));
        ix_.rvMod = add(lin("Reverb", "Modulation", 0.f, 1.f, 0.35f));
        ix_.rvLow = add(logp("Reverb", "Reverb Low Cut", 20.f, 1000.f, 90.f, "Hz", 0));
        ix_.rvHigh = add(logp("Reverb", "Reverb High Cut", 1000.f, 20000.f, 9500.f, "Hz", 0));
        ix_.rvWidth = add(lin("Reverb", "Width", 0.f, 1.f, 1.f));
        ix_.rvShim = add(lin("Reverb", "Shimmer", 0.f, 1.f, 0.4f));
        ix_.rvMix = add(lin("Reverb", "Reverb Mix", 0.f, 1.f, 0.3f));
        ix_.route = add(choice("Main", "Routing", {"Delay > Reverb", "Reverb > Delay", "Parallel"}, 0));
        ix_.output = add(lin("Main", "Output Gain", -24.f, 12.f, 0.f, "dB", 1));
    }
    const char* name() const override { return "Zedok Space"; }
    bool isInstrument() const override { return false; }
    void prepare(double sr, int maxFrames) override { sr_ = sr; delay_.prepare(sr); reverb_.prepare(sr); bufL_.assign(maxFrames, 0.f); bufR_.assign(maxFrames, 0.f); reset(); }
    void reset() override { delay_.reset(); reverb_.reset(); inPk_ = outPk_ = 0.f; }

    void process(const ProcessCtx& c, const float* v) override {
        const int n = c.frames; if (n > (int)bufL_.size()) return;
        static const float beats[] = {0.125f, 1.f / 6.f, 0.25f, 0.375f, 1.f / 3.f, 0.5f, 0.75f, 2.f / 3.f, 1.f, 1.5f, 4.f / 3.f, 2.f, 3.f, 4.f};
        const double spb = 60000.0 / (c.tempo > 20.0 ? c.tempo : 120.0);
        const bool dOn = real(v, ix_.dlOn) > 0.5f, rOn = real(v, ix_.rvOn) > 0.5f;
        float tl, tr;
        if ((int)(real(v, ix_.dlSync) + 0.5f) == 0) { tl = (float)(spb * beats[(int)(real(v, ix_.dlL) + 0.5f)]); tr = (float)(spb * beats[(int)(real(v, ix_.dlR) + 0.5f)]); }
        else { tl = real(v, ix_.dlFreeL); tr = real(v, ix_.dlFreeR); }
        delay_.set(std::min(tl, 4000.f), std::min(tr, 4000.f), real(v, ix_.dlFb) * 0.97f, real(v, ix_.dlPing), real(v, ix_.dlHp), real(v, ix_.dlLp), real(v, ix_.dlDrive),
                   real(v, ix_.dlModRate), real(v, ix_.dlModDepth), real(v, ix_.dlDuck), real(v, ix_.dlMix));
        reverb_.set((Reverb::Mode)(int)(real(v, ix_.rvMode) + 0.5f), real(v, ix_.rvSize), real(v, ix_.rvDecay), real(v, ix_.rvDamp), real(v, ix_.rvDiff), real(v, ix_.rvMod),
                    real(v, ix_.rvPre), real(v, ix_.rvLow), real(v, ix_.rvHigh), real(v, ix_.rvWidth), real(v, ix_.rvShim), real(v, ix_.rvMix));
        float* L = c.outL; float* R = c.outR;
        if (c.inL != L) { std::memcpy(L, c.inL, sizeof(float) * n); std::memcpy(R, c.inR, sizeof(float) * n); }
        float ip = 0.f; for (int i = 0; i < n; i++) ip = std::max(ip, std::max(std::fabs(L[i]), std::fabs(R[i])));
        const int route = (int)(real(v, ix_.route) + 0.5f);
        if (route == 2) {
            std::memcpy(bufL_.data(), L, sizeof(float) * n); std::memcpy(bufR_.data(), R, sizeof(float) * n);
            if (dOn) delay_.process(L, R, n);
            if (rOn) reverb_.process(bufL_.data(), bufR_.data(), n);
            if (dOn && rOn) for (int i = 0; i < n; i++) { L[i] = L[i] + bufL_[i] - c.inL[i]; R[i] = R[i] + bufR_[i] - c.inR[i]; }
            else if (rOn) { std::memcpy(L, bufL_.data(), sizeof(float) * n); std::memcpy(R, bufR_.data(), sizeof(float) * n); }
        } else if (route == 0) { if (dOn) delay_.process(L, R, n); if (rOn) reverb_.process(L, R, n); }
        else { if (rOn) reverb_.process(L, R, n); if (dOn) delay_.process(L, R, n); }
        const float g = dbToLin(real(v, ix_.output)); float op = 0.f;
        for (int i = 0; i < n; i++) { L[i] *= g; R[i] *= g; op = std::max(op, std::max(std::fabs(L[i]), std::fabs(R[i]))); }
        inPk_ = std::max(ip, inPk_ * 0.92f); outPk_ = std::max(op, outPk_ * 0.92f);
    }
    int telemetry(int what, float* out, int cap) override { if (what == 1 && cap >= 2) { out[0] = linToDb(inPk_); out[1] = linToDb(outPk_); return 2; } return 0; }

private:
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    struct Ix { int dlOn, dlSync, dlL, dlR, dlFreeL, dlFreeR, dlFb, dlPing, dlHp, dlLp, dlDrive, dlModRate, dlModDepth, dlDuck, dlMix, rvOn, rvMode, rvSize, rvDecay, rvPre, rvDamp, rvDiff, rvMod, rvLow, rvHigh, rvWidth, rvShim, rvMix, route, output; } ix_;
    Delay delay_; Reverb reverb_; std::vector<float> bufL_, bufR_; float inPk_ = 0.f, outPk_ = 0.f;
};

}  // namespace zdsp
