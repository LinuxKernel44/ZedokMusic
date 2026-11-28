#include "engine.h"
#include "flac_enc.h"
#include "mp3_enc.h"
#include <android/log.h>
#include <algorithm>
#include <thread>
#include <chrono>
#include <cmath>
#include <cstring>
#define TAG "ZedokEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace zedok {
static void appendAutomation(const std::vector<AutoLane>& lanes, int64_t pos, float* last, ZkParamChange* ch, int& nc);

Engine::Engine() {
    auto p = std::make_shared<Project>();
    current_ = p;
    published_.store(p.get());
    for (auto& m : meters_) m.store(0.f);
    for (auto& c : panicTrack_) c.store(0);
    for (auto& c : hardTrack_) c.store(0);
    for (auto& row : fxAutoLast_) for (auto& v : row) v = NAN;
    for (auto& row : fxAutoLastOff_) for (auto& v : row) v = NAN;
}
Engine::~Engine() { stop(); }

// ---------- editing ----------
void Engine::publish(std::shared_ptr<Project> p) {
    uint64_t v = version_.load(std::memory_order_relaxed);
    retired_.push_back({v, current_});
    uint64_t ack = ackVersion_.load(std::memory_order_acquire);
    // Keep anything the audio thread might still be reading (>= ack), plus a safety margin when no stream runs.
    std::erase_if(retired_, [&](const Retired& r) { return r.version + 2 < ack || (!stream_ && r.version + 8 < v); });
    current_ = p;
    published_.store(p.get(), std::memory_order_release);
    version_.store(v + 1, std::memory_order_release);
}
template <class F> void Engine::edit(F&& f) {
    std::lock_guard<std::mutex> lk(editMutex_);
    auto p = std::make_shared<Project>(*current_);
    f(*p);
    planLatency(*p);
    publish(std::move(p));
}
void Engine::releaseRetired() {
    if (releasePending_.fetch_add(1) > 0) return;  // a worker is already running: it will see the counter and go round once more
    std::thread([this]() {
        int again;
        do {
            for (int i = 0; i < 10; i++) { edit([](Project&) {}); std::this_thread::sleep_for(std::chrono::milliseconds(15)); }
            again = releasePending_.fetch_sub(1) - 1;
        } while (again > 0);
    }).detach();
}
bool Engine::resetTrackIds() {
    bool ok = false;
    edit([&](Project& p) { if (p.tracks.empty()) { nextTrackId_ = 1; ok = true; } });
    return ok;
}
int Engine::addTrack() {
    int id = 0;
    edit([&](Project& p) {
        if (nextTrackId_ >= kMaxTracks) return;
        id = nextTrackId_++; TrackState t; t.id = id; p.tracks.push_back(std::move(t));
    });
    return id;
}
bool Engine::removeTrack(int id) {
    bool ok = false;
    edit([&](Project& p) {
        auto it = std::remove_if(p.tracks.begin(), p.tracks.end(), [&](auto& t) { return t.id == id; });
        ok = it != p.tracks.end(); p.tracks.erase(it, p.tracks.end());
    });
    return ok;
}
int Engine::addClip(int track, AudioData audio, int64_t start, int64_t offset, int64_t length, float gain) {
    int id = 0;
    edit([&](Project& p) {
        for (auto& t : p.tracks) if (t.id == track) {
            Clip c; c.id = nextClipId_; c.audio = audio; c.start = start; c.offset = offset; c.length = length; c.gain = gain;
            t.clips.push_back(c); id = nextClipId_++;
        }
    });
    return id;
}
int Engine::loadClip(int track, const std::string& path, int64_t start, int64_t* framesOut) {
    std::vector<float> data; int rate = 0;
    if (!readWav(path, data, rate)) return 0;
    auto res = std::make_shared<const std::vector<float>>(resampleStereo(data, rate, kProjectRate));
    int64_t frames = (int64_t)(res->size() / 2);
    if (framesOut) *framesOut = frames;
    return addClip(track, res, start, 0, frames, 1.f);
}
bool Engine::removeClip(int clipId) {
    bool ok = false;
    edit([&](Project& p) { for (auto& t : p.tracks) { auto it = std::remove_if(t.clips.begin(), t.clips.end(), [&](auto& c) { return c.id == clipId; }); if (it != t.clips.end()) { t.clips.erase(it, t.clips.end()); ok = true; } } });
    return ok;
}
bool Engine::moveClip(int clipId, int64_t start) {
    bool ok = false;
    edit([&](Project& p) { for (auto& t : p.tracks) for (auto& c : t.clips) if (c.id == clipId) { c.start = std::max<int64_t>(0, start); ok = true; } });
    return ok;
}
bool Engine::setClipFrozen(int clipId, bool frozen) {
    bool ok = false;
    edit([&](Project& p) { for (auto& t : p.tracks) for (auto& c : t.clips) if (c.id == clipId) { c.frozen = frozen; ok = true; } });
    return ok;
}
bool Engine::setClipGain(int clipId, float gain) {
    bool ok = false;
    edit([&](Project& p) { for (auto& t : p.tracks) for (auto& c : t.clips) if (c.id == clipId) { c.gain = std::clamp(gain, 0.f, 4.f); ok = true; } });
    return ok;
}
#define TRACK_SET(name, expr) \
    void Engine::name { edit([&](Project& p) { for (auto& t : p.tracks) if (t.id == id) { expr; } }); }
TRACK_SET(setTrackGain(int id, float g), t.gain = std::clamp(g, 0.f, 4.f))
TRACK_SET(setTrackPan(int id, float v), t.pan = std::clamp(v, -1.f, 1.f))
TRACK_SET(setTrackWidth(int id, float v), t.width = std::clamp(v, 0.f, 2.f))
void Engine::setMasterWidth(float w) { edit([&](Project& p) { p.masterWidth = std::clamp(w, 0.f, 2.f); }); }
TRACK_SET(setTrackMute(int id, bool m), t.mute = m)
TRACK_SET(setTrackFrozen(int id, bool f), t.frozen = f)
TRACK_SET(setTrackSolo(int id, bool s), t.solo = s)
void Engine::setMasterGain(float g) { edit([&](Project& p) { p.masterGain = std::clamp(g, 0.f, 4.f); }); }
void Engine::setLoop(bool on, int64_t s, int64_t e) {
    edit([&](Project& p) { p.loop = on && e > s; p.loopStart = s; p.loopEnd = e; });
}

void Engine::setTempoMap(std::vector<int64_t> frames, std::vector<double> bpms) {
    edit([&](Project& p) {
        if (frames.empty() || bpms.size() != frames.size() + 1) { p.tempoFrames.clear(); p.tempoBpm.clear(); if (!bpms.empty()) tempo_.store(std::clamp(bpms[0], 20.0, 400.0)); return; }
        for (auto& b : bpms) b = std::clamp(b, 20.0, 400.0);
        p.tempoFrames = std::move(frames); p.tempoBpm = std::move(bpms);
    });
}
void Engine::tempoAt(const Project* p, int64_t pos, double& bpm, double& ppq) const {
    if (!p || p->tempoFrames.empty()) { bpm = tempo_.load(std::memory_order_relaxed); ppq = (double)pos / kProjectRate * bpm / 60.0; return; }
    size_t seg = 0; while (seg < p->tempoFrames.size() && p->tempoFrames[seg] <= pos) seg++;
    double q = 0.0; int64_t from = 0;
    for (size_t k = 0; k < seg; k++) { q += (double)(p->tempoFrames[k] - from) / kProjectRate * p->tempoBpm[k] / 60.0; from = p->tempoFrames[k]; }
    bpm = p->tempoBpm[seg]; ppq = q + (double)(pos - from) / kProjectRate * bpm / 60.0;
}

// ---------- routing ----------
static BusState* findBus(Project& p, int id) { for (auto& b : p.buses) if (b.id == id) return &b; return nullptr; }
static int busSlot(const Project& p, int id) { if (id <= 0) return -1; for (size_t i = 0; i < p.buses.size() && i < (size_t)kMaxBuses; i++) if (p.buses[i].id == id) return (int)i; return -1; }

