// Minimal FLAC encoder (fixed predictors + Rice coding, adaptive stereo decorrelation). Written from the public FLAC format spec.
#pragma once
#include <string>
#include <vector>
namespace zedok {
// Encodes interleaved stereo float samples (-1..1) as 16- or 24-bit FLAC. Returns false on I/O error.
bool encodeFlac(const std::vector<float>& stereo, int sampleRate, int bitsPerSample, const std::string& path);
}
