# Phase 7 — fidelity, tempo and independent pitch/time

Development version **0.7.0-phase7 / versionCode 7**, Android 14+, green and black. This update improves specific conversion and editing paths. It does not claim perceptually perfect processing or equivalence to Ableton.

## Controls

- **Tempo map**: positions are quarter-note beats starting at zero. Save up to 128 points at 30–300 BPM. A point's **Ramp to next point** flag interpolates BPM linearly in musical beats; otherwise the next point is a sudden change. The first point stays at zero. Disable the map to keep its points but use the base BPM. Arrangement playback, seeking, waveform previews, sample-position splitting and export account for the map. Session uses the base BPM.
- **Stretch**: select an audio clip, choose its new length in beats at the current **base BPM**, and pitch shift from −24 to +24 semitones. A worker renders float stereo at the original sample rate. The result appears in the next free scene on that track and **Samples → Stretched**. The original remains intact; one Undo removes the new clip and asset from the project. Resample MIDI first to stretch its sound. No gain normalization is applied.
- **Export arrangement WAV** defaults to **32-bit float**, with **24-bit PCM** for delivery. Both use the same synths, effects, sends and master limiter at 44.1 or 48 kHz, and trim the existing fixed processing latency. Float preserves the engine's float samples without integer quantization or dither. PCM24 adds deterministic TPDF dither once at the final write, with saturating quantization. The bit-depth selection does not increase source resolution or change the existing limiter into a true-peak limiter.
- **Resample** now writes float32, avoiding another integer conversion during repeated editing.

The stretch is a **fixed-duration offline render**, not an automatically warped clip. Imported audio retains its original speed when tempo changes. Use manual stretching/slicing to prepare sections for a tempo map; there are no per-clip warp markers. Short transients, dense mixes and extreme shifts can sound smeared or textured. Supported length is 50–200% of the trimmed original; the library recommends moderate changes around 75–150% for better transparency. Back closes the current detail/panel; the system keyboard closes first when visible.

## Audio implementation

Sampler and timeline sample-rate conversion use a shared precomputed 64-tap Blackman–Harris windowed-sinc table, 128 fractional phases with interpolation, 129 conservative cutoff bands and double-precision accumulation. Unity-rate integer-position playback has a direct sample path. Kernel storage is about 4.3 MiB per RenderCore, created off the callback. Finite filters retain transition bands and have a CPU cost; this is not an alias-free claim across arbitrary pitch shifts.

Offline stretching uses **Signalsmith Stretch 1.3.2** with its default high-quality configuration, double-precision analysis/reconstruction, deterministic seed, stereo phase linking, explicit preroll/tail handling and cancellation. No FFT processing or new allocation is introduced into the live sample callback. Original source-rate data is used; outputs are padded/trimmed to the exact requested frame count. Exact unchanged duration with zero pitch shift bypasses the stretcher bit-for-bit.

The tempo clock integrates linear BPM ramps analytically, caches their recurrence between boundaries, and resets on seeks/plan changes. Musical note timing is sampled at the engine rate. Tempo-synced effects/LFO coefficients refresh at a short control cadence (up to 32 samples); they do not imply zero-latency parameter changes. Map and audio state transfer as one immutable project snapshot.

## Measured regression fixtures

Host Release tests, 2026-11-15. These are narrow synthetic measurements, not full-band specifications or a listening certification:

| Fixture | Result |
| --- | --- |
| 96 → 48 kHz conversion, 30 kHz sine | −124.88 dB residual relative to input sine RMS |
| 44.1 → 48 kHz conversion, 3 kHz sine | 120.74 dB signal/error ratio, excluding boundaries |
| Unity-rate integer playback | Bit-exact source samples |
| Float32 WAV write/read, including ±1.5 | Bit-exact without clipping at ±1 |
| Stretch 0.75× / 1.25× / 1.5× at zero pitch | Measured 440 Hz for a 440 Hz source |
| Same ratios, +7 semitones | 659.5–660 Hz by zero crossings; target 659.255 Hz |
| Stretch sine RMS | 0.3521–0.3535 vs source 0.3536; no normalization |
| Opposite-phase stereo test | Worst peak L+R residual 0.000382; finite phase-reconstruction error remains |
| Eight beats: 120 BPM until beat 4, then 60 BPM | Exactly 288,000 exported frames at 48 kHz (6 seconds) |
| Tempo step with split raw audio and seek | Original sample sequence retained across boundary and sample-aligned split |

Tests also cover logarithmic ramp timing/inversion, exact duration after stretch latency trimming, repeated-render determinism, cancellation, rejected invalid inputs, PCM full-scale saturation and callback allocation/deallocation counters. Android tests exercise the real JNI exports, schema-v3 archive round trips, new panels, Undo, rotation and Back. See [VALIDATION.md](VALIDATION.md) for build-specific results.

## Limits and compatibility

Limits remain **16 tracks, 8 scenes, 128 total voices, 64 sample assets and 512 MiB decoded sample data**. A stretch/bounce must fit alongside its retained originals. This budget is not total app RAM: DSP, Kotlin UI, undo snapshots, workers and temporary buffers require additional memory. Audio remains fully decoded in memory. Export is RIFF WAV with a roughly 4 GiB file limit, not RF64. No 96/192 kHz mix export or disk streaming is introduced.

Projects now save as schema v3. Earlier v1/v2 projects migrate with the tempo map disabled. Earlier APKs cannot read v3 files. Development signing remains unchanged, so Phase 7 installs over prior Astra development builds. Physical Xperia latency, thermal throttling, sustained large-project performance and live Gemini behavior still require device testing.

## Third-party provenance

Vendored unmodified header sources and license texts are in `app/src/main/cpp/third_party/`:

- [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch), commit `88061fd4eda56a2353c5d65c74aa3f4ccf76f9da`, header version 1.3.2, MIT.
- [Signalsmith Linear](https://github.com/Signalsmith-Audio/linear), tag 0.6.6, commit `7bb1c8f36794d6980080ce817c91f8da8fe56a66`, MIT.

The upstream [usage guide](https://signalsmith-audio.co.uk/code/stretch/) explains configuration, latency and offline processing. No Ableton/Serum code, proprietary warp algorithm or commercial samples were copied.