void Engine::planRouting(Project& p) {
    const size_t n = std::min<size_t>(p.buses.size(), kMaxBuses);
    std::vector<int> feeders(n, 0), order; order.reserve(n);
    for (size_t i = 0; i < n; i++) { int d = busSlot(p, p.buses[i].output); if (d >= 0) feeders[d]++; }
    std::vector<int> ready; for (size_t i = 0; i < n; i++) if (feeders[i] == 0) ready.push_back((int)i);
    while (!ready.empty()) {
        int b = ready.back(); ready.pop_back(); order.push_back(b);
        int d = busSlot(p, p.buses[b].output);
        if (d >= 0 && --feeders[d] == 0) ready.push_back(d);
    }
    for (size_t i = 0; i < n; i++) if (std::find(order.begin(), order.end(), (int)i) == order.end()) order.push_back((int)i);   // never happens: setBusOutput refuses loops
    p.busOrder = std::move(order);
}
// Plugin delay compensation: every effect adds one callback, so each path from a track to the master gets the same total by waiting
// (D - its own latency) callbacks at the track's output. R(bus) = the bus's effects + R(its output).
void Engine::planLatency(Project& p) {
    const int nb = (int)std::min<size_t>(p.buses.size(), kMaxBuses);
    p.busLat.assign(p.buses.size(), 0);
    auto fxN = [](const std::vector<FxState>& v) { return std::min((int)v.size(), kMaxFx); };
    for (int pass = 0; pass < nb + 1; pass++)   // the bus graph has no loops: one pass per level settles it
        for (int b = 0; b < nb; b++) { const int d = busSlot(p, p.buses[b].output); p.busLat[b] = fxN(p.buses[b].fx) + (d >= 0 ? p.busLat[d] : 0); }
    auto routeOf = [&](int bus) { const int s = busSlot(p, bus); return s >= 0 && s < nb ? p.busLat[s] : 0; };
    int D = 0;
    for (auto& t : p.tracks) {
        const int n = fxN(t.fx);
        D = std::max(D, n + routeOf(t.output));
        for (auto& s : t.sends) D = std::max(D, n + routeOf(s.bus));
    }
    D = std::min(D, kMaxFx);
    p.latency = D;
    for (auto& t : p.tracks) {
        const int n = fxN(t.fx);
        t.outDelay = std::clamp(D - (n + routeOf(t.output)), 0, kMaxFx);
        for (auto& s : t.sends) s.delay = std::clamp(D - (n + routeOf(s.bus)), 0, kMaxFx);
    }
}
int Engine::addBus() {
    int id = 0;
    edit([&](Project& p) {
        if ((int)p.buses.size() >= kMaxBuses) return;
        int nid = 1; while (nid <= kMaxBusId && findBus(p, nid)) nid++;   // the smallest id in use by no bus (ids come back when a bus is removed)
        if (nid > kMaxBusId) return;
        BusState b; b.id = id = nid; p.buses.push_back(b); planRouting(p);
    });
    return id;
}
bool Engine::removeBus(int busId) {
    bool ok = false;
    edit([&](Project& p) {
        auto it = std::remove_if(p.buses.begin(), p.buses.end(), [&](auto& b) { return b.id == busId; });
        ok = it != p.buses.end(); if (!ok) return;
        p.buses.erase(it, p.buses.end());
        for (auto& t : p.tracks) { if (t.output == busId) t.output = 0; t.sends.erase(std::remove_if(t.sends.begin(), t.sends.end(), [&](auto& s) { return s.bus == busId; }), t.sends.end()); }
        for (auto& b : p.buses) if (b.output == busId) b.output = 0;
        planRouting(p);
    });
    return ok;
}
#define BUS_SET(name, expr) \
    void Engine::name { edit([&](Project& p) { if (auto* b = findBus(p, id)) { expr; } }); }
BUS_SET(setBusGain(int id, float g), b->gain = std::clamp(g, 0.f, 4.f))
BUS_SET(setBusPan(int id, float v), b->pan = std::clamp(v, -1.f, 1.f))
BUS_SET(setBusWidth(int id, float v), b->width = std::clamp(v, 0.f, 2.f))
BUS_SET(setBusMute(int id, bool m), b->mute = m)
int Engine::totalLatency() { std::lock_guard<std::mutex> lk(editMutex_); return current_->latency; }
int Engine::trackOutDelay(int track) { std::lock_guard<std::mutex> lk(editMutex_); for (auto& t : current_->tracks) if (t.id == track) return t.outDelay; return -1; }
int Engine::trackSendDelay(int track, int busId) { std::lock_guard<std::mutex> lk(editMutex_); for (auto& t : current_->tracks) if (t.id == track) for (auto& s : t.sends) if (s.bus == busId) return s.delay; return -1; }
bool Engine::setSend(int track, int busId, float gain, bool pre) {
    bool ok = false;
    edit([&](Project& p) {
        if (!findBus(p, busId)) return;
        for (auto& t : p.tracks) if (t.id == track) {
            const float g = std::clamp(gain, 0.f, 4.f);
            for (auto& s : t.sends) if (s.bus == busId) { s.gain = g; s.pre = pre; ok = true; return; }
            if ((int)t.sends.size() >= kMaxSends) return;
            t.sends.push_back({busId, g, pre}); ok = true;
        }
    });
    return ok;
}
bool Engine::removeSend(int track, int busId) {
    bool ok = false;
    edit([&](Project& p) { for (auto& t : p.tracks) if (t.id == track) { auto it = std::remove_if(t.sends.begin(), t.sends.end(), [&](auto& s) { return s.bus == busId; }); ok = it != t.sends.end(); t.sends.erase(it, t.sends.end()); } });
    return ok;
}
bool Engine::setTrackOutput(int track, int busId) {
    bool ok = false;
    edit([&](Project& p) {
        if (busId != 0 && !findBus(p, busId)) return;
        for (auto& t : p.tracks) if (t.id == track) { t.output = busId; ok = true; }
    });
    return ok;
}
bool Engine::setBusOutput(int busId, int dest) {
    bool ok = false;
    edit([&](Project& p) {
        BusState* b = findBus(p, busId); if (!b) return;
        if (dest != 0) {   // walking down from the destination must never come back to this bus
            if (!findBus(p, dest)) return;
            for (int at = dest, hops = 0; at != 0; hops++) { if (at == busId || hops > kMaxBuses) return; BusState* x = findBus(p, at); at = x ? x->output : 0; }
        }
        b->output = dest; planRouting(p); ok = true;
    });
    return ok;
}
float Engine::peak(int track) const {
    int i = (track >= 0 && track < kMaxTracks) ? track : kMaxTracks;
    return meters_[i].exchange(0.f);
}
int64_t Engine::projectLength() const {
    int64_t len = 0;
    std::lock_guard<std::mutex> lk(const_cast<std::mutex&>(editMutex_));
    auto p = current_;
    for (auto& t : p->tracks) for (auto& c : t.clips) len = std::max(len, c.start + c.length);
    return len;
}

// ---------- mixer ----------
void Engine::accumulateDry(const Project& p, int64_t pos, int n, MixState& ms, int offset) {
    for (size_t ti = 0; ti < p.tracks.size() && ti < (size_t)kMaxTracks; ti++) {
        const TrackState& t = p.tracks[ti];
        float* tb = ms.dry[ti] + offset * 2;
        if (ms.playClips) for (auto& c : t.clips) {
            if (!c.audio || c.frozen != t.frozen) continue;   // a frozen track plays only its frozen render, every other track never plays one
            int64_t cs = std::max<int64_t>(pos, c.start), ce = std::min<int64_t>(pos + n, c.start + c.length);
            if (ce <= cs) continue;
            const float* src = c.audio->data();
            int64_t total = (int64_t)(c.audio->size() / 2);
            for (int64_t f = cs; f < ce; f++) {
                int64_t si = c.offset + (f - c.start); if (si < 0 || si >= total) continue;
                int o = (int)(f - pos) * 2;
                tb[o] += src[si * 2] * c.gain; tb[o + 1] += src[si * 2 + 1] * c.gain;
            }
        }
        if (t.plugin && t.pluginSlot >= 0 && !t.frozen) {
            const float* po = ms.plugOut[t.pluginSlot] + offset * 2;
            for (int i = 0; i < n * 2; i++) tb[i] += po[i];
        }
    }
}

int64_t Engine::renderBlockDry(const Project& p, int64_t pos, int frames, MixState& ms) {
    for (size_t ti = 0; ti < p.tracks.size() && ti < (size_t)kMaxTracks; ti++) memset(ms.dry[ti], 0, sizeof(float) * 2 * frames);
    int done = 0;
    while (done < frames) {
        int n = frames - done;
        if (p.loop && pos < p.loopEnd) n = (int)std::min<int64_t>(n, p.loopEnd - pos);
        accumulateDry(p, pos, n, ms, done);
        done += n; pos += n;
        if (p.loop && pos >= p.loopEnd) pos = p.loopStart;
    }
    return pos;
}

