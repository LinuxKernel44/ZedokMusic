// Shared-memory block layout between the native audio engine (ARM64) and the Wine plugin host (x86_64).
#pragma once
#include <stdint.h>
#define ZK_MAX_FRAMES 1024
#define ZK_SHM_SIZE   (4096 * 4)
struct ZkShm {
    volatile uint32_t req_seq;   char _p0[60];   // written by engine
    volatile uint32_t ack_seq;   char _p1[60];   // written by host
    volatile uint32_t frames;    volatile uint32_t quit; volatile uint32_t ready; char _p2[52];
    float in[2][ZK_MAX_FRAMES];
    float out[2][ZK_MAX_FRAMES];
};
