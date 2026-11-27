#!/bin/sh
# Builds the offline tools and runs the plugin robustness test, then regenerates the factory presets (which also checks every preset renders cleanly).
set -e
cd "$(dirname "$0")"
gcc -O2 -c ../dsp/stb_vorbis_impl.c -o /tmp/zstbv.o
g++ -std=c++17 -O2 -pthread fuzz.cpp ../dsp/registry.cpp ../../app/src/main/cpp/wav.cpp /tmp/zstbv.o -o /tmp/zfuzz
g++ -std=c++17 -O2 -pthread render.cpp ../dsp/registry.cpp ../../app/src/main/cpp/wav.cpp /tmp/zstbv.o -o /tmp/zrender
/tmp/zfuzz "${1:-30}"
../presets/build.sh
