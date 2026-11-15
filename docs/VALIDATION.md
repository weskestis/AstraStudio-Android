# Phase 7 validation

Development version **0.7.0-phase7 / versionCode 7**. Six native regression suites pass in the host Release build and under AddressSanitizer/UndefinedBehaviorSanitizer, including conversion, stretch, tempo and float-WAV checks. All 103 Kotlin unit tests pass. The first Android run passed the new pitch/time/tempo/float cases but exposed excessive UI recomposition during existing workflows. Stable editor/session projections and conflated Main-thread publication address that issue; the Android suite must pass on the corrected build before delivery. [Quality fixtures and limitations](QUALITY.md).

The unchanged public development signing certificate supports in-place updates. Physical Xperia tests and live Gemini requests were not performed here.

---

# Phase 6 historical validation

Development build **0.6.0-phase6 / versionCode 6**. The same public development certificate permits installing over Phase 5 without uninstalling.

## Verified checks

App/test commit `3bda6152f2e7a8418ff396585dac0c08da1fd394` passed both jobs in [CI 43739785588](https://github.com/weskestis/AstraStudio-Android/actions/runs/43739785588).

- All five native suites passed locally and on GitHub: core, synth, effects, project and sampler. All five also passed with AddressSanitizer and UndefinedBehaviorSanitizer on GitHub.
- The sampler suite exercises chromatic frequency, stereo, forward/reverse looping, gate/one-shot/choke, invalid wires, overlapping unchoked pad retriggers, shared voice ownership, project/sample replacement, audition/panic and sampled MIDI WAV export. Callback allocation/free trackers both remain zero.
- All **88 Kotlin/JUnit tests passed** locally and on GitHub (14 new sampler/navigation/model tests plus 74 regressions). ARM64/x86_64 APK and instrumentation APK assembled; lint passed with 0 errors. 
- All **6 Android instrumentation tests passed**, with 0 failures, errors or skips, in 73.963 seconds on an API 34 x86_64 emulator configured to 1080 × 2520 at density 420. The checks exercise injected Android Back key events through pad settings, browser details, nested sample selection, Gemini settings, clip properties and Arrangement; factory kit creation; resampling; sampler selection across rotation; ZIP rollback; portable unused samples/favorites; and actual JNI sampled WAV output and audition commands.

- Screenshots were reviewed for the green/black drum grid, pad editor, sample details, resampling result, chromatic sampler and landscape arrangement. Back testing waits until each page is displayed and asserts trim fields close before the pad editor.

## Delivered APK

- File: `AstraStudio_Phase6_0.6.0.apk` — 28,556,396 bytes.
- SHA-256: `5f8a9b412eae8eaefebbaab21549632078915c27a1a364480f6d5e8e5d7f00f6`.
- Package `com.weskestis.astrastudio`, versionCode 6, minimum and target API 34; ARM64 and x86_64.
- APK signature verification passed. Certificate SHA-256: `1bff24ae64d74c99d9540a1ecc5acccc8479367f44772b305144d2c10ce4bd45`.
- APK 16 KiB zip alignment passed; every native library LOAD segment is aligned to at least 16 KiB.

## Limits

No physical Xperia, USB hardware, thermal/latency measurements or live Gemini calls were performed here. Android emulator results establish functional behavior on the configured test device, not phone performance. This is a development release. Pitch-linked sampling is implemented; independent time stretching is not. Extreme transposition and 128 simultaneous voices are not a verified quality/performance guarantee. ZIP is supported; RAR is not. Version 2 projects open in this update, while older builds only read version 1.

## Phase 5 evidence

The prior app commit `9db19755106a26173deea1b3cbd557f78733b88c` passed [CI 43690764693](https://github.com/weskestis/AstraStudio-Android/actions/runs/43690764693): 74 unit tests, 4 Android instrumentation tests and all four then-existing native/sanitizer suites. Its full report and APK checksum remain in git history.
