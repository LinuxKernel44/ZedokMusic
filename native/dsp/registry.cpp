#include "processor.h"
#include "hybrid.h"
#include "eqcomp.h"
#include "space.h"
#include "color.h"
#include "drums.h"
#include "grain.h"
#include "sampler.h"
namespace zdsp {
Processor* createProcessor(const std::string& id) {
    if (id == "hybrid") return new Hybrid();
    if (id == "eqcomp") return new EqComp();
    if (id == "space") return new Space();
    if (id == "color") return new Color();
    if (id == "drums") return new Drums();
    if (id == "grain") return new Grain();
    if (id == "sampler") return new Sampler();
    return nullptr;
}
const std::vector<PluginDesc>& pluginList() {
    static const std::vector<PluginDesc> l = {
        {"hybrid", "Zedok Hybrid", true, "Analog, wavetable and FM synthesizer"},
        {"grain", "Zedok Grain", true, "Granular synthesizer for your samples and built-in textures"},
        {"sampler", "Zedok Sampler", true, "Sampler with loops, slices, time-stretch, envelopes and filter"},
        {"drums", "Zedok Drums", true, "16-pad synthesized drum machine"},
        {"space", "Zedok Space", false, "Reverb and tempo-synced delay"},
        {"color", "Zedok Color", false, "Chorus, flanger, phaser, saturation and bit crusher"},
        {"eqcomp", "Zedok EQ + Comp", false, "6-band equalizer with spectrum, compressor and limiter"},
    };
    return l;
}
}  // namespace zdsp
