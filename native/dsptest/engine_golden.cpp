// Offline "golden" renders of the real audio engine (app/src/main/cpp/engine.cpp, built on a desktop with a stub for Oboe).
// Each scene is built through the same Engine calls the app makes, rendered with Engine::renderMix, and summarised as
// frames / peak / RMS / a checksum of every output sample. A change to the engine that should not change the sound must leave
// the checksums alone (use --strict); changes that should change it update the golden file on purpose.
//
//   engine_golden --print                    print the table
//   engine_golden --check golden/engine.txt [--strict]   compare (peak and RMS within 0.02 dB; with --strict the checksum too)
//   engine_golden --update golden/engine.txt rewrite the golden file
// Every scene is rendered twice in two engines: a scene whose two renders differ is reported as NONDETERMINISTIC.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <map>
#include <string>
#include <vector>
#include "../../app/src/main/cpp/engine.h"
using namespace zedok;

namespace zedok { namespace {
}}
// the encoders are only used by renderEncoded, which the tests never call
namespace zedok {
bool encodeFlac(const std::vector<float>&, int, int, const std::string&) { return false; }
bool encodeMp3(const std::vector<float>&, int, int, const std::string&) { return false; }
}

static AudioData tone(double hz, double secs, double amp, double rightRatio = 1.0, double phase = 0.0) {
    const int n = (int)(secs * kProjectRate); auto v = std::make_shared<std::vector<float>>(n * 2);
    for (int i = 0; i < n; i++) {
        const double env = std::min(1.0, i / 480.0) * std::min(1.0, (n - i) / 480.0);   // short ramps: no clicks
        const double x = amp * env * std::sin(2 * M_PI * hz * i / kProjectRate + phase);
        (*v)[2 * i] = (float)x; (*v)[2 * i + 1] = (float)(x * rightRatio);
    }
    return v;
}
static void notes(Engine& e, int track, std::vector<std::array<double, 4>> list) {   // {pitch, startSec, lenSec, velocity}
    std::vector<Note> ns;
    for (auto& n : list) { Note x; x.pitch = (uint8_t)n[0]; x.start = (int64_t)(n[1] * kProjectRate); x.length = (int64_t)(n[2] * kProjectRate); x.velocity = (uint8_t)n[3]; ns.push_back(x); }
    e.setNotes(track, std::move(ns));
}

