# Phase 2 synthesis design

## Signal path

A voice selects either the dual-wavetable path or the four-operator FM path. Noise is mixed into that path, followed by two filters in series or equal-weight parallel. The sub oscillator bypasses the filters. This runs at twice the output sample rate. A 31-tap half-band FIR returns it to the output rate; a DC blocker, main envelope, velocity, patch gain and amplitude modulation precede the existing track/master mixer.

The FIR contributes 7.5 output frames of group delay, about 0.156 ms at 48 kHz. This is only DSP delay; it is not measured input-to-output latency. Amp envelopes operate at output sample rate. Filter coefficient ramps operate over 32 oversampled samples. Most modulation targets are recalculated every 16 output samples and interpolated between updates. Base continuous parameters settle with an approximately 5 ms smoother. Mode changes and stolen voices use a 64-sample fade from the previous output value.

## Wavetables and warp

Each of four original banks has four frames, 1,024 samples plus a guard sample per frame, and eight mip levels with harmonic limits 128, 64, 32, 16, 8, 4, 2 and 1. The richest frame determines a common normalization across its mip levels. Tables are built on the control thread before starting callbacks, shared by all voices in that stream, and immutable during rendering.

Lookup interpolates phase, frame position and mip level. Conservative mip selection considers frequency and warp/FM amount. Basic frames are sine, triangle, saw and square. Digital, growl and metallic families use original additive spectra. These are harmonic waveforms; additional phase modulation supplies more complex spectra.

Sync multiplies phase inside each fundamental cycle and resets at the fundamental boundary. Bend +/− applies a monotonic quadratic phase bend. A modulates B's phase, independently of A's audible mix level. The separate FM synth uses the conventional digital phase-modulation implementation, with modulation index in radians. FM/warp and drive generate new harmonics: mipmaps and 2× oversampling reduce aliasing but do not guarantee alias-free output at extreme settings. A true supersaw unison engine and external wavetable import are not implemented in this phase.

## FM algorithms

Operators are numbered 1–4. Each has its own ratio, level and ADSR in addition to the three general envelopes. Operator 4 can feed back its previous output. Oscillator increments and modulation depth are bounded.

| Algorithm | Modulation / carriers |
| --- | --- |
| Chain | 4 → 3 → 2 → 1; operator 1 is the carrier |
| Pairs | 2 → 1 and 4 → 3; carriers averaged |
| Fan | Operators 2, 3 and 4 modulate operator 1 |
| Parallel | Four averaged carriers; operator 4 retains feedback |

## Filters

Two trapezoidal state-variable filters provide low-, high- and normalized band-pass output. Resonance maps to bounded Q; integrator state is flushed near zero. Cutoff is clamped relative to internal sample rate.

Comb mode uses a preallocated 8,192-sample interpolated delay per filter, damped feedback below unity and soft saturation. Cutoff specifies delay tuning. The delay-length limit raises the minimum available comb pitch at very high output sample rates; at 44.1/48 kHz output the UI's 20 Hz lower bound fits, while at 96 kHz output the minimum is about 23.4 Hz. No specific device sample rate is forced or assumed by the stream.

Vowel mode interpolates three resonant bands through A/E/I/O/U frequency sets. Cutoff scales the formant frequencies around a 900 Hz reference. Drive blends toward a saturating input transfer continuously from zero. In parallel routing both complete filter outputs are averaged, including a bypassed branch. The sub remains clean of these filters and drive, but is still affected by final envelope, velocity, amplitude modulation, pitch modulation and output gain.

## Modulation

Four periodic LFOs have 32 bipolar samples with linear interpolation and wrapping at the boundary. Each supports Hz or beats-per-cycle, and note retrigger or track-shared free-run. Free-run continues on the audio clock with transport stopped; starting transport resets tempo-synced free-run phase. Retriggered LFOs start at phase zero per note. BPM changes affect the next control update, within 16 output samples.

Envelope 1 is the main amp envelope. Envelopes 2–3 are modulation envelopes. All three are routable. Envelope levels are unipolar; LFOs are bipolar. Velocity is 0–1. Key tracking is `(MIDI note − 60) / 60`. Sixteen route slots sum before destination clamps.

| Destination | Unit at depth 1 for a source value of 1 |
| --- | --- |
| A/B position, warp, levels | +1 normalized |
| A → B phase FM | +4 phase cycles |
| Pitch | +24 semitones |
| Filter 1/2 cutoff | ×64 (+6 octaves) |
| Amplitude | ×2 relative to unmodulated gain; zero floor |
| Filter resonance / drive | +1 normalized, safety-clamped |
| FM operator index | +8 radians, safety-clamped |

This phase supplies touch source/destination assignment. Drag-and-drop rack interaction belongs to the Phase 4 DAW UI. Every displayed Phase 2 synthesis control is wired to actual DSP.

## Complete patch publication

`tools/synth-schema.json` defines 85 parameter IDs and ranges. Generated Kotlin/C++ declarations prevent numbering drift. The wire contains 261 floats: 85 parameters, 128 LFO points, then 16 triples of source/destination/depth. Kotlin and native validation reject wrong lengths, NaN/Infinity, fractional enums, invalid ranges and invalid routes before indexing or casting.

The control producer claims one of 64 fixed patch slots, writes the complete patch and publishes its index through the existing SPSC command queue. The audio consumer copies it into the track and releases the slot. Slot reuse has acquire/release ordering. JNI serializes all producers. There is no buffer pointer exposed to UI, no shared mutable patch read by rendering, no allocation on patch dispatch and no audio-thread mutex. A full mailbox returns backpressure; the service retains its previous UI patch. Panic stops notes while retaining accepted patch changes. Heap-overflow recovery also retains accepted patch snapshots.

The service persists immutable patches with a versioned, validated text format after a 500 ms edit debounce. It restores them before playback, and replays complete patches after stream recreation. No disk I/O happens in the audio callback. This is patch persistence, not full DAW project save/load.

## Fixed bounds and performance

The existing 16 tracks and 128 shared voice slots remain. Each voice has fixed filter delays, operator/envelope state and FIR history; storage is allocated when constructing the stream's callback object. Note start clears preallocated filter state, which is bounded work but can be expensive for simultaneous large note bursts. Bank generation, Java arrays, persistence and patch encoding remain off the audio thread.

Host tests establish bounds, functional behavior and zero intercepted C++ allocations in the owned renderer. They do not establish Xperia polyphony, xrun-free operation, thermal stability, UI frame rate or end-to-end latency. The render tool reports host processing ratios only. No new mastering processing is included; the existing sample clamp remains a final overload guard.

## Primary references

- Andrew Simper, Cytomic: trapezoidal state-variable filter derivation, https://cytomic.com/files/dsp/SvfLinearTrapOptimised2.pdf
- CMU Introduction to Computer Music, wavetable synthesis and interpolation, https://www.cs.cmu.edu/~15322/book/ch03/04.html
- Android Developers, Oboe low-latency callback guidance, https://developer.android.com/games/sdk/oboe/low-latency-audio

The source implements original tables, patches and application logic. References explain standard DSP techniques, not licensed commercial synthesizer code or presets.
