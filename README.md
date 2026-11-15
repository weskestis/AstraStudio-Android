# Astra Studio — Phase 4

Android 14+ native music workstation, built for **Sony Xperia 1 IV** first. Package `com.weskestis.astrastudio`, version **0.4.0-phase4**, green and black throughout. This is a development DAW, with implemented editing/playback features and explicit limits below; it is not yet a production-certified replacement for a desktop workstation.

## Install and make a track

Install `AstraStudio_Phase4_0.4.0.apk` over Phase 1, 2 or 3. The development signing certificate is unchanged. GitHub fallback: **Actions → Android Phase 4 → a successful run → AstraStudio-Phase4-APK**.

1. **File → Load starter song** opens Green circuit: original, editable MIDI drums, bass and lead, two scenes and a 16-bar arrangement. Save your current project first.
2. Tap **Start audio**, then **Play**. Session Play launches Scene 1; Arrangement Play begins at the white cursor. Clip/scene launch buttons follow the selected bar, quarter-note or eighth-note quantization.
3. Tap a clip body to select it, then **Edit clip**. Draw notes, drag to move, drag their right edge to resize, edit velocity, quantize, use legato or draw automation.
4. **Keys** opens a multitouch piano. **Record MIDI** records the on-screen keyboard or USB MIDI into the selected clip after its launch boundary. Overdub adds; disabling it replaces the take. One take is one Undo step.
5. In **Arrangement**, tap the ruler to set the cursor, then **Place clip**. Drag regions, duplicate, split or edit exact lengths with **Region**. Audio splits can use individual source samples.
6. The bottom **device chain** opens the track mixer, synth and effects. Mixer includes arm, mute, solo, pan and post-fader reverb/echo sends.
7. **File → Import audio** accepts PCM WAV directly and other mono/stereo formats supported by Android's codecs. **Save portable project** creates a `.astra` ZIP with all used audio, notes, automation, mix, synth and effect settings. Work also autosaves privately on the phone.
8. **Export arrangement WAV** renders 24-bit stereo at 44.1 or 48 kHz through the same DSP, with optional effect tails. Export ignores playback looping.

Detailed controls, limits and file format: [Phase 4 workflow](docs/WORKSPACE.md). Build/verification evidence: [Validation](docs/VALIDATION.md).

## Included effects

Eight reorderable units per track: EQ Eight with Mid/Side, three-band upward/downward dynamics, four-mode 2× saturator, sidechain compressor, ping-pong delay, freeze/wash reverb, chorus/ensemble and phaser/flanger. Serial or parallel A/B lanes, rack wet/output and four macros. The master has a −0.1 dBFS sample-peak limiter with 5 ms lookahead. EQ/spectrum and gain-reduction displays are live; settings persist on the phone. [Full DSP and routing details](docs/EFFECTS.md).

## Included synthesis

| Module | Implemented |
| --- | --- |
| Wavetable | Two oscillators; independent bank, position, level, tuning, detune and warp; four original banks with four morphing frames each |
| Banks | Basic shapes, digital spectra, monster growls, metallic formants; eight harmonic-limited mip levels per frame |
| Warp | Sync, Bend +, Bend −; A-to-B phase FM |
| Extra sources | Clean sine sub one octave down and seeded noise |
| FM | Four operators with ratio/level/ADSR, four algorithms, global modulation index, operator 4 feedback |
| Filters | Two independently configured bypass/low-pass/high-pass/band-pass/comb/vowel filters, drive, serial/parallel routing; clean sub bypass |
| Modulation | Four 32-point drawable LFOs, tempo/Hz modes, note retrigger/free-run, three ADSRs, velocity and key sources, 16 routes and 16 destinations |
| Quality | 2× oscillator/filter processing, 31-tap half-band decimation, DC removal, parameter smoothing and short voice-steal/mode-change fades |
| State | Atomic complete-patch transfer to the render thread, validation at Kotlin/JNI/C++ boundaries, versioned local patch persistence |

Factory patches: **Init Sine, Liquid Riddim, Metal Maw, Trance Prism, Sub Pressure, FM Alloy, FM Donk, FM Glass Pluck**. These are original synthesized sounds; no third-party sample packs or copied commercial presets are bundled.

## Audio foundation

