# Astra Studio — Phase 1

Android 14+ native audio foundation for a new music workstation, with Sony Xperia 1 IV as the first physical-device target. Working name: **Astra Studio**. Package: `com.weskestis.astrastudio`. Version: `0.1.0-phase1`.

This is a playable engineering build. The complete Phase 1 source is included. The full DAW, premium synths, mastering effects, Session/Arrangement editors and AI assistant are not finished in this phase.

## Try the APK on your Xperia

1. Install the supplied `AstraStudio_Phase1_0.1.0.apk` on Android 14 or newer.
2. Open Astra Studio and tap **Start audio**. The notification permission is optional; Android still requires a foreground playback notification.
3. Start at a comfortable system volume. Hold the F-minor note pads, or tap **Timing demo** for a two-bar sequence. The current instrument is a basic sine calibration voice.
4. Select a track and try Volume, Pan, Mute and Solo. MIDI channel 1 maps to Track 1, through channel 16 / Track 16.
5. **Play transport** runs the beat clock. Enable **Click** to hear the metronome. **Stop transport** releases notes and cancels older scheduled notes; **Panic** clears sound immediately.
6. Try **Balanced** first. Compare **Fast** (2 bursts), **Stable** (4) and **Large** (8) if xruns increase. Actual buffer duration is shown, not claimed round-trip latency.
7. **Stop audio** shuts down the stream and foreground playback service. The notification also has a Stop audio action.

The UI scrolls on a phone, supports either orientation, and does not keep audio state inside Composables. Wired headphones or a wired interface are the appropriate first latency test. Real Xperia latency, thermals and controller behavior require testing on the actual phone.

## Implemented

- C++20 Oboe/AAudio float-stereo output with low-latency/exclusive request and shared fallback.
- Allocation-free RenderCore callback, fixed SPSC commands, fixed event heap and bounded dispatch work.
- 16 mixer tracks, 128 total voices, note IDs, sample-frame scheduling and overflow panic handling.
- Smoothed volume/pan/mute/solo and master controls; output safety clamp and clipping counter.
- Tempo and transport clock, metronome, three tempo presets and tap tempo.
- Safe JNI handles, serialized producers, reusable direct-buffer telemetry and explicit lifecycle ownership.
- Android mediaPlayback foreground service, audio focus, route recovery and notification Stop action.
- USB MIDI hot plug and basic MIDI 1 / MIDI 2 note input; parsing tests included.
- Compose test controls, stereo meter, xruns, callback load and queue/late-event counters.
- Gradle wrapper, CMake build, native regression tests, Kotlin parser tests and GitHub Actions workflow.

No engine or bridge file contains an unfinished DSP placeholder. Future phase modules are omitted until actually implemented.

## Pinned build configuration

| Component | Version / setting |
| --- | --- |
| Android minimum / compile / target | API 34 / API 34 / API 34 |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 8.11.1 |
| JDK | 17 |
| Kotlin + Compose compiler plugin | 2.1.20 |
| Compose BOM | 2024.06.00 |
| Oboe | 1.9.3, Maven Prefab |
| NDK | 28.0.13004108 |
| CMake | 3.22.1 |
| C++ | C++20 |
| CPU architectures | arm64-v8a, x86_64 |

The SDK 34 setting follows the requested baseline. App-store submission requirements and a newer target SDK are a separate release task, not a claim made by this test build. Native libraries use 16 KB ELF segment alignment; actual 16 KB device operation still needs device/emulator validation.

## Build locally

Open this folder as an Android Studio project, with JDK 17 selected. Install the listed SDK/NDK/CMake versions. Or run:

```sh
sdkmanager 'platforms;android-34' 'build-tools;35.0.0' 'ndk;28.0.13004108' 'cmake;3.22.1'
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Run the native tests on Linux/macOS with CMake and a C++20 compiler:

```sh
bash scripts/test-native.sh
```

The build includes a deliberately public **development-only** signing key (`androiddebugkey`, password `android`) so these test APKs can update one another when built locally or in CI. Never use that key for a production release. Production signing is intentionally unconfigured.

## GitHub builds from a phone

The included `.github/workflows/android.yml` builds on a push to `main`, a pull request or manual **Actions → Android Phase 1 → Run workflow**. It runs native tests, Kotlin tests and Android lint before uploading an `AstraStudio-Phase1-APK` artifact. A GitHub artifact is a ZIP containing the APK. The workflow is provided as source; an actual hosted run is only claimed after it has run in a created repository.

## Complete project folder structure

See `docs/PROJECT_TREE.txt` for the generated exact file inventory. Principal paths:

```text
AstraStudio/
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradlew
  gradlew.bat
  gradle/wrapper/
    gradle-wrapper.jar
    gradle-wrapper.properties
  .github/workflows/android.yml
  signing/astra-development.keystore
  scripts/test-native.sh
  docs/
    ARCHITECTURE.md
    PROJECT_TREE.txt
    VALIDATION.md
  app/
    build.gradle.kts
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      cpp/
        CMakeLists.txt
        AudioEngine.h
        AudioEngine.cpp
        native-lib.cpp
        core/
          SpscRing.h
          RenderCore.h
          RenderCore.cpp
        tests/
          AllocationTracker.cpp
          CoreTests.cpp
      java/com/weskestis/astrastudio/
        MainActivity.kt
        audio/
          AudioBridge.kt
          AudioService.kt
        midi/
          MidiDecoder.kt
          MidiInputManager.kt
      res/
        drawable/ic_astra.xml
        values/strings.xml
        values/themes.xml
    src/test/java/com/weskestis/astrastudio/midi/
      MidiDecoderTest.kt
```

`AudioEngine.h` / `.cpp` implement stream management and the Oboe callback; `core/RenderCore.h` / `.cpp` contain the complete mixer and voice loop used by that callback. `native-lib.cpp` and `AudioBridge.kt` are the complete JNI boundary. See `docs/ARCHITECTURE.md` for the thread contract, frame/transport semantics, limits and phase plan. See `docs/VALIDATION.md` for the checks actually completed for this delivery.

## Phase boundary

Phase 1 ends here. Phase 2 is the wavetable and four-operator FM synthesis work. Later phases add the effects/routing rack, full DAW UI/workflows and Astra's validated JSON command interface. The sampler, drum rack and time-stretch engine also remain explicit later work. No cloud AI provider, API key, subscription, server or network request is present in this build.
