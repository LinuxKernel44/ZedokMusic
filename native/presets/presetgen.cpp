// usage: presetgen <plugin id> <out.json> [--report report.txt]
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <unistd.h>
#include <string>
#include <algorithm>
#include "presetgen.h"
#include "../dsp/processor.h"
using namespace zdsp;

static std::string esc(const std::string& s) { std::string o; for (char c : s) { if (c == '"' || c == '\\') o += '\\'; o += c; } return o; }

struct Measure { double peak = 0, rms = 0; int bad = 0; };
// Renders a short phrase and measures it. `chord` selects what is played (instruments only).
static Measure render(Processor& p, const std::vector<float>& vals, const std::vector<int>& notes, double secs, double hold) {
    const int sr = 48000, bs = 128, total = (int)(secs * sr);
    p.prepare(sr, bs); p.reset();
    std::vector<float> L(bs), R(bs), zero(bs, 0.f); Measure m; double e = 0; long cnt = 0;
    for (int pos = 0; pos < total; pos += bs) {
        std::vector<ZkEvent> ev;
        if (pos == 0) for (int n : notes) ev.push_back({0, ZK_NOTE_ON, 0, (uint8_t)n, 100});
        const int off = (int)(hold * sr); if (off >= pos && off < pos + bs) for (int n : notes) ev.push_back({(uint32_t)(off - pos), ZK_NOTE_OFF, 0, (uint8_t)n, 0});
        ProcessCtx c{ev.data(), (int)ev.size(), zero.data(), zero.data(), L.data(), R.data(), bs, 120.0, (double)pos / sr * 2.0, true};
        p.process(c, vals.data());
        for (int i = 0; i < bs; i++) for (float x : {L[i], R[i]}) { if (!std::isfinite(x)) { m.bad++; continue; } m.peak = std::max(m.peak, (double)std::fabs(x)); if (pos / (double)sr < hold) { e += x * x; cnt++; } }
    }
    m.rms = cnt ? std::sqrt(e / cnt) : 0; return m;
}

// Pink-ish noise with a slow amplitude pulse: a signal that exercises equalisers, compressors and distortions. Returns peak/bad; rms of input and output via the pointers.
static Measure renderFx(Processor& p, const std::vector<float>& vals, double secs, double* inRms, double* outRms) {
    const int sr = 48000, bs = 128, total = (int)(secs * sr); p.prepare(sr, bs); p.reset();
    std::vector<float> iL(bs), iR(bs), oL(bs), oR(bs); Measure m; double ei = 0, eo = 0; long cnt = 0; uint32_t s = 12345; float b0 = 0, b1 = 0, b2 = 0;
    const int skip = (int)(0.5 * sr);
    for (int pos = 0; pos < total; pos += bs) {
        for (int i = 0; i < bs; i++) {
            s ^= s << 13; s ^= s >> 17; s ^= s << 5; const float w = ((s >> 8) * (1.f / 16777216.f)) * 2.f - 1.f;
            b0 = 0.99765f * b0 + w * 0.0990460f; b1 = 0.96300f * b1 + w * 0.2965164f; b2 = 0.57000f * b2 + w * 1.0526913f;
            const double t = (double)(pos + i) / sr; const float env = 0.55f + 0.45f * (float)std::sin(6.2831853 * 1.7 * t);
            const float x = (b0 + b1 + b2 + w * 0.1848f) * 0.07f * env; iL[i] = x; iR[i] = x * 0.9f + 0.02f * w;
        }
        std::vector<float> L = iL, R = iR; ProcessCtx c{nullptr, 0, iL.data(), iR.data(), L.data(), R.data(), bs, 120.0, (double)pos / sr * 2.0, true};
        p.process(c, vals.data());
        for (int i = 0; i < bs; i++) for (float x : {L[i], R[i]}) { if (!std::isfinite(x)) { m.bad++; continue; } m.peak = std::max(m.peak, (double)std::fabs(x)); }
        if (pos >= skip) for (int i = 0; i < bs; i++) { ei += (double)iL[i] * iL[i] + (double)iR[i] * iR[i]; eo += (double)L[i] * L[i] + (double)R[i] * R[i]; cnt += 2; }
    }
    if (inRms) *inRms = cnt ? std::sqrt(ei / cnt) : 0; if (outRms) *outRms = cnt ? std::sqrt(eo / cnt) : 0; return m;
}

