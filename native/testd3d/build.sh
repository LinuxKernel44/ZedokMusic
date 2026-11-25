#!/bin/sh
# Builds ztestd3d.exe, a minimal Direct3D 11 program for testing the graphics path. Needs mingw-w64.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
x86_64-w64-mingw32-g++ -O2 -mwindows -o "${1:-$HERE/ztestd3d.exe}" "$HERE/ztestd3d.cpp" -static -static-libgcc -static-libstdc++ -ld3d11 -ldxgi
