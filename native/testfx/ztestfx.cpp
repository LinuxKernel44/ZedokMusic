// A minimal VST3 effect used to test ZedokMusic's insert effects: stereo in/out, parameter 0 "Gain" (0..2, default 1) and
// parameter 1 "Invert" (polarity). Single-component design (the component is also the edit controller). No editor.
// Build: native/testfx/build.sh  ->  ZTestGain.vst3 (a DLL; copy it to Plugins/VST3).
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include "pluginterfaces/base/ipluginbase.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include "pluginterfaces/vst/ivstparameterchanges.h"
#include "pluginterfaces/vst/vsttypes.h"
using namespace Steinberg; using namespace Steinberg::Vst;

static const TUID kCid = INLINE_UID(0x5A544658, 0x00000001, 0x00000000, 0x5A465801);

static void put(TChar* dst, const char* s, int cap = 128) { int i = 0; for (; s[i] && i < cap - 1; i++) dst[i] = (TChar)s[i]; dst[i] = 0; }

class Fx : public IComponent, public IAudioProcessor, public IEditController {
public:
    volatile long refs = 1; float gain = 0.5f, invert = 0.f; IComponentHandler* handler = nullptr;
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, FUnknown::iid) || FUnknownPrivate::iidEqual(iid, IComponent::iid) || FUnknownPrivate::iidEqual(iid, IPluginBase::iid)) { *obj = static_cast<IComponent*>(this); }
        else if (FUnknownPrivate::iidEqual(iid, IAudioProcessor::iid)) *obj = static_cast<IAudioProcessor*>(this);
        else if (FUnknownPrivate::iidEqual(iid, IEditController::iid)) *obj = static_cast<IEditController*>(this);
        else { *obj = nullptr; return kNoInterface; }
        addRef(); return kResultOk; }
    uint32 PLUGIN_API addRef() override { return InterlockedIncrement(&refs); }
    uint32 PLUGIN_API release() override { long r = InterlockedDecrement(&refs); if (!r) delete this; return r; }
    // IPluginBase
    tresult PLUGIN_API initialize(FUnknown*) override { return kResultOk; }
    tresult PLUGIN_API terminate() override { return kResultOk; }
    // IComponent
    tresult PLUGIN_API getControllerClassId(TUID) override { return kNotImplemented; }
    tresult PLUGIN_API setIoMode(IoMode) override { return kResultOk; }
    int32 PLUGIN_API getBusCount(MediaType t, BusDirection) override { return t == kAudio ? 1 : 0; }
    tresult PLUGIN_API getBusInfo(MediaType t, BusDirection d, int32 i, BusInfo& b) override {
        if (t != kAudio || i != 0) return kInvalidArgument;
        b.mediaType = kAudio; b.direction = d; b.channelCount = 2; put(b.name, d == kInput ? "In" : "Out"); b.busType = kMain; b.flags = BusInfo::kDefaultActive; return kResultOk; }
    tresult PLUGIN_API getRoutingInfo(RoutingInfo&, RoutingInfo&) override { return kNotImplemented; }
    tresult PLUGIN_API activateBus(MediaType, BusDirection, int32, TBool) override { return kResultOk; }
    tresult PLUGIN_API setActive(TBool) override { return kResultOk; }
    tresult PLUGIN_API setState(IBStream*) override { return kResultOk; }
    tresult PLUGIN_API getState(IBStream*) override { return kResultOk; }
    // IAudioProcessor
    tresult PLUGIN_API setBusArrangements(SpeakerArrangement* in, int32 nIn, SpeakerArrangement* out, int32 nOut) override { return (nIn == 1 && nOut == 1 && in[0] == SpeakerArr::kStereo && out[0] == SpeakerArr::kStereo) ? kResultTrue : kResultFalse; }
    tresult PLUGIN_API getBusArrangement(BusDirection, int32, SpeakerArrangement& a) override { a = SpeakerArr::kStereo; return kResultOk; }
    tresult PLUGIN_API canProcessSampleSize(int32 s) override { return s == kSample32 ? kResultTrue : kResultFalse; }
    uint32 PLUGIN_API getLatencySamples() override { return 0; }
    tresult PLUGIN_API setupProcessing(ProcessSetup&) override { return kResultOk; }
    tresult PLUGIN_API setProcessing(TBool) override { return kResultOk; }
    uint32 PLUGIN_API getTailSamples() override { return 0; }
    tresult PLUGIN_API process(ProcessData& d) override {
        if (d.inputParameterChanges) for (int32 i = 0; i < d.inputParameterChanges->getParameterCount(); i++) {
            IParamValueQueue* q = d.inputParameterChanges->getParameterData(i); if (!q || !q->getPointCount()) continue;
            int32 off; ParamValue v; q->getPoint(q->getPointCount() - 1, off, v);
            if (q->getParameterId() == 0) gain = (float)v; else if (q->getParameterId() == 1) invert = (float)v;
        }
        if (!d.numInputs || !d.numOutputs || d.numSamples <= 0) return kResultOk;
        const float g = gain * 2.f * (invert > 0.5f ? -1.f : 1.f);
        for (int c = 0; c < 2; c++) { const float* in = d.inputs[0].channelBuffers32[c]; float* out = d.outputs[0].channelBuffers32[c]; for (int i = 0; i < d.numSamples; i++) out[i] = in[i] * g; }
        return kResultOk; }
    // IEditController
    tresult PLUGIN_API setComponentState(IBStream*) override { return kResultOk; }
    int32 PLUGIN_API getParameterCount() override { return 2; }
    tresult PLUGIN_API getParameterInfo(int32 i, ParameterInfo& p) override {
        if (i < 0 || i > 1) return kInvalidArgument;
        memset(&p, 0, sizeof p); p.id = i; put(p.title, i == 0 ? "Gain" : "Invert"); put(p.shortTitle, i == 0 ? "Gain" : "Inv");
        p.stepCount = i == 0 ? 0 : 1; p.defaultNormalizedValue = i == 0 ? 0.5 : 0.0; p.flags = ParameterInfo::kCanAutomate; return kResultOk; }
    tresult PLUGIN_API getParamStringByValue(ParamID id, ParamValue v, String128 s) override {
        char t[32]; if (id == 0) snprintf(t, sizeof t, "%.2f", v * 2.0); else snprintf(t, sizeof t, "%s", v > 0.5 ? "On" : "Off"); put(s, t); return kResultOk; }
    tresult PLUGIN_API getParamValueByString(ParamID, TChar*, ParamValue&) override { return kNotImplemented; }
    ParamValue PLUGIN_API normalizedParamToPlain(ParamID id, ParamValue v) override { return id == 0 ? v * 2.0 : v; }
    ParamValue PLUGIN_API plainParamToNormalized(ParamID id, ParamValue v) override { return id == 0 ? v / 2.0 : v; }
    ParamValue PLUGIN_API getParamNormalized(ParamID id) override { return id == 0 ? gain : invert; }
    tresult PLUGIN_API setParamNormalized(ParamID id, ParamValue v) override { if (id == 0) gain = (float)v; else if (id == 1) invert = (float)v; else return kInvalidArgument; return kResultOk; }
    tresult PLUGIN_API setComponentHandler(IComponentHandler* h) override { handler = h; return kResultOk; }
    IPlugView* PLUGIN_API createView(FIDString) override { return nullptr; }
};

