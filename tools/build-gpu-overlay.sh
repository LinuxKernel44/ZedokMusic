#!/bin/bash
# Builds the Vulkan/Direct3D overlay merged into the rootfs, so plugins that draw with Direct3D 11 (Serum 2) can open their editor:
#   usr/lib/libvulkan_lvp.so + libLLVM 20 and friends   Mesa lavapipe, a software Vulkan driver (Ubuntu 24.04 arm64 packages)
#   usr/share/vulkan/icd.d/lvp_icd.json                  the Vulkan loader's driver file (absolute path inside the fixed prefix)
#   opt/dxvk/x64/*.dll                                   DXVK 2.4.1 (d3d8/9/10core/11, dxgi), taken from the Winlator 11.2 release
# Wine's d3d11 -> DXVK -> Vulkan (the runtime's libvulkan.so.1) -> lavapipe -> CPU. The app copies the DXVK dlls into the Windows
# system folder (PluginHost.applyDxvk). A real GPU driver (Turnip / Vortek) would replace only the ICD.
# Needs: curl, ar, tar, xz, zstd, python3, unzip. Output: $WORK/gpu-overlay
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HERE/build/rootfs-work}
RF=${RF:-$WORK/rf}
OUT=$WORK/gpu-overlay; G=$WORK/gpu-build; mkdir -p "$G/debs"
MIRROR=https://ports.ubuntu.com/ubuntu-ports
python3 -I - "$G" "$MIRROR" "$RF/usr/lib" <<'PY'
import sys, urllib.request, lzma, os
g, mirror, rflib = sys.argv[1:4]
idx = {}
for pocket in ("noble-updates", "noble-security", "noble"):
    for comp in ("main", "universe"):
        t = lzma.decompress(urllib.request.urlopen(f"{mirror}/dists/{pocket}/{comp}/binary-arm64/Packages.xz").read()).decode()
        for b in t.split("\n\n"):
            f = {l.split(": ", 1)[0]: l.split(": ", 1)[1] for l in b.split("\n") if ": " in l and not l.startswith(" ")}
            if f.get("Package") and f["Package"] not in idx: idx[f["Package"]] = f
deny = {"libc6", "libgcc-s1", "libstdc++6", "zlib1g", "libvulkan1", "libzstd1", "liblzma5", "libx11-6", "libxcb1", "libxau6", "libxdmcp6", "libbsd0", "libmd0"}
seen, stack = {}, ["mesa-vulkan-drivers", "libsensors5", "libdrm-amdgpu1", "libpciaccess0", "libxcb-glx0"]
while stack:
    n = stack.pop()
    if n in seen or n in deny or n not in idx: continue
    seen[n] = idx[n]["Filename"]
    for d in idx[n].get("Depends", "").split(","):
        d = d.strip().split(" ")[0].split(":")[0].split("|")[0]
        if d: stack.append(d)
open(os.path.join(g, "list.txt"), "w").write("".join(f"{n} {mirror}/{fn}\n" for n, fn in seen.items()))
PY
while read -r name url; do [ -f "$G/debs/$name.deb" ] || curl -fsSL -o "$G/debs/$name.deb" "$url"; done < "$G/list.txt"
X=$G/x; mkdir -p "$X"
for d in "$G"/debs/*.deb; do (cd "$X" && { ar x "$d" data.tar.zst 2>/dev/null && zstd -dc data.tar.zst | tar -xf - && rm -f data.tar.zst; } || { ar x "$d" data.tar.xz && tar -xJf data.tar.xz && rm -f data.tar.xz; }); done
mkdir -p "$OUT/usr/lib" "$OUT/usr/share/vulkan/icd.d" "$OUT/opt/dxvk/x64"
for f in "$X"/usr/lib/aarch64-linux-gnu/*.so*; do b=$(basename "$f"); case $b in libvulkan_lvp.so|libLLVM*|libxml2*|libicuuc*|libicudata*|libdrm.so*|libelf*|libexpat*|libffi*|libedit*|libwayland-client*|libX11-xcb*|libxcb-*|libxshmfence*|libsensors*|libpciaccess*|libtinfo*|libtic*|libdrm_amdgpu*) [ -e "$RF/usr/lib/$b" ] || cp -a "$f" "$OUT/usr/lib/";; esac; done
cat > "$OUT/usr/share/vulkan/icd.d/lvp_icd.json" <<'JSON'
{ "ICD": { "api_version": "1.4.318", "library_path": "/data/data/com.zedokmusic/files/root/usr/lib/libvulkan_lvp.so" }, "file_format_version": "1.0.1" }
JSON
mkdir -p "$G/apk" && [ -f "$G/apk/assets/dxwrapper/dxvk-2.4.1.tzst" ] || unzip -q -o "$WORK/Winlator_11.2.apk" 'assets/dxwrapper/dxvk-2.4.1.tzst' -d "$G/apk"
mkdir -p "$G/dxvk" && zstd -dc "$G/apk/assets/dxwrapper/dxvk-2.4.1.tzst" | tar -xf - -C "$G/dxvk" && cp "$G"/dxvk/system32/*.dll "$OUT/opt/dxvk/x64/"
echo "GPU overlay: $(du -sh "$OUT" | cut -f1) in $OUT"
