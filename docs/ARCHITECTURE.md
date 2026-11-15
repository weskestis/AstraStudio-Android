# Engine and Android architecture — Phase 2

The application combines the Phase 1 native audio foundation with Phase 2 wavetable/FM synthesis and a phone-oriented synth editor. It is not yet a completed DAW. See `SYNTHESIS.md` for the signal path, modulation and patch-publication contract. Effects, clip editing and the AI interface remain later phases.

## Ownership and threads

| Layer | Owner / thread | Responsibility |
| --- | --- | --- |
| Compose UI | Activity main thread | Touch controls and immutable state display |
| AudioService | Android foreground service | Survives Activity rotation and backgrounding during user-started playback |
| Control Handler | One HandlerThread | Stream lifecycle, audio focus, settings, MIDI connection management, telemetry polling |
| AudioBridge / JNI | Serialized non-audio callers | Validate handles, queue fixed-size commands, copy telemetry to a reusable direct buffer |
| SPSC queue | Serialized producer, one audio consumer | Acquire/release publication without an audio-thread mutex |
| RenderCore | Oboe callback only | Event scheduling, voice rendering, per-track stereo mix, transport clock, metronome |
| Oboe error callback | Oboe error thread | Atomically report the failure; stream close/reopen belongs to the control thread |

JNI uses opaque integer handles in a mutex-protected registry. Java never owns a raw C++ pointer. The mutex is exclusively on the non-audio side and serializes every producer, including callers outside the service. The Kotlin bridge also synchronizes its lifecycle and reusable direct telemetry buffer. The audio callback never enters Java, acquires these locks, logs, performs file/network access, or grows a container.

Each stream owns a shared callback object containing its own RenderCore. The callback contains no pointer back to the service or engine. Closing/reopening creates a new session. Oboe retains callback ownership for any in-flight callback; an old stream cannot overwrite a new stream's error state. The error callback returns `true`, explicitly taking responsibility for close; the service calls stop/close on its control thread. Shutdown always closes the stream even if recovery never runs.

## Realtime contract

All capacities are allocated before starting a stream:

- 16 mixer tracks; 128 total voices across the whole engine.
- 4,096 command slots in a fixed SPSC ring.
- 8,192 slots in a fixed binary event heap, ordered by frame then submission order.
- Maximum 256 commands accepted from the queue and 512 scheduled events dispatched per callback.
- One metronome sine table, shared immutable wavetable banks, 64 patch-transfer slots and fixed per-voice filter/FIR/operator state. All are constructed off the callback.
- A fixed 16-float stack accumulator per sample. No callback-sized scratch allocation.

Supported ABIs are `arm64-v8a` (Xperia 1 IV and other modern phones) and `x86_64` (emulators). Compile-time assertions enforce lock-free atomics on these targets. This phase deliberately does not claim 32-bit support.

When a queue write fails, the caller gets `false`, the dropped counter increments, and an out-of-band atomic panic clears voices and pending events. A lost Note Off cannot silently leave a note hanging. An event-heap overflow also triggers panic. Oversized event bursts are bounded and may be late; the late-event counter exposes that condition. These capacities are engineering limits, not a promise that every workload will meet the phone's deadline.

The voice renderer now supports dual wavetable and four-operator FM synthesis, shared per-track patches, envelopes, modulation, filters and short voice-steal fades. Quiet/releasing voices are preferred for stealing, with stable oldest-voice tie-breaking. See `SYNTHESIS.md` for details.

Track volume, equal-power pan, mute/solo and master gain are smoothed over about 5 ms. The mixer ends in a -0.1 dBFS **sample clamp**, with a clipping counter. This is intentionally labelled a safety clamp: it is not the Phase 3 lookahead/true-peak limiter, and overloading it produces distortion.

## Timing and transport

Engine frame zero begins when a fresh stream starts. Frames advance while the stream is active, even when transport is stopped; live keyboard notes still work with transport stopped. A scheduled command specifies an absolute frame in that stream's sample clock. `-1` means the next callback in which the command is dequeued. A past event is applied immediately when dispatched and counted as late.

Transport Start resets the musical beat to zero. Tempo is integrated per sample, so changing BPM does not resample or speed up any audio. No sample playback/time-stretching exists in this phase. Transport Stop releases active voices and cancels note events submitted before Stop, including events still in the command queue. A new pattern submitted after Stop is preserved. Panic clears pending note events and voices and stops transport; accepted complete patch changes are retained so the engine stays consistent with the UI.

