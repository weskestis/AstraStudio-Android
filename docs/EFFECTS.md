# Phase 3 effects and routing

Version 0.3.0-phase3 adds an original native effects rack to each of the 16 tracks. The editor uses green controls and meters on black/dark-green panels. It remains a development synth/effects lab; timeline editing, recording and file export are separate work.

## Signal path and timing

Each sample first renders all synth voices into 16 dry track sums. Each track then processes its rack, followed by its volume, stereo balance, mute and solo. Tracks sum into the master gain/drive and a stereo-linked limiter. The metronome enters the master with the same rack compensation.

Eight units, one instance each, may be reordered. Serial mode follows that order. Parallel mode sends the input to lanes A and B, processes units assigned to each lane in order, aligns the lanes, and blends them linearly with A/B balance. There are no user-defined feedback connections. Rack wet and output gain follow this routing.

Every rack adds **15 output frames**, including bypass. The saturator uses two 31-tap half-band FIRs at 2× rate; other paths receive matching delay. Dry/wet and the parallel lane without the saturator also receive 15 frames of compensation. The limiter adds `ceil(sampleRate * 0.005)` frames. At 48 kHz the combined rack/limiter delay is **255 frames / 5.3125 ms**, in addition to synthesis and device buffering. The UI's processing-delay field reports this rack/limiter portion; it is not a measured round-trip latency.

External sidechain sources are the same-frame dry synth sums **before all effects, fader, pan, mute and solo**. A muted source can therefore act as a ghost trigger. Self mode detects the compressor's own input at its location in the chain. An external detector is delayed 15 frames if its compressor follows the saturator in that lane. This pre-effects tap makes routing independent of track processing order and prevents cycles. It is not a post-fader send bus.

## DSP units

| Unit | Implementation and controls |
| --- | --- |
| EQ Eight | Eight RBJ biquads: peak, low/high shelf, low/high pass and notch; frequency, ±18 dB, Q and enable per band. Stereo or orthonormal Mid/Side; each band targets both, mid or side. Double-precision coefficients/state, 32-frame coefficient ramps. |
| Multiband / OTT | Two Linkwitz–Riley fourth-order crossovers plus low-band phase compensation. Stereo-linked peak envelopes; separate hard upward/downward thresholds, ratios, amounts, band makeup, depth, attack/release and crossover frequencies. Upward gain is capped and fades out below −72 dBFS, reaching zero at −84 dBFS. |
| Saturator | Soft tanh, hard clip, sine fold and asymmetric tube-style tanh; pre/post gain and wet mix. 2× FIR up/downsampling and DC removal. |
| Sidechain compressor | External or self detector, detector high-pass, stereo-linked peak envelope, threshold, ratio, soft knee, attack/release, makeup and wet mix. Live reduction meter/history. |
| Ping-pong delay | Mono sum injected into left delay, alternating cross-feedback into right and back. Free milliseconds or tempo divisions, low-pass feedback tone, wet mix. Time changes slew rather than abruptly jumping the read head. |
| Reverb | Eight delay lines with normalized Hadamard feedback, per-line decay gains, damping and four input all-pass diffusers. Size, decay, wash diffusion and wet mix. Freeze smoothly removes new input, sets feedback to unity and removes damping. |
| Chorus / Ensemble | Three fractional-delay voices per channel with offset sine modulation, rate, depth, feedback and wet mix. |
| Phaser / Flanger | Six modulated all-pass stages or short fractional delay; stereo LFO phase offset, rate, depth, signed feedback and wet mix. |
| Master limiter | Always-on, stereo-linked, 5 ms lookahead, monotonic peak deque, immediate gain reduction and adjustable release. −0.1 dBFS sample ceiling; drive 0–24 dB. The final rounding clamp is only a bound guard. |

OTT here describes the upward/downward multiband approach, not a claim to reproduce any commercial device's transfer function. Neutral crossover recombination has flat magnitude but an all-pass phase response; blending unprocessed audio with it can alter the spectrum. The saturator mitigates aliasing but is not alias-free at extreme drive. The limiter controls sample peaks; it is **not a certified true-peak/ISP limiter**, and strong reduction can distort transients. These limits matter when assessing mastering quality.

## Controls and persistence

