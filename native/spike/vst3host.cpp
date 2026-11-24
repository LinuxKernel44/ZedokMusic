// Minimal VST3 host (Windows x64, runs under Wine+Box64): loads a .vst3, plays a note, measures per-block CPU time.
#include <windows.h>
#include <cstdio>
#include <cmath>
#include <vector>
#include <algorithm>
#include "pluginterfaces/base/ipluginbase.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include "pluginterfaces/vst/ivstevents.h"
#include "pluginterfaces/vst/ivsthostapplication.h"
#include "pluginterfaces/vst/ivstprocesscontext.h"
using namespace Steinberg; using namespace Steinberg::Vst;

struct HostApp : IHostApplication {
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IHostApplication::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    tresult PLUGIN_API getName(String128 n) override { const char16_t s[] = u"ZedokHost"; for (int i=0;i<10;i++) n[i]=s[i]; return kResultOk; }
    tresult PLUGIN_API createInstance(TUID, TUID, void** obj) override { *obj = nullptr; return kNotImplemented; }
};
struct EvList : IEventList {
    std::vector<Event> ev;
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, IEventList::iid) || FUnknownPrivate::iidEqual(iid, FUnknown::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    int32 PLUGIN_API getEventCount() override { return (int32)ev.size(); }
    tresult PLUGIN_API getEvent(int32 i, Event& e) override { if (i<0||i>=(int)ev.size()) return kResultFalse; e = ev[i]; return kResultOk; }
    tresult PLUGIN_API addEvent(Event&) override { return kResultOk; }
};
static double now_ms() { LARGE_INTEGER t, f; QueryPerformanceCounter(&t); QueryPerformanceFrequency(&f); return t.QuadPart * 1000.0 / f.QuadPart; }

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    int frames = argc > 2 ? atoi(argv[2]) : 256, blocks = argc > 3 ? atoi(argv[3]) : 2000, voices = argc > 4 ? atoi(argv[4]) : 1, warm = argc > 5 ? atoi(argv[5]) : 0;
    double t0 = now_ms();
    HMODULE m = LoadLibraryA(argv[1]);
    if (!m) { printf("LoadLibrary failed %lu\n", GetLastError()); return 3; }
    typedef bool (*InitFn)(); InitFn init = (InitFn)GetProcAddress(m, "InitDll"); if (init) init();
    typedef IPluginFactory* (*FactoryFn)(); FactoryFn gf = (FactoryFn)GetProcAddress(m, "GetPluginFactory");
    if (!gf) { printf("no GetPluginFactory\n"); return 4; }
    IPluginFactory* fac = gf();
    printf("loaded + factory in %.0f ms, classes=%d\n", now_ms() - t0, fac->countClasses());
    HostApp host; IComponent* comp = nullptr; TUID cid = {}; bool found = false;
    for (int i = 0; i < fac->countClasses(); i++) {
        PClassInfo ci; fac->getClassInfo(i, &ci);
        printf("class %d: %s [%s]\n", i, ci.name, ci.category);
        if (!found && !strcmp(ci.category, kVstAudioEffectClass)) { memcpy(cid, ci.cid, 16); found = true; }
    }
    if (!found) { printf("no audio class\n"); return 5; }
    if (fac->createInstance(cid, IComponent::iid, (void**)&comp) != kResultOk || !comp) { printf("createInstance failed\n"); return 6; }
    printf("initialize: %d\n", comp->initialize(&host));
    IAudioProcessor* proc = nullptr; comp->queryInterface(IAudioProcessor::iid, (void**)&proc);
    if (!proc) { printf("no IAudioProcessor\n"); return 7; }
    int nIn = comp->getBusCount(kAudio, kInput), nOut = comp->getBusCount(kAudio, kOutput), nEv = comp->getBusCount(kEvent, kInput);
    printf("buses: audioIn=%d audioOut=%d eventIn=%d\n", nIn, nOut, nEv);
    SpeakerArrangement st = SpeakerArr::kStereo;
    std::vector<SpeakerArrangement> ins(nIn, st), outs(nOut, st);
    proc->setBusArrangements(nIn ? ins.data() : nullptr, nIn, nOut ? outs.data() : nullptr, nOut);
    ProcessSetup ps{kRealtime, kSample32, frames, 48000.0};
    printf("setupProcessing: %d\n", proc->setupProcessing(ps));
    for (int i = 0; i < nIn; i++) comp->activateBus(kAudio, kInput, i, true);
    for (int i = 0; i < nOut; i++) comp->activateBus(kAudio, kOutput, i, i == 0);
    for (int i = 0; i < nEv; i++) comp->activateBus(kEvent, kInput, i, true);
    printf("setActive: %d\n", comp->setActive(true));
    printf("setProcessing: %d\n", proc->setProcessing(true));

    std::vector<float> inL(frames), inR(frames), oL(frames), oR(frames);
    float* inCh[2] = {inL.data(), inR.data()}; float* outCh[2] = {oL.data(), oR.data()};
    AudioBusBuffers inB{}; inB.numChannels = 2; inB.channelBuffers32 = inCh;
    AudioBusBuffers outB{}; outB.numChannels = 2; outB.channelBuffers32 = outCh;
    EvList evl; ProcessContext pc{}; pc.state = ProcessContext::kPlaying | ProcessContext::kTempoValid; pc.tempo = 120; pc.sampleRate = 48000;
    std::vector<double> ms; float peak = 0; int nonSilentBlocks = 0;
    for (int b = 0; b < blocks; b++) {
        evl.ev.clear();
        for (int v = 0; v < voices; v++) {
            if (b % 100 == 0) { Event e{}; e.type = Event::kNoteOnEvent; e.noteOn.channel = 0; e.noteOn.pitch = 48 + v * 3 + (b / 100) % 12; e.noteOn.velocity = 0.8f; e.noteOn.noteId = -1; evl.ev.push_back(e); }
            if (b % 100 == 60) { Event e{}; e.type = Event::kNoteOffEvent; e.noteOff.channel = 0; e.noteOff.pitch = 48 + v * 3 + (b / 100) % 12; e.noteOff.velocity = 0; e.noteOff.noteId = -1; evl.ev.push_back(e); }
        }
        ProcessData d{}; d.processMode = kRealtime; d.symbolicSampleSize = kSample32; d.numSamples = frames;
        d.numInputs = nIn ? 1 : 0; d.inputs = nIn ? &inB : nullptr; d.numOutputs = 1; d.outputs = &outB;
        d.inputEvents = &evl; d.processContext = &pc;
        double a = now_ms(); proc->process(d); if (b >= warm) ms.push_back(now_ms() - a);
        pc.projectTimeSamples += frames;
        float p = 0; for (int i = 0; i < frames; i++) p = std::max(p, std::max(fabsf(oL[i]), fabsf(oR[i])));
        if (p > 1e-5f) nonSilentBlocks++; peak = std::max(peak, p);
    }
    std::sort(ms.begin(), ms.end()); double budget = frames / 48000.0 * 1000; int over = 0; double sum = 0;
    for (double v : ms) { sum += v; if (v > budget) over++; }
    printf("RESULT frames=%d blocks=%d budget=%.2fms avg=%.3f p50=%.3f p99=%.3f max=%.3f ms  cpu_load=%.1f%%  over=%d  peak=%.3f nonSilent=%d\n",
        frames, blocks, budget, sum / ms.size(), ms[ms.size() / 2], ms[(size_t)(ms.size() * 0.99)], ms.back(), 100.0 * (sum / ms.size()) / budget, over, peak, nonSilentBlocks);
    proc->setProcessing(false); comp->setActive(false); comp->terminate();
    return 0;
}
