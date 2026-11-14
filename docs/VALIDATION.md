# Phase 1 validation

Validation performed on the delivery source on November 14, 2026. This report distinguishes compiled/tested code from physical-device behavior that has not been observed.

| Check | Result |
| --- | --- |
| C++20 host CMake build, warnings treated as errors | Passed |
| Native regression suite | Passed: 12,349 assertions |
| Concurrent SPSC queue stress | Passed: 500,000 ordered producer/consumer messages with wraparound |
| Allocation tracking inside rendering and event dispatch | Zero observed C++ heap allocations, including aligned allocations |
| AddressSanitizer and UndefinedBehaviorSanitizer | Passed native suite |
| ARM64 and x86_64 Android native compilation | Passed |
| Kotlin / Compose compilation | Passed |
| MIDI parser JUnit tests | 6 passed; 0 failures; 0 errors |
| Android `assembleDebug` | Passed |
| Android `lintDebug` | Passed, 0 errors; non-blocking warnings described below |
| APK signature | APK Signature Scheme v2 verification passed |
| APK native library ZIP alignment | 16 KB check passed |
| ELF LOAD segments | All six packaged native libraries have at least 16 KB alignment |
| Xperia 1 IV physical installation / audio latency / USB hardware | Not performed here; requires the phone |
| Android emulator / instrumentation / UI screenshot testing | Not performed |
| Hosted GitHub Actions run | Separate from local validation; see this repository's Actions tab for current run results |

Native cases cover silence initialization, arbitrary buffer sizes, exact event onset, note-off completion, deterministic output across callback partitions, pan/mute/solo, cancellation of older scheduled notes, preservation of newly queued patterns, queue and event-heap overflow, panic, bounded polyphony, output clamp, tempo integration, mid-stream tempo changes and late-event accounting.

MIDI cases cover fragmented MIDI 1 messages, running status with interleaved real-time bytes, SysEx/system status reset, UMP fragmentation, MIDI 2 velocity and group identity, zero-velocity MIDI 2 Note On semantics, skipping unsupported UMP sizes and scoped all-notes-off.

LeakSanitizer could not run in the traced host environment; ASan was run with `detect_leaks=0`. This is not a leak-free certification. ThreadSanitizer was not run. The concurrency stress test and acquire/release code inspection do not replace formal race detection or hardware stress tests. Allocation interception covers C++ new/new[] in the owned renderer, not every internal system or Oboe operation.

Lint's non-blocking warnings concern the deliberately pinned SDK/dependency baseline and Android backup configuration. No warning was suppressed to make the build pass. SDK 34 is the requested baseline, not a claim of current store-submission eligibility.

## First phone test

1. Confirm Android 14 or newer, install the APK, start audio and play the note pads.
2. Check both orientations and scroll to all track, pad and buffer controls.
3. Play the timing demo; enable Click; adjust tempo; stop transport; confirm silence after release.
4. Compare Fast/Balanced/Stable buffers with wired output. Record xruns, buffer size and DSP load.
5. Background the app and return; rotate during playback; stop from the notification.
6. Test interruption by another audio app and unplug wired headphones. Playback should stop on focus/noisy events.
7. Connect a USB MIDI keyboard; test note-on/off, channel-to-track mapping and disconnect while a note is held.

The audible test patch is intentionally a sine voice. Judge responsiveness and stability in this phase, not the later EDM sound design. A low xrun count in a short test does not establish 128-voice performance under thermal load, and software buffer duration is not measured round-trip latency.
