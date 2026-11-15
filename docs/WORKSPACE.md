# Phase 4 workspace and project format

## Views and transport

The green/black workspace adapts to portrait and landscape without locking rotation. Track columns and toolbars scroll horizontally; scenes and track rows scroll vertically. Bottom device chips stay accessible. Editors remain open until Back or Close. Main buttons use 48 dp targets; dense Canvas notes also have exact-value editing controls.

Session holds 16 tracks × 8 scenes. A clip body selects it, double-tap/long-press edits it, and its separate launch button queues it. Scene buttons launch every track together; empty slots stop that track at the same boundary. Quantization is one signature-aware bar, one quarter note or one eighth note. Re-launch retriggers at the next boundary. Stop-track uses the same quantization.

Arrangement regions reference source clips. Select a source in Session, switch to Arrangement, tap the ruler and Place clip. Horizontal dragging snaps region starts to quarter beats. Region opens exact start/length fields, delete and audio sample split. Regions cannot overlap on one track. Duplicate region retains the same source; Duplicate clip creates an independent editable source in the next free scene slot. Source edits therefore affect all regions using that clip.

Loop braces use the Project name/loop panel's quarter-note positions, indexed from zero. The ruler displays signature-aware bar numbers starting at one. Loop affects Arrangement only. Follow pages the visible window during playback; zoom changes the visible beat span. Playhead drawing follows Compose frame callbacks and interpolates the latest native clock. This is not a measured 60/120 Hz performance claim.

## MIDI and automation

Draw adds notes to the selected grid. Select moves notes; the right part of a note resizes it. Erase deletes. Octave/time-window controls navigate the roll. Exact note fields and velocity sliders supplement touch gestures. Quantize changes onset and length; legato extends each onset to the next onset or clip end. Root/major/minor only highlight pitch rows and do not transpose existing notes.

Each clip has four linear automation lanes: volume, pan, filter 1 cutoff (20–18,000 Hz) and FX macro 1. Draw replaces snapped points, Erase removes the nearest point, and Clear restores the base control. Macro 1 must be assigned in the effects editor. Automation is clip-relative, loops with it, and does not overwrite the saved base patch/mix. Volume/pan use mixer smoothing; cutoff uses the synth's filter updates. Limits: 64 points per lane, 256 MIDI notes per clip.

The keyboard supports multiple fingers, slide between keys and accessible note actions. USB channels 1–16 address tracks 1–16. Record MIDI arms the selected track and launches its selected MIDI clip at the quantized boundary. Capture starts only once that clip is active. Overdub preserves notes; disabling Overdub clears the previous notes. Notes crossing the clip end are split across the boundary. Capture uses published native transport/phase snapshots and is not calibrated for touch, controller or device I/O latency. Recording is MIDI-only in this version.

## Audio and export

PCM16/24/32 and IEEE float32 mono/stereo WAV load natively. Other Android-supported mono/stereo formats decode through MediaCodec on the I/O worker. Imported files are copied to private immutable WAV assets. 8–384 kHz sources use linear interpolation at playback rate; high-quality offline sample-rate conversion and independent warping are later work.

Imported audio keeps its pitch and speed. Clip beat length is set from duration and BPM at import. Changing the song tempo changes the musical loop boundary, not source speed. Audio trim uses inclusive first and exclusive last source-sample indices. Arrangement sample splitting rounds the cursor to the nearest source sample and carries the exact integer offset into the second region. Arbitrarily lengthened regions repeat the source clip at its musical loop length; audio beyond a trimmed source end is silent until that loop restarts.

Save portable project writes a `.astra` ZIP containing `project.json` and `audio/<safe-basename>.wav` for used assets. Opening validates filenames, size, duplicate entries, note/region limits and asset metadata, then copies audio privately before adopting the project. Work autosaves with Android AtomicFile. Keep portable saves for deliberate versions and backups; New project replaces the current autosave and clears Undo.

WAV export always uses Arrangement, includes the same synths, effects, automation, mixer, post-fader reverb/echo sends and master limiter, and ignores the playback loop. Output is stereo PCM24 at 44.1/48 kHz, with a selectable 0–30 second tail, fixed rack/limiter latency trimmed and deterministic TPDF dither. It is a sample-peak limited file, not a certified true-peak master. Import/save/export continue as a foreground file job and can be cancelled. A cancelled or interrupted destination may be incomplete and should not be used.

## Limits and deferred features

16 tracks, 8 scenes, 128 shared voices, 512 nonoverlapping arrangement regions, 256 notes per clip, 64 audio assets and 512 MiB decoded stereo audio per project. Timeline ends at 65,536 quarter-note beats. Full-length tracks fit within the audio budget; there is no arbitrary 60-second import cutoff. Raw input/archives also have bounded size, and RIFF export is limited to 4 GiB. Actual polyphony, route latency and thermal limits require Xperia testing.

Not implemented: microphone recording, simultaneous recording into multiple clips, arrangement overdub, MIDI file interchange, tempo maps, per-note expression, sampler/drum rack, plugin hosting, advanced time stretch, arbitrary automation targets, arrangement crossfades, per-region pitch envelopes or arbitrary Astra operations beyond the Phase 5 allowlist. The original starter uses synthesized percussion, not a hidden drum sampler. Astra commands and Gemini listening are described in [ASTRA.md](ASTRA.md).

## Wire contracts

`ProjectCodec` JSON format/version: `astra-studio` / 1. It stores project name, BPM, signature, loop, launch quantization, 16 mix/synth/FX records, clips, MIDI IDs/notes/velocities, four lanes, arrangement regions, and audio metadata/wave summaries. Unknown versions and invalid values are rejected.

Native project wire is a bounded double array: header `[version, mode, numerator, denominator, loop, loopStart, loopEnd, clipCount, regionCount]`; clip header `[slot, length, asset, first, last, noteCount, pointCount]`; notes `[beat, length, pitch, velocity]`; points `[lane, beat, value]`; regions `[id, slot, start, length, clipOffset, audioSampleOffset]`. Internal IDs and source sample offsets are validated integers. MIDI-only asset is -1. Native sequence telemetry is 49 floats: 16 active slots, 16 queued scenes, 16 normalized phases and mode. Telemetry is observational, not a transactional snapshot.

Primary references: [Android document picker](https://developer.android.com/training/data-storage/shared/documents-files), [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).


## Phase 6 extension

See [SAMPLER.md](SAMPLER.md) for sample instruments, packs and resampling. Project JSON version 2 adds sample assignments, favorites and collections; version 1 projects remain readable. Portable projects now retain every browser asset, including unused samples. Android Back first returns from subpages and dialogs, then closes panels, then returns Arrangement to Session.
