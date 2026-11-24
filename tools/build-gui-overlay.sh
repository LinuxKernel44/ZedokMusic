#!/bin/bash
# Builds the GUI overlay merged into the rootfs: an unmodified Xvfb from Ubuntu 24.04 (arm64, glibc 2.39 = same glibc as the
# Winlator rootfs), patched to run from the app's private storage, plus two small shims. See docs/gui-spike.md.
#   usr/bin/Xvfb, xkbcomp, sh -> /system/bin/sh   (Xvfb is started with its working directory = usr/bin)
#   usr/lib/<libs the rootfs lacks>, usr/share/X11/xkb
#   usr/lib/libxshim.so    LD_PRELOAD shim: link() and /tmp/.X11-unix redirection (native/xshim/xshim.c)
#   usr/lib/libzshmstub.so BOX64_LD_PRELOAD stub: XShmCreateImage -> NULL (native/xshim/zshmstub.c)
# Needs: curl, ar, tar, xz, python3, patchelf, aarch64-linux-gnu-gcc, gcc (x86_64 host).
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HERE/build/rootfs-work}
RF=${RF:-$WORK/rf}                    # extracted rootfs (to avoid shipping libraries it already has)
OVL=${OVL:-$WORK/gui-overlay}
G=$WORK/gui-build; mkdir -p "$G/debs" "$G/x"
MIRROR=https://ports.ubuntu.com/ubuntu-ports
PKGS="xvfb xserver-common x11-xkb-utils xkb-data libxfont2 libfontenc1 libpixman-1-0 libxkbfile1 libunwind8 libgl1 libglvnd0 libglx0 \
libaudit1 libbsd0 libcap2 libcap-ng0 libgcrypt20 libgpg-error0 liblz4-1 liblzma5 libmd0 libpcre2-8-0 libselinux1 libsystemd0 libxau6 libxdmcp6 libzstd1"

# Resolve package file names from the Ubuntu indexes (updates pocket first).
python3 -I - "$G" "$MIRROR" $PKGS <<'PY'
import sys, urllib.request, lzma, os
g, mirror, pkgs = sys.argv[1], sys.argv[2], sys.argv[3:]
idx = {}
for pocket in ("noble-updates", "noble-security", "noble"):
    for comp in ("main", "universe"):
        url = f"{mirror}/dists/{pocket}/{comp}/binary-arm64/Packages.xz"
        text = lzma.decompress(urllib.request.urlopen(url).read()).decode()
        for block in text.split("\n\n"):
            f = dict(l.split(": ", 1) for l in block.split("\n") if ": " in l and not l.startswith(" "))
            n = f.get("Package")
            if n in pkgs and n not in idx: idx[n] = f["Filename"]
missing = [p for p in pkgs if p not in idx]
if missing: sys.exit("not found: " + " ".join(missing))
with open(os.path.join(g, "list.txt"), "w") as o:
    for n, fn in idx.items(): o.write(f"{n} {mirror}/{fn}\n")
PY
while read -r name url; do [ -f "$G/debs/$name.deb" ] || curl -fsSL -o "$G/debs/$name.deb" "$url"; done < "$G/list.txt"
rm -rf "$G/x" "$OVL"; mkdir -p "$G/x" "$OVL/usr/bin" "$OVL/usr/lib" "$OVL/usr/share/X11"
for d in "$G"/debs/*.deb; do (cd "$G/x" && ar x "$d" data.tar.zst 2>/dev/null && zstd -dc data.tar.zst | tar -xf - && rm -f data.tar.zst) || \
  (cd "$G/x" && ar x "$d" data.tar.xz && tar -xJf data.tar.xz && rm -f data.tar.xz); done
cp "$G/x/usr/bin/Xvfb" "$G/x/usr/bin/xkbcomp" "$OVL/usr/bin/"
cp -r "$G/x/usr/share/X11/xkb" "$OVL/usr/share/X11/"
for f in "$G"/x/usr/lib/aarch64-linux-gnu/*.so*; do b=$(basename "$f"); [ -e "$RF/usr/lib/$b" ] || cp -a "$f" "$OVL/usr/lib/"; done
# Equal-length string patches so that Xvfb never needs /usr/bin, /tmp, /var/lib/xkb or /bin/sh (absent or forbidden on Android).
python3 -I - "$OVL/usr/bin/Xvfb" <<'PY'
import sys
p = sys.argv[1]; d = bytearray(open(p, "rb").read())
def sub(old, new):
    o = old + b"\0"; n = new + b"\0"; n += b"\0" * (len(o) - len(n)); assert len(n) == len(o)
    i = 0
    while True:
        i = d.find(o, i)
        if i < 0: break
        d[i:i + len(o)] = n; i += len(o)
sub(b"/usr/bin", b"."); sub(b"/tmp", b"."); sub(b"/var/lib/xkb/", b"./"); sub(b"/tmp/", b"./"); sub(b"/bin/sh", b"./sh")
open(p, "wb").write(d)
PY
P=/data/data/com.zedokmusic/files/root/usr/lib
patchelf --set-interpreter $P/ld-linux-aarch64.so.1 --set-rpath $P --force-rpath "$OVL/usr/bin/Xvfb" "$OVL/usr/bin/xkbcomp"
ln -sf /system/bin/sh "$OVL/usr/bin/sh"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -o "$OVL/usr/lib/libxshim.so" "$HERE/native/xshim/xshim.c" -ldl
gcc -shared -nostdlib -fPIC -O1 -o "$OVL/usr/lib/libzshmstub.so" "$HERE/native/xshim/zshmstub.c"
chmod -R u+rwX "$OVL"
echo "GUI overlay: $(du -sh "$OVL" | cut -f1) in $OVL"
