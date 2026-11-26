// Zedok EQ + Comp: a six-band parametric equaliser with a live spectrum, followed (or preceded) by a compressor with a lookahead
// limiter. One plugin for the usual channel-strip / bus job.
#pragma once
#include <atomic>
#include "fx.h"
#include "processor.h"

namespace zdsp {

class EqComp : public Processor {
public:
    static constexpr int kBands = 6;
    EqComp() {
        const std::vector<std::string> types = {"Bell", "Low Shelf", "High Shelf", "Low Cut", "High Cut", "Notch", "Band Pass", "Tilt"};
        const float defF[kBands] = {40.f, 120.f, 450.f, 2200.f, 8000.f, 16000.f};
        const int defT[kBands] = {3, 1, 0, 0, 2, 4};
        const bool defOn[kBands] = {false, true, true, true, true, false};
        for (int b = 0; b < kBands; b++) {
            const std::string n = "B" + std::to_string(b + 1) + " ";
            band_[b].on = add(toggle("EQ", (n + "On").c_str(), defOn[b]));
            band_[b].type = add(choice("EQ", (n + "Type").c_str(), types, defT[b]));
            band_[b].freq = add(logp("EQ", (n + "Freq").c_str(), 20.f, 20000.f, defF[b], "Hz", 0));
            band_[b].gain = add(lin("EQ", (n + "Gain").c_str(), -18.f, 18.f, 0.f, "dB", 1));
            band_[b].q = add(logp("EQ", (n + "Q").c_str(), 0.1f, 12.f, 1.f, "", 2));
            band_[b].slope = add(choice("EQ", (n + "Slope").c_str(), {"12 dB", "24 dB", "36 dB", "48 dB"}, 0));
        }
        ix_.eqMode = add(choice("EQ", "EQ Mode", {"Stereo", "Mid/Side", "Left", "Right"}, 0));
        ix_.eqOn = add(toggle("EQ", "EQ On", true));
        ix_.compOn = add(toggle("Comp", "Comp On", false));
        ix_.thresh = add(lin("Comp", "Threshold", -60.f, 0.f, -18.f, "dB", 1));
        ix_.ratio = add(pw("Comp", "Ratio", 1.f, 20.f, 3.f, 2.f, ":1", 1));
        ix_.knee = add(lin("Comp", "Knee", 0.f, 24.f, 6.f, "dB", 1));
        ix_.attack = add(pw("Comp", "Attack", 0.05f, 200.f, 10.f, 3.f, "ms", 1));
        ix_.release = add(pw("Comp", "Release", 5.f, 2000.f, 120.f, 3.f, "ms", 0));
        ix_.autoRel = add(toggle("Comp", "Auto Release", false));
        ix_.detect = add(choice("Comp", "Detector", {"Peak", "RMS", "Mixed"}, 0));
        ix_.look = add(lin("Comp", "Lookahead", 0.f, 5.f, 0.f, "ms", 1));
        ix_.makeup = add(lin("Comp", "Makeup", -12.f, 24.f, 0.f, "dB", 1));
        ix_.autoMk = add(toggle("Comp", "Auto Makeup", false));
        ix_.scHp = add(logp("Comp", "Sidechain HP", 20.f, 600.f, 20.f, "Hz", 0));
        ix_.link = add(lin("Comp", "Stereo Link", 0.f, 1.f, 1.f));
        ix_.compMix = add(lin("Comp", "Comp Mix", 0.f, 1.f, 1.f));
        ix_.limOn = add(toggle("Limiter", "Limiter On", false));
        ix_.ceiling = add(lin("Limiter", "Ceiling", -12.f, 0.f, -0.3f, "dB", 1));
        ix_.limRel = add(pw("Limiter", "Limiter Release", 5.f, 500.f, 60.f, 2.f, "ms", 0));
        ix_.order = add(choice("Main", "Order", {"EQ > Comp", "Comp > EQ"}, 0));
        ix_.input = add(lin("Main", "Input Gain", -24.f, 24.f, 0.f, "dB", 1));
        ix_.output = add(lin("Main", "Output Gain", -24.f, 24.f, 0.f, "dB", 1));
    }
    const char* name() const override { return "Zedok EQ + Comp"; }
    bool isInstrument() const override { return false; }
    void prepare(double sr, int maxFrames) override { sr_ = sr; (void)maxFrames; eq_.prepare(sr); comp_.prepare(sr); reset(); for (int i = 0; i < kRing; i++) ring_[i] = 0.f; }
    void reset() override { eq_.reset(); comp_.reset(); lastSig_ = {}; haveSig_ = false; inPk_ = outPk_ = 0.f; gr_ = 0.f; }
    int latency() const override { return latency_; }

