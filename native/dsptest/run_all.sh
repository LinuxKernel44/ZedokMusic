#!/bin/sh
# Builds the offline tools, runs the plugin robustness test and the engine golden renders (run_engine.sh), then regenerates the factory presets (which also checks every preset renders cleanly).
set -e
cd "$(dirname "$0")"
gcc -O2 -c ../dsp/stb_vorbis_impl.c -o /tmp/zstbv.o
g++ -std=c++17 -O2 -pthread fuzz.cpp ../dsp/registry.cpp ../../app/src/main/cpp/wav.cpp /tmp/zstbv.o -o /tmp/zfuzz
g++ -std=c++17 -O2 -pthread render.cpp ../dsp/registry.cpp ../../app/src/main/cpp/wav.cpp /tmp/zstbv.o -o /tmp/zrender
/tmp/zfuzz "${1:-30}"
g++ -std=c++17 -O2 t_loudness.cpp -o /tmp/zloud && /tmp/zloud
./run_engine.sh
../presets/build.sh