struct Scene { const char* name; double secs; std::function<void(Engine&)> build; };
static std::vector<Scene> scenes() {
    std::vector<Scene> s;
    s.push_back({"clips_mix", 3.0, [](Engine& e) {
        int a = e.addTrack(), b = e.addTrack(), c = e.addTrack();
        e.addClip(a, tone(220, 2.5, 0.5), 0, 0, 2.5 * kProjectRate, 1.f);
        e.addClip(b, tone(330, 2.0, 0.4, 0.6), kProjectRate / 2, 0, 2.0 * kProjectRate, 0.7f);
        e.addClip(c, tone(440, 1.0, 0.3, 1.0, 1.0), kProjectRate, 12000, 0.9 * kProjectRate, 1.2f);
        e.setTrackGain(a, 0.8f); e.setTrackPan(a, -0.5f); e.setTrackPan(b, 0.7f); e.setTrackWidth(b, 1.6f); e.setTrackWidth(c, 0.3f);
        e.setMasterGain(0.9f); e.setMasterWidth(1.2f);
    }});
    s.push_back({"mute_solo", 2.0, [](Engine& e) {
        int a = e.addTrack(), b = e.addTrack(), c = e.addTrack();
        e.addClip(a, tone(200, 1.8, 0.4), 0, 0, 1.8 * kProjectRate, 1.f); e.addClip(b, tone(300, 1.8, 0.4), 0, 0, 1.8 * kProjectRate, 1.f); e.addClip(c, tone(500, 1.8, 0.4), 0, 0, 1.8 * kProjectRate, 1.f);
        e.setTrackMute(a, true); e.setTrackSolo(c, true);
    }});
    s.push_back({"hybrid_notes", 3.5, [](Engine& e) {
        int t = e.addTrack(); e.createNativePlugin(t, "hybrid");
        notes(e, t, {{48, 0.0, 1.0, 100}, {52, 0.0, 1.0, 90}, {55, 0.0, 1.0, 95}, {50, 1.5, 1.0, 110}, {57, 1.5, 1.0, 80}, {60, 2.5, 0.5, 120}});
    }});
    s.push_back({"drums_pattern", 3.0, [](Engine& e) {
        int t = e.addTrack(); e.createNativePlugin(t, "drums");
        std::vector<std::array<double, 4>> n; for (int i = 0; i < 8; i++) { n.push_back({36, i * 0.375, 0.1, 110}); if (i % 2) n.push_back({38, i * 0.375, 0.1, 100}); n.push_back({42, i * 0.375 + 0.1875, 0.05, 80}); }
        notes(e, t, n);
    }});
    s.push_back({"automation", 3.0, [](Engine& e) {
        int t = e.addTrack(); e.createNativePlugin(t, "hybrid");
        notes(e, t, {{45, 0.0, 2.8, 100}});
        AutoLane l; l.param = 0; l.frames = {0, kProjectRate, 2 * kProjectRate}; l.values = {0.2f, 0.9f, 0.4f}; l.shapes = {0, 2, 1};
        e.setAutomation(t, {l});
    }});
    s.push_back({"fx_chain", 3.0, [](Engine& e) {
        int a = e.addTrack(); e.addClip(a, tone(261.6, 2.8, 0.5), 0, 0, 2.8 * kProjectRate, 1.f);
        int f1 = e.addFx(a, 0); e.createNativePlugin(f1, "color"); int f2 = e.addFx(a, 1); e.createNativePlugin(f2, "eqcomp");
        int m = e.addFx(0, 0); e.createNativePlugin(m, "space");
    }});
    s.push_back({"bus_group", 3.0, [](Engine& e) {
        int a = e.addTrack(), b = e.addTrack(), c = e.addTrack();
        e.addClip(a, tone(220, 2.5, 0.4), 0, 0, 2.5 * kProjectRate, 1.f); e.addClip(b, tone(330, 2.5, 0.3, 0.7), 0, 0, 2.5 * kProjectRate, 1.f); e.addClip(c, tone(440, 2.5, 0.3), 0, 0, 2.5 * kProjectRate, 1.f);
        int b1 = e.addBus(), b2 = e.addBus();
        e.setBusGain(b1, 0.8f); e.setBusPan(b1, -0.3f); e.setBusWidth(b2, 1.5f);
        e.setTrackOutput(a, b1); e.setTrackOutput(b, b1); e.setBusOutput(b1, b2);   // a, b -> bus 1 -> bus 2 -> master; c goes straight to the master
        e.setTrackPan(c, 0.5f); e.setMasterGain(0.9f);
    }});
    s.push_back({"bus_mute_solo", 2.0, [](Engine& e) {
        int a = e.addTrack(), b = e.addTrack();
        e.addClip(a, tone(250, 1.8, 0.4), 0, 0, 1.8 * kProjectRate, 1.f); e.addClip(b, tone(350, 1.8, 0.4), 0, 0, 1.8 * kProjectRate, 1.f);
        int m = e.addBus(); e.setTrackOutput(a, m); e.setBusMute(m, true); e.setTrackSolo(b, true);   // the muted bus carries a silent track; solo on b
    }});
    s.push_back({"full_song", 5.0, [](Engine& e) {
        int k = e.addTrack(); e.createNativePlugin(k, "drums");
        int b = e.addTrack(); e.createNativePlugin(b, "hybrid");
        int v = e.addTrack(); e.addClip(v, tone(392, 4.0, 0.3, 0.8), kProjectRate / 4, 0, 4.0 * kProjectRate, 0.9f);
        std::vector<std::array<double, 4>> n; for (int i = 0; i < 12; i++) { n.push_back({36, i * 0.375, 0.1, 115}); if (i % 4 == 2) n.push_back({38, i * 0.375, 0.1, 100}); }
        notes(e, k, n); notes(e, b, {{36, 0.0, 2.0, 100}, {43, 2.25, 1.0, 100}, {41, 3.5, 1.2, 100}});
        e.setTrackGain(k, 0.9f); e.setTrackPan(v, 0.4f); e.setTrackWidth(v, 1.4f);
        int f = e.addFx(v, 0); e.createNativePlugin(f, "space"); int mf = e.addFx(0, 0); e.createNativePlugin(mf, "eqcomp");
        e.setMasterGain(0.85f); e.setTempo(110);
    }});
    return s;
}

