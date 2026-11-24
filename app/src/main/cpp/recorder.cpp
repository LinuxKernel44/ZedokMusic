#include "recorder.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#define TAG "ZedokRecorder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace zedok {
namespace {
void wr32(FILE* f, uint32_t v) { fwrite(&v, 4, 1, f); }
void wr16(FILE* f, uint16_t v) { fwrite(&v, 2, 1, f); }
void header(FILE* f, uint32_t dataBytes, int rate) {
    fseek(f, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, f); wr32(f, 36 + dataBytes); fwrite("WAVEfmt ", 1, 8, f);
    wr32(f, 16); wr16(f, 3); wr16(f, 2); wr32(f, rate); wr32(f, rate * 8); wr16(f, 8); wr16(f, 32);
    fwrite("data", 1, 4, f); wr32(f, dataBytes);
}
}

std::string Recorder::start(const std::string& path, int sampleRate, int deviceId) {
    if (running_.load()) return "already recording";
    file_ = fopen(path.c_str(), "wb");
    if (!file_) return "cannot create " + path;
    rate_ = sampleRate; header(file_, 0, rate_);
    ring_.assign(kRingFrames * 2, 0.f); head_ = tail_ = 0; dropped_ = written_ = 0; level_ = 0.f;

    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)->setFormat(oboe::AudioFormat::Float)
        ->setSampleRate(sampleRate)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(oboe::InputPreset::Unprocessed)  // no AGC / noise suppression: this is a DAW
        ->setDataCallback(this);
    if (deviceId) b.setDeviceId(deviceId);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {  // some devices reject Unprocessed: retry with the generic preset
        b.setInputPreset(oboe::InputPreset::Generic); r = b.openStream(stream_);
    }
    if (r != oboe::Result::OK) { fclose(file_); file_ = nullptr; return std::string("cannot open input: ") + oboe::convertToText(r); }
    channels_ = stream_->getChannelCount(); inBuf_ = stream_->getBufferSizeInFrames();
    stopWriter_ = false; running_ = true;
    writer_ = std::thread(&Recorder::writerLoop, this);
    r = stream_->requestStart();
    if (r != oboe::Result::OK) { running_ = false; stopWriter_ = true; writer_.join(); stream_->close(); stream_.reset(); fclose(file_); file_ = nullptr; return std::string("cannot start input: ") + oboe::convertToText(r); }
    return "";
}

oboe::DataCallbackResult Recorder::onAudioReady(oboe::AudioStream*, void* data, int32_t frames) {
    const float* in = static_cast<const float*>(data);
    uint64_t h = head_.load(std::memory_order_relaxed), t = tail_.load(std::memory_order_acquire);
    if (h - t + frames > kRingFrames) { dropped_.fetch_add(frames, std::memory_order_relaxed); return oboe::DataCallbackResult::Continue; }
    float pk = 0.f;
    for (int i = 0; i < frames; i++) {
        float l = in[i * channels_], r = channels_ > 1 ? in[i * channels_ + 1] : l;  // mono -> both channels
        size_t o = (size_t)((h + i) % kRingFrames) * 2; ring_[o] = l; ring_[o + 1] = r;
        pk = std::max(pk, std::max(fabsf(l), fabsf(r)));
    }
    head_.store(h + frames, std::memory_order_release);
    float old = level_.load(std::memory_order_relaxed); if (pk > old) level_.store(pk, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void Recorder::writerLoop() {
    std::vector<float> buf(4096 * 2);
    for (;;) {
        uint64_t h = head_.load(std::memory_order_acquire), t = tail_.load(std::memory_order_relaxed);
        if (h == t) {
            if (stopWriter_.load()) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(4)); continue;
        }
        size_t n = (size_t)std::min<uint64_t>(h - t, 4096);
        for (size_t i = 0; i < n; i++) { size_t o = (size_t)((t + i) % kRingFrames) * 2; buf[i * 2] = ring_[o]; buf[i * 2 + 1] = ring_[o + 1]; }
        fwrite(buf.data(), sizeof(float) * 2, n, file_);
        written_.fetch_add((int64_t)n); tail_.store(t + n, std::memory_order_release);
    }
}

Recorder::Result Recorder::stop() {
    Result res;
    if (!running_.exchange(false)) return res;
    if (stream_) { stream_->requestStop(); stream_->close(); stream_.reset(); }
    stopWriter_ = true; if (writer_.joinable()) writer_.join();
    res.frames = written_.load(); res.dropped = dropped_.load(); res.inputBufferFrames = inBuf_; res.channels = channels_;
    if (file_) { header(file_, (uint32_t)(res.frames * 8), rate_); fflush(file_); res.ok = fclose(file_) == 0; file_ = nullptr; }
    return res;
}
}  // namespace zedok
