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
#include <memory>
#include "../../../../native/dsp/processor.h"

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
    // A native (built-in) plugin: the "host" is a Processor run synchronously inside submit(), on the audio thread. The bridge memory is
    // anonymous, but laid out exactly like the Wine one, so parameters, automation and presets work unchanged.
    bool createNative(std::unique_ptr<zdsp::Processor> proc, int sampleRate, int maxFrames) {
        void* m = mmap(nullptr, ZK_BRIDGE_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        if (m == MAP_FAILED) return false;
        b_ = static_cast<ZkBridge*>(m);
        memset(b_, 0, ZK_BRIDGE_SIZE);
        b_->magic = ZK_MAGIC; b_->version = ZK_VERSION; b_->sample_rate = sampleRate; b_->max_frames = maxFrames; frames_ = maxFrames;
        proc->prepare(sampleRate, maxFrames);
        const auto& ps = proc->params();
        const int n = (int)std::min<size_t>(ps.size(), ZK_MAX_PARAMS);
        for (int i = 0; i < n; i++) {
            ZkParamInfo& pi = b_->params[i]; pi.id = (uint32_t)i; pi.default_norm = ps[i].defNorm(); pi.step_count = (uint32_t)ps[i].steps(); pi.flags = 0;
            strncpy(pi.name, ps[i].name.c_str(), sizeof pi.name - 1); strncpy(pi.units, ps[i].units.c_str(), sizeof pi.units - 1);
            b_->values[i] = pi.default_norm;
        }
        b_->n_params = n;
        strncpy(b_->plugin_name, proc->name(), sizeof b_->plugin_name - 1);
        b_->plugin_kind = proc->isInstrument() ? 1 : 2; b_->in_channels = b_->out_channels = 2;
        native_ = std::move(proc);
        __atomic_store_n(&b_->host_state, (uint32_t)ZK_HOST_READY, __ATOMIC_RELEASE);
        return true;
    }
    zdsp::Processor* native() const { return native_.get(); }
    bool supportsSlide() const { return native_ && native_->supportsSlide(); }
    // Transport information for plugins that need tempo (delays, arpeggiators, LFO sync). Audio thread, before submit().
    void setTransport(double bpm, double ppq, bool playing) { bpm_ = bpm; ppq_ = ppq; playingT_ = playing; }
    uint32_t hostState() const { return b_ ? __atomic_load_n(&b_->host_state, __ATOMIC_ACQUIRE) : ZK_HOST_ERROR; }
    bool ready() const { return hostState() == ZK_HOST_READY; }
    uint32_t hostError() const { return b_ ? b_->host_error : 0; }
    uint32_t kind() const { return b_ ? __atomic_load_n(&b_->plugin_kind, __ATOMIC_ACQUIRE) : 0; }  // 0 unknown, 1 instrument, 2 effect
    std::string pluginName() const { return b_ ? std::string(b_->plugin_name, strnlen(b_->plugin_name, sizeof b_->plugin_name)) : ""; }
    int frames() const { return frames_; }
    // --- parameters (published by the host once ready) ---
    int paramCount() const { return b_ ? (int)__atomic_load_n(&b_->n_params, __ATOMIC_ACQUIRE) : 0; }
    const ZkParamInfo* paramInfo(int i) const { return (b_ && i >= 0 && i < paramCount()) ? &b_->params[i] : nullptr; }
    float paramValue(int i) const { return (b_ && i >= 0 && i < paramCount()) ? b_->values[i] : 0.f; }
    void setParamValue(int i, float v) { if (b_ && i >= 0 && i < paramCount()) b_->values[i] = v; }
    std::string paramText(int i) const {
        if (native_ && b_ && i >= 0 && i < paramCount()) return native_->params()[i].text(b_->values[i]);
        return paramTextRaw(i);
    }
    std::string paramGroup(int i) const { return native_ && i >= 0 && i < paramCount() ? native_->params()[i].group : std::string(); }
    std::string paramTextAt(int i, float norm) const { return native_ && i >= 0 && i < paramCount() ? native_->params()[i].text(norm) : std::string(); }
    std::string paramTextRaw(int i) const { return (b_ && i >= 0 && i < paramCount()) ? std::string(b_->texts[i], strnlen(b_->texts[i], sizeof b_->texts[i])) : ""; }

    // Parameter edits made in the plugin editor since the last call (index into the parameter table, normalized value).
    int pollEdits(ZkParamChange* out, int max) {
        if (!b_) return 0;
        uint32_t head = __atomic_load_n(&b_->edit_head, __ATOMIC_ACQUIRE); int n = 0;
        if (head - editTail_ > ZK_EDIT_RING) editTail_ = head - ZK_EDIT_RING;  // overrun: skip the oldest
        while (editTail_ != head && n < max) out[n++] = b_->edits[editTail_++ % ZK_EDIT_RING];
        return n;
    }
    // --- plugin editor ---
    void guiCommand(uint32_t cmd, int x, int y) { if (!b_) return; b_->gui_x_pos_req = (uint32_t)x; b_->gui_y_pos_req = (uint32_t)y; __atomic_store_n(&b_->gui_cmd, cmd, __ATOMIC_RELEASE); }
    uint32_t guiState() const { return b_ ? __atomic_load_n(&b_->gui_state, __ATOMIC_ACQUIRE) : (uint32_t)ZK_GUI_CLOSED; }
    void guiSize(int* w, int* h, int* x, int* y) const { *w = b_ ? (int)b_->gui_w : 0; *h = b_ ? (int)b_->gui_h : 0; *x = b_ ? b_->gui_x : 0; *y = b_ ? b_->gui_y : 0; }

    // Real-time safe. Hands one block (events for the window about to be rendered by the host) to the Wine host.
    void submit(const ZkEvent* ev, int n, int frames, const ZkParamChange* ch = nullptr, int nch = 0) {
        n = n > ZK_MAX_EVENTS ? ZK_MAX_EVENTS : n;
        nch = nch > ZK_MAX_PARAM_CHANGES ? ZK_MAX_PARAM_CHANGES : nch;
        if (native_) {   // run the built-in plugin right here
            for (int i = 0; i < nch; i++) if (ch[i].index < ZK_MAX_PARAMS && (int)ch[i].index < paramCount()) b_->values[ch[i].index] = ch[i].value;
            const int f = frames > ZK_MAX_FRAMES ? ZK_MAX_FRAMES : frames;
            zdsp::ProcessCtx c{ev, n, b_->in[0], b_->in[1], b_->out[0], b_->out[1], f, bpm_, ppq_, playingT_};
            bool hard = false; for (int i = 0; i < n; i++) if (ev[i].type == ZK_ALL_NOTES_OFF && ev[i].pitch == 1) hard = true;
            native_->process(c, b_->values);
            if (hard) {   // "silence" (a new preset is about to be heard): the old sound fades out over this block, then every voice, filter and effect tail is forgotten, so nothing of it can colour the next sound
                float* L = b_->out[0]; float* R = b_->out[1];
                for (int i = 0; i < f; i++) { const float g = 1.f - (float)(i + 1) / (float)f; L[i] *= g; R[i] *= g; }
                native_->reset();
            }
            seq_++; __atomic_store_n(&b_->ack_seq, seq_, __ATOMIC_RELEASE); pending_ = true;
            return;
        }
        b_->tempo = bpm_; b_->ppq = ppq_; b_->playing = playingT_ ? 1u : 0u; b_->sig_num = 4; b_->sig_den = 4;   // the Windows plugin's tempo, position and time signature
        if (n > 0) memcpy(b_->events, ev, n * sizeof(ZkEvent));
        if (nch > 0) memcpy(b_->changes, ch, nch * sizeof(ZkParamChange));
        b_->n_events = n; b_->n_changes = nch; b_->frames = frames;
        __atomic_store_n(&b_->req_seq, ++seq_, __ATOMIC_RELEASE);
        pending_ = true;
    }
    // Effects: hands one block of audio (interleaved stereo) to the host, together with parameter changes.
    void submitAudio(const float* interleaved, int frames, const ZkParamChange* ch = nullptr, int nch = 0) {
        const int n = frames > ZK_MAX_FRAMES ? ZK_MAX_FRAMES : frames;
        for (int i = 0; i < n; i++) { b_->in[0][i] = interleaved[i * 2]; b_->in[1][i] = interleaved[i * 2 + 1]; }
        submit(nullptr, 0, n, ch, nch);
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
    uint32_t editTail_ = 0;
    std::unique_ptr<zdsp::Processor> native_; double bpm_ = 120.0, ppq_ = -1.0; bool playingT_ = false;
    uint32_t seq_ = 0; bool pending_ = false, skipped_ = false;
    std::atomic<int64_t> late_{0}, blocks_{0}, waitedBlocks_{0}, waitNsTotal_{0}, waitNsMax_{0};
};
}  // namespace zedok