void Engine::mixTracks(const Project& p, int frames, float* out, MixState& ms, std::atomic<float>* meters, bool offline, int64_t pos, int64_t budgetNs) {
    if (!ms.init) { for (auto& g : ms.smoothGain) g = 1.f; for (auto& w : ms.smoothWidth) w = 1.f; for (auto& g : ms.smoothBusGain) g = 1.f; for (auto& row : ms.smoothSend) for (auto& g : row) g = 1.f; for (auto& w : ms.smoothBusWidth) w = 1.f; ms.init = true; }
    memset(out, 0, sizeof(float) * 2 * frames);
    const int nBuses = (int)std::min<size_t>(p.buses.size(), kMaxBuses);
    for (int b = 0; b < nBuses; b++) memset(ms.bus[b], 0, sizeof(float) * 2 * frames);
    auto destOf = [&](int output) -> float* { const int s = busSlot(p, output); return s >= 0 ? ms.bus[s] : out; };   // 0 or a bus that is gone: the master
    bool anySolo = false;
    for (auto& t : p.tracks) anySolo |= t.solo;
    for (size_t ti = 0; ti < p.tracks.size() && ti < (size_t)kMaxTracks; ti++) {
        const TrackState& t = p.tracks[ti];
        bool audible = !t.mute && (!anySolo || t.solo);
        float target = audible ? t.gain : 0.f;
        float g0 = ms.smoothGain[t.id], g1 = target;
        ms.smoothGain[t.id] = g1;
        // constant-power pan
        float ang = (t.pan * 0.5f + 0.5f) * (float)M_PI_2;
        float pl = cosf(ang) * (float)M_SQRT2, pr = sinf(ang) * (float)M_SQRT2;
        const float* tb = ms.dry[ti];
        float* dst = destOf(t.output);
        float tpk = 0.f;
        const float w0 = ms.smoothWidth[t.id], w1 = t.width; ms.smoothWidth[t.id] = w1;   // stereo width: mid / side, the side gain moves smoothly
        for (int i = 0; i < frames; i++) {
            float g = g0 + (g1 - g0) * ((float)i / (float)frames);
            float xl = tb[i * 2], xr = tb[i * 2 + 1];
            if (w0 != 1.f || w1 != 1.f) { const float w = w0 + (w1 - w0) * ((float)i / (float)frames); const float m = 0.5f * (xl + xr), s = 0.5f * (xl - xr) * w; xl = m + s; xr = m - s; }
            float l = xl * g * pl, r = xr * g * pr;
            dst[i * 2] += l; dst[i * 2 + 1] += r;
            tpk = std::max(tpk, std::max(fabsf(l), fabsf(r)));
        }
        int sk = -1;
        for (const SendState& sd : t.sends) {   // sends: a copy at the send level (pre: before the fader and the pan, but not while the track is muted or not soloed)
            sk++; const int bs = busSlot(p, sd.bus); if (bs < 0 || t.id >= kMaxTracks) continue;
            const float* sb = (ms.useSendSrc && sk < kMaxSends) ? ms.sendSrc[ti][sk] : tb;   // the track's output, delayed to line up with the other paths
            float& sm = ms.smoothSend[t.id][sd.bus]; const float s0 = sm, s1 = sd.gain * (sd.pre ? (audible ? 1.f : 0.f) : 1.f); sm = s1;
            float* sdst = ms.bus[bs];
            if (sd.pre) { for (int i = 0; i < frames; i++) { const float s = s0 + (s1 - s0) * ((float)i / (float)frames); sdst[i * 2] += sb[i * 2] * s; sdst[i * 2 + 1] += sb[i * 2 + 1] * s; } }
            else {   // after the fader: what the track sends to its output, scaled by the send level
                for (int i = 0; i < frames; i++) {
                    const float s = s0 + (s1 - s0) * ((float)i / (float)frames), g = g0 + (g1 - g0) * ((float)i / (float)frames);
                    float xl = sb[i * 2], xr = sb[i * 2 + 1];
                    if (w0 != 1.f || w1 != 1.f) { const float w = w0 + (w1 - w0) * ((float)i / (float)frames); const float m = 0.5f * (xl + xr), sdv = 0.5f * (xl - xr) * w; xl = m + sdv; xr = m - sdv; }
                    sdst[i * 2] += xl * g * pl * s; sdst[i * 2 + 1] += xr * g * pr * s;
                }
            }
        }
        if (meters && t.id < kMaxTracks) { float old = meters[t.id].load(std::memory_order_relaxed); if (tpk > old) meters[t.id].store(tpk, std::memory_order_relaxed); }
    }
    // Buses, each after everything routed into it: same gain / constant-power pan / width as a track, then on to its destination.
    for (int oi = 0; oi < (int)p.busOrder.size() && oi < nBuses; oi++) {
        const int bi = p.busOrder[oi]; if (bi < 0 || bi >= nBuses) continue;
        const BusState& b = p.buses[bi];
        if (!b.fx.empty()) busFx(p, ms, bi, frames, offline, pos, budgetNs);
        const float g0 = ms.smoothBusGain[b.id], g1 = b.mute ? 0.f : b.gain; ms.smoothBusGain[b.id] = g1;
        const float w0 = ms.smoothBusWidth[b.id], w1 = b.width; ms.smoothBusWidth[b.id] = w1;
        const float ang = (b.pan * 0.5f + 0.5f) * (float)M_PI_2;
        const float pl = cosf(ang) * (float)M_SQRT2, pr = sinf(ang) * (float)M_SQRT2;
        const float* bb = ms.bus[bi]; float* dst = destOf(b.output);
        for (int i = 0; i < frames; i++) {
            const float g = g0 + (g1 - g0) * ((float)i / (float)frames);
            float xl = bb[i * 2], xr = bb[i * 2 + 1];
            if (w0 != 1.f || w1 != 1.f) { const float w = w0 + (w1 - w0) * ((float)i / (float)frames); const float m = 0.5f * (xl + xr), s = 0.5f * (xl - xr) * w; xl = m + s; xr = m - s; }
            dst[i * 2] += xl * g * pl; dst[i * 2 + 1] += xr * g * pr;
        }
    }
}

void Engine::masterOut(const Project& p, int frames, float* out, MixState& ms, std::atomic<float>* meters) {
    float masterPeak = 0.f;
    float m0 = ms.smoothMaster, m1 = p.masterGain; ms.smoothMaster = m1;
    const float mw0 = ms.smoothMasterWidth, mw1 = p.masterWidth; ms.smoothMasterWidth = mw1;
    for (int i = 0; i < frames; i++) {
        float g = m0 + (m1 - m0) * ((float)i / (float)frames);
        if (mw0 != 1.f || mw1 != 1.f) { const float w = mw0 + (mw1 - mw0) * ((float)i / (float)frames); const float m = 0.5f * (out[i * 2] + out[i * 2 + 1]), s = 0.5f * (out[i * 2] - out[i * 2 + 1]) * w; out[i * 2] = m + s; out[i * 2 + 1] = m - s; }
        out[i * 2] *= g; out[i * 2 + 1] *= g;
        masterPeak = std::max(masterPeak, std::max(fabsf(out[i * 2]), fabsf(out[i * 2 + 1])));
    }
    if (meters) { float old = meters[kMaxTracks].load(std::memory_order_relaxed); if (masterPeak > old) meters[kMaxTracks].store(masterPeak, std::memory_order_relaxed); }
}

// ---------- insert effects ----------
static inline bool fxActive(const FxState& f) { return f.plugin && !f.bypass && f.plugin->ready(); }

void Engine::drainParams() {
    np_ = 0;
    uint32_t pt = paramTail_.load(std::memory_order_relaxed), ph = paramHead_.load(std::memory_order_acquire);
    while (pt != ph && np_ < ZK_MAX_PARAM_CHANGES * 2) pe_[np_++] = paramQ_[pt++ % paramQ_.size()];
    paramTail_.store(pt, std::memory_order_release);
}

// Sends one block to an effect. `autoPos` is the timeline position of that block (-1 = not playing, no automation).
void Engine::fxSubmit(const FxState& fx, const float* in, int frames, int64_t autoPos, float* autoLast, int64_t* lastPos) {
    ZkParamChange ch[ZK_MAX_PARAM_CHANGES]; int nc = 0;
    for (int i = 0; i < np_; i++) if (pe_[i].track == fx.id && nc < ZK_MAX_PARAM_CHANGES) ch[nc++] = ZkParamChange{(uint32_t)pe_[i].index, pe_[i].value};
    if (autoPos < 0 || (lastPos && *lastPos != autoPos - frames)) for (int k = 0; k < kMaxLanes; k++) autoLast[k] = NAN;  // stopped or jumped: send the values again
    if (autoPos >= 0 && !fx.lanes.empty()) appendAutomation(fx.lanes, autoPos, autoLast, ch, nc);
    if (lastPos) *lastPos = autoPos;
    double bpm, ppq; tempoAt(audioProject_, autoPos >= 0 ? autoPos : 0, bpm, ppq);
    fx.plugin->setTransport(bpm, autoPos >= 0 ? ppq : -1.0, autoPos >= 0);
    fx.plugin->submitAudio(in, frames, ch, nc);
}

