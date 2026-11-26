// Factory wavetables for the Zedok synths, generated at start-up from spectra (nothing is stored in the app).
// Each table = kFrames single-cycle frames; each frame has kMips band-limited versions (harmonics halve per mip) so that an
// oscillator can always read a version that does not alias at the note's frequency.
#pragma once
#include <memory>
#include <string>
#include "zdsp.h"

namespace zdsp {

constexpr int kWtLen = 2048;       // samples per cycle
constexpr int kWtFrames = 32;      // morph positions
constexpr int kWtMips = 10;        // mip m keeps harmonics 1..(1024 >> m)

struct WaveTable {
    std::string name;
    std::vector<float> data;       // [frame][mip][kWtLen + 4]  (4 guard samples for interpolation)
    static constexpr int kStride = kWtLen + 4;
    const float* cycle(int frame, int mip) const { return data.data() + ((size_t)frame * kWtMips + mip) * kStride; }
};

namespace wt {

// A frame spectrum: harmonic amplitudes a[1..1023] and phases p[1..1023] (radians, sine phase).
struct Spec { std::vector<float> a, p; Spec() : a(1024, 0.f), p(1024, 0.f) {} };

inline void addMips(WaveTable& t, int frame, const Spec& s) {
    std::vector<float> re(kWtLen), im(kWtLen);
    // normalise the frame so that its peak is about 0.9 (computed on the full-band version)
    float scale = 1.f;
    for (int pass = 0; pass < 2; pass++) {
        for (int mip = 0; mip < kWtMips; mip++) {
            std::fill(re.begin(), re.end(), 0.f); std::fill(im.begin(), im.end(), 0.f);
            const int hmax = 1024 >> mip;
            for (int h = 1; h <= hmax && h < 1024; h++) {
                // gentle roll-off of the top eighth of the band avoids ringing at the cut
                float g = 1.f; if (h > hmax * 7 / 8) g = 0.5f + 0.5f * std::cos(kPi * (h - hmax * 7 / 8) / (float)(hmax - hmax * 7 / 8 + 1));
                const float amp = s.a[h] * g * scale;
                // spectrum of a*sin(h*x + p) -> bin h: -i*a/2 * e^{ip}
                const float c = std::cos(s.p[h]), sn = std::sin(s.p[h]);
                re[h] += amp * 0.5f * sn;  im[h] += -amp * 0.5f * c;
                re[kWtLen - h] += amp * 0.5f * sn; im[kWtLen - h] += amp * 0.5f * c;
            }
            fft(re, im, true);
            if (pass == 0) {
                float pk = 0.f; for (float v : re) pk = std::max(pk, std::fabs(v)); scale = pk > 1e-6f ? 0.9f / pk : 1.f;
                break;   // first pass only measures the full-band peak
            }
            float* dst = t.data.data() + ((size_t)frame * kWtMips + mip) * WaveTable::kStride;
            for (int i = 0; i < kWtLen; i++) dst[i] = re[i];
            for (int i = 0; i < 4; i++) dst[kWtLen + i] = re[i];
        }
    }
}

inline float sinc(float x) { return std::fabs(x) < 1e-6f ? 1.f : std::sin(x) / x; }

// ---- table recipes: f in [0,1] is the morph position of the frame ----
inline void basicMorph(float f, Spec& s) {   // sine -> triangle -> saw -> square
    const float x = f * 3.f;
    for (int h = 1; h < 1024; h++) {
        const float inv = 1.f / h; const float odd = (h & 1) ? 1.f : 0.f;
        const float sine = h == 1 ? 1.f : 0.f;
        const float tri = odd * inv * inv * ((((h - 1) / 2) & 1) ? -1.f : 1.f) * 8.f / (kPi * kPi);
        const float saw = inv * 2.f / kPi;
        const float sq = odd * inv * 4.f / kPi;
        s.a[h] = x < 1.f ? lerp(sine, tri, x) : x < 2.f ? lerp(tri, saw, x - 1.f) : lerp(saw, sq, x - 2.f);
        s.p[h] = 0.f;
    }
}
inline void sawStack(float f, Spec& s) {     // saw whose upper harmonics are progressively detuned in phase -> thick, supersaw-like
    for (int h = 1; h < 1024; h++) { s.a[h] = 2.f / (kPi * h); s.p[h] = f * 6.f * kPi * std::sin(h * 1.7f) / (1.f + 0.3f * h); }
}
inline void vowels(float f, Spec& s) {       // formant-filtered glottal pulse: a-e-i-o-u morph
    static const float F[5][3] = {{800, 1150, 2900}, {400, 1700, 2600}, {350, 2000, 2800}, {450, 800, 2830}, {325, 700, 2530}};
    const float x = f * 4.f; const int i = std::min(3, (int)x); const float t = x - i;
    float fm[3]; for (int k = 0; k < 3; k++) fm[k] = lerp(F[i][k], F[i + 1][k], t);
    const float f0 = 110.f;                  // formants are defined for a 110 Hz cycle: harmonic h sits at h*f0
    for (int h = 1; h < 1024; h++) {
        const float hz = h * f0; float g = 0.f;
        for (int k = 0; k < 3; k++) { const float bw = 90.f + 60.f * k; const float d = (hz - fm[k]) / bw; g += (k == 0 ? 1.f : 0.6f / k) / (1.f + d * d); }
        s.a[h] = g * (1.f / h) * 0.9f * (h < 60 ? 1.f : 60.f / h); s.p[h] = 0.f;
    }
}
inline void glassBells(float f, Spec& s) {   // sparse, inharmonic-sounding partial structure that evolves with position
    for (int h = 1; h < 1024; h++) {
        const float spread = 0.5f + 0.5f * std::sin(h * (0.35f + f * 2.3f));
        s.a[h] = (h < 48 ? std::pow(spread, 3.f) : 0.f) / std::pow((float)h, 0.8f) * (0.4f + f); s.p[h] = h * f * 3.f;
    }
}
inline void pluckSweep(float f, Spec& s) {   // saw with a moving spectral rolloff: dark to bright
    const float cut = 1.5f + f * f * 90.f;
    for (int h = 1; h < 1024; h++) { s.a[h] = 2.f / (kPi * h) * (1.f / std::sqrt(1.f + std::pow(h / cut, 4.f))); s.p[h] = 0.f; }
}
inline void pwmSweep(float f, Spec& s) {     // pulse width from 50% to 4%
    const float w = 0.5f - 0.46f * f;
    for (int h = 1; h < 1024; h++) { s.a[h] = 2.f / (kPi * h) * std::fabs(std::sin(kPi * h * w)); s.p[h] = -kPi * h * w + kPi * 0.5f; }
}
inline void drawbars(float f, Spec& s) {     // additive organ: drawbar registrations morph from flute to full to bright
    static const int hs[9] = {1, 2, 3, 4, 6, 8, 10, 12, 16};
    static const float A[4][9] = {{8, 0, 0, 0, 0, 0, 0, 0, 0}, {8, 8, 8, 0, 0, 0, 0, 0, 0}, {8, 8, 6, 8, 4, 8, 0, 0, 4}, {5, 8, 8, 8, 8, 8, 8, 8, 8}};
    const float x = f * 3.f; const int i = std::min(2, (int)x); const float t = x - i;
    for (int k = 0; k < 9; k++) s.a[hs[k]] = lerp(A[i][k], A[i + 1][k], t) / 8.f * 0.3f;
}
inline void resonantSaw(float f, Spec& s) {  // saw through a resonant peak that sweeps up the harmonic series
    const float c = 1.5f + f * 38.f;
    for (int h = 1; h < 1024; h++) { const float d = (h - c) / (0.6f + 0.1f * c); s.a[h] = 2.f / (kPi * h) * (1.f + 5.f / (1.f + d * d)); s.p[h] = 0.f; }
}
inline void foldSweep(float f, Spec& s) {    // a sine wavefolded harder and harder: built in time domain then analysed
    std::vector<float> re(kWtLen), im(kWtLen, 0.f);
    const float k = 0.5f + f * 7.f;
    for (int i = 0; i < kWtLen; i++) { float x = std::sin(kTwoPi * i / kWtLen) * k; x = std::sin(x * 1.5707963f * 1.f); re[i] = x; }
    fft(re, im, false);
    for (int h = 1; h < 1024; h++) { const float a = 2.f * std::sqrt(re[h] * re[h] + im[h] * im[h]) / kWtLen; s.a[h] = a; s.p[h] = std::atan2(re[h], -im[h]); }
}
inline void digitalGrit(float f, Spec& s) {  // seeded random phases/amps that smoothly cross-fade between two spectra
    Rng a(12345), b(67890);
    for (int h = 1; h < 1024; h++) {
        const float a0 = a.uni(), a1 = b.uni(), p0 = a.uni() * kTwoPi, p1 = b.uni() * kTwoPi;
        const float cut = h < 64 ? 1.f : 64.f / h;
        s.a[h] = lerp(a0, a1, f) * cut * 1.4f / std::sqrt((float)h); s.p[h] = lerp(p0, p1, f);
    }
}

using Recipe = void (*)(float, Spec&);

inline std::shared_ptr<const std::vector<WaveTable>> library() {
    static std::shared_ptr<const std::vector<WaveTable>> lib = [] {
        struct R { const char* name; Recipe fn; };
        static const R recipes[] = {
            {"Basic Morph", basicMorph}, {"Super Stack", sawStack}, {"Vowels", vowels}, {"Glass Bells", glassBells},
            {"Pluck Sweep", pluckSweep}, {"PWM Sweep", pwmSweep}, {"Organ Bars", drawbars}, {"Resonant Saw", resonantSaw},
            {"Fold Sweep", foldSweep}, {"Digital Grit", digitalGrit}};
        auto v = std::make_shared<std::vector<WaveTable>>();
        for (const R& r : recipes) {
            WaveTable t; t.name = r.name; t.data.assign((size_t)kWtFrames * kWtMips * WaveTable::kStride, 0.f);
            for (int fr = 0; fr < kWtFrames; fr++) { Spec s; r.fn(fr / (float)(kWtFrames - 1), s); addMips(t, fr, s); }
            v->push_back(std::move(t));
        }
        return std::shared_ptr<const std::vector<WaveTable>>(v);
    }();
    return lib;
}

}  // namespace wt
}  // namespace zdsp