int main(int argc, char** argv) {
    if (argc < 3) return 1;
    const std::string id = argv[1]; const char* out = argv[2]; const char* rep = nullptr;
    for (int i = 3; i + 1 < argc; i++) if (!strcmp(argv[i], "--report")) rep = argv[i + 1];
    PresetList list;
    if (id == "hybrid") list = hybridPresets();
    else if (id == "eqcomp") list = eqcompPresets();
    else if (id == "space") list = spacePresets();
    else if (id == "color") list = colorPresets();
    else if (id == "drums") list = drumsPresets();
    else if (id == "grain") list = grainPresets();
    else { fprintf(stderr, "no presets for %s\n", id.c_str()); return 1; }
    std::unique_ptr<Processor> p(createProcessor(id)); if (!p) return 1;
    const auto& specs = p->params();
    p->prepare(48000, 128); if (id == "grain") usleep(900000);   // the grain textures are built on a background thread
    FILE* f = fopen(out, "w"); FILE* r = rep ? fopen(rep, "w") : nullptr;
    fprintf(f, "{\"plugin\":\"%s\",\"version\":1,\"presets\":[\n", id.c_str());
    int idx = 0, problems = 0;
    for (auto& pd : list) {
        std::vector<float> vals; for (auto& s : specs) vals.push_back(s.defNorm());
        for (auto& v : pd.values) {
            int k = p->indexOf(v.k); if (k < 0) { fprintf(stderr, "[%s] unknown parameter '%s'\n", pd.name.c_str(), v.k.c_str()); problems++; continue; }
            const ParamSpec& s = specs[k]; float real = (float)v.d;
            if (v.isStr) { int found = -1; for (size_t j = 0; j < s.labels.size(); j++) if (s.labels[j] == v.s) found = (int)j; if (found < 0) { fprintf(stderr, "[%s] '%s' has no choice '%s'\n", pd.name.c_str(), v.k.c_str(), v.s.c_str()); problems++; continue; } real = (float)found; }
            else if (s.curve == Curve::Linear && (real < s.lo - 1e-4f || real > s.hi + 1e-4f)) { fprintf(stderr, "[%s] '%s'=%g outside %g..%g\n", pd.name.c_str(), v.k.c_str(), v.d, s.lo, s.hi); problems++; }
            vals[k] = s.toNorm(real);
        }
        // level matching (instruments): play a test chord and bring the sustained RMS to the target by adjusting "Volume"
        double peak = 0, rms = 0; int bad = 0; const int vol = p->indexOf("Volume");
        if (id == "drums") {   // a loud pattern: kick + snare + closed hat + clap at once must peak at about -4 dBFS
            for (int pass = 0; pass < 3; pass++) {
                Measure m = render(*p, vals, {36, 39, 42, 40}, 1.5, 1.0); peak = m.peak; rms = m.rms; bad = m.bad; if (peak < 1e-5) break;
                const double db = 20 * std::log10(0.63 / peak); if (std::fabs(db) < 0.3) break;
                const float cur = specs[vol].toReal(vals[vol]); vals[vol] = specs[vol].toNorm(std::min(specs[vol].hi, std::max(specs[vol].lo, cur + (float)db)));
            }
        } else if (id == "eqcomp" || id == "color") {   // loudness-match the effect to its input on a noise signal
            const int og = p->indexOf("Output Gain");
            for (int pass = 0; pass < 3 && og >= 0; pass++) {
                double inR = 0, outR = 0; Measure m = renderFx(*p, vals, 3.0, &inR, &outR); peak = m.peak; bad = m.bad; rms = outR;
                if (outR < 1e-6) break; const double db = 20 * std::log10(inR / outR); if (std::fabs(db) < 0.25) break;
                const float cur = specs[og].toReal(vals[og]); vals[og] = specs[og].toNorm(std::min(specs[og].hi, std::max(specs[og].lo, cur + (float)db)));
            }
        } else if (id == "space") { double inR = 0, outR = 0; Measure m = renderFx(*p, vals, 3.0, &inR, &outR); peak = m.peak; bad = m.bad; rms = outR; }
        else if (p->isInstrument() && vol >= 0) {
            const bool low = pd.category == "Bass";
            const std::vector<int> notes = low ? std::vector<int>{36} : std::vector<int>{57, 64, 69};
            const float volNormBase = vals[vol];
            for (int pass = 0; pass < 3; pass++) {
                Measure m = render(*p, vals, notes, 2.5, 1.5); peak = m.peak; rms = m.rms; bad = m.bad;
                if (rms < 1e-5) break;
                const double target = low ? 0.095 : 0.085;                // ~ -20.5 / -21.4 dBFS RMS while the notes are held
                double db = 20 * std::log10(target / rms);
                if (std::fabs(db) < 0.4) break;
                const float cur = specs[vol].toReal(vals[vol]); float want = std::min(specs[vol].hi, std::max(specs[vol].lo, cur + (float)db)); vals[vol] = specs[vol].toNorm(want);
            }
            // never let a transient exceed about -4.4 dBFS: quiet sustained sounds can still have loud attacks
            if (peak > 0.6) { const float cur = specs[vol].toReal(vals[vol]); vals[vol] = specs[vol].toNorm(std::max(specs[vol].lo, cur + (float)(20 * std::log10(0.6 / peak)))); Measure m = render(*p, vals, notes, 2.5, 1.5); peak = m.peak; rms = m.rms; bad = m.bad; }
            (void)volNormBase;
        } else { Measure m = render(*p, vals, {60}, 1.0, 0.5); peak = m.peak; rms = m.rms; bad = m.bad; }
        if (bad || (peak < 1e-4 && pd.category != "Sample")) { fprintf(stderr, "[%s] PROBLEM: nonfinite=%d peak=%g\n", pd.name.c_str(), bad, peak); problems++; }
        if (peak > 0.98) fprintf(stderr, "[%s] warning: peak %.2f\n", pd.name.c_str(), peak);
        if (r) fprintf(r, "%-28s %-10s peak %6.2f dB  rms %6.2f dB\n", pd.name.c_str(), pd.category.c_str(), 20 * std::log10(peak + 1e-9), 20 * std::log10(rms + 1e-9));
        // write only values that differ from the default
        fprintf(f, "%s{\"name\":\"%s\",\"category\":\"%s\",\"tags\":[", idx++ ? ",\n" : "", esc(pd.name).c_str(), esc(pd.category).c_str());
        for (size_t t = 0; t < pd.tags.size(); t++) fprintf(f, "%s\"%s\"", t ? "," : "", esc(pd.tags[t]).c_str());
        fprintf(f, "],\"values\":{"); int n = 0;
        for (size_t k = 0; k < specs.size(); k++) if (std::fabs(vals[k] - specs[k].defNorm()) > 1e-5f) fprintf(f, "%s\"%s\":%.5f", n++ ? "," : "", esc(specs[k].name).c_str(), vals[k]);
        fprintf(f, "}");
        if (!pd.state.empty()) { fprintf(f, ",\"state\":{"); int s = 0; for (auto& kv : pd.state) fprintf(f, "%s\"%s\":\"%s\"", s++ ? "," : "", esc(kv.first).c_str(), esc(kv.second).c_str()); fprintf(f, "}"); }
        fprintf(f, "}");
    }
    fprintf(f, "\n]}\n"); fclose(f); if (r) fclose(r);
    fprintf(stderr, "%d presets, %d problems\n", (int)list.size(), problems);
    return problems ? 2 : 0;
}
