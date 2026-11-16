# Phase 10 validation

Development **0.10.0-phase10 / versionCode 10**, Android 14+.

Verified executable/test source commit: `de808a463b70abf117c1070f2d3574f8dca518b8`.
[Successful build and Android run](https://github.com/weskestis/AstraStudio-Android/actions/runs/43804330140).

- All **143 Kotlin unit tests passed**, with zero failures or ignored tests. New checks cover large frame positions, per-source/project storage bounds, schema-v5 persistence and legacy loading, safe export filenames, exact export-size estimates and tempo-map duration.
- All **eight native suites passed** in Release and with AddressSanitizer/UndefinedBehaviorSanitizer. Streaming fixtures cover exact float/PCM24 reads, page-boundary and distant seeks, sinc conversion parity, sixteen forward/reverse looping sampler voices, concurrent independent live/offline readers, exact streamed-versus-resident exports, source bounces before effects/master, independent time/pitch rendering, cancellation and truncated-source failures.
- A sparse **600 MiB WAV used 2 MiB of audio page storage**, with a full waveform scan. The streaming fixture measured **zero audio-thread allocations, frees, file reads or file seeks**. Retired sample ownership is reclaimed on the serialized control thread after the renderer releases its plan; a lifetime fixture verifies that reclamation never occurs in the callback. These are bounded fixtures, not sustained-phone throughput measurements.
- All **12 Android instrumentation tests passed**, zero failures/errors/skips, in **241.011 seconds**, on API 34 x86_64 at 1080 × 2520. New coverage includes streamed import diagnostics; exact dry instrument-to-audio bounce and Undo/Redo; non-silent PCM24 finished-track rendering; canonical export identity after reopening; FileProvider read grants, exact URI bytes and rejected out-of-folder paths; actual system share-sheet opening and Back; exact saved-file copies; and phone/landscape controls. Earlier recording, Warp, MIDI, project archive, Astra and navigation checks remain green.
- APK assembly and lint passed: **0 lint errors**, 23 advisory warnings in CI (including dependency/update advisories). Green/black portrait and landscape screenshots were reviewed. Share/save/delete controls wrap and remain reachable through vertical scrolling.

## Verified APK

- File: `AstraStudio_Phase10_0.10.0.apk` — **28,447,586 bytes**.
- SHA-256: `45c865c3d9be44ab65dbdebd186c00e271cb9724b184bfcee5e5f1998b116571`.
- Certificate SHA-256: `1bff24ae64d74c99d9540a1ecc5acccc8479367f44772b305144d2c10ce4bd45`.
- Package `com.weskestis.astrastudio`; minimum/target API 34; ARM64 and x86_64. Signature verification, 16 KiB ZIP alignment and native LOAD-segment alignment passed. The unchanged development certificate permits installing over Phase 9.

Finished WAVs stay in the app until explicitly deleted, allowing receiving apps to read after the chooser closes. The app offers Android file sharing and document saving; no actual email or SoundCloud upload was sent during tests. The provider exposes only completed-export storage. [Streaming, bounce, sharing and current limits](STREAMING.md).

Physical Xperia sustained capacity, storage-pressure behavior, route/USB performance, thermal limits and latency remain unmeasured. Cold seeks can miss cached frames and produce reported live silence; export uses separate complete-data readers. The current bounce is one source-clip cycle, not persistent full-track freeze/unfreeze. Track/scene/voice limits remain 16/8/128; 128 MiB is an active audio-page/resident-bank budget, not total application RAM. Version-5 projects open versions 1–4; older APKs cannot open the new format.

---

# Phase 9 validation

Development **0.9.0-phase9 / versionCode 9**, Android 14+.

Verified executable/test source commit: `c8de8f1f75ca4a8c4151a23faaedbcd3bfe63fce`.
[Successful build and Android run](https://github.com/weskestis/AstraStudio-Android/actions/runs/43777003969).

- All **135 Kotlin unit tests passed**, with zero failures or ignored tests. Coverage includes warp settings, source-frame mapping, trimming/splitting, schema-v4 persistence, legacy migration, bounds and Undo.
- All **seven native suites passed** in Release and with AddressSanitizer/UndefinedBehaviorSanitizer. The warp suite completed **729,659 assertions**; measured render-thread allocations and frees were both **zero**. Fixtures cover 55 Hz bass and 220 Hz tones, independent octave shifts, 44.1-to-48 kHz source conversion, antiphase stereo, tempo steps/ramps, seeks/loops/downbeat offsets, sixteen mixed tone/noise processors and extreme supported tempo ratios. Unchanged Session speed/pitch bypass is sample-exact, and adjacent warped splits match the unsplit render sample-for-sample.
- Drum-fixture landmarks were within approximately **2 ms** of their analytic positions through the tested tempo steps and ramps. This is a fixture result, not a universal transient/timing guarantee. Export frame counts matched their musical duration.
- All **11 Android instrumentation tests passed**, with zero failures/errors/skips, in **138.463 seconds**, on API 34 x86_64 at 1080 × 2520. New coverage includes Warp controls, rotation, hierarchical Back, Undo/Redo, portable archive metadata and unchanged original media, actual Oboe playback with an advancing transport and active warped clip, and non-silent float WAV export. Earlier recording, Sample Lab, sampler, editor and navigation checks remain green.
- APK assembly and lint passed: **0 lint errors**, 14 advisory warnings. Green/black phone and landscape screenshots were reviewed; Warp controls remain accessible by vertical scrolling.

## Verified APK

- File: `AstraStudio_Phase9_0.9.0.apk` — **28,348,610 bytes**.
- SHA-256: `ab972c555fc8187c2a28d860c33fc3e1ebf310f5845c8dd68b9ebfe9c5ecd160`.
- Certificate SHA-256: `1bff24ae64d74c99d9540a1ecc5acccc8479367f44772b305144d2c10ce4bd45`.
- Package `com.weskestis.astrastudio`; minimum/target API 34; ARM64 and x86_64. Signature verification and 16 KiB ZIP alignment passed. The unchanged development certificate permits installing over Phase 8.

Warp uses the same processing in playback, export and resampling. Original media is retained; settings are saved in project schema v4. Versions 1–3 load with Warp disabled. Older APKs cannot read schema-v4 saves.

Physical Xperia latency, thermal behavior, sustained capacity, USB routing and listening quality remain unmeasured. This is a development release with one warp algorithm; extreme ratios can soften transients or add artifacts. Detailed warp markers, automatic tempo detection, material-specific modes and disk-streamed project playback remain planned. Existing track/sample budgets are unchanged. See [warp implementation and limits](WARP.md).

---

# Phase 8 validation

Development **0.8.0-phase8 / versionCode 8**, Android 14+.

Verified executable/source commit: `258c83f5c8d5bdf14f9f365f885b479499c203a4`.
[Successful build and Android run](https://github.com/weskestis/AstraStudio-Android/actions/runs/43759779293).

- **124 Kotlin unit tests passed**, including 21 new recording/file/analysis tests: unchanged float extraction, mono/stereo handling, recovery of interrupted writes, truncated/invalid data rejection, optional fades, cancellation cleanup, a 700 MiB file read through a small window, bass pitch at multiple rates, antiphase stereo, harmonic bass, silence/noise rejection and bounded analysis duration.
- All **six native suites passed** in Release and with AddressSanitizer/UndefinedBehaviorSanitizer. The existing live DSP and callback allocation checks remain green; this phase adds no processing or file work to the Oboe callback.
- All **10 Android instrumentation tests passed**, 0 failures/errors/skips, **167.319 seconds**, on API 34 x86_64 at 1080 × 2520. The new tests check frame-entry/zoom, a measured A1/55 Hz fixture, rotation, sampler root assignment, exact extracted and directly saved WAV values, retained originals, Undo/Redo, clip insertion, and actual platform AudioRecord capture continuing through panel Back and rotation before finalizing a readable WAV. Emulator input is not a physical microphone fidelity test.
- Assembly and lint passed. Lint has **0 errors**, with advisory warnings including the requested SDK baseline and conservative free-storage checks.
- Phone and landscape screenshots were reviewed. Sample Lab controls wrap vertically; dialogs avoid the keyboard. Input meters update in their own subtree. Earlier validation caught an Apply control behind the keyboard and a nested horizontal row that left an action outside the visible area after rotation; these are fixed in the verified build.

## Verified APK

- File: `AstraStudio_Phase8_0.8.0.apk` — **28,283,074 bytes**.
- SHA-256: `d425822d0b00d922705d0d4212b350ff020f04ab7c8456153ec2c14f0cac60fd`.
- Certificate SHA-256: `1bff24ae64d74c99d9540a1ecc5acccc8479367f44772b305144d2c10ce4bd45`.
- Package `com.weskestis.astrastudio`; minimum/target API 34; ARM64 and x86_64. Signature verification and 16 KiB ZIP alignment passed. Install over Phase 7 using the same development certificate.

Physical Sony input quality, stereo/USB routing, long-duration endurance, thermal behavior and acoustic bass response remain unmeasured. Float storage cannot improve the hardware input resolution. [Recording controls and limits](RECORDING.md). Full multitrack disk streaming and automatic tempo-following warp are not included; existing track/sample budgets and schema-v3 project format remain unchanged.

---

# Phase 7 validation

Development **0.7.0-phase7 / versionCode 7**, Android 14+.

Verified executable/source commit: `631b53335396dad0010852844bc42c0d4780dc0f`.
[Successful build and Android run](https://github.com/weskestis/AstraStudio-Android/actions/runs/43747332460).

- All **103 Kotlin unit tests** pass, including schema migration, tempo integration/inversion, source-frame splitting, stretch capacity checks, Undo and UI snapshot invalidation.
- All **six native suites** pass in Release and with AddressSanitizer/UndefinedBehaviorSanitizer. They include DSP, scheduling, sample conversion, stretch, export, memory bounds, and callback allocation/deallocation checks. [Measured quality fixtures](QUALITY.md).
- All **eight Android instrumentation tests** pass on API 34 x86_64 with a 1080 × 2520 phone display; total test time 105.376 seconds. They exercise native JNI renders and portable archives, Session/Arrangement editing, live audio controls, nested Back behavior, rotation, sampler/drum rack, resampling, Astra preview/Undo, tempo maps, pitch/time rendering and the float export selector. The workflow retains the screenshots and test reports.
- Debug APK assembly and lint pass for ARM64 and x86_64. Lint has no errors; advisory warnings include the requested SDK 34 baseline, dependency updates and existing style/backup suggestions.
- Green/black phone and landscape screens were visually inspected. Static editors/grid no longer depend on continuously changing diagnostics; progress draws separately and delayed Main-thread state publications coalesce.

## Verified APK

- File: `AstraStudio_Phase7_0.7.0.apk` — **28,020,834 bytes**.
- SHA-256: `c853f8432c743b57ce479a62398f43b2f298498d19aceff5d8bb38892962070b`.
- Signing certificate SHA-256: `1bff24ae64d74c99d9540a1ecc5acccc8479367f44772b305144d2c10ce4bd45`.
- The development certificate is unchanged, allowing an in-place update over prior Astra development APKs. MIT notices for the newly bundled processing libraries are inside the APK.

No physical Xperia, USB hardware, thermal/latency measurements or live Gemini calls were performed here. Emulator results establish functional behavior, not a phone performance guarantee. Float output does not undo earlier clipping or certify true peaks. Stretch is offline, creates a new fixed-duration clip, and cannot automatically follow a later tempo map. Track/sample budgets are unchanged. Schema v3 opens v1/v2 projects; older APKs cannot open v3, so save a portable copy before updating if a downgrade is needed.

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