void Engine::fxRealtime(const Project& p, MixState& ms, int frames, int64_t budgetNs) {
    const int L = p.latency;
    ms.latencyBlocks = L; ms.useSendSrc = false;
    if (L == 0 || frames != kCallbackFramesFx) return;
    const size_t bytes = sizeof(float) * 2 * frames;
    const auto t0 = std::chrono::steady_clock::now();
    const int64_t totalNs = (int64_t)(0.7 * 1e9 * frames / kProjectRate);
    auto waitLeft = [&]() { int64_t el = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count(); return std::min<int64_t>(budgetNs, std::max<int64_t>(0, totalNs - el)); };
    ms.useSendSrc = true;
    const int ring = kMaxFx + 1;
    for (size_t ti = 0; ti < p.tracks.size() && ti < (size_t)kMaxTracks; ti++) {
        const TrackState& t = p.tracks[ti];
        const int n = std::min((int)t.fx.size(), kMaxFx);
        FxChain& ch = ms.chain[t.id];
        const int sig = n * 100 + L;
        if (ch.sig != sig) { memset(ch.hold, 0, sizeof ch.hold); memset(ch.fin, 0, sizeof ch.fin); ch.finPos = 0; ch.sig = sig; }
        const float* cur = ms.dry[ti];
        if (n > 0) {
            for (int i = 0; i < n; i++) {  // results of last callback's requests
                const FxState& f = t.fx[i]; float* o = ms.stage[i];
                if (!t.frozen && fxActive(f)) f.plugin->collect(o, frames, waitLeft()); else memcpy(o, ch.hold[i], bytes);
            }
            for (int i = 0; i < n; i++) {  // requests for this callback: effect i processes what effect i-1 returned
                const FxState& f = t.fx[i]; const float* in = i == 0 ? ms.dry[ti] : ms.stage[i - 1];
                if (!t.frozen && fxActive(f)) fxSubmit(f, in, frames, posRing_[(blockCounter_ - i) & 31], fxAutoLast_[f.pluginSlot], &fxLastPos_[f.pluginSlot]);
                else memcpy(ch.hold[i], in, bytes);
            }
            cur = ms.stage[n - 1];
        }
        // Delay compensation: the track's output (and each send) waits so that every path to the master has the same latency, see planLatency.
        memcpy(ch.fin[ch.finPos], cur, bytes);
        const int rd = ((ch.finPos - t.outDelay) % ring + ring) % ring;
        for (size_t k = 0; k < t.sends.size() && k < (size_t)kMaxSends; k++) ms.sendSrc[ti][k] = ch.fin[((ch.finPos - t.sends[k].delay) % ring + ring) % ring];
        memcpy(ms.dry[ti], ch.fin[rd], bytes);
        ch.finPos = (ch.finPos + 1) % ring;
    }
}

void Engine::busFx(const Project& p, MixState& ms, int bi, int frames, bool offline, int64_t pos, int64_t budgetNs) {
    const BusState& b = p.buses[bi];
    const int n = std::min((int)b.fx.size(), kMaxFx);
    float* buf = ms.bus[bi];
    if (offline) {
        constexpr int64_t kWait = 5'000'000'000;
        for (int i = 0; i < n; i++) {
            const FxState& f = b.fx[i]; if (!fxActive(f)) continue;
            ZkParamChange ch[ZK_MAX_PARAM_CHANGES]; int nc = 0;
            if (!f.lanes.empty()) appendAutomation(f.lanes, pos, fxAutoLastOff_[f.pluginSlot], ch, nc);
            { double bpm, ppq; tempoAt(&p, pos, bpm, ppq); f.plugin->setTransport(bpm, ppq, true); }
            f.plugin->submitAudio(buf, frames, ch, nc);
            f.plugin->collect(buf, frames, kWait);
        }
        return;
    }
    if (frames != kCallbackFramesFx) return;
    const size_t bytes = sizeof(float) * 2 * frames;
    FxChain& ch = ms.busChain[bi];
    if (ch.sig != n) { memset(ch.hold, 0, sizeof ch.hold); ch.sig = n; }
    const auto t0 = std::chrono::steady_clock::now();
    const int64_t totalNs = (int64_t)(0.7 * 1e9 * frames / kProjectRate);
    auto waitLeft = [&]() { int64_t el = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count(); return std::min<int64_t>(budgetNs, std::max<int64_t>(0, totalNs - el)); };
    for (int i = 0; i < n; i++) {
        const FxState& f = b.fx[i]; float* o = ms.stage[i];
        if (fxActive(f)) f.plugin->collect(o, frames, waitLeft()); else memcpy(o, ch.hold[i], bytes);
    }
    const int before = p.latency - (bi < (int)p.busLat.size() ? p.busLat[bi] : 0);   // callbacks between the timeline and this bus's input
    for (int i = 0; i < n; i++) {
        const FxState& f = b.fx[i]; const float* in = i == 0 ? buf : ms.stage[i - 1];
        if (fxActive(f)) fxSubmit(f, in, frames, posRing_[(blockCounter_ - before - i) & 31], fxAutoLast_[f.pluginSlot], &fxLastPos_[f.pluginSlot]);
        else memcpy(ch.hold[i], in, bytes);
    }
    memcpy(buf, ms.stage[n - 1], bytes);
}

void Engine::masterFxRealtime(const Project& p, MixState& ms, float* out, int frames, int64_t budgetNs) {
    const int m = std::min((int)p.masterFx.size(), kMaxFx);
    if (m == 0 || frames != kCallbackFramesFx) return;
    const size_t bytes = sizeof(float) * 2 * frames;
    FxChain& ch = ms.masterChain;
    if (ch.sig != m) { memset(ch.hold, 0, sizeof ch.hold); ch.sig = m; }
    const auto t0 = std::chrono::steady_clock::now();
    const int64_t totalNs = (int64_t)(0.7 * 1e9 * frames / kProjectRate);
    auto waitLeft = [&]() { int64_t el = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count(); return std::min<int64_t>(budgetNs, std::max<int64_t>(0, totalNs - el)); };
    for (int i = 0; i < m; i++) {
        const FxState& f = p.masterFx[i]; float* o = ms.stage[i];
        if (fxActive(f)) f.plugin->collect(o, frames, waitLeft()); else memcpy(o, ch.hold[i], bytes);
    }
    for (int i = 0; i < m; i++) {
        const FxState& f = p.masterFx[i]; const float* in = i == 0 ? out : ms.stage[i - 1];
        if (fxActive(f)) fxSubmit(f, in, frames, posRing_[(blockCounter_ - ms.latencyBlocks - i) & 31], fxAutoLast_[f.pluginSlot], &fxLastPos_[f.pluginSlot]);
        else memcpy(ch.hold[i], in, bytes);
    }
    memcpy(out, ms.stage[m - 1], bytes);
}

// Offline: every effect is waited for, so the chain adds no latency and nothing needs compensating.
void Engine::fxOffline(const Project& p, MixState& ms, int frames, int64_t pos) {
    constexpr int64_t kWait = 5'000'000'000;
    for (size_t ti = 0; ti < p.tracks.size() && ti < (size_t)kMaxTracks; ti++) {
        const TrackState& t = p.tracks[ti];
        if (t.mute || t.frozen) continue;
        float* buf = ms.dry[ti];
        for (auto& f : t.fx) {
            if (!fxActive(f)) continue;
            ZkParamChange ch[ZK_MAX_PARAM_CHANGES]; int nc = 0;
            if (!f.lanes.empty()) appendAutomation(f.lanes, pos, fxAutoLastOff_[f.pluginSlot], ch, nc);
            { double bpm, ppq; tempoAt(&p, pos, bpm, ppq); f.plugin->setTransport(bpm, ppq, true); }
            f.plugin->submitAudio(buf, frames, ch, nc);
            f.plugin->collect(buf, frames, kWait);
        }
    }
}
void Engine::fxOfflineMaster(const Project& p, float* out, int frames, int64_t pos) {
    constexpr int64_t kWait = 5'000'000'000;
    for (auto& f : p.masterFx) {
        if (!fxActive(f)) continue;
        ZkParamChange ch[ZK_MAX_PARAM_CHANGES]; int nc = 0;
        if (!f.lanes.empty()) appendAutomation(f.lanes, pos, fxAutoLastOff_[f.pluginSlot], ch, nc);
        { double bpm, ppq; tempoAt(&p, pos, bpm, ppq); f.plugin->setTransport(bpm, ppq, true); }
        f.plugin->submitAudio(out, frames, ch, nc);
        f.plugin->collect(out, frames, kWait);
    }
}

