// Engine-side endpoint of the Wine plugin bridge (see native/common/bridge.h).
#pragma once
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include "bridge.h"

namespace zedok {

class PluginBridge {
public:
    ~PluginBridge() {
        if (b_) { b_->quit = 1; munmap(b_, ZK_BRIDGE_SIZE); }
        if (fd_ >= 0) close(fd_);
    }
    // Creates/truncates the shared file; the Wine host is launched afterwards and maps the same file.
    bool create(const std::string& path, int sampleRate, int maxFrames) {
        fd_ = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0666);
        if (fd_ < 0 || ftruncate(fd_, ZK_BRIDGE_SIZE) != 0) return false;
        void* m = mmap(nullptr, ZK_BRIDGE_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
        if (m == MAP_FAILED) return false;
        b_ = static_cast<ZkBridge*>(m);
        memset(b_, 0, ZK_BRIDGE_SIZE);
        b_->magic = ZK_MAGIC; b_->version = ZK_VERSION; b_->sample_rate = sampleRate; b_->max_frames = maxFrames;
        frames_ = maxFrames;
        return true;
    }
    uint32_t hostState() const { return b_ ? __atomic_load_n(&b_->host_state, __ATOMIC_ACQUIRE) : ZK_HOST_ERROR; }
    bool ready() const { return hostState() == ZK_HOST_READY; }
    uint32_t hostError() const { return b_ ? b_->host_error : 0; }
    std::string pluginName() const { return b_ ? std::string(b_->plugin_name, strnlen(b_->plugin_name, sizeof b_->plugin_name)) : ""; }
    int frames() const { return frames_; }

    // Real-time safe. Hands one block (events for the window about to be rendered by the host) to the Wine host.
    void submit(const ZkEvent* ev, int n, int frames) {
        n = n > ZK_MAX_EVENTS ? ZK_MAX_EVENTS : n;
        if (n > 0) memcpy(b_->events, ev, n * sizeof(ZkEvent));
        b_->n_events = n; b_->frames = frames;
        __atomic_store_n(&b_->req_seq, ++seq_, __ATOMIC_RELEASE);
        pending_ = true;
    }
    // Waits (bounded spin) for the outstanding block. Returns false and counts a late block if the host missed the deadline.
    bool collect(float* interleaved, int frames, int64_t maxWaitNs) {
        if (!pending_) { memset(interleaved, 0, sizeof(float) * 2 * frames); return false; }
        auto t0 = std::chrono::steady_clock::now();
        bool waited = false;
        while (__atomic_load_n(&b_->ack_seq, __ATOMIC_ACQUIRE) != seq_) {
            waited = true;
            int64_t el = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count();
            if (el > maxWaitNs) {
                late_.fetch_add(1, std::memory_order_relaxed);
                skipped_ = true;  // block still in flight: its result is discarded when it eventually lands
                memset(interleaved, 0, sizeof(float) * 2 * frames);
                pending_ = false;
                return false;
            }
        }
        if (waited) {
            int64_t el = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count();
            waitedBlocks_.fetch_add(1, std::memory_order_relaxed); waitNsTotal_.fetch_add(el, std::memory_order_relaxed);
            int64_t m = waitNsMax_.load(std::memory_order_relaxed); while (el > m && !waitNsMax_.compare_exchange_weak(m, el)) {}
        }
        for (int i = 0; i < frames; i++) { interleaved[i * 2] = b_->out[0][i]; interleaved[i * 2 + 1] = b_->out[1][i]; }
        pending_ = false; blocks_.fetch_add(1, std::memory_order_relaxed);
        return true;
    }
    // A request is "idle-safe" when the host has acknowledged everything we sent.
    bool hostCaughtUp() const { return __atomic_load_n(&b_->ack_seq, __ATOMIC_ACQUIRE) == seq_; }
    bool pending() const { return pending_; }
    int64_t late() const { return late_.load(); }
    int64_t blocks() const { return blocks_.load(); }
    int64_t waitedBlocks() const { return waitedBlocks_.load(); }
    int64_t waitNsTotal() const { return waitNsTotal_.load(); }
    int64_t waitNsMax() const { return waitNsMax_.load(); }

    // Bookkeeping owned by the audio thread (or the offline renderer).
    bool wasPlaying = false;
    int64_t expectedPos = 0;

private:
    ZkBridge* b_ = nullptr; int fd_ = -1, frames_ = 0;
    uint32_t seq_ = 0; bool pending_ = false, skipped_ = false;
    std::atomic<int64_t> late_{0}, blocks_{0}, waitedBlocks_{0}, waitNsTotal_{0}, waitNsMax_{0};
};
}  // namespace zedok
