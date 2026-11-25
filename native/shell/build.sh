#!/bin/sh
# Builds zshell.exe (Windows x64) with mingw-w64: the taskbar / Start menu of the app's Windows desktop.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
x86_64-w64-mingw32-gcc -O2 -mwindows -o "${1:-$HERE/zshell.exe}" "$HERE/zshell.c" -lgdi32 -luser32 -lcomdlg32
