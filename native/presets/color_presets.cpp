// Factory presets of Zedok Color (modulation + distortion + crusher).
#include "presetgen.h"
using VV = std::vector<V>;
static VV operator+(VV a, const VV& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
static VV operator+(VV a, const V& b) { a.push_back(b); return a; }
static VV mod(const char* type, double rate, double depth, double mix, double center = 10, double fb = 0, double spread = 0.8, double tone = 12000) {
    return {V("Mod Type", type), V("Rate", rate), V("Depth", depth), V("Mod Mix", mix), V("Center", center), V("Mod Feedback", fb), V("Stereo Spread", spread), V("Mod Tone", tone)};
}
static VV modSync(const char* type, const char* div, double depth, double mix, double center = 10, double fb = 0) { return {V("Mod Type", type), V("Mod Sync", 1), V("Mod Division", div), V("Depth", depth), V("Mod Mix", mix), V("Center", center), V("Mod Feedback", fb)}; }
static VV phaser(double rate, double depth, double center, double fb, const char* stages, double mix = 0.5, double spread = 0.6) { return {V("Mod Type", "Phaser"), V("Rate", rate), V("Depth", depth), V("Phaser Center", center), V("Mod Feedback", fb), V("Stages", stages), V("Mod Mix", mix), V("Stereo Spread", spread)}; }
static VV noMod() { return {V("Mod Type", "Off")}; }
static VV dist(const char* type, double drive, double bias = 0, double tone = 12000, double mix = 1, double out = 0, double preHp = 20, bool hq = true) {
    return {V("Dist On", 1), V("Dist Type", type), V("Drive", drive), V("Bias", bias), V("Tone", tone), V("Dist Mix", mix), V("Dist Output", out), V("Pre High-Pass", preHp), V("Oversampling", hq ? 1 : 0)};
}
static VV noDist() { return {V("Dist On", 0)}; }
static VV crush(double bits, double down, double jitter = 0, double mix = 1) { return {V("Crush On", 1), V("Bits", bits), V("Downsample", down), V("Jitter", jitter), V("Crush Mix", mix)}; }
static VV order(const char* o) { return {V("Order", o)}; }
static VV wet(double w) { return {V("Dry/Wet", w)}; }

PresetList colorPresets() {
    PresetList L; auto P = [&](const char* n, const char* c, std::initializer_list<const char*> t, VV v) { L.emplace_back(n, c, t, std::move(v)); };
    // ------------------------------------------------------------ Chorus & ensemble
    P("Classic Chorus", "Chorus", {"chorus", "classic", "guitar"}, noDist() + mod("Chorus", 0.6, 0.45, 0.5, 10, 0, 0.9));
    P("Slow Lush Chorus", "Chorus", {"chorus", "lush", "pads"}, noDist() + mod("Chorus", 0.22, 0.6, 0.55, 12, 0, 1.0, 9000) + V("Voices", "4"));
    P("Shimmer Chorus", "Chorus", {"chorus", "bright", "keys"}, noDist() + mod("Chorus", 0.9, 0.35, 0.45, 8, 0, 1.0, 16000) + V("Voices", "3"));
    P("Tiny Doubler", "Chorus", {"double", "thick", "vocal"}, noDist() + mod("Chorus", 0.35, 0.15, 0.4, 5, 0, 0.7, 12000) + V("Voices", "2"));
    P("Wide Ensemble", "Chorus", {"ensemble", "strings", "wide"}, noDist() + mod("Ensemble", 0.5, 0.55, 0.6, 12, 0, 1.0, 10000));
    P("String Machine", "Chorus", {"ensemble", "70s", "pads"}, noDist() + mod("Ensemble", 0.3, 0.7, 0.7, 14, 0, 1.0, 7500));
    P("Dimension Hint", "Chorus", {"chorus", "subtle", "mix"}, noDist() + mod("Chorus", 0.25, 0.25, 0.3, 9, 0, 0.8, 11000) + V("Voices", "2"));
    P("Seasick", "Chorus", {"chorus", "detune", "wobble"}, noDist() + mod("Chorus", 0.5, 1.0, 0.6, 14, 0, 0.9, 8000) + V("Voices", "3"));
    P("Bass Chorus", "Chorus", {"chorus", "bass", "clean"}, noDist() + mod("Chorus", 0.4, 0.3, 0.35, 10, 0, 0.6, 5000) + V("Voices", "2"));
    // ------------------------------------------------------------ Flanger
    P("Jet Flanger", "Flanger", {"flanger", "jet", "classic"}, noDist() + mod("Flanger", 0.18, 0.8, 0.55, 5, 0.65, 0.7, 14000));
    P("Soft Flanger", "Flanger", {"flanger", "soft", "keys"}, noDist() + mod("Flanger", 0.3, 0.5, 0.45, 6, 0.3, 0.8, 11000));
    P("Through Zero Feel", "Flanger", {"flanger", "negative", "hollow"}, noDist() + mod("Flanger", 0.12, 0.9, 0.55, 4, -0.7, 0.6, 15000));
    P("Fast Whoosh", "Flanger", {"flanger", "fast", "fx"}, noDist() + mod("Flanger", 2.5, 0.6, 0.5, 4, 0.55, 0.9, 13000));
    P("Tempo Flange", "Flanger", {"flanger", "sync", "rhythmic"}, noDist() + modSync("Flanger", "1 bar", 0.8, 0.55, 5, 0.6));
    P("Metallic Comb", "Flanger", {"flanger", "metal", "resonant"}, noDist() + mod("Flanger", 0.05, 0.3, 0.6, 2, 0.9, 0.5, 16000));
    // ------------------------------------------------------------ Phaser
    P("Classic Phaser", "Phaser", {"phaser", "classic", "guitar"}, noDist() + phaser(0.4, 0.7, 700, 0.4, "4", 0.5));
    P("Slow Sweep", "Phaser", {"phaser", "slow", "pads"}, noDist() + phaser(0.12, 0.85, 600, 0.5, "8", 0.55));
    P("Deep Twelve", "Phaser", {"phaser", "deep", "lush"}, noDist() + phaser(0.25, 0.8, 900, 0.6, "12", 0.55));
    P("Fast Rotary-ish", "Phaser", {"phaser", "fast", "organ"}, noDist() + phaser(4.5, 0.4, 900, 0.2, "4", 0.45));
    P("Resonant Phaser", "Phaser", {"phaser", "resonant", "synth"}, noDist() + phaser(0.3, 0.8, 1200, 0.85, "6", 0.55));
    P("Barber Phase", "Phaser", {"phaser", "negative", "psychedelic"}, noDist() + phaser(0.2, 0.9, 700, -0.7, "8", 0.6));
    P("Gentle Two-Stage", "Phaser", {"phaser", "subtle", "vocal"}, noDist() + phaser(0.35, 0.5, 1200, 0.2, "2", 0.35));
    P("Synced Phaser", "Phaser", {"phaser", "sync", "edm"}, noDist() + VV{V("Mod Type", "Phaser"), V("Mod Sync", 1), V("Mod Division", "2 bars"), V("Depth", 0.9), V("Phaser Center", 800), V("Mod Feedback", 0.6), V("Stages", "8"), V("Mod Mix", 0.55)});
    // ------------------------------------------------------------ Saturation
    P("Warm Tape", "Saturation", {"tape", "warm", "glue"}, noMod() + dist("Tape", 8, 0.05, 11000, 1, 0, 20) + order("Mod > Dist"));
    P("Tube Glow", "Saturation", {"tube", "warm", "vocal"}, noMod() + dist("Tube", 10, 0.2, 14000, 1, 0, 30));
    P("Gentle Saturation", "Saturation", {"soft", "subtle", "mix"}, noMod() + dist("Soft", 6, 0, 16000, 1, 0, 20));
    P("Drum Crunch", "Saturation", {"drums", "crunch", "punch"}, noMod() + dist("Hard", 14, 0, 9000, 0.7, 0, 60));
    P("Bass Warmth", "Saturation", {"bass", "harmonics", "warm"}, noMod() + dist("Tube", 14, 0.25, 6000, 0.8, 0, 40));
    P("Master Soft Clip", "Saturation", {"master", "soft clip", "loud"}, noMod() + dist("Soft", 5, 0, 18000, 1, 0, 20));
    P("Vintage Console", "Saturation", {"console", "analog", "bus"}, noMod() + dist("Tape", 11, 0.1, 13000, 0.85, 0, 25));
    P("Parallel Heat", "Saturation", {"parallel", "heat", "drums"}, noMod() + dist("Soft", 26, 0, 8500, 0.35, 0, 80));
    P("Even Harmonics", "Saturation", {"tube", "even", "keys"}, noMod() + dist("Tube", 12, 0.5, 12000, 0.8, 0, 30));
    // ------------------------------------------------------------ Distortion
    P("Overdrive", "Distortion", {"overdrive", "guitar", "classic"}, noMod() + dist("Soft", 22, 0.1, 6500, 1, -2, 140));
    P("Fuzz Face", "Distortion", {"fuzz", "guitar", "70s"}, noMod() + dist("Hard", 34, 0.35, 4800, 1, -4, 120));
    P("Heavy Distortion", "Distortion", {"distortion", "heavy", "rock"}, noMod() + dist("Hard", 40, 0, 5500, 1, -5, 160));
    P("Wavefolder Lead", "Distortion", {"fold", "synth", "lead"}, noMod() + dist("Fold", 20, 0, 12000, 1, -3, 40));
    P("Octave Rectifier", "Distortion", {"rectify", "octave", "synth"}, noMod() + dist("Rectify", 22, 0, 9000, 0.8, 0, 100));
    P("Sine Shaper", "Distortion", {"sine", "smooth", "synth"}, noMod() + dist("Sine", 24, 0, 14000, 1, -2, 40));
    P("Broken Amp", "Distortion", {"broken", "lofi", "dirty"}, noMod() + dist("Hard", 36, 0.6, 3800, 1, -4, 250));
    P("Vocal Grit", "Distortion", {"vocal", "grit", "alt"}, noMod() + dist("Tube", 20, 0.3, 7500, 0.5, -1, 200));
    P("Hyper Saturate", "Distortion", {"hyperpop", "extreme", "bright"}, noMod() + dist("Fold", 30, 0.1, 16000, 0.85, -4, 60));
    // ------------------------------------------------------------ Lo-fi & crush
    P("8-Bit Console", "Lo-fi", {"8-bit", "chip", "crush"}, noMod() + noDist() + crush(6, 6, 0, 1));
    P("Dusty Sampler", "Lo-fi", {"sampler", "12-bit", "hip-hop"}, noMod() + dist("Tape", 6, 0.1, 7500, 1, 0, 30) + crush(12, 3, 0.1, 0.8));
    P("Telephone Crush", "Lo-fi", {"phone", "crush", "lofi"}, noMod() + dist("Hard", 14, 0, 3500, 1, -2, 400) + crush(8, 5, 0.2, 0.7));
    P("Vinyl Warble", "Lo-fi", {"vinyl", "chorus", "lofi"}, mod("Chorus", 0.3, 0.7, 0.5, 14, 0, 0.5, 5500) + dist("Tape", 8, 0.1, 6000, 1, 0, 40) + crush(13, 2, 0.1, 0.5));
    P("Glitch Rate Reducer", "Lo-fi", {"glitch", "downsample", "hyperpop"}, noMod() + noDist() + crush(10, 18, 0.5, 1));
    P("Bit Rot", "Lo-fi", {"crush", "destroy", "experimental"}, noMod() + dist("Hard", 20, 0, 6000, 1, -3, 100) + crush(4, 10, 0.4, 1));
    P("Cassette Memory", "Lo-fi", {"cassette", "tape", "indie"}, mod("Chorus", 0.5, 0.4, 0.4, 12, 0, 0.5, 4500) + dist("Tape", 10, 0.15, 5500, 1, 0, 40));
    P("Gameboy", "Lo-fi", {"8-bit", "chip", "bright"}, noMod() + dist("Hard", 8, 0, 9000, 0.7, -2, 150) + crush(4, 3, 0, 1));
    // ------------------------------------------------------------ Combos
    P("Phased Fuzz", "Combos", {"fuzz", "phaser", "psychedelic"}, phaser(0.3, 0.8, 800, 0.5, "4", 0.5) + dist("Hard", 28, 0.2, 5000, 1, -4, 140) + order("Dist > Mod"));
    P("Chorused Overdrive", "Combos", {"overdrive", "chorus", "clean"}, mod("Chorus", 0.55, 0.45, 0.5, 10, 0, 0.9, 9000) + dist("Soft", 18, 0.1, 7000, 1, -2, 120) + order("Dist > Mod"));
    P("Wide Warm Keys", "Combos", {"keys", "warm", "wide"}, mod("Ensemble", 0.4, 0.5, 0.55, 12, 0, 1.0, 9000) + dist("Tube", 8, 0.2, 12000, 0.7, 0, 30));
    P("Dirty Flange", "Combos", {"flanger", "dirty", "industrial"}, mod("Flanger", 0.2, 0.8, 0.5, 4, 0.7, 0.8, 11000) + dist("Hard", 22, 0, 8000, 0.8, -3, 100) + order("Dist > Mod"));
    P("Alt Pop Vocal Shine", "Combos", {"vocal", "alt pop", "shine"}, mod("Chorus", 0.4, 0.2, 0.25, 8, 0, 0.7, 14000) + dist("Tube", 7, 0.15, 15000, 0.6, 0, 80) + V("Voices", "2"));
    P("Lo-fi Beat Smear", "Combos", {"lofi", "hip-hop", "beats"}, phaser(0.15, 0.5, 700, 0.3, "4", 0.4) + dist("Tape", 12, 0.1, 6500, 1, 0, 40) + crush(12, 2, 0.1, 0.6));
    P("Synth Brass Edge", "Combos", {"synth", "brass", "edge"}, mod("Chorus", 0.6, 0.35, 0.4, 9, 0, 0.9, 12000) + dist("Soft", 14, 0.1, 10000, 0.9, -1, 60));
    return L;
}
