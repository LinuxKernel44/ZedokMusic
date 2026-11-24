#!/bin/bash
# Builds the Wine-side plugin host (zhost.exe) and stages it as an APK asset. Needs mingw-w64 and a vst3_pluginterfaces clone
# (see native/host/build.sh).
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
"$HERE/native/host/build.sh" "$HERE/app/src/main/assets/zhost.exe"
ls -la "$HERE/app/src/main/assets/zhost.exe"
