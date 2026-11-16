# Astra Studio — Phase 13 / Sound packs and imported wavetables

Android 14+ native music workstation, built for **Sony Xperia 1 IV** first. Package `com.weskestis.astrastudio`, version **0.13.0-soundpacks**, green and black throughout. This is a development DAW, with implemented editing/playback features and explicit limits below; it is not yet a production-certified replacement for a desktop workstation.

## Full workstation development goal

The goal is a professional Android DAW with Ableton Live-level workflow depth and sound-design capability. Phase 13 adds the reusable sound library within that goal. The [full roadmap and capability ledger](docs/ROADMAP.md) records the remaining scope, current limitations and completion criteria. Phase 10 adds bounded project audio streaming, instrument phrase bounces and finished-track sharing. Phase 12 adds full Arrangement source freeze/unfreeze, three-generation autosave recovery and on-device performance reports. Physical device measurements and the broader capability ledger remain open.

## All Rift sound packs in Astra

Open **Samples → Sound packs → Install pack parts** and select the same seven `.riftpack` files used in Rift. The separate library holds all **1,194 samples and six original 256-cycle wavetables**, with search, categories, favorites and disk-backed previews. Load selected sounds into the sampler, pads, clips or either wavetable oscillator, then edit them using Astra's real audio engine. Project edits preserve installed originals and portable saves include selected audio. [Install, editing, memory and compatibility details](docs/SOUND_PACKS.md).

## Freeze, recover and measure

Select a track, then **Freeze track** in its device rack. Render the full Arrangement source at **48 / 96 / 192 kHz, stereo float32**, with an instrument tail. Original clips stay editable; mixer, effects, sidechains and sends stay live. **Unfreeze track** restores the source; source/tempo edits automatically invalidate stale freezes. Session still uses the original instruments. Undo reloads the correct audio when cache slots are reused.

**File → Recovery** restores one of three previous autosaves, with Undo. Startup tries the newest usable backup if the current save or audio is unreadable. **File → Performance** shows real device CPU, memory, voice, xrun and disk-read readings and copies a report. Back and rotation preserve these panels. [Controls, fidelity, storage costs and exact limits](docs/RELIABILITY.md).

## 50 tracks and layers

Phase 11 expands the complete project to **50 tracks**, **8 scenes / 400 clip slots** and **2,048 arrangement regions**. Every track supports the existing synth, sampler/drums, audio clips, mixer, inserts, sends, automation, Astra commands, resampling and master export. Tap **Track … / 50** beside Session/Arrangement to jump to any track. The grid and timeline compose only visible tracks and retain the selected track through rotation and Back navigation.

Existing projects migrate automatically: old clip/scene positions, instruments, routing and arrangement regions stay together. New saves use JSON version 8 and native wire version 7; Phase 12 and earlier projects remain compatible. Keep an original portable copy if you also need to open it in an older APK, which cannot read the new format.

The **128 shared instrument voices**, 64 audio assets and existing streamed-audio budgets are unchanged. Fifty stored tracks are not a guarantee of fifty simultaneous heavy synth/effects chains on a phone. Live playback uses the device rate; 192 kHz is an offline master option. Hardware MIDI channels still address tracks 1–16; clips, Keys and Astra address all 50. [Capacity, migration and measurements](docs/CAPACITY.md).

## 192 kHz / 24-bit master

Open **File → Export arrangement WAV** or **File → Share finished track**, then select **192 kHz / 24-bit master**. The preset sets both the rate and encoding. Native synths, effects, automation, tempo maps and master processing render offline at the chosen rate; source files are converted as needed and remain unchanged. PCM24 receives TPDF dither once at the final conversion.

Rates: **44.1, 48, 88.2, 96, 176.4 and 192 kHz**, in stereo PCM24 or float32. Existing defaults remain 48 kHz. The 192 kHz / 24-bit file uses about **69.1 MB per minute**; render duration and memory also increase. Playback and microphone capture retain their device-rate behavior. A larger output rate does not recreate detail missing from an imported recording. [Master format, implementation and limits](MASTERING.md).

## Larger sources and sharing