struct Result { int64_t frames; double peakDb, rmsDb; uint64_t sum; };
static Result renderScene(const Scene& sc) {
    auto ep = std::make_unique<Engine>(); Engine& e = *ep; sc.build(e);
    std::vector<float> out; const int64_t to = (int64_t)(sc.secs * kProjectRate);
    if (!e.renderMix(0, to, 0, out)) { fprintf(stderr, "%s: renderMix failed\n", sc.name); return {0, 0, 0, 0}; }
    double sq = 0, pk = 0; uint64_t h = 1469598103934665603ull;
    for (float x : out) {
        sq += (double)x * x; pk = std::max(pk, (double)std::fabs(x));
        uint32_t u; memcpy(&u, &x, 4); for (int i = 0; i < 4; i++) { h ^= (u >> (8 * i)) & 0xff; h *= 1099511628211ull; }
    }
    return {(int64_t)out.size() / 2, 20 * log10(pk + 1e-12), 10 * log10(sq / std::max<size_t>(1, out.size()) + 1e-12), h};
}

// ---- routing checks that do not need a golden file ----
static std::vector<float> renderWith(double secs, const std::function<void(Engine&)>& build) {
    auto ep = std::make_unique<Engine>(); build(*ep); std::vector<float> out; ep->renderMix(0, (int64_t)(secs * kProjectRate), 0, out); return out;
}
static double maxDiff(const std::vector<float>& a, const std::vector<float>& b, size_t skip = 0) {   // skip: leading floats to ignore (a gain that starts away from unity ramps during the first block)
    if (a.size() != b.size()) return 1e9; double m = 0; for (size_t i = skip; i < a.size(); i++) m = std::max(m, (double)std::fabs(a[i] - b[i])); return m;
}
static int routingChecks() {
    int fails = 0; auto check = [&](const char* what, bool ok, double v) { printf("routing: %-52s %s (%.3g)\n", what, ok ? "ok" : "FAILED", v); if (!ok) fails++; };
    auto three = [](Engine& e, std::array<int, 3>& t) {
        t = {e.addTrack(), e.addTrack(), e.addTrack()};
        e.addClip(t[0], tone(220, 1.5, 0.4), 0, 0, 1.5 * kProjectRate, 1.f); e.addClip(t[1], tone(330, 1.5, 0.3, 0.6), 0, 0, 1.5 * kProjectRate, 1.f); e.addClip(t[2], tone(440, 1.5, 0.3), 0, 0, 1.5 * kProjectRate, 1.f);
        e.setTrackPan(t[0], -0.4f); e.setTrackWidth(t[1], 1.3f);
    };
    const auto direct = renderWith(2.0, [&](Engine& e) { std::array<int, 3> t; three(e, t); });
    const auto viaUnity = renderWith(2.0, [&](Engine& e) { std::array<int, 3> t; three(e, t); int b = e.addBus(); for (int x : t) e.setTrackOutput(x, b); });
    check("a unity bus changes nothing (<= 1e-5)", maxDiff(direct, viaUnity) < 1e-5, maxDiff(direct, viaUnity));
    const auto removed = renderWith(2.0, [&](Engine& e) { std::array<int, 3> t; three(e, t); int b = e.addBus(); for (int x : t) e.setTrackOutput(x, b); e.removeBus(b); });
    check("removing the bus sends its tracks back to the master, bit for bit", maxDiff(direct, removed) == 0.0, maxDiff(direct, removed));
    const auto halfBus = renderWith(2.0, [&](Engine& e) { std::array<int, 3> t; three(e, t); int b = e.addBus(); e.setBusGain(b, 0.5f); for (int x : t) e.setTrackOutput(x, b); });
    std::vector<float> half = direct; for (auto& x : half) x *= 0.5f;
    check("a bus at gain 0.5 halves the mix after the first block (<= 1e-5)", maxDiff(half, halfBus, 1024) < 1e-5, maxDiff(half, halfBus, 1024));
    // a chain declared against the flow: tracks -> bus3 -> bus2 -> bus1 -> master, buses created 1, 2, 3
    const auto chainA = renderWith(2.0, [&](Engine& e) { std::array<int, 3> t; three(e, t); int b1 = e.addBus(), b2 = e.addBus(), b3 = e.addBus(); e.setBusGain(b2, 0.7f); e.setBusPan(b1, 0.3f);
        for (int x : t) e.setTrackOutput(x, b3); e.setBusOutput(b3, b2); e.setBusOutput(b2, b1); });
    const auto chainB = renderWith(2.0, [&](Engine& e) { std::array<int, 3> t; three(e, t); int b3 = e.addBus(), b2 = e.addBus(), b1 = e.addBus(); e.setBusGain(b2, 0.7f); e.setBusPan(b1, 0.3f);
        for (int x : t) e.setTrackOutput(x, b3); e.setBusOutput(b3, b2); e.setBusOutput(b2, b1); });
    double peakA = 0; for (float x : chainA) peakA = std::max(peakA, (double)std::fabs(x));
    check("a chain created against the flow sounds the same (<= 1e-6)", maxDiff(chainA, chainB) < 1e-6 && peakA > 0.1, maxDiff(chainA, chainB));
    { auto ep = std::make_unique<Engine>(); Engine& e = *ep; int b1 = e.addBus(), b2 = e.addBus(), b3 = e.addBus();
      check("a bus can feed another", e.setBusOutput(b1, b2) && e.setBusOutput(b2, b3), 0);
      check("a loop is refused (3 -> 1)", !e.setBusOutput(b3, b1), 0);
      check("a bus cannot feed itself", !e.setBusOutput(b2, b2), 0);
      check("an unknown bus or track is refused", !e.setBusOutput(b1, 99) && !e.setTrackOutput(1, 99) && !e.setTrackOutput(77, b1), 0);
      int n = 3; while (e.addBus()) n++; check("the bus limit is enforced", n == kMaxBuses, n); }
    return fails;
}

