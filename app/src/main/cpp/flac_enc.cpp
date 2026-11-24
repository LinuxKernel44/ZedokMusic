#include "flac_enc.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
namespace zedok {
namespace {
constexpr int kBlock = 4096;

struct BitWriter {
    std::vector<uint8_t> b; uint64_t acc = 0; int n = 0;  // acc holds n pending bits (n < 8 after every flush)
    void put(uint64_t v, int bits) {  // bits <= 32
        while (bits > 0) {
            int take = std::min(bits, 8 - n);
            acc = (acc << take) | ((v >> (bits - take)) & ((1u << take) - 1));
            n += take; bits -= take;
            if (n == 8) { b.push_back((uint8_t)acc); acc = 0; n = 0; }
        }
    }
    void putSigned(int64_t v, int bits) { put((uint64_t)v & (bits >= 64 ? ~0ull : ((1ull << bits) - 1)), bits > 32 ? 32 : bits); }
    void unary0(uint32_t q) { while (q >= 32) { put(0, 32); q -= 32; } put(1, (int)q + 1); }  // q zeros then a 1
    void align() { if (n) put(0, 8 - n); }
};

uint8_t crc8(const uint8_t* p, size_t len) { uint8_t c = 0; for (size_t i = 0; i < len; i++) { c ^= p[i]; for (int k = 0; k < 8; k++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1); } return c; }
uint16_t crc16(const uint8_t* p, size_t len) { uint16_t c = 0; for (size_t i = 0; i < len; i++) { c ^= (uint16_t)p[i] << 8; for (int k = 0; k < 8; k++) c = (c & 0x8000) ? (uint16_t)((c << 1) ^ 0x8005) : (uint16_t)(c << 1); } return c; }


// MD5 (RFC 1321) of the raw PCM, stored in STREAMINFO so decoders can verify the file.
struct Md5 {
    uint32_t a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476; uint64_t len = 0; uint8_t buf[64]; size_t fill = 0;
    static uint32_t rol(uint32_t x, int s) { return (x << s) | (x >> (32 - s)); }
    void block(const uint8_t* p) {
        static const uint32_t K[64] = {
            0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
            0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
            0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
            0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391 };
        static const int R[64] = {7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21};
        uint32_t m[16]; for (int i = 0; i < 16; i++) m[i] = p[i*4] | p[i*4+1] << 8 | p[i*4+2] << 16 | (uint32_t)p[i*4+3] << 24;
        uint32_t A = a, B = b, C = c, D = d;
        for (int i = 0; i < 64; i++) {
            uint32_t f; int g;
            if (i < 16) { f = (B & C) | (~B & D); g = i; } else if (i < 32) { f = (D & B) | (~D & C); g = (5 * i + 1) & 15; }
            else if (i < 48) { f = B ^ C ^ D; g = (3 * i + 5) & 15; } else { f = C ^ (B | ~D); g = (7 * i) & 15; }
            uint32_t t = D; D = C; C = B; B = B + rol(A + f + K[i] + m[g], R[i]); A = t;
        }
        a += A; b += B; c += C; d += D;
    }
    void update(const uint8_t* p, size_t n) { len += n; while (n) { size_t t = std::min(n, 64 - fill); memcpy(buf + fill, p, t); fill += t; p += t; n -= t; if (fill == 64) { block(buf); fill = 0; } } }
    void finish(uint8_t out[16]) {
        const uint64_t bits = len * 8; uint8_t pad = 0x80; update(&pad, 1); uint8_t z = 0; while (fill != 56) update(&z, 1);
        uint8_t l[8]; for (int i = 0; i < 8; i++) l[i] = (uint8_t)(bits >> (8 * i)); update(l, 8);
        uint32_t v[4] = {a, b, c, d}; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[i * 4 + j] = (uint8_t)(v[i] >> (8 * j));
    }
};

inline uint32_t zigzag(int64_t r) { return (uint32_t)((r << 1) ^ (r >> 63)); }

// Residual of a fixed predictor of the given order (0..4); the first `order` samples are warm-up.
void fixedResidual(const int64_t* x, int n, int order, std::vector<int64_t>& res) {
    res.resize(n - order);
    for (int i = order; i < n; i++) {
        int64_t p = 0;
        switch (order) {
            case 0: p = 0; break;
            case 1: p = x[i - 1]; break;
            case 2: p = 2 * x[i - 1] - x[i - 2]; break;
            case 3: p = 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]; break;
            default: p = 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]; break;
        }
        res[i - order] = x[i] - p;
    }
}

// Cost (bits) of Rice-coding res[0..n) with parameter k.
uint64_t riceCost(const int64_t* res, int n, int k) { uint64_t bits = 0; for (int i = 0; i < n; i++) bits += (zigzag(res[i]) >> k) + 1 + k; return bits; }

