// Offline checks of zdsp::spectrumBands.
#include "../dsp/spectrum.h"
#include <cstdio>
static int fails = 0;
static void check(const char* what, bool ok, double v) { printf("spectrum: %-60s %s (%.3f)\n", what, ok ? "ok" : "FAILED", v); if (!ok) fails++; }
int main() {
    const double sr = 48000; const int B = 64;
    zdsp::SpectrumRing r; std::vector<float> st(2 * 4096);
    auto tone = [&](double hz, double amp) { for (int i = 0; i < 4096; i++) st[2 * i] = st[2 * i + 1] = (float)(amp * std::sin(2 * M_PI * hz * i / sr)); r.push(st.data(), 4096); };
    float x[4096], db[B];
    tone(1000, 1.0); r.latest(x); zdsp::spectrumBands(x, sr, db, B);
    int arg = 0; for (int b = 1; b < B; b++) if (db[b] > db[arg]) arg = b;
    const double fc = 20.0 * std::pow(1000.0, (arg + 0.5) / B);
    check("1 kHz sine peaks in the band containing 1 kHz", std::fabs(fc - 1000.0) < 1000.0 * 0.08, fc);
    check("full-scale sine reads about 0 dB (within 1.5 dB, Hann scalloping)", std::fabs(db[arg]) < 1.5, db[arg]);
    tone(1000, 0.1); r.latest(x); zdsp::spectrumBands(x, sr, db, B);
    check("-20 dB sine reads about -20 dB", std::fabs(db[arg] + 20.0) < 1.5, db[arg]);
    check("far bands are at least 60 dB lower", db[2] < db[arg] - 60 && db[B - 3] < db[arg] - 60, db[2]);
    for (int i = 0; i < 4096; i++) st[2 * i] = st[2 * i + 1] = 0; r.push(st.data(), 4096); r.latest(x); zdsp::spectrumBands(x, sr, db, B);
    check("silence reads below -150 dB, no NaN", db[10] < -150 && std::isfinite(db[10]), db[10]);
    return fails ? 1 : 0;
}