`tools/fx-schema.json` defines 113 bounded parameters. Its generator writes identical Kotlin/C++ IDs and metadata. The wire adds eight order entries, eight lane entries, and four `(target, depth, value)` macro triples: **141 floats** total. All entries must be finite, enums integral, the order a unique permutation, and each upward threshold below its downward threshold. Validation occurs in Kotlin and again at the JNI/C++ boundary.

Four macros each control one of 16 continuous destinations. Signed depth scales the destination's permitted range; contributions add to the base value and the final value clamps. Resetting a macro to zero restores its contribution without overwriting the base patch. The EQ response display includes macro contributions.

A 32-slot effects mailbox copies whole configurations on the serialized control thread. The existing SPSC command queue publishes slot ownership to the callback. If all slots are busy, the UI retains the previous accepted state and reports backpressure. A five-millisecond smoothing step runs at a 32-frame control interval; unit enables smooth per sample. Topology/mode changes fade from the last output over 64 frames and reset affected filter states. These fades reduce discontinuities, but a live reorder is not a fully crossfaded duplicate graph.

Each track's configuration saves in versioned `fx-v1` preferences, alongside master drive/release. Synthesis retains its separate Phase 2 preferences, so updating the APK preserves existing patches. Panic clears notes, delay/FIR/filter history and frozen tails while retaining accepted parameter edits. Audio restart reconstructs the engine and reapplies saved controls.

## Realtime and metering

Delay memory is allocated only by `prepare()` before streaming. At 48 kHz, all 16 racks reserve about 27 MiB of delay storage; total engine/app memory is higher. Reset marks delay buffers empty in constant time; stale/uninitialized samples are not read. Circular delay indices wrap as integers so a fractional read close to the boundary cannot round to one past the allocation.

The callback never allocates, locks, logs, accesses files or calls Java. The 15-frame compensation rings, limiter storage and mailboxes have fixed capacity. DSP meter values publish through lock-free atomics. Peak safety guards bound pathological feedback, but ordinary user settings still need device/perceptual assessment.

The selected post-fader track or post-limiter master feeds a 32,768-frame SPSC analyzer queue. Samples carry a source tag, so switching tracks clears stale analysis. The **2048-point stereo Hann FFT runs on the control thread**, outside the audio callback, at the 50 ms telemetry poll. Sixty-four log-frequency bars display stereo power, including opposite-polarity stereo signals. The EQ overlay shows steady-state mid/stereo and side response. Low-frequency resolution is limited by the FFT size; the graph is not a measurement-grade analyzer.

The effects meter JNI buffer holds 133 native-endian floats: 16 sidechain reductions; 48 signed multiband gains; master reduction; rack/limiter latency; 64 spectrum bands; sample rate; analyzed source; analyzer drops. Meters are observations rather than a transactional snapshot.

## Try it on Xperia

1. Start audio, choose Track 1 and hold F1 in the synth panel. Collapse synth controls to reach the effects quickly.
2. Load **Riddim Pressure**. Compare the rack wet control and the four saturator shapes. Use the master reduction display to judge how hard the limiter is working.
3. Use **Trance Space** on a lead; change the delay division, reverb decay and chorus depth. Freeze a ringing reverb tail, then use Panic to clear it.
4. For sidechain: mute Track 1 and play its two-bar phrase. Select Track 3, hold F1, load **Pumping House** and leave its source at Track 1. Watch the reduction trace. The phrase is a synth trigger; a kick sampler is not included yet.
5. Reorder units or assign them to parallel lanes, compare A/B balance, and map a macro.
6. Close/reopen the app and verify effects persist. Repeat with background playback, rotation and headphone route changes. Watch xruns and thermal behavior over a longer session.

## Primary DSP references

- RBJ filter equations: https://www.w3.org/TR/audio-eq-cookbook/
- Giannoulis, Massberg and Reiss, *Digital Dynamic Range Compressor Design—A Tutorial and Analysis*: https://eecs.qmul.ac.uk/~josh/documents/2012/GiannoulisMassbergReiss-dynamicrangecompression-JAES2012.pdf
- Julius O. Smith, feedback delay networks: https://ccrma.stanford.edu/~jos/pasp/FDN_Reverberation.html

These inform the algorithms; the implementation and presets in this repository are original. Commercial device names do not imply affiliation or copied source/assets.
