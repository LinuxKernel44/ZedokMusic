// Loudness and level meter after ITU-R BS.1770-4 / EBU R128 / EBU Tech 3342: momentary (400 ms), short-term (3 s), integrated (two gates)
// and loudness range, K-weighted; true peak (4x oversampled); sample peak; stereo correlation. No allocation, safe for the audio thread.
#pragma once
#include <cmath>
#include <cstring>
#include <algorithm>

namespace zdsp {

class LoudnessMeter {
public:
    void prepare(double sr) { sr_ = sr; sub_ = (int)std::lround(sr * 0.1); designK(); buildTp(); reset(); }
    void reset() {
        for (auto& c : st1_) c = {}; for (auto& c : st2_) c = {};
        subAcc_ = 0.0; subCount_ = 0; ringPos_ = 0; ringFill_ = 0; for (auto& e : ring_) e = 0.0;
        std::memset(hist_, 0, sizeof hist_); std::memset(histE_, 0, sizeof histE_); std::memset(lraHist_, 0, sizeof lraHist_);
        sinceShort_ = 0; momentary_ = shortTerm_ = integrated_ = lra_ = kNone; truePeak_ = samplePeak_ = 0.f;
        for (auto& h : tpHist_) for (auto& x : h) x = 0.f; tpPos_ = 0; corrXY_ = corrXX_ = corrYY_ = 0.0;
    }
    /** Feeds interleaved stereo. */
    void process(const float* x, int frames) {
        for (int i = 0; i < frames; i++) {
            const float l = x[i * 2], r = x[i * 2 + 1];
            samplePeak_ = std::max(samplePeak_, std::max(std::fabs(l), std::fabs(r)));
            truePeak_ = std::max(truePeak_, tpFeed(0, l)); truePeak_ = std::max(truePeak_, tpFeed(1, r)); tpPos_ = (tpPos_ + 1) % kTpTaps;
            corrXY_ = corrXY_ * kCorrDecay + (double)l * r; corrXX_ = corrXX_ * kCorrDecay + (double)l * l; corrYY_ = corrYY_ * kCorrDecay + (double)r * r;
            const double kl = k(0, l), kr = k(1, r);
            subAcc_ += kl * kl + kr * kr;
            if (++subCount_ >= sub_) { endSub(); }
        }
    }
    // results (LUFS / LU / dBTP); kNone while there is not enough audio yet
    static constexpr float kNone = -200.f;
    float momentary() const { return momentary_; }
    float shortTerm() const { return shortTerm_; }
    float integrated() const { return integrated_; }
    float range() const { return lra_; }
    float truePeakDb() const { return truePeak_ > 0.f ? 20.f * std::log10(truePeak_) : kNone; }
    float samplePeakDb() const { return samplePeak_ > 0.f ? 20.f * std::log10(samplePeak_) : kNone; }
    /** Phase correlation -1 (opposite) .. +1 (mono), recent signal. */
    float correlation() const { const double d = std::sqrt(corrXX_ * corrYY_); return d > 1e-12 ? (float)(corrXY_ / d) : 1.f; }
    void resetPeaks() { truePeak_ = samplePeak_ = 0.f; }

private:
    struct Bq { double z1 = 0, z2 = 0; };
    struct Co { double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0; };
    static constexpr int kBins = 760;                 // -70.0 .. +6.0 LUFS in 0.1 steps
    static constexpr int kRing = 30;                  // 100 ms sub-blocks in 3 s
    static constexpr int kTpTaps = 12, kTpPhases = 4;
    static constexpr double kCorrDecay = 0.99985;     // about 150 ms at 48 kHz
    static int bin(double lufs) { return (int)std::floor((lufs + 70.0) * 10.0); }
    static double lufsOfEnergy(double e) { return e > 0.0 ? -0.691 + 10.0 * std::log10(e) : -1000.0; }
    static double energyOfBin(int b) { return std::pow(10.0, ((b / 10.0 - 70.0) + 0.691) / 10.0); }

