# Phase 4 validation

Local checks on November 15, 2026 UTC for **0.4.0-phase4 / versionCode 4**. The physical Sony Xperia 1 IV remains untested here.

| Check | Observed result |
| --- | --- |
| Native C++20, warnings as errors | Passed |
| Core / synthesis / effects regressions | 12,350 / 574,403 / 1,293,757 assertions passed |
| New project/sequencer/export suite | 33,094 assertions passed |
| Tested realtime C++ allocations and deallocations | Zero |
| Kotlin JUnit | 39 passed, including 22 project/edit/format/history cases |
| ARM64 + x86_64 JNI and Compose compilation | Passed |
| APK and instrumentation APK assembly | Passed |
| APK signature and ZIP / ELF alignment | v2 signature verified; unchanged development certificate; all six native libraries aligned to 16 KB |
| Android lint | 0 errors, 16 non-blocking warnings |
| Native sanitizers | All four suites passed under AddressSanitizer + UndefinedBehaviorSanitizer |
| Android 14 instrumentation | Automated phone UI/file tests and screenshots configured in GitHub Actions; inspect the exact run result |
| Physical Xperia, USB MIDI, listening, touch/audio latency, thermals | Not tested here |

## New coverage

Native checks exercise malformed/truncated/nonfinite project wires; clip/region bounds; overlapping regions; quantized clip/scene starts and stops; independent note IDs; loop retriggering; seek note chasing; automation interpolation/restoration; three-slot immutable plan backpressure and concurrent recycling; stereo audio split continuity at every source sample; rate interpolation; PCM parsing and decoded memory limits; WAV export headers/duration/cancellation; actual live-versus-export sample comparison after known latency compensation; audible shared echo send and panic tail clearing. The test allocation tracker intercepts new/new[], aligned/nothrow variants and deletes. It covers owned DSP/sequencer code, not all Android/Oboe internals.

Kotlin checks exercise note edits, quantization, legato, resizing, point replacement, capacity errors, independent clip duplication, region collisions, MIDI/audio split offsets, unsafe filenames, missing assets, numeric validation, JSON/mix round trips, corrupt versions/IDs/patches, native-wire layout, signature-aware quantization, source-reference deletion, undo grouping/coalescing/redo invalidation, the editable starter and bounded cancellable copying.

Instrumentation tests exercise starter loading, note drawing, Undo/Redo, Arrangement selection, region panel and portrait/landscape rotation; screenshots are saved as CI artifacts. A second test runs portable project save/open, JNI WAV rendering, audio reimport, archive audio-byte comparison and atomic autosave on Android. Passing host tests alone does not imply these device tests passed; consult the workflow run and artifacts.

## Limits of the evidence

This remains a development build. No claim of measured 60/120 fps, guaranteed 128-voice Xperia polyphony, calibrated MIDI-record timing, transparent mastering, perfect audio resampling, or production readiness. The limiter controls sample peaks rather than certified true peaks. Imported audio is unwarped; linear sample-rate conversion can alias when downsampling. Recording is MIDI-only, and tempo maps are not implemented.

LeakSanitizer is disabled on the traced host (`ASAN_OPTIONS=detect_leaks=0`); AddressSanitizer and UndefinedBehaviorSanitizer remain active. ThreadSanitizer was not run. Warnings concern pinned Android dependencies, backup configuration and Kotlin convenience APIs; they are not suppressed.

## First Xperia session

Install over the earlier APK with the same development certificate. Load Green circuit, start audio, compare Session/Arrangement and rotate the phone. Edit a note, Undo/Redo, record a keyboard take, import your own audio, save/reopen a portable project and export WAV. Check background playback and headset-disconnect behavior. Compare Fast/Balanced/Stable buffer modes while watching DSP load and xruns, then repeat after several minutes. USB MIDI hardware and physical audio paths need testing on the phone.