class Factory : public IPluginFactory2 {
public:
    tresult PLUGIN_API queryInterface(const TUID iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(iid, FUnknown::iid) || FUnknownPrivate::iidEqual(iid, IPluginFactory::iid) || FUnknownPrivate::iidEqual(iid, IPluginFactory2::iid)) { *obj = this; return kResultOk; }
        *obj = nullptr; return kNoInterface; }
    uint32 PLUGIN_API addRef() override { return 1; } uint32 PLUGIN_API release() override { return 1; }
    tresult PLUGIN_API getFactoryInfo(PFactoryInfo* i) override { memset(i, 0, sizeof *i); strcpy(i->vendor, "ZedokMusic tests"); strcpy(i->url, ""); strcpy(i->email, ""); i->flags = 0; return kResultOk; }
    int32 PLUGIN_API countClasses() override { return 1; }
    tresult PLUGIN_API getClassInfo(int32 i, PClassInfo* c) override {
        if (i) return kInvalidArgument; memset(c, 0, sizeof *c); memcpy(c->cid, kCid, 16); c->cardinality = PClassInfo::kManyInstances; strcpy(c->category, kVstAudioEffectClass); strcpy(c->name, "ZTest Gain"); return kResultOk; }
    tresult PLUGIN_API createInstance(FIDString cid, FIDString iid, void** obj) override {
        if (memcmp(cid, kCid, 16)) return kNoInterface; Fx* f = new Fx(); tresult r = f->queryInterface((const char*)iid, obj); f->release(); return r; }
    tresult PLUGIN_API getClassInfo2(int32 i, PClassInfo2* c) override {
        if (i) return kInvalidArgument; memset(c, 0, sizeof *c); memcpy(c->cid, kCid, 16); c->cardinality = PClassInfo::kManyInstances; strcpy(c->category, kVstAudioEffectClass); strcpy(c->name, "ZTest Gain");
        strcpy(c->subCategories, "Fx"); strcpy(c->vendor, "ZedokMusic tests"); strcpy(c->version, "1.0.0"); strcpy(c->sdkVersion, "VST 3.7"); return kResultOk; }
};
static Factory gFactory;
extern "C" {
__declspec(dllexport) bool InitDll() { return true; }
__declspec(dllexport) bool ExitDll() { return true; }
__declspec(dllexport) IPluginFactory* PLUGIN_API GetPluginFactory() { return &gFactory; }
}
