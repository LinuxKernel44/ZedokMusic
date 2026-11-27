// ZedokMusic native audio engine: project snapshot -> mixer -> Oboe output (and offline WAV render).
#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <oboe/Oboe.h>
#include <array>
#include "plugin_bridge.h"
#include "wav.h"

namespace zedok {

constexpr int kProjectRate = 48000;
constexpr int kMaxTracks = 256;
constexpr int kCallbackFrames = 128;  // fixed audio callback size; also the plugin bridge block size
constexpr int kCallbackFramesFx = 128;  // same, for the effect pipeline buffers (declared before kCallbackFrames users)

using AudioData = std::shared_ptr<const std::vector<float>>;  // interleaved stereo @ kProjectRate

struct Clip {
    int id = 0;
    AudioData audio;
    int64_t start = 0;   // timeline position (frames)
    int64_t offset = 0;  // offset into the audio (frames)
    int64_t length = 0;  // frames played
    float gain = 1.f;
};

struct Note {
    int64_t start = 0, length = 0;  // timeline frames
    uint8_t pitch = 60, velocity = 100, channel = 0;
    int8_t pan = 0, cutoff = 0, res = 0, fine = 0, release = 0;   // per-note properties, see ZkEvent
};

constexpr int kMaxPlugins = 64;   // instruments and effects share these slots (one Wine process each)
constexpr int kMaxFx = 10;        // insert effects per track, and on the master
constexpr int kFxIdBase = 1000;   // effect ids start here, so one id space addresses instruments (track ids) and effects

// Parameter automation: piecewise-linear lane of a plugin parameter, points sorted by frame.
struct AutoLane { int param = 0; std::vector<int64_t> frames; std::vector<float> values; std::vector<uint8_t> shapes; };  // shape of the segment starting at each point: 0 linear, 1 step, 2 smooth
constexpr int kMaxLanes = 32;
constexpr int kMaxBuses = 16;     // buses in one project (a bus sums the tracks routed to it, then goes to the master or to another bus)
constexpr int kMaxBusId = 64;     // bus ids are never reused while the app runs, so this bounds the buses created in one session

// A mixing bus: tracks (and other buses) are routed into it; it has the same gain / pan / width / mute as a track and its own destination.
// Effect chains, sends and sidechain come with phase 4 of docs/ROADMAP-FL-PARITY.md; this is the routing graph they will hang on.
struct BusState {
    int id = 0;
    float gain = 1.f, pan = 0.f, width = 1.f;
    bool mute = false;
    int output = 0;                                 // 0 = the master, else the id of another bus (never one that leads back here)
};

// One insert effect: a VST3 plugin in its own Wine host, processing the track's (or the master's) audio.
struct FxState {
    int id = 0;
    std::shared_ptr<PluginBridge> plugin;
    int pluginSlot = -1;                            // index into MixState::plugOut
    bool bypass = false;
    std::vector<AutoLane> lanes;
};

struct TrackState {
    int id = 0;
    std::vector<Clip> clips;
    std::vector<Note> notes;                        // MIDI notes driving the track's plugin
    std::vector<AutoLane> lanes;                    // parameter automation
    std::shared_ptr<PluginBridge> plugin;           // optional Wine-hosted instrument
    int pluginSlot = -1;                            // index into MixState::plugOut
    std::vector<FxState> fx;                        // insert effects, in series, after the clips and the instrument
    float gain = 1.f, pan = 0.f;
    float width = 1.f;                              // stereo width: 0 mono, 1 unchanged, 2 twice as wide (side signal gain)
    bool mute = false, solo = false;
    int output = 0;                                 // 0 = the master, else the id of the bus this track is mixed into
};

// Immutable snapshot, published by the UI thread and read by the audio thread.
struct Project {
    std::vector<TrackState> tracks;
    std::vector<FxState> masterFx;                  // insert effects on the summed mix
    std::vector<BusState> buses;
    std::vector<int> busOrder;                      // indices into `buses`, every bus after all the buses that feed it (computed by Engine::planRouting)
    float masterGain = 1.f;
    float masterWidth = 1.f;
    bool loop = false;
    int64_t loopStart = 0, loopEnd = 0;
};

// Per-render mutable state (gain smoothing, meters). One per render context.
// Pipeline state of one effect chain (audio thread only). Effects run one callback behind each other: the result of the effect
// at position i for block P arrives i+1 callbacks after P was rendered. `hold` delays a bypassed effect by the same one callback;
// `fin` delays shorter chains so every track lines up with the longest one (plugin delay compensation).
struct FxChain {
    float hold[kMaxFx][kCallbackFramesFx * 2];
    float fin[kMaxFx + 1][kCallbackFramesFx * 2];
    int finPos = 0;
    int sig = -1;  // chain length and compensation it was set up for; a change clears the buffers
};

struct MixState {
    float plugOut[kMaxPlugins][ZK_MAX_FRAMES * 2];  // block returned by each plugin (interleaved)
    float dry[kMaxTracks][ZK_MAX_FRAMES * 2];       // each track's own signal (clips + instrument), then after its effects
    float stage[kMaxFx][kCallbackFramesFx * 2];     // scratch: outputs of the effects of the chain being processed
    FxChain chain[kMaxTracks];
    FxChain masterChain;
    int latencyBlocks = 0;                          // longest track chain (callbacks), set by Engine::fxRealtime
    float smoothGain[kMaxTracks];
    float smoothWidth[kMaxTracks];
    float bus[kMaxBuses][ZK_MAX_FRAMES * 2];        // what each bus has received so far (by position in Project::buses)
    float smoothBusGain[kMaxBusId + 1];
    float smoothBusWidth[kMaxBusId + 1];
    float smoothMaster = 1.f, smoothMasterWidth = 1.f;
    bool init = false;
    bool playClips = true;  // false while the transport is stopped (plugins still sound for live MIDI)
    std::vector<float> scratch;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Engine();
    ~Engine();
    bool start();
    /** Track ids are never reused while the app runs (a project switch removes every track); this starts again at 1 once none is left. */
    int trackIdsUsed() const { return nextTrackId_ - 1; }
    bool resetTrackIds();
    /** A removed plugin's host only ends when its bridge is freed, i.e. when the audio thread has moved past every older snapshot
     *  that still holds it. Without another edit that never happened: publish no-op versions (in the background) until it did. */
    void releaseRetired();
    /** Audio output settings, used by the next start(): AAudio device id (0 = automatic), buffer = this many bursts, exclusive mode. */
    void configure(int outDevice, int bufferMult, bool exclusive) { outDevice_ = outDevice; bufferMult_ = std::clamp(bufferMult, 2, 8); exclusive_ = exclusive; }
    void stop();
    bool running() const { return stream_ != nullptr; }

