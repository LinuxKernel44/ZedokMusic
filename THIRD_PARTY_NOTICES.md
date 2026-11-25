# Third-party components

ZedokMusic's own code is MIT-licensed (see `LICENSE`). The APK also bundles the following, each under its own license.
They run as separate programs inside the app's private runtime; none of them is modified except as noted.

| Component | Version | License | Source |
|---|---|---|---|
| Wine (x86_64 build) | 10.10 | LGPL-2.1-or-later | https://www.winehq.org / https://gitlab.winehq.org/wine/wine |
| Box64 | 0.4.4 | MIT | https://github.com/ptitSeb/box64 |
| GNU C Library (aarch64) | as shipped in the Winlator 11.2 rootfs | LGPL-2.1-or-later | https://www.gnu.org/software/libc/ |
| Winlator 11.2 rootfs (source of the three above and of the supporting libraries) | 11.2 | MIT (project) | https://github.com/brunodev85/winlator/releases/tag/v11.2.0 |
| Oboe | 1.10.0 | Apache-2.0 | https://github.com/google/oboe |
| Zstandard (decoder only, `app/src/main/cpp/third_party/zstd`) | 1.5.7 | BSD-3-Clause | https://github.com/facebook/zstd |
| Steinberg VST 3 interfaces (`vst3_pluginterfaces`, used to build `zhost.exe`) | 3.8+ | MIT | https://github.com/steinbergmedia/vst3_pluginterfaces |
| Xvfb, xkbcomp, xkb-data, libXfont2, libxkbfile, libXau, libXdmcp (X server for plugin editors, `tools/build-gui-overlay.sh`) | Ubuntu 24.04 (noble) packages | X.Org licenses (MIT/BSD-style) | https://packages.ubuntu.com/noble/xvfb (source packages on the same site) |
| libpixman, libunwind, libglvnd (libGL, libGLX, libGLdispatch), libselinux, liblz4, liblzma, libpcre2, libzstd, libcap, libcap-ng, libmd, libbsd (runtime libraries of Xvfb) | Ubuntu 24.04 (noble) packages | MIT / BSD / public domain / dual BSD-GPL | https://packages.ubuntu.com/noble/ |
| libgcrypt, libgpg-error, libsystemd, libaudit (runtime libraries of Xvfb) | Ubuntu 24.04 (noble) packages | LGPL-2.1-or-later | https://packages.ubuntu.com/noble/ |
| Mesa lavapipe (`libvulkan_lvp.so`, software Vulkan driver, Ubuntu 24.04 arm64 package 25.2.8) | 25.2.8 | MIT | https://www.mesa3d.org / https://packages.ubuntu.com/noble/mesa-vulkan-drivers |
| LLVM (`libLLVM.so.20`, used by lavapipe) | 20.1 | Apache-2.0 with LLVM exceptions | https://llvm.org |
| ICU (`libicuuc`, `libicudata`), libxml2, libdrm, libelf, libexpat, libffi, libedit, libwayland-client, libxcb-*, libxshmfence, libsensors, libpciaccess (runtime libraries of lavapipe) | Ubuntu 24.04 (noble) packages | Unicode / MIT / LGPL-2.1+ / BSD-style | https://packages.ubuntu.com/noble/ |
| DXVK (d3d8/d3d9/d3d10core/d3d11/dxgi, from the Winlator 11.2 release) | 2.4.1 | zlib | https://github.com/doitsujin/dxvk |
| AndroidX / Jetpack Compose / Material 3 | see `app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack |

## What is changed in the bundled runtime

`tools/build-rootfs.sh` downloads the official Winlator 11.2 release and rewrites one hard-coded install prefix
(`/data/data/com.winlator/files/rootfs` -> `/data/data/com.zedokmusic/files/root`, same length) inside the binaries.
No other change is made. The unmodified upstream sources for Wine, glibc and Box64 are available at the links above;
the exact binaries come from the Winlator release linked in the table.

VST is a trademark of Steinberg Media Technologies GmbH. Podolski and other plugins are the property of their authors
and are **not** distributed with ZedokMusic.

## LAME 3.100 (MP3 encoder)

`libmp3lame` from https://lame.sourceforge.io/ (lame-3.100.tar.gz), unmodified, under the GNU LGPL 2.1 (full text in
`app/src/main/cpp/third_party/lame/COPYING`). It is built as a separate shared library (`libmp3lame.so`) that the app links
dynamically, so it can be replaced by a modified build. The source in this repository is the complete corresponding source.
