# Samples, drum rack, resampling and Back

Introduced in Phase 6; current sound-pack workflow updated for 0.13.0-soundpacks / versionCode 14. Android 14 minimum, ARM64 and x86_64; the development signing certificate is unchanged.

## Play samples

Open **Sampler / drums** in the selected track's bottom device chain. Select **Sampler** for chromatic keyboard/MIDI playback or **Drum rack** for 16 pads. Synth, sampler and drum rack are mutually exclusive note instruments on each track; changing mode retains the other instruments' settings. Audio clips continue to play through that track independently of its note instrument.

**Drum rack → Load starter kit** creates 16 original deterministic percussion samples, including kicks, snares, clap, hats, toms, metallic percussion, crashes, an 808 and a noise rise. It replaces that track's pad assignments as one Undo step. No third-party recordings or commercial sample packs are bundled. Tap pads to play/select; **Edit pad** opens the selected sound. Long-press also opens its editor. Pad note numbers are 36–51, contiguous rather than a complete General MIDI mapping; kick/snare/closed hat occupy 36/38/42 and open hat 46. Touch pads, the piano roll, USB MIDI and WAV export use the same native sampler. **Record MIDI** captures pad performances in the selected clip with the existing recording/overdub workflow.

Choose a sample from **Samples**, open its details, then assign it to the sampler or selected pad. Sample controls include root MIDI note (sampler), pitch ±48 semitones, volume, pan, attack/decay/sustain/release, reverse, one-shot and loop. A drum's pitch knob transposes its source; incoming drum note numbers select pads. Velocity scales volume. Choke groups 1–8 fade earlier voices in the same group on that track over 5 ms; 0 disables choking.

**Trim / loop frames** edits exact source-frame positions. End frames are exclusive. White waveform lines mark the trim range; yellow lines mark the loop. Loop bounds must be inside the trimmed range. One-shot ignores ordinary note-off; gated playback releases on note-off. Loop and one-shot are mutually exclusive. Transport Stop releases one-shots too; Panic clears all notes, audition and effect tails. Changes to a zone take effect on its next note; currently playing notes retain their original settings. Switching instrument mode stops that track's held voices.

This is pitch-linked sample playback: transposition changes speed and duration. Phase 7 adds offline independent time stretching and Arrangement tempo maps; see [QUALITY.md](QUALITY.md). Microphone recording and Sample Lab are documented in [RECORDING.md](RECORDING.md); granular synthesis remains future work.

## Browser and packs

**Samples → Project samples → Import samples** accepts a multi-selection of audio through Android's document picker. These sources belong to the current project, including favorites and collection labels. The active project supports **64 sources**, **2 GiB per file** and **8 GiB of stereo-float-equivalent frames**, with 128 MiB of active native source pages/resident data. Large ordinary sources stream.

**Samples → Sound packs → Install pack parts** installs `.riftpack` files or PCM WAV ZIPs into a separate persistent library of up to 20,000 sounds. All seven Rift parts work directly; only chosen sounds enter the project's 64 slots. Search/filter, preview, favorite, then load into a sampler, pad, audio clip or wavetable oscillator. Read [Sound packs](SOUND_PACKS.md) for installation, editing and exact limits.

Project samples retain the existing reduced-level native preview of up to 10 seconds. Installed-pack previews use an independent disk-backed audition and pause transport. Neither changes the composition or Undo history. Back/Stop ends audition. Project copies and selected wavetable sources travel in portable saves; installed originals remain unchanged.

## Resampling

Select a clip and open **Resample**, or choose **Arrangement** in that panel. **Resample to browser** writes 48 kHz, 32-bit float stereo WAV with an adjustable 0–30 second effect tail. Results appear under **Samples → Resampled**. There is no automatic destructive replacement: the source stays editable, and assigning the bounce is a separate operation.

An isolated clip bounce plays one cycle at the current project tempo, including its instrument, track rack, sends and master processing. Other tracks do not play and therefore do not drive external sidechains. The full-arrangement option includes all arranged tracks and their sidechains, ignores playback looping and uses the existing offline engine. Fixed engine latency is trimmed. Assign a processed bounce to a fresh track to avoid applying the same effects twice. An early duration/memory check rejects a bounce that would exceed the sample budget.

## Android Back

Panels are explicit Compose dialog windows. Android Back and the visible **‹ Back** button share a scoped child-handler stack. A child can consume Back before its containing panel closes. **Close ×** closes the whole panel directly. Leaving a panel releases held touch notes and stops browser audition; playback itself continues.

Examples:

| Current screen | First Back | Next Back |
| --- | --- | --- |
| Pad trim frame fields | Pad settings | Pad grid |
| Pad settings | Pad grid | Session/Arrangement |
| Sample details opened from a pad | Sample browser | Parent instrument panel |
| Gemini settings / JSON / feedback | Main Astra panel | Session/Arrangement |
| Clip properties dialog | Clip editor | Session/Arrangement |
| Synth detail tab | Oscillators | Collapsed synth controls |
| Effects controls | Effects rack overview | Session/Arrangement |
| Arrangement with no panel | Session | Normal Android leave behavior |

The system keyboard consumes Back first while visible. Rotation and returns from the document picker retain the workspace/panel state. Back hides a file-operation progress dialog while the foreground operation continues; its Cancel action explicitly stops it. Normal Android Back can leave the Activity only at the main Session workspace with no open panel or dialog.

## Native ownership and quality

Project wire version 2 appends 16 immutable instrument configurations. A zone is 17 bounded numeric values and an instrument is its mode plus one sampler zone and sixteen pad zones (290 values). Native and Kotlin validation reject invalid IDs, nonfinite values, fractional frame fields, invalid trim/loop ranges and incompatible one-shot looping. JSON project version 2 persists these values; version 1 projects migrate to synth mode with empty sample assignments. Older APKs cannot open newly saved version 2 projects.

Sample voices share the existing 128-voice pool and per-track stereo effects. A precomputed 64-tap, 128-phase windowed-sinc table uses conservative cutoff buckets for upward transposition. Integer-rate playback has a direct path. Start/end ramps, loop-edge fades, envelopes and choke fades reduce discontinuities; finite interpolation and very large transpositions are not alias-free guarantees. Timeline audio now uses the same converter, with double accumulation and interpolation between adjacent phase kernels. There is no FFT, allocation, file access, shared-pointer ownership operation or mutex in a sample voice tick.

An active plan owns all raw sample pointers used by voices. Before retiring it, the callback stops voices whose sample identity or instrument mode changed. Voices can continue across unrelated edits only if the new plan retains the same asset pointer. Reclamation happens when a control-thread producer reuses a retired plan slot. Browser audition follows the same ownership rule and has separate queue commands; panic discards pending audition starts.

## References

- Android custom Back: https://developer.android.com/guide/navigation/custom-back
- Android multiple document selection: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments

Performed checks and remaining hardware limits are recorded in [VALIDATION.md](VALIDATION.md).
