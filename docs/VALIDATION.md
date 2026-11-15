# Phase 6 validation

Development build **0.6.0-phase6 / versionCode 6**. The same public development certificate permits installing over Phase 5 without uninstalling.

## Current checks

- All five native suites passed locally: core, synth, effects, project and sampler.
- The sampler suite exercises chromatic frequency, stereo, forward/reverse looping, gate/one-shot/choke, invalid wires, shared voice ownership, project/sample replacement, audition/panic and sampled MIDI WAV export. Callback allocation/free trackers both remain zero.
- All **87 Kotlin/JUnit tests passed** locally (13 new sampler/navigation/model tests plus 74 regressions). ARM64/x86_64 APK and instrumentation APK assembled; lint passed with 0 errors. Android emulator and GitHub CI evidence are recorded after the run completes.
- Added Android checks exercise physical Back key events through pad settings, browser details, nested sample selection, Gemini settings, clip properties and Arrangement; factory kit creation; resampling; sampler selection across rotation; ZIP rollback; portable unused samples/favorites; and actual JNI sampled WAV output and audition commands.

## Limits

No physical Xperia, USB hardware, thermal/latency measurements or live Gemini calls were performed here. Android emulator results establish functional behavior on the configured test device, not phone performance. This is a development release. Pitch-linked sampling is implemented; independent time stretching is not. Extreme transposition and 128 simultaneous voices are not a verified quality/performance guarantee. ZIP is supported; RAR is not. Version 2 projects open in this update, while older builds only read version 1.

## Phase 5 evidence

The prior app commit `9db19755106a26173deea1b3cbd557f78733b88c` passed [CI 43690764693](https://github.com/weskestis/AstraStudio-Android/actions/runs/43690764693): 74 unit tests, 4 Android instrumentation tests and all four then-existing native/sanitizer suites. Its full report and APK checksum remain in git history.
