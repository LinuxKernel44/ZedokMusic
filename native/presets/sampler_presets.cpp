// Factory presets of Zedok Sampler. They only shape how a sample is played (loops, chops, envelopes, filter, time handling): load your own sample in the plugin.
#include "presetgen.h"
using VV = std::vector<V>;
static VV operator+(VV a, const VV& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
static VV amp(double a, double d, double s, double r) { return {V("Amp Attack", a), V("Amp Decay", d), V("Amp Sustain", s), V("Amp Release", r)}; }
static VV loop(const char* mode, double xfade = 20, double ls = 0, double le = 1) { return {V("Loop Mode", mode), V("Loop Crossfade", xfade), V("Loop Start", ls), V("Loop End", le)}; }
static VV filt(const char* type, double cut, double res = 0.1, double env = 0, const char* dec = nullptr, double d = 300) { VV v = {V("Filter Type", type), V("Cutoff", cut), V("Resonance", res), V("Filter Env", env)}; if (dec) v.push_back(V("Flt Decay", d)); return v; }
static VV lfo(const char* shape, double rate, double pitch, double vol, double pan, double cut, const char* sync = "Free") { return {V("LFO Shape", shape), V("LFO Rate", rate), V("LFO Sync", sync), V("LFO Pitch", pitch), V("LFO Volume", vol), V("LFO Pan", pan), V("LFO Cutoff", cut)}; }
static VV pit(double amt, double decay, double sus = 0) { return {V("Pitch Env", amt), V("Pit Decay", decay), V("Pit Sustain", sus)}; }
static VV one() { return {V("One Shot", 1)}; }
static VV mono(double glide = 0, bool legato = true) { return {V("Play Mode", legato ? "Legato" : "Mono"), V("Glide", glide)}; }

PresetList samplerPresets() {
    PresetList L; auto P = [&](const char* n, const char* c, std::initializer_list<const char*> t, VV v) { L.emplace_back(n, c, t, std::move(v)); };
    // ------------------------------------------------------------ Basics
    P("One-Shot Drum", "Basics", {"drum", "one-shot", "hit"}, one() + amp(0.5, 3000, 1, 60) + VV{V("Key Tracking", 0)});
    P("Pitched One-Shot", "Basics", {"one-shot", "pitched", "keys"}, one() + amp(0.5, 3000, 1, 80));
    P("Keys Sustain", "Basics", {"keys", "sustain", "release"}, amp(1, 4000, 0.8, 350));
    P("Reverse One-Shot", "Basics", {"reverse", "one-shot", "fx"}, one() + VV{V("Reverse", 1), V("Fade Out", 25)} + amp(0.5, 3000, 1, 60));
    P("Short Stab", "Basics", {"stab", "short", "tight"}, amp(0.5, 160, 0, 60));
    P("Mono Sub Sample", "Basics", {"bass", "mono", "glide"}, mono(60) + amp(1, 3000, 1, 120) + filt("LP 24", 900, 0.1));
    P("Legato Lead Sample", "Basics", {"lead", "legato", "glide"}, mono(90) + loop("Forward", 60) + amp(8, 400, 0.9, 260) + filt("LP 12", 6500, 0.1));
    // ------------------------------------------------------------ Loops & pads
    P("Pad Loop", "Loops", {"pad", "loop", "smooth"}, loop("Forward", 140, 0.15, 0.85) + amp(500, 800, 0.9, 1400) + filt("LP 12", 5200, 0.1));
    P("Ping-Pong Pad", "Loops", {"pad", "ping-pong", "evolving"}, loop("Ping-Pong", 0, 0.2, 0.7) + amp(700, 800, 0.9, 1600) + filt("LP 12", 4200, 0.1));
    P("Sustain Loop Keys", "Loops", {"keys", "sustain loop", "release tail"}, loop("Until Release", 60, 0.35, 0.6) + amp(2, 1200, 0.85, 400));
    P("Slow Swell Loop", "Loops", {"swell", "pad", "cinematic"}, loop("Forward", 200) + amp(2500, 1500, 0.9, 2600) + filt("LP 24", 700, 0.15, 0.8, "Flt Decay", 3000));
    P("Tremolo Loop", "Loops", {"tremolo", "loop", "movement"}, loop("Forward", 100) + amp(60, 600, 0.9, 500) + lfo("Sine", 5.5, 0, 0.7, 0, 0));
    P("Auto-Pan Pad", "Loops", {"pad", "pan", "wide"}, loop("Forward", 160) + amp(600, 900, 0.9, 1500) + lfo("Sine", 0.4, 0, 0, 0.8, 0));
    // ------------------------------------------------------------ Chops
    P("Chop 8", "Chops", {"chop", "slices", "8"}, one() + VV{V("Slices", "8"), V("Root Note", 60)} + amp(0.5, 3000, 1, 25));
    P("Chop 16 Tight", "Chops", {"chop", "slices", "16"}, one() + VV{V("Slices", "16"), V("Root Note", 60)} + amp(0.5, 3000, 1, 15));
    P("Chop 4 Reverse", "Chops", {"chop", "reverse", "4"}, one() + VV{V("Slices", "4"), V("Root Note", 60), V("Reverse", 1)} + amp(0.5, 3000, 1, 25));
    P("Chop 8 Filtered", "Chops", {"chop", "filter", "lofi"}, one() + VV{V("Slices", "8"), V("Root Note", 60)} + amp(0.5, 3000, 1, 25) + filt("LP 12", 2400, 0.25, 0.5, "Flt Decay", 150));
    // ------------------------------------------------------------ Time
    P("Stretch To Tempo", "Time", {"stretch", "tempo", "loop"}, VV{V("Time Mode", "Stretch"), V("Tempo Sync", 1), V("Sample BPM", 120), V("Stretch Grain", 80)} + loop("Forward", 0) + amp(2, 3000, 1, 60));
    P("Pitch Independent Keys", "Time", {"stretch", "keys", "keep time"}, VV{V("Time Mode", "Stretch"), V("Stretch Grain", 70)} + amp(2, 3000, 1, 200));
    P("Half-Time Stretch", "Time", {"half-time", "stretch", "slow"}, VV{V("Time Mode", "Stretch"), V("Time Stretch", 0.5), V("Key Tracking", 0), V("Stretch Grain", 100)} + one() + amp(2, 3000, 1, 80));
    P("Tape Slow", "Time", {"tape", "slow", "lofi"}, VV{V("Time Stretch", 0.7), V("Key Tracking", 0)} + one() + amp(1, 3000, 1, 100) + filt("LP 12", 5000, 0.1));
    // ------------------------------------------------------------ FX
    P("Pitch Drop Kick", "FX", {"kick", "drop", "808"}, one() + pit(-24, 160) + amp(0.5, 3000, 1, 60) + VV{V("Key Tracking", 0)});
    P("Pitch Rise", "FX", {"rise", "riser", "fx"}, one() + VV{V("Pitch Env", 12), V("Pit Attack", 1800), V("Pit Sustain", 1), V("Key Tracking", 0)} + amp(60, 3000, 1, 300));
    P("Wobble Filter", "FX", {"wobble", "filter", "bass"}, loop("Forward", 80) + amp(10, 500, 0.9, 300) + filt("LP 24", 1200, 0.35) + lfo("Triangle", 2, 0, 0, 0, 0.8, "1/8"));
    P("Vibrato Sample", "FX", {"vibrato", "wobble", "pitch"}, loop("Forward", 80) + amp(30, 500, 0.9, 400) + lfo("Sine", 5.5, 0.4, 0, 0, 0));
    P("Random Pan Hits", "FX", {"random", "pan", "hits"}, one() + amp(0.5, 3000, 1, 80) + lfo("Sample & Hold", 8, 0, 0, 0.9, 0));
    return L;
}
