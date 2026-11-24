#!/bin/bash
# Builds app/src/main/assets/root.tar.zst: the glibc aarch64 rootfs (Wine 10.10 x86_64 + Box64 0.4.4) taken from the
# Winlator 11.2 release (MIT), with its hard-coded install prefix rewritten for ZedokMusic.
#
# glibc, Box64 and Wine have the prefix compiled into their binaries, so the replacement must have the SAME LENGTH
# (36 bytes): /data/data/com.winlator/files/rootfs  ->  /data/data/com.zedokmusic/files/root
# That is why the application id must stay "com.zedokmusic" and the rootfs directory must be "files/root".
#
# Needs: gh (or curl), unzip, zstd, tar, perl. ~1.5 GB of temporary disk space.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$HERE/build/rootfs-work}
OUT=${OUT:-$HERE/app/src/main/assets}
OLD="/data/data/com.winlator/files/rootfs"
NEW="/data/data/com.zedokmusic/files/root"
[ ${#OLD} -eq ${#NEW} ] || { echo "prefix length mismatch"; exit 1; }

mkdir -p "$WORK" "$OUT"
cd "$WORK"
if [ ! -f Winlator_11.2.apk ]; then
  curl -fL -o Winlator_11.2.apk https://github.com/brunodev85/winlator/releases/download/v11.2.0/Winlator_11.2.apk
fi
rm -rf apk rf && mkdir apk rf
unzip -q -o Winlator_11.2.apk 'assets/rootfs.tzst' 'assets/box64/box64-*.tzst' -d apk
zstd -dc apk/assets/rootfs.tzst | tar -xf - -C rf
zstd -dc apk/assets/box64/box64-*.tzst | tar -xf - -C rf
# Rewrite the prefix in every regular file that contains it.
export OLD NEW
grep -rlaF "$OLD" rf | while read -r f; do
  [ -L "$f" ] || perl -0pi -e 's/\Q$ENV{OLD}\E/$ENV{NEW}/g' "$f"
done
if grep -rlaF "$OLD" rf >/dev/null; then echo "prefix still present somewhere"; fi
chmod -R u+rwX rf
# GUI overlay (Xvfb + shims): built from Ubuntu packages, merged after the prefix rewrite.
RF="$WORK/rf" WORK="$WORK" "$HERE/tools/build-gui-overlay.sh"
cp -a "$WORK/gui-overlay/." rf/
chmod -R u+rwX rf
tar --numeric-owner --owner=0 --group=0 -C rf -cf - . | zstd -19 -T0 -q -f -o "$OUT/root.tar.zst"
echo "2" > "$OUT/root.version"
ls -la "$OUT"
