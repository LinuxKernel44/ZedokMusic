// ZedokMusic DSP toolbox: small, allocation-free building blocks shared by the native plugins (header only, C++17).
// Everything here is real-time safe once constructed: no allocation, locks or exceptions inside process paths.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace zdsp {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;

inline float clampf(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float dbToLin(float db) { return std::exp2(db * 0.16609640474f); }          // 10^(db/20)
inline float linToDb(float x) { return 6.02059991328f * std::log2(std::max(x, 1e-9f)); }
inline float semiToRatio(float semis) { return std::exp2(semis * (1.f / 12.f)); }
inline float midiToHz(float note) { return 440.f * std::exp2((note - 69.f) * (1.f / 12.f)); }
// Flush denormals produced by recursive filters.
inline float denorm(float x) { return std::fabs(x) < 1e-20f ? 0.f : x; }
inline double denorm(double x) { return std::fabs(x) < 1e-30 ? 0. : x; }

// Rational tanh approximation (max error ~2e-4 in [-3,3]); saturates cleanly outside.
inline float fastTanh(float x) {
    if (x > 4.97f) return 1.f;
    if (x < -4.97f) return -1.f;
    const float x2 = x * x;
    const float a = x * (135135.f + x2 * (17325.f + x2 * (378.f + x2)));
    const float b = 135135.f + x2 * (62370.f + x2 * (3150.f + x2 * 28.f));
    return a / b;
}
// Smooth soft clip, odd, unity slope at 0.
inline float softClip(float x) { return fastTanh(x); }

// ---------------------------------------------------------------- random

struct Rng {
    uint32_t s = 0x9E3779B9u;
    explicit Rng(uint32_t seed = 0x9E3779B9u) : s(seed ? seed : 1u) {}
    inline uint32_t next() { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s; }
    inline float uni() { return (next() >> 8) * (1.f / 16777216.f); }        // [0,1)
    inline float bi() { return uni() * 2.f - 1.f; }                          // [-1,1)
};

// ---------------------------------------------------------------- smoothing

// One-pole parameter smoother (time constant in ms), for zipper-free control changes.
struct Smooth {
    float v = 0.f, t = 0.f, a = 0.f;
    void setup(double sr, float ms) { a = 1.f - std::exp(-1.f / (std::max(ms, 0.01f) * 0.001f * (float)sr)); }
    void snap(float x) { v = t = x; }
    inline void set(float x) { t = x; }
    inline float next() { v += (t - v) * a; return v; }
    inline float current() const { return v; }
};

// ---------------------------------------------------------------- filters

enum class BQ { LowPass, HighPass, BandPass, Notch, Peak, LowShelf, HighShelf, AllPass };

struct BiquadCoef { double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0; };

// RBJ cookbook. q is Q for LP/HP/BP/Notch/Peak/AllPass; for shelves it is the shelf slope S (0.707 ~ Butterworth-like).
inline BiquadCoef biquadDesign(BQ type, double sr, double f0, double q, double gainDb = 0.0) {
    f0 = std::min(std::max(f0, 1.0), sr * 0.49);
    q = std::max(q, 0.05);
    const double w0 = 2.0 * 3.14159265358979323846 * f0 / sr, cw = std::cos(w0), sw = std::sin(w0);
    const double A = std::pow(10.0, gainDb / 40.0);
    double b0, b1, b2, a0, a1, a2;
    const double alpha = sw / (2.0 * q);
    switch (type) {
    case BQ::LowPass:  b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case BQ::HighPass: b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case BQ::BandPass: b0 = alpha; b1 = 0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case BQ::Notch:    b0 = 1; b1 = -2 * cw; b2 = 1; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case BQ::AllPass:  b0 = 1 - alpha; b1 = -2 * cw; b2 = 1 + alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case BQ::Peak:     b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A; a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A; break;
    case BQ::LowShelf: case BQ::HighShelf: {
        const double S = q;  // slope
        const double al = sw / 2.0 * std::sqrt(std::max(1e-4, (A + 1 / A) * (1 / S - 1) + 2));   // S above ~1 would make this negative (overshooting shelf): keep it real
        const double tsa = 2 * std::sqrt(A) * al;
        if (type == BQ::LowShelf) {
            b0 = A * ((A + 1) - (A - 1) * cw + tsa); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - tsa);
            a0 = (A + 1) + (A - 1) * cw + tsa; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - tsa;
        } else {
            b0 = A * ((A + 1) + (A - 1) * cw + tsa); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - tsa);
            a0 = (A + 1) - (A - 1) * cw + tsa; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - tsa;
        }
        break;
    }
    default: b0 = 1; b1 = b2 = 0; a0 = 1; a1 = a2 = 0;
    }
    BiquadCoef c; c.b0 = b0 / a0; c.b1 = b1 / a0; c.b2 = b2 / a0; c.a1 = a1 / a0; c.a2 = a2 / a0; return c;
}
// Magnitude response (linear) of a biquad at frequency f (for drawing curves).
inline double biquadMag(const BiquadCoef& c, double sr, double f) {
    const double w = 2.0 * 3.14159265358979323846 * f / sr;
    const double cw = std::cos(w), sw = std::sin(w), c2 = std::cos(2 * w), s2 = std::sin(2 * w);
    const double nr = c.b0 + c.b1 * cw + c.b2 * c2, ni = -(c.b1 * sw + c.b2 * s2);
    const double dr = 1 + c.a1 * cw + c.a2 * c2, di = -(c.a1 * sw + c.a2 * s2);
    return std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di + 1e-30));
}

