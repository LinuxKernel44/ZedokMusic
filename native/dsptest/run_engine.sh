#!/bin/sh
# Builds the real audio engine on the desktop (Oboe stubbed) and renders the golden scenes.
#   run_engine.sh            check against golden/engine.txt (levels within 0.02 dB)
#   run_engine.sh --strict   also require bit-exact output (use when refactoring the engine)
#   run_engine.sh --update   rewrite golden/engine.txt (only when a change is meant to alter the sound)
# The golden checksums are for x86-64 with these flags (no FMA contraction); other CPUs/compilers: use the level check.
set -e
cd "$(dirname "$0")"
gcc -O2 -c ../dsp/stb_vorbis_impl.c -o /tmp/zstbv.o
g++ -std=c++20 -O2 -ffp-contract=off -pthread -Istubs -w -I../../app/src/main/cpp -I../common engine_golden.cpp ../../app/src/main/cpp/engine.cpp ../../app/src/main/cpp/wav.cpp ../dsp/registry.cpp /tmp/zstbv.o -o /tmp/zengine_golden
case "$1" in
  --update) /tmp/zengine_golden --update golden/engine.txt ;;
  --strict) /tmp/zengine_golden --check golden/engine.txt --strict ;;
  *) /tmp/zengine_golden --check golden/engine.txt ;;
esac
