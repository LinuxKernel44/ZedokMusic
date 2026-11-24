// MP3 export through LAME (LGPL, linked as a separate shared library).
#pragma once
#include <string>
#include <vector>
namespace zedok {
// Encodes interleaved stereo float samples (-1..1) as constant-bitrate MP3 (joint stereo). Returns false on error.
bool encodeMp3(const std::vector<float>& stereo, int sampleRate, int kbps, const std::string& path);
}
