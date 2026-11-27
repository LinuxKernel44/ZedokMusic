// Factory presets of Zedok Piano: the same sampled grand played in different rooms, with different touch, tone, tuning and pedal behaviour.
#include "presetgen.h"
using VV = std::vector<V>;
static VV operator+(VV a, const VV& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
static VV tone(double low, double pres, double high, double lid = 1.0) { return {V("Low Tone", low), V("Presence", pres), V("High Tone", high), V("Lid", lid)}; }
static VV room(const char* type, double mix, double size, double decay, double damp = 0.5) { return {V("Reverb Type", type), V("Reverb Mix", mix), V("Reverb Size", size), V("Reverb Decay", decay), V("Reverb Damping", damp)}; }
static VV touch(double curve, double touchAmt = 0, double human = 0) { return {V("Velocity Curve", curve), V("Touch", touchAmt), V("Humanize", human)}; }
// noise 0..1 (how present the release noise is) -> dB: 0 = off, 0.5 about -41 dB
static VV rel(double ms, double key, double noise) { return {V("Release", ms), V("Release Key Track", key), V("Key Off Noise", noise <= 0 ? -70.0 : -50.0 + noise * 17.0)}; }
static VV res(double amt, double decay = 3) { return {V("String Resonance", amt), V("Resonance Decay", decay)}; }
static VV tune(const char* temp, const char* key = "C", double a4 = 440, double stretch = 10) { return {V("Temperament", temp), V("Temperament Key", key), V("A4", a4), V("Stretch", stretch)}; }

PresetList pianoPresets() {
    PresetList L; auto P = [&](const char* n, const char* c, std::initializer_list<const char*> t, VV v) { L.emplace_back(n, c, t, std::move(v)); };
    // ------------------------------------------------------------ Grand
    P("Concert Grand", "Grand", {"concert", "natural", "hall"}, tone(0, 0, 0) + room("Hall", 0.14, 0.75, 2.4) + res(0.35) + touch(1.0));
    P("Studio Grand", "Grand", {"studio", "close", "pop"}, tone(0, 1.5, 1.5) + room("Plate", 0.08, 0.5, 1.4) + res(0.3) + touch(0.95) + rel(180, 0.4, 0.5));
    P("Bright Grand", "Grand", {"bright", "cutting", "band"}, tone(-1.5, 2.5, 4) + room("Room", 0.07, 0.45, 1.1) + res(0.25) + touch(1.1, 0.25) + VV{V("Width", 1.3)});
    P("Warm Grand", "Grand", {"warm", "round", "ballad"}, tone(2.5, -1, -3.5, 0.82) + room("Hall", 0.12, 0.7, 2.2, 0.6) + res(0.4) + touch(0.9, -0.15));
    P("Recital Hall", "Grand", {"classical", "hall", "wide"}, tone(0.5, 0, -0.5) + room("Hall", 0.22, 0.9, 3.4, 0.45) + res(0.4) + touch(1.0, 0, 0.15) + VV{V("Width", 1.3)});
    P("Dry Close Mic", "Grand", {"dry", "intimate", "studio"}, tone(0.5, 1, 0.5) + room("Room", 0.0, 0.3, 0.6) + res(0.25) + touch(1.0) + rel(150, 0.4, 0.7));
    P("Jazz Club", "Grand", {"jazz", "room", "warm"}, tone(1.5, 0.5, -1.5, 0.9) + room("Room", 0.16, 0.55, 1.5, 0.6) + res(0.3) + touch(0.9, 0, 0.3) + tune("Equal"));
    P("Concert Pedaled", "Grand", {"pedal", "sustain", "wash"}, tone(0, 0, 0) + room("Hall", 0.18, 0.8, 2.8) + res(0.6, 4) + VV{V("Sustain Pedal", 1)} + touch(1.0));
    // ------------------------------------------------------------ Soft & intimate
    P("Felt Piano", "Soft", {"felt", "muted", "soft"}, tone(2, -2.5, -7, 0.6) + room("Room", 0.1, 0.4, 1.0, 0.7) + res(0.15) + touch(0.8, -0.4) + VV{V("Una Corda", 0.7)} + rel(120, 0.3, 0.3));
    P("Una Corda", "Soft", {"soft pedal", "tender", "close"}, tone(1.5, -1.5, -4, 0.8) + room("Room", 0.1, 0.4, 1.2, 0.6) + res(0.2) + touch(0.85, -0.2) + VV{V("Una Corda", 1)});
    P("Lullaby", "Soft", {"gentle", "soft", "child"}, tone(1.5, -1, -3, 0.85) + room("Hall", 0.2, 0.7, 2.6, 0.6) + res(0.3) + touch(0.75, -0.35) + VV{V("Una Corda", 0.4)});
    P("Late Night", "Soft", {"night", "dark", "intimate"}, tone(3, -2, -5.5, 0.72) + room("Room", 0.14, 0.5, 1.6, 0.7) + res(0.3) + touch(0.8, -0.3) + VV{V("Una Corda", 0.5)});
    P("Cinema Soft", "Soft", {"cinematic", "film", "piano"}, tone(1, -0.5, -2) + room("Hall", 0.3, 0.95, 4.5, 0.55) + res(0.45, 4) + touch(0.85, -0.2) + VV{V("Una Corda", 0.3), V("Sustain Pedal", 1)});
    // ------------------------------------------------------------ Character
    P("Honky Upright", "Character", {"honky", "tack", "old"}, tone(-1, 4, 2, 0.8) + room("Room", 0.08, 0.35, 0.9) + res(0.1) + touch(1.2, 0.2, 0.5) + tune("Equal", "C", 438, 4) + VV{V("Fine Tune", 7), V("Width", 0.7)});
    P("Old Radio Piano", "Character", {"lofi", "radio", "vintage"}, tone(-4, 5, -6, 0.42) + room("Room", 0.06, 0.3, 0.7, 0.8) + res(0.05) + touch(1.1, 0, 0.5) + VV{V("Width", 0.2)} + tune("Equal", "C", 432, 6));
    P("Tape Piano", "Character", {"tape", "lofi", "warm"}, tone(2.5, -2, -6, 0.55) + room("Plate", 0.12, 0.55, 1.6, 0.8) + res(0.2) + touch(0.9, -0.1, 0.45) + tune("Equal", "C", 436, 3) + VV{V("Width", 0.8)});
    P("Pop Hit Piano", "Character", {"pop", "hit", "bright"}, tone(-2, 3, 3.5) + room("Plate", 0.1, 0.5, 1.2) + res(0.2) + touch(1.3, 0.35) + rel(120, 0.3, 0.4));
    P("Ballroom Wide", "Character", {"wide", "stereo", "big"}, tone(1, 0.5, 1) + room("Hall", 0.2, 0.85, 3.0) + res(0.4) + touch(1.0) + VV{V("Width", 1.7)});
    // ------------------------------------------------------------ Tuning
    P("Baroque 415", "Tuning", {"baroque", "415", "historical"}, tone(0.5, 0, -1.5) + room("Hall", 0.16, 0.7, 2.0) + res(0.3) + tune("Werckmeister III", "C", 415, 0) + touch(0.95));
    P("Just Intonation", "Tuning", {"just", "pure", "harmonic"}, tone(0, 0, 0) + room("Hall", 0.14, 0.75, 2.4) + res(0.4) + tune("Just", "C", 440, 0));
    P("Pythagorean", "Tuning", {"pythagorean", "medieval", "pure fifths"}, tone(0, 0, 0) + room("Hall", 0.14, 0.75, 2.4) + res(0.35) + tune("Pythagorean", "C", 440, 0));
    P("Kirnberger III", "Tuning", {"kirnberger", "well-tempered", "historical"}, tone(0.5, 0, -1) + room("Hall", 0.15, 0.7, 2.2) + res(0.35) + tune("Kirnberger III", "C", 440, 0));
    P("Meantone", "Tuning", {"meantone", "renaissance", "historical"}, tone(0.5, 0, -1) + room("Hall", 0.15, 0.7, 2.2) + res(0.35) + tune("Meantone", "C", 440, 0));
    P("Stretched Concert", "Tuning", {"stretch", "wide", "grand"}, tone(0, 0, 0) + room("Hall", 0.14, 0.75, 2.4) + res(0.35) + tune("Equal", "C", 442, 24));
    return L;
}