    void designK() {
        const double pi = 3.14159265358979323846;
        { const double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
          const double K = std::tan(pi * f0 / sr_), Vh = std::pow(10.0, G / 20.0), Vb = std::pow(Vh, 0.4996667741545416), a0 = 1.0 + K / Q + K * K;
          c1_.b0 = (Vh + Vb * K / Q + K * K) / a0; c1_.b1 = 2.0 * (K * K - Vh) / a0; c1_.b2 = (Vh - Vb * K / Q + K * K) / a0; c1_.a1 = 2.0 * (K * K - 1.0) / a0; c1_.a2 = (1.0 - K / Q + K * K) / a0; }
        { const double f0 = 38.13547087602444, Q = 0.5003270373238773;
          const double K = std::tan(pi * f0 / sr_), a0 = 1.0 + K / Q + K * K;
          c2_.b0 = 1.0; c2_.b1 = -2.0; c2_.b2 = 1.0; c2_.a1 = 2.0 * (K * K - 1.0) / a0; c2_.a2 = (1.0 - K / Q + K * K) / a0; }
    }
    double k(int ch, double x) {
        Bq& a = st1_[ch]; double y = c1_.b0 * x + a.z1; a.z1 = c1_.b1 * x - c1_.a1 * y + a.z2; a.z2 = c1_.b2 * x - c1_.a2 * y;
        Bq& b = st2_[ch]; double z = c2_.b0 * y + b.z1; b.z1 = c2_.b1 * y - c2_.a1 * z + b.z2; b.z2 = c2_.b2 * y - c2_.a2 * z;
        return z;
    }
    void endSub() {
        const double e = subAcc_ / (double)sub_;     // sum over the two channels of the mean square of this 100 ms
        subAcc_ = 0.0; subCount_ = 0;
        ring_[ringPos_] = e; ringPos_ = (ringPos_ + 1) % kRing; if (ringFill_ < kRing) ringFill_++;
        auto mean = [&](int n) { double s = 0; for (int i = 0; i < n; i++) s += ring_[((ringPos_ - 1 - i) % kRing + kRing) % kRing]; return s / n; };
        if (ringFill_ >= 4) {
            const double lm = lufsOfEnergy(mean(4)); momentary_ = (float)lm;
            if (lm >= -70.0) { const int b = std::clamp(bin(lm), 0, kBins - 1); hist_[b]++; histE_[b] += mean(4); }
            integrated_ = computeIntegrated();
        }
        if (ringFill_ >= kRing) {
            shortTerm_ = (float)lufsOfEnergy(mean(kRing));
            if (++sinceShort_ >= 10) {   // a loudness-range block every second
                sinceShort_ = 0; const double ls = lufsOfEnergy(mean(kRing));
                if (ls >= -70.0) lraHist_[std::clamp(bin(ls), 0, kBins - 1)]++;
                lra_ = computeLra();
            }
        }
    }
    float computeIntegrated() const {
        double sum = 0; long n = 0;
        for (int b = 0; b < kBins; b++) { sum += histE_[b]; n += hist_[b]; }
        if (n == 0) return kNone;
        double rel = lufsOfEnergy(sum / n) - 10.0; sum = 0; n = 0;
        for (int b = std::max(0, bin(rel) + 1); b < kBins; b++) { sum += histE_[b]; n += hist_[b]; }
        return n ? (float)lufsOfEnergy(sum / n) : kNone;
    }
    float computeLra() const {
        double sum = 0; long n = 0;
        for (int b = 0; b < kBins; b++) { sum += lraHist_[b] * energyOfBin(b); n += lraHist_[b]; }
        if (n == 0) return kNone;
        const double rel = lufsOfEnergy(sum / n) - 20.0; const int from = std::max(0, bin(rel) + 1);
        long m = 0; for (int b = from; b < kBins; b++) m += lraHist_[b];
        if (m < 2) return 0.f;
        auto at = [&](double q) { const long target = (long)std::floor(q * (m - 1)); long c = 0; for (int b = from; b < kBins; b++) { c += lraHist_[b]; if (c > target) return b / 10.0 - 70.0; } return 0.0; };
        return (float)(at(0.95) - at(0.10));
    }
    // true peak: 4x oversampling with a windowed-sinc polyphase filter
    void buildTp() {
        const double pi = 3.14159265358979323846;
        for (int ph = 0; ph < kTpPhases; ph++) {
            double s = 0;
            for (int t = 0; t < kTpTaps; t++) {
                const double x = (t - (kTpTaps / 2 - 1)) - (double)ph / kTpPhases;   // distance from the sample, in input samples
                const double sinc = std::fabs(x) < 1e-9 ? 1.0 : std::sin(pi * x) / (pi * x);
                const double w = 0.5 + 0.5 * std::cos(pi * x / (kTpTaps / 2));
                tp_[ph][t] = sinc * w; s += tp_[ph][t];
            }
            for (int t = 0; t < kTpTaps; t++) tp_[ph][t] /= s;
        }
    }
    float tpFeed(int ch, float x) {
        tpHist_[ch][tpPos_] = x; float peak = std::fabs(x);
        for (int ph = 1; ph < kTpPhases; ph++) {
            double y = 0; for (int t = 0; t < kTpTaps; t++) y += tp_[ph][t] * tpHist_[ch][((tpPos_ - (kTpTaps - 1) + t) % kTpTaps + kTpTaps) % kTpTaps];
            peak = std::max(peak, (float)std::fabs(y));
        }
        return peak;
    }

    double sr_ = 48000; int sub_ = 4800; Co c1_, c2_; Bq st1_[2], st2_[2];
    double subAcc_ = 0; int subCount_ = 0; double ring_[kRing] = {}; int ringPos_ = 0, ringFill_ = 0, sinceShort_ = 0;
    long hist_[kBins] = {}; double histE_[kBins] = {}; long lraHist_[kBins] = {};
    float momentary_ = kNone, shortTerm_ = kNone, integrated_ = kNone, lra_ = kNone, truePeak_ = 0.f, samplePeak_ = 0.f;
    double tp_[kTpPhases][kTpTaps] = {}; float tpHist_[2][kTpTaps] = {}; int tpPos_ = 0;
    double corrXY_ = 0, corrXX_ = 0, corrYY_ = 0;
};

}  // namespace zdsp
