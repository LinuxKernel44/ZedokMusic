#include "extract.h"
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <vector>
#include "third_party/zstd/zstd.h"

namespace zedok {
namespace {
std::string join(const std::string& a, const std::string& b) { return a + "/" + b; }

bool safeRel(const std::string& p) {  // reject absolute paths and ".." escapes
    if (p.empty() || p[0] == '/') return false;
    size_t i = 0;
    while (i <= p.size()) {
        size_t j = p.find('/', i); if (j == std::string::npos) j = p.size();
        if (p.compare(i, j - i, "..") == 0) return false;
        i = j + 1;
    }
    return true;
}
void mkdirs(const std::string& path, mode_t mode) {
    for (size_t i = 1; i <= path.size(); i++)
        if (i == path.size() || path[i] == '/') { std::string d = path.substr(0, i); mkdir(d.c_str(), mode); }
}
std::string parent(const std::string& p) { size_t i = p.rfind('/'); return i == std::string::npos ? "" : p.substr(0, i); }
uint64_t octal(const char* s, size_t n) {
    if ((unsigned char)s[0] & 0x80) { uint64_t v = (unsigned char)s[0] & 0x7f; for (size_t i = 1; i < n; i++) v = (v << 8) | (unsigned char)s[i]; return v; }  // base-256
    uint64_t v = 0; for (size_t i = 0; i < n && s[i] >= '0' && s[i] <= '7'; i++) v = v * 8 + (s[i] - '0'); return v;
}

// Tar state machine fed with decompressed bytes.
struct Tar {
    std::string dest, err;
    char hdr[512]; size_t hdrFill = 0;
    uint64_t remaining = 0, pad = 0;   // payload bytes left in the current entry and its 512-padding
    enum Kind { None, File, Meta } kind = None;
    int fd = -1; char type = 0;
    std::string metaBuf, pendingName, pendingLink, curPath;
    bool zeroBlocks = false;

