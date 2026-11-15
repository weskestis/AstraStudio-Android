# Phase 5 validation

Development build **0.5.0-phase5 / versionCode 5**, November 15, 2026. The same deliberately public development certificate supports updating Phases 1–4 without uninstalling.

## Observed local checks

| Check | Result |
| --- | --- |
| Kotlin/JUnit | 74 tests passed, including 35 Astra tests |
| Android ARM64/x86_64 APK | Assembled |
| Android instrumentation APK | Assembled |
| Android lint | 0 errors, 17 warnings (pinned versions, API target, backup settings, KTX conveniences) |
| Native source | Unchanged in Phase 5; existing core/synth/effects/project regression and sanitizer suites retained in CI |
| Gemini REST | Request/response, schema, audio encoding and bounds tested with fixtures; no live Google request |
| Physical Sony Xperia / USB / voice recognition | Not tested here |

## Command and protocol coverage

Tests cover the three requested command examples, F minor pitch sets, syncopated riddim and psytrance kick gaps, deterministic phrase seeds, signature-aware durations/capacity, real comb/LFO and zero-based native sidechain mappings, complete-batch validation, one-step history Undo/Redo and portable document round trips. Validation checks include unknown operations/fields, duplicate keys, invalid types/numbers, nonfinite values, trailing text, malformed escapes, nesting/length/count limits, occupied MIDI slots, audio replacement refusal, region collisions, note bounds, modulation capacity, cross-band compressor thresholds and accidental float rounding of integer parameters. Unsupported extra natural-language operations and overflowed numeric text cannot silently execute partial edits.

Gemini fixtures verify exact audio bytes after Base64 encoding, selected project context, absence of embedded credentials, a schema-constrained request, no unrestricted tools, one finished candidate, thought-part exclusion, blocked/truncated outputs, bounded reads and cancellation. These tests verify the integration code; they do not certify current remote service acceptance, musical judgment or account quotas.

## Android validation

The existing playback/edit/rotation and portable project/native export tests are retained. Added tests exercise Astra preview/apply/Undo, a proposal invalidated by a later Redo, missing-key guidance, Android Keystore encrypted round trip/removal and a JSON-generated phrase rendered through the actual JNI WAV exporter with signal exceeding the dither floor. Screenshot capture now uses UiAutomation to include modal windows.

Phase 5 CI execution and screenshot review will be recorded here after the run completes.

## Practical limits

A Google AI Studio key was not available, so no live text/audio inference or account-tier verification was performed. Hardware speech input, network reliability on the Xperia, thermal behavior, audio latency and battery use remain device checks. The app is a development release, not a production-certified desktop DAW replacement. No claim of unlimited free AI, measured 60/120 fps, transparent mastering or perfect audio transcription is made. Gemini API settings explain Google's free-tier data use and the consequences of using a billed project.

The previous Phase 4 evidence remains in git history at 184c845866b01fe48ceebd770c299a3f9d6f4af7 and [its passing Android run](https://github.com/weskestis/AstraStudio-Android/actions/runs/43671365069).
