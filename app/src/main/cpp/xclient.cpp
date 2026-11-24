// Display side of plugin GUIs. Wine draws the plugin editor on an Xvfb screen whose framebuffer is a memory-mappable XWD file.
//  * xfbOpen/xfbBlit: map that file and copy a rectangle into an Android Bitmap (RGBA_8888).
//  * xInput*: a tiny X11 protocol client (no Xlib) that injects pointer events with the XTEST extension.
#include <android/bitmap.h>
#include <jni.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
struct Fb { uint8_t* map = nullptr; size_t len = 0; int w = 0, h = 0; size_t bpl = 0, off = 0; } fb;
std::mutex fbMutex;

uint32_t be32(const uint8_t* p) { return (uint32_t)p[0] << 24 | p[1] << 16 | p[2] << 8 | p[3]; }

struct XConn {
    int fd = -1; uint32_t root = 0; uint8_t xtestOpcode = 0; uint16_t seq = 0;
    int minKey = 8, maxKey = 255, perKey = 0; std::vector<uint32_t> keysyms;  // keyboard mapping: keysyms[(code - minKey) * perKey + column]
    std::mutex m;
    bool readFull(void* buf, size_t n) {
        auto* p = static_cast<uint8_t*>(buf);
        while (n) { ssize_t r = recv(fd, p, n, 0); if (r <= 0) return false; p += r; n -= r; }
        return true;
    }
    bool writeFull(const void* buf, size_t n) {
        auto* p = static_cast<const uint8_t*>(buf);
        while (n) { ssize_t r = send(fd, p, n, MSG_NOSIGNAL); if (r <= 0) return false; p += r; n -= r; }
        return true;
    }
    void drain() { uint8_t tmp[512]; while (recv(fd, tmp, sizeof tmp, MSG_DONTWAIT) > 0) {} }
    void close_() { if (fd >= 0) ::close(fd); fd = -1; root = 0; xtestOpcode = 0; }
    bool connectTo(const std::string& path) {
        close_();
        fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return false;
        sockaddr_un a{}; a.sun_family = AF_UNIX; strncpy(a.sun_path, path.c_str(), sizeof a.sun_path - 1);
        if (connect(fd, (sockaddr*)&a, sizeof a) != 0) { close_(); return false; }
        uint8_t setup[12] = {'l', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0};  // little endian, protocol 11.0, no authorization
        if (!writeFull(setup, sizeof setup)) { close_(); return false; }
        uint8_t head[8]; if (!readFull(head, 8) || head[0] != 1) { close_(); return false; }
        const size_t extra = (size_t)(head[6] | head[7] << 8) * 4;
        std::vector<uint8_t> d(extra); if (!readFull(d.data(), extra)) { close_(); return false; }
        const size_t vendorLen = d[16] | d[17] << 8, nFormats = d[21];
        const size_t screens = 32 + ((vendorLen + 3) & ~3u) + nFormats * 8;
        if (screens + 4 > d.size()) { close_(); return false; }
        root = d[screens] | d[screens + 1] << 8 | d[screens + 2] << 16 | (uint32_t)d[screens + 3] << 24;
        // QueryExtension "XTEST"
        const char* name = "XTEST"; const size_t nl = 5;
        uint8_t q[16] = {98, 0, 4, 0, (uint8_t)nl, 0, 0, 0}; memcpy(q + 8, name, nl);
        if (!writeFull(q, 16)) { close_(); return false; }
        uint8_t rep[32]; if (!readFull(rep, 32) || rep[0] != 1 || !rep[8]) { close_(); return false; }
        xtestOpcode = rep[9];
        minKey = d[26]; maxKey = d[27];
        loadKeymap();
        return true;
    }
    void loadKeymap() {  // GetKeyboardMapping: lets us turn characters into key presses
        keysyms.clear(); perKey = 0;
        const int count = maxKey - minKey + 1;
        uint8_t q[8] = {101, 0, 2, 0, (uint8_t)minKey, (uint8_t)count, 0, 0};
        if (!writeFull(q, 8)) return;
        uint8_t h[32]; if (!readFull(h, 32) || h[0] != 1) return;
        perKey = h[1]; const size_t words = h[4] | h[5] << 8 | h[6] << 16 | (uint32_t)h[7] << 24;
        std::vector<uint8_t> b(words * 4); if (!readFull(b.data(), b.size())) { perKey = 0; return; }
        keysyms.resize(words);
        for (size_t i = 0; i < words; i++) keysyms[i] = b[i * 4] | b[i * 4 + 1] << 8 | b[i * 4 + 2] << 16 | (uint32_t)b[i * 4 + 3] << 24;
    }
    // Finds the key producing [sym]: returns keycode (0 = none) and whether Shift is needed.
    int findKey(uint32_t sym, bool* shift) {
        *shift = false;
        for (int c = 0; c < maxKey - minKey + 1 && perKey > 0; c++) for (int col = 0; col < std::min(perKey, 2); col++)
            if (keysyms[(size_t)c * perKey + col] == sym) { *shift = col == 1; return c + minKey; }
        return 0;
    }
    bool fakeKey(int code, bool down) {
        if (fd < 0 || !code) return false;
        uint8_t r[36] = {0}; r[0] = xtestOpcode; r[1] = 2; r[2] = 9; r[4] = down ? 2 : 3; r[5] = (uint8_t)code;
        drain(); return writeFull(r, sizeof r);
    }
    // Types a key by keysym (Latin-1 keysym == code point; others are 0x1000000 + code point).
    bool tapSym(uint32_t sym) {
        bool shift; int code = findKey(sym, &shift); if (!code) return false;
        int shiftCode = 0; bool dummy; if (shift) shiftCode = findKey(0xffe1, &dummy);
        if (shiftCode) fakeKey(shiftCode, true);
        bool ok = fakeKey(code, true) && fakeKey(code, false);
        if (shiftCode) fakeKey(shiftCode, false);
        return ok;
    }
    // XTEST FakeInput: type 4 ButtonPress, 5 ButtonRelease, 6 MotionNotify (absolute: detail 0, coordinates relative to root)
    bool fake(uint8_t type, uint8_t detail, int x, int y) {
        if (fd < 0) return false;
        uint8_t r[36] = {0};
        r[0] = xtestOpcode; r[1] = 2; r[2] = 9; r[3] = 0;
        r[4] = type; r[5] = detail;
        r[12] = root & 255; r[13] = (root >> 8) & 255; r[14] = (root >> 16) & 255; r[15] = (root >> 24) & 255;
        r[24] = x & 255; r[25] = (x >> 8) & 255; r[26] = y & 255; r[27] = (y >> 8) & 255;
        drain();
        return writeFull(r, sizeof r);
    }
} xc;
}  // namespace

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_zedokmusic_engine_XDisplay_fbOpen(JNIEnv* env, jobject, jstring jpath) {
    const char* c = env->GetStringUTFChars(jpath, nullptr); std::string path(c); env->ReleaseStringUTFChars(jpath, c);
    std::lock_guard<std::mutex> lk(fbMutex);
    if (fb.map) { munmap(fb.map, fb.len); fb.map = nullptr; }
    int fd = open(path.c_str(), O_RDONLY); if (fd < 0) return JNI_FALSE;
    struct stat st; if (fstat(fd, &st) != 0) { close(fd); return JNI_FALSE; }
    void* m = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0); close(fd);
    if (m == MAP_FAILED) return JNI_FALSE;
    auto* p = static_cast<uint8_t*>(m);
    const uint32_t hs = be32(p), w = be32(p + 16), h = be32(p + 20), bpl = be32(p + 48), nc = be32(p + 76), bpp = be32(p + 44);
    if (bpp != 32 || w == 0 || h == 0 || hs + (size_t)nc * 12 + (size_t)bpl * h > (size_t)st.st_size) { munmap(m, st.st_size); return JNI_FALSE; }
    fb = {p, (size_t)st.st_size, (int)w, (int)h, bpl, hs + (size_t)nc * 12};
    return JNI_TRUE;
}
JNIEXPORT jintArray JNICALL Java_com_zedokmusic_engine_XDisplay_fbSize(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(fbMutex);
    jint v[2] = {fb.w, fb.h}; jintArray a = env->NewIntArray(2); env->SetIntArrayRegion(a, 0, 2, v); return a;
}
// Copies the (x, y, w, h) rectangle of the screen to the top-left of an RGBA_8888 bitmap.
JNIEXPORT jboolean JNICALL Java_com_zedokmusic_engine_XDisplay_fbBlit(JNIEnv* env, jobject, jobject bmp, jint x, jint y, jint w, jint h) {
    std::lock_guard<std::mutex> lk(fbMutex);
    if (!fb.map) return JNI_FALSE;
    AndroidBitmapInfo info; if (AndroidBitmap_getInfo(env, bmp, &info) != 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* px; if (AndroidBitmap_lockPixels(env, bmp, &px) != 0) return JNI_FALSE;
    const int cw = std::min<int>({w, (int)info.width, fb.w - x}), ch = std::min<int>({h, (int)info.height, fb.h - y});
    for (int r = 0; r < ch; r++) {
        const uint8_t* s = fb.map + fb.off + (size_t)(y + r) * fb.bpl + (size_t)x * 4;
        uint8_t* d = static_cast<uint8_t*>(px) + (size_t)r * info.stride;
        for (int i = 0; i < cw; i++) { d[i * 4] = s[i * 4 + 2]; d[i * 4 + 1] = s[i * 4 + 1]; d[i * 4 + 2] = s[i * 4]; d[i * 4 + 3] = 255; }
    }
    AndroidBitmap_unlockPixels(env, bmp);
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_com_zedokmusic_engine_XDisplay_inputConnect(JNIEnv* env, jobject, jstring jpath) {
    const char* c = env->GetStringUTFChars(jpath, nullptr); std::string path(c); env->ReleaseStringUTFChars(jpath, c);
    std::lock_guard<std::mutex> lk(xc.m);
    return xc.connectTo(path) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_zedokmusic_engine_XDisplay_inputClose(JNIEnv*, jobject) { std::lock_guard<std::mutex> lk(xc.m); xc.close_(); }
// action: 0 = move, 1 = button down, 2 = button up; button 1 left, 2 middle, 3 right, 4/5 wheel up/down
JNIEXPORT jboolean JNICALL Java_com_zedokmusic_engine_XDisplay_pointer(JNIEnv*, jobject, jint action, jint x, jint y, jint button) {
    std::lock_guard<std::mutex> lk(xc.m);
    if (xc.fd < 0) return JNI_FALSE;
    bool ok = xc.fake(6, 0, x, y);
    if (ok && action == 1) ok = xc.fake(4, (uint8_t)button, x, y);
    if (ok && action == 2) ok = xc.fake(5, (uint8_t)button, x, y);
    return ok ? JNI_TRUE : JNI_FALSE;
}
// Types one Unicode character (shifted when needed). Returns false when the keyboard has no such key.
JNIEXPORT jboolean JNICALL Java_com_zedokmusic_engine_XDisplay_typeChar(JNIEnv*, jobject, jint cp) {
    std::lock_guard<std::mutex> lk(xc.m);
    if (xc.fd < 0) return JNI_FALSE;
    const uint32_t sym = (cp >= 0x20 && cp <= 0x7e) || (cp >= 0xa0 && cp <= 0xff) ? (uint32_t)cp : 0x1000000u + (uint32_t)cp;
    return xc.tapSym(sym) ? JNI_TRUE : JNI_FALSE;
}
// Taps a key by X keysym (e.g. 0xff08 BackSpace, 0xff0d Return, 0xff09 Tab, 0xff1b Escape, 0xff51..0xff54 arrows).
JNIEXPORT jboolean JNICALL Java_com_zedokmusic_engine_XDisplay_tapKeysym(JNIEnv*, jobject, jint sym) {
    std::lock_guard<std::mutex> lk(xc.m);
    if (xc.fd < 0) return JNI_FALSE;
    return xc.tapSym((uint32_t)sym) ? JNI_TRUE : JNI_FALSE;
}
}
