// ZedokMusic Wine plugin host: loads one VST3 plugin and renders blocks on request through the shared-memory bridge.
// Usage: zhost.exe <plugin.vst3> <bridge-file> [loglevel]
#include <windows.h>
#include <emmintrin.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>
#include "bridge.h"
#include "pluginterfaces/base/ipluginbase.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivstevents.h"
#include "pluginterfaces/vst/ivsthostapplication.h"
#include "pluginterfaces/vst/ivstprocesscontext.h"
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
    B->in_channels = nIn ? 2 : 0; B->out_channels = 2;

    std::vector<float> inL(frames), inR(frames), oL(frames), oR(frames);
    float* inCh[2] = {inL.data(), inR.data()}; float* outCh[2] = {oL.data(), oR.data()};
    AudioBusBuffers inB{}; inB.numChannels = 2; inB.channelBuffers32 = inCh;
    AudioBusBuffers outB{}; outB.numChannels = 2; outB.channelBuffers32 = outCh;
    EvList evl; ProcessContext pc{}; pc.state = ProcessContext::kPlaying | ProcessContext::kTempoValid; pc.tempo = 120; pc.sampleRate = rate;

    auto render = [&](int n, const ZkEvent* ev, int nev) {
        evl.ev.clear();
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
        d.inputEvents = &evl; d.processContext = &pc;
        proc->process(d); pc.projectTimeSamples += n;
    };

    // Box64's dynarec compiles code on first execution (a cold first block can take ~50 ms): warm the hot paths up front.
    ZkEvent on{0, ZK_NOTE_ON, 0, 60, 100}, off{0, ZK_NOTE_OFF, 0, 60, 0};
    for (int b = 0; b < 300; b++) {
        std::fill(inL.begin(), inL.end(), 0.f); std::fill(inR.begin(), inR.end(), 0.f);
        if (b == 5) render(frames, &on, 1); else if (b == 150) render(frames, &off, 1); else render(frames, nullptr, 0);
    }
    ZkEvent panic{0, ZK_ALL_NOTES_OFF, 0, 0, 0}; render(frames, &panic, 1);
    for (int b = 0; b < 100; b++) render(frames, nullptr, 0);  // let release tails die

    printf("zhost ready: %s, frames=%d rate=%.0f in=%d out=%d events=%d\n", name, frames, rate, nIn, nOut, nEv); fflush(stdout);
    InterlockedExchange((volatile LONG*)&B->host_state, ZK_HOST_READY);

    // --- main loop: spin while active, back off when the engine is idle ---
    uint32_t last = B->req_seq;
    LARGE_INTEGER qf, lastWork; QueryPerformanceFrequency(&qf); QueryPerformanceCounter(&lastWork);
    const LONGLONG spinTicks = qf.QuadPart / 20;  // spin for 50 ms after the last block, then sleep (engine stopped / paused)
    int poll = 0;
    while (!B->quit) {
        uint32_t r = B->req_seq;
        if (r == last) {
            _mm_pause();
            if ((++poll & 255) == 0) {  // check the clock only now and then: QPC is not free under emulation
                LARGE_INTEGER now; QueryPerformanceCounter(&now);
                if (now.QuadPart - lastWork.QuadPart > spinTicks) Sleep(1);
            }
            continue;
        }
        QueryPerformanceCounter(&lastWork);
        const int n = (int)std::min<uint32_t>(B->frames, (uint32_t)frames);
        const int nev = (int)std::min<uint32_t>(B->n_events, ZK_MAX_EVENTS);
        if (nIn) { memcpy(inL.data(), B->in[0], n * 4); memcpy(inR.data(), B->in[1], n * 4); }
        render(n, B->events, nev);
        memcpy(B->out[0], oL.data(), n * 4); memcpy(B->out[1], oR.data(), n * 4);
        last = r; InterlockedExchange((volatile LONG*)&B->ack_seq, (LONG)r);
    }
    proc->setProcessing(false); comp->setActive(false); comp->terminate();
    return 0;
}
