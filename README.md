# Astra Studio — Phase 2

Native Android 14+ music workstation in development, with **Sony Xperia 1 IV** as the first physical-device target. Package `com.weskestis.astrastudio`; version **0.2.0-phase2**.

Phase 2 adds playable **wavetable and four-operator FM synthesizers** to the Phase 1 Oboe engine. This is a synth lab and engineering build. Session/Arrangement editing, production effects, recording/export and Astra AI are later phases.

## Install and play on your Xperia

Install `AstraStudio_Phase2_0.2.0.apk` over Phase 1. It uses the same development signing certificate. Alternatively, open this repository's **Actions → Android Phase 2**, choose a successful run, and download the **AstraStudio-Phase2-APK** artifact (a ZIP containing the APK).

1. Tap **Start audio**. Use wired headphones for the first latency test.
2. Track 1 starts with **Liquid Riddim**, Track 2 with **Trance Prism**, and Track 3 with **FM Alloy**. Other tracks start with Init Sine. Saved edits take precedence on subsequent launches.
3. Hold the F-minor pads, tap **Play phrase**, or use **Hold F1 to edit** in the synth panel. The held note lets you adjust controls without keeping a finger on a pad. Tap Release F1 to stop it.
4. Choose a factory preset, then explore **Oscillators**, **Filters**, **Envelopes**, **LFOs**, and **Modulation**. Edits affect held notes and save automatically on this phone.
5. In LFOs, choose LFO 1–4, enable tempo sync or use Hz, and draw across the graph. In Modulation, pick one of 16 slots and assign a source, destination and signed depth. Shape presets provide an alternative to drawing.
6. Compare **Balanced** (3 hardware bursts) with Fast/Stable if xruns increase. The displayed buffer time is not measured end-to-end latency.
7. **Panic** stops voices and scheduled notes; accepted synth edits are retained. **Stop audio** closes the stream and background service.

The test phrase is two bars scheduled using the BPM at launch. Tempo changes update LFOs and the transport clock, but do not retime already-scheduled phrase notes. It is not yet an arranger or clip sequencer.

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

128 is a fixed capacity, not a measured Xperia performance guarantee. High FM/warp settings can still alias despite mitigation. The master uses the existing -0.1 dBFS sample safety clamp; a mastering limiter is Phase 3. Actual phone latency, thermal behavior, touch ergonomics and USB hardware still need physical testing.

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

- `app/src/main/cpp/dsp/`: complete wavetable/FM, envelope, filter, patch and voice DSP.
- `app/src/main/cpp/core/`: scheduling, allocation-free rendering, mixer and patch mailbox.
- `app/src/main/cpp/AudioEngine.*`, `native-lib.cpp`: Oboe lifecycle and JNI.
- `app/src/main/java/com/weskestis/astrastudio/synth/`: patch model, touch editor and generated parameter IDs.
- `tools/synth-schema.json`: single parameter-wire specification. Run `python3 tools/generate_synth_schema.py` after editing it; CI checks generated C++ and Kotlin match.
- `app/src/main/cpp/tests/`: engine/synth regressions, allocation interception and offline preset renderer.
- `app/src/test/`: MIDI and patch-state tests.
- `docs/PROJECT_TREE.txt`: exact source inventory.
- `docs/SYNTHESIS.md`: signal path, modulation units, memory/thread contract and DSP limits.
- `docs/VALIDATION.md`: performed checks and remaining hardware validation.

Phase 2 ends here. **Phase 3 is the effects rack, OTT/multiband dynamics and sidechain DSP.** Full Session/Arrangement UI is Phase 4; Astra's validated command interface is Phase 5. Sampler, drum rack, importable wavetables and time stretching remain explicit future work.
