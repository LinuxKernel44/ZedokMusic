// Factory kits of Zedok Drums. Each kit describes its voices with a few high-level settings that are expanded into the 16 pads.
#include "presetgen.h"
using VV = std::vector<V>;

namespace {
struct Kit {
    const char *name, *cat; std::vector<const char*> tags;
    // kicks
    const char* kick; double kickTune, kickDecay, kickPunch, kickDrive; const char* kick2; double kick2Tune;
    // snare / clap / rim
    const char* snare; double snareTune, snareDecay, snareTone; double clapTone, clapDecay; double rimTune;
    // hats
    const char* hatC; double hatTone, hatDecay; const char* hatO; double hatOpenDecay;
    // toms / cymbal / percussion
    double tomTune, tomDecay; double cymDecay; const char* perc1; const char* perc2;
    // master
    double glue, sat, room, roomSize, kitTone;
};

void pad(VV& v, int n, const char* type, double tune, double decay, double tone, double punch, double drive, double level, double pan, const char* choke = "-") {
    tune = tune < -12 ? -12 : tune > 12 ? 12 : tune; tone = tone < 0 ? 0 : tone > 1 ? 1 : tone; decay = decay < 0.25 ? 0.25 : decay > 4 ? 4 : decay;
    const std::string p = "P" + std::to_string(n) + " ";
    v.push_back(V((p + "Type").c_str(), type)); v.push_back(V((p + "Tune").c_str(), tune)); v.push_back(V((p + "Decay").c_str(), decay)); v.push_back(V((p + "Tone").c_str(), tone));
    v.push_back(V((p + "Punch").c_str(), punch)); v.push_back(V((p + "Drive").c_str(), drive)); v.push_back(V((p + "Level").c_str(), level)); v.push_back(V((p + "Pan").c_str(), pan)); v.push_back(V((p + "Choke").c_str(), choke));
}

PresetDef make(const Kit& k) {
    VV v;
    pad(v, 1, k.kick, k.kickTune, k.kickDecay, 0.5, k.kickPunch, k.kickDrive, 0.85, 0);
    pad(v, 2, k.kick2, k.kick2Tune, k.kickDecay * 0.8, 0.55, k.kickPunch, k.kickDrive, 0.8, 0);
    pad(v, 3, "Rim", k.rimTune, 1.0, 0.5, 0.5, 0, 0.7, -0.15);
    pad(v, 4, k.snare, k.snareTune, k.snareDecay, k.snareTone, 0.5, k.kickDrive * 0.3, 0.85, 0);
    pad(v, 5, "Clap", 0, k.clapDecay, k.clapTone, 0.5, 0, 0.8, 0.1);
    pad(v, 6, "Tom", k.tomTune - 5, k.tomDecay, 0.4, 0.5, 0, 0.75, -0.3);
    pad(v, 7, k.hatC, 0, k.hatDecay, k.hatTone, 0.4, 0, 0.65, 0.25, "1");
    pad(v, 8, "Tom", k.tomTune, k.tomDecay, 0.4, 0.5, 0, 0.75, -0.1);
    pad(v, 9, k.hatC, 2, k.hatDecay * 0.85, k.hatTone + 0.1, 0.4, 0, 0.6, -0.2, "1");
    pad(v, 10, "Tom", k.tomTune + 4, k.tomDecay * 0.9, 0.4, 0.5, 0, 0.75, 0.15);
    pad(v, 11, k.hatO, 0, k.hatOpenDecay, k.hatTone, 0.4, 0, 0.62, 0.3, "1");
    pad(v, 12, k.perc1, 0, 1.0, 0.5, 0.5, 0, 0.65, -0.35);
    pad(v, 13, "Cymbal", 0, k.cymDecay, 0.5, 0.5, 0, 0.55, 0.4);
    pad(v, 14, "Shaker", 0, 1.0, 0.5, 0.5, 0, 0.6, -0.4);
    pad(v, 15, k.perc2, 0, 1.0, 0.5, 0.5, 0, 0.7, 0.35);
    pad(v, 16, "Click", 0, 1.0, 0.5, 0.5, 0, 0.6, 0);
    v.push_back(V("Glue", k.glue)); v.push_back(V("Saturation", k.sat)); v.push_back(V("Room", k.room)); v.push_back(V("Room Size", k.roomSize)); v.push_back(V("Kit Tone", k.kitTone));
    PresetDef d(k.name, k.cat, {}, std::move(v)); d.tags.assign(k.tags.begin(), k.tags.end()); return d;
}
}  // namespace

