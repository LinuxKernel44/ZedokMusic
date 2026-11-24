// Streaming .tar.zst extractor (files, dirs, symlinks, hard links; GNU long names and pax paths).
#pragma once
#include <atomic>
#include <cstdint>
#include <string>
namespace zedok {
// Reads `length` bytes at `offset` of file descriptor `fd` (an uncompressed APK asset) and extracts into destDir.
// `progress` receives the number of compressed bytes consumed. Returns an empty string on success, else an error.
std::string extractTarZst(int fd, int64_t offset, int64_t length, const std::string& destDir, std::atomic<int64_t>* progress);
}
