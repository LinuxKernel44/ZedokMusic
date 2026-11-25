#!/bin/sh
# Builds libzpresent.so, the Vulkan layer that shows GPU frames on the app's X screen (see zpresent.c). Needs aarch64-linux-gnu-gcc and the Vulkan headers.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
aarch64-linux-gnu-gcc -idirafter /usr/include -shared -fPIC -O2 -fvisibility=hidden -Wall -Wno-unused-function -o "${1:-$HERE/libzpresent.so}" "$HERE/zpresent.c" -ldl -lpthread
