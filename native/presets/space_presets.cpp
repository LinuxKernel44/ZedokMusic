// Factory presets of Zedok Space (reverb + delay).
#include "presetgen.h"
using VV = std::vector<V>;
static VV operator+(VV a, const VV& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
static VV operator+(VV a, const V& b) { a.push_back(b); return a; }
static VV rv(const char* mode, double mix, double size, double decay, double pre, double damp, double diff = 0.75, double mod = 0.35, double low = 90, double high = 9500, double width = 1, double shim = 0.4) {
    return {V("Reverb On", 1), V("Reverb Mode", mode), V("Reverb Mix", mix), V("Size", size), V("Decay", decay), V("Pre-Delay", pre), V("Damping", damp), V("Diffusion", diff), V("Modulation", mod), V("Reverb Low Cut", low), V("Reverb High Cut", high), V("Width", width), V("Shimmer", shim)};
}
static VV noRv() { return {V("Reverb On", 0)}; }
static VV noDl() { return {V("Delay On", 0)}; }
static VV dl(const char* l, const char* r, double fb, double mix, double ping = 0, double lo = 100, double hi = 6500, double drive = 0, double wob = 0, double wobRate = 0.6, double duck = 0) {
    return {V("Delay On", 1), V("Time Mode", "Sync"), V("Time L", l), V("Time R", r), V("Feedback", fb), V("Delay Mix", mix), V("Ping-Pong", ping), V("Delay Low Cut", lo), V("Delay High Cut", hi), V("Tape Drive", drive), V("Wobble Depth", wob), V("Wobble Rate", wobRate), V("Duck", duck)};
}
static VV dlFree(double l, double r, double fb, double mix, double ping = 0, double lo = 100, double hi = 6500) {
    return {V("Delay On", 1), V("Time Mode", "Free"), V("Free Time L", l), V("Free Time R", r), V("Feedback", fb), V("Delay Mix", mix), V("Ping-Pong", ping), V("Delay Low Cut", lo), V("Delay High Cut", hi)};
}
static VV route(const char* r) { return {V("Routing", r)}; }

PresetList spacePresets() {
    PresetList L; auto P = [&](const char* n, const char* c, std::initializer_list<const char*> t, VV v) { L.emplace_back(n, c, t, std::move(v)); };
    // ------------------------------------------------------------ Rooms
    P("Small Booth", "Rooms", {"room", "tight", "vocal"}, noDl() + rv("Room", 0.22, 0.15, 0.35, 0, 0.6, 0.7, 0.2, 120, 8000));
    P("Drum Room", "Rooms", {"room", "drums", "natural"}, noDl() + rv("Room", 0.3, 0.45, 0.7, 4, 0.5, 0.8, 0.25, 140, 9000));
    P("Wood Studio", "Rooms", {"room", "warm", "natural"}, noDl() + rv("Room", 0.28, 0.35, 0.9, 6, 0.7, 0.7, 0.3, 110, 7000));
    P("Tight Ambience", "Rooms", {"room", "ambience", "snare"}, noDl() + rv("Room", 0.25, 0.1, 0.28, 0, 0.45, 0.6, 0.15, 150, 11000));
    P("Live Room", "Rooms", {"room", "live", "big"}, noDl() + rv("Room", 0.33, 0.7, 1.3, 8, 0.45, 0.8, 0.3, 100, 10000));
    P("Dark Closet", "Rooms", {"room", "dark", "lofi"}, noDl() + rv("Room", 0.3, 0.12, 0.5, 0, 0.9, 0.6, 0.2, 140, 3800));
    // ------------------------------------------------------------ Plates
    P("Vocal Plate", "Plates", {"plate", "vocal", "classic"}, noDl() + rv("Plate", 0.26, 0.5, 1.6, 12, 0.35, 0.9, 0.4, 150, 11000));
    P("Bright Plate", "Plates", {"plate", "bright", "snare"}, noDl() + rv("Plate", 0.28, 0.4, 1.3, 4, 0.15, 0.9, 0.45, 180, 14000));
    P("Dark Plate", "Plates", {"plate", "dark", "warm"}, noDl() + rv("Plate", 0.3, 0.55, 2.1, 10, 0.75, 0.85, 0.35, 120, 5500));
    P("Long Plate", "Plates", {"plate", "long", "lush"}, noDl() + rv("Plate", 0.3, 0.75, 3.6, 18, 0.4, 0.9, 0.45, 140, 10000));
    P("Gold Foil", "Plates", {"plate", "vintage", "snare"}, noDl() + rv("Plate", 0.3, 0.3, 1.0, 0, 0.2, 0.95, 0.5, 200, 13000));
    // ------------------------------------------------------------ Halls
    P("Concert Hall", "Halls", {"hall", "natural", "big"}, noDl() + rv("Hall", 0.3, 0.7, 2.8, 22, 0.45, 0.8, 0.35, 110, 9500));
    P("Warm Hall", "Halls", {"hall", "warm", "strings"}, noDl() + rv("Hall", 0.28, 0.65, 2.4, 25, 0.65, 0.8, 0.3, 100, 7000));
    P("Cathedral", "Halls", {"hall", "huge", "choir"}, noDl() + rv("Hall", 0.33, 0.95, 7.5, 35, 0.5, 0.85, 0.4, 100, 8000));
    P("Small Hall", "Halls", {"hall", "small", "piano"}, noDl() + rv("Hall", 0.24, 0.45, 1.7, 15, 0.45, 0.8, 0.3, 110, 9500));
    P("Arena", "Halls", {"hall", "arena", "drums"}, noDl() + rv("Hall", 0.28, 0.9, 3.6, 45, 0.35, 0.7, 0.25, 130, 10000));
    P("Stone Church", "Halls", {"hall", "church", "organ"}, noDl() + rv("Hall", 0.32, 0.85, 5.2, 30, 0.55, 0.8, 0.35, 120, 6500));
    // ------------------------------------------------------------ Shimmer & ambient
    P("Shimmer Cloud", "Shimmer", {"shimmer", "ambient", "pads"}, noDl() + rv("Shimmer", 0.4, 0.85, 6.5, 20, 0.4, 0.85, 0.5, 150, 12000, 1, 0.5));
    P("Octave Bloom", "Shimmer", {"shimmer", "bloom", "keys"}, noDl() + rv("Shimmer", 0.38, 0.8, 5.0, 15, 0.35, 0.9, 0.55, 160, 13000, 1, 0.7));
    P("Subtle Shimmer", "Shimmer", {"shimmer", "subtle", "vocal"}, noDl() + rv("Shimmer", 0.28, 0.6, 3.2, 20, 0.45, 0.85, 0.45, 160, 11000, 1, 0.25));
    P("Heaven", "Shimmer", {"shimmer", "huge", "cinematic"}, noDl() + rv("Shimmer", 0.45, 1.0, 12.0, 30, 0.35, 0.85, 0.55, 140, 12500, 1, 0.8));
    P("Frozen Space", "Ambient", {"ambient", "frozen", "infinite"}, noDl() + rv("Hall", 0.5, 1.0, 16.0, 40, 0.3, 0.9, 0.6, 150, 9000, 1, 0));
    P("Deep Space", "Ambient", {"ambient", "dark", "space"}, noDl() + rv("Hall", 0.5, 1.0, 10.0, 60, 0.7, 0.9, 0.55, 90, 4500));
    P("Ghost Room", "Ambient", {"ambient", "ghost", "eerie"}, noDl() + rv("Plate", 0.4, 0.9, 5.5, 80, 0.55, 0.8, 0.8, 200, 7500));
    // ------------------------------------------------------------ Delays
    P("Slap Back", "Delays", {"delay", "slap", "rockabilly"}, noRv() + dlFree(115, 125, 0.12, 0.28, 0, 150, 6500));
    P("Quarter Note Echo", "Delays", {"delay", "classic"}, noRv() + dl("1/4", "1/4", 0.35, 0.3, 0, 120, 6500));
    P("Dotted Eighth", "Delays", {"delay", "u2", "rhythmic"}, noRv() + dl("1/8 .", "1/8 .", 0.4, 0.32, 0, 150, 7000));
    P("Ping-Pong Wide", "Delays", {"delay", "stereo", "wide"}, noRv() + dl("1/8", "1/8", 0.45, 0.32, 1.0, 150, 7500));
    P("Stereo Offset", "Delays", {"delay", "stereo", "haas"}, noRv() + dl("1/8", "1/8 .", 0.35, 0.28, 0, 150, 7000));
    P("Tape Echo", "Delays", {"delay", "tape", "vintage"}, noRv() + dl("1/8 .", "1/8 .", 0.5, 0.35, 0, 200, 4500, 0.45, 1.2, 0.5));
    P("Dub Echo", "Delays", {"delay", "dub", "reggae"}, noRv() + dl("1/4 .", "1/4 .", 0.62, 0.38, 0.2, 250, 3500, 0.55, 0.8, 0.4));
    P("Triplet Bounce", "Delays", {"delay", "triplet", "groove"}, noRv() + dl("1/8 T", "1/8 T", 0.42, 0.3, 0.5, 150, 7000));
    P("Sixteenth Flutter", "Delays", {"delay", "fast", "sparkle"}, noRv() + dl("1/16", "1/16 .", 0.4, 0.26, 0.4, 250, 9000));
    P("Ducking Delay", "Delays", {"delay", "ducking", "vocal"}, noRv() + dl("1/4", "1/4", 0.45, 0.4, 0, 150, 6000, 0, 0, 0.6, 0.85));
    P("Half Note Wash", "Delays", {"delay", "long", "ambient"}, noRv() + dl("1/2", "1/2", 0.55, 0.35, 0.6, 200, 5500));
    P("Lo-fi Tape Wobble", "Delays", {"delay", "lofi", "wobble"}, noRv() + dl("1/8", "1/8", 0.5, 0.35, 0.2, 300, 3200, 0.6, 3.0, 0.8));
    P("Clean Digital Echo", "Delays", {"delay", "clean", "pop"}, noRv() + dl("1/8", "1/8", 0.3, 0.25, 0, 100, 12000));
    // ------------------------------------------------------------ Combos (delay + reverb)
    P("Vocal Space", "Combos", {"vocal", "delay", "reverb"}, route("Delay > Reverb") + dl("1/8 .", "1/4", 0.3, 0.22, 0.2, 200, 6000, 0, 0, 0.6, 0.6) + rv("Plate", 0.2, 0.5, 1.8, 14, 0.4, 0.9, 0.4, 150, 10000));
    P("Guitar Ambience", "Combos", {"guitar", "ambient", "delay"}, route("Delay > Reverb") + dl("1/4 .", "1/4", 0.4, 0.3, 0.3, 200, 5500, 0.2, 0.6, 0.5) + rv("Hall", 0.28, 0.7, 3.2, 20, 0.5, 0.85, 0.4, 150, 9000));
    P("Synth Wash", "Combos", {"synth", "wash", "lush"}, route("Delay > Reverb") + dl("1/8 .", "1/4 .", 0.45, 0.3, 0.5, 200, 7000) + rv("Shimmer", 0.35, 0.85, 6.0, 20, 0.4, 0.85, 0.5, 150, 12000, 1, 0.4));
    P("Dreamy Drift", "Combos", {"dream", "shoegaze", "wide"}, route("Delay > Reverb") + dl("1/4", "1/4 .", 0.5, 0.35, 0.6, 200, 5000, 0.2, 1.0, 0.4) + rv("Hall", 0.4, 0.9, 6.0, 25, 0.5, 0.9, 0.6, 130, 8500));
    P("Parallel Space", "Combos", {"parallel", "clean", "wide"}, route("Parallel") + dl("1/8", "1/8 .", 0.35, 0.28, 0.5, 200, 7000) + rv("Plate", 0.28, 0.6, 2.2, 10, 0.4, 0.9, 0.4, 150, 10000));
    P("Reverse Feel", "Combos", {"pre-delay", "swell"}, route("Reverb > Delay") + rv("Hall", 0.4, 0.8, 3.5, 90, 0.4, 0.9, 0.5, 140, 9000) + dl("1/4", "1/4", 0.35, 0.25, 0.4, 250, 6000));
    P("Trap Vocal Throw", "Combos", {"trap", "vocal", "throw"}, route("Delay > Reverb") + dl("1/4", "1/8 .", 0.35, 0.3, 0.8, 250, 7500, 0, 0, 0.6, 0.7) + rv("Plate", 0.22, 0.55, 2.0, 12, 0.45, 0.9, 0.4, 180, 11000));
    P("Hyperpop Sparkle", "Combos", {"hyperpop", "bright", "wide"}, route("Parallel") + dl("1/16 T", "1/16", 0.45, 0.3, 0.7, 300, 14000) + rv("Shimmer", 0.35, 0.7, 3.5, 5, 0.2, 0.9, 0.6, 200, 15000, 1, 0.6));
    P("Indie Room Echo", "Combos", {"indie", "natural", "analog"}, route("Delay > Reverb") + dl("1/8", "1/8", 0.3, 0.25, 0.3, 200, 5500, 0.25, 0.7, 0.5) + rv("Room", 0.22, 0.5, 1.2, 6, 0.55, 0.8, 0.3, 120, 8000));
    P("Cinematic Tail", "Combos", {"cinematic", "film", "big"}, route("Delay > Reverb") + dl("1/2", "1/2", 0.45, 0.25, 0.5, 200, 5000) + rv("Hall", 0.45, 1.0, 8.5, 40, 0.5, 0.85, 0.45, 110, 8500));
    // ------------------------------------------------------------ Creative
    P("Infinite Cave", "Creative", {"cave", "dark", "long"}, noDl() + rv("Hall", 0.55, 1.0, 14.0, 70, 0.8, 0.9, 0.7, 80, 3800));
    P("Metal Tank", "Creative", {"tank", "metallic", "experimental"}, noDl() + rv("Plate", 0.5, 0.35, 2.4, 0, 0.05, 1.0, 0.9, 200, 16000));
    P("Gated Snare Verb", "Creative", {"gated", "80s", "snare"}, noDl() + rv("Room", 0.5, 0.55, 0.45, 0, 0.15, 0.8, 0.1, 200, 13000));
    P("Wobbly Space", "Creative", {"wobble", "chorused", "alt"}, noDl() + rv("Hall", 0.45, 0.75, 4.2, 20, 0.45, 0.85, 1.0, 140, 9000));
    P("Wide Mono Fixer", "Creative", {"utility", "narrow"}, noDl() + rv("Room", 0.18, 0.3, 0.8, 0, 0.5, 0.7, 0.2, 150, 9000, 0.3));
    P("Echo Chamber", "Creative", {"chamber", "vintage", "echo"}, route("Parallel") + dlFree(45, 52, 0.1, 0.2, 0, 150, 4500) + rv("Room", 0.3, 0.6, 1.6, 10, 0.7, 0.9, 0.35, 140, 5500));
    P("Vinyl Cathedral", "Creative", {"lofi", "cathedral", "dark"}, noDl() + rv("Hall", 0.35, 0.9, 5.0, 25, 0.8, 0.85, 0.4, 150, 4200));
    return L;
}