// Transposed direct form II, double precision state (stable under fast modulation).
struct Biquad {
    BiquadCoef c; double z1 = 0, z2 = 0;
    void set(const BiquadCoef& k) { c = k; }
    void reset() { z1 = z2 = 0; }
    inline float process(float x) {
        const double in = x, out = c.b0 * in + z1;
        z1 = c.b1 * in - c.a1 * out + z2;
        z2 = c.b2 * in - c.a2 * out;
        return (float)out;
    }
};

// Zero-delay-feedback state variable filter (Cytomic / Zavalishin TPT). All responses from one structure.
struct SVF {
    float g = 0.1f, k = 1.f, a1 = 0, a2 = 0, a3 = 0, ic1 = 0, ic2 = 0;
    // cutoff Hz, resonance 0..1 (0 = Butterworth-ish, 1 = near self oscillation)
    void set(double sr, float cutoff, float res) {
        cutoff = clampf(cutoff, 5.f, (float)sr * 0.45f);
        g = std::tan(kPi * cutoff / (float)sr);
        k = 2.f - 1.98f * clampf(res, 0.f, 1.f);
        a1 = 1.f / (1.f + g * (g + k)); a2 = g * a1; a3 = g * a2;
    }
    void reset() { ic1 = ic2 = 0; }
    struct Out { float lp, bp, hp; };
    inline Out tick(float v0) {
        const float v3 = v0 - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2;
        return {v2, v1, v0 - k * v1 - v2};
    }
};

// 4-pole transistor-ladder low-pass (TPT with a linearised feedback solve and tanh stage saturation).
struct Ladder {
    float G = 0.1f, k = 0.f, s[4] = {0, 0, 0, 0}, drive = 1.f, G4 = 0;
    void set(double sr, float cutoff, float res, float driveAmt) {
        cutoff = clampf(cutoff, 5.f, (float)sr * 0.45f);
        const float g = std::tan(kPi * cutoff / (float)sr);
        G = g / (1.f + g); G4 = G * G * G * G;
        k = clampf(res, 0.f, 1.f) * 4.05f;
        drive = 1.f + clampf(driveAmt, 0.f, 1.f) * 5.f;
    }
    void reset() { s[0] = s[1] = s[2] = s[3] = 0; }
    inline float tick(float x) {
        const float f = 1.f - G;
        const float S = G * G * G * f * s[0] + G * G * f * s[1] + G * f * s[2] + f * s[3];
        float u = (x * drive - k * S) / (1.f + k * G4);
        u = fastTanh(u);
        float y = u;
        for (int i = 0; i < 4; i++) {
            const float v = G * (y - s[i]);
            y = v + s[i]; s[i] = y + v;
            s[i] = denorm(s[i]);
            if (i < 3) y = fastTanh(y * 0.9f) * 1.1f;  // mild inter-stage saturation
        }
        return y / drive * (1.f + 0.5f * (drive - 1.f) * 0.1f);
    }
};

// ---------------------------------------------------------------- delay line

// Power-of-two circular buffer with cubic (Hermite) interpolated reads.
struct DelayLine {
    std::vector<float> buf; uint32_t mask = 0, w = 0;
    void init(int maxSamples) { int n = 4; while (n < maxSamples + 8) n <<= 1; buf.assign(n, 0.f); mask = n - 1; w = 0; }
    void clear() { std::fill(buf.begin(), buf.end(), 0.f); }
    inline void push(float x) { buf[w & mask] = x; w++; }
    inline float tap(int d) const { return buf[(w - 1 - d) & mask]; }  // d = 0: most recent sample
    inline float read(float d) const {                                    // fractional delay (>= 1 sample)
        const int i = (int)d; const float f = d - i;
        const float ym1 = buf[(w - i) & mask], y0 = buf[(w - 1 - i) & mask], y1 = buf[(w - 2 - i) & mask], y2 = buf[(w - 3 - i) & mask];
        const float c0 = y0, c1 = 0.5f * (y1 - ym1), c2 = ym1 - 2.5f * y0 + 2.f * y1 - 0.5f * y2, c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
        return ((c3 * f + c2) * f + c1) * f + c0;
    }
};

// ---------------------------------------------------------------- envelope follower / ADSR