struct Plan { int partOrder = 0; std::vector<int> k; uint64_t bits = 0; };

// Best partitioning of a residual (RICE2 coding: 5-bit parameters).
Plan planResidual(const std::vector<int64_t>& res, int blockSize, int predOrder) {
    Plan best; best.bits = ~0ull;
    const int n = (int)res.size();
    for (int po = 0; po <= 8; po++) {
        const int parts = 1 << po;
        if (blockSize % parts != 0 || blockSize / parts <= predOrder) break;
        Plan p; p.partOrder = po; p.bits = 4 + 4;  // coding method (2) + partition order (4) are counted by the caller; keep a small constant
        int pos = 0;
        for (int i = 0; i < parts; i++) {
            const int len = blockSize / parts - (i == 0 ? predOrder : 0);
            uint64_t sum = 0; for (int j = 0; j < len; j++) sum += zigzag(res[pos + j]);
            int k = 0; if (len > 0) { uint64_t mean = sum / len; while (k < 30 && (1ull << (k + 1)) <= mean + 1) k++; }
            uint64_t bk = riceCost(&res[pos], len, k), bk1 = k < 30 ? riceCost(&res[pos], len, k + 1) : ~0ull, bkm = k > 0 ? riceCost(&res[pos], len, k - 1) : ~0ull;
            int bestK = k; uint64_t bb = bk; if (bk1 < bb) { bb = bk1; bestK = k + 1; } if (bkm < bb) { bb = bkm; bestK = k - 1; }
            p.k.push_back(bestK); p.bits += 5 + bb; pos += len;
        }
        if (p.bits < best.bits) best = p;
    }
    (void)n;
    return best;
}

// Writes one subframe for samples x[0..n) of width `bps` bits (bps may be 17 or 25 for a side channel).
void writeSubframe(BitWriter& w, const int64_t* x, int n, int bps) {
    bool constant = true; for (int i = 1; i < n; i++) if (x[i] != x[0]) { constant = false; break; }
    if (constant) { w.put(0, 1); w.put(0, 6); w.put(0, 1); w.putSigned(x[0], bps); return; }
    int bestOrder = -1; uint64_t bestBits = (uint64_t)n * bps + 8; Plan bestPlan; std::vector<int64_t> bestRes, res;
    for (int order = 0; order <= 4; order++) {
        if (order >= n) break;
        fixedResidual(x, n, order, res);
        Plan p = planResidual(res, n, order);
        const uint64_t bits = 8 + (uint64_t)order * bps + 2 + p.bits;
        if (p.bits != ~0ull && bits < bestBits) { bestBits = bits; bestOrder = order; bestPlan = p; bestRes = res; }
    }
    if (bestOrder < 0) {  // verbatim
        w.put(0, 1); w.put(1, 6); w.put(0, 1);
        for (int i = 0; i < n; i++) w.putSigned(x[i], bps);
        return;
    }
    w.put(0, 1); w.put(8 + bestOrder, 6); w.put(0, 1);  // 001xxx = fixed predictor of order xxx
    for (int i = 0; i < bestOrder; i++) w.putSigned(x[i], bps);  // warm-up
    w.put(1, 2);                                                 // residual coding method: Rice with 5-bit parameters
    w.put(bestPlan.partOrder, 4);
    const int parts = 1 << bestPlan.partOrder; int pos = 0;
    for (int i = 0; i < parts; i++) {
        const int len = n / parts - (i == 0 ? bestOrder : 0); const int k = bestPlan.k[i];
        w.put(k, 5);
        for (int j = 0; j < len; j++) { const uint32_t u = zigzag(bestRes[pos + j]); w.unary0(u >> k); if (k) w.put(u & ((1u << k) - 1), k); }
        pos += len;
    }
}

void putUtf8(std::vector<uint8_t>& o, uint64_t v) {  // frame number
    if (v < 0x80) { o.push_back((uint8_t)v); return; }
    int extra = v < 0x800 ? 1 : v < 0x10000 ? 2 : v < 0x200000 ? 3 : v < 0x4000000 ? 4 : 5;
    o.push_back((uint8_t)((0xFF00 >> (extra + 1)) | (v >> (6 * extra))));
    for (int i = extra - 1; i >= 0; i--) o.push_back((uint8_t)(0x80 | ((v >> (6 * i)) & 0x3F)));
}
}  // namespace

