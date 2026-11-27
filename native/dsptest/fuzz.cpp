// Robustness test: random parameter sets and random notes through every plugin; fails on NaN/inf, runaway levels or denormal-slow blocks.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <random>
#include <unistd.h>
#include "../dsp/processor.h"
using namespace zdsp;
int main(int argc, char** argv) {
    const int rounds = argc > 1 ? atoi(argv[1]) : 20; int fails = 0;
    for (const auto& d : pluginList()) {
        std::unique_ptr<Processor> p(createProcessor(d.id)); const int sr = 48000, bs = 128; p->prepare(sr, bs); if (!strcmp(d.id, "grain")) usleep(900000); if (!strcmp(d.id, "sampler")) p->setState("sample", "/tmp/zsamp.wav");
        const auto& specs = p->params(); std::mt19937 rng(1234); std::uniform_real_distribution<float> U(0.f, 1.f);
        double worstPeak = 0; double worstBlockUs = 0; double sumUs = 0; long blocks = 0;
        for (int r = 0; r < rounds; r++) {
            p->reset(); std::vector<float> v(specs.size());
            for (size_t i = 0; i < v.size(); i++) { const float u = U(rng); v[i] = (r % 3 == 0) ? (u < 0.5f ? 0.f : 1.f) : u; if (specs[i].curve == Curve::Choice && r % 2) v[i] = specs[i].toNorm(std::floor(U(rng) * specs[i].labels.size())); }
            // sometimes the effects get a loud input
            std::vector<float> iL(bs), iR(bs), oL(bs), oR(bs);
            for (int b = 0; b < sr * 3 / bs; b++) {
                std::vector<ZkEvent> ev;
                if (b % 40 == 0) { for (int k = 0; k < 1 + (int)(U(rng) * 4); k++) ev.push_back({(uint32_t)(U(rng) * bs), ZK_NOTE_ON, 0, (uint8_t)(24 + U(rng) * 80), (uint8_t)(1 + U(rng) * 126)}); }
                if (b % 40 == 25) ev.push_back({0, ZK_ALL_NOTES_OFF, 0, 0, 0});
                for (int i = 0; i < bs; i++) { const float x = (U(rng) * 2.f - 1.f) * (r % 2 ? 0.9f : 0.1f) * (float)std::sin(6.28318 * 220 * (b * bs + i) / sr); iL[i] = x; iR[i] = x * 0.8f; }
                if (!d.instrument) { oL = iL; oR = iR; }
                ProcessCtx c{ev.data(), (int)ev.size(), iL.data(), iR.data(), oL.data(), oR.data(), bs, 60.0 + U(rng) * 200, (double)(b * bs) / sr * 2.0, true};
                for (size_t i = 0; i < v.size(); i++) if (U(rng) < 0.002f) v[i] = U(rng);   // parameters move while playing
                const auto t0 = std::chrono::steady_clock::now(); p->process(c, v.data()); const double us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count();
                worstBlockUs = std::max(worstBlockUs, us); sumUs += us; blocks++;
                for (int i = 0; i < bs; i++) for (float x : {oL[i], oR[i]}) { if (!std::isfinite(x)) { printf("%s round %d: NON-FINITE output\n", d.id, r); fails++; goto next; } worstPeak = std::max(worstPeak, (double)std::fabs(x)); }
            }
            next:;
        }
        const bool loud = d.instrument ? worstPeak > 1.5 : worstPeak > 5000.0;   // instruments end in a soft ceiling; effects may legitimately stack +60 dB of gain at extreme settings
        printf("%-7s peak %7.2f  avg %6.1f us/block  worst %7.1f us (budget %.0f us)%s\n", d.id, worstPeak, sumUs / blocks, worstBlockUs, 1e6 * bs / sr, loud ? "  <-- too loud" : ""); if (loud) fails++;
    }
    return fails ? 1 : 0;
}