- C++20 with Oboe/AAudio float-stereo output; low-latency/exclusive request and shared fallback.
- Fixed command queue, event heap, patch mailbox and voice/filter storage. No owned-renderer heap allocations or mutexes in the callback.
- 16 mixer tracks, 128 total voice slots, sample-frame note scheduling, note IDs and overflow panic handling.
- Smoothed volume/pan/mute/solo/master, transport, tap tempo, metronome, output meters and callback/xrun diagnostics.
- Android foreground media playback service, audio focus, headphone-disconnect handling and route recovery.
- USB MIDI hot plug; basic MIDI 1/2 notes and CC 120/123. MIDI channel 1–16 maps to track 1–16. Full expression and MIDI-CI are later work.

128 is a fixed capacity, not a measured Xperia performance guarantee. High FM/warp settings can still alias despite mitigation. The master limiter controls sample peaks, not certified true peaks. Aggressive dynamics can still distort; its ceiling is not a guarantee of transparent mastering. Actual phone latency, thermal behavior, touch ergonomics and USB hardware still need physical testing.

## Build

| Component | Pinned version |
| --- | --- |
| Minimum / compile / target SDK | 34 / 34 / 34 |
| Android Gradle Plugin / Gradle | 8.9.2 / 8.11.1 |
| JDK | 17 |
| Kotlin / Compose compiler | 2.1.20 |
| Compose BOM | 2024.06.00 |
| Oboe | 1.9.3, Prefab |
| NDK / CMake | 28.0.13004108 / 3.22.1 |
| ABIs | arm64-v8a and x86_64 |

```sh
sdkmanager 'platforms;android-34' 'build-tools;35.0.0' 'ndk;28.0.13004108' 'cmake;3.22.1'
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
bash scripts/test-native.sh
build/host/astra_render_presets build/preset-audition.wav
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. The host renderer generates a 32-second WAV: eight presets, four seconds each, in the factory order above. It uses the actual mixer/synth code and does not normalize each preset.

The debug key in `signing/astra-development.keystore` is deliberately public (`androiddebugkey` / `android`) so local/CI test APKs can update one another. Do not use it for a production release. Production signing is unconfigured. SDK 34 follows the requested baseline; store submission requirements are a separate release task.

## Source map

- `app/src/main/cpp/project/`: validated immutable clip plans, sample playback, native sequencer and offline WAV renderer.
- `app/src/main/java/com/weskestis/astrastudio/workspace/`: Session, Arrangement, piano roll, automation, audio trim, keyboard and device chain.
- `app/src/main/java/com/weskestis/astrastudio/project/`: model, Undo/Redo, original starter song, JSON persistence and portable audio archives.
- `app/src/androidTest/`: Android interface gestures/rotation, screenshots, archive and JNI export checks.

- `app/src/main/cpp/fx/`: complete effects, rack routing, limiter and off-callback FFT.
- `app/src/main/java/com/weskestis/astrastudio/fx/`: green/black effects editor, immutable rack state, EQ response overlay and schema.
- `tools/fx-schema.json`: effects wire definition; regenerate with `python3 tools/generate_fx_schema.py`.
- `app/src/main/cpp/dsp/`: complete wavetable/FM, envelope, filter, patch and voice DSP.
- `app/src/main/cpp/core/`: scheduling, allocation-free rendering, mixer and patch mailbox.
- `app/src/main/cpp/AudioEngine.*`, `native-lib.cpp`: Oboe lifecycle and JNI.
- `app/src/main/java/com/weskestis/astrastudio/synth/`: patch model, touch editor and generated parameter IDs.
- `tools/synth-schema.json`: single parameter-wire specification. Run `python3 tools/generate_synth_schema.py` after editing it; CI checks generated C++ and Kotlin match.
- `app/src/main/cpp/tests/`: engine/synth/effects regressions, allocation interception and offline preset renderer.
- `app/src/test/`: MIDI, patch-state, effects persistence and EQ display tests.
- `docs/PROJECT_TREE.txt`: exact source inventory.
- `docs/SYNTHESIS.md`: signal path, modulation units, memory/thread contract and DSP limits.
- `docs/VALIDATION.md`: performed checks and remaining hardware validation.

Phase 4 ends here. **Phase 5 is Astra's validated JSON command interface.** Advanced warping/time stretching, sampler/drum rack, microphone recording, importable wavetables, tempo-map editing, full MIDI expression and production certification remain future work.