    void finishMeta() {
        if (type == 'L') pendingName = metaBuf.substr(0, metaBuf.find('\0'));
        else if (type == 'K') pendingLink = metaBuf.substr(0, metaBuf.find('\0'));
        else if (type == 'x' || type == 'g') {  // pax: "len key=value\n" records
            size_t i = 0;
            while (i < metaBuf.size()) {
                size_t sp = metaBuf.find(' ', i); if (sp == std::string::npos) break;
                size_t len = strtoul(metaBuf.c_str() + i, nullptr, 10); if (len == 0 || i + len > metaBuf.size()) break;
                std::string rec = metaBuf.substr(sp + 1, i + len - sp - 2);
                size_t eq = rec.find('=');
                if (eq != std::string::npos && type == 'x') {
                    std::string k = rec.substr(0, eq), v = rec.substr(eq + 1);
                    if (k == "path") pendingName = v; else if (k == "linkpath") pendingLink = v;
                }
                i += len;
            }
        }
        metaBuf.clear();
    }
    void closeFile() { if (fd >= 0) { close(fd); fd = -1; } }
    bool startEntry() {
        bool allZero = true; for (char c : hdr) if (c) { allZero = false; break; }
        if (allZero) { zeroBlocks = true; return true; }
        type = hdr[156] ? hdr[156] : '0';
        uint64_t size = octal(hdr + 124, 12); mode_t mode = (mode_t)octal(hdr + 100, 8) & 07777;
        std::string name(hdr, strnlen(hdr, 100));
        if (!memcmp(hdr + 257, "ustar", 5) && hdr[345]) name = std::string(hdr + 345, strnlen(hdr + 345, 155)) + "/" + name;
        if (!pendingName.empty() && type != 'L' && type != 'K' && type != 'x' && type != 'g') { name = pendingName; }
        std::string link(hdr + 157, strnlen(hdr + 157, 100));
        if (!pendingLink.empty() && type != 'L' && type != 'K' && type != 'x' && type != 'g') link = pendingLink;
        remaining = size; pad = (512 - size % 512) % 512; kind = None;
        if (type == 'L' || type == 'K' || type == 'x' || type == 'g') { kind = Meta; metaBuf.clear(); return true; }
        pendingName.clear(); pendingLink.clear();
        while (name.size() > 1 && name.back() == '/') name.pop_back();
        if (name.rfind("./", 0) == 0) name = name.substr(2);
        if (name == "." || name.empty()) { remaining = 0; pad = pad; return true; }
        if (!safeRel(name)) { err = "unsafe path in archive: " + name; return false; }
        std::string full = join(dest, name);
        if (type == '5') { mkdirs(full, 0755); chmod(full.c_str(), (mode | 0700)); remaining = 0; return true; }
        mkdirs(parent(full), 0755);
        if (type == '2') { unlink(full.c_str()); if (symlink(link.c_str(), full.c_str()) != 0) { err = "symlink failed: " + full; return false; } remaining = 0; return true; }
        if (type == '1') {
            if (!safeRel(link)) { err = "unsafe hardlink: " + link; return false; }
            unlink(full.c_str()); if (::link(join(dest, link).c_str(), full.c_str()) != 0) { err = "hardlink failed: " + full; return false; }
            remaining = 0; return true;
        }
        if (type == '0' || type == '7') {
            unlink(full.c_str());
            fd = open(full.c_str(), O_WRONLY | O_CREAT | O_TRUNC, (mode | 0600) & 07777);
            if (fd < 0) { err = std::string("open failed: ") + full + ": " + strerror(errno); return false; }
            fchmod(fd, (mode | 0600) & 07777);  // honour umask-free exec bits
            kind = File; return true;
        }
        remaining = 0; return true;  // devices, fifos: ignore
    }
    bool feed(const uint8_t* p, size_t n) {
        while (n && err.empty()) {
            if (kind != None && remaining > 0) {
                size_t k = (size_t)std::min<uint64_t>(remaining, n);
                if (kind == File) { if (write(fd, p, k) != (ssize_t)k) { err = "write failed"; return false; } }
                else metaBuf.append((const char*)p, k);
                remaining -= k; p += k; n -= k;
                if (remaining > 0) continue;
            }
            if (kind != None && remaining == 0 && pad == 0) {}
            if (kind != None && remaining == 0) {  // payload done: consume padding then close
                size_t k = (size_t)std::min<uint64_t>(pad, n); p += k; n -= k; pad -= k;
                if (pad > 0) continue;
                if (kind == File) closeFile(); else finishMeta();
                kind = None; continue;
            }
            if (kind == None && pad > 0 && remaining == 0 && hdrFill == 0) {  // padding after a header-only entry
                size_t k = (size_t)std::min<uint64_t>(pad, n); p += k; n -= k; pad -= k; if (pad > 0) continue;
            }
            size_t need = 512 - hdrFill, k = std::min(need, n);
            memcpy(hdr + hdrFill, p, k); hdrFill += k; p += k; n -= k;
            if (hdrFill == 512) { hdrFill = 0; if (!startEntry()) return false; if (kind == None) pad = (type == '5' || type == '2' || type == '1') ? 0 : pad; }
        }
        return err.empty();
    }
};
}  // namespace

std::string extractTarZst(int fd, int64_t offset, int64_t length, const std::string& destDir, std::atomic<int64_t>* progress) {
    mkdirs(destDir, 0755);
    ZSTD_DCtx* dctx = ZSTD_createDCtx(); if (!dctx) return "zstd init failed";
    Tar tar; tar.dest = destDir;
    std::vector<uint8_t> in(1 << 20), out(1 << 20);
    int64_t done = 0; std::string result;
    while (done < length && result.empty()) {
        ssize_t r = pread(fd, in.data(), (size_t)std::min<int64_t>((int64_t)in.size(), length - done), offset + done);
        if (r <= 0) { result = "read error"; break; }
        done += r; if (progress) progress->store(done);
        ZSTD_inBuffer ib{in.data(), (size_t)r, 0};
        while (ib.pos < ib.size) {
            ZSTD_outBuffer ob{out.data(), out.size(), 0};
            size_t rc = ZSTD_decompressStream(dctx, &ob, &ib);
            if (ZSTD_isError(rc)) { result = std::string("zstd: ") + ZSTD_getErrorName(rc); break; }
            if (!tar.feed(out.data(), ob.pos)) { result = tar.err; break; }
        }
    }
    tar.closeFile(); ZSTD_freeDCtx(dctx);
    return result;
}
}  // namespace zedok