Large project samples now stream through **2 MiB audio-page caches per asset**, with an active-bank audio-page/resident ceiling of **128 MiB**. Imports support **2 GiB per file**, 64 assets and **8 GiB of stereo-float-equivalent source frames**. Instrument, effect, UI and temporary export memory are additional. Master shows memory, streamed files, read misses and errors. Exports read independently and never capture a live cache underrun as successful audio.

**File → Share finished track → Render finished track** keeps a completed WAV ready for **Share WAV** or **Save WAV as…**. The Android share sheet supports compatible installed apps. For SoundCloud, save the WAV and choose it from SoundCloud's Upload screen if it is absent from the share sheet. The app does not upload automatically. Exports remain available until you delete them.

**Resample → Bounce instrument to clip** adds a float audio version on the same track while preserving the original. Insert effects, mixer, sends and master stay live. This is a one-cycle phrase bounce; use **Freeze track** for full Arrangement source freeze/unfreeze.

[Streaming behavior, memory accounting, sharing and limits](docs/STREAMING.md).

## Tempo-following audio

Select an audio clip and open **Warp**. Enter its **source BPM**, choose the **downbeat source frame**, enable Warp and apply. The clip follows Session BPM and Arrangement tempo steps/ramps while preserving pitch. Set an independent pitch shift of −24 to +24 semitones. **Fit source to clip length** calculates BPM from the selected audio and existing beat length; it is not automatic beat detection.

Settings are nondestructive, saved in portable/autosaved projects and undoable. Playback, resampling and WAV export share the same processing. Warped Arrangement splits retain musical source position, and the waveform follows the beat mapping. Original audio is never rewritten. [Warp controls, implementation and limitations](docs/WARP.md).

## Record audio and find bass tones

Open **Record audio** (or **File → Record & Sample Lab**). Allow microphone access, choose mono or stereo, then **Record microphone → Stop & save**. The input meter shows clipping and the actual input format/route. Back, rotation and backgrounding retain the foreground recording; the notification's Stop action saves it. Monitoring is off to avoid feedback.

Choose a take or a project sample. Zoom, pan/pinch, drag a selection or enter exact source frames. **Analyze bass** shows an estimated fundamental, MIDI note/cents, periodicity, RMS/peak and a 20 Hz–2 kHz spectrum. **Use as sampler** maps the selection to the keyboard with an editable root note; **Add audio clip** puts it in the selected track's next free slot. Both preserve the original and support Undo. Optional 2 ms edge fades prevent hard-cut clicks; fades default off for unchanged samples.

Recordings and **Open audio from storage** use disk-backed reading, preview and waveform windows. Each take/import supports up to 1 GiB of audio, while extracted samples enter the **64-asset streamed project library**. Project imports have the separate Phase 10 limits above. [Recording controls, fidelity and storage details](docs/RECORDING.md).

## Sampler, drum rack and resampling

Open **Sampler / drums** in the bottom device chain. **Drum rack → Load starter kit** supplies 16 original samples, including an 808. Load your own sounds through **Samples → Import samples** (multiple files) or **Sound packs → Install pack parts**. The keyboard sampler and 16 pads support pitch, trim, reverse, envelopes, looping, velocity and choke groups, with the same effects and WAV-export path as the synths.

**Resample** turns the selected clip or full arrangement into a 32-bit float WAV in **Samples → Resampled**. Originals remain editable. Search, favorite and organize samples into collections; portable projects carry the browser and all assignments. The browser holds 64 assets; larger files stream under the Phase 10 limits above. ZIP packs are supported; RAR is not included. Independent offline time stretching is described below.

**Android Back returns through the current screen**: detail → parent panel → workspace, then Arrangement → Session. It leaves the Activity only from Session with no open panel. The system keyboard closes first when visible. **Close ×** closes a whole panel directly.

Full controls and behavior: [Phase 6 sampler guide](docs/SAMPLER.md).

## Fidelity, tempo and stretch

**Tempo map** adds up to 128 saved BPM points, with sudden changes or ramps, to Arrangement playback and export. Session retains its base tempo. **Stretch** creates a new audio clip with independent duration and pitch, preserving the original; it is an offline render, not automatic live warping. The 64-tap sampler/timeline sample-rate converter and float WAV bounces reduce conversion artifacts. Float32 is the default export format; PCM24 remains available.

[Phase 7 controls, measured checks and limitations](docs/QUALITY.md). These improvements do not certify mastering transparency, true-peak safety or Xperia performance.

