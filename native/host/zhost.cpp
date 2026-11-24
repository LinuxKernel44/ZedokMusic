// ZedokMusic Wine plugin host: loads one VST3 plugin and renders blocks on request through the shared-memory bridge.
// Usage: zhost.exe <plugin.vst3> <bridge-file> [loglevel]
#include <windows.h>
#include <emmintrin.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <unordered_map>
#include <vector>
#include "bridge.h"
#include "pluginterfaces/base/ipluginbase.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivstevents.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include "pluginterfaces/vst/ivstparameterchanges.h"
#include "pluginterfaces/vst/ivsthostapplication.h"
#include "pluginterfaces/vst/ivstprocesscontext.h"
#include "pluginterfaces/gui/iplugview.h"
using namespace Steinberg; using namespace Steinberg::Vst;

namespace {
struct HostApp : IHostApplication {
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IHostApplication::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    tresult PLUGIN_API getName(String128 n) override { const char16_t s[] = u"ZedokMusic"; for (int i = 0; i < 11; i++) n[i] = s[i]; return kResultOk; }
    tresult PLUGIN_API createInstance(TUID, TUID, void** obj) override { *obj = nullptr; return kNotImplemented; }
};
struct EvList : IEventList {
    std::vector<Event> ev;
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IEventList::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    int32 PLUGIN_API getEventCount() override { return (int32)ev.size(); }
    tresult PLUGIN_API getEvent(int32 i, Event& e) override { if (i < 0 || i >= (int)ev.size()) return kResultFalse; e = ev[i]; return kResultOk; }
    tresult PLUGIN_API addEvent(Event&) override { return kResultOk; }
};
// Minimal IParameterChanges / IParamValueQueue: one point per parameter, at sample offset 0.
struct ValueQueue : IParamValueQueue {
    ParamID id = 0; ParamValue value = 0; bool used = false;
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IParamValueQueue::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    ParamID PLUGIN_API getParameterId() override { return id; }
    int32 PLUGIN_API getPointCount() override { return 1; }
    tresult PLUGIN_API getPoint(int32 i, int32& offset, ParamValue& v) override { if (i != 0) return kResultFalse; offset = 0; v = value; return kResultOk; }
    tresult PLUGIN_API addPoint(int32, ParamValue v, int32& index) override { value = v; index = 0; return kResultOk; }
};
struct ParamChanges : IParameterChanges {
    ValueQueue q[ZK_MAX_PARAM_CHANGES + 8]; int n = 0;
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IParameterChanges::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    int32 PLUGIN_API getParameterCount() override { return n; }
    IParamValueQueue* PLUGIN_API getParameterData(int32 i) override { return (i >= 0 && i < n) ? &q[i] : nullptr; }
    IParamValueQueue* PLUGIN_API addParameterData(const ParamID& id, int32& index) override {
        for (int i = 0; i < n; i++) if (q[i].id == id) { index = i; return &q[i]; }
        if (n >= (int)(sizeof q / sizeof q[0])) return nullptr;
        q[n].id = id; index = n; return &q[n++]; }
    void clear() { n = 0; }
};
static void utf8(const TChar* src, char* dst, size_t cap) {  // UTF-16 (BMP) -> UTF-8, enough for parameter names
    size_t o = 0;
    for (int i = 0; src[i] && o + 4 < cap; i++) {
        unsigned c = src[i];
        if (c < 0x80) dst[o++] = (char)c;
        else if (c < 0x800) { dst[o++] = (char)(0xC0 | (c >> 6)); dst[o++] = (char)(0x80 | (c & 63)); }
        else { dst[o++] = (char)(0xE0 | (c >> 12)); dst[o++] = (char)(0x80 | ((c >> 6) & 63)); dst[o++] = (char)(0x80 | (c & 63)); }
    }
    dst[o] = 0;
}

// --- plugin editor support -------------------------------------------------------------------------------------------
ZkBridge* gB = nullptr; IEditController* gCtrl = nullptr;
std::unordered_map<ParamID, int> gParamIndex;  // VST3 parameter id -> index in the bridge's parameter table

// Receives edits made inside the plugin's own GUI: they are mirrored to the bridge and queued for the next audio block.
struct Handler : IComponentHandler {
    struct Item { ParamID id; ParamValue v; };
    static const int N = 512;
    Item q[N]; volatile LONG head = 0, tail = 0;  // single producer (GUI thread) / single consumer (audio loop)
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IComponentHandler::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    tresult PLUGIN_API beginEdit(ParamID) override { return kResultOk; }
    tresult PLUGIN_API endEdit(ParamID) override { return kResultOk; }
    tresult PLUGIN_API restartComponent(int32) override { return kResultOk; }
    tresult PLUGIN_API performEdit(ParamID id, ParamValue v) override {
        LONG h = head, next = (h + 1) % N;
        if (next != tail) { q[h] = {id, v}; MemoryBarrier(); head = next; }
        auto it = gParamIndex.find(id);
        if (it != gParamIndex.end() && gB) {
            gB->values[it->second] = (float)v;
            { uint32_t h = gB->edit_head; gB->edits[h % ZK_EDIT_RING] = ZkParamChange{(uint32_t)it->second, (float)v}; MemoryBarrier(); gB->edit_head = h + 1; }
            String128 str{}; if (gCtrl && gCtrl->getParamStringByValue(id, v, str) == kResultOk) { char t[96]; utf8(str, t, sizeof t); snprintf(gB->texts[it->second], sizeof gB->texts[0], "%s", t); }
        }
        return kResultOk;
    }
    bool pop(Item& o) { if (tail == head) return false; o = q[tail]; MemoryBarrier(); tail = (tail + 1) % N; return true; }
};
Handler gHandler;

struct Frame : IPlugFrame {
    HWND hwnd = nullptr; bool inResize = false;
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IPlugFrame::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    tresult PLUGIN_API resizeView(IPlugView* v, ViewRect* r) override {
        if (!r || !hwnd || inResize) return kResultFalse;
        inResize = true;
        const int w = r->right - r->left, h = r->bottom - r->top;
        SetWindowPos(hwnd, nullptr, 0, 0, w, h, SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
        ViewRect nr{0, 0, w, h}; v->onSize(&nr);
        gB->gui_w = w; gB->gui_h = h;
        inResize = false; return kResultOk;
    }
};

// Owns the editor window. Windows requires the window and its message loop to live on one thread: this is it.
DWORD WINAPI guiThread(LPVOID) {
    WNDCLASSA wc{}; wc.lpfnWndProc = DefWindowProcA; wc.hInstance = GetModuleHandleA(nullptr); wc.lpszClassName = "ZedokEditor"; wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    RegisterClassA(&wc);
    IPlugView* view = nullptr; HWND hwnd = nullptr; Frame frame;
    auto closeView = [&]() {
        if (view) { view->setFrame(nullptr); view->removed(); view->release(); view = nullptr; }
        if (hwnd) { DestroyWindow(hwnd); hwnd = nullptr; frame.hwnd = nullptr; }
        gB->gui_w = gB->gui_h = 0; InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_CLOSED);
    };
    while (!gB->quit) {
        MSG m; while (PeekMessageA(&m, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage(&m); DispatchMessageA(&m); }
        const uint32_t cmd = gB->gui_cmd;
        if (cmd == ZK_GUI_OPEN) {
            InterlockedExchange((volatile LONG*)&gB->gui_cmd, ZK_GUI_NONE);
            if (!view) {
                view = gCtrl ? gCtrl->createView(ViewType::kEditor) : nullptr;
                ViewRect r{};
                if (!view || view->isPlatformTypeSupported(kPlatformTypeHWND) != kResultTrue || view->getSize(&r) != kResultOk) {
                    if (view) { view->release(); view = nullptr; }
                    InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_FAILED);
                } else {
                    const int w = std::max(1, (int)(r.right - r.left)), h = std::max(1, (int)(r.bottom - r.top));
                    hwnd = CreateWindowExA(WS_EX_TOOLWINDOW, "ZedokEditor", "Editor", WS_POPUP, 0, 0, w, h, nullptr, nullptr, wc.hInstance, nullptr);
                    frame.hwnd = hwnd; view->setFrame(&frame);
                    if (!hwnd || view->attached(hwnd, kPlatformTypeHWND) != kResultOk) { closeView(); InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_FAILED); }
                    else {
                        ViewRect nr{0, 0, w, h}; view->onSize(&nr);
                        SetWindowPos(hwnd, HWND_TOP, 0, 0, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
                        gB->gui_x = 0; gB->gui_y = 0; gB->gui_w = w; gB->gui_h = h;
                        InterlockedExchange((volatile LONG*)&gB->gui_state, ZK_GUI_OPENED);
                    }
                }
            }
        } else if (cmd == ZK_GUI_CLOSE) {
            InterlockedExchange((volatile LONG*)&gB->gui_cmd, ZK_GUI_NONE);
            closeView();
        }
        MsgWaitForMultipleObjects(0, nullptr, FALSE, 8, QS_ALLINPUT);
    }
    closeView();
    return 0;
}

int fail(ZkBridge* b, uint32_t code, const char* msg) {
    fprintf(stderr, "zhost error %u: %s\n", code, msg); fflush(stderr);
    if (b) { b->host_error = code; InterlockedExchange((volatile LONG*)&b->host_state, ZK_HOST_ERROR); }
    return (int)code;
}
}

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: zhost plugin.vst3 bridge-file\n"); return 2; }
    // --- map the bridge ---
    HANDLE f = CreateFileA(argv[2], GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return fail(nullptr, 10, "cannot open bridge file");
    HANDLE mp = CreateFileMappingA(f, nullptr, PAGE_READWRITE, 0, (DWORD)ZK_BRIDGE_SIZE, nullptr);
    ZkBridge* B = mp ? (ZkBridge*)MapViewOfFile(mp, FILE_MAP_ALL_ACCESS, 0, 0, ZK_BRIDGE_SIZE) : nullptr;
    if (!B) return fail(nullptr, 11, "cannot map bridge");
    if (B->magic != ZK_MAGIC || B->version != ZK_VERSION) return fail(B, 12, "bridge magic/version mismatch");
    const int frames = (int)std::min<uint32_t>(B->max_frames, ZK_MAX_FRAMES);
    const double rate = B->sample_rate;

    // --- load the plugin ---
    HMODULE m = LoadLibraryA(argv[1]);
    if (!m) return fail(B, 20, "LoadLibrary failed");
    typedef bool (*InitFn)(); if (auto init = (InitFn)GetProcAddress(m, "InitDll")) init();
    typedef IPluginFactory* (*FactoryFn)(); auto gf = (FactoryFn)GetProcAddress(m, "GetPluginFactory");
    if (!gf) return fail(B, 21, "no GetPluginFactory (not a VST3?)");
    IPluginFactory* fac = gf();
    TUID cid = {}; bool found = false; char name[64] = "";
    for (int i = 0; i < fac->countClasses(); i++) {
        PClassInfo ci; fac->getClassInfo(i, &ci);
        if (!strcmp(ci.category, kVstAudioEffectClass)) { memcpy(cid, ci.cid, 16); snprintf(name, sizeof name, "%s", ci.name); found = true; break; }
    }
    if (!found) return fail(B, 22, "no audio class");
    snprintf(B->plugin_name, sizeof B->plugin_name, "%s", name);
    HostApp host; IComponent* comp = nullptr;
    if (fac->createInstance(cid, IComponent::iid, (void**)&comp) != kResultOk || !comp) return fail(B, 23, "createInstance failed");
    if (comp->initialize(&host) != kResultOk) return fail(B, 24, "initialize failed");
    IAudioProcessor* proc = nullptr; comp->queryInterface(IAudioProcessor::iid, (void**)&proc);
    if (!proc) return fail(B, 25, "no IAudioProcessor");
    const int nIn = comp->getBusCount(kAudio, kInput), nOut = comp->getBusCount(kAudio, kOutput), nEv = comp->getBusCount(kEvent, kInput);
    std::vector<SpeakerArrangement> ins(nIn, SpeakerArr::kStereo), outs(nOut, SpeakerArr::kStereo);
    proc->setBusArrangements(nIn ? ins.data() : nullptr, nIn, nOut ? outs.data() : nullptr, nOut);
    ProcessSetup ps{kRealtime, kSample32, frames, rate};
    if (proc->setupProcessing(ps) != kResultOk) return fail(B, 26, "setupProcessing failed");
    for (int i = 0; i < nIn; i++) comp->activateBus(kAudio, kInput, i, true);
    for (int i = 0; i < nOut; i++) comp->activateBus(kAudio, kOutput, i, i == 0);
    for (int i = 0; i < nEv; i++) comp->activateBus(kEvent, kInput, i, true);
    if (comp->setActive(true) != kResultOk) return fail(B, 27, "setActive failed");
    proc->setProcessing(true);
    // --- edit controller: enumerate parameters ---
    IEditController* ctrl = nullptr;
    if (comp->queryInterface(IEditController::iid, (void**)&ctrl) != kResultOk || !ctrl) {
        TUID cc = {};
        if (comp->getControllerClassId(cc) == kResultOk && fac->createInstance(cc, IEditController::iid, (void**)&ctrl) == kResultOk && ctrl) ctrl->initialize(&host);
        else ctrl = nullptr;
    }
    uint32_t np = 0;
    if (ctrl) {
        int total = ctrl->getParameterCount();
        for (int i = 0; i < total && np < ZK_MAX_PARAMS; i++) {
            ParameterInfo pi{}; if (ctrl->getParameterInfo(i, pi) != kResultOk) continue;
            if (pi.flags & (ParameterInfo::kIsReadOnly | ParameterInfo::kIsHidden | ParameterInfo::kIsProgramChange)) continue;
            ZkParamInfo& o = B->params[np]; o.id = pi.id; o.default_norm = (float)pi.defaultNormalizedValue; o.step_count = pi.stepCount; o.flags = pi.flags;
            utf8(pi.title, o.name, sizeof o.name); utf8(pi.units, o.units, sizeof o.units);
            B->values[np] = (float)ctrl->getParamNormalized(pi.id);
            String128 str{}; if (ctrl->getParamStringByValue(pi.id, B->values[np], str) == kResultOk) { char t[96]; utf8(str, t, sizeof t); snprintf(B->texts[np], sizeof B->texts[np], "%s", t); }
            np++;
        }
    }
    B->n_params = np;
    gB = B; gCtrl = ctrl;
    for (uint32_t i = 0; i < np; i++) gParamIndex[B->params[i].id] = (int)i;
    if (ctrl) ctrl->setComponentHandler(&gHandler);
    ParamChanges pcIn, pcOut;
    B->in_channels = nIn ? 2 : 0; B->out_channels = 2;

    std::vector<float> inL(frames), inR(frames), oL(frames), oR(frames);
    float* inCh[2] = {inL.data(), inR.data()}; float* outCh[2] = {oL.data(), oR.data()};
    AudioBusBuffers inB{}; inB.numChannels = 2; inB.channelBuffers32 = inCh;
    AudioBusBuffers outB{}; outB.numChannels = 2; outB.channelBuffers32 = outCh;
    EvList evl; ProcessContext pc{}; pc.state = ProcessContext::kPlaying | ProcessContext::kTempoValid; pc.tempo = 120; pc.sampleRate = rate;

    auto render = [&](int n, const ZkEvent* ev, int nev, const ZkParamChange* ch = nullptr, int nch = 0) {
        evl.ev.clear(); pcIn.clear(); pcOut.clear();
        for (int i = 0; i < nch && ch; i++) if (ch[i].index < np) { int ix; if (auto* q = pcIn.addParameterData(B->params[ch[i].index].id, ix)) q->addPoint(0, ch[i].value, ix); }
        { Handler::Item it; while (gHandler.pop(it)) { int ix; if (auto* q = pcIn.addParameterData(it.id, ix)) q->addPoint(0, it.v, ix); } }
        for (int i = 0; i < nev; i++) {
            Event e{}; e.sampleOffset = (int32)std::min<uint32_t>(ev[i].offset, (uint32_t)n - 1);
            if (ev[i].type == ZK_NOTE_ON) { e.type = Event::kNoteOnEvent; e.noteOn.channel = ev[i].channel; e.noteOn.pitch = ev[i].pitch; e.noteOn.velocity = ev[i].velocity / 127.f; e.noteOn.noteId = -1; }
            else if (ev[i].type == ZK_NOTE_OFF) { e.type = Event::kNoteOffEvent; e.noteOff.channel = ev[i].channel; e.noteOff.pitch = ev[i].pitch; e.noteOff.velocity = 0; e.noteOff.noteId = -1; }
            else if (ev[i].type == ZK_ALL_NOTES_OFF) {  // VST3 has no panic event: release every pitch
                for (int p = 0; p < 128; p++) { Event o{}; o.type = Event::kNoteOffEvent; o.sampleOffset = 0; o.noteOff.channel = ev[i].channel; o.noteOff.pitch = (int16)p; o.noteOff.noteId = -1; evl.ev.push_back(o); }
                continue;
            } else continue;
            evl.ev.push_back(e);
        }
        ProcessData d{}; d.processMode = kRealtime; d.symbolicSampleSize = kSample32; d.numSamples = n;
        d.numInputs = nIn ? 1 : 0; d.inputs = nIn ? &inB : nullptr; d.numOutputs = 1; d.outputs = &outB;
        d.inputEvents = &evl; d.processContext = &pc; d.inputParameterChanges = &pcIn; d.outputParameterChanges = &pcOut;
        proc->process(d);
        for (int i = 0; i < nch && ch; i++) if (ch[i].index < np && ctrl) {  // keep the controller (and the display text) in sync
            ctrl->setParamNormalized(B->params[ch[i].index].id, ch[i].value);
            String128 str{}; if (ctrl->getParamStringByValue(B->params[ch[i].index].id, ch[i].value, str) == kResultOk) { char t[96]; utf8(str, t, sizeof t); snprintf(B->texts[ch[i].index], sizeof B->texts[0], "%s", t); }
        } pc.projectTimeSamples += n;
    };

    // Box64's dynarec compiles code on first execution (a cold first block can take ~50 ms): warm the hot paths up front.
    ZkEvent on{0, ZK_NOTE_ON, 0, 60, 100}, off{0, ZK_NOTE_OFF, 0, 60, 0};
    for (int b = 0; b < 300; b++) {
        std::fill(inL.begin(), inL.end(), 0.f); std::fill(inR.begin(), inR.end(), 0.f);
        if (b == 5) render(frames, &on, 1); else if (b == 150) render(frames, &off, 1); else render(frames, nullptr, 0);
    }
    ZkEvent panic{0, ZK_ALL_NOTES_OFF, 0, 0, 0}; render(frames, &panic, 1);
    for (int b = 0; b < 100; b++) render(frames, nullptr, 0);  // let release tails die

    printf("zhost ready: %s, frames=%d rate=%.0f in=%d out=%d events=%d params=%u\n", name, frames, rate, nIn, nOut, nEv, np); fflush(stdout);
    InterlockedExchange((volatile LONG*)&B->host_state, ZK_HOST_READY);
    if (ctrl) CreateThread(nullptr, 0, guiThread, nullptr, 0, nullptr);

    // --- main loop ---
    // The engine submits one block per audio callback (every `frames/rate` seconds) and only needs the result one callback later,
    // so the host sleeps through most of the interval and spins only around the predicted arrival (a pure spin kept a core at 100%).
    typedef LONG (NTAPI* NtDelayFn)(BOOLEAN, PLARGE_INTEGER);
    NtDelayFn ntDelay = (NtDelayFn)GetProcAddress(GetModuleHandleA("ntdll.dll"), "NtDelayExecution");
    auto sleepTicks = [&](LONGLONG ticks, LONGLONG freq) {  // sub-millisecond sleep (Sleep(1) would be too coarse)
        LARGE_INTEGER li; li.QuadPart = -(ticks * 10000000LL / freq); if (li.QuadPart > -100) li.QuadPart = -100;
        if (ntDelay) ntDelay(FALSE, &li); else Sleep(1);
    };
    uint32_t last = B->req_seq;
    LARGE_INTEGER qf, lastWork, nowT; QueryPerformanceFrequency(&qf); QueryPerformanceCounter(&lastWork);
    const LONGLONG idleTicks = qf.QuadPart / 20;                         // no block for 50 ms: engine paused/stopped -> plain sleeping
    const LONGLONG nominal = (LONGLONG)(qf.QuadPart * (double)frames / rate);
    bool regular = false;                                                // true while requests arrive at the audio callback cadence
    LONGLONG period = nominal, lastReq = lastWork.QuadPart;              // observed request interval, time of the last request
    LONGLONG margin = qf.QuadPart * 35 / 100000;                         // wake this long before the predicted arrival (adapts)
    const LONGLONG marginMin = qf.QuadPart * 2 / 10000, marginMax = qf.QuadPart * 15 / 10000;
    while (!B->quit) {
        uint32_t r = B->req_seq;
        if (r == last) {
            QueryPerformanceCounter(&nowT);
            if (nowT.QuadPart - lastWork.QuadPart > idleTicks) { Sleep(1); continue; }
            const LONGLONG untilNext = period - (nowT.QuadPart - lastReq);
            if (regular && untilNext > margin + qf.QuadPart / 20000) sleepTicks(untilNext - margin, qf.QuadPart);
            else for (int i = 0; i < 256 && B->req_seq == last; i++) _mm_pause();
            continue;
        }
        QueryPerformanceCounter(&nowT);
        const LONGLONG iv = nowT.QuadPart - lastReq;
        if (iv > nominal / 2 && iv < nominal * 3) {                      // regular cadence: learn it and check how late we woke up
            period = (period * 15 + iv) / 16;
            if (iv - period > qf.QuadPart * 3 / 10000) margin = std::min(marginMax, margin + qf.QuadPart / 10000);
            else margin = std::max(marginMin, margin - qf.QuadPart / 200000);
            regular = true;
        } else { period = nominal; regular = false; }  // bursts (offline render) or a restart: stay on the spin path
        lastReq = nowT.QuadPart; lastWork = nowT;
        const int n = (int)std::min<uint32_t>(B->frames, (uint32_t)frames);
        const int nev = (int)std::min<uint32_t>(B->n_events, ZK_MAX_EVENTS);
        if (nIn) { memcpy(inL.data(), B->in[0], n * 4); memcpy(inR.data(), B->in[1], n * 4); }
        const int nch = (int)std::min<uint32_t>(B->n_changes, ZK_MAX_PARAM_CHANGES);
        render(n, B->events, nev, B->changes, nch);
        memcpy(B->out[0], oL.data(), n * 4); memcpy(B->out[1], oR.data(), n * 4);
        last = r; InterlockedExchange((volatile LONG*)&B->ack_seq, (LONG)r);
    }
    proc->setProcessing(false); comp->setActive(false); comp->terminate();
    return 0;
}