// ---------- offline render ----------
bool Engine::renderMix(int64_t from, int64_t to, int onlyTrack, std::vector<float>& all) {
    playing_.store(false);
    std::shared_ptr<const Project> p;
    { std::lock_guard<std::mutex> lk(editMutex_); p = current_; }
    if (to <= from) to = std::max<int64_t>(from, projectLength());
    if (to <= from) return false;
    Project q = *p; q.loop = false;  // offline render ignores loop
    const bool raw = onlyTrack < 0; if (raw) onlyTrack = -onlyTrack;
    if (onlyTrack > 0) for (auto& t : q.tracks) if (t.id != onlyTrack) t.mute = true;
    if (raw) for (auto& t : q.tracks) if (t.id == onlyTrack) { t.gain = 1.f; t.pan = 0.f; t.width = 1.f; t.output = 0; t.sends.clear(); t.solo = false; t.mute = false; t.frozen = false; }
    if (raw) { q.masterGain = 1.f; q.masterWidth = 1.f; }
    if (raw) for (auto& t : q.tracks) if (t.id != onlyTrack) t.solo = false;
    auto ms = std::make_unique<MixState>();
    ms->playClips = true;
    {   // gains start at unity, except for tracks that are silent for the whole render: they must not fade out during the first block
        for (auto& g : ms->smoothGain) g = 1.f; for (auto& w : ms->smoothWidth) w = 1.f; for (auto& g : ms->smoothBusGain) g = 1.f; for (auto& w : ms->smoothBusWidth) w = 1.f; for (auto& row : ms->smoothSend) for (auto& g : row) g = 1.f;
        bool anySolo = false; for (auto& t : q.tracks) anySolo |= t.solo;
        for (auto& t : q.tracks) if (t.id < kMaxTracks && (t.mute || (anySolo && !t.solo))) ms->smoothGain[t.id] = 0.f;
        ms->init = true;
    }
    constexpr int64_t kSyncWaitNs = 5'000'000'000;
    const int plugFrames = kCallbackFrames;
    // Take the plugin bridges away from the realtime callback (it keeps running but outputs silence) and wait until it has noticed.
    exporting_.store(true, std::memory_order_release);
    { const int64_t c0 = callbacks_.load(); auto t0 = std::chrono::steady_clock::now();
      while (stream_ && callbacks_.load() < c0 + 3 && std::chrono::steady_clock::now() - t0 < std::chrono::milliseconds(500)) std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
    struct Done { Engine* e; ~Done() { e->exporting_.store(false, std::memory_order_release); } } done{this};
    // Effects: finish what the realtime path left in flight, then let their tails die (a second of silence) so the render starts clean.
    {
        std::vector<const FxState*> fxs;
        for (auto& t : q.tracks) for (auto& f : t.fx) if (fxActive(f)) fxs.push_back(&f);
        for (auto& f : q.masterFx) if (fxActive(f)) fxs.push_back(&f);
        for (auto& b : q.buses) for (auto& f : b.fx) if (fxActive(f)) fxs.push_back(&f);
        std::vector<float> zeros(plugFrames * 2, 0.f), sink(plugFrames * 2);
        for (auto* f : fxs) f->plugin->collect(sink.data(), plugFrames, kSyncWaitNs);
        for (int b = 0; b < 150 && !fxs.empty(); b++) for (auto* f : fxs) { f->plugin->submitAudio(zeros.data(), plugFrames); f->plugin->collect(sink.data(), plugFrames, kSyncWaitNs); }
        for (auto& row : fxAutoLastOff_) for (auto& v : row) v = NAN;
    }
    // Drain anything the realtime path left in flight, then silence the plugins.
    for (auto& t : q.tracks) if (t.plugin && t.plugin->ready()) {
        t.plugin->collect(ms->plugOut[t.pluginSlot], plugFrames, kSyncWaitNs);
        ZkEvent off{0, ZK_ALL_NOTES_OFF, 0, 0, 0}; t.plugin->submit(&off, 1, plugFrames);
        t.plugin->collect(ms->plugOut[t.pluginSlot], plugFrames, kSyncWaitNs);
    }
    all.clear(); all.reserve((size_t)(to - from) * 2);
    std::vector<float> blk(plugFrames * 2);
    for (int64_t pos = from; pos < to; pos += plugFrames) {
        int n = (int)std::min<int64_t>(plugFrames, to - pos);
        for (auto& t : q.tracks) if (t.plugin && t.pluginSlot >= 0) {
            float* dst = ms->plugOut[t.pluginSlot];
            if (!t.plugin->ready() || (onlyTrack > 0 && t.id != onlyTrack)) { memset(dst, 0, sizeof(float) * 2 * plugFrames); continue; }
            ZkEvent ev[ZK_MAX_EVENTS]; int ne = 0;
            if (pos == from) ev[ne++] = ZkEvent{0, ZK_ALL_NOTES_OFF, 0, 0, 0};
            windowEvents(q, t, pos, n, ev, ne);
            ZkParamChange ch[ZK_MAX_PARAM_CHANGES]; int nc = 0;  // replay the UI's current parameter values, 64 per block
            { int total = t.plugin->paramCount(); int blk = (int)((pos - from) / plugFrames);
              for (int i = blk * ZK_MAX_PARAM_CHANGES; i < total && nc < ZK_MAX_PARAM_CHANGES; i++) ch[nc++] = ZkParamChange{(uint32_t)i, t.plugin->paramValue(i)}; }
            if (!t.lanes.empty()) {
                static thread_local float last[kMaxLanes];
                if (pos == from) for (int k = 0; k < kMaxLanes; k++) last[k] = NAN;
                appendAutomation(t.lanes, pos, last, ch, nc);
            }
            { double bpm, ppq; tempoAt(&q, pos, bpm, ppq); t.plugin->setTransport(bpm, ppq, true); }
            t.plugin->submit(ev, ne, plugFrames, ch, nc);
            t.plugin->collect(dst, plugFrames, kSyncWaitNs);
        }
        for (size_t ti = 0; ti < q.tracks.size() && ti < (size_t)kMaxTracks; ti++) memset(ms->dry[ti], 0, sizeof(float) * 2 * plugFrames);
        accumulateDry(q, pos, n, *ms, 0);
        fxOffline(q, *ms, plugFrames, pos);
        mixTracks(q, plugFrames, blk.data(), *ms, nullptr, true, pos);
        if (onlyTrack == 0) fxOfflineMaster(q, blk.data(), plugFrames, pos);  // stems come without the master effects
        masterOut(q, plugFrames, blk.data(), *ms, nullptr);
        all.insert(all.end(), blk.begin(), blk.begin() + n * 2);
    }
    for (auto& t : q.tracks) if (t.plugin && t.plugin->ready()) {  // leave the plugins silent for the realtime path
        ZkEvent off{0, ZK_ALL_NOTES_OFF, 0, 0, 0}; t.plugin->submit(&off, 1, plugFrames);
        t.plugin->collect(ms->plugOut[t.pluginSlot], plugFrames, kSyncWaitNs);
    }
    return true;
}
bool Engine::renderToWav(const std::string& path, int64_t from, int64_t to, WavFormat fmt, int onlyTrack) {
    std::vector<float> all; if (!renderMix(from, to, onlyTrack, all)) return false;
    return writeWav(path, all, kProjectRate, fmt);
}
bool Engine::renderEncoded(const std::string& path, int64_t from, int64_t to, int format, int onlyTrack) {
    std::vector<float> all; if (!renderMix(from, to, onlyTrack, all)) return false;
    switch (format) {
        case 0: return writeWav(path, all, kProjectRate, WavFormat::Pcm16);
        case 1: return writeWav(path, all, kProjectRate, WavFormat::Pcm24);
        case 2: return writeWav(path, all, kProjectRate, WavFormat::Float32);
        case 4: return encodeFlac(all, kProjectRate, 16, path);
        case 5: return encodeFlac(all, kProjectRate, 24, path);
        case 6: return encodeMp3(all, kProjectRate, 320, path);
        case 7: return encodeMp3(all, kProjectRate, 192, path);
        default: return false;
    }
}

// ---------- MIDI / plugins ----------
bool Engine::addNote(int track, int64_t start, int64_t length, int pitch, int velocity) {
    bool ok = false;
    edit([&](Project& p) { for (auto& t : p.tracks) if (t.id == track) {
        Note n; n.start = start; n.length = std::max<int64_t>(1, length); n.pitch = (uint8_t)std::clamp(pitch, 0, 127); n.velocity = (uint8_t)std::clamp(velocity, 1, 127);
        t.notes.push_back(n); ok = true; } });
    return ok;
}
void Engine::setNotes(int track, std::vector<Note> notes) {
    edit([&](Project& p) { for (auto& t : p.tracks) if (t.id == track) t.notes = std::move(notes); });
}
void Engine::clearNotes(int track) { edit([&](Project& p) { for (auto& t : p.tracks) if (t.id == track) t.notes.clear(); }); }

// One id space for every plugin: track ids address the track's instrument, ids >= kFxIdBase address an insert effect (on a track or the master).
struct Endpoint { std::shared_ptr<PluginBridge>* bridge = nullptr; int* slot = nullptr; std::vector<AutoLane>* lanes = nullptr; };
static Endpoint findEndpoint(Project& p, int id) {
    Endpoint e;
    if (id >= kFxIdBase) {
        for (auto& f : p.masterFx) if (f.id == id) return Endpoint{&f.plugin, &f.pluginSlot, &f.lanes};
        for (auto& b : p.buses) for (auto& f : b.fx) if (f.id == id) return Endpoint{&f.plugin, &f.pluginSlot, &f.lanes};
        for (auto& t : p.tracks) for (auto& f : t.fx) if (f.id == id) return Endpoint{&f.plugin, &f.pluginSlot, &f.lanes};
    } else for (auto& t : p.tracks) if (t.id == id) return Endpoint{&t.plugin, &t.pluginSlot, &t.lanes};
    return e;
}
static std::shared_ptr<PluginBridge> bridgeOf(const std::shared_ptr<const Project>& p, int id) {
    Endpoint e = findEndpoint(const_cast<Project&>(*p), id);
    return e.bridge ? *e.bridge : nullptr;
}

bool Engine::attachBridge(int id, std::shared_ptr<PluginBridge> bridge) {
    bool ok = false;
    edit([&](Project& p) {
        bool used[kMaxPlugins] = {};
        for (auto& t : p.tracks) { if (t.plugin && t.pluginSlot >= 0) used[t.pluginSlot] = true; for (auto& f : t.fx) if (f.plugin && f.pluginSlot >= 0) used[f.pluginSlot] = true; }
        for (auto& f : p.masterFx) if (f.plugin && f.pluginSlot >= 0) used[f.pluginSlot] = true;
        for (auto& b : p.buses) for (auto& f : b.fx) if (f.plugin && f.pluginSlot >= 0) used[f.pluginSlot] = true;
        Endpoint e = findEndpoint(p, id);
        if (!e.bridge) return;
        int slot = *e.slot >= 0 ? *e.slot : -1;
        for (int s = 0; slot < 0 && s < kMaxPlugins; s++) if (!used[s]) slot = s;
        if (slot < 0) return;
        *e.bridge = bridge; *e.slot = slot; ok = true;
    });
    return ok;
}
bool Engine::createPluginBridge(int id, const std::string& path, int maxFrames) {
    auto bridge = std::make_shared<PluginBridge>();
    if (!bridge->create(path, kProjectRate, maxFrames)) return false;
    return attachBridge(id, bridge);
}
bool Engine::createNativePlugin(int id, const std::string& pluginId) {
    std::unique_ptr<zdsp::Processor> proc(zdsp::createProcessor(pluginId));
    if (!proc) return false;
    auto bridge = std::make_shared<PluginBridge>();
    if (!bridge->createNative(std::move(proc), kProjectRate, kCallbackFrames)) return false;
    return attachBridge(id, bridge);
}
bool Engine::setPluginState(int id, const std::string& key, const std::string& value) {
    std::shared_ptr<PluginBridge> b; { std::lock_guard<std::mutex> lk(editMutex_); b = bridgeOf(current_, id); }
    return b && b->native() && b->native()->setState(key, value);
}
std::string Engine::getPluginState(int id, const std::string& key) {
    std::shared_ptr<PluginBridge> b; { std::lock_guard<std::mutex> lk(editMutex_); b = bridgeOf(current_, id); }
    return b && b->native() ? b->native()->getState(key) : "";
}
std::string Engine::paramGroup(int id, int index) {
    std::shared_ptr<PluginBridge> b; { std::lock_guard<std::mutex> lk(editMutex_); b = bridgeOf(current_, id); }
    return b ? b->paramGroup(index) : "";
}
std::string Engine::paramTextAt(int id, int index, float norm) {
    std::shared_ptr<PluginBridge> b; { std::lock_guard<std::mutex> lk(editMutex_); b = bridgeOf(current_, id); }
    return b ? b->paramTextAt(index, norm) : "";
}
int Engine::pluginTelemetry(int id, int what, float* out, int cap) {
    std::shared_ptr<PluginBridge> b; { std::lock_guard<std::mutex> lk(editMutex_); b = bridgeOf(current_, id); }
    return b && b->native() ? b->native()->telemetry(what, out, cap) : 0;
}
bool Engine::detachPlugin(int id) {
    bool ok = false;
    edit([&](Project& p) { Endpoint e = findEndpoint(p, id); if (e.bridge && *e.bridge) { e.bridge->reset(); *e.slot = -1; ok = true; } });
    return ok;
}
int Engine::pluginState(int id) {
    std::lock_guard<std::mutex> lk(editMutex_);
    auto b = bridgeOf(current_, id);
    if (!b) return 0;
    uint32_t s = b->hostState();
    return s == ZK_HOST_READY ? 2 : s == ZK_HOST_ERROR ? 3 : 1;
}
std::string Engine::pluginName(int id) {
    std::lock_guard<std::mutex> lk(editMutex_);
    auto b = bridgeOf(current_, id); return b ? b->pluginName() : "";
}
int Engine::pluginKind(int id) {
    std::lock_guard<std::mutex> lk(editMutex_);
    auto b = bridgeOf(current_, id); return b ? (int)b->kind() : 0;
}
Engine::PluginStats Engine::pluginStats(int id) {
    std::lock_guard<std::mutex> lk(editMutex_);
    if (auto b = bridgeOf(current_, id)) return {b->late(), b->blocks(), b->waitedBlocks(), b->waitNsTotal(), b->waitNsMax()};
    return {0, 0, 0, 0, 0};
}

// ---------- insert effects ----------
int Engine::addFx(int track, int position) {
    int id = 0;
    edit([&](Project& p) {
        std::vector<FxState>* v = nullptr;
        if (track == 0) v = &p.masterFx; else if (track < 0) { if (auto* b = findBus(p, -track)) v = &b->fx; } else for (auto& t : p.tracks) if (t.id == track) v = &t.fx;
        if (!v || (int)v->size() >= kMaxFx) return;
        FxState f; f.id = nextFxId_++; id = f.id;
        position = std::clamp(position, 0, (int)v->size());
        v->insert(v->begin() + position, std::move(f));
    });
    return id;
}
// Finds the chain that holds an effect (callers hold a mutable project copy).
static std::vector<FxState>* chainOf(Project& p, int fxId, int* index) {
    auto look = [&](std::vector<FxState>& v) -> std::vector<FxState>* { for (size_t i = 0; i < v.size(); i++) if (v[i].id == fxId) { *index = (int)i; return &v; } return nullptr; };
    if (auto* v = look(p.masterFx)) return v;
    for (auto& b : p.buses) if (auto* v = look(b.fx)) return v;
    for (auto& t : p.tracks) if (auto* v = look(t.fx)) return v;
    return nullptr;
}
bool Engine::removeFx(int fxId) {
    bool ok = false;
    edit([&](Project& p) { int i = 0; if (auto* v = chainOf(p, fxId, &i)) { v->erase(v->begin() + i); ok = true; } });
    return ok;
}
bool Engine::moveFx(int fxId, int newIndex) {
    bool ok = false;
    edit([&](Project& p) {
        int i = 0; auto* v = chainOf(p, fxId, &i); if (!v) return;
        newIndex = std::clamp(newIndex, 0, (int)v->size() - 1);
        FxState f = std::move((*v)[i]); v->erase(v->begin() + i); v->insert(v->begin() + newIndex, std::move(f)); ok = true;
    });
    return ok;
}
bool Engine::setFxBypass(int fxId, bool bypass) {
    bool ok = false;
    edit([&](Project& p) { int i = 0; if (auto* v = chainOf(p, fxId, &i)) { (*v)[i].bypass = bypass; ok = true; } });
    return ok;
}

int Engine::paramCount(int track) { std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track); return b && b->ready() ? b->paramCount() : 0; }
bool Engine::paramInfo(int track, int index, std::string* name, std::string* units, float* def, int* steps) {
    std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track); if (!b) return false;
    const ZkParamInfo* pi = b->paramInfo(index); if (!pi) return false;
    *name = std::string(pi->name, strnlen(pi->name, sizeof pi->name)); *units = std::string(pi->units, strnlen(pi->units, sizeof pi->units));
    *def = pi->default_norm; *steps = (int)pi->step_count; return true;
}
float Engine::paramValue(int track, int index) { std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track); return b ? b->paramValue(index) : 0.f; }
std::string Engine::paramText(int track, int index) { std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track); return b ? b->paramText(index) : ""; }
void Engine::guiCommand(int track, int cmd, int x, int y) { std::lock_guard<std::mutex> lk(editMutex_); if (auto b = bridgeOf(current_, track)) b->guiCommand((uint32_t)cmd, x, y); }
int Engine::guiState(int track, int* w, int* h, int* x, int* y) {
    std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track);
    *w = *h = *x = *y = 0; if (!b) return 0; b->guiSize(w, h, x, y); return (int)b->guiState();
}
void Engine::setParam(int track, int index, float value) {
    value = std::clamp(value, 0.f, 1.f);
    { std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track); if (b) b->setParamValue(index, value); }
    std::lock_guard<std::mutex> lk(paramMutex_);
    uint32_t h = paramHead_.load(std::memory_order_relaxed);
    if (h - paramTail_.load(std::memory_order_acquire) >= paramQ_.size()) return;  // queue full: drop (UI will resend on next move)
    paramQ_[h % paramQ_.size()] = ParamEvt{track, index, value};
    paramHead_.store(h + 1, std::memory_order_release);
}
void Engine::liveNote(int track, bool on, int pitch, int velocity) {
    std::lock_guard<std::mutex> lk(liveMutex_);
    uint32_t h = liveHead_.load(std::memory_order_relaxed);
    if (h - liveTail_.load(std::memory_order_acquire) >= live_.size()) return;  // queue full: drop
    live_[h % live_.size()] = LiveEvt{track, (uint8_t)on, (uint8_t)pitch, (uint8_t)velocity};
    liveHead_.store(h + 1, std::memory_order_release);
}

