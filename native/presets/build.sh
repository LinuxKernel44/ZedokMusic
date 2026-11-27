#!/bin/sh
# Generates the factory preset JSON files into the app's assets (and a level report). usage: build.sh [plugin ids...]
set -e
cd "$(dirname "$0")"
OUT=../../app/src/main/assets/presets; mkdir -p "$OUT"
gcc -O2 -c ../dsp/stb_vorbis_impl.c -o /tmp/zstbv.o
g++ -std=c++17 -O2 -pthread -Wall -o /tmp/zpresetgen presetgen.cpp hybrid_presets.cpp eqcomp_presets.cpp space_presets.cpp color_presets.cpp drums_presets.cpp grain_presets.cpp sampler_presets.cpp piano_presets.cpp ../dsp/registry.cpp ../../app/src/main/cpp/wav.cpp /tmp/zstbv.o
for id in ${@:-hybrid eqcomp space color drums grain sampler piano}; do /tmp/zpresetgen "$id" "$OUT/$id.json" --report "/tmp/zpreset_$id.txt"; done