bool encodeFlac(const std::vector<float>& st, int rate, int bps, const std::string& path) {
    if (bps != 16 && bps != 24) return false;
    FILE* f = fopen(path.c_str(), "wb"); if (!f) return false;
    const uint64_t total = st.size() / 2;
    const int64_t full = 1ll << (bps - 1);  // float -1..1 maps onto the signed range [-full, full - 1] (bit-exact for PCM sources)
    uint32_t minFrame = ~0u, maxFrame = 0;
    // Placeholder header: "fLaC" + STREAMINFO block (patched at the end).
    uint8_t head[4 + 4 + 34] = {'f', 'L', 'a', 'C', 0x80, 0, 0, 34};
    fwrite(head, 1, sizeof head, f);
    std::vector<int64_t> L(kBlock), R(kBlock), M(kBlock), S(kBlock);
    uint64_t frameNo = 0; Md5 md5;
    for (uint64_t pos = 0; pos < total; pos += kBlock, frameNo++) {
        const int n = (int)std::min<uint64_t>(kBlock, total - pos);
        for (int i = 0; i < n; i++) {
            L[i] = std::clamp<int64_t>(std::llround((double)st[(pos + i) * 2] * full), -full, full - 1);
            R[i] = std::clamp<int64_t>(std::llround((double)st[(pos + i) * 2 + 1] * full), -full, full - 1);
            S[i] = L[i] - R[i]; M[i] = (L[i] + R[i]) >> 1;
        }
        for (int i = 0; i < n; i++) for (int ch = 0; ch < 2; ch++) { const int64_t v = ch ? R[i] : L[i]; uint8_t b3[3] = {(uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16)}; md5.update(b3, bps / 8); }
        // Pick the channel assignment by trying all four (independent, left/side, right/side, mid/side).
        BitWriter best; int bestMode = -1;
        for (int mode = 0; mode < 4; mode++) {
            BitWriter w; const int64_t* c0 = mode == 0 ? L.data() : mode == 1 ? L.data() : mode == 2 ? S.data() : M.data();
            const int64_t* c1 = mode == 0 ? R.data() : mode == 1 ? S.data() : mode == 2 ? R.data() : S.data();
            const int b0 = (mode == 2) ? bps + 1 : bps, b1 = (mode == 1 || mode == 3) ? bps + 1 : bps;
            writeSubframe(w, c0, n, b0); writeSubframe(w, c1, n, b1);
            if (bestMode < 0 || w.b.size() * 8 + w.n < best.b.size() * 8 + best.n) { best = w; bestMode = mode; }
        }
        // Frame header
        std::vector<uint8_t> fr; fr.push_back(0xFF); fr.push_back(0xF8);  // sync + fixed block size
        const bool full = n == kBlock;
        const int bsCode = full ? 12 : (n - 1 < 256 ? 6 : 7);
        const int bpsCode = bps == 16 ? 4 : 6;
        fr.push_back((uint8_t)((bsCode << 4) | 0));  // sample rate from STREAMINFO
        const int chCode = bestMode == 0 ? 1 : bestMode == 1 ? 8 : bestMode == 2 ? 9 : 10;
        fr.push_back((uint8_t)((chCode << 4) | (bpsCode << 1)));
        putUtf8(fr, frameNo);
        if (!full) { if (n - 1 < 256) fr.push_back((uint8_t)(n - 1)); else { fr.push_back((uint8_t)((n - 1) >> 8)); fr.push_back((uint8_t)(n - 1)); } }
        fr.push_back(crc8(fr.data(), fr.size()));
        best.align();
        fr.insert(fr.end(), best.b.begin(), best.b.end());
        const uint16_t c = crc16(fr.data(), fr.size());
        fr.push_back((uint8_t)(c >> 8)); fr.push_back((uint8_t)c);
        minFrame = std::min<uint32_t>(minFrame, (uint32_t)fr.size()); maxFrame = std::max<uint32_t>(maxFrame, (uint32_t)fr.size());
        if (fwrite(fr.data(), 1, fr.size(), f) != fr.size()) { fclose(f); return false; }
    }
    // STREAMINFO
    uint8_t si[34] = {0};
    si[0] = kBlock >> 8; si[1] = kBlock & 255; si[2] = kBlock >> 8; si[3] = kBlock & 255;
    if (minFrame == ~0u) minFrame = 0;
    si[4] = minFrame >> 16; si[5] = minFrame >> 8; si[6] = minFrame; si[7] = maxFrame >> 16; si[8] = maxFrame >> 8; si[9] = maxFrame;
    const uint64_t packed = ((uint64_t)rate << 44) | ((uint64_t)(2 - 1) << 41) | ((uint64_t)(bps - 1) << 36) | (total & 0xFFFFFFFFFull);
    for (int i = 0; i < 8; i++) si[10 + i] = (uint8_t)(packed >> (56 - 8 * i));
    md5.finish(si + 18);
    fseek(f, 8, SEEK_SET); fwrite(si, 1, 34, f); fclose(f);
    return true;
}
}  // namespace zedok
