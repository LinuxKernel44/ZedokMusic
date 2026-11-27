// Shared-memory protocol between the native audio engine (ARM64, in-app) and the Wine plugin host (x86_64 Windows exe).
// The file is mmap'd MAP_SHARED by the engine and via CreateFileMapping by the host. All fields are little-endian.
#pragma once
#include <stdint.h>
#define ZK_MAGIC 0x5A4B4252u  // 'ZKBR'
#define ZK_VERSION 5
#define ZK_MAX_FRAMES 2048
#define ZK_MAX_EVENTS 512
#define ZK_MAX_PARAMS 2048
#define ZK_MAX_PARAM_CHANGES 64
#define ZK_EDIT_RING 64

enum ZkEventType : uint8_t { ZK_NOTE_ON = 1, ZK_NOTE_OFF = 2, ZK_ALL_NOTES_OFF = 3 };
enum ZkHostState : uint32_t { ZK_HOST_STARTING = 0, ZK_HOST_READY = 1, ZK_HOST_ERROR = 2 };
enum ZkGuiCmd : uint32_t { ZK_GUI_NONE = 0, ZK_GUI_OPEN = 1, ZK_GUI_CLOSE = 2 };
enum ZkGuiState : uint32_t { ZK_GUI_CLOSED = 0, ZK_GUI_OPENED = 1, ZK_GUI_FAILED = 2 };

#pragma pack(push, 4)
struct ZkParamInfo { uint32_t id; float default_norm; uint32_t step_count; uint32_t flags; char name[48]; char units[16]; };  // flags: VST3 ParameterInfo flags
struct ZkParamChange { uint32_t index; float value; };  // index into ZkBridge::params, value normalized 0..1
// offset = sample offset in the block. pan / cutoff / res / fine / release are the per-note properties of a Note On (-127..127, 0 = unchanged); other events leave them 0.
struct ZkEvent { uint32_t offset; uint8_t type, channel, pitch, velocity; int8_t pan, cutoff, res, fine, release; uint8_t _pad[3]; };

struct ZkBridge {
    uint32_t magic, version;
    volatile uint32_t host_state;      // ZkHostState, written by host
    volatile uint32_t quit;            // set by engine
    uint32_t sample_rate, max_frames;  // set by engine before launching the host
    volatile uint32_t host_error;      // error code when host_state == ZK_HOST_ERROR
    volatile uint32_t plugin_kind;     // written by host: 0 unknown, 1 instrument, 2 effect (from the VST3 sub-categories)
    char plugin_name[96];              // written by host
    // --- transport, written by the engine before every block (read by the Wine host for the plugin's ProcessContext / VstTimeInfo) ---
    double tempo;                      // beats per minute of the project
    double ppq;                        // position in quarter notes at the start of the block, < 0 when the transport is stopped
    uint32_t playing;                  // 1 while the project is playing
    uint32_t sig_num, sig_den;         // time signature
    char _pad0[36];
    // --- plugin editor (GUI) ---
    volatile uint32_t gui_cmd;         // ZkGuiCmd, set by the engine side, cleared by the host when handled
    volatile uint32_t gui_state;       // ZkGuiState, written by the host
    volatile uint32_t gui_w, gui_h;    // editor size in pixels while open
    int32_t gui_x, gui_y;              // top-left of the editor window on the Wine desktop
    uint32_t gui_x_pos_req, gui_y_pos_req;  // where the engine side wants the window (desktop coordinates)
    // Parameter edits made inside the plugin editor, for automation recording (host = producer, app = consumer).
    volatile uint32_t edit_head;
    ZkParamChange edits[ZK_EDIT_RING];
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
