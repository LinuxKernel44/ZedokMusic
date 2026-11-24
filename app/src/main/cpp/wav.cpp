#include "wav.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <algorithm>
namespace zedok {
namespace {
struct File { FILE* f; explicit File(const char* p, const char* m) : f(fopen(p, m)) {} ~File() { if (f) fclose(f); } };
uint32_t rd32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
void wr32(FILE* f, uint32_t v) { fwrite(&v, 4, 1, f); }
void wr16(FILE* f, uint16_t v) { fwrite(&v, 2, 1, f); }
}

bool readWav(const std::string& path, std::vector<float>& out, int& rate) {
    File fp(path.c_str(), "rb"); if (!fp.f) return false;
    fseek(fp.f, 0, SEEK_END); long size = ftell(fp.f); fseek(fp.f, 0, SEEK_SET);
    if (size < 44) return false;
    std::vector<uint8_t> b(size); if (fread(b.data(), 1, size, fp.f) != (size_t)size) return false;
    if (memcmp(b.data(), "RIFF", 4) || memcmp(b.data() + 8, "WAVE", 4)) return false;
    int fmtTag = 0, ch = 0, bits = 0; const uint8_t* data = nullptr; uint32_t dlen = 0;
    for (size_t pos = 12; pos + 8 <= (size_t)size;) {
        uint32_t len = rd32(&b[pos + 4]); const uint8_t* body = &b[pos + 8];
        if (!memcmp(&b[pos], "fmt ", 4) && pos + 8 + 16 <= (size_t)size) {
            fmtTag = rd16(body); ch = rd16(body + 2); rate = (int)rd32(body + 4); bits = rd16(body + 14);
            if (fmtTag == 0xFFFE && len >= 26) fmtTag = rd16(body + 24);  // WAVE_FORMAT_EXTENSIBLE
        } else if (!memcmp(&b[pos], "data", 4)) {
            data = body; dlen = std::min<uint32_t>(len, (uint32_t)(size - (pos + 8))); break;
        }
        pos += 8 + len + (len & 1);
    }
    if (!data || (ch != 1 && ch != 2)) return false;
    int bps = bits / 8; if (bps < 1) return false;
    size_t frames = dlen / (bps * ch); out.assign(frames * 2, 0.f);
    for (size_t i = 0; i < frames; i++) for (int c = 0; c < ch; c++) {
        const uint8_t* s = data + (i * ch + c) * bps; float v;
        if (fmtTag == 3 && bits == 32) { float x; memcpy(&x, s, 4); v = x; }
        else if (fmtTag == 1 && bits == 16) v = (int16_t)rd16(s) / 32768.f;
        else if (fmtTag == 1 && bits == 24) { int32_t x = (s[0] << 8 | s[1] << 16 | (int32_t)s[2] << 24) >> 8; v = x / 8388608.f; }
        else if (fmtTag == 1 && bits == 32) v = (int32_t)rd32(s) / 2147483648.f;
        else if (fmtTag == 1 && bits == 8) v = (s[0] - 128) / 128.f;
        else return false;
        out[i * 2 + c] = v;
    }
    if (ch == 1) for (size_t i = 0; i < frames; i++) out[i * 2 + 1] = out[i * 2];
    return true;
}

bool writeWav(const std::string& path, const std::vector<float>& st, int rate, WavFormat fmt) {
    File fp(path.c_str(), "wb"); if (!fp.f) return false;
    int bits = fmt == WavFormat::Pcm16 ? 16 : fmt == WavFormat::Pcm24 ? 24 : 32;
    uint32_t bytes = (uint32_t)(st.size() * (bits / 8));
    fwrite("RIFF", 1, 4, fp.f); wr32(fp.f, 36 + bytes); fwrite("WAVEfmt ", 1, 8, fp.f);
    wr32(fp.f, 16); wr16(fp.f, fmt == WavFormat::Float32 ? 3 : 1); wr16(fp.f, 2); wr32(fp.f, rate);
    wr32(fp.f, rate * 2 * bits / 8); wr16(fp.f, 2 * bits / 8); wr16(fp.f, (uint16_t)bits);
    fwrite("data", 1, 4, fp.f); wr32(fp.f, bytes);
    std::vector<uint8_t> buf; buf.reserve(st.size() * 4);
    for (float x : st) {
        x = std::clamp(x, -1.f, 1.f);
        if (fmt == WavFormat::Float32) { uint8_t t[4]; memcpy(t, &x, 4); buf.insert(buf.end(), t, t + 4); }
        else if (fmt == WavFormat::Pcm16) { int16_t v = (int16_t)lrintf(x * 32767.f); buf.push_back(v & 255); buf.push_back((v >> 8) & 255); }
        else { int32_t v = (int32_t)lrintf(x * 8388607.f); buf.push_back(v & 255); buf.push_back((v >> 8) & 255); buf.push_back((v >> 16) & 255); }
    }
    return fwrite(buf.data(), 1, buf.size(), fp.f) == buf.size();
}

std::vector<float> resampleStereo(const std::vector<float>& in, int from, int to) {
    if (from == to || in.empty()) return in;
    size_t inF = in.size() / 2, outF = (size_t)((double)inF * to / from); std::vector<float> out(outF * 2);
    double step = (double)from / to;
    for (size_t i = 0; i < outF; i++) {
        double p = i * step; size_t i0 = (size_t)p, i1 = std::min(i0 + 1, inF - 1); float t = (float)(p - i0);
        for (int c = 0; c < 2; c++) out[i * 2 + c] = in[i0 * 2 + c] * (1 - t) + in[i1 * 2 + c] * t;
    }
    return out;
}
}