// Notes whose on/off fall inside the window [pos, pos+frames), following loop wraps. Offs are emitted before ons.
// Slide notes: a slide note S glides from the latest-starting note P that is still sounding (or just ending) when S starts. P is not released at its own end;
// S's own Note Off ends the voice (the plugin retargets the voice to S's pitch).
static bool hasSlideSource(const TrackState& t, const Note& s) {
    for (auto& p : t.notes) if (&p != &s && p.start < s.start && p.start + p.length >= s.start) return true;
    return false;
}
static bool slidesInto(const TrackState& t, const Note& p) {
    const int64_t end = p.start + p.length;
    for (auto& s : t.notes) {
        if (!s.slide || &s == &p || !(p.start < s.start) || end < s.start) continue;
        // p is the source of s when no other note starts later than p and still covers s's start
        bool latest = true; for (auto& q : t.notes) if (&q != &s && &q != &p && q.start < s.start && q.start > p.start && q.start + q.length >= s.start) { latest = false; break; }
        if (latest) return true;
    }
    return false;
}
void Engine::windowEvents(const Project& p, const TrackState& t, int64_t pos, int frames, ZkEvent* ev, int& n) {
    int done = 0;
    while (done < frames && n < ZK_MAX_EVENTS - 2) {
        int seg = frames - done;
        if (p.loop && pos < p.loopEnd) seg = (int)std::min<int64_t>(seg, p.loopEnd - pos);
        const bool slides = t.plugin && t.plugin->supportsSlide();
        for (auto& nt : t.notes) {
            int64_t end = nt.start + nt.length;
            if (slides && slidesInto(t, nt)) continue;   // a slide note takes over this note's voice: it must not be released at its end
            if (end >= pos && end < pos + seg && end > nt.start && n < ZK_MAX_EVENTS) ev[n++] = ZkEvent{(uint32_t)(done + (end - pos)), ZK_NOTE_OFF, nt.channel, nt.pitch, 0};
        }
        for (auto& nt : t.notes)
            if (nt.start >= pos && nt.start < pos + seg && n < ZK_MAX_EVENTS) ev[n++] = ZkEvent{(uint32_t)(done + (nt.start - pos)), ZK_NOTE_ON, nt.channel, nt.pitch, nt.velocity, nt.pan, nt.cutoff, nt.res, nt.fine, nt.release, (uint8_t)(slides && nt.slide && hasSlideSource(t, nt) ? 1 : 0)};
        done += seg; pos += seg;
        if (p.loop && pos >= p.loopEnd) { pos = p.loopStart; if (done < frames) ev[n++] = ZkEvent{(uint32_t)done, ZK_ALL_NOTES_OFF, 0, 0, 0}; }
    }
}