    void process(const ProcessCtx& c, const float* v) override {
        const int n = c.frames;
        // ---- read parameters
        const bool eqOn = real(v, ix_.eqOn) > 0.5f, compOn = real(v, ix_.compOn) > 0.5f, limOn = real(v, ix_.limOn) > 0.5f;
        const int order = (int)(real(v, ix_.order) + 0.5f), mode = (int)(real(v, ix_.eqMode) + 0.5f);
        // EQ bands: re-design only when something changed
        Sig sig; int k = 0;
        for (int b = 0; b < kBands; b++) { sig.v[k++] = v[band_[b].on]; sig.v[k++] = v[band_[b].type]; sig.v[k++] = v[band_[b].freq]; sig.v[k++] = v[band_[b].gain]; sig.v[k++] = v[band_[b].q]; sig.v[k++] = v[band_[b].slope]; }
        if (!haveSig_ || std::memcmp(&sig, &lastSig_, sizeof sig) != 0) {
            for (int b = 0; b < kBands; b++) {
                EqBand e; e.on = real(v, band_[b].on) > 0.5f; e.type = (EqBand::Type)(int)(real(v, band_[b].type) + 0.5f); e.freq = real(v, band_[b].freq);
                e.gainDb = real(v, band_[b].gain); e.q = real(v, band_[b].q); e.slope = 12 * ((int)(real(v, band_[b].slope) + 0.5f) + 1);
                eq_.setBand(b, e);
            }
            lastSig_ = sig; haveSig_ = true;
        }
        Compressor::Params p;
        p.thresh = real(v, ix_.thresh); p.ratio = real(v, ix_.ratio) >= 19.9f ? 100.f : real(v, ix_.ratio); p.knee = real(v, ix_.knee); p.attack = real(v, ix_.attack); p.release = real(v, ix_.release);
        p.autoRelease = real(v, ix_.autoRel) > 0.5f; const int det = (int)(real(v, ix_.detect) + 0.5f); p.rms = det == 0 ? 0.f : det == 1 ? 1.f : 0.5f;
        p.lookahead = real(v, ix_.look); p.makeup = real(v, ix_.makeup); p.autoMakeup = real(v, ix_.autoMk) > 0.5f; p.scHp = real(v, ix_.scHp); p.link = real(v, ix_.link); p.mix = real(v, ix_.compMix);
        p.limiter = limOn; p.ceiling = real(v, ix_.ceiling); p.limRelease = real(v, ix_.limRel);
        if (!compOn) { p.ratio = 1.f; p.makeup = 0.f; p.autoMakeup = false; p.lookahead = 0.f; p.mix = 1.f; }   // only the limiter is wanted
        comp_.set(p);
        latency_ = (compOn || limOn) ? comp_.latency() : 0;
        // ---- process in place on the output buffers (the host copied the input there)
        float* L = c.outL; float* R = c.outR;
        if (c.inL != L) { std::memcpy(L, c.inL, sizeof(float) * n); std::memcpy(R, c.inR, sizeof(float) * n); }
        const float ing = dbToLin(real(v, ix_.input)), outg = dbToLin(real(v, ix_.output));
        float ip = 0.f;
        for (int i = 0; i < n; i++) { L[i] *= ing; R[i] *= ing; ip = std::max(ip, std::max(std::fabs(L[i]), std::fabs(R[i]))); }
        if (order == 0) { if (eqOn) eq_.process(L, R, n, mode); if (compOn || limOn) comp_.process(L, R, n); }
        else { if (compOn || limOn) comp_.process(L, R, n); if (eqOn) eq_.process(L, R, n, mode); }
        float op = 0.f;
        for (int i = 0; i < n; i++) { L[i] *= outg; R[i] *= outg; op = std::max(op, std::max(std::fabs(L[i]), std::fabs(R[i]))); ring_[(ringW_ + i) & (kRing - 1)] = 0.5f * (L[i] + R[i]); }
        ringW_ = (ringW_ + n) & (kRing - 1);
        inPk_ = std::max(ip, inPk_ * 0.92f); outPk_ = std::max(op, outPk_ * 0.92f);
        gr_ = (compOn || limOn) ? comp_.grDb() : 0.f;
        grPk_ = std::max(gr_, grPk_ * 0.995f);
    }

