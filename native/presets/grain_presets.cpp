// Factory presets of Zedok Grain. They use the built-in textures; the two "Sample" starters stay silent until a sample is loaded.
#include "presetgen.h"
using VV = std::vector<V>;
static VV operator+(VV a, const VV& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
static VV operator+(VV a, const V& b) { a.push_back(b); return a; }
// cloud(source, position, size ms, density /s, pitch st, position spread, pitch jitter st, pan spread)
static VV cloud(const char* src, double pos, double size, double dens, double pitch = 0, double pspread = 0.1, double pjit = 0, double pan = 0.6) {
    return {V("Source", src), V("Position", pos), V("Size", size), V("Density", dens), V("Pitch", pitch), V("Position Spread", pspread), V("Pitch Jitter", pjit), V("Pan Spread", pan)};
}
static VV shape(const char* s, double skew = 0.5, const char* dir = "Forward") { return {V("Shape", s), V("Skew", skew), V("Direction", dir)}; }
static VV jit(double timing, double level) { return {V("Timing Jitter", timing), V("Level Jitter", level)}; }
static VV amp(double a, double d, double s, double r) { return {V("Amp Attack", a), V("Amp Decay", d), V("Amp Sustain", s), V("Amp Release", r)}; }
static VV e2(double a, double d, double s, double r) { return {V("Env2 Attack", a), V("Env2 Decay", d), V("Env2 Sustain", s), V("Env2 Release", r)}; }
static VV filt(const char* type, double cut, double res = 0.1, double env = 0, double key = 0) { return {V("Filter Type", type), V("Cutoff", cut), V("Resonance", res), V("Filter Env", env), V("Key Track", key)}; }
static VV scan(double speed, bool freeze = false) { return {V("Scan", speed), V("Freeze", freeze ? 1 : 0)}; }
static VV mono(double glide = 0, bool legato = false) { return {V("Play Mode", legato ? "Legato" : "Mono"), V("Glide", glide)}; }
static VV lfo(int n, const char* shp, double rate, double fade = 0) { std::string p = "LFO" + std::to_string(n) + " "; return {V((p + "Shape").c_str(), shp), V((p + "Rate").c_str(), rate), V((p + "Fade In").c_str(), fade)}; }
static VV mod(int n, const char* src, const char* dst, double amt) { std::string p = "Mod " + std::to_string(n) + " "; return {V((p + "Source").c_str(), src), V((p + "Target").c_str(), dst), V((p + "Amount").c_str(), amt)}; }
static VV fx(double delay, double reverb, const char* dtime = "1/8 .", double fb = 0.4, double size = 0.6, double decay = 3) { return {V("Delay Mix", delay), V("Delay Time", dtime), V("Delay Feedback", fb), V("Reverb Mix", reverb), V("Reverb Size", size), V("Reverb Decay", decay)}; }
static VV pt(double track) { return {V("Pitch Track", track)}; }

PresetList grainPresets() {
    PresetList L; auto P = [&](const char* n, const char* c, std::initializer_list<const char*> t, VV v) { L.emplace_back(n, c, t, std::move(v)); };
    // ------------------------------------------------------------ Pads
    P("Saw Cloud Pad", "Pads", {"pad", "analog", "wide"}, cloud("Saw Cloud", 0.3, 140, 40, 0, 0.3, 0.1, 0.9) + shape("Hann") + jit(0.4, 0.2) + amp(900, 700, 0.9, 1400) + filt("LP 12", 3800, 0.1) + fx(0, 0.3, "1/8 .", 0.4, 0.7, 4));
    P("Choir Mist", "Pads", {"pad", "choir", "airy"}, cloud("Choir", 0.4, 220, 30, 0, 0.45, 0.2, 1.0) + shape("Gauss") + jit(0.5, 0.25) + scan(0.04) + amp(1100, 900, 0.9, 1800) + fx(0, 0.4, "1/4", 0.4, 0.85, 6));
    P("Glass Rain Pad", "Pads", {"pad", "glass", "bright"}, cloud("Glass", 0.5, 160, 45, 12, 0.5, 0.3, 1.0) + shape("Gauss") + jit(0.6, 0.35) + amp(700, 800, 0.9, 1800) + filt("LP 12", 9000, 0.05) + fx(0.2, 0.4, "1/8 .", 0.45, 0.8, 5));
    P("Organ Haze", "Pads", {"pad", "organ", "warm"}, cloud("Organ Drone", 0.2, 180, 35, 0, 0.4, 0.1, 0.8) + shape("Hann") + jit(0.3, 0.1) + scan(0.03) + amp(800, 600, 0.95, 1400) + filt("LP 12", 2600, 0.08) + fx(0, 0.35, "1/4", 0.3, 0.7, 4));
    P("Noise Bed", "Pads", {"pad", "noise", "texture"}, cloud("Noise Texture", 0.5, 260, 24, 0, 0.6, 0, 1.0) + shape("Gauss") + jit(0.6, 0.3) + amp(1500, 900, 0.95, 2200) + filt("LP 12", 4500, 0.15) + fx(0, 0.5, "1/4", 0.4, 0.9, 7));
    P("Metal Shimmer", "Pads", {"pad", "metal", "shimmer"}, cloud("Metal Pulses", 0.4, 200, 28, 7, 0.5, 0.4, 1.0) + shape("Hann") + jit(0.5, 0.3) + amp(900, 700, 0.9, 1600) + filt("LP 12", 7500, 0.1) + fx(0.2, 0.45, "1/4 .", 0.5, 0.85, 6));
    P("Slow Scan Saw", "Pads", {"pad", "evolving", "scan"}, cloud("Saw Cloud", 0.0, 260, 28, 0, 0.12, 0.1, 0.9) + shape("Hann") + jit(0.3, 0.15) + scan(0.12) + amp(1400, 800, 0.9, 2000) + filt("LP 24", 2800, 0.15) + fx(0, 0.4, "1/8", 0.3, 0.8, 5));
    P("Frozen Choir", "Pads", {"pad", "frozen", "choir"}, cloud("Choir", 0.55, 300, 22, 0, 0.05, 0.05, 0.9) + shape("Gauss") + jit(0.25, 0.1) + scan(0, true) + amp(1800, 900, 0.95, 2800) + fx(0, 0.5, "1/4", 0.4, 0.9, 8));
    P("Reverse Cloud", "Pads", {"pad", "reverse", "swell"}, cloud("Saw Cloud", 0.5, 400, 20, 0, 0.3, 0, 1.0) + shape("Reverse", 0.5, "Reverse") + jit(0.4, 0.2) + amp(1200, 800, 0.9, 2000) + filt("LP 12", 3500, 0.1) + fx(0, 0.45, "1/4", 0.4, 0.85, 6));
    P("Swell Cloud", "Pads", {"pad", "swell", "cinematic"}, cloud("Organ Drone", 0.3, 240, 30, -12, 0.4, 0.1, 1.0) + shape("Hann") + jit(0.4, 0.2) + amp(3200, 1200, 0.9, 2800) + filt("LP 24", 800, 0.2, 0.7) + e2(3200, 2500, 0.7, 2000) + fx(0, 0.5, "1/4", 0.4, 0.9, 8));
    P("Octave Choir", "Pads", {"pad", "choir", "octave"}, cloud("Choir", 0.45, 200, 34, 0, 0.35, 12, 1.0) + shape("Gauss") + jit(0.5, 0.3) + amp(900, 800, 0.9, 1700) + fx(0.15, 0.45, "1/8 .", 0.4, 0.85, 6));
    // ------------------------------------------------------------ Textures & FX
    P("Dust", "Textures", {"dust", "tiny", "crackle"}, cloud("Noise Texture", 0.5, 12, 18, 0, 0.9, 2, 1.0) + shape("Hann") + jit(1.0, 0.7) + amp(60, 400, 0.9, 900) + filt("HP 12", 1500, 0.1) + fx(0, 0.25, "1/8", 0.3, 0.5, 2));
    P("Rain On Glass", "Textures", {"rain", "glass", "ambient"}, cloud("Glass", 0.5, 18, 55, 12, 0.9, 5, 1.0) + shape("Percussive") + jit(1.0, 0.6) + amp(120, 500, 0.9, 1200) + filt("LP 12", 11000, 0.05) + fx(0.15, 0.4, "1/8 .", 0.4, 0.8, 4));
    P("Insects", "Textures", {"insects", "buzz", "dark"}, cloud("Saw Cloud", 0.5, 9, 140, 12, 0.8, 7, 1.0) + shape("Triangle") + jit(0.8, 0.5) + amp(300, 500, 0.9, 900) + filt("BP 12", 3200, 0.3) + fx(0, 0.2, "1/8", 0.3, 0.5, 2));
    P("Wind Chimes", "Textures", {"chimes", "metal", "random"}, cloud("Glass", 0.4, 400, 3.5, 12, 0.7, 6, 1.0) + shape("Percussive", 0.3) + jit(1.0, 0.5) + amp(10, 2200, 0.0, 1800) + fx(0.15, 0.5, "1/8 .", 0.45, 0.85, 6));
    P("Granular Drone", "Textures", {"drone", "dark", "slow"}, cloud("Organ Drone", 0.1, 480, 14, -12, 0.25, 0.05, 1.0) + shape("Hann") + jit(0.4, 0.2) + scan(0.02) + amp(2500, 1000, 0.95, 3500) + filt("LP 24", 1400, 0.2) + fx(0, 0.5, "1/4", 0.4, 0.9, 9));
    P("Radio Static", "Textures", {"static", "noise", "lofi"}, cloud("Noise Texture", 0.7, 25, 70, 0, 0.9, 0, 1.0) + shape("Triangle") + jit(1.0, 0.8) + amp(120, 500, 0.9, 700) + filt("BP 12", 2400, 0.2) + fx(0, 0.15, "1/8", 0.3, 0.4, 1.5));
    P("Metal Insects", "Textures", {"metal", "insects", "experimental"}, cloud("Metal Pulses", 0.3, 15, 90, 7, 0.9, 12, 1.0) + shape("Percussive") + jit(0.9, 0.6) + amp(80, 500, 0.8, 800) + filt("HP 12", 800, 0.15) + fx(0.1, 0.3, "1/16", 0.4, 0.6, 3));
    P("Underwater Choir", "Textures", {"underwater", "choir", "dark"}, cloud("Choir", 0.5, 330, 16, -5, 0.5, 0.4, 1.0) + shape("Gauss") + jit(0.7, 0.3) + amp(1000, 800, 0.9, 2000) + filt("LP 24", 1100, 0.2) + lfo(1, "Sine", 0.2) + mod(1, "LFO 1", "Cutoff", 0.3) + fx(0, 0.5, "1/4", 0.4, 0.9, 7));
    P("Shattered Glass", "Textures", {"glass", "shatter", "burst"}, cloud("Glass", 0.3, 22, 60, 12, 0.9, 12, 1.0) + shape("Percussive", 0.15) + jit(0.9, 0.5) + amp(5, 900, 0.1, 900) + fx(0.2, 0.4, "1/16", 0.45, 0.7, 4));
    // ------------------------------------------------------------ Rhythmic
    P("Stutter Choir", "Rhythmic", {"stutter", "choir", "rhythmic"}, cloud("Choir", 0.4, 70, 8, 0, 0.05, 0, 0.5) + shape("Percussive", 0.2) + jit(0.0, 0.0) + amp(8, 500, 0.9, 400) + fx(0.2, 0.3, "1/8", 0.4, 0.6, 3));
    P("Pulse Cloud", "Rhythmic", {"pulse", "saw", "rhythmic"}, cloud("Saw Cloud", 0.35, 90, 6, 0, 0.1, 0, 0.7) + shape("Percussive", 0.15) + jit(0.0, 0.0) + amp(5, 600, 0.9, 400) + filt("LP 12", 3500, 0.15) + fx(0.25, 0.25, "1/8 .", 0.45, 0.5, 2));
    P("Tick Garden", "Rhythmic", {"ticks", "percussive", "glitch"}, cloud("Metal Pulses", 0.2, 30, 9, 12, 0.6, 3, 1.0) + shape("Percussive", 0.1) + jit(0.1, 0.4) + amp(2, 500, 0.9, 300) + filt("HP 12", 600, 0.1) + fx(0.2, 0.25, "1/16", 0.4, 0.5, 2));
    P("Glass Arp Grains", "Rhythmic", {"arp", "glass", "bright"}, cloud("Glass", 0.5, 120, 12, 12, 0.3, 0, 0.8) + shape("Percussive", 0.2) + jit(0.1, 0.1) + amp(5, 800, 0.2, 500) + fx(0.3, 0.35, "1/8 .", 0.5, 0.7, 4));
    P("Trap Texture Pulse", "Rhythmic", {"trap", "pulse", "dark"}, cloud("Organ Drone", 0.3, 100, 5, -12, 0.1, 0, 0.6) + shape("Percussive", 0.15) + jit(0, 0) + amp(5, 700, 0.8, 300) + filt("LP 24", 1500, 0.3) + fx(0.2, 0.25, "1/8", 0.4, 0.6, 3));
    // ------------------------------------------------------------ Keys & leads
    P("Grain Keys", "Keys", {"keys", "soft", "glass"}, cloud("Glass", 0.45, 55, 60, 0, 0.15, 0, 0.5) + shape("Hann") + jit(0.2, 0.1) + amp(10, 1200, 0.3, 700) + filt("LP 12", 6500, 0.1) + fx(0.1, 0.35, "1/8 .", 0.4, 0.7, 4));
    P("Choir Lead", "Leads", {"lead", "choir", "mono"}, cloud("Choir", 0.4, 90, 70, 0, 0.1, 0, 0.4) + shape("Hann") + jit(0.2, 0.1) + mono(80, true) + amp(40, 400, 0.9, 400) + filt("LP 12", 5500, 0.1) + fx(0.15, 0.35, "1/8 .", 0.4, 0.7, 3));
    P("Saw Grain Lead", "Leads", {"lead", "analog", "mono"}, cloud("Saw Cloud", 0.5, 45, 110, 0, 0.12, 0.05, 0.6) + shape("Hann") + jit(0.2, 0.1) + mono(60, true) + amp(15, 350, 0.9, 300) + filt("LP 24", 4200, 0.2, 0.2) + e2(5, 400, 0.5, 300) + fx(0.2, 0.3, "1/8 .", 0.4, 0.6, 3));
    P("Metal Bell Lead", "Leads", {"lead", "bell", "metal"}, cloud("Metal Pulses", 0.4, 120, 40, 0, 0.1, 0, 0.5) + shape("Gauss") + jit(0.2, 0.1) + mono(40, true) + amp(10, 900, 0.5, 700) + filt("LP 12", 8000, 0.1) + fx(0.2, 0.4, "1/4", 0.45, 0.7, 4));
    P("Grain Bass", "Bass", {"bass", "saw", "gritty"}, cloud("Saw Cloud", 0.5, 40, 90, -12, 0.08, 0, 0.3) + shape("Hann") + jit(0.2, 0.1) + mono(30, true) + amp(5, 300, 0.9, 150) + filt("LP 24", 1100, 0.25, 0.4) + e2(2, 300, 0.3, 200));
    P("Organ Sub Grain", "Bass", {"bass", "organ", "sub"}, cloud("Organ Drone", 0.4, 60, 70, -12, 0.05, 0, 0.2) + shape("Hann") + jit(0.1, 0.05) + mono(20, true) + amp(10, 300, 0.95, 200) + filt("LP 12", 700, 0.1));
    // ------------------------------------------------------------ Ambient
    P("Slow Bloom", "Ambient", {"ambient", "bloom", "evolving"}, cloud("Glass", 0.5, 380, 18, 0, 0.5, 0.3, 1.0) + shape("Gauss") + jit(0.5, 0.3) + scan(0.05) + lfo(1, "Sine", 0.07) + mod(1, "LFO 1", "Position", 0.3) + amp(2200, 1000, 0.95, 3000) + fx(0.2, 0.55, "1/4", 0.45, 0.9, 9));
    P("Tidal Pool", "Ambient", {"ambient", "water", "gentle"}, cloud("Choir", 0.3, 300, 20, 0, 0.5, 0.3, 1.0) + shape("Hann") + jit(0.6, 0.3) + lfo(1, "Sine", 0.12) + lfo(2, "Smooth Random", 0.3) + mod(1, "LFO 1", "Position", 0.4) + mod(2, "LFO 2", "Size", 0.25) + amp(1800, 900, 0.95, 2600) + fx(0, 0.55, "1/4", 0.4, 0.9, 8));
    P("Aurora", "Ambient", {"ambient", "aurora", "shimmer"}, cloud("Glass", 0.6, 280, 26, 12, 0.6, 0.5, 1.0) + shape("Gauss") + jit(0.6, 0.35) + lfo(1, "Smooth Random", 0.2) + mod(1, "LFO 1", "Pitch Jitter", 0.4) + amp(2500, 900, 0.95, 3200) + filt("LP 12", 10000, 0.05) + fx(0.25, 0.55, "1/4 .", 0.5, 0.95, 10));
    P("Dark Matter", "Ambient", {"ambient", "dark", "huge"}, cloud("Organ Drone", 0.15, 500, 12, -12, 0.2, 0.1, 1.0) + shape("Hann") + jit(0.4, 0.2) + scan(0.015) + amp(3500, 1200, 0.95, 4500) + filt("LP 24", 900, 0.3) + fx(0, 0.6, "1/4", 0.4, 1.0, 12));
    P("Floating Voices", "Ambient", {"ambient", "voices", "soft"}, cloud("Choir", 0.35, 260, 24, 7, 0.55, 0.35, 1.0) + shape("Gauss") + jit(0.6, 0.3) + lfo(1, "Sine", 0.09) + mod(1, "LFO 1", "Pitch", 0.02) + amp(1700, 900, 0.95, 2800) + fx(0.15, 0.55, "1/4", 0.4, 0.9, 8));
    P("Spectral Fog", "Ambient", {"ambient", "fog", "noise"}, cloud("Noise Texture", 0.5, 400, 18, 0, 0.7, 0, 1.0) + shape("Gauss") + jit(0.7, 0.3) + lfo(1, "Smooth Random", 0.15) + mod(1, "LFO 1", "Cutoff", 0.4) + amp(2000, 900, 0.95, 3000) + filt("LP 12", 3000, 0.25) + fx(0, 0.55, "1/4", 0.4, 0.9, 9));
    // ------------------------------------------------------------ Experimental
    P("Tape Stop Garden", "Experimental", {"tape", "pitch", "wobble"}, cloud("Organ Drone", 0.4, 100, 40, -7, 0.3, 4, 1.0) + shape("Triangle") + jit(0.7, 0.4) + lfo(1, "Sine", 0.25) + mod(1, "LFO 1", "Pitch", 0.35) + amp(300, 700, 0.9, 1200) + fx(0.2, 0.4, "1/8 .", 0.5, 0.7, 4));
    P("Granular Choir Gate", "Experimental", {"gate", "choir", "rhythmic"}, cloud("Choir", 0.5, 120, 14, 0, 0.3, 0, 0.8) + shape("Trapezoid") + jit(0.0, 0.0) + lfo(1, "Square", 5) + mod(1, "LFO 1", "Volume", 0.5) + amp(100, 600, 0.9, 700) + fx(0.2, 0.35, "1/8", 0.4, 0.6, 3));
    P("Pitch Scatter", "Experimental", {"scatter", "random", "glitch"}, cloud("Saw Cloud", 0.5, 60, 50, 0, 0.8, 12, 1.0) + shape("Percussive", 0.2) + jit(0.8, 0.5) + amp(10, 600, 0.6, 500) + filt("LP 12", 6000, 0.15) + fx(0.25, 0.4, "1/16", 0.45, 0.6, 3));
    P("Reverse Metal", "Experimental", {"reverse", "metal", "swell"}, cloud("Metal Pulses", 0.5, 220, 16, 0, 0.4, 2, 1.0) + shape("Reverse", 0.5, "Reverse") + jit(0.5, 0.3) + amp(600, 700, 0.9, 1400) + fx(0.2, 0.45, "1/4", 0.5, 0.8, 5));
    P("Random Direction Cloud", "Experimental", {"random", "cloud", "wide"}, cloud("Choir", 0.45, 150, 30, 0, 0.5, 0.5, 1.0) + shape("Hann", 0.5, "Random") + jit(0.7, 0.3) + amp(800, 700, 0.9, 1500) + fx(0.15, 0.45, "1/8 .", 0.4, 0.8, 5));
    P("Hyperpop Grain Sparkle", "Experimental", {"hyperpop", "sparkle", "bright"}, cloud("Glass", 0.4, 30, 80, 12, 0.7, 12, 1.0) + shape("Percussive", 0.1) + jit(0.8, 0.5) + amp(5, 600, 0.5, 700) + filt("HP 12", 400, 0.1) + fx(0.3, 0.4, "1/16", 0.5, 0.7, 4));
    // ------------------------------------------------------------ Sample starters (silent until a sample is loaded)
    P("Sample Cloud", "Sample", {"sample", "starter", "cloud"}, cloud("Sample", 0.3, 120, 25, 0, 0.15, 0.1, 0.8) + shape("Hann") + jit(0.4, 0.2) + pt(0) + amp(400, 600, 0.9, 900) + fx(0, 0.35, "1/8 .", 0.4, 0.7, 4));
    P("Sample Freeze Scrub", "Sample", {"sample", "starter", "freeze"}, cloud("Sample", 0.3, 180, 30, 0, 0.04, 0, 0.7) + shape("Gauss") + jit(0.3, 0.1) + scan(0, true) + pt(0) + amp(300, 700, 0.95, 1200) + fx(0, 0.4, "1/4", 0.4, 0.8, 5));
    P("Sample Slow Scan", "Sample", {"sample", "starter", "scan"}, cloud("Sample", 0.0, 200, 28, 0, 0.1, 0, 0.8) + shape("Hann") + jit(0.3, 0.1) + scan(0.1) + pt(0) + amp(500, 700, 0.9, 1200) + fx(0, 0.4, "1/4", 0.4, 0.8, 5));
    return L;
}