    // --- editing (UI thread) ---
    int addTrack();
    bool removeTrack(int id);
    int addClip(int track, AudioData audio, int64_t start, int64_t offset, int64_t length, float gain);  // returns clip id, 0 on failure
    int loadClip(int track, const std::string& path, int64_t start, int64_t* framesOut);               // returns clip id, 0 on failure
    bool removeClip(int clipId);
    bool moveClip(int clipId, int64_t start);
    bool setClipGain(int clipId, float gain);
    void setTrackGain(int id, float g);
    void setTrackPan(int id, float p);
    void setTrackWidth(int id, float w);
    void setMasterWidth(float w);
    void setTrackMute(int id, bool m);
    void setTrackSolo(int id, bool s);
    void setMasterGain(float g);
    void setLoop(bool on, int64_t s, int64_t e);

    // --- routing (UI thread): tracks and buses are mixed into buses, buses into the master ---
    int addBus();                                 // returns the bus id, 0 when the limit is reached
    bool removeBus(int busId);                    // whatever fed it goes to the master again
    void setBusGain(int busId, float g);
    void setBusPan(int busId, float p);
    void setBusWidth(int busId, float w);
    void setBusMute(int busId, bool m);
    bool setTrackOutput(int track, int busId);    // busId 0 = master; false for an unknown track or bus
    bool setBusOutput(int busId, int destBusId);  // false for an unknown bus, or when it would make a loop