    // 0: spectrum of the output (128 log-spaced bands, dB)   1: [in peak dB, out peak dB, gain reduction dB, gr peak]   2: EQ response (256 points 20 Hz..20 kHz, dB)
    int telemetry(int what, float* out, int cap) override {
        if (what == 0) {
            const int N = 2048; if (cap < 128) return 0;
            std::vector<float> re(N), im(N, 0.f); const int w0 = ringW_;
            for (int i = 0; i < N; i++) { const float win = 0.5f - 0.5f * std::cos(kTwoPi * i / (N - 1)); re[i] = ring_[(w0 - N + i) & (kRing - 1)] * win; }
            fft(re, im, false);
            for (int b = 0; b < 128; b++) {
                const float f0 = 20.f * std::pow(1000.f, b / 128.f), f1 = 20.f * std::pow(1000.f, (b + 1) / 128.f);
                const int k0 = std::max(1, (int)(f0 / (sr_ / N))), k1 = std::min(N / 2 - 1, std::max(k0, (int)(f1 / (sr_ / N))));
                float m = 0.f; for (int k = k0; k <= k1; k++) m = std::max(m, std::sqrt(re[k] * re[k] + im[k] * im[k]));
                out[b] = linToDb(m * 4.f / N);
            }
            return 128;
        }
        if (what == 1 && cap >= 4) { out[0] = linToDb(inPk_); out[1] = linToDb(outPk_); out[2] = gr_; out[3] = grPk_; grPk_ *= 0.9f; return 4; }
        if (what == 2 && cap >= 256) { for (int i = 0; i < 256; i++) out[i] = eq_.responseDb(20.f * std::pow(1000.f, i / 255.f)); return 256; }
        if (what >= 10 && what < 10 + kBands && cap >= 256) { for (int i = 0; i < 256; i++) out[i] = eq_.bandResponseDb(what - 10, 20.f * std::pow(1000.f, i / 255.f)); return 256; }
        return 0;
    }

private:
    float real(const float* v, int i) const { return specs_[i].toReal(v[i]); }
    struct BandIx { int on, type, freq, gain, q, slope; } band_[kBands];
    struct Ix { int eqMode, eqOn, compOn, thresh, ratio, knee, attack, release, autoRel, detect, look, makeup, autoMk, scHp, link, compMix, limOn, ceiling, limRel, order, input, output; } ix_;
    struct Sig { float v[kBands * 6] = {}; } lastSig_; bool haveSig_ = false;
    ParametricEq eq_; Compressor comp_; int latency_ = 0;
    static constexpr int kRing = 8192; float ring_[kRing] = {}; int ringW_ = 0;
    float inPk_ = 0.f, outPk_ = 0.f, gr_ = 0.f, grPk_ = 0.f;
};

}  // namespace zdsp
