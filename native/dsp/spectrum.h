// Spectrum analyzer: the audio thread pushes the mono mix into a ring; any other thread asks for log-spaced band levels in dB.
#pragma once
#include "zdsp.h"
#include <atomic>
#include <cmath>
#include <vector>

namespace zdsp {

struct SpectrumRing {
    static constexpr int N = 4096;           // analysis window (power of two)
    float buf[N] = {};
    std::atomic<uint32_t> head{0};
    void push(const float* stereo, int frames) {   // audio thread
        uint32_t h = head.load(std::memory_order_relaxed);
        for (int i = 0; i < frames; i++) buf[(h++) & (N - 1)] = 0.5f * (stereo[2 * i] + stereo[2 * i + 1]);
        head.store(h, std::memory_order_release);
    }
    void latest(float* dst) const {                // any thread; a block being overwritten only smears the picture slightly
        const uint32_t h = head.load(std::memory_order_acquire);
        for (int i = 0; i < N; i++) dst[i] = buf[(h + i) & (N - 1)];
    }
};

/** Hann-windowed FFT of the last N samples folded into `bands` log-spaced bands between fLo and fHi; levels in dBFS of a sine's peak (a full-scale sine reads about 0 dB). */
inline void spectrumBands(const float* x, double sr, float* db, int bands, double fLo = 20.0, double fHi = 20000.0) {
    constexpr int N = SpectrumRing::N;
    std::vector<float> re(N), im(N, 0.f);
    double wsum = 0;
    for (int i = 0; i < N; i++) { const double w = 0.5 - 0.5 * std::cos(2 * M_PI * i / N); re[i] = (float)(x[i] * w); wsum += w; }
    fft(re, im, false);
    const double binHz = sr / N; const double hi = std::min(fHi, sr * 0.5);
    for (int b = 0; b < bands; b++) {
        const double a = fLo * std::pow(hi / fLo, (double)b / bands), z = fLo * std::pow(hi / fLo, (double)(b + 1) / bands);
        int k0 = (int)std::floor(a / binHz), k1 = (int)std::ceil(z / binHz);
        if (k1 <= k0) k1 = k0 + 1;
        k0 = std::max(k0, 1); k1 = std::min(std::max(k1, k0 + 1), N / 2);
        double peak = 0;   // strongest bin in the band: keeps a lone tone at its true level whatever the band width
        for (int k = k0; k < k1; k++) peak = std::max(peak, (double)re[k] * re[k] + (double)im[k] * im[k]);
        const double amp = 2.0 * std::sqrt(peak) / wsum;
        db[b] = (float)(20.0 * std::log10(amp + 1e-9));
    }
}

}  // namespace zdsp
