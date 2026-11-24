// Audio input recorder: Oboe input stream -> lock-free ring -> writer thread -> streaming float32 WAV.
#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include <oboe/Oboe.h>

namespace zedok {

class Recorder : public oboe::AudioStreamDataCallback {
public:
    ~Recorder() { stop(); }
    // Opens the default input and starts writing a stereo float32 WAV at `path`. Returns "" or an error message.
    std::string start(const std::string& path, int sampleRate, int deviceId = 0);
    struct Result { int64_t frames = 0, dropped = 0; int inputBufferFrames = 0, channels = 0; bool ok = false; };
    Result stop();
    bool active() const { return running_.load(); }
    float level() { return level_.exchange(0.f); }  // peak since last call

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override;

private:
    void writerLoop();
    static constexpr size_t kRingFrames = 48000 * 8;  // 8 s of slack for slow storage
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<float> ring_;                  // interleaved stereo
    std::atomic<uint64_t> head_{0}, tail_{0};  // in frames
    std::atomic<bool> running_{false}, stopWriter_{false};
    std::atomic<int64_t> dropped_{0}, written_{0};
    std::atomic<float> level_{0.f};
    std::thread writer_;
    FILE* file_ = nullptr;
    int channels_ = 2, rate_ = 48000, inBuf_ = 0;
};

}  // namespace zedok
