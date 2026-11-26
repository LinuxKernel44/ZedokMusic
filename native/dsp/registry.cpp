#include "processor.h"
#include "hybrid.h"
namespace zdsp {
Processor* createProcessor(const std::string& id) {
    if (id == "hybrid") return new Hybrid();
    return nullptr;
}
const std::vector<PluginDesc>& pluginList() {
    static const std::vector<PluginDesc> l = {
        {"hybrid", "Zedok Hybrid", true, "Analog, wavetable and FM synthesizer"},
    };
    return l;
}
}  // namespace zdsp
