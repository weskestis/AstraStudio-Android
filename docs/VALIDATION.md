# Phase 3 validation

Validated November 15, 2026 UTC for **0.3.0-phase3 / versionCode 3**. These are host/build results; no Sony Xperia or Android emulator was available for installation, listening, UI interaction or thermal measurements.

| Check | Observed result |
| --- | --- |
| C++20 compilation, warnings as errors | Passed |
| Core regression suite | 12,350 assertions passed |
| Synthesis regression suite | 574,403 assertions passed |
| Effects regression suite | 1,293,757 assertions passed, including per-sample bounds checks |
| Realtime allocation interception | Zero C++ allocations during tested rendering, dispatch, configuration changes and panic |
| AddressSanitizer + UndefinedBehaviorSanitizer | All three native suites passed |
| ARM64 and x86_64 Android native builds | Passed |
| Kotlin/Compose and APK assembly | Passed |
| Kotlin JUnit | 17 passed: 6 MIDI, 5 synth state, 6 effects state/display cases |
| Android lint | 0 errors, 8 non-blocking warnings |
| APK v2 signature | Verified; same development certificate as Phases 1/2 |
| APK ZIP / packaged ELF alignment | 16 KB verified for all six native libraries |
| SDK and version metadata | min/compile/target 34, versionCode 3, 0.3.0-phase3 |
| GitHub Actions | Workflow verifies source hashes, generated schemas, native suites, sanitizers, preset rendering, APK build, JUnit and lint; inspect the run for its exact revision |
| Physical Xperia / USB MIDI / latency / thermals | Not tested here |
| Emulator / instrumentation / visual UI testing | Not performed |

APK: `AstraStudio_Phase3_0.3.0.apk`, 26,556,520 bytes.

SHA-256: `d83f5c43a7532ed5a389b03e0ebed6f756e91c25dbe9074f205b2a371c0bfab3`.

## New effects coverage

- Full wire roundtrip, malformed length, non-finite values, enum/routing errors, duplicate device order, threshold ordering and macro bounds.
- Limiter exact five-millisecond delay at 8/44.1/48/96/192/384 kHz, stereo linking, release and ceiling under impulses and adversarial peaks.
- Equal serial/parallel bypass delay, both saturator lane positions, oversampled-shaper dry alignment, all four shaping modes and bounded output.
- EQ center gain, Mid/Side isolation, six filter types, and low-frequency/high-Q stability over 8–384 kHz.
- Flat-magnitude neutral multiband recombination, upward/downward behavior, gated silent floor, compressor ratio/knee and external detector release.
- Alternating ping-pong echoes, decaying reverb, stable frozen tails and reset to exact silence; chorus stereo width, phaser and flanger response.
- Fractional circular-buffer wrap next to an integer boundary and integer reads without accessing unfilled neighbors. ASan exposed this boundary issue during development; integer-index wrapping and regression tests fixed it.
- FFT known-frequency mapping, opposite-polarity stereo power, source-change clearing and bounded analyzer overflow.
- Enabled full-rack parameter extremes, live reordering/parallel changes, zero render allocation, mailbox backpressure/recycling, panic tail clearing and a muted external sidechain source.
- Identical output for one-block offline rendering and 97-frame callback partitions with scheduled notes and spatial effects.

The effects implementation uses the same render path in host tests and Android, but host correctness does not certify mobile callback deadlines, subjective audio quality, interface usability or production readiness. The 128-voice capacity is not a measured Xperia polyphony limit. The limiter is sample-peak, not certified true-peak; extreme settings can distort or alias.

Allocation interception covers new/new[], aligned and nothrow variants in the owned C++ renderer. The test tracker was extended for nothrow delay-buffer preparation; no allocation/deallocation sanitizer checks were suppressed. It does not audit all internal Android/Oboe operations. LeakSanitizer is disabled (`ASAN_OPTIONS=detect_leaks=0`) because of the traced host; ThreadSanitizer was not run.

The existing core/concurrent queue and synth/patch-publication tests also pass. Kotlin tests cover immutable rack editing, serialization, malformed settings, order/lane/macro persistence, bounded values and the EQ response display's center gain/channel selection. They are logic tests, not UI tests.

Lint warnings concern the intentionally pinned SDK/dependency versions and backup configuration. They were not suppressed. SDK 34 remains the requested baseline; store submission and private production signing are separate tasks.

## First Xperia session

Install this APK over the previous version. Check the green/black appearance, existing synth patches and new rack presets. Follow the short audition/sidechain steps in `EFFECTS.md`. Test saved edits after restarting, background playback, portrait/landscape, USB note input and headset disconnect. Compare buffer modes, callback load and xruns, then repeat after several minutes to expose thermal changes.

Phase 4 Session/Arrangement work, file recording/export, sampler/granular engines, full MIDI expression and Astra commands are outside this validation.
