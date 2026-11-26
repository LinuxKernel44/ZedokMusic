// Factory preset authoring for the Zedok plugins. Presets are written in C++ with readable values (real units or choice labels),
// converted to normalized parameter values with the plugin's own parameter table, level-matched by rendering them, and
// written as JSON into the app's assets.
#pragma once
#include <initializer_list>
#include <map>
#include <string>
#include <vector>

struct V {  // one parameter assignment: name + a number (real units) or a label (choice parameters)
    std::string k; double d = 0; std::string s; bool isStr = false;
    V(const char* key, double v) : k(key), d(v) {}
    V(const char* key, int v) : k(key), d((double)v) {}
    V(const char* key, const char* label) : k(key), s(label), isStr(true) {}
};
struct PresetDef {
    std::string name, category; std::vector<std::string> tags; std::vector<V> values; std::map<std::string, std::string> state;
    PresetDef(const char* n, const char* cat, std::initializer_list<const char*> tg, std::initializer_list<V> v) : name(n), category(cat), tags(tg.begin(), tg.end()), values(v) {}
    PresetDef(const char* n, const char* cat, std::initializer_list<const char*> tg, std::vector<V> v) : name(n), category(cat), tags(tg.begin(), tg.end()), values(std::move(v)) {}
};
using PresetList = std::vector<PresetDef>;
PresetList hybridPresets();
