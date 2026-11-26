// A tiny VST2 plugin for testing the VST2 path of zhost: built as a synth (sine on note-on) or an effect (gain), 2 parameters, and an editor
// that is a plain child window with the parameter values written on it. Build: see build-vst2test.sh.
#include <windows.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdint>
#ifndef AS_SYNTH
#define AS_SYNTH 0
#endif
struct AEffect {
    int32_t magic; intptr_t (*dispatcher)(AEffect*, int32_t, int32_t, intptr_t, void*, float);
    void (*process)(AEffect*, float**, float**, int32_t); void (*setParameter)(AEffect*, int32_t, float); float (*getParameter)(AEffect*, int32_t);
    int32_t numPrograms, numParams, numInputs, numOutputs, flags; intptr_t resvd1, resvd2; int32_t initialDelay, realQualities, offQualities; float ioRatio;
    void* object; void* user; int32_t uniqueID, version;
    void (*processReplacing)(AEffect*, float**, float**, int32_t); void (*processDoubleReplacing)(AEffect*, double**, double**, int32_t); char future[56];
};
struct ERect { short top, left, bottom, right; };
struct MidiEvent { int32_t type, byteSize, deltaFrames, flags, noteLength, noteOffset; char midiData[4]; char detune, noteOffVelocity, r1, r2; };
struct Events { int32_t numEvents; intptr_t reserved; MidiEvent* events[2]; };
typedef intptr_t (*HostCb)(AEffect*, int32_t, int32_t, intptr_t, void*, float);
static HostCb host; static float prm[2] = {0.5f, 0.5f}; static double phase = 0, rate = 44100; static int note = -1; static float vel = 0; static HWND child = nullptr; static ERect rect{0, 0, 200, 320};
static const char* names[2] = {"Gain", "Tone"};

static void paintText(HWND h) {
    char t[128]; snprintf(t, sizeof t, "VST2 test  gain=%.2f  tone=%.2f", prm[0], prm[1]);
    HDC dc = GetDC(h); RECT r; GetClientRect(h, &r); FillRect(dc, &r, (HBRUSH)GetStockObject(DKGRAY_BRUSH)); SetTextColor(dc, RGB(255, 255, 255)); SetBkMode(dc, TRANSPARENT);
    TextOutA(dc, 10, 10, t, (int)strlen(t)); ReleaseDC(h, dc);
}
static LRESULT CALLBACK wp(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_LBUTTONDOWN) { prm[0] = (float)LOWORD(l) / 320.f; if (prm[0] > 1) prm[0] = 1; if (host) host(nullptr, 0, 0, 0, nullptr, prm[0]); paintText(h); return 0; }  // automate param 0
    if (m == WM_PAINT) { PAINTSTRUCT ps; BeginPaint(h, &ps); EndPaint(h, &ps); paintText(h); return 0; }
    return DefWindowProcA(h, m, w, l);
}
static intptr_t disp(AEffect* e, int32_t op, int32_t idx, intptr_t val, void* p, float opt) {
    switch (op) {
    case 10: rate = opt; return 0;
    case 8: if (idx >= 0 && idx < 2) strcpy((char*)p, names[idx]); return 0;
    case 7: if (idx >= 0 && idx < 2) snprintf((char*)p, 24, "%.2f", prm[idx]); return 0;
    case 6: strcpy((char*)p, "x"); return 0;
    case 45: strcpy((char*)p, AS_SYNTH ? "ZTest2 Synth" : "ZTest2 Fx"); return 1;
    case 48: strcpy((char*)p, "ZTest2"); return 1;
    case 13: *(ERect**)p = &rect; return 1;
    case 14: {
        WNDCLASSA c{}; c.lpfnWndProc = wp; c.hInstance = GetModuleHandleA(nullptr); c.lpszClassName = "ZTest2Ed"; RegisterClassA(&c);
        child = CreateWindowExA(0, "ZTest2Ed", "", WS_CHILD | WS_VISIBLE, 0, 0, rect.right, rect.bottom, (HWND)p, nullptr, c.hInstance, nullptr); return 1; }
    case 15: if (child) { DestroyWindow(child); child = nullptr; } return 1;
    case 25: { Events* ev = (Events*)p; for (int i = 0; i < ev->numEvents; i++) { MidiEvent* m = ev->events[i]; int st = m->midiData[0] & 0xF0;
        if (st == 0x90 && m->midiData[2]) { note = m->midiData[1]; vel = m->midiData[2] / 127.f; } else if (st == 0x80 || (st == 0x90) || (st == 0xB0 && m->midiData[1] == 123)) note = -1; } return 1; }
    case 51: return 0;
    }
    return 0;
}
static void setp(AEffect*, int32_t i, float v) { if (i >= 0 && i < 2) prm[i] = v; if (child) paintText(child); }
static float getp(AEffect*, int32_t i) { return (i >= 0 && i < 2) ? prm[i] : 0.f; }
static void procR(AEffect*, float** in, float** out, int32_t n) {
    for (int i = 0; i < n; i++) {
        float s;
        if (AS_SYNTH) { s = 0; if (note >= 0) { s = (float)std::sin(phase) * vel * prm[0]; phase += 6.283185307 * 440.0 * std::pow(2.0, (note - 69) / 12.0) / rate; } }
        else s = in[0][i] * prm[0] * 2.f;
        out[0][i] = s; out[1][i] = AS_SYNTH ? s : in[1][i] * prm[0] * 2.f;
    }
}
extern "C" __declspec(dllexport) AEffect* VSTPluginMain(HostCb cb) {
    static AEffect e; host = cb; memset(&e, 0, sizeof e);
    e.magic = 0x56737450; e.dispatcher = disp; e.setParameter = setp; e.getParameter = getp; e.processReplacing = procR;
    e.numParams = 2; e.numInputs = AS_SYNTH ? 0 : 2; e.numOutputs = 2; e.flags = 1 | (1 << 4) | (AS_SYNTH ? (1 << 8) : 0); e.uniqueID = AS_SYNTH ? 'ZT2S' : 'ZT2F'; e.version = 1;
    return &e;
}
