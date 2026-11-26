#!/bin/sh
# Builds two tiny VST2 test plugins (ZTest2Synth.dll, ZTest2Fx.dll) for the VST2 path of zhost.
set -e
HERE=$(cd "$(dirname "$0")" && pwd); OUT=${1:-$HERE}
x86_64-w64-mingw32-g++ -O2 -shared -static -DAS_SYNTH=1 -o "$OUT/ZTest2Synth.dll" "$HERE/vst2test.cpp" -luser32 -lgdi32
x86_64-w64-mingw32-g++ -O2 -shared -static -DAS_SYNTH=0 -o "$OUT/ZTest2Fx.dll" "$HERE/vst2test.cpp" -luser32 -lgdi32