PresetList drumsPresets() {
    //                 name                cat           tags                                       kick    kT    kD    kP    kDr  kick2        k2T   snare         sT   sD   sTn  clT  clD  rimT  hatC          hT   hD   hatO         hOD  tomT tomD cymD  perc1       perc2     glue sat  room rSz  kitT
    static const Kit kits[] = {
        {"808 Classic",       "Electronic", {"808", "classic", "hip-hop"},             "808 Kick", 0, 1.0, 0.5, 0.0, "Sub Kick", 0,       "808 Snare", 0, 1.0, 0.5, 0.5, 1.0, 0,     "Closed Hat", 0.5, 1.0, "Open Hat", 1.0,  0, 1.0, 1.0, "Cowbell", "FM Perc",    0.0, 0.0, 0.0, 0.3, 0},
        {"808 Heavy Sub",     "Electronic", {"808", "sub", "trap"},                    "808 Kick", -3, 1.7, 0.4, 0.2, "Sub Kick", -2,     "808 Snare", -1, 0.9, 0.4, 0.5, 0.9, -1,    "Noise Hat", 0.55, 0.9, "Open Hat", 0.8,  -2, 1.2, 0.9, "Cowbell", "Click",      0.2, 0.15, 0.0, 0.3, -0.1},
        {"808 Short Punch",   "Electronic", {"808", "tight", "punch"},                 "808 Kick", 2, 0.45, 0.7, 0.1, "Punch Kick", 0,    "808 Snare", 1, 0.8, 0.6, 0.6, 0.8, 2,      "Closed Hat", 0.6, 0.9, "Open Hat", 0.7,  2, 0.7, 0.8, "Cowbell", "FM Perc",    0.3, 0.1, 0.0, 0.3, 0.1},
        {"909 House",         "Electronic", {"909", "house", "club"},                  "Punch Kick", 0, 0.9, 0.7, 0.2, "Punch Kick", 3,   "909 Snare", 0, 0.9, 0.6, 0.6, 1.0, 0,      "Closed Hat", 0.65, 0.8, "Open Hat", 1.1, 0, 0.9, 1.2, "Cowbell", "Click",      0.35, 0.1, 0.1, 0.35, 0.1},
        {"909 Techno",        "Electronic", {"909", "techno", "driving"},              "Punch Kick", -2, 1.1, 0.8, 0.45, "Punch Kick", 1, "909 Snare", -1, 0.8, 0.7, 0.7, 0.8, -2,    "Closed Hat", 0.7, 0.7, "Open Hat", 0.9,  -1, 0.9, 1.0, "Click", "FM Perc",       0.4, 0.25, 0.12, 0.4, 0.15},
        {"909 Punchy Bus",    "Electronic", {"909", "bus", "glue"},                    "Punch Kick", 0, 0.8, 0.85, 0.3, "Punch Kick", 2,  "909 Snare", 1, 0.8, 0.6, 0.6, 0.9, 1,       "Closed Hat", 0.6, 0.8, "Open Hat", 1.0,  0, 0.8, 1.1, "Cowbell", "Shaker",     0.65, 0.2, 0.08, 0.35, 0.05},
        {"707 Disco Light",   "Electronic", {"707", "disco", "bright"},                "Punch Kick", 3, 0.6, 0.5, 0.0, "808 Kick", 4,     "909 Snare", 3, 0.7, 0.4, 0.5, 0.8, 3,       "Closed Hat", 0.75, 0.7, "Open Hat", 0.8, 3, 0.7, 1.1, "Cowbell", "Click",      0.25, 0.0, 0.05, 0.3, 0.2},
        {"Electro Funk",      "Electronic", {"electro", "funk", "80s"},                "808 Kick", 1, 0.8, 0.6, 0.15, "Punch Kick", 2,    "808 Snare", 2, 0.9, 0.5, 0.5, 1.0, 2,       "Closed Hat", 0.55, 0.9, "Open Hat", 1.1, 2, 1.0, 1.2, "Cowbell", "FM Perc",     0.4, 0.15, 0.1, 0.35, 0.1},
        {"Electro Clash",     "Electronic", {"electro", "clash", "dirty"},             "Punch Kick", -1, 0.9, 0.75, 0.55, "808 Kick", 0,  "909 Snare", -2, 0.9, 0.7, 0.65, 0.9, -1,    "Noise Hat", 0.7, 0.8, "Open Hat", 0.9,  -2, 0.9, 1.0, "Click", "Cowbell",      0.5, 0.35, 0.1, 0.4, 0.2},
        {"Trap Modern",       "Hip-Hop",    {"trap", "modern", "bounce"},              "808 Kick", -4, 1.9, 0.3, 0.15, "Sub Kick", -3,    "909 Snare", 2, 0.8, 0.6, 0.55, 0.8, 2,      "Noise Hat", 0.6, 0.7, "Open Hat", 0.8,  -2, 1.0, 1.0, "Cowbell", "Click",      0.2, 0.1, 0.0, 0.3, 0.1},
        {"Trap Dark",         "Hip-Hop",    {"trap", "dark", "heavy"},                 "808 Kick", -6, 2.4, 0.3, 0.3, "Sub Kick", -5,     "808 Snare", -1, 1.0, 0.4, 0.4, 1.0, -2,    "Noise Hat", 0.45, 0.8, "Open Hat", 0.9,  -4, 1.2, 1.2, "Cowbell", "FM Perc",    0.3, 0.2, 0.1, 0.45, -0.3},
        {"Drill Slide",       "Hip-Hop",    {"drill", "uk", "dark"},                   "808 Kick", -5, 2.2, 0.35, 0.25, "Sub Kick", -4,   "909 Snare", 1, 0.7, 0.55, 0.5, 0.7, 1,      "Noise Hat", 0.55, 0.65, "Open Hat", 0.7, -3, 0.9, 1.0, "Click", "Cowbell",      0.25, 0.1, 0.05, 0.3, -0.1},
        {"Boom Bap Dusty",    "Hip-Hop",    {"boom bap", "dusty", "90s"},              "Punch Kick", 0, 0.9, 0.6, 0.2, "Punch Kick", 2,   "808 Snare", 1, 1.0, 0.6, 0.5, 1.0, 1,       "Closed Hat", 0.45, 1.0, "Open Hat", 1.0, 0, 1.0, 1.0, "Cowbell", "Shaker",      0.5, 0.35, 0.12, 0.4, -0.25},
        {"Lo-fi Dust",        "Hip-Hop",    {"lofi", "dusty", "chill"},                "Punch Kick", -2, 0.8, 0.4, 0.1, "808 Kick", 0,    "808 Snare", 0, 0.9, 0.4, 0.4, 0.9, 0,       "Closed Hat", 0.35, 0.9, "Open Hat", 0.8, -1, 0.9, 0.9, "Shaker", "FM Perc",      0.45, 0.45, 0.18, 0.45, -0.5},
        {"Lo-fi Tape Kit",    "Hip-Hop",    {"lofi", "tape", "warm"},                  "808 Kick", -1, 0.9, 0.4, 0.1, "Punch Kick", -1,   "808 Snare", -1, 1.0, 0.3, 0.4, 1.0, -1,     "Closed Hat", 0.3, 1.0, "Open Hat", 0.9, -2, 1.0, 0.8, "Shaker", "Click",         0.4, 0.55, 0.15, 0.4, -0.6},
        {"Phonk Cowbell",     "Hip-Hop",    {"phonk", "cowbell", "memphis"},           "808 Kick", -3, 1.3, 0.4, 0.4, "Punch Kick", -1,   "909 Snare", 0, 0.8, 0.6, 0.6, 0.9, 0,       "Closed Hat", 0.55, 0.8, "Open Hat", 0.8, -2, 0.9, 0.9, "Cowbell", "Cowbell",     0.3, 0.4, 0.08, 0.3, -0.1},
        {"Jersey Club",       "Club",       {"jersey", "club", "bounce"},              "Punch Kick", 1, 0.55, 0.7, 0.3, "Punch Kick", 3,  "909 Snare", 1, 0.6, 0.7, 0.7, 0.7, 1,       "Closed Hat", 0.65, 0.7, "Open Hat", 0.7, 1, 0.7, 0.8, "Click", "FM Perc",         0.4, 0.15, 0.06, 0.3, 0.1},
        {"UK Garage",         "Club",       {"garage", "2-step", "uk"},                "Punch Kick", 0, 0.8, 0.6, 0.15, "808 Kick", 2,    "909 Snare", 2, 0.7, 0.55, 0.6, 0.8, 2,      "Closed Hat", 0.6, 0.7, "Open Hat", 0.9, 0, 0.8, 1.0, "Shaker", "Click",         0.35, 0.1, 0.1, 0.35, 0.05},
        {"Tech House",        "Club",       {"tech house", "club", "groove"},          "Punch Kick", -1, 0.85, 0.8, 0.25, "Punch Kick", 2, "909 Snare", -1, 0.7, 0.6, 0.65, 0.8, -1,   "Closed Hat", 0.7, 0.65, "Open Hat", 0.85, 0, 0.8, 0.9, "Shaker", "Cowbell",       0.45, 0.2, 0.06, 0.3, 0.1},
        {"Dubstep Impact",    "Club",       {"dubstep", "heavy", "impact"},            "Punch Kick", -3, 1.2, 0.9, 0.6, "Sub Kick", -2,   "909 Snare", -3, 1.3, 0.8, 0.7, 1.2, -3,    "Closed Hat", 0.6, 0.8, "Open Hat", 0.8, -4, 1.1, 1.4, "Click", "FM Perc",         0.55, 0.5, 0.14, 0.45, 0.1},
        {"DnB Breaks",        "Club",       {"dnb", "fast", "tight"},                  "Punch Kick", 2, 0.5, 0.8, 0.2, "Punch Kick", 4,   "909 Snare", 3, 0.6, 0.75, 0.7, 0.6, 3,       "Closed Hat", 0.7, 0.55, "Open Hat", 0.6, 2, 0.6, 0.8, "Click", "Shaker",         0.45, 0.2, 0.05, 0.25, 0.15},
        {"Future Bass",       "Club",       {"future bass", "wide", "bright"},         "Punch Kick", 0, 0.9, 0.5, 0.1, "808 Kick", 1,     "909 Snare", 2, 1.1, 0.6, 0.65, 1.1, 2,      "Closed Hat", 0.65, 0.8, "Open Hat", 1.0, 0, 1.0, 1.2, "Click", "FM Perc",         0.3, 0.1, 0.2, 0.55, 0.2},
        {"Hyperpop Glitch",   "Alternative", {"hyperpop", "glitch", "distorted"},      "Punch Kick", 2, 0.7, 0.9, 0.85, "808 Kick", 0,    "909 Snare", 4, 0.7, 0.9, 0.8, 0.7, 5,       "Noise Hat", 0.85, 0.6, "Open Hat", 0.6, 4, 0.6, 0.8, "Click", "FM Perc",         0.6, 0.6, 0.1, 0.3, 0.3},
        {"Hyperpop Bright",   "Alternative", {"hyperpop", "bright", "pop"},            "Punch Kick", 1, 0.8, 0.8, 0.5, "Punch Kick", 3,   "909 Snare", 3, 0.8, 0.8, 0.7, 0.9, 4,       "Closed Hat", 0.9, 0.7, "Open Hat", 0.8, 3, 0.7, 1.0, "FM Perc", "Click",         0.5, 0.4, 0.12, 0.35, 0.4},
        {"Indie Dry",         "Alternative", {"indie", "dry", "natural"},              "Punch Kick", -2, 0.7, 0.3, 0.0, "Tom", -4,        "808 Snare", -1, 0.8, 0.4, 0.4, 0.7, -2,     "Noise Hat", 0.4, 0.8, "Open Hat", 0.7, -1, 0.8, 0.8, "Shaker", "FM Perc",         0.2, 0.05, 0.12, 0.35, -0.2},
        {"Art Pop Rhythms",   "Alternative", {"art pop", "experimental", "quirky"},    "808 Kick", 0, 0.7, 0.4, 0.1, "FM Perc", -8,       "808 Snare", 3, 0.7, 0.6, 0.5, 0.8, 5,       "Noise Hat", 0.6, 0.7, "Open Hat", 0.8, 2, 0.8, 0.9, "FM Perc", "Cowbell",         0.3, 0.2, 0.15, 0.4, 0.0},
        {"Experimental Metal","Alternative", {"experimental", "metal", "industrial"},  "Punch Kick", -4, 0.9, 0.9, 0.7, "Tom", -9,       "909 Snare", -4, 1.0, 0.9, 0.8, 1.0, -4,     "Noise Hat", 0.9, 0.6, "Cymbal", 0.5,     -6, 1.2, 1.5, "Cowbell", "FM Perc",    0.5, 0.55, 0.2, 0.5, 0.15},
        {"Pop Clean",         "Pop",        {"pop", "clean", "modern"},                "Punch Kick", 0, 0.7, 0.5, 0.0, "808 Kick", 2,     "909 Snare", 1, 0.8, 0.6, 0.6, 0.9, 1,       "Closed Hat", 0.6, 0.8, "Open Hat", 0.9, 0, 0.9, 1.0, "Shaker", "Click",          0.35, 0.05, 0.12, 0.35, 0.1},
        {"Synthpop Gated",    "Pop",        {"synthpop", "gated", "80s"},              "808 Kick", 1, 0.7, 0.6, 0.15, "Punch Kick", 2,    "909 Snare", 2, 0.55, 0.6, 0.6, 0.7, 2,      "Closed Hat", 0.6, 0.8, "Open Hat", 0.8, 1, 0.8, 1.0, "Cowbell", "FM Perc",       0.5, 0.2, 0.3, 0.45, 0.15},
        {"Synthwave Drive",   "Pop",        {"synthwave", "retro", "gated"},           "Punch Kick", 0, 0.9, 0.6, 0.2, "808 Kick", 1,     "909 Snare", 0, 1.0, 0.7, 0.6, 1.1, 0,       "Closed Hat", 0.6, 0.8, "Open Hat", 1.0, 0, 1.0, 1.2, "Cowbell", "FM Perc",         0.5, 0.2, 0.35, 0.55, 0.15},
        {"Pop Punk Tight",    "Rock",       {"rock", "tight", "live-ish"},             "Punch Kick", 1, 0.6, 0.7, 0.3, "Punch Kick", 3,   "909 Snare", 0, 0.7, 0.7, 0.7, 0.8, 0,       "Noise Hat", 0.6, 0.8, "Open Hat", 0.8, 0, 0.8, 1.0, "Shaker", "Click",          0.45, 0.2, 0.15, 0.4, 0.1},
        {"Big Rock Room",     "Rock",       {"rock", "big", "room"},                   "Punch Kick", -1, 1.0, 0.6, 0.3, "Tom", -5,        "909 Snare", -1, 1.1, 0.6, 0.6, 1.0, -1,     "Noise Hat", 0.55, 0.9, "Open Hat", 1.0, -2, 1.1, 1.3, "Cowbell", "Shaker",        0.55, 0.3, 0.3, 0.6, 0.0},
        {"Minimal Click",     "Experimental", {"minimal", "click", "glitch"},          "Click", -4, 1.0, 0.5, 0.0, "Click", 0,            "Rim", 0, 1.0, 0.5, 0.5, 0.6, 0,             "Noise Hat", 0.8, 0.4, "Noise Hat", 0.6,  0, 0.5, 0.6, "Click", "Rim",              0.1, 0.0, 0.05, 0.2, 0.3},
        {"Afro Percussion",   "Percussion", {"afro", "percussion", "organic"},         "808 Kick", 0, 0.7, 0.4, 0.1, "Tom", -7,           "FM Perc", 2, 0.9, 0.5, 0.5, 0.8, 0,         "Shaker", 0.5, 0.9, "Shaker", 1.3,         2, 0.9, 0.8, "FM Perc", "Cowbell",        0.3, 0.1, 0.15, 0.4, 0.0},
        {"Ambient Perc",      "Percussion", {"ambient", "soft", "space"},              "Sub Kick", 2, 0.7, 0.3, 0.0, "Tom", -2,           "FM Perc", -2, 1.5, 0.3, 0.3, 1.6, -4,       "Shaker", 0.4, 1.4, "Cymbal", 0.7,         -3, 1.6, 2.0, "FM Perc", "Click",          0.1, 0.0, 0.5, 0.8, -0.3},
        {"Cinematic Hits",    "Percussion", {"cinematic", "film", "epic"},             "Sub Kick", -6, 1.6, 0.6, 0.4, "808 Kick", -4,     "909 Snare", -5, 1.6, 0.8, 0.8, 1.8, -5,     "Cymbal", 0.4, 1.2, "Cymbal", 1.4,         -7, 1.8, 2.5, "Cowbell", "FM Perc",    0.4, 0.3, 0.4, 0.8, -0.1},
        {"Industrial Hits",   "Percussion", {"industrial", "harsh", "dark"},           "Punch Kick", -5, 0.8, 0.9, 0.8, "Tom", -10,       "909 Snare", -3, 0.9, 0.9, 0.9, 1.0, -3,     "Noise Hat", 0.95, 0.5, "Cymbal", 0.4,     -8, 1.0, 1.4, "Cowbell", "FM Perc",    0.6, 0.7, 0.18, 0.4, 0.2},
        {"Vapor Chill",       "Electronic", {"vapor", "chill", "soft"},                "808 Kick", 0, 1.0, 0.3, 0.0, "Sub Kick", 0,       "808 Snare", -2, 1.0, 0.3, 0.3, 1.0, -2,     "Closed Hat", 0.4, 1.1, "Open Hat", 1.0,   -1, 1.1, 1.0, "Shaker", "FM Perc",      0.3, 0.15, 0.3, 0.6, -0.3},
        {"Tight Studio",      "Pop",        {"studio", "tight", "clean"},              "Punch Kick", 0, 0.55, 0.6, 0.1, "808 Kick", 3,    "909 Snare", 2, 0.6, 0.6, 0.6, 0.7, 2,       "Closed Hat", 0.6, 0.6, "Open Hat", 0.6,   1, 0.6, 0.8, "Shaker", "Click",         0.4, 0.05, 0.05, 0.25, 0.0},
        {"Boom Room",         "Hip-Hop",    {"boom", "room", "big"},                   "808 Kick", -2, 1.3, 0.5, 0.2, "Punch Kick", -1,   "808 Snare", -1, 1.2, 0.5, 0.5, 1.2, -1,     "Closed Hat", 0.45, 1.0, "Open Hat", 1.1,  -1, 1.2, 1.2, "Cowbell", "Shaker",       0.4, 0.25, 0.35, 0.6, -0.15},
    };
    PresetList L; for (const Kit& k : kits) L.push_back(make(k)); return L;
}
