# Phase 8 — microphone recording and Sample Lab

Android 14+, version 0.8.0-phase8. The green/black Record audio panel is available from the workspace toolbar and File menu.

## Record

- Tap Record microphone from the visible Activity. Android asks for RECORD_AUDIO only when you request capture. The service declares and activates the microphone foreground-service type before opening the input, alongside any active media/data work.
- Mono is the default. Stereo is requested when selected and falls back to mono if opening stereo fails. Input tries 48 kHz, then 44.1 kHz, float first and PCM16 fallback. The panel reports the actual Android stream rate/channel count/encoding and routed input. No claim of 32-bit microphone hardware is made: a float WAV container cannot improve the source ADC or undo clipping.
- UNPROCESSED is requested only when Android reports support; otherwise VOICE_RECOGNITION is preferred. MIC is a last fallback and is labeled as potentially processed. Manufacturer microphone, resampling and USB route behavior require physical-device validation.
- The recorder has a dedicated blocking AudioRecord/file worker. It never allocates, locks or writes files on the Oboe playback callback. Capture writes float WAV progressively with bounded buffers, checkpoints its header each second and recovers aligned data from interrupted `.part` takes on next launch.
- Input peak/RMS, clipped-sample count and Android input-silenced status update at most ten times per second. Only the meter subtree receives those readings. There is no input monitoring, AGC in app code, automatic normalization, system-audio capture or quantized live track recording.
- Back hides the panel while capture continues. Rotation/backgrounding retain it in the foreground service. Stop & save or the notification Stop audio action finalizes it. It never resumes microphone capture automatically after process death. One take is capped at 1 GiB audio payload (about 93 minutes mono or 46 minutes stereo at 48 kHz); recording stops when less than 16 MiB free storage remains. Actual phone endurance is unmeasured.

## Zoom, inspect and extract

Recorded takes remain in app-private storage separately from the active project. Open audio from storage decodes supported Android mono/stereo formats to float stereo, or copies supported PCM WAV, with a 1 GiB import/decoded payload cap. Import may need temporary disk space for both source and converted output. No full-take float array is loaded into the native engine.

The source picker also includes existing project samples. Zoom +/−, Fit selection, Fit all and pan buttons change the visible frame window. Drag to select uses half-open source-frame ranges; Pan / pinch zoom changes the visible window. At close zoom, sample-level lines are drawn. Exact start/end fields allow repeatable cuts. Files are read on a cancellable I/O worker into fixed-size buffers; only screen min/max bins are retained.

Audition selection streams the selected interval from storage at its source sample rate through an Android AudioTrack worker, with 65% audition gain and system output conversion. It pauses the DAW transport, does not run through track effects/master limiter, and does not alter saved audio. Back, closing the panel, backgrounding or audio focus loss stops audition. It is distinct from arrangement/export rendering. Extracted samples subsequently play/export through the existing native sampler and effects.

Analyze bass examines up to the first two seconds of the selection. A normalized difference periodicity estimator searches roughly 20–1000 Hz using a bounded, averaged analysis signal. It reports a pitch only above a periodicity threshold; a mono signal is taken from the stronger channel to avoid opposite-phase stereo cancellation. The FFT spectrum covers 20 Hz–2 kHz on a logarithmic scale. Periodicity is not a probability or a promise of the correct musical fundamental. Harmonics, changing pitch, noisy growls and short selections can be ambiguous. Choose a sustained section and adjust the sampler root note when needed. Analysis never changes the source audio.

Use as sampler creates a float stereo cut at the source rate and loads it into the selected track's keyboard sampler. The detected MIDI note pre-fills the editable root field. Add audio clip creates a cut in the next free scene on the selected track. No normalization is applied; with optional edge fades off, float input values are copied exactly (mono is duplicated to stereo). Two-ms linear edge fades are explicitly optional. One Undo restores the previous project/instrument state. Originals remain in the take browser until explicitly discarded.

## Storage and remaining limits

- Track/scene/voice/asset limits are 50/8/128/64 in Phase 11. Phase 10 replaces full decoded-project loading with bounded streaming; see [current project storage limits](STREAMING.md).
- Extracting must fit alongside retained sources under the project storage limits. Both Sample Lab and Phase 10 project playback read large sources through bounded windows/pages.
- Takes are shared across projects on the device. Portable `.astra` archives include extracted project samples, not unselected originals. App data removal/uninstall removes private takes. Use Save original WAV or Save selection WAV to back them up directly to a chosen Android document location without loading them into the engine. Direct selection export copies frames without edge fades; the fade toggle applies only to project extraction.
- Phase 9 adds live Warp, and Phase 10 adds streamed project playback and schema-v5 saves. Existing Stretch still renders a new fixed-duration clip.

## Platform sources

- [Android 14 microphone foreground services](https://developer.android.com/about/versions/14/changes/fgs-types-required#microphone)
- [AudioRecord float and blocking read API](https://developer.android.com/reference/android/media/AudioRecord)
- [Unprocessed/voice-recognition source guidance](https://developer.android.com/media/platform/mediarecorder)

See VALIDATION.md for the exact build/test evidence and physical-device limitations.
