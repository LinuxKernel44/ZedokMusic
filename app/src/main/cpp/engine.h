// ZedokMusic native audio engine: project snapshot -> mixer -> Oboe output (and offline WAV render).
#pragma once
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
};

constexpr int kMaxPlugins = 16;

struct TrackState {
    int id = 0;
    std::vector<Clip> clips;
    std::vector<Note> notes;                        // MIDI notes driving the track's plugin
    std::shared_ptr<PluginBridge> plugin;           // optional Wine-hosted instrument
    int pluginSlot = -1;                            // index into MixState::plugOut
    float gain = 1.f, pan = 0.f;
    bool mute = false, solo = false;
};

// Immutable snapshot, published by the UI thread and read by the audio thread.
struct Project {
    std::vector<TrackState> tracks;
    float masterGain = 1.f;
    bool loop = false;
    int64_t loopStart = 0, loopEnd = 0;
};

// Per-render mutable state (gain smoothing, meters). One per render context.
struct MixState {
    float plugOut[kMaxPlugins][ZK_MAX_FRAMES * 2];  // block returned by each plugin (interleaved)
    float smoothGain[kMaxTracks];
    float smoothMaster = 1.f;
    bool init = false;
    bool playClips = true;  // false while the transport is stopped (plugins still sound for live MIDI)
    std::vector<float> scratch;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Engine();
    ~Engine();
    bool start();
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
    void setTrackMute(int id, bool m);
    void setTrackSolo(int id, bool s);
    void setMasterGain(float g);
    void setLoop(bool on, int64_t s, int64_t e);

    // --- MIDI / plugins ---
    bool addNote(int track, int64_t start, int64_t length, int pitch, int velocity);
    void clearNotes(int track);
    void setNotes(int track, std::vector<Note> notes);  // replaces all notes of a track in one snapshot
    // Creates the shared bridge file for a track; the caller then launches the Wine host on that file.
    bool createPluginBridge(int track, const std::string& path, int maxFrames);
    bool detachPlugin(int track);
    int pluginState(int track);  // 0 none, 1 starting, 2 ready, 3 error
    std::string pluginName(int track);
    void liveNote(int track, bool on, int pitch, int velocity);  // MIDI keyboard / on-screen keys (any thread)
    // --- plugin parameters (normalized 0..1) ---
    int paramCount(int track);
    bool paramInfo(int track, int index, std::string* name, std::string* units, float* def, int* steps);
    float paramValue(int track, int index);
    std::string paramText(int track, int index);
    void setParam(int track, int index, float value);  // any thread; applied at the next block
    struct PluginStats { int64_t late, blocks, waited, waitNsTotal, waitNsMax; };
    PluginStats pluginStats(int track);

    // --- transport ---
    void play() { playing_.store(true); }
    void pause() { playing_.store(false); }
    void seek(int64_t f) { pos_.store(f < 0 ? 0 : f); }
    bool playing() const { return playing_.load(); }
    int64_t position() const { return pos_.load(); }
    float peak(int track) const;
    int64_t projectLength() const;

    // --- offline render (any thread but the audio thread) ---
    bool renderToWav(const std::string& path, int64_t from, int64_t to, WavFormat fmt);

    struct Stats { int sampleRate, burst, bufferSize, xruns; float avgUs, maxUs, latencyMs; bool exclusive; int64_t callbacks; };
    Stats stats();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override;
    bool onError(oboe::AudioStream*, oboe::Result) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

private:
    template <class F> void edit(F&& f);  // copy-on-write publish
    void publish(std::shared_ptr<Project> p);
    // Renders `frames` frames starting at `pos` into out (interleaved stereo). Real-time safe.
    static void mix(const Project& p, int64_t pos, int frames, float* out, MixState& ms, std::atomic<float>* meters, int plugOffset);
    // Handles looping while rendering a block; returns the new position.
    static int64_t renderBlock(const Project& p, int64_t pos, int frames, float* out, MixState& ms, std::atomic<float>* meters);
    // Plugin pipeline: collect the block requested one callback ago, then request the next window.
    struct LiveEvt { int track; uint8_t on, pitch, vel; };
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

    std::atomic<bool> playing_{false};
    std::atomic<int64_t> pos_{0};
    mutable std::atomic<float> meters_[kMaxTracks + 1];  // [kMaxTracks] = master
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<int64_t> callbacks_{0};
    std::atomic<int64_t> cbNsTotal_{0};
    std::atomic<int64_t> cbNsMax_{0};
    std::atomic<bool> restart_{false};
    // live MIDI queue (single producer under liveMutex_, single consumer = audio thread)
    std::mutex liveMutex_;
    std::array<LiveEvt, 256> live_;
    std::atomic<uint32_t> liveHead_{0}, liveTail_{0};
    struct ParamEvt { int track, index; float value; };
    std::mutex paramMutex_;
    std::array<ParamEvt, 512> paramQ_;
    std::atomic<uint32_t> paramHead_{0}, paramTail_{0};
    std::atomic<int64_t> lateTotal_{0};
    bool pluginWasPlaying_ = false;   // audio-thread-only
    int64_t pluginExpectedPos_ = 0;   // audio-thread-only
};

}  // namespace zedok
