// Offline renderer for the native plugins: plays notes through a Processor in 128-frame blocks (like the engine), writes a float WAV and prints measurements.
// usage: render <plugin> <out.wav> <seconds> [--notes pitch:start:len[:vel],...] [--in input.wav] [--tempo 120] ["Param Name=value" ...]
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>
#include <algorithm>
#include <memory>
#include "../dsp/processor.h"
using namespace zdsp;
static void writeWav(const char* path, const std::vector<float>& L, const std::vector<float>& R, int sr) {
    FILE* f = fopen(path, "wb"); uint32_t n = (uint32_t)L.size(), data = n * 8, riff = 36 + data, fmtSize = 16; uint16_t fmt = 3, ch = 2, bits = 32, align = 8; uint32_t rate = sr, br = sr * 8;
    fwrite("RIFF", 1, 4, f); fwrite(&riff, 4, 1, f); fwrite("WAVEfmt ", 1, 8, f); fwrite(&fmtSize, 4, 1, f); fwrite(&fmt, 2, 1, f); fwrite(&ch, 2, 1, f); fwrite(&rate, 4, 1, f); fwrite(&br, 4, 1, f); fwrite(&align, 2, 1, f); fwrite(&bits, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&data, 4, 1, f);
    for (uint32_t i = 0; i < n; i++) { fwrite(&L[i], 4, 1, f); fwrite(&R[i], 4, 1, f); }
    fclose(f);
}
static bool readWav(const char* path, std::vector<float>& L, std::vector<float>& R) {
    FILE* f = fopen(path, "rb"); if (!f) return false; std::vector<uint8_t> b; uint8_t t[4096]; size_t k; while ((k = fread(t, 1, sizeof t, f)) > 0) b.insert(b.end(), t, t + k); fclose(f);
    if (b.size() < 44) return false; uint16_t fmt = *(uint16_t*)&b[20], ch = *(uint16_t*)&b[22], bits = *(uint16_t*)&b[34]; size_t pos = 12;
    while (pos + 8 < b.size()) { uint32_t sz = *(uint32_t*)&b[pos + 4]; if (!memcmp(&b[pos], "data", 4)) { pos += 8; size_t n = std::min<size_t>(sz, b.size() - pos) / (bits / 8) / ch;
        for (size_t i = 0; i < n; i++) { float v[2] = {0, 0}; for (int c = 0; c < ch && c < 2; c++) { const uint8_t* p = &b[pos + (i * ch + c) * (bits / 8)]; v[c] = fmt == 3 ? *(float*)p : bits == 16 ? *(int16_t*)p / 32768.f : 0.f; } L.push_back(v[0]); R.push_back(ch > 1 ? v[1] : v[0]); } return true; } pos += 8 + sz + (sz & 1); }
    return false;
}
int main(int argc, char** argv) {
    if (argc < 4) { fprintf(stderr, "usage\n"); return 1; }
    std::unique_ptr<Processor> p(createProcessor(argv[1])); if (!p) { fprintf(stderr, "unknown plugin\n"); return 1; }
    const int sr = 48000; const double secs = atof(argv[3]); double tempo = 120;
    std::vector<float> values; for (auto& s : p->params()) values.push_back(s.defNorm());
    struct Note { int pitch; double start, len; int vel; }; std::vector<Note> notes; std::vector<float> inL, inR;
    for (int i = 4; i < argc; i++) {
        std::string a = argv[i];
        if (a == "--notes" && i + 1 < argc) { std::string l = argv[++i]; size_t pos = 0; while (pos < l.size()) { size_t e = l.find(',', pos); if (e == std::string::npos) e = l.size(); int pi; double st, ln; int vel = 100; sscanf(l.substr(pos, e - pos).c_str(), "%d:%lf:%lf:%d", &pi, &st, &ln, &vel); notes.push_back({pi, st, ln, vel}); pos = e + 1; } }
        else if (a == "--in" && i + 1 < argc) { readWav(argv[++i], inL, inR); }
        else if (a == "--tempo" && i + 1 < argc) tempo = atof(argv[++i]);
        else { size_t eq = a.rfind('='); if (eq == std::string::npos) continue; std::string n = a.substr(0, eq), v = a.substr(eq + 1); int k = p->indexOf(n); if (k < 0) { fprintf(stderr, "unknown param '%s'\n", n.c_str()); return 2; }
            const ParamSpec& s = p->params()[k]; float real; if (s.curve == Curve::Choice) { int idx = -1; for (size_t j = 0; j < s.labels.size(); j++) if (s.labels[j] == v) idx = (int)j; real = idx >= 0 ? (float)idx : (float)atof(v.c_str()); } else real = (float)atof(v.c_str()); values[k] = s.toNorm(real); }
    }
    p->prepare(sr, 128);
    const int total = (int)(secs * sr); std::vector<float> L(total), R(total), zero(128, 0.f);
    std::vector<ZkEvent> ev;
    for (int pos = 0; pos < total; pos += 128) {
        const int n = std::min(128, total - pos); ev.clear();
        for (auto& nt : notes) {
            const int on = (int)(nt.start * sr), off = (int)((nt.start + nt.len) * sr);
            if (on >= pos && on < pos + n) ev.push_back({(uint32_t)(on - pos), ZK_NOTE_ON, 0, (uint8_t)nt.pitch, (uint8_t)nt.vel});
            if (off >= pos && off < pos + n) ev.push_back({(uint32_t)(off - pos), ZK_NOTE_OFF, 0, (uint8_t)nt.pitch, 0});
        }
        std::sort(ev.begin(), ev.end(), [](const ZkEvent& a, const ZkEvent& b) { return a.offset < b.offset; });
        std::vector<float> iL(n, 0.f), iR(n, 0.f); for (int i = 0; i < n; i++) if (pos + i < (int)inL.size()) { iL[i] = inL[pos + i]; iR[i] = inR[pos + i]; }
        ProcessCtx c{ev.data(), (int)ev.size(), iL.data(), iR.data(), L.data() + pos, R.data() + pos, n, tempo, (double)pos / sr * tempo / 60.0, true};
        // effects process in place: copy input to output first
        if (!p->isInstrument()) { memcpy(c.outL, iL.data(), n * 4); memcpy(c.outR, iR.data(), n * 4); }
        p->process(c, values.data());
    }
    double e = 0, dc = 0, pk = 0; int bad = 0; for (int i = 0; i < total; i++) { for (float x : {L[i], R[i]}) { if (!std::isfinite(x)) { bad++; continue; } e += x * x; dc += x; pk = std::max(pk, (double)std::fabs(x)); } }
    printf("peak %.2f dBFS  rms %.2f dBFS  dc %.5f  nonfinite %d\n", 20 * log10(pk + 1e-12), 10 * log10(e / (2.0 * total) + 1e-12), dc / (2.0 * total), bad);
    writeWav(argv[2], L, R, sr);
    return bad ? 3 : 0;
}