struct Adsr {
    enum Stage { Idle, Attack, Decay, Sustain, Release } st = Idle;
    float v = 0.f, sus = 0.7f, aRate = 0.01f, dCoef = 0.001f, rCoef = 0.001f;
    float curve = 0.f;  // unused hook
    // times in seconds
    void set(double sr, float a, float d, float s, float r) {
        sus = clampf(s, 0.f, 1.f);
        aRate = 1.f / std::max(a * (float)sr, 1.f);
        // exponential approach with a target overshoot so that the stage ends in finite time
        dCoef = 1.f - std::exp(-4.6f / std::max(d * (float)sr, 1.f));
        rCoef = 1.f - std::exp(-4.6f / std::max(r * (float)sr, 1.f));
    }
    void gate(bool on, bool retrigger = true) {
        if (on) { st = Attack; if (retrigger && v < 0.f) v = 0.f; }
        else if (st != Idle) st = Release;
    }
    void kill() { st = Idle; v = 0.f; }
    bool active() const { return st != Idle; }
    inline float tick() {
        switch (st) {
        case Idle: return 0.f;
        case Attack: v += aRate; if (v >= 1.f) { v = 1.f; st = Decay; } break;
        case Decay: v += (sus - v) * dCoef * 1.0f; if (v - sus < 1e-4f) { v = sus; st = Sustain; } break;
        case Sustain: v = sus; break;
        case Release: v -= v * rCoef; if (v < 1e-5f) { v = 0.f; st = Idle; } break;
        }
        return v;
    }
};

// ---------------------------------------------------------------- oscillator helpers

inline float polyBlep(float t, float dt) {
    if (t < dt) { t /= dt; return t + t - t * t - 1.f; }
    if (t > 1.f - dt) { t = (t - 1.f) / dt; return t * t + t + t + 1.f; }
    return 0.f;
}
inline float polyBlamp(float t, float dt) {  // integrated blep, for corners (triangle)
    if (t < dt) { t = t / dt - 1.f; return -(1.f / 3.f) * t * t * t; }
    if (t > 1.f - dt) { t = (t - 1.f) / dt + 1.f; return (1.f / 3.f) * t * t * t; }
    return 0.f;
}

// ---------------------------------------------------------------- FFT (radix-2, for wavetable synthesis and analysis)

inline void fft(std::vector<float>& re, std::vector<float>& im, bool inverse) {
    const int n = (int)re.size();
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1; for (; j & bit; bit >>= 1) j ^= bit; j ^= bit;
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = 2 * 3.14159265358979323846 / len * (inverse ? 1 : -1);
        const double wr = std::cos(ang), wi = std::sin(ang);
        for (int i = 0; i < n; i += len) {
            double cr = 1, ci = 0;
            for (int j = 0; j < len / 2; j++) {
                const int a = i + j, b = i + j + len / 2;
                const double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                re[b] = (float)(re[a] - xr); im[b] = (float)(im[a] - xi);
                re[a] = (float)(re[a] + xr); im[a] = (float)(im[a] + xi);
                const double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
            }
        }
    }
    if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
}

// ---------------------------------------------------------------- 2x oversampler (half-band FIR, linear phase)

// Up/down by 2 with a 31-tap Kaiser-windowed half-band filter. latency() reports the added delay in base-rate samples (~7.5).
struct HalfBand {
    static constexpr int N = 31, H = (N - 1) / 2;
    float h[N]; float z[N] = {};
    HalfBand() {
        double sum = 0;
        for (int i = 0; i < N; i++) {
            const int m = i - H; double x = (m == 0) ? 0.5 : std::sin(3.14159265358979323846 * m / 2.0) / (3.14159265358979323846 * m);
            const double r = (double)m / (H + 1); const double w = 0.42 + 0.5 * std::cos(3.14159265358979323846 * r) + 0.08 * std::cos(2 * 3.14159265358979323846 * r);  // Blackman
            h[i] = (float)(x * w); sum += h[i];
        }
        for (int i = 0; i < N; i++) h[i] = (float)(h[i] / sum);  // unity DC gain
    }
    void reset() { std::memset(z, 0, sizeof z); }
    inline float push(float x) {  // feed one sample at the high rate, returns filtered
        for (int i = N - 1; i > 0; i--) z[i] = z[i - 1];
        z[0] = x;
        float y = 0.f; for (int i = 0; i < N; i++) y += h[i] * z[i];
        return y;
    }
    static constexpr float latency() { return H * 0.5f; }
};

struct Oversampler2 {
    HalfBand up, down;
    void reset() { up.reset(); down.reset(); }
    // Runs f(x) at 2x the rate on one input sample; returns one output sample.
    template <class F> inline float process(float x, F&& f) {
        const float a = up.push(2.f * x), b = up.push(0.f);       // zero-stuff + interpolate (gain 2 compensates the zeros)
        const float ya = f(a), yb = f(b);
        down.push(ya); return down.push(yb);                      // decimate: keep every second
    }
};

// ---------------------------------------------------------------- peak/RMS meters, DC blocker

struct DcBlock { float x1 = 0, y1 = 0, r = 0.995f; inline float tick(float x) { const float y = x - x1 + r * y1; x1 = x; y1 = denorm(y); return y; } void reset() { x1 = y1 = 0; } };

// Text helpers for parameter display (no allocation).
inline void fmtFloat(char* out, int n, float v, int decimals) {
    int d = decimals < 0 ? 0 : (decimals > 4 ? 4 : decimals);
    const char* f[] = {"%.0f", "%.1f", "%.2f", "%.3f", "%.4f"};
    std::snprintf(out, n, f[d], v);
}

}  // namespace zdsp
