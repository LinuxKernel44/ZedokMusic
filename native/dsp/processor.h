// Interface between the engine and a native (built-in) plugin, plus the parameter description every plugin publishes.
// A native plugin is hosted exactly like a Wine plugin: it appears to the engine as a PluginBridge whose "host" is this object,
// run synchronously on the audio thread. Parameters are normalized 0..1 on the wire; the plugin maps them to real units.
#pragma once
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "../common/bridge.h"

namespace zdsp {

enum class Curve : uint8_t { Linear, Log, Power, Choice, Toggle };

struct ParamSpec {
    std::string name;            // unique, stable key (presets and projects refer to it)
    std::string group;           // UI section / category
    std::string units;           // "Hz", "dB", "ms", "%", ""
    Curve curve = Curve::Linear;
    float lo = 0.f, hi = 1.f, def = 0.f;   // real units (for Choice: index range 0..n-1, def = index)
    float skew = 2.f;            // Curve::Power exponent: real = lo + (hi-lo) * n^skew
    int decimals = 2;
    std::vector<std::string> labels;  // Choice labels

    int steps() const { return curve == Curve::Choice ? (int)labels.size() - 1 : curve == Curve::Toggle ? 1 : 0; }
    float toReal(float n) const {
        n = n < 0.f ? 0.f : (n > 1.f ? 1.f : n);
        switch (curve) {
        case Curve::Linear: return lo + (hi - lo) * n;
        case Curve::Log:    return lo * std::pow(hi / lo, n);
        case Curve::Power:  return lo + (hi - lo) * std::pow(n, skew);
        case Curve::Choice: return std::floor(n * (labels.size() - 1) + 0.5f);
        case Curve::Toggle: return n >= 0.5f ? 1.f : 0.f;
        }
        return lo;
    }
    float toNorm(float r) const {
        float n = 0.f;
        switch (curve) {
        case Curve::Linear: n = (r - lo) / (hi - lo); break;
        case Curve::Log:    n = std::log(std::max(r, lo * 1e-3f) / lo) / std::log(hi / lo); break;
        case Curve::Power:  n = std::pow(std::max((r - lo) / (hi - lo), 0.f), 1.f / skew); break;
        case Curve::Choice: n = labels.size() > 1 ? r / (labels.size() - 1) : 0.f; break;
        case Curve::Toggle: n = r >= 0.5f ? 1.f : 0.f; break;
        }
        return n < 0.f ? 0.f : (n > 1.f ? 1.f : n);
    }
    float defNorm() const { return toNorm(def); }
    std::string text(float n) const {
        const float r = toReal(n);
        char b[48];
        if (curve == Curve::Choice) { int i = (int)r; return i >= 0 && i < (int)labels.size() ? labels[i] : "?"; }
        if (curve == Curve::Toggle) return r >= 0.5f ? "On" : "Off";
        int d = decimals;
        if (units == "Hz" && std::fabs(r) >= 1000.f) { std::snprintf(b, sizeof b, "%.2f k", r / 1000.f); return b; }
        std::snprintf(b, sizeof b, d <= 0 ? "%.0f" : d == 1 ? "%.1f" : d == 2 ? "%.2f" : "%.3f", r);
        return b;
    }
};

struct ProcessCtx {
    const ZkEvent* events; int nEvents;   // note events with sample offsets inside this block
    const float* inL; const float* inR;   // effect input (instruments: silence)
    float* outL; float* outR;
    int frames;
    double tempo;                          // beats per minute (120 if the host does not know)
    double ppq;                            // timeline position of the block start, in beats (-1 when the transport is stopped)
    bool playing;
};

class Processor {
public:
    virtual ~Processor() = default;
    virtual const char* name() const = 0;
    virtual bool isInstrument() const = 0;
    virtual void prepare(double sampleRate, int maxFrames) = 0;
    virtual void reset() = 0;
    // `v` holds one normalized value per parameter, in the order of params().
    virtual void process(const ProcessCtx& c, const float* v) = 0;
    // Latency the plugin adds, in samples (reported so the host could compensate; 0 for most).
    virtual int latency() const { return 0; }
    // Values for the plugin's own display (spectrum, gain reduction, waveform...). Returns how many floats were written.
    virtual int telemetry(int what, float* out, int cap) { (void)what; (void)out; (void)cap; return 0; }
    // Non-parameter state, stored in presets and projects as text (sample path, ...). Returns false for an unknown key.
    virtual bool setState(const std::string& key, const std::string& value) { (void)key; (void)value; return false; }
    virtual std::string getState(const std::string& key) const { (void)key; return ""; }
    virtual std::vector<std::string> stateKeys() const { return {}; }

    const std::vector<ParamSpec>& params() const { return specs_; }
    int indexOf(const std::string& n) const { for (size_t i = 0; i < specs_.size(); i++) if (specs_[i].name == n) return (int)i; return -1; }
    double sampleRate() const { return sr_; }

protected:
    // Declares a parameter and returns its index.
    int add(ParamSpec s) { specs_.push_back(std::move(s)); return (int)specs_.size() - 1; }
    static ParamSpec lin(const char* grp, const char* nm, float lo, float hi, float def, const char* units = "", int dec = 2) { ParamSpec s; s.group = grp; s.name = nm; s.lo = lo; s.hi = hi; s.def = def; s.units = units; s.decimals = dec; return s; }
    static ParamSpec logp(const char* grp, const char* nm, float lo, float hi, float def, const char* units = "", int dec = 2) { ParamSpec s = lin(grp, nm, lo, hi, def, units, dec); s.curve = Curve::Log; return s; }
    static ParamSpec pw(const char* grp, const char* nm, float lo, float hi, float def, float skew, const char* units = "", int dec = 2) { ParamSpec s = lin(grp, nm, lo, hi, def, units, dec); s.curve = Curve::Power; s.skew = skew; return s; }
    static ParamSpec choice(const char* grp, const char* nm, std::vector<std::string> labels, int def = 0) { ParamSpec s; s.group = grp; s.name = nm; s.curve = Curve::Choice; s.labels = std::move(labels); s.lo = 0; s.hi = (float)s.labels.size() - 1; s.def = (float)def; return s; }
    static ParamSpec toggle(const char* grp, const char* nm, bool def = false) { ParamSpec s; s.group = grp; s.name = nm; s.curve = Curve::Toggle; s.lo = 0; s.hi = 1; s.def = def ? 1.f : 0.f; return s; }

    std::vector<ParamSpec> specs_;
    double sr_ = 48000.0;
};

// Factory for the built-in plugins: returns null for an unknown id. ids: "hybrid", "drums", "eqcomp", "space", "color", "grain".
Processor* createProcessor(const std::string& id);
// The list shown in the "Zedok" section of the plugin menus.
struct PluginDesc { const char* id; const char* name; bool instrument; const char* blurb; };
const std::vector<PluginDesc>& pluginList();

}  // namespace zdsp
