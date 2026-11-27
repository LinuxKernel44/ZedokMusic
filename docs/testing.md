# Tests

## Kotlin unit tests
`./gradlew testDebugUnitTest` — project format migrations (`ProjectFormatTest`, witness projects in `app/src/test/resources/projects`), undo history (`HistoryTest`), project store, MIDI parser, kit mapper, update checker, audio helpers.

## Native tests (desktop, no phone needed)
All live in `native/dsptest` and need only `g++`.

| Script | What it checks |
|---|---|
| `run_all.sh [rounds]` | builds everything; `zfuzz`: random parameters and notes through every built-in plugin (no NaN, no runaway level, block time); the engine golden renders; regenerates the factory presets (which also checks that each one renders cleanly) |
| `run_engine.sh` | **Engine golden renders**: the real `engine.cpp` (Oboe replaced by a stub in `stubs/`) renders seven scenes through the same calls the app makes — clips with gain / pan / width / mute / solo, a built-in instrument with notes, drums, parameter automation, insert effects and a master effect, a small song — and compares frames, peak, RMS (within 0.02 dB) with `golden/engine.txt`. Every scene is rendered twice: a scene whose two renders differ is reported as non-deterministic |
| `run_engine.sh --strict` | also compares a checksum of every output sample: use it when **refactoring** the engine (phase 0.5 and later), where the sound must stay bit-for-bit identical |
| `run_engine.sh --update` | rewrites `golden/engine.txt`: only when a change is *meant* to alter the sound, and say so in the commit |
| `render.cpp` (`/tmp/zrender`) | renders one plugin with notes / parameters to a WAV and prints peak, RMS, DC and CPU use |
| `analyze.py`, `onsets.py` | spectrum, band energy, onsets of a rendered WAV |

The checksums in the golden file are for x86-64 compiled by `run_engine.sh` (`-O2 -ffp-contract=off`); on another CPU or compiler use the level check (without `--strict`).

## Tests with a real plugin on the phone
`native/testfx` builds tiny Windows VST2 plugins (`build-vst2test.sh`). `ZTest2Tempo` clicks on every beat of the host position: export a project with it and measure the spacing of the clicks to check the tempo given to plugins (0.49.6: 0.6667 s at 90 BPM, 0.6 s at 100, 0.5 s at 120).
