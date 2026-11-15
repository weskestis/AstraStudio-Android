# Engine and Android architecture — Phase 7

The application combines the Phase 1 native audio foundation with Phase 2 wavetable/FM synthesis and a phone-oriented synth editor. It is not yet a completed DAW. See `SYNTHESIS.md` for the signal path, modulation and patch-publication contract. Phase 3 adds the effects engine described in `EFFECTS.md`; Phase 4 adds the sequencer, editing and persistence described in `WORKSPACE.md`. Phase 5 adds the reversible assistant and Gemini integration described in `ASTRA.md`.

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
- 32 effects mailbox slots, preallocated delay storage for all 16 racks, fixed limiter/compensation buffers and a 32,768-frame analyzer queue. FFT runs on the control thread.
- A fixed 16-float stack accumulator per sample. No callback-sized scratch allocation.

Supported ABIs are `arm64-v8a` (Xperia 1 IV and other modern phones) and `x86_64` (emulators). Compile-time assertions enforce lock-free atomics on these targets. This phase deliberately does not claim 32-bit support.

When a queue write fails, the caller gets `false`, the dropped counter increments, and an out-of-band atomic panic clears voices and pending events. A lost Note Off cannot silently leave a note hanging. An event-heap overflow also triggers panic. Oversized event bursts are bounded and may be late; the late-event counter exposes that condition. These capacities are engineering limits, not a promise that every workload will meet the phone's deadline.

The voice renderer now supports dual wavetable and four-operator FM synthesis, shared per-track patches, envelopes, modulation, filters and short voice-steal fades. Quiet/releasing voices are preferred for stealing, with stable oldest-voice tie-breaking. See `SYNTHESIS.md` for details.

Track volume, equal-power pan, mute/solo and master gain are smoothed over about 5 ms. Every track passes through a compensated effects rack. The mixer ends in a −0.1 dBFS sample-peak limiter with 5 ms lookahead; its over-ceiling counter counts pre-limiter frames. This is not a certified true-peak limiter. See `EFFECTS.md` for routing, delay compensation and meter-buffer contracts.

## Timing and transport

Engine frame zero begins when a fresh stream starts. Frames advance while the stream is active, even when transport is stopped; live keyboard notes still work with transport stopped. A scheduled command specifies an absolute frame in that stream's sample clock. `-1` means the next callback in which the command is dequeued. A past event is applied immediately when dispatched and counted as late.

Transport Start resets the musical beat to zero. Tempo is integrated per sample, so changing BPM does not resample or speed up any audio. Imported audio plays at original speed through sample-rate conversion. Offline independent time stretching creates a new float sample; it does not run in the callback. Transport Stop releases active voices and cancels note events submitted before Stop, including events still in the command queue. A new pattern submitted after Stop is preserved. Panic clears pending note events and voices and stops transport; accepted complete patch changes are retained so the engine stays consistent with the UI.

Phase 4 advances clip events directly from the integrated native beat clock on every audio sample. Live tempo changes retime future MIDI events and launch boundaries. The synth-only audition phrase still uses fixed absolute frames, independently of the arranger. Phase 7 adds an immutable saved tempo map with analytic integration and a cached exponential beat-clock recurrence for linear BPM ramps. Session ignores the map.

The JNI telemetry direct buffer has 20 native-endian doubles. It is owned by Kotlin, reused for polling and copied with `memcpy` to avoid alignment assumptions. Its fields are: running, sample rate, burst frames, buffer frames, DSP load, xruns, frame, active voices, left/right peak, drops, late events, clipped frames, error, exclusive, BPM, playing, beat, track capacity, voice capacity. Each field is an atomic observation, not a transactionally consistent snapshot. Integer values remain exact in a double for practical session durations.

## Phase 4 project publication

Three immutable `Plan` slots use release/acquire busy flags. Parsing, sorting, sample decoding, vector allocation and destruction happen on control/worker threads. A callback activates a raw pointer, releases notes for changed tracks, then retires the previous slot. Its last owning references are destroyed only when the producer reuses that slot. The active plan remains pinned. Backpressure retains the desired UI state and retries from the service's 50 ms poll.

Each plan owns references to immutable decoded stereo assets. Session and Arrangement events run in sample time, with per-track sorted event cursors, 256 reserved held-note IDs, interpolation of four automation lanes every 32 samples and linear source-rate audio interpolation. Plan limits bound work; dense simultaneous notes may still exceed a real device's DSP budget. Sequenced note events do not use the external command-heap event budget.

The control handler owns its current StudioState; a main-thread handler publishes snapshots to Compose collectors. Status publication never runs Compose view work on the audio control thread, including under the instrumentation test dispatcher.

The dedicated project I/O worker performs archive operations, Android MediaCodec decoding, atomic autosave and offline export. Long I/O never holds the Kotlin audio monitor or native registry mutex. Offline export snapshots assets and uses a separate RenderCore with the same synth/rack/send/limiter path. It trims fixed algorithmic latency and writes float32 WAV or deterministic-dithered 24-bit WAV outside render calls. A dataSync foreground service covers user-started file jobs.

Undo/Redo stores 64 immutable project snapshots; continuous controls coalesce and MIDI takes form one group. Audio files are immutable and retained privately for safe Undo; opening/new-project clears history as appropriate. Phase 6 portable archives retain all browser audio, including unused samples and inactive pad assignments. Audio import is capped by a 512 MiB decoded asset budget, not a 60-second duration limit.

## Android integration

