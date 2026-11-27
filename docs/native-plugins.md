# Zedok native plugins

Six instruments and effects built into the audio engine. They need no Windows runtime and no Wine process: the DSP is C++ (`native/dsp`), run synchronously on the audio thread.

| Plugin | Kind | What it is |
|---|---|---|
| Zedok Hybrid | instrument | 2 oscillators (analog band-limited, wavetable, 2-operator FM) with unison up to 8, sub, noise, 2 filters (LP12/LP24/ladder/HP/BP/notch/comb), 3 envelopes, 3 LFOs, 8-slot modulation matrix, arpeggiator, mono/legato/glide, chorus + delay + reverb. 10 wavetables of 32 frames are generated at start-up. |
| Zedok Grain | instrument | Granular synthesizer. Source: your sample (WAV from the project or shared library) or 6 built-in textures; position, scan (and freeze), size, density, pitch, spreads and jitters, direction, grain shape and skew; poly / mono / legato; filter, 2 envelopes, 2 LFOs, 6-slot matrix, delay + reverb. Tap or drag on the waveform to scrub. |
| Zedok Drums | instrument | 16 pads (MIDI notes 36-51), 16 synthesized drum models, per pad tune / decay / tone / punch / drive / level / pan / choke group; glue compressor, saturation, kit tone and room. |
| Zedok EQ + Comp | effect | 6 bands (bell, shelves, cuts up to 48 dB/oct, notch, band-pass, tilt), stereo / mid-side / left / right, live spectrum; compressor (peak/RMS, soft knee, auto release, lookahead, sidechain high-pass, parallel mix) and a lookahead brickwall limiter. |
| Zedok Space | effect | Reverb (room, plate, hall, shimmer: an 8-line feedback delay network) and stereo delay (tempo-synced or free, ping-pong, filtered and saturated feedback, wobble, ducking). |
| Zedok Color | effect | Chorus, ensemble, flanger, phaser, 7 saturation types with 2x oversampling, bit crusher, in either order, with dry/wet. |

## How they plug into the app

A native plugin is a `PluginBridge` whose "host" is a `zdsp::Processor`: the bridge memory is anonymous but laid out like the Wine one (`native/common/bridge.h`), so parameters (normalized 0..1), automation, project save and the engine's pipeline and delay compensation work unchanged. A track or insert whose plugin path is `zedok:<id>` (`hybrid`, `grain`, `drums`, `eqcomp`, `space`, `color`) is created with `NativeEngine.createNativePlugin`. Each plugin declares its parameters (`ParamSpec`: name, section, range, curve, labels); the generic parameter list, automation and the custom Compose screens (`ui/nplug`) are all driven by that table. Plugins return display data (waveform, spectrum, gain reduction, grains...) through `telemetry()`, and keep non-parameter state (the sample of Grain) through `setState()`.

## Zedok Sampler
One sample (WAV, state key `sample`) played over the keyboard. Pages: **Sample** (waveform with draggable start / end and loop markers, loop mode Off / Forward / Ping-Pong / Until Release with crossfade, reverse, normalize, fade in / out, key tracking, one-shot, slices 2-32 mapped to the keys from the root note, Resample or Stretch time mode, sample BPM, tempo sync), **Envelopes** (volume, filter and pitch: delay, attack, hold, decay, sustain, release), **Filter & LFO** (LP12 / LP24 / HP12 / BP12 / notch; LFO with free or tempo-synced rate, delay and four destinations: pitch, volume, pan, cutoff) and **Main** (volume, pan, velocity, coarse / fine pitch, pitch bend, voices 1-32, poly / mono / legato, glide, cut itself, Eco / High interpolation). Stretch mode is a two-grain overlap-add: pitch and time are independent but tonal material shows the usual granular colouring. Offline tests (`native/dsptest`) check the pitch of the root note, +12 st, stretch that keeps the pitch, tempo sync lengths, slices, reverse and the filter envelope.

