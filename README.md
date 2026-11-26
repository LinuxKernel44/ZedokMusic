# ZedokMusic

A digital audio workstation for Android that runs **real Windows VST3 plugins**.

> **Status: alpha (0.8.0).** It works end to end on a Galaxy S10 (Android 12), but it is young. Expect rough edges — see *Known limitations*.

## What it does

- Multitrack arrangement, piano roll, mixer (gain, pan, mute/solo, level meters), tempo and looping, on-screen keyboard.
- **Zedok native plugins** (no Windows runtime involved, they run inside the audio engine): **Zedok Hybrid** (analog, wavetable and FM synthesizer with unison, two filters, three envelopes, three LFOs, a modulation matrix, an arpeggiator and built-in chorus, delay and reverb), **Zedok Grain** (granular synthesizer: your own samples or built-in textures, scrub and freeze, polyphonic or mono/legato), **Zedok Drums** (16-pad synthesized drum machine), **Zedok EQ + Comp** (6-band equalizer with live spectrum, compressor and brickwall limiter), **Zedok Space** (reverb and tempo-synced delay) and **Zedok Color** (chorus, ensemble, flanger, phaser, saturation, bit crusher). They have their own touch screens and about 375 factory presets in a browser with categories, search, favourites, previous/next, A/B, your own presets (saved in the ZedokMusic folder, shared by all projects) and import/export. **Sample drum kits** in Zedok Drums: import a kit folder (one-shot pack, multikit or SFZ) with the Android folder picker; the files the kit uses are copied into the project's own Kits folder. A sound browser (preview, favourites, search) loads single sounds onto pads. See `docs/native-plugins.md`.
- **VST2 plugins (64-bit `.dll`)** are hosted next to VST3: they are found in `Plugins/VST2`, `C:\Program Files\VSTPlugins` (where most VST2 installers put them), `Steinberg\VstPlugins`, and in the phone storage in folders with "vst" in their path; parameters, MIDI, automation, the plugin's own editor and the desktop licence/setup window work as for VST3. A `.dll` is checked once; files that are not VST2 plugins are ignored.
- **Init preset**: the first entry of every preset browser (built-in plugins and the Windows plugins' presets list): every control back to its default and everything loaded removed (samples, kit). If you changed the current sound, a clear message asks first.
- **Project scale**: the button in the piano roll header (e.g. "C major") sets the project's root and scale (major, minor, modes, pentatonics, blues...); rows in the scale are lighter, the others darker, the root is tinted; saved with the project.
- **Note names everywhere**: every key of the on-screen keyboard and of the piano roll shows its name with the octave (C, C#, D ... B), and so does every note in the piano roll.
- **Block screenshots** (Settings → Display, off by default): the phone's screenshot gestures cannot act in the app (uses the secure-window flag, which also blocks other screenshots and recordings; debug builds ignore it).
- **Lit keys**: on the on-screen keyboard and the piano roll's keys, a key takes the track's colour while it sounds: touched with a finger, played from a MIDI keyboard, previewed, or played by the notes of the track at the playhead.
- **Rounded screen corners**: the app keeps a side margin so buttons are not cut by the rounded corners of the screen; automatic from the phone's corner radius, or None / Small / Medium / Large in Settings → Display.
- **On-screen keyboard size**: drag the handle in the middle of the Keys bar up or down; the height is remembered.
- **Hybrid preset preview**: choosing a preset in Zedok Hybrid (list, ◀ ▶) plays C4 for a moment so you hear it at once; Settings → Plugins lets you pick C5 or turn it off.
- **Piano roll selection (like FL Studio)**: rectangle selection (long press in empty space then drag, or the **Select** button), tap to add or remove notes in Select mode, drag a selected note to move the whole selection, drag a right end to stretch all selected notes. A floating bar over the selection offers **Clone** (like Ctrl+B: copies after the selection and selects the copies), Copy, Cut and Delete; a row at the bottom offers transpose (±1, ±12), velocity, Quantize, length, Select all and Invert; **Paste** appears at the playhead when nothing is selected. ↶ ↷ undo and redo the notes (50 steps per track).
- **Sound browser in the Tracks view** (♪ button in the ruler header): your drum kit folder as a folder tree, the sounds of the project, and the plugins; docked beside the tracks or as a drawer (⇄ button in the browser; an upright phone always uses the drawer). Drag with a long press on a row or by the ⋮⋮ handle: a sound onto an audio track makes a clip at that position (several selected sounds follow one another), onto a Zedok Drums track opens a 16-pad grid to drop on a pad, a folder onto a Zedok Drums track imports it as a kit, into the empty zone below the tracks creates a new track (audio, or Zedok Drums for a kit; an instrument plugin makes an instrument track), an effect plugin onto a track adds it. Files are copied into the project.
- **Drum kit folder**: chosen once in Settings → Storage, scanned once, in parallel (thousands of sounds in a few seconds, no limit on their number; the index is remembered, scanned again only when you change the folder there or tap Rescan); the drum sound browser always shows it as a folder tree (fold / unfold each folder, Collapse all).
- **Phone storage as a drive**: the Windows desktop has an `F:` drive that is the whole phone storage (`/storage/emulated/0`), next to `C:` (Windows), `D:` (the ZedokMusic folder) and `E:` (app files). Windows plugins (`.vst3`) found anywhere in the phone storage (a few folders deep) are offered in the plugin lists and in the plugin manager, and load through `F:`.
- **Plugin manager** (Settings → Plugins): for each Windows plugin, choose whether it is offered as an instrument, an effect or both (when a plugin reports the wrong type), re-detect its type, or hide it from the lists.
- **Plugin parameters**: every VST3 parameter is listed with the plugin's own value text, searchable, and saved with the project.
- **Plugin editors**: open a plugin's own window from the **Plugin → Editor** button. Wine draws it on an embedded X server (Xvfb) and the app mirrors it live and forwards your touches.
- **Parameter automation**: the **Auto** tab draws a lane per plugin parameter (tap = add or select a point, drag = move, double-tap = delete; each point sets the curve after it: line, step or smooth). With REC armed and the transport running, moving a knob in the plugin editor or the parameter list records into the lane. Lanes are saved with the project and play back (also in the offline export).
- **Settings** (gear button): audio output/input device (USB interfaces), latency, exclusive mode and a live stream report; autosave, play-cursor follow, piano roll grid and default note length, default export format; plugin editor refresh rate and a verbose Wine log; MIDI auto-connect and channel filter; keep-screen-on, orientation (landscape locked by default; Auto-rotate and Portrait are one tap away), full screen and drawing under the notch (both on by default); storage usage with clean-up actions and a shareable diagnostics report; version, licenses and reset.
- **Insert effects**: every mixer track and the master have an **FX** button: a chain of up to 10 Windows VST3 effects in series, each in its own Wine host, with bypass, reorder, remove, the plugin's own editor, parameter list, presets and automation (Plugin and Auto tabs, chip row to pick the instrument or an effect). The engine delays the other tracks so everything stays in sync (about 3 ms per effect in the longest chain), and exports apply the effects exactly with no added latency. Effects are installed like instruments (Install .exe or copy the .vst3); the app reads each plugin's type to list instruments and effects separately.
- **Updates** (Settings → Updates): the app checks the project's GitHub Releases once a day (or on demand), shows the release notes, downloads the APK (resumable), verifies its SHA-256 and that it is signed with the same key as the installed app, then opens Android's installer after saving your project. Nothing is installed without you, nothing about you is sent, and a version can be skipped. Android asks once to allow ZedokMusic to install apps.
- **Windows desktop** (Settings → Windows): a complete Wine desktop full screen, like a Winlator container, with a taskbar (one button per window, clock), a Start menu (file manager, registry editor, task manager, command prompt, notepad, WordPad, control panel, uninstaller, Wine configuration, run a program or installer, shortcuts to the Installers and VST3 folders), drag windows by their title bars, Keys for the soft keyboard. D: is your ZedokMusic folder, C: the Windows drive. The project is saved and its plugins stop while it is open (it needs the whole X screen) and they restart when you close it. **Settings → Windows → Open a plugin in the desktop** (or the Start menu's *Open a plugin…*) opens one plugin's own editor in a normal window there: for a plugin that asks for a licence or an activation once; what you set is stored in the Windows environment and kept.
- **Direct3D plugins** (Serum 2 and others whose window is drawn with Direct3D 11): Wine's Direct3D is replaced by DXVK on Vulkan. On phones with an **Adreno GPU** it runs on the GPU (Mesa Turnip; a small Vulkan layer of ours, `native/gpulayer`, puts the frames on the app's X screen); elsewhere it falls back to software (Mesa lavapipe, on the CPU). Settings → Plugins → *Graphics for plugin windows* chooses; *Direct3D plugins (DXVK)* turns it off.
- **Audio export**: **Add → Export audio…** renders the whole project (plugins included, plus a 2 s tail) or the loop region to WAV (16/24-bit or float), **FLAC** (16/24-bit, lossless), **MP3** (320 or 192 kbps) or AAC (.m4a, 256 kbps), either as a full mix or one file per track (stems), in `Exports/`. **Add → Exported files…** lists them with play, share (to any app) and delete.
- **Audio recording** from the device microphone or a USB audio interface (stereo float WAV, latency-compensated), and **MIDI input** from USB and Bluetooth LE keyboards, recorded live into the piano roll.
- Audio engine in C++ on [Oboe](https://github.com/google/oboe) (AAudio, exclusive mode when available), WAV import and offline WAV export.
- **Windows VST3 plugins (64-bit)** are hosted by a small Windows program running under **Wine + Box64** inside the app. Audio and MIDI travel
  through shared memory, one block ahead, so the audio thread never waits on Wine.
- Your whole Windows environment is visible: Wine's `C:` drive, your plugins, projects and samples live in a folder you choose. Like FL Studio, every project has its own folder with everything it needs:

  ```
  Projects/<Name>/<Name>.zedok   the project
                  Samples/       imported samples and recordings (Add sample → Import…)
                  Exports/       rendered audio (File → Export audio…)
                  Presets/       plugin presets (Plugin tab → Presets)
                  Backup/        automatic copies of earlier saves
  ```

  The **File** menu has New, Open, Save, Save as, Save new version (`Song` → `Song_2`), Rename, Collect samples into project, and Project info with backups you can restore. Plugins and installers stay shared, as does Wine's `C:` drive. Projects, samples and exports from before this layout are moved or still listed.
- Interface in US English.

## Requirements

- An **arm64** Android device, **Android 9 or newer**. A recent flagship is strongly recommended: plugins run under CPU emulation.
- About 450 MB of internal storage for the runtime and about 400 MB in the folder you choose.
- Windows `.vst3` files that you own (none are included).

## Install

1. Download the APK from the [Releases](../../releases) page and install it (allow installs from your browser or file manager).
2. Open ZedokMusic, tap **Install**, allow storage access, then choose or create a folder (not the top level of your storage).
   The first start takes about a minute and a half.
3. Either run the plugin's Windows installer: **Add → Install plugin (.exe)…**, **Import…** the `.exe`/`.msi` (it is copied to `<your folder>/Installers`), **Run** it, and use its windows with touch (**Keys** brings up the keyboard). Plugins installed to `C:\Program Files\Common Files\VST3` are found automatically. Or copy your `.vst3` plugins into `<your folder>/Plugins/VST3`. Plugins that keep resources in a data folder (for example u-he's `Podolski.data`) need that folder **next to the `.vst3` file**, otherwise they show a "data missing" dialog and their editor cannot open.
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

- Plugin editors are mirrored at about 30 fps; up to three stay open at once (opening a fourth closes the oldest), and on wide screens two editors are shown side by side, with a **Full** button that gives the editor the whole area under the transport. Input: one finger = mouse, two-finger drag = mouse wheel, two-finger tap = right click, the **Keys** button opens the keyboard. (The two-finger gestures and typing are implemented but only lightly tested.)
- Each plugin host uses roughly 40% of a CPU core while playing (about 13% when idle).
- MIDI input and audio recording are implemented but only the microphone path has been tested on hardware; USB and Bluetooth MIDI devices are untested (the MIDI parser has unit tests).
- Automation curves are line, step or smooth only, and are stored per parameter in beats.
- Installers run under Wine on a 1024×768 virtual desktop; anything that needs a driver, a license service or a GPU may fail. If an installer asks to restart Windows, just close it.
- VST3 only (no VST2, no CLAP). Plugins that need a GPU, an installer-based license (iLok, etc.) or Windows-only drivers may not work.
- Heavy plugins can exceed the CPU budget on older phones. The status line shows xruns.
- Tested on one device so far (the wide layout only on the same phone with an emulated large screen). SD cards and Android 13+ storage behaviour with `targetSdk 28` are untested.
- The tempo passed to plugins is fixed at 120 BPM.

## License

ZedokMusic is MIT-licensed. Bundled third-party components keep their own licenses: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
