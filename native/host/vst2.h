// VST 2.4 host side of zhost.exe: loads a 64-bit VST2 plugin (.dll) and serves it through the same shared-memory bridge as VST3 plugins.
// The structures below describe the plugin binary interface (field order and sizes) as needed to host a plugin; they are written for this
// project and contain no Steinberg code. Included by zhost.cpp after the helpers it uses (gB, fail, serve).
#pragma once
#include <string>
#include <cmath>

namespace vst2 {
enum : int32_t {
    // host callbacks (audioMaster...)
    mAutomate = 0, mVersion = 1, mCurrentId = 2, mIdle = 3, mWantMidi = 6, mGetTime = 7, mIOChanged = 13, mNeedIdle = 14, mSizeWindow = 15,
    mGetSampleRate = 16, mGetBlockSize = 17, mGetInputLatency = 18, mGetOutputLatency = 19, mGetProcessLevel = 23, mGetAutomationState = 24,
    mGetVendorString = 32, mGetProductString = 33, mGetVendorVersion = 34, mCanDo = 37, mGetLanguage = 38, mUpdateDisplay = 42, mBeginEdit = 43, mEndEdit = 44,
    // plugin opcodes (eff...)
    eOpen = 0, eClose = 1, eGetParamLabel = 6, eGetParamDisplay = 7, eGetParamName = 8, eSetSampleRate = 10, eSetBlockSize = 11, eMainsChanged = 12,
    eEditGetRect = 13, eEditOpen = 14, eEditClose = 15, eEditIdle = 19, eProcessEvents = 25, eGetEffectName = 45, eGetVendorString = 47, eGetProductString = 48,
    eStartProcess = 71, eStopProcess = 72,
    // AEffect flags
    fHasEditor = 1, fCanReplacing = 1 << 4, fIsSynth = 1 << 8,
};
struct AEffect;
typedef intptr_t (*HostCb)(AEffect*, int32_t, int32_t, intptr_t, void*, float);
struct AEffect {
    int32_t magic;  // 'VstP'
    intptr_t (*dispatcher)(AEffect*, int32_t, int32_t, intptr_t, void*, float);
    void (*process)(AEffect*, float**, float**, int32_t);
    void (*setParameter)(AEffect*, int32_t, float);
    float (*getParameter)(AEffect*, int32_t);
    int32_t numPrograms, numParams, numInputs, numOutputs, flags;
    intptr_t resvd1, resvd2;
    int32_t initialDelay, realQualities, offQualities;
    float ioRatio;
    void* object; void* user;
    int32_t uniqueID, version;
    void (*processReplacing)(AEffect*, float**, float**, int32_t);
    void (*processDoubleReplacing)(AEffect*, double**, double**, int32_t);
    char future[56];
};
struct ERect { short top, left, bottom, right; };
struct MidiEvent { int32_t type, byteSize, deltaFrames, flags, noteLength, noteOffset; char midiData[4]; char detune, noteOffVelocity, r1, r2; };  // type 1 = MIDI
struct Events { int32_t numEvents; intptr_t reserved; MidiEvent* events[ZK_MAX_EVENTS + 140]; };
struct TimeInfo {
    double samplePos, sampleRate, nanoSeconds, ppqPos, tempo, barStartPos, cycleStartPos, cycleEndPos;
    int32_t timeSigNumerator, timeSigDenominator, smpteOffset, smpteFrameRate, samplesToNextClock, flags;
};
typedef AEffect* (*EntryFn)(HostCb);

AEffect* gE = nullptr; HWND gEditWnd = nullptr; double gRate = 44100; int gFrames = 128; double gPpq = 0;
TimeInfo gTime{};

inline intptr_t disp(int32_t op, int32_t idx = 0, intptr_t val = 0, void* p = nullptr, float opt = 0) { return gE ? gE->dispatcher(gE, op, idx, val, p, opt) : 0; }
inline std::string text(int32_t op, int32_t idx = 0) { char b[256] = ""; disp(op, idx, 0, b); b[255] = 0; return b; }

static void paramText(int i) {
    if (!gB || i < 0 || i >= (int)ZK_MAX_PARAMS) return;
    std::string d = text(eGetParamDisplay, i); snprintf(gB->texts[i], sizeof gB->texts[0], "%s", d.c_str());
}

static intptr_t hostCallback(AEffect* e, int32_t op, int32_t index, intptr_t value, void* ptr, float opt) {
    (void)e; (void)value;
    switch (op) {
    case mAutomate:   // the plugin's own editor moved a control: mirror it to the bridge (the app records it as automation)
        if (gB && index >= 0 && (uint32_t)index < gB->n_params) {
            gB->values[index] = opt;
            { uint32_t h = gB->edit_head; gB->edits[h % ZK_EDIT_RING] = ZkParamChange{(uint32_t)index, opt}; MemoryBarrier(); gB->edit_head = h + 1; }
            paramText(index);
        }
        return 0;
    case mVersion: return 2400;
    case mCurrentId: return 0;
    case mGetTime: {
        const double bpm = gB && gB->tempo >= 20.0 && gB->tempo <= 400.0 ? gB->tempo : 120.0;   // the project's tempo, written by the engine before every block
        const bool playing = gB && gB->playing != 0;
        const int num = gB && gB->sig_num ? (int)gB->sig_num : 4, den = gB && gB->sig_den ? (int)gB->sig_den : 4;
        const double bar = num * 4.0 / den;
        gTime.sampleRate = gRate; gTime.tempo = bpm; gTime.ppqPos = gPpq; gTime.samplePos = gPpq * 60.0 / bpm * gRate;
        gTime.barStartPos = std::floor(gPpq / bar) * bar;
        gTime.timeSigNumerator = num; gTime.timeSigDenominator = den;
        gTime.flags = (playing ? 2 : 0) | (1 << 9) | (1 << 10) | (1 << 11) | (1 << 13);  // playing, ppq valid, tempo valid, bar start valid, time signature valid
        return (intptr_t)&gTime;
    }
    case mGetSampleRate: return (intptr_t)gRate;
    case mGetBlockSize: return gFrames;
    case mGetInputLatency: case mGetOutputLatency: return 0;
    case mGetProcessLevel: return 2;  // realtime
    case mGetAutomationState: return 0;
    case mGetVendorString: if (ptr) strcpy((char*)ptr, "ZedokMusic"); return 1;
    case mGetProductString: if (ptr) strcpy((char*)ptr, "ZedokMusic"); return 1;
    case mGetVendorVersion: return 1;
    case mGetLanguage: return 1;  // English
    case mCanDo: {
        const char* s = (const char*)ptr; if (!s) return 0;
        static const char* yes[] = {"sendVstEvents", "sendVstMidiEvent", "sendVstTimeInfo", "sizeWindow", "supplyIdle", "receiveVstEvents", "receiveVstMidiEvent", "openFileSelector"};
        for (const char* y : yes) if (!strcmp(s, y)) return 1;
        return 0;
    }
    case mSizeWindow:
        if (gEditWnd && index > 0 && (int)value > 0) {
            RECT rc{0, 0, index, (int)value}; LONG st = GetWindowLongA(gEditWnd, GWL_STYLE); AdjustWindowRect(&rc, st, FALSE);
            SetWindowPos(gEditWnd, nullptr, 0, 0, rc.right - rc.left, rc.bottom - rc.top, SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
            if (gB) { gB->gui_w = index; gB->gui_h = (uint32_t)value; }
        }
        return 1;
    default: return 0;
    }
}

// The editor: effEditGetRect/effEditOpen with the window we own, effEditIdle from this (the window's) thread.
static bool editSize(int& w, int& h) {
    ERect* r = nullptr; disp(eEditGetRect, 0, 0, &r);
    if (!r) return false; w = std::max(1, (int)(r->right - r->left)); h = std::max(1, (int)(r->bottom - r->top)); return true;
}

static DWORD WINAPI guiThread2(LPVOID) {
    WNDCLASSA wc{}; wc.lpfnWndProc = DefWindowProcA; wc.hInstance = GetModuleHandleA(nullptr); wc.lpszClassName = "ZedokEditor"; wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    RegisterClassA(&wc);
    bool open = false;
    auto closeView = [&]() {
        if (open) { disp(eEditClose); open = false; }
        if (gEditWnd) { DestroyWindow(gEditWnd); gEditWnd = nullptr; }
        gB->gui_w = gB->gui_h = 0; InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_CLOSED);
    };
    while (!gB->quit) {
        MSG m; while (PeekMessageA(&m, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage(&m); DispatchMessageA(&m); }
        const uint32_t cmd = gB->gui_cmd;
        if (cmd == ZK_GUI_OPEN) {
            InterlockedExchange((volatile LONG*)&gB->gui_cmd, ZK_GUI_NONE);
            if (!open) {
                int w = 400, h = 300; editSize(w, h);
                const int px = (int)gB->gui_x_pos_req, py = (int)gB->gui_y_pos_req;
                gEditWnd = CreateWindowExA(WS_EX_TOOLWINDOW, "ZedokEditor", "Editor", WS_POPUP, px, py, w, h, nullptr, nullptr, wc.hInstance, nullptr);
                if (!gEditWnd || !disp(eEditOpen, 0, 0, gEditWnd)) { if (gEditWnd) { DestroyWindow(gEditWnd); gEditWnd = nullptr; } InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_FAILED); }
                else {
                    open = true; editSize(w, h);   // many plugins only know their size once the editor is open
                    SetWindowPos(gEditWnd, HWND_TOP, px, py, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
                    gB->gui_x = px; gB->gui_y = py; gB->gui_w = w; gB->gui_h = h;
                    InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_OPENED);
                }
            }
        } else if (cmd == ZK_GUI_CLOSE) { InterlockedExchange((volatile LONG*)&gB->gui_cmd, ZK_GUI_NONE); closeView(); }
        if (open) disp(eEditIdle);
        MsgWaitForMultipleObjects(0, nullptr, FALSE, 16, QS_ALLINPUT);
    }
    closeView();
    return 0;
}

static AEffect* create(HMODULE m) {
    auto entry = (EntryFn)GetProcAddress(m, "VSTPluginMain"); if (!entry) entry = (EntryFn)GetProcAddress(m, "main");
    if (!entry) return nullptr;
    AEffect* e = entry(hostCallback);
    return (e && e->magic == 0x56737450) ? e : nullptr;
}

// `--probe` for a VST2 DLL: kind (1 instrument, 2 effect) and name; -1 when the DLL is not a VST2 plugin at all.
static int probe(HMODULE m, const char* outFile) {
    int kind = -1; char name[64] = "";
    if (AEffect* e = create(m)) {
        gE = e; disp(eOpen);
        kind = (e->flags & fIsSynth) ? 1 : 2;
        std::string n = text(eGetEffectName); if (n.empty()) n = text(eGetProductString); snprintf(name, sizeof name, "%s", n.c_str());
    }
    FILE* f = fopen(outFile, "w"); if (!f) return 3;
    fprintf(f, "%d\t%s\n", kind, name); fclose(f);
    fflush(stdout); TerminateProcess(GetCurrentProcess(), 0);
    return 0;
}

// `--edit` for a VST2 DLL: the plugin's editor in a normal window (licences, one-time setup) with no audio.
static LRESULT CALLBACK editProc2(HWND h, UINT msg, WPARAM w, LPARAM l) {
    if (msg == WM_TIMER) { disp(eEditIdle); return 0; }
    if (msg == WM_CLOSE) { DestroyWindow(h); return 0; }
    if (msg == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(h, msg, w, l);
}
static int edit(HMODULE m) {
    static ZkBridge dummy; gB = &dummy;
    AEffect* e = create(m);
    if (!e) { MessageBoxA(nullptr, "This is not a VST2 or VST3 plugin.", "ZedokMusic", MB_OK); return 21; }
    gE = e; disp(eOpen); disp(eSetSampleRate, 0, 0, nullptr, 44100.f); disp(eSetBlockSize, 0, 512); disp(eMainsChanged, 0, 1);
    if (!(e->flags & fHasEditor)) { MessageBoxA(nullptr, "This plugin has no editor.", "ZedokMusic", MB_OK); return 24; }
    int w = 400, h = 300; editSize(w, h);
    WNDCLASSA wc{}; wc.lpfnWndProc = editProc2; wc.hInstance = GetModuleHandleA(nullptr); wc.lpszClassName = "ZedokPluginWindow2"; wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH); wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    RegisterClassA(&wc);
    RECT rc{0, 0, w, h}; AdjustWindowRect(&rc, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE);
    std::string title = text(eGetEffectName) + " (plugin)";
    gEditWnd = CreateWindowExA(0, "ZedokPluginWindow2", title.c_str(), WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE, 16, 16, rc.right - rc.left, rc.bottom - rc.top, nullptr, nullptr, wc.hInstance, nullptr);
    if (!gEditWnd || !disp(eEditOpen, 0, 0, gEditWnd)) { MessageBoxA(nullptr, "The editor could not be opened.", "ZedokMusic", MB_OK); return 26; }
    editSize(w, h); RECT r2{0, 0, w, h}; AdjustWindowRect(&r2, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE);
    SetWindowPos(gEditWnd, nullptr, 0, 0, r2.right - r2.left, r2.bottom - r2.top, SWP_NOMOVE | SWP_NOZORDER);
    SetTimer(gEditWnd, 1, 20, nullptr);
    MSG msg; while (GetMessageA(&msg, nullptr, 0, 0)) { TranslateMessage(&msg); DispatchMessageA(&msg); }
    disp(eEditClose);
    fflush(stdout); TerminateProcess(GetCurrentProcess(), 0);
    return 0;
}

// Normal start: sets the plugin up, publishes its parameters, warms it up and serves blocks.
static int run(ZkBridge* B, HMODULE m, int frames, double rate) {
    AEffect* e = create(m);
    if (!e) return fail(B, 21, "no VSTPluginMain / GetPluginFactory (not a VST plugin?)");
    gE = e; gRate = rate; gFrames = frames; gB = B;
    disp(eOpen); disp(eSetSampleRate, 0, 0, nullptr, (float)rate); disp(eSetBlockSize, 0, frames); disp(eMainsChanged, 0, 1); disp(eStartProcess);
    std::string nm = text(eGetEffectName); if (nm.empty()) nm = text(eGetProductString); if (nm.empty()) nm = "VST2 plugin";
    snprintf(B->plugin_name, sizeof B->plugin_name, "%s", nm.c_str());
    B->plugin_kind = (e->flags & fIsSynth) ? 1 : 2;
    const uint32_t np = (uint32_t)std::min(std::max(e->numParams, 0), (int)ZK_MAX_PARAMS);
    for (uint32_t i = 0; i < np; i++) {
        ZkParamInfo& o = B->params[i]; o.id = i; o.step_count = 0; o.flags = 0;
        snprintf(o.name, sizeof o.name, "%s", text(eGetParamName, i).c_str()); snprintf(o.units, sizeof o.units, "%s", text(eGetParamLabel, i).c_str());
        if (!o.name[0]) snprintf(o.name, sizeof o.name, "Param %u", i + 1);
        B->values[i] = o.default_norm = e->getParameter(e, i); paramText(i);
    }
    B->n_params = np;
    const int nIn = e->numInputs, nOut = e->numOutputs, nI = std::max(nIn, 2), nO = std::max(nOut, 2);
    B->in_channels = nIn ? 2 : 0; B->out_channels = 2;
    std::vector<std::vector<float>> inBuf(nI, std::vector<float>(frames, 0.f)), outBuf(nO, std::vector<float>(frames, 0.f));
    std::vector<float*> inP(nI), outP(nO); for (int i = 0; i < nI; i++) inP[i] = inBuf[i].data(); for (int i = 0; i < nO; i++) outP[i] = outBuf[i].data();
    static Events evs; static MidiEvent pool[ZK_MAX_EVENTS + 140]; int nPool;
    auto add = [&](uint32_t off, int n, uint8_t s, uint8_t d1, uint8_t d2) {
        if (nPool >= (int)(sizeof pool / sizeof pool[0])) return;
        MidiEvent& me = pool[nPool]; me = MidiEvent{}; me.type = 1; me.byteSize = sizeof(MidiEvent); me.deltaFrames = (int32_t)std::min<uint32_t>(off, (uint32_t)n - 1);
        me.midiData[0] = (char)s; me.midiData[1] = (char)d1; me.midiData[2] = (char)d2; evs.events[nPool] = &me; nPool++;
    };
    auto render = [&](int n, const ZkEvent* ev, int nev, const ZkParamChange* ch, int nch) {
        for (int i = 0; i < nch && ch; i++) if (ch[i].index < np) { e->setParameter(e, (int32_t)ch[i].index, ch[i].value); B->values[ch[i].index] = ch[i].value; paramText((int)ch[i].index); }
        if (B->ppq >= 0.0) gPpq = B->ppq;   // position of this block, from the engine
        nPool = 0;
        for (int i = 0; i < nev; i++) {
            const uint8_t c = ev[i].channel & 15;
            if (ev[i].type == ZK_NOTE_ON) add(ev[i].offset, n, 0x90 | c, ev[i].pitch, ev[i].velocity);
            else if (ev[i].type == ZK_NOTE_OFF) add(ev[i].offset, n, 0x80 | c, ev[i].pitch, 0);
            else if (ev[i].type == ZK_ALL_NOTES_OFF) { add(0, n, 0xB0 | c, 123, 0); add(0, n, 0xB0 | c, 120, 0); }
        }
        if (nPool) { evs.numEvents = nPool; evs.reserved = 0; disp(eProcessEvents, 0, 0, &evs); }
        for (auto& b : outBuf) std::fill(b.begin(), b.end(), 0.f);
        if (e->flags & fCanReplacing) e->processReplacing(e, inP.data(), outP.data(), n); else if (e->process) e->process(e, inP.data(), outP.data(), n);
        if (B->ppq < 0.0) gPpq += n / rate * (B->tempo >= 20.0 && B->tempo <= 400.0 ? B->tempo : 120.0) / 60.0;   // stopped: keep a running position
    };
    ZkEvent on{0, ZK_NOTE_ON, 0, 60, 100}, off{0, ZK_NOTE_OFF, 0, 60, 0};
    for (int b = 0; b < 300; b++) {   // warm the dynarec up before going realtime (see the VST3 path)
        for (int i = 0; i < nI; i++) std::fill(inBuf[i].begin(), inBuf[i].end(), 0.f);
        if (b == 5) render(frames, &on, 1, nullptr, 0); else if (b == 150) render(frames, &off, 1, nullptr, 0); else render(frames, nullptr, 0, nullptr, 0);
    }
    ZkEvent panic{0, ZK_ALL_NOTES_OFF, 0, 0, 0}; render(frames, &panic, 1, nullptr, 0);
    for (int b = 0; b < 100; b++) render(frames, nullptr, 0, nullptr, 0);
    printf("zhost ready (VST2): %s, frames=%d rate=%.0f in=%d out=%d params=%u\n", nm.c_str(), frames, rate, nIn, nOut, np); fflush(stdout);
    InterlockedExchange((volatile LONG*)&B->host_state, ZK_HOST_READY);
    if (e->flags & fHasEditor) CreateThread(nullptr, 0, guiThread2, nullptr, 0, nullptr);
    serve(B, frames, rate, nIn, [&](int n, int nev, int nch) {
        if (nIn) { memcpy(inBuf[0].data(), B->in[0], n * 4); memcpy(inBuf[1].data(), B->in[1], n * 4); }
        render(n, B->events, nev, B->changes, nch);
        memcpy(B->out[0], outBuf[0].data(), n * 4); memcpy(B->out[1], outBuf[nOut >= 2 ? 1 : 0].data(), n * 4);
    });
    disp(eStopProcess); disp(eMainsChanged, 0, 0); disp(eClose);
    return 0;
}
}  // namespace vst2
