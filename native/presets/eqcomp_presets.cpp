// Factory presets of Zedok EQ + Comp. Output gain is trimmed by presetgen so every preset keeps the level of the source.
#include "presetgen.h"
using VV = std::vector<V>;
static VV operator+(VV a, const VV& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
static VV operator+(VV a, const V& b) { a.push_back(b); return a; }
// band(n, type, freq, gain dB, Q [, slope for cuts])
static VV band(int n, const char* type, double f, double g = 0, double q = 1, const char* slope = "12 dB") {
    const std::string p = "B" + std::to_string(n) + " ";
    return {V((p + "On").c_str(), 1), V((p + "Type").c_str(), type), V((p + "Freq").c_str(), f), V((p + "Gain").c_str(), g), V((p + "Q").c_str(), q), V((p + "Slope").c_str(), slope)};
}
static VV off(int n) { return {V(("B" + std::to_string(n) + " On").c_str(), 0)}; }
static VV comp(double thr, double ratio, double knee, double att, double rel, double makeup = 0, double mix = 1, bool autoRel = false) {
    return {V("Comp On", 1), V("Threshold", thr), V("Ratio", ratio), V("Knee", knee), V("Attack", att), V("Release", rel), V("Makeup", makeup), V("Comp Mix", mix), V("Auto Release", autoRel ? 1 : 0)};
}
static VV det(const char* d, double look = 0) { return {V("Detector", d), V("Lookahead", look)}; }
static VV lim(double ceiling, double rel = 60) { return {V("Limiter On", 1), V("Ceiling", ceiling), V("Limiter Release", rel)}; }
static VV allOff() { return off(1) + off(2) + off(3) + off(4) + off(5) + off(6); }

PresetList eqcompPresets() {
    PresetList L; auto P = [&](const char* n, const char* c, std::initializer_list<const char*> t, VV v) { L.emplace_back(n, c, t, std::move(v)); };
    // ------------------------------------------------------------ Vocal
    P("Vocal Presence", "Vocal", {"vocal", "clarity"}, band(1, "Low Cut", 90, 0, 0.7, "12 dB") + band(2, "Bell", 220, -1.5, 1.2) + band(3, "Bell", 3200, 2.5, 1.0) + band(5, "High Shelf", 11000, 2.0, 0.7) + off(4) + off(6));
    P("Vocal Warmth", "Vocal", {"vocal", "warm"}, band(1, "Low Cut", 80, 0, 0.7) + band(2, "Low Shelf", 180, 2.5, 0.7) + band(3, "Bell", 450, -1.5, 1.5) + band(4, "Bell", 5000, 1.0, 1.0) + off(5) + off(6));
    P("De-Mud Vocal", "Vocal", {"vocal", "corrective"}, band(1, "Low Cut", 100, 0, 0.7) + band(2, "Bell", 300, -3.5, 1.4) + band(3, "Bell", 600, -1.5, 2.0) + band(4, "Bell", 3500, 1.5, 1.0) + off(5) + off(6));
    P("Airy Vocal", "Vocal", {"vocal", "air", "pop"}, band(1, "Low Cut", 100, 0, 0.7) + band(3, "Bell", 2800, 1.5, 0.9) + band(4, "High Shelf", 9500, 4.0, 0.7) + band(5, "Bell", 15000, 1.5, 0.6) + off(2) + off(6));
    P("Vocal Leveler", "Vocal", {"vocal", "compressor"}, band(1, "Low Cut", 85, 0, 0.7) + off(2) + off(3) + off(4) + off(5) + off(6) + comp(-22, 3.5, 8, 12, 110, 3, 1, true) + det("Mixed"));
    P("Radio Voice", "Vocal", {"vocal", "broadcast"}, band(1, "Low Cut", 120, 0, 0.7, "24 dB") + band(2, "Bell", 200, 3.0, 1.0) + band(3, "Bell", 3500, 3.0, 1.0) + band(5, "High Cut", 12000, 0, 0.7) + off(4) + off(6) + comp(-24, 4, 6, 8, 100, 4, 1, true));
    P("Podcast Voice", "Vocal", {"vocal", "speech", "podcast"}, band(1, "Low Cut", 80, 0, 0.7, "24 dB") + band(2, "Low Shelf", 150, 2.0, 0.7) + band(3, "Bell", 350, -2.0, 1.4) + band(4, "Bell", 4000, 2.5, 1.0) + band(5, "High Shelf", 10000, 1.5, 0.7) + off(6) + comp(-20, 3, 8, 10, 120, 3, 1, true) + lim(-1, 60));
    P("Backing Vocal Pocket", "Vocal", {"vocal", "backing", "mix"}, band(1, "Low Cut", 150, 0, 0.7) + band(2, "Bell", 400, -2.0, 1.3) + band(3, "Bell", 2500, -1.5, 1.0) + band(4, "High Shelf", 9000, 2.0, 0.7) + off(5) + off(6) + comp(-24, 3, 8, 15, 140, 2, 1));
    P("De-Esser Region", "Vocal", {"vocal", "sibilance", "corrective"}, band(1, "Bell", 6800, -4.0, 3.0) + band(2, "Bell", 8800, -2.5, 3.0) + off(3) + off(4) + off(5) + off(6));
    // ------------------------------------------------------------ Drums
    P("Kick Punch", "Drums", {"kick", "punch"}, band(1, "Low Cut", 35, 0, 0.7) + band(2, "Bell", 62, 3.5, 1.2) + band(3, "Bell", 350, -4.0, 1.5) + band(4, "Bell", 4200, 3.0, 1.0) + off(5) + off(6) + comp(-14, 4, 4, 22, 90, 2) + det("Peak"));
    P("Kick Sub Boost", "Drums", {"kick", "sub", "808"}, band(1, "Low Cut", 28, 0, 0.7, "24 dB") + band(2, "Bell", 52, 5.0, 1.0) + band(3, "Bell", 220, -3.0, 1.2) + band(4, "Bell", 3000, 1.5, 1.0) + off(5) + off(6));
    P("Snare Crack", "Drums", {"snare", "bright"}, band(1, "Low Cut", 90, 0, 0.7) + band(2, "Bell", 200, 3.0, 1.2) + band(3, "Bell", 600, -3.0, 2.0) + band(4, "Bell", 5000, 4.0, 1.0) + band(5, "High Shelf", 11000, 2.0, 0.7) + off(6) + comp(-16, 4, 4, 5, 90, 2));
    P("Drum Bus Glue", "Drums", {"drums", "bus", "glue"}, band(1, "Low Cut", 30, 0, 0.7) + band(2, "Low Shelf", 90, 1.5, 0.7) + band(5, "High Shelf", 10000, 1.5, 0.7) + off(3) + off(4) + off(6) + comp(-14, 2.2, 10, 30, 0, 2, 1, true) + det("Mixed"));
    P("Overhead Air", "Drums", {"overheads", "cymbals", "air"}, band(1, "Low Cut", 250, 0, 0.7, "24 dB") + band(2, "Bell", 500, -2.0, 1.0) + band(4, "Bell", 7500, 2.0, 0.8) + band(5, "High Shelf", 12000, 3.0, 0.7) + off(3) + off(6));
    P("Tight Toms", "Drums", {"toms", "tight"}, band(1, "Low Cut", 60, 0, 0.7) + band(2, "Bell", 110, 2.5, 1.2) + band(3, "Bell", 400, -4.0, 1.5) + band(4, "Bell", 4500, 3.0, 1.0) + off(5) + off(6) + comp(-18, 3, 6, 8, 100, 1));
    P("Parallel Drum Smash", "Drums", {"drums", "parallel", "smash"}, band(1, "Low Cut", 40, 0, 0.7) + band(5, "High Shelf", 8000, 3.0, 0.7) + off(2) + off(3) + off(4) + off(6) + comp(-34, 10, 2, 2, 120, 8, 0.4) + det("Peak"));
    P("Hi-Hat Tamer", "Drums", {"hats", "corrective"}, band(1, "Low Cut", 300, 0, 0.7, "24 dB") + band(2, "Bell", 5500, -2.5, 2.0) + band(3, "Bell", 9500, -2.0, 2.0) + off(4) + off(5) + off(6));
    // ------------------------------------------------------------ Bass
    P("Bass Clarity", "Bass", {"bass", "clarity"}, band(1, "Low Cut", 30, 0, 0.7) + band(2, "Bell", 250, -3.0, 1.3) + band(3, "Bell", 800, 2.5, 1.0) + band(4, "Bell", 2400, 2.0, 1.0) + off(5) + off(6) + comp(-18, 3.5, 6, 20, 140, 2));
    P("Sub Bass Tighten", "Bass", {"bass", "sub", "tight"}, band(1, "Low Cut", 28, 0, 0.7, "24 dB") + band(2, "Bell", 55, 2.0, 1.2) + band(3, "Bell", 140, -2.5, 1.5) + band(5, "High Cut", 4500, 0, 0.7) + off(4) + off(6) + comp(-16, 4, 4, 25, 100, 2));
    P("Bass Amp Feel", "Bass", {"bass", "amp", "character"}, band(1, "Low Cut", 35, 0, 0.7) + band(2, "Low Shelf", 100, 2.5, 0.7) + band(3, "Bell", 600, -2.0, 1.5) + band(4, "Bell", 1800, 3.0, 1.0) + band(5, "High Cut", 7000, 0, 0.7) + off(6) + comp(-20, 4, 6, 15, 150, 3));
    P("Bass Glue", "Bass", {"bass", "compressor", "leveler"}, allOff() + comp(-20, 4, 8, 18, 160, 3, 1, true) + det("RMS"));
    // ------------------------------------------------------------ Keys & guitars
    P("Acoustic Guitar Sparkle", "Instruments", {"guitar", "acoustic", "bright"}, band(1, "Low Cut", 90, 0, 0.7) + band(2, "Bell", 250, -2.0, 1.2) + band(3, "Bell", 3000, 1.5, 1.0) + band(5, "High Shelf", 10000, 3.0, 0.7) + off(4) + off(6));
    P("Piano Clarity", "Instruments", {"piano", "keys", "clarity"}, band(1, "Low Cut", 50, 0, 0.7) + band(2, "Bell", 300, -2.5, 1.3) + band(3, "Bell", 2800, 1.5, 1.0) + band(5, "High Shelf", 9000, 2.0, 0.7) + off(4) + off(6));
    P("Electric Guitar Body", "Instruments", {"guitar", "electric", "body"}, band(1, "Low Cut", 80, 0, 0.7, "24 dB") + band(2, "Bell", 180, 2.0, 1.2) + band(3, "Bell", 800, -1.5, 1.5) + band(4, "Bell", 3500, 2.5, 1.0) + band(5, "High Cut", 9000, 0, 0.7) + off(6));
    P("Synth Pad Space", "Instruments", {"synth", "pad", "space"}, band(1, "Low Cut", 90, 0, 0.7) + band(2, "Bell", 300, -3.0, 1.0) + band(3, "Bell", 1800, -1.5, 0.8) + band(5, "High Shelf", 10000, 2.5, 0.7) + off(4) + off(6));
    P("Synth Lead Cut-Through", "Instruments", {"synth", "lead", "presence"}, band(1, "Low Cut", 140, 0, 0.7) + band(2, "Bell", 400, -2.0, 1.2) + band(3, "Bell", 2500, 3.0, 1.0) + band(4, "Bell", 5500, 1.5, 1.0) + off(5) + off(6));
    P("Strings Warmth", "Instruments", {"strings", "warm"}, band(1, "Low Cut", 80, 0, 0.7) + band(2, "Low Shelf", 200, 1.5, 0.7) + band(3, "Bell", 2200, -1.5, 1.0) + band(5, "High Cut", 14000, 0, 0.7) + off(4) + off(6));
    // ------------------------------------------------------------ Mix bus & master
    P("Master Gentle Glue", "Master", {"master", "glue", "bus"}, band(1, "Low Cut", 25, 0, 0.7) + off(2) + off(3) + off(4) + off(5) + off(6) + comp(-14, 1.6, 12, 30, 0, 1, 1, true) + det("RMS"));
    P("Master Brighten", "Master", {"master", "bright"}, band(1, "Low Cut", 25, 0, 0.7) + band(2, "Bell", 280, -1.0, 0.8) + band(5, "High Shelf", 9500, 2.0, 0.6) + off(3) + off(4) + off(6));
    P("Master Warm", "Master", {"master", "warm"}, band(1, "Low Cut", 25, 0, 0.7) + band(2, "Low Shelf", 120, 1.5, 0.6) + band(5, "High Shelf", 11000, -1.0, 0.6) + off(3) + off(4) + off(6));
    P("Mix Bus Smile", "Master", {"master", "smile", "loudness"}, band(1, "Low Cut", 25, 0, 0.7) + band(2, "Low Shelf", 90, 2.0, 0.7) + band(3, "Bell", 900, -1.5, 0.6) + band(5, "High Shelf", 10000, 2.5, 0.7) + off(4) + off(6));
    P("Loudness Limiter", "Master", {"master", "limiter", "loud"}, band(1, "Low Cut", 25, 0, 0.7) + off(2) + off(3) + off(4) + off(5) + off(6) + comp(-12, 2, 10, 25, 0, 2, 1, true) + lim(-1.0, 50));
    P("Streaming Ready", "Master", {"master", "limiter", "streaming"}, band(1, "Low Cut", 22, 0, 0.7) + off(2) + off(3) + off(4) + off(5) + off(6) + lim(-1.0, 80));
    P("Mid/Side Brightener", "Master", {"master", "mid/side", "wide"}, VV{V("EQ Mode", "Mid/Side")} + band(1, "Low Cut", 25, 0, 0.7) + band(5, "High Shelf", 8500, 2.0, 0.7) + off(2) + off(3) + off(4) + off(6));
    // ------------------------------------------------------------ Creative
    P("Telephone", "Creative", {"lofi", "telephone", "filter"}, band(1, "Low Cut", 500, 0, 0.9, "24 dB") + band(2, "High Cut", 3200, 0, 0.9, "24 dB") + band(3, "Bell", 1500, 4.0, 1.2) + off(4) + off(5) + off(6));
    P("Lo-fi Radio", "Creative", {"lofi", "radio", "vintage"}, band(1, "Low Cut", 250, 0, 0.7, "24 dB") + band(2, "High Cut", 5500, 0, 0.8, "24 dB") + band(3, "Bell", 1200, 3.0, 1.0) + off(4) + off(5) + off(6) + comp(-22, 4, 4, 5, 80, 4));
    P("Underwater", "Creative", {"filter", "dark", "muffled"}, band(1, "High Cut", 650, 0, 1.2, "36 dB") + band(2, "Bell", 480, 3.0, 1.5) + off(3) + off(4) + off(5) + off(6));
    P("Megaphone", "Creative", {"lofi", "megaphone", "distorted"}, band(1, "Low Cut", 600, 0, 1.0, "24 dB") + band(2, "High Cut", 4500, 0, 1.0, "24 dB") + band(3, "Bell", 2000, 6.0, 1.0) + off(4) + off(5) + off(6) + comp(-26, 8, 2, 2, 60, 6));
    P("Dark Tape", "Creative", {"tape", "dark", "vintage"}, band(1, "Low Cut", 40, 0, 0.7) + band(2, "Low Shelf", 100, 2.0, 0.7) + band(3, "High Cut", 8500, 0, 0.7, "24 dB") + band(4, "Bell", 3000, -1.5, 0.8) + off(5) + off(6));
    P("Air Lift", "Creative", {"air", "bright", "polish"}, band(1, "Low Cut", 40, 0, 0.7) + band(4, "Bell", 7000, 1.5, 0.7) + band(5, "High Shelf", 12500, 4.5, 0.6) + off(2) + off(3) + off(6));
    P("Hollow Band-Pass", "Creative", {"filter", "hollow", "tone"}, band(1, "Band Pass", 1400, 0, 1.4) + off(2) + off(3) + off(4) + off(5) + off(6));
    // ------------------------------------------------------------ Corrective
    P("Rumble Cut", "Corrective", {"rumble", "low cut"}, band(1, "Low Cut", 42, 0, 0.7, "24 dB") + off(2) + off(3) + off(4) + off(5) + off(6));
    P("Low Cut 80 Hz", "Corrective", {"low cut", "clean"}, band(1, "Low Cut", 80, 0, 0.7, "24 dB") + off(2) + off(3) + off(4) + off(5) + off(6));
    P("High Cut 12 kHz", "Corrective", {"high cut", "smooth"}, band(6, "High Cut", 12000, 0, 0.7, "24 dB") + off(1) + off(2) + off(3) + off(4) + off(5));
    P("Boxiness Reduce", "Corrective", {"boxy", "mud"}, band(2, "Bell", 380, -3.5, 1.6) + band(3, "Bell", 700, -2.0, 2.0) + off(1) + off(4) + off(5) + off(6));
    P("Harshness Tamer", "Corrective", {"harsh", "ears", "smooth"}, band(3, "Bell", 2800, -2.5, 1.8) + band(4, "Bell", 4600, -2.0, 2.2) + band(5, "Bell", 7200, -1.5, 2.0) + off(1) + off(2) + off(6));
    P("Resonance Notch", "Corrective", {"resonance", "notch"}, band(3, "Notch", 1000, 0, 8.0) + off(1) + off(2) + off(4) + off(5) + off(6));
    P("Tilt Brighter", "Corrective", {"tilt", "balance"}, band(3, "Tilt", 1000, 3.0, 0.5) + off(1) + off(2) + off(4) + off(5) + off(6));
    P("Tilt Darker", "Corrective", {"tilt", "balance"}, band(3, "Tilt", 1000, -3.0, 0.5) + off(1) + off(2) + off(4) + off(5) + off(6));
    // ------------------------------------------------------------ Dynamics
    P("Gentle Leveler", "Dynamics", {"compressor", "gentle"}, allOff() + comp(-20, 2, 12, 25, 200, 2, 1, true) + det("RMS"));
    P("Slow Opto", "Dynamics", {"compressor", "opto", "smooth"}, allOff() + comp(-22, 3, 14, 30, 400, 3) + det("RMS"));
    P("Fast Peak Catcher", "Dynamics", {"compressor", "fast", "transient"}, allOff() + comp(-16, 6, 3, 0.5, 60, 2) + det("Peak", 2));
    P("Parallel Squash", "Dynamics", {"compressor", "parallel", "new york"}, allOff() + comp(-32, 12, 2, 3, 150, 9, 0.35) + det("Peak"));
    P("Punchy Bus", "Dynamics", {"compressor", "punch", "bus"}, allOff() + comp(-16, 3, 6, 35, 120, 2) + det("Mixed"));
    P("Sustain Boost", "Dynamics", {"compressor", "sustain", "guitar"}, allOff() + comp(-30, 5, 10, 8, 500, 8) + det("RMS"));
    P("Brickwall -1 dB", "Dynamics", {"limiter", "brickwall"}, allOff() + lim(-1.0, 60));
    P("Transparent Limiter -2 dB", "Dynamics", {"limiter", "transparent", "safe"}, allOff() + lim(-2.0, 120));
    return L;
}
