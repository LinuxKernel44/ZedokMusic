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
| AndroidX / Jetpack Compose / Material 3 | see `app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack |

## What is changed in the bundled runtime

`tools/build-rootfs.sh` downloads the official Winlator 11.2 release and rewrites one hard-coded install prefix
(`/data/data/com.winlator/files/rootfs` -> `/data/data/com.zedokmusic/files/root`, same length) inside the binaries.
No other change is made. The unmodified upstream sources for Wine, glibc and Box64 are available at the links above;
the exact binaries come from the Winlator release linked in the table.

VST is a trademark of Steinberg Media Technologies GmbH. Podolski and other plugins are the property of their authors
and are **not** distributed with ZedokMusic.