    // --- MIDI / plugins ---
    bool addNote(int track, int64_t start, int64_t length, int pitch, int velocity);
    void clearNotes(int track);
    void setNotes(int track, std::vector<Note> notes);  // replaces all notes of a track in one snapshot
    // Creates the shared bridge file for a track; the caller then launches the Wine host on that file.
    bool createPluginBridge(int track, const std::string& path, int maxFrames);
    // Built-in (native) plugin by id ("hybrid", ...): same endpoints as a Wine plugin, no process, ready at once. Returns false if unknown or no slot.
    bool createNativePlugin(int track, const std::string& pluginId);
    bool setPluginState(int id, const std::string& key, const std::string& value);
    std::string getPluginState(int id, const std::string& key);
    std::string paramGroup(int id, int index);                // section a built-in plugin's parameter belongs to
    std::string paramTextAt(int id, int index, float norm);   // display text of a value the parameter does not have now (choice labels)
    int pluginTelemetry(int id, int what, float* out, int cap);   // plugin-specific display data
    void setTempo(double bpm) { tempo_.store(bpm < 20.0 ? 20.0 : bpm > 400.0 ? 400.0 : bpm); }
    bool detachPlugin(int track);
    int pluginState(int track);  // 0 none, 1 starting, 2 ready, 3 error
    std::string pluginName(int track);
    void liveNote(int track, bool on, int pitch, int velocity);  // MIDI keyboard / on-screen keys (any thread)
    // --- insert effects (track 0 = the master). Ids >= kFxIdBase work with every plugin call above and below (bridge, params, editor...) ---
    int addFx(int track, int position);          // returns the effect id, 0 on failure (limit reached)
    bool removeFx(int fxId);
    bool moveFx(int fxId, int newIndex);
    bool setFxBypass(int fxId, bool bypass);
    int pluginKind(int id);                       // 0 unknown, 1 instrument, 2 effect (as reported by the loaded plugin)
    // --- plugin parameters (normalized 0..1) ---
    int paramCount(int track);
    bool paramInfo(int track, int index, std::string* name, std::string* units, float* def, int* steps);
    float paramValue(int track, int index);
    std::string paramText(int track, int index);
    void setParam(int track, int index, float value);  // any thread; applied at the next block
    // --- parameter automation ---
    void setAutomation(int track, std::vector<AutoLane> lanes);
    int pollEdits(int track, ZkParamChange* out, int max);  // edits made inside the plugin editor
    // --- plugin editor window (hosted by Wine, shown through the X framebuffer) ---
    void guiCommand(int track, int cmd, int x, int y);   // 1 open (window at x,y on the X screen), 2 close
    int guiState(int track, int* w, int* h, int* x, int* y);  // 0 closed, 1 open, 2 failed
    struct PluginStats { int64_t late, blocks, waited, waitNsTotal, waitNsMax; };
    PluginStats pluginStats(int track);

    // --- transport ---
    void play() { playing_.store(true); }
    /** Stops the transport and releases every sounding note: ALL_NOTES_OFF is sent for several blocks, because one block can be lost
     *  when a plugin host is late, which used to leave a note sounding for good. Pressing Stop again repeats it. */
    void pause() { playing_.store(false); panicAll_.store(12); }
    /** Releases the notes of one instrument track once (e.g. a sounding note was deleted or moved while playing). */
    void panic(int track) { if (track > 0 && track < kMaxTracks) panicTrack_[track].store(2); }
    /** Hard stop: every voice and effect tail of the track's plugin is dropped (after a short fade). */
    void silence(int track) { if (track > 0 && track < kMaxTracks) { hardTrack_[track].store(1); panicTrack_[track].store(2); } }
    void seek(int64_t f) { pos_.store(f < 0 ? 0 : f); }
    bool playing() const { return playing_.load(); }
    int64_t position() const { return pos_.load(); }
    float peak(int track) const;
    int64_t projectLength() const;

    // --- offline render (any thread but the audio thread) ---
    bool renderToWav(const std::string& path, int64_t from, int64_t to, WavFormat fmt, int onlyTrack = 0);
    // Format codes: 0/1/2 = WAV 16/24/float, 4/5 = FLAC 16/24, 6/7 = MP3 320/192 kbps.
    bool renderEncoded(const std::string& path, int64_t from, int64_t to, int format, int onlyTrack = 0);
    bool renderMix(int64_t from, int64_t to, int onlyTrack, std::vector<float>& out);  // onlyTrack > 0: render just that track (a stem)

    struct Stats { int sampleRate, burst, bufferSize, xruns; float avgUs, maxUs, latencyMs; bool exclusive; int64_t callbacks; };
    Stats stats();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override;
    bool onError(oboe::AudioStream*, oboe::Result) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

private:
    template <class F> void edit(F&& f);  // copy-on-write publish
    void publish(std::shared_ptr<Project> p);
    // --- rendering (real-time safe) ---
    // Adds the clips (when ms.playClips) and the instrument output of the segment [pos, pos+n) to each track's dry buffer at `offset`.
    static void accumulateDry(const Project& p, int64_t pos, int n, MixState& ms, int offset);
    // Zeroes the dry buffers, then accumulates `frames` frames from `pos` (following loop wraps); returns the new position.
    static int64_t renderBlockDry(const Project& p, int64_t pos, int frames, MixState& ms);
    // Applies gain, pan, mute/solo to each track's dry buffer and sums into out (interleaved stereo); no master gain yet.
    static void mixTracks(const Project& p, int frames, float* out, MixState& ms, std::atomic<float>* meters);
    // Master gain and meter.
    /** Orders p.busOrder so that every bus comes after the buses routed into it. */
    static void planRouting(Project& p);
    static void masterOut(const Project& p, int frames, float* out, MixState& ms, std::atomic<float>* meters);
    // Effect chains, pipelined (audio callback): results arrive one callback later per effect; tracks are delay-compensated.
    void fxRealtime(const Project& p, MixState& ms, int frames, int64_t budgetNs);
    void masterFxRealtime(const Project& p, MixState& ms, float* out, int frames, int64_t budgetNs);
    // Effect chains, synchronous (offline render): every effect is waited for, so there is no added latency. `pos` is the block's position.
    void fxOffline(const Project& p, MixState& ms, int frames, int64_t pos);
    void fxOfflineMaster(const Project& p, float* out, int frames, int64_t pos);
    // Sends one block to an effect; automation and UI parameter edits are applied at the start of the block.
    void fxSubmit(const FxState& fx, const float* in, int frames, int64_t autoPos, float* autoLast, int64_t* lastPos);
    void drainParams();
    // Plugin pipeline: collect the block requested one callback ago, then request the next window.
    struct LiveEvt { int track; uint8_t on, pitch, vel; };
    struct ParamEvt { int track, index; float value; };
    static void pluginCollect(const Project& p, MixState& ms, int frames, int64_t maxWaitNs);
    void pluginSubmit(const Project& p, int64_t windowPos, int frames, bool playing, bool jumped, bool drainLive);
    static void windowEvents(const Project& p, const TrackState& t, int64_t pos, int frames, ZkEvent* ev, int& n);

