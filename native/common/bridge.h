// Shared-memory protocol between the native audio engine (ARM64, in-app) and the Wine plugin host (x86_64 Windows exe).
// The file is mmap'd MAP_SHARED by the engine and via CreateFileMapping by the host. All fields are little-endian.
#pragma once
#include <stdint.h>
#define ZK_MAGIC 0x5A4B4252u  // 'ZKBR'
#define ZK_VERSION 2
#define ZK_MAX_FRAMES 2048
#define ZK_MAX_EVENTS 512
#define ZK_MAX_PARAMS 2048
#define ZK_MAX_PARAM_CHANGES 64

enum ZkEventType : uint8_t { ZK_NOTE_ON = 1, ZK_NOTE_OFF = 2, ZK_ALL_NOTES_OFF = 3 };
enum ZkHostState : uint32_t { ZK_HOST_STARTING = 0, ZK_HOST_READY = 1, ZK_HOST_ERROR = 2 };

#pragma pack(push, 4)
struct ZkParamInfo { uint32_t id; float default_norm; uint32_t step_count; uint32_t flags; char name[48]; char units[16]; };  // flags: VST3 ParameterInfo flags
struct ZkParamChange { uint32_t index; float value; };  // index into ZkBridge::params, value normalized 0..1
struct ZkEvent { uint32_t offset; uint8_t type, channel, pitch, velocity; };  // offset = sample offset in the block

struct ZkBridge {
    uint32_t magic, version;
    volatile uint32_t host_state;      // ZkHostState, written by host
    volatile uint32_t quit;            // set by engine
    uint32_t sample_rate, max_frames;  // set by engine before launching the host
    volatile uint32_t host_error;      // error code when host_state == ZK_HOST_ERROR
    uint32_t reserved0;
    char plugin_name[96];              // written by host
    char _pad0[64];
    // --- block handshake (separate cache lines) ---
    volatile uint32_t req_seq; char _p1[60];   // engine -> host: "process one block"
    volatile uint32_t ack_seq; char _p2[60];   // host -> engine: "block done"
    uint32_t frames, n_events, n_changes;      // valid for the current request
    uint32_t in_channels, out_channels;        // written by host (what the plugin really exposes)
    ZkEvent events[ZK_MAX_EVENTS];
    ZkParamChange changes[ZK_MAX_PARAM_CHANGES];  // parameter changes applied at the start of the block
    // --- parameters, published by the host once it is ready ---
    volatile uint32_t n_params;
    ZkParamInfo params[ZK_MAX_PARAMS];
    float values[ZK_MAX_PARAMS];               // current normalized values (engine/UI keep these up to date)
    char texts[ZK_MAX_PARAMS][24];             // display text for the current value, refreshed by the host
    float in[2][ZK_MAX_FRAMES];                // audio input for effects (instruments ignore it)
    float out[2][ZK_MAX_FRAMES];               // audio output
};
#pragma pack(pop)
#define ZK_BRIDGE_SIZE ((sizeof(ZkBridge) + 4095) & ~4095u)
