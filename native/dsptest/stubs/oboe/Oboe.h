// Just enough of Oboe for engine.cpp to compile on a desktop: the offline tests never open an audio stream.
#pragma once
#include <cstdint>
#include <memory>
namespace oboe {
enum class Result { OK, ErrorUnavailable };
inline const char* convertToText(Result) { return "stub"; }
enum class DataCallbackResult { Continue, Stop };
enum class Direction { Output };
enum class PerformanceMode { LowLatency };
enum class SharingMode { Exclusive, Shared };
enum class AudioFormat { Float };
enum class SampleRateConversionQuality { Medium };
enum class Usage { Media };
enum class ContentType { Music };
enum class AudioApi { AAudio };
template <class T> struct ResultWithValue { T v{}; explicit operator bool() const { return false; } T value() const { return v; } };
class AudioStream;
class AudioStreamDataCallback { public: virtual ~AudioStreamDataCallback() = default; virtual DataCallbackResult onAudioReady(AudioStream*, void*, int32_t) = 0; };
class AudioStreamErrorCallback { public: virtual ~AudioStreamErrorCallback() = default; virtual bool onError(AudioStream*, Result) { return false; } virtual void onErrorAfterClose(AudioStream*, Result) {} };
class AudioStream {
public:
    int getSampleRate() const { return 48000; } int getFramesPerBurst() const { return 128; } int getBufferSizeInFrames() const { return 256; }
    void setBufferSizeInFrames(int) {} Result requestStart() { return Result::OK; } void requestStop() {} void close() {}
    SharingMode getSharingMode() const { return SharingMode::Shared; } AudioApi getAudioApi() const { return AudioApi::AAudio; }
    ResultWithValue<int> getXRunCount() const { return {}; }
};
class AudioStreamBuilder {
public:
    AudioStreamBuilder* setDirection(Direction) { return this; } AudioStreamBuilder* setPerformanceMode(PerformanceMode) { return this; }
    AudioStreamBuilder* setSharingMode(SharingMode) { return this; } AudioStreamBuilder* setFormat(AudioFormat) { return this; }
    AudioStreamBuilder* setChannelCount(int) { return this; } AudioStreamBuilder* setSampleRate(int) { return this; }
    AudioStreamBuilder* setSampleRateConversionQuality(SampleRateConversionQuality) { return this; } AudioStreamBuilder* setUsage(Usage) { return this; }
    AudioStreamBuilder* setContentType(ContentType) { return this; } AudioStreamBuilder* setFramesPerDataCallback(int) { return this; }
    AudioStreamBuilder* setDataCallback(AudioStreamDataCallback*) { return this; } AudioStreamBuilder* setErrorCallback(AudioStreamErrorCallback*) { return this; }
    AudioStreamBuilder* setDeviceId(int) { return this; }
    Result openStream(std::shared_ptr<AudioStream>&) { return Result::ErrorUnavailable; }
};
}