void Engine::pluginCollect(const Project& p, MixState& ms, int frames, int64_t maxWaitNs) {
    for (auto& t : p.tracks) if (t.plugin && t.pluginSlot >= 0 && !t.frozen) {
        float* dst = ms.plugOut[t.pluginSlot];
        if (!t.plugin->ready() || !t.plugin->collect(dst, frames, maxWaitNs)) memset(dst, 0, sizeof(float) * 2 * frames);
    }
}

static float laneValueAt(const AutoLane& l, int64_t pos) {
    const size_t n = l.frames.size();
    if (n == 0) return 0.f;
    if (pos <= l.frames[0]) return l.values[0];
    if (pos >= l.frames[n - 1]) return l.values[n - 1];
    size_t hi = std::upper_bound(l.frames.begin(), l.frames.end(), pos) - l.frames.begin(), lo = hi - 1;
    const double span = (double)(l.frames[hi] - l.frames[lo]);
    if (span <= 0) return l.values[hi];
    const uint8_t shape = lo < l.shapes.size() ? l.shapes[lo] : 0;
    if (shape == 1) return l.values[lo];
    double t = (double)(pos - l.frames[lo]) / span;
    if (shape == 2) t = t * t * (3.0 - 2.0 * t);
    return l.values[lo] + (float)((l.values[hi] - l.values[lo]) * t);
}
// Appends the automation changes for the block starting at [pos]. [last] holds the last value sent per lane (NaN = send now).
static void appendAutomation(const std::vector<AutoLane>& lanes, int64_t pos, float* last, ZkParamChange* ch, int& nc) {
    for (size_t i = 0; i < lanes.size() && i < (size_t)kMaxLanes; i++) {
        if (nc >= ZK_MAX_PARAM_CHANGES) break;
        const float v = laneValueAt(lanes[i], pos);
        if (std::isnan(last[i]) || std::fabs(v - last[i]) > 0.0005f) { ch[nc++] = ZkParamChange{(uint32_t)lanes[i].param, v}; last[i] = v; }
    }
}
void Engine::setAutomation(int track, std::vector<AutoLane> lanes) {
    edit([&](Project& p) { Endpoint e = findEndpoint(p, track); if (e.lanes) *e.lanes = std::move(lanes); });
}
int Engine::pollEdits(int track, ZkParamChange* out, int max) {
    std::lock_guard<std::mutex> lk(editMutex_); auto b = bridgeOf(current_, track); return b ? b->pollEdits(out, max) : 0;
}

void Engine::pluginSubmit(const Project& p, int64_t windowPos, int frames, bool playing, bool jumped, bool) {
    // drain live MIDI (consumer side)
    LiveEvt le[64]; int nl = 0;
    uint32_t tail = liveTail_.load(std::memory_order_relaxed), head = liveHead_.load(std::memory_order_acquire);
    while (tail != head && nl < 64) le[nl++] = live_[tail++ % live_.size()];
    liveTail_.store(tail, std::memory_order_release);
    const int panicAll = panicAll_.load(std::memory_order_relaxed);
    if (panicAll > 0) panicAll_.store(panicAll - 1, std::memory_order_relaxed);
    for (auto& t : p.tracks) if (t.plugin && t.pluginSlot >= 0 && t.plugin->ready() && !t.frozen) {
        ZkEvent ev[ZK_MAX_EVENTS]; int n = 0;
        ZkParamChange ch[ZK_MAX_PARAM_CHANGES]; int nc = 0;
        for (int i = 0; i < np_; i++) if (pe_[i].track == t.id && nc < ZK_MAX_PARAM_CHANGES) ch[nc++] = ZkParamChange{(uint32_t)pe_[i].index, pe_[i].value};
        if (!playing || jumped) for (int k = 0; k < kMaxLanes; k++) autoLast_[t.pluginSlot][k] = NAN;  // re-send values when playback (re)starts
        if (playing) appendAutomation(t.lanes, windowPos, autoLast_[t.pluginSlot], ch, nc);
        const int pt = t.id < kMaxTracks ? panicTrack_[t.id].load(std::memory_order_relaxed) : 0;
        const bool hard = t.id < kMaxTracks && hardTrack_[t.id].exchange(0, std::memory_order_relaxed) != 0;
        if (jumped || pt > 0 || (!playing && panicAll > 0)) ev[n++] = ZkEvent{0, ZK_ALL_NOTES_OFF, 0, (uint8_t)(hard ? 1 : 0), 0};
        if (pt > 0) panicTrack_[t.id].store(pt - 1, std::memory_order_relaxed);
        if (playing) windowEvents(p, t, windowPos, frames, ev, n);
        for (int i = 0; i < nl; i++) if (le[i].track == t.id && n < ZK_MAX_EVENTS)
            ev[n++] = ZkEvent{0, le[i].on ? (uint8_t)ZK_NOTE_ON : (uint8_t)ZK_NOTE_OFF, 0, le[i].pitch, le[i].vel};
        { double bpm, ppq; tempoAt(&p, windowPos, bpm, ppq); t.plugin->setTransport(bpm, playing ? ppq : -1.0, playing); }
        t.plugin->submit(ev, n, frames, ch, nc);
    }
}