int main(int argc, char** argv) {
    std::string mode = argc > 1 ? argv[1] : "--print", file = argc > 2 ? argv[2] : ""; bool strict = false;
    for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--strict")) strict = true;
    std::map<std::string, std::string> golden;
    if (mode == "--check") {
        FILE* f = fopen(file.c_str(), "r"); if (!f) { fprintf(stderr, "cannot open %s\n", file.c_str()); return 2; }
        char n[64], line[512]; while (fgets(line, sizeof line, f)) { if (sscanf(line, "%63s", n) == 1 && n[0] != '#') golden[n] = line; } fclose(f);
    }
    int fails = 0; std::string table = "# scene frames peak_dBFS rms_dBFS checksum (FNV-1a over the float bits of the output)\n";
    for (auto& sc : scenes()) {
        const Result r = renderScene(sc), r2 = renderScene(sc);
        char line[256]; snprintf(line, sizeof line, "%s %lld %.3f %.3f %016llx\n", sc.name, (long long)r.frames, r.peakDb, r.rmsDb, (unsigned long long)r.sum);
        table += line; printf("%s", line);
        if (r.sum != r2.sum || r.frames != r2.frames) { printf("  NONDETERMINISTIC: two renders of this scene differ\n"); fails++; }
        if (r.frames == 0) fails++;
        if (mode == "--check") {
            auto it = golden.find(sc.name); if (it == golden.end()) { printf("  missing from the golden file\n"); fails++; continue; }
            char n[64]; long long fr; double pk, rms; unsigned long long sum;
            if (sscanf(it->second.c_str(), "%63s %lld %lf %lf %llx", n, &fr, &pk, &rms, &sum) != 5) { printf("  bad golden line\n"); fails++; continue; }
            if (fr != r.frames) { printf("  FRAMES %lld != %lld\n", (long long)r.frames, fr); fails++; }
            if (std::fabs(pk - r.peakDb) > 0.02 || std::fabs(rms - r.rmsDb) > 0.02) { printf("  LEVEL peak %.3f vs %.3f, rms %.3f vs %.3f\n", r.peakDb, pk, r.rmsDb, rms); fails++; }
            else if (strict && sum != r.sum) { printf("  CHECKSUM %016llx != %016llx (levels match: not bit-exact)\n", (unsigned long long)r.sum, sum); fails++; }
        }
    }
    fails += routingChecks();
    if (mode == "--update") { FILE* f = fopen(file.c_str(), "w"); if (!f) return 2; fputs(table.c_str(), f); fclose(f); printf("wrote %s\n", file.c_str()); }
    printf(fails ? "FAILED (%d)\n" : "ok\n", fails);
    return fails ? 1 : 0;
}