Future timeline/clip scheduling must map beats to frames using the tempo map. This phase exposes frame scheduling and a continuous BPM-controlled beat clock; it does not mislabel a fixed-frame demo as a tempo-following arranger. Tempo changes during the timing demo affect the metronome; already-scheduled demo notes keep their absolute frame times.

The JNI telemetry direct buffer has 20 native-endian doubles. It is owned by Kotlin, reused for polling and copied with `memcpy` to avoid alignment assumptions. Its fields are: running, sample rate, burst frames, buffer frames, DSP load, xruns, frame, active voices, left/right peak, drops, late events, clipped frames, error, exclusive, BPM, playing, beat, track capacity, voice capacity. Each field is an atomic observation, not a transactionally consistent snapshot. Integer values remain exact in a double for practical session durations.

## Android integration

AAudio low-latency/exclusive output is requested first. Shared mode is a fallback; the UI displays the actual negotiated mode. The stream uses its device's negotiated sample rate and float stereo output. Requested buffer sizes are multiples of the hardware burst, bounded by capacity. A route may reject tuning, and the UI reports that. `bufferMs` is software output-buffer duration, not measured end-to-end latency. DSP load is callback processing time divided by that callback's audio duration; it is not whole-device CPU utilization.

The foreground service declares both required foreground permissions and the mediaPlayback service type, starts from a visible user action, displays a Stop action, and is not sticky after process death. Audio-focus loss and the becoming-noisy broadcast stop playback. A disconnected stream can reopen on the control thread with playback stopped. Repeated recovery failures stop the service. No microphone, Internet, broad storage or recording permission is requested by this build.

USB MIDI hot plug uses both Android transport registrations. When Android exposes a shared USB identity, UMP is preferred over the legacy sibling to avoid doubled input. MIDI 1 byte-stream parsing supports running status, real-time bytes between messages and fragmented messages. UMP supports MIDI 1 and MIDI 2 Note On/Off, 16-bit MIDI 2 velocity, group identity and channel-specific CC 120/123. Groups share the same 16 mixer tracks but retain distinct note IDs. Input-device disconnect releases that device's notes.

This is **basic MIDI 2 note input**, not full MIDI 2 compliance: MIDI-CI negotiation, per-note expression, sustain, pitch bend, controller mapping, MIDI output and sample-clock alignment of external timestamps remain later work. Touch/MIDI notes enter the next available block. Physical USB hardware behavior still requires device testing.

## Xperia 1 IV baseline

ARM64 is the primary physical-device target. The test screen uses density-independent 48 dp or larger interactive controls, 64 dp note pads, scrollable track/pad rows, system insets, portrait/landscape rotation and resizable Activity behavior. Balanced defaults to three hardware bursts; two, four and eight are available. Start with wired audio and compare xruns under load. Battery, thermal throttling, Bluetooth latency, 120 Hz frame pacing and real round-trip latency require measurements on the phone; none is inferred from desktop tests.

## Next phases

| Phase | Implementation scope |
| --- | --- |
| 2 (implemented) | Dual wavetable oscillator, anti-aliasing/mips, sub/noise, warp modes, modulation, filters, four-operator FM, instrument presets |
| 3 | Ordered/routed effects graph, dynamics, multiband processing, sidechain routing, EQ/FFT, distortion, spatial FX, limiter, offline consistency |
| 4 | Session and arrangement workflows, piano roll, clip editing, automation, recording/import/export, project persistence and robust Undo |
| 5 | Validated JSON command schema, reversible operations, natural-language/voice interface and the chosen AI integration |

The sampler, drum rack, granular engine and independent time-stretch algorithms will need explicit implementation within the later phases. Ableton's proprietary devices and “Complex Pro” algorithm are not available to copy; any equivalent mode must have an original or appropriately licensed implementation and an honest name. No absent module has a fake control, empty DSP method or placeholder implementation in this source.

## Primary references

- Android low-latency Oboe guidance: https://developer.android.com/games/sdk/oboe/low-latency-audio
- Oboe 1.9.3 source and release: https://github.com/google/oboe/releases/tag/1.9.3
- Oboe callback lifetime guidance: https://github.com/google/oboe/wiki/TechNote_HowToAvoidCrashes
- Android media playback foreground services: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android MIDI and network byte order for UMP: https://developer.android.com/reference/android/media/midi/package-summary
- AGP 8.9 compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
- Android 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- Sony Xperia 1 IV specifications: https://www.sony.com/electronics/support/mobile-phones-tablets-mobile-phones/xperia-1-iv-512gb/specifications