// ---------- realtime ----------
oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream*, void* data, int32_t frames) {
    auto t0 = std::chrono::steady_clock::now();
    float* out = static_cast<float*>(data);
    uint64_t v = version_.load(std::memory_order_acquire);
    if (v != audioVersion_) { audioProject_ = published_.load(std::memory_order_acquire); audioVersion_ = v; ackVersion_.store(v, std::memory_order_release); }
    const Project* p = audioProject_;
    const bool playing = playing_.load(std::memory_order_relaxed);
    int64_t pos = pos_.load(std::memory_order_relaxed);
    if (exporting_.load(std::memory_order_acquire)) {
        memset(out, 0, sizeof(float) * 2 * frames); pluginWasPlaying_ = false; pluginExpectedPos_ = -1;
    } else if (p && frames <= ZK_MAX_FRAMES) {
        const bool jumped = (playing != pluginWasPlaying_) || (playing && pos != pluginExpectedPos_);
        // Plugin block requested one callback ago (bounded wait: never stall the audio thread).
        const int64_t budgetNs = (int64_t)(0.5 * 1e9 * frames / kProjectRate);
        drainParams();
        pluginCollect(*p, audioMix_, frames, budgetNs);
        if (jumped) for (int s = 0; s < kMaxPlugins; s++) memset(audioMix_.plugOut[s], 0, sizeof(float) * 2 * frames);
        // Plugins run one block behind: the block submitted now is heard on the next callback. When playback starts or the
        // position jumps, the first timeline block would never reach the plugin (a note at beat 0 stayed silent). So that
        // callback is a silent pre-roll: the position does not advance and the plugin is sent the block at `pos` itself.
        const bool preroll = playing && jumped;
        audioMix_.playClips = playing && !preroll;
        int64_t next = pos;
        if (audioMix_.playClips) next = renderBlockDry(*p, pos, frames, audioMix_);
        else { for (size_t ti = 0; ti < p->tracks.size() && ti < (size_t)kMaxTracks; ti++) memset(audioMix_.dry[ti], 0, sizeof(float) * 2 * frames); accumulateDry(*p, pos, frames, audioMix_, 0); }
        posRing_[blockCounter_ & 31] = audioMix_.playClips ? pos : -1;
        fxRealtime(*p, audioMix_, frames, budgetNs);
        mixTracks(*p, frames, out, audioMix_, meters_, false, 0, budgetNs);
        masterFxRealtime(*p, audioMix_, out, frames, budgetNs);
        masterOut(*p, frames, out, audioMix_, meters_);
        // --- anti-click ---
        constexpr int kDeclickFrames = 480, kFadeInFrames = 384;   // 10 ms / 8 ms at 48 kHz
        if (jumped) {
            for (int ch = 0; ch < 2; ch++) { declickOff_[ch] = lastOut_[ch] - out[ch]; declickStep_[ch] = declickOff_[ch] / kDeclickFrames; }
            declickLeft_ = kDeclickFrames;
        }
        if (preroll) fadeInLeft_ = kFadeInFrames;
        else if (fadeInLeft_ > 0 && !jumped) {
            for (int i = 0; i < frames; i++) {
                const float g = fadeInLeft_ > 0 ? 1.f - (float)fadeInLeft_ / kFadeInFrames : 1.f;
                out[i * 2] *= g; out[i * 2 + 1] *= g;
                if (fadeInLeft_ > 0) fadeInLeft_--;
            }
        }
        if (declickLeft_ > 0) {
            for (int i = 0; i < frames && declickLeft_ > 0; i++, declickLeft_--) {
                out[i * 2] += declickOff_[0]; out[i * 2 + 1] += declickOff_[1];
                declickOff_[0] -= declickStep_[0]; declickOff_[1] -= declickStep_[1];
            }
        }
        lastOut_[0] = out[(frames - 1) * 2]; lastOut_[1] = out[(frames - 1) * 2 + 1];
        blockCounter_++;
        pos_.store(next, std::memory_order_relaxed);
        pluginSubmit(*p, preroll ? pos : next, frames, playing, jumped, true);
        pluginWasPlaying_ = playing; pluginExpectedPos_ = next;
    } else {
        memset(out, 0, sizeof(float) * 2 * frames);
    }
    int64_t ns = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count();
    callbacks_.fetch_add(1, std::memory_order_relaxed);
    cbNsTotal_.fetch_add(ns, std::memory_order_relaxed);
    int64_t m = cbNsMax_.load(std::memory_order_relaxed);
    while (ns > m && !cbNsMax_.compare_exchange_weak(m, ns)) {}
    return oboe::DataCallbackResult::Continue;
}
bool Engine::onError(oboe::AudioStream*, oboe::Result r) { LOGE("stream error %s", oboe::convertToText(r)); return false; }
void Engine::onErrorAfterClose(oboe::AudioStream*, oboe::Result r) {
    LOGE("stream closed after error %s: restarting", oboe::convertToText(r));
    stream_.reset(); start();
}

bool Engine::start() {
    if (stream_) return true;
    audioMix_.scratch.assign(8192 * 2, 0.f);  // pre-size so the audio thread never allocates
    auto open = [&](int device, std::shared_ptr<oboe::AudioStream>& s) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(exclusive_ ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)->setChannelCount(2)
            ->setSampleRate(kProjectRate)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setUsage(oboe::Usage::Media)->setContentType(oboe::ContentType::Music)
            ->setFramesPerDataCallback(kCallbackFrames)
            ->setDataCallback(this)->setErrorCallback(this);
        if (device > 0) b.setDeviceId(device);
        return b.openStream(s);
    };
    std::shared_ptr<oboe::AudioStream> s;
    oboe::Result r = open(outDevice_, s);
    if (r != oboe::Result::OK && outDevice_ > 0) {  // the chosen device is gone: fall back to the default output
        LOGE("openStream on device %d failed: %s; using the default", outDevice_, oboe::convertToText(r)); s.reset(); r = open(0, s);
    }
    if (r != oboe::Result::OK) { LOGE("openStream failed: %s", oboe::convertToText(r)); return false; }
    s->setBufferSizeInFrames(std::max(s->getFramesPerBurst() * bufferMult_, kCallbackFrames * bufferMult_));
    r = s->requestStart();
    if (r != oboe::Result::OK) { LOGE("requestStart failed: %s", oboe::convertToText(r)); return false; }
    stream_ = s;
    LOGI("stream: rate=%d burst=%d buffer=%d sharing=%d api=%d", s->getSampleRate(), s->getFramesPerBurst(), s->getBufferSizeInFrames(), (int)s->getSharingMode(), (int)s->getAudioApi());
    return true;
}
void Engine::stop() {
    if (!stream_) return;
    auto s = stream_; stream_.reset();
    s->requestStop(); s->close();
}
Engine::Stats Engine::stats() {
    Stats st{}; auto s = stream_; if (!s) return st;
    st.sampleRate = s->getSampleRate(); st.burst = s->getFramesPerBurst(); st.bufferSize = s->getBufferSizeInFrames();
    auto x = s->getXRunCount(); st.xruns = x ? x.value() : -1;
    int64_t n = callbacks_.load(); st.callbacks = n;
    st.avgUs = n ? (float)(cbNsTotal_.load() / n) / 1000.f : 0.f; st.maxUs = cbNsMax_.load() / 1000.f;
    st.latencyMs = 1000.f * st.bufferSize / std::max(1, st.sampleRate);
    st.exclusive = s->getSharingMode() == oboe::SharingMode::Exclusive;
    return st;
}
}  // namespace zedok