AAudio low-latency/exclusive output is requested first. Shared mode is a fallback; the UI displays the actual negotiated mode. The stream uses its device's negotiated sample rate and float stereo output. Requested buffer sizes are multiples of the hardware burst, bounded by capacity. A route may reject tuning, and the UI reports that. `bufferMs` is software output-buffer duration, not measured end-to-end latency. DSP load is callback processing time divided by that callback's audio duration; it is not whole-device CPU utilization.

The foreground service declares both required foreground permissions and the mediaPlayback service type, starts from a visible user action, displays a Stop action, and is not sticky after process death. Audio-focus loss and the becoming-noisy broadcast stop playback. A disconnected stream can reopen on the control thread with playback stopped. Repeated recovery failures stop the service. Phase 5 adds Internet permission for explicit Gemini requests. No microphone or broad storage permission is requested; the Speak button delegates to an installed Android recognition activity. User-started assistant network jobs use the existing dataSync foreground-service type.

USB MIDI hot plug uses both Android transport registrations. When Android exposes a shared USB identity, UMP is preferred over the legacy sibling to avoid doubled input. MIDI 1 byte-stream parsing supports running status, real-time bytes between messages and fragmented messages. UMP supports MIDI 1 and MIDI 2 Note On/Off, 16-bit MIDI 2 velocity, group identity and channel-specific CC 120/123. Groups share the same 16 mixer tracks but retain distinct note IDs. Input-device disconnect releases that device's notes.

This is **basic MIDI 2 note input**, not full MIDI 2 compliance: MIDI-CI negotiation, per-note expression, sustain, pitch bend, controller mapping, MIDI output and sample-clock alignment of external timestamps remain later work. Touch/MIDI notes enter the next available block. Physical USB hardware behavior still requires device testing.

## Xperia 1 IV baseline

ARM64 is the primary physical-device target. The test screen uses density-independent 48 dp or larger interactive controls, 64 dp note pads, scrollable track/pad rows, system insets, portrait/landscape rotation and resizable Activity behavior. Balanced defaults to three hardware bursts; two, four and eight are available. Start with wired audio and compare xruns under load. Battery, thermal throttling, Bluetooth latency, 120 Hz frame pacing and real round-trip latency require measurements on the phone; none is inferred from desktop tests.

## Next phases

| Phase | Implementation scope |
| --- | --- |
| 2 (implemented) | Dual wavetable oscillator, anti-aliasing/mips, sub/noise, warp modes, modulation, filters, four-operator FM, instrument presets |
| 3 (implemented) | Ordered/routed effects graph, dynamics, multiband processing, sidechain routing, EQ/FFT, distortion, spatial FX, limiter, offline consistency |
| 4 (implemented) | Session and arrangement workflows, piano roll, clip editing, automation, recording/import/export, project persistence and robust Undo |
| 5 (implemented) | Strict JSON command compiler, one-step Undo, revision checks, offline phrase grammar, voice-input delegation, Gemini text/audio integration and manual Gemini handoff |

| 6 (implemented) | Native sample voices, 16-pad drum rack, original kit, sample browser/packs, resampling, portable sample state and nested Back navigation |

Phase 6 implements the sampler, drum rack and resampling in `SAMPLER.md`. Phase 7 adds independently licensed offline pitch/time processing; granular synthesis and automatic live warp remain future work. Ableton's proprietary devices and “Complex Pro” algorithm are not available to copy; any equivalent mode must have an original or appropriately licensed implementation and an honest name. No absent module has a fake control, empty DSP method or placeholder implementation in this source.

## Primary references

- Android low-latency Oboe guidance: https://developer.android.com/games/sdk/oboe/low-latency-audio
- Oboe 1.9.3 source and release: https://github.com/google/oboe/releases/tag/1.9.3
- Oboe callback lifetime guidance: https://github.com/google/oboe/wiki/TechNote_HowToAvoidCrashes
- Android media playback foreground services: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android MIDI and network byte order for UMP: https://developer.android.com/reference/android/media/midi/package-summary
- AGP 8.9 compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
- Android 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- Sony Xperia 1 IV specifications: https://www.sony.com/electronics/support/mobile-phones-tablets-mobile-phones/xperia-1-iv-512gb/specifications

## Phase 5 command boundary

Astra parses and compiles proposals on a dedicated worker, never on the audio callback. `StrictJson` supplies the same duplicate-key/coercion/depth checks on Android and JVM. `AstraCommands` builds a new immutable `ProjectDocument`; each complete batch is validated before the control handler can adopt it. No operation exposes arbitrary JNI buffers, native pointers, file paths, URLs or code execution. Native patch/effect/project validators remain in the existing JNI path. Applying is one history transaction, with a project revision check; previews cannot silently overwrite intervening edits. StateFlow still publishes through Main.

The Gemini client uses a fixed HTTPS endpoint, no redirects, no URL query credentials, bounded input/output, explicit cancellation, timeouts, a five-second request gap and no automatic retries or model fallback. A user-owned API key is encrypted using Android Keystore AES-GCM and stored through AtomicFile in noBackupFilesDir. No key is embedded, included in saved state, printed in errors or exported with projects. This development build uses individual user keys; a distributed service using an owner-funded key needs a protected backend or Firebase architecture instead.

The assistant worker uses the dataSync foreground service while processing a user request. Cancellation invalidates the job generation so late results cannot become new previews. Audio is held only for the current service session and leaves the device only after Ask Gemini. Audio feedback is model-generated qualitative advice, not an FFT/LUFS/true-peak measurement.
