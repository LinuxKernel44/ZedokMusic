# ZedokMusic

A digital audio workstation for Android that runs **real Windows VST3 plugins**.

> **Status: alpha (0.3.0).** It works end to end on a Galaxy S10 (Android 12), but it is young. Expect rough edges — see *Known limitations*.

## What it does

- Multitrack arrangement, piano roll, mixer (gain, pan, mute/solo, level meters), tempo and looping, on-screen keyboard.
- **Plugin parameters**: every VST3 parameter is listed with the plugin's own value text, searchable, and saved with the project.
- **Plugin editors**: open a plugin's own window from the **Plugin → Editor** button. Wine draws it on an embedded X server (Xvfb) and the app mirrors it live and forwards your touches.
- **Audio recording** from the device microphone or a USB audio interface (stereo float WAV, latency-compensated), and **MIDI input** from USB and Bluetooth LE keyboards, recorded live into the piano roll.
- Audio engine in C++ on [Oboe](https://github.com/google/oboe) (AAudio, exclusive mode when available), WAV import and offline WAV export.
- **Windows VST3 plugins (64-bit)** are hosted by a small Windows program running under **Wine + Box64** inside the app. Audio and MIDI travel
  through shared memory, one block ahead, so the audio thread never waits on Wine.
- Your whole Windows environment is visible: Wine's `C:` drive, your plugins, projects and samples live in a folder you choose.
- Interface in US English.

## Requirements

- An **arm64** Android device, **Android 9 or newer**. A recent flagship is strongly recommended: plugins run under CPU emulation.
- About 450 MB of internal storage for the runtime and about 400 MB in the folder you choose.
- Windows `.vst3` files that you own (none are included).

## Install

1. Download the APK from the [Releases](../../releases) page and install it (allow installs from your browser or file manager).
2. Open ZedokMusic, tap **Install**, allow storage access, then choose or create a folder (not the top level of your storage).
   The first start takes about a minute and a half.
3. Copy your `.vst3` plugins into `<your folder>/Plugins/VST3`. Plugins that keep resources in a data folder (for example u-he's `Podolski.data`) need that folder **next to the `.vst3` file**, otherwise they show a "data missing" dialog and their editor cannot open.
4. **Add → Instrument track (VST3)…**, pick a plugin, wait for the green dot, tap the pattern and write notes.

Every release APK is signed with the same key. Certificate SHA-256: see the release notes.

## How it works

```
Compose UI ── JNI ── C++ engine (Oboe) ──┐
                                          │  shared memory (events in, audio out)
                    Wine + Box64 ── zhost.exe ── your VST3 plugin
```

- `app/src/main/cpp/` — engine (`engine.cpp`), plugin bridge endpoint (`plugin_bridge.h`), WAV I/O, tar.zst extractor.
- `native/common/bridge.h` — the shared-memory protocol, used by both sides.
- `native/host/` — `zhost.exe`, the Windows-side VST3 host (cross-compiled with mingw-w64).
- `app/src/main/java/com/zedokmusic/` — UI (`ui/`), project model (`model/`), runtime installer (`wine/`), engine facade (`engine/`).

### Why `targetSdk 28` and the package name `com.zedokmusic`

Android 10+ forbids executing binaries from writable app storage for apps targeting API 29 or higher, and Wine/Box64 need exactly that.
ZedokMusic therefore targets API 28, which is **not accepted by Google Play** — releases are published here on GitHub instead.
glibc, Box64 and Wine also have their install prefix compiled in, so the app id and the `files/root` directory name are fixed
(the prefix is rewritten at build time to a string of identical length).

## Building

Needs: JDK 21, Android SDK + NDK 27, `gh` or `curl`, `unzip`, `zstd`, `perl`, and for the plugin host `mingw-w64`.

```sh
git clone --depth 1 https://github.com/steinbergmedia/vst3_pluginterfaces.git build/vst3_pluginterfaces
./tools/build-rootfs.sh     # downloads Winlator 11.2 and produces app/src/main/assets/root.tar.zst (~80 MB, not in git)
./tools/build-host.sh       # builds app/src/main/assets/zhost.exe
./gradlew :app:assembleDebug
```

For a signed release, put a keystore description in `~/.zedokmusic-signing/keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or point `ZEDOK_SIGNING_PROPS` at one, then run `./gradlew :app:assembleRelease`.
Debug builds contain adb test hooks (`DebugHooks`); release builds do not.

## Known limitations

- Plugin editors are mirrored at about 30 fps with single-touch input only (no keyboard, scroll wheel or multi-touch yet), one editor at a time. Each plugin host currently keeps one CPU core busy while the engine runs.
- MIDI input and audio recording are implemented but only the microphone path has been tested on hardware; USB and Bluetooth MIDI devices are untested (the MIDI parser has unit tests).
- Parameter automation over time is not available yet.
- VST3 only (no VST2, no CLAP). Plugins that need a GPU, an installer-based license (iLok, etc.) or Windows-only drivers may not work.
- Heavy plugins can exceed the CPU budget on older phones. The status line shows xruns.
- Tested on one device so far. Tablet layout, SD cards and Android 13+ storage behaviour with `targetSdk 28` are untested.
- The tempo passed to plugins is fixed at 120 BPM.

## License

ZedokMusic is MIT-licensed. Bundled third-party components keep their own licenses: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
