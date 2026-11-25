#!/bin/sh
# Builds ZTestGain.vst3, a tiny effect for testing insert effects. Needs mingw-w64 and the vst3_pluginterfaces clone (see native/host/build.sh).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
VST3PI=${VST3PI:-$HERE/../../build/vst3_pluginterfaces}
INC=$HERE/../../build/vst3_inc; mkdir -p "$INC"; ln -sfn "$VST3PI" "$INC/pluginterfaces"
x86_64-w64-mingw32-g++ -std=c++17 -O2 -shared -I"$INC" -I"$HERE/../common" -o "${1:-$HERE/ZTestGain.vst3}" \
  "$HERE/ztestfx.cpp" "$HERE/../host/vst3iids.cpp" "$VST3PI/base/funknown.cpp" "$VST3PI/base/ustring.cpp" "$VST3PI/base/conststringtable.cpp" \
  -static -static-libgcc -static-libstdc++ -lole32
