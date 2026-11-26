#!/bin/sh
# Generates the factory preset JSON files into the app's assets (and a level report). usage: build.sh [plugin ids...]
set -e
cd "$(dirname "$0")"
OUT=../../app/src/main/assets/presets; mkdir -p "$OUT"
g++ -std=c++17 -O2 -Wall -o /tmp/zpresetgen presetgen.cpp hybrid_presets.cpp ../dsp/registry.cpp
for id in ${@:-hybrid}; do /tmp/zpresetgen "$id" "$OUT/$id.json" --report "/tmp/zpreset_$id.txt"; done
