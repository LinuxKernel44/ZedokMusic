// WAV import/export (PCM16/24/32 and float32, mono/stereo).
#pragma once
#include <string>
#include <vector>
namespace zedok {
// Reads a WAV file and returns interleaved stereo float at the file's own rate.
bool readWav(const std::string& path, std::vector<float>& stereo, int& sampleRate);
enum class WavFormat { Pcm16, Pcm24, Float32 };
// Writes interleaved stereo float samples.
bool writeWav(const std::string& path, const std::vector<float>& stereo, int sampleRate, WavFormat fmt);
// Linear resample of interleaved stereo audio.
std::vector<float> resampleStereo(const std::vector<float>& in, int fromRate, int toRate);
}
