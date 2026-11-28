// Offline checks of zdsp::LoudnessMeter against reference signals (EBU Tech 3341 style).
#include "../dsp/loudness.h"
#include <cstdio>
#include <vector>
using zdsp::LoudnessMeter;
static int fails = 0;
static void check(const char* what, bool ok, double v) { printf("loudness: %-62s %s (%.3f)\n", what, ok ? "ok" : "FAILED", v); if (!ok) fails++; }
static std::vector<float> sine(double hz, double dbPeak, double secs, double sr, double lGain = 1.0, double rGain = 1.0, double rPhase = 0.0) {
    const int n = (int)(secs * sr); std::vector<float> v(n * 2); const double a = std::pow(10.0, dbPeak / 20.0);
    for (int i = 0; i < n; i++) { const double ph = 2 * M_PI * hz * i / sr; v[2 * i] = (float)(a * lGain * std::sin(ph)); v[2 * i + 1] = (float)(a * rGain * std::sin(ph + rPhase)); }
    return v;
}
static void feed(LoudnessMeter& m, const std::vector<float>& v) { for (size_t i = 0; i < v.size() / 2; i += 480) m.process(&v[i * 2], (int)std::min<size_t>(480, v.size() / 2 - i)); }
int main() {
    for (double sr : {48000.0, 44100.0}) {
        char t[96];
        LoudnessMeter m; m.prepare(sr);
        feed(m, sine(1000, -23.0, 20.0, sr));
        snprintf(t, sizeof t, "%.0f Hz: 1 kHz stereo sine, peak -23 dBFS: integrated -23.0 LUFS", sr); check(t, std::fabs(m.integrated() + 23.0f) < 0.1f, m.integrated());
        snprintf(t, sizeof t, "%.0f Hz: ... momentary and short-term agree", sr); check(t, std::fabs(m.momentary() + 23.0f) < 0.1f && std::fabs(m.shortTerm() + 23.0f) < 0.1f, m.shortTerm());
        snprintf(t, sizeof t, "%.0f Hz: ... loudness range of a steady tone is 0", sr); check(t, m.range() >= 0.f && m.range() < 0.2f, m.range());
        snprintf(t, sizeof t, "%.0f Hz: ... sample peak -23 dBFS", sr); check(t, std::fabs(m.samplePeakDb() + 23.0f) < 0.05f, m.samplePeakDb());
        snprintf(t, sizeof t, "%.0f Hz: ... true peak not below the sample peak, within 0.5 dB", sr); check(t, m.truePeakDb() >= m.samplePeakDb() - 0.01f && m.truePeakDb() < m.samplePeakDb() + 0.5f, m.truePeakDb());
        snprintf(t, sizeof t, "%.0f Hz: ... in phase: correlation +1", sr); check(t, m.correlation() > 0.99f, m.correlation());
    }
    { LoudnessMeter m; m.prepare(48000); feed(m, sine(1000, -20.0, 10.0, 48000, 1.0, 0.0));   // left only
      check("one channel at -20 dBFS reads like both channels at -23 dBFS", std::fabs(m.integrated() + 23.0f) < 0.1f, m.integrated()); }
    { LoudnessMeter m; m.prepare(48000); feed(m, sine(1000, -23.0, 10.0, 48000, 1.0, 1.0, M_PI));
      check("opposite phase: correlation -1, loudness unchanged", m.correlation() < -0.99f && std::fabs(m.integrated() + 23.0f) < 0.1f, m.correlation()); }
    { LoudnessMeter m; m.prepare(48000);   // gating: 10 s at -23 then 10 s of silence-ish (-90): the quiet part is gated out
      feed(m, sine(1000, -23.0, 10.0, 48000)); feed(m, sine(1000, -90.0, 10.0, 48000));
      check("quiet passages are gated out of the integrated value", std::fabs(m.integrated() + 23.0f) < 0.1f, m.integrated()); }
    { LoudnessMeter m; m.prepare(48000);   // relative gate: loud 10 s then 10 s 15 LU lower (above the -70 abs gate, below -10 LU relative): ignored
      feed(m, sine(1000, -20.0, 10.0, 48000)); feed(m, sine(1000, -35.0, 10.0, 48000));
      check("passages 15 LU below are gated out (relative gate)", std::fabs(m.integrated() + 20.0f) < 0.15f, m.integrated()); }
    { LoudnessMeter m; m.prepare(48000);   // loudness range: alternate 10 s at -20 and -30: about 10 LU
      for (int i = 0; i < 4; i++) { feed(m, sine(1000, -20.0, 10.0, 48000)); feed(m, sine(1000, -30.0, 10.0, 48000)); }
      check("loudness range of 10 s at -20 then 10 s at -30 is about 10 LU", m.range() > 9.0f && m.range() < 11.0f, m.range()); }
    { LoudnessMeter m; m.prepare(48000);   // K-weighting: 100 Hz is attenuated a little, 10 kHz boosted (about +4 dB shelf)
      feed(m, sine(10000, -23.0, 5.0, 48000)); const float hi = m.integrated();
      check("K-weighting boosts 10 kHz by about 4 dB", hi > -20.0f && hi < -18.5f, hi); }
    { LoudnessMeter m; m.prepare(48000); std::vector<float> v(48000 * 2, 0.f);   // inter-sample peak: a 11 kHz sine at fs/4-ish phase has true peak above sample peak
      for (int i = 0; i < 48000; i++) { const double ph = 2 * M_PI * 12000.0 * i / 48000.0 + 0.785398; v[2*i] = v[2*i+1] = (float)(0.9 * std::sin(ph)); }
      feed(m, v); check("true peak sees what lies between samples (12 kHz at 45 deg)", m.truePeakDb() > m.samplePeakDb() + 0.5f && m.truePeakDb() < -0.7f, m.truePeakDb() - m.samplePeakDb()); }
    printf(fails ? "FAILED (%d)\n" : "ok\n", fails);
    return fails ? 1 : 0;
}
