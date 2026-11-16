# 192 kHz / 24-bit master export

This update adds a delivery format to Astra Studio 0.10.1. It does not claim completion of the workstation roadmap or hardware certification.

## Use it

1. Put the finished song in Arrangement.
2. Open **File → Export arrangement WAV** to save directly, or **File → Share finished track** to keep an export available for sharing.
3. Select **192 kHz / 24-bit master**. This sets both the rate and PCM24 encoding. Select the desired effect tail, then export/render.
4. In the sharing panel, use **Share WAV** or **Save WAV as…**. The saved or shared bytes are the completed master; sharing does not rerender or transcode them. A receiving service may perform its own conversion.

Both screens offer 44.1, 48, 88.2, 96, 176.4 and 192 kHz, with PCM24 or float32. The one-tap master preset is selectable; existing defaults remain 48 kHz. Format choices and the open export dialog survive rotation. Controls wrap and scroll on a phone. Back dismisses the export dialog or returns through the sharing panel.

## Render path

The offline renderer prepares the full native graph at the selected sample rate. Synths, source conversion, Warp, clip automation, tempo-map timing, inserts, sends and master limiting run at that rate. Synth oscillators and driven filters retain their existing 2× internal processing. This is not a lower-rate completed mix relabeled as 192 kHz.

Imported sources retain their original stored rate and samples. The existing 64-tap converter maps them to the master rate; resampling cannot restore information absent from a source. No microphone or Oboe hardware-rate change is made by selecting a master format. Android's low-latency guidance recommends using the device's native playback rate; offline file rendering is independent of that path.

The comb-filter delay storage is prepared for the selected internal rate, so a 20 Hz setting still has a 50 ms delay at 192 kHz output. Extra buffers are allocated during preparation, never at note-on or in the render callback. The original inline buffer remains sufficient at ordinary playback rates. Comb feedback damping and initial parameter settling account for sample rate.

The final WAV contains two channels of signed, little-endian, packed 24-bit PCM: 192,000 frames/second, six bytes/frame and 1,152,000 bytes/second. The float mix is dithered with TPDF once at the final integer conversion. Optional float32 output is unquantized and undithered. Master limiting remains sample-peak limiting; this update adds no true-peak or loudness certification.

## Capacity and quality limits

- Stereo 192 kHz PCM24 uses **69,120,000 bytes/minute**, about 69.1 MB or 65.9 MiB, plus the header. Float32 at this rate uses 92,160,000 bytes/minute.
- Standard RIFF WAV remains limited to roughly 4 GiB: about 62 minutes for this PCM24 format, including tails. RF64 is not implemented. Both export paths check size and available local storage before rendering. A destination may have an additional storage or attachment limit.
- Higher rates increase processing, delay/FFT memory and render time. The active sample-page budget remains separate from DSP/export memory. At 192 kHz, extended comb buffers alone add 32 MiB across the fixed voice pool; effect/warp buffers add their own rate-dependent storage.
- Source files, project schema v5, track/scene/voice counts, live-device settings, Sample Lab recording and the existing 48 kHz instrument-bounce workflow retain their prior behavior.
- Higher sample rate and bit depth are delivery properties, not guarantees of better sound. Noise, clipping, recording quality and the existing synthesis/stretch algorithms still determine the result. No Xperia 192 kHz physical output or microphone capability is claimed.

## Verification

The native master suite checks all offered rates, exact PCM/float headers and file sizes, final 24-bit quantization, an independently streamed 30 kHz fixture that must survive the 192 kHz renderer, 48→192 kHz bass conversion, comb timing, synth tuning, all effect types, Warp/tempo changes, cancellation and RIFF rejection. Render-thread allocation/free counters must remain zero. Android tests exercise both selectors, rotation, actual PCM24 rendering through JNI and byte-exact sharing/saving. Build-specific results and APK identity are recorded in [validation](docs/VALIDATION.md).

References: [Android low-latency guidance](https://developer.android.com/games/sdk/oboe/low-latency-audio), [Oboe stream builder](https://google.github.io/oboe/classoboe_1_1_audio_stream_builder.html), [PCM block alignment and byte rate](https://learn.microsoft.com/en-us/windows/win32/api/mmreg/ns-mmreg-waveformatex).