## Zedok Piano
A sampled concert grand with its own engine. The sounds are the **Salamander Grand Piano V3** (Yamaha C5, recorded by Alexander Holm, licence **CC BY 3.0**), converted by `tools/build-piano-pack.py` into one pack of Ogg Vorbis files (`assets/piano.zpk`, about 60 MB: 16 velocity layers of every third key, key-off noises, pedal noises; tails are trimmed, the longer the lower the note). The app copies the pack to its data folder at the first start; the plugin memory-maps it, keeps the first 0.25 s of every sound decoded, and a worker thread decodes the rest when a key is struck (voices read what is decoded, so a note starts at once). stb_vorbis (public domain) decodes. Engine: velocity layers are cross-faded, keys between samples are re-pitched by at most a semitone, dampers release in a time that depends on the register (no dampers above F#6), key-off and pedal noises are played, restriking a key cuts its previous sound, a bank of 264 resonators (three partials of each of the 88 strings) lets the undamped strings ring in sympathy (held keys, or all strings with the pedal). The host sends no controllers, so the **sustain pedal and una corda are parameters** (automatable; above 50 % the dampers are lifted, in between a half pedal lengthens the release). Tuning: A4 415-466 Hz, Equal / Just / Pythagorean / Werckmeister III / Kirnberger III / Meantone with a key, stretch tuning, fine tune, humanize. Tone: low and high shelves, presence, lid, soft-pedal darkening; width, pan, a small reverb. `ZEDOK_DATA=<folder with piano.zpk>` points the offline test tools to the pack; they pace the render in real time for this plugin because of the worker thread.

## Sample kits (Zedok Drums)

Besides its synthesized sounds, each pad of Zedok Drums can play samples (sound type **Sample**).

* **Drum kit folder.** Chosen once in Settings → Storage → Drum kit folder (Android folder picker, Storage Access Framework); the app keeps read access and scans it one time (the index is stored in the app and loaded at start). It is scanned again only when you choose another folder there or tap Rescan. Nothing else ever asks for a folder.
* **Sound browser.** Load kit… and Load to pad N… open a browser that shows the library as a **folder tree** (folders and sub-folders keep their hierarchy: tap a folder to fold or unfold it; **Collapse all** folds everything, **Expand all** unfolds everything; the fold state is kept while the app runs; each folder has an Import kit link that imports that folder as a whole kit, and Import as kit at the top imports the whole library). Search and Favourites show flat lists. In the tree: every audio file in it (subfolders included) with a ▶ preview button, a ★ favourite star and a search box. Tap rows to select, then **Load to pad N** (replaces the pad) or **Add to pad N** (adds round robins / layers); the pad number steps forward after each load. The **Favourites** tab lists the starred sounds (kept in `<workspace>/Presets/sample_favorites.json`, shared by all projects). Chosen files are copied into the project like a kit.
* **Import as kit** (in the browser) detects what the folder is. The folder can be a flat pack of one-shots (also when sorted into Kicks/Snares/... folders), a **multikit** (a folder of kit folders: you choose which one), or hold **SFZ** instruments (key and velocity ranges, round robins).
* Files are laid out on the 16 pads from their names (kick, snare, clap, rim, closed/open hat, toms, cymbals, percussion). Velocity layers (`_v1`, `_soft`, `_hard`...) split the velocity range; files sharing a range (round robins, `_rr2`, trailing numbers) play in turn. Mapping logic: `model/KitMapper.kt` (unit tested).
* **Every file the kit uses is copied into the project**: `Projects/<name>/Kits/<kit>/...` plus `kit.zkit` (the pad layout). WAVs the engine reads (PCM 16/24/32, float32, mono/stereo) are copied as they are; mp3, flac, ogg, aiff, m4a and other formats are decoded to WAV with Android's MediaCodec. The project therefore does not depend on the original folder; Save as and Rename carry the kits along, and the pad states hold project-relative paths.
* **Add to pad…** puts hand-picked files on one pad (copied the same way); **Project kits** lists the kits stored in the project (switch between them, delete one); **Clear pad / Clear kit**.
* Per sample pad: tune, start, length, tone, drive, level, pan, one-shot or gate (stops with the note), reverse, choke group.

## Presets

* Factory presets are JSON in `app/src/main/assets/presets/<plugin>.json`, generated by `native/presets/build.sh` from the C++ recipes in `native/presets/*_presets.cpp` (readable values: Hz, ms, choice labels). The generator also level-matches every preset (instruments to a common loudness, EQ/Color presets to the level of their input), checks that each one renders cleanly (no NaN, no silence) and writes a report.
* User presets are `<workspace>/Presets/<plugin>/<Category>/<Name>.zpreset` (JSON: name, category, tags, values by parameter name, state), shared by every project. Favourites are in `<workspace>/Presets/favorites.json`. A preset lists only the parameters that differ from the default; loading one resets the others.
* The browser (`ui/nplug/PresetUi.kt`): categories, search (name, category, tag), favourites, previous/next, A/B, save, rename, delete, export (to `Exports/Presets`) and import.

## Testing

`native/dsptest/run_all.sh` builds the offline tools and runs a robustness test (random parameters and notes on every plugin: no NaN or runaway level), then regenerates the presets. `native/dsptest/render.cpp` renders a plugin to a WAV and prints its levels and CPU use (`render hybrid out.wav 3 --notes 60:0:1 "A Wave=Square"`), `analyze.py` measures spectra and aliasing. The tools also cross-compile with the NDK and run on a phone through `adb shell`.

Measured on a Galaxy S10 (one core, 5 s renders): Hybrid with 6 notes, unison 8 on both oscillators, ladder filter and all effects 10 %; Grain with 6 notes at 200 grains/s 19 %; Drums 4 %; Space, EQ + Comp and Color 3 % each.

Not covered by tests: how the presets sound. They were designed from sound-design knowledge and verified by measurement only.

## Init preset

Every preset browser starts with **Init preset**: all controls return to their defaults and everything loaded in the plugin is removed (samples and kits on the drum pads, the grain source...). If the sound was changed since it was selected (a control moved, something loaded), a confirmation explains what will be lost. For Windows plugins it is the first entry of the Presets list (controls only).

## VST2 hosting

`native/host/vst2.h` holds the VST2 side of `zhost.exe` (plugin binary interface structures written for this project, host callbacks, editor window, MIDI events). `native/testfx/build-vst2test.sh` builds two tiny VST2 test plugins (synth, effect).
