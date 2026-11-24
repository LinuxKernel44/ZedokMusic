#!/bin/sh
# Builds zhost.exe (Windows x64) with mingw-w64. Needs the MIT-licensed Steinberg VST3 interfaces:
#   git clone --depth 1 https://github.com/steinbergmedia/vst3_pluginterfaces.git "$VST3PI"
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
VST3PI=${VST3PI:-$HERE/../../build/vst3_pluginterfaces}
INC=$HERE/../../build/vst3_inc; mkdir -p "$INC"; ln -sfn "$VST3PI" "$INC/pluginterfaces"
x86_64-w64-mingw32-g++ -std=c++17 -O2 -I"$INC" -I"$HERE/../common" -o "${1:-$HERE/zhost.exe}" \
  "$HERE/zhost.cpp" "$HERE/vst3iids.cpp" "$VST3PI/base/funknown.cpp" "$VST3PI/base/ustring.cpp" "$VST3PI/base/conststringtable.cpp" \
  -static -lole32