## Astra commands and Gemini listening

Tap **Astra** beside File. Type a request or use **Speak**; choose **Offline preview** for the supported examples, or **Ask Gemini** for flexible language and listening feedback. Every edit is previewed and applied as one Undo step. Occupied clips require explicit replacement, and a preview is rejected if the project changed meanwhile.

- Offline examples generate riddim/psytrance/trance/house phrases, load patches, map LFOs and route sidechains without an account.
- **Ask Gemini** uses your own Google AI Studio key, saved encrypted on the phone. The default `gemini-3.5-flash-lite` supports audio and has a free tier subject to Google's quotas and availability. Use a project without Cloud Billing to stay on the free tier; Astra cannot inspect your billing tier or prevent charges on a paid project. No shared key is included in this public repository or APK.
- **Attach audio** adds a local file up to 8 MiB; it is sent to Google only when you choose Ask Gemini. MP3 is practical for longer songs. This listening attachment limit is separate from the DAW's streamed project storage limits. There is no automatic live-engine listening.
- **Copy for Gemini** lets you paste a prepared prompt into the Gemini app/website, attach audio there yourself, then paste its response into **Command JSON → Validate JSON**. This manual route needs no API key in Astra.
- Google says free-tier submissions may be used to improve its products. Requests include your text, selected clip notes, track/patch names and mix summary, plus any attached audio. Audio files, API keys and network chat are not stored in `.astra` projects.

Full workflow, command reference and limitations: [Astra Phase 5](docs/ASTRA.md). Live Gemini requests and Sony voice/audio hardware have not been tested here because no user Google key or physical phone is available.

## Install and make a track

Install `AstraStudio_0.12.0_FreezeRecovery.apk` over Phase 11 or an earlier Astra build. The development signing certificate is unchanged. GitHub fallback: **Actions → Android Phase 12 - Freeze and Recovery → a successful run → AstraStudio-Phase12-FreezeRecovery-APK**.

1. **File → Load starter song** opens Green circuit: original, editable MIDI drums, bass and lead, two scenes and a 16-bar arrangement. Save your current project first.
2. Tap **Start audio**, then **Play**. Session Play launches Scene 1; Arrangement Play begins at the white cursor. Clip/scene launch buttons follow the selected bar, quarter-note or eighth-note quantization.
3. Tap a clip body to select it, then **Edit clip**. Draw notes, drag to move, drag their right edge to resize, edit velocity, quantize, use legato or draw automation.
4. **Keys** opens a multitouch piano. **Record MIDI** records the on-screen keyboard or USB MIDI into the selected clip after its launch boundary. Overdub adds; disabling it replaces the take. One take is one Undo step.
5. In **Arrangement**, tap the ruler to set the cursor, then **Place clip**. Drag regions, duplicate, split or edit exact lengths with **Region**. Audio splits can use individual source samples.
6. The bottom **device chain** opens the track mixer, synth and effects. Mixer includes arm, mute, solo, pan and post-fader reverb/echo sends.
7. **File → Import audio** accepts PCM WAV directly and other mono/stereo formats supported by Android's codecs. **Save portable project** creates a `.astra` ZIP with all used audio, notes, automation, mix, synth and effect settings. Work also autosaves privately on the phone.
8. **Export arrangement WAV** renders 32-bit float or dithered 24-bit stereo at 44.1–192 kHz through the same DSP, with optional effect tails. Export ignores playback looping.

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
- 50 mixer tracks, 128 total voice slots, sample-frame note scheduling, note IDs and overflow panic handling.
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

- `app/src/main/java/com/weskestis/astrastudio/astra/`: command compiler, phrase algorithms, Gemini protocol/client, encrypted key storage and green/black command panel.
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

Phase 5 adds the validated command interface and optional Gemini listening. Phase 6 adds sample instruments, packs and resampling. Phase 7 adds tempo maps, independent offline pitch/time processing, improved sample-rate conversion and float export. Phase 8 adds microphone recording and the disk-backed Sample Lab. Phase 9 adds source-tempo/downbeat warp with independent pitch. Phase 10 adds bounded streamed playback, source bounces and finished-track sharing. Full-track freeze, imported wavetables, full MIDI expression and the other workstation gaps remain tracked in [ROADMAP.md](docs/ROADMAP.md).