    std::mutex editMutex_;
    // Ownership lives on the UI side; the audio thread only sees a raw pointer (no refcounting, no frees).
    std::shared_ptr<const Project> current_;          // guarded by editMutex_
    std::atomic<const Project*> published_{nullptr};
    std::atomic<uint64_t> version_{0};
    std::atomic<uint64_t> ackVersion_{0};              // last version the audio thread has switched to
    struct Retired { uint64_t version; std::shared_ptr<const Project> p; };
    std::vector<Retired> retired_;                     // old snapshots, freed only once acked by the audio thread
    int nextTrackId_ = 1;
    int nextClipId_ = 1;

    // audio-thread-only cache
    const Project* audioProject_ = nullptr;
    uint64_t audioVersion_ = ~0ull;
    MixState audioMix_;

    std::atomic<double> tempo_{120.0};
    bool attachBridge(int id, std::shared_ptr<PluginBridge> bridge);
    std::atomic<bool> playing_{false};
    std::atomic<int64_t> pos_{0};
    mutable std::atomic<float> meters_[kMaxTracks + 1];  // [kMaxTracks] = master
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<int64_t> callbacks_{0};
    int outDevice_ = 0, bufferMult_ = 2; bool exclusive_ = true;  // see configure()
    std::atomic<int> panicAll_{0}, panicTrack_[kMaxTracks], hardTrack_[kMaxTracks];  // blocks of ALL_NOTES_OFF still to send (see pause() / panic())
    std::atomic<int> releasePending_{0};
    std::atomic<bool> exporting_{false};  // offline render owns the plugin bridges: the audio callback stays away from them
    std::atomic<int64_t> cbNsTotal_{0};
    std::atomic<int64_t> cbNsMax_{0};
    std::atomic<bool> restart_{false};
    // live MIDI queue (single producer under liveMutex_, single consumer = audio thread)
    std::mutex liveMutex_;
    std::array<LiveEvt, 256> live_;
    std::atomic<uint32_t> liveHead_{0}, liveTail_{0};
    std::mutex paramMutex_;
    ParamEvt pe_[ZK_MAX_PARAM_CHANGES * 2]; int np_ = 0;                 // UI parameter edits drained at the start of this callback
    uint64_t blockCounter_ = 0; int64_t posRing_[32] = {};                // audio thread: timeline position of the last callbacks (-1 = not playing)
    float fxAutoLast_[kMaxPlugins][kMaxLanes];                           // audio thread: last automation value sent per effect slot
    int64_t fxLastPos_[kMaxPlugins] = {};                                // audio thread: position of the block last sent to each effect slot
    float fxAutoLastOff_[kMaxPlugins][kMaxLanes];                        // offline render: same, separate state
    int nextFxId_ = kFxIdBase;
    int nextBusId_ = 1;
    std::array<ParamEvt, 512> paramQ_;
    float autoLast_[kMaxPlugins][kMaxLanes];  // audio thread: last automation value sent per (plugin slot, lane); NaN = unsent
    std::atomic<uint32_t> paramHead_{0}, paramTail_{0};
    std::atomic<int64_t> lateTotal_{0};
    bool pluginWasPlaying_ = false;   // audio-thread-only
    // anti-click when the position jumps (seek while playing, start, stop): the step between the last sample and the new signal decays
    // over a few milliseconds, and the first real block after the silent pre-roll fades in. Audio-thread-only.
    float lastOut_[2] = {0.f, 0.f}, declickOff_[2] = {0.f, 0.f}, declickStep_[2] = {0.f, 0.f};
    int declickLeft_ = 0, fadeInLeft_ = 0;
    int64_t pluginExpectedPos_ = 0;   // audio-thread-only
};

}  // namespace zedok
