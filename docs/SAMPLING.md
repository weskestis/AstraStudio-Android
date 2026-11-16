# Phase 14 — Advanced sampling and layered instruments

Development 0.14.0-sampling (versionCode 15), Android 14+, Sony Xperia 1 IV target. The green/black phone workspace now connects slicing, independent sample layers, editable fades/loop overlaps, direct resampling and reusable kits to the native playback/export engine.

## Slice a recording or loop

1. Record and stop a take in **Record & Sample Lab**, then choose **Slice recordings to pads**. Alternatively open **File → Slice to pads**, or **Samples / drums → Slice to pads**.
2. Choose a project sample or saved recording. A saved recording is copied into project storage; the original take is retained. Installed pack sounds must first be loaded into project samples from Sound packs.
3. Choose 4, 8 or 16 as the maximum slice count. **Detect transients** scans for amplitude attacks with adjustable sensitivity. Silence produces one slice; detection is an editable starting point, not automatic beat/key recognition. **Equal slices** makes equal source-frame divisions.
4. Tap the waveform to position the cursor. Zoom up to 1024× and pan to inspect actual source audio. Enter an exact source frame to add a marker. Select a numbered marker to move/remove it; fixed file edges cannot be moved. Markers cannot cross or duplicate each other.
5. **Preview slice** auditions the chosen range. **Send slices to pads** replaces the selected track's pad assignments. **Pads + MIDI clip** also writes the original trigger timing into an empty scene slot on that track. Both preserve the original audio; Undo restores the old kit.

At most **16 slices per source** map to pads/MIDI 36–51. Each pad references a different frame range of the same source asset, with short editable fades: slicing does not consume sixteen source slots. Creating a timed MIDI clip requires each slice to span at least one MIDI tick; very short slices can still be sent to pads. MIDI triggers follow current BPM; changing BPM later changes trigger spacing without automatically time-stretching each one-shot. Use the existing Warp workflow when a whole audio clip should follow tempo.

Markers are saved in project JSON and portable .astra archives. They do not alter source WAV bytes or modify an already-created kit until applied again. Applying a new set replaces all 16 pad assignments and their extra layers; the keyboard sampler is retained. Undo remains available.

## Layer and shape sounds

In Sampler or **Drum rack → Edit pad**, choose **Add layer**, then **Choose sample** from project samples or Sound packs. Each sampler/pad holds **four layers total**: its primary layer plus three additional layers. Layers have independent source selection, source-frame trim and loop bounds, ±48-semitone pitch, volume, pan, ADSR, reverse, one-shot, choke group, fades and loop overlap. All assigned layers trigger on the same MIDI note. Clearing the primary layer leaves other layers playable; removing an extra layer preserves its siblings.

**Snap edges near zero** searches within about ±2 ms for quiet crossings in both channels. This is an edit to the stored frame boundaries, with Undo. It is not a guarantee that arbitrary material will loop transparently; listen and adjust the fade/loop range as needed.

Fade-in and fade-out controls use milliseconds (up to 1 second in the UI), stored exactly as source frames. They follow transposition and reverse playback. Loop crossfade overlaps the loop tail and head with a linear blend in either direction, limited to half the loop range and 1 second in the UI. A nonzero overlap reduces the repeated loop period by that overlap. **Set overlap to 0 to preserve the original loop timing.** Fades and envelope values apply to newly triggered notes; active notes finish using their original settings.

The keyboard sampler transposes relative to its root note; drum pads use their own pitch value. Velocity scales all layers. Choke groups act on matching layers within the track, leaving unrelated layers playing. Layers sum before the existing track effects, sends and master; this is not a per-layer insert rack, velocity zoning, round-robin multisampler or arbitrary parallel instrument rack.

## Resample directly

Open **Resample**, choose the selected clip or full Arrangement, a tail, and **48 / 96 / 192 kHz**. **Resample directly to sampler** renders a stereo float32 WAV with the source processing, then loads it into a different destination track's sampler in one Undo transaction. Source clips remain editable. **Resample to browser** remains available.

Choose a fresh destination track with neutral effects to avoid processing the bounce through extra inserts. The destination's previous instrument is replaced; its mixer/effects/sends remain. Master processing is already baked into a processed bounce and also remains active during playback. Isolated-clip resampling excludes other tracks as sidechain drivers. Full Arrangement resampling includes the complete mix. This differs from **Bounce instrument to clip**, which renders before track effects, and **Freeze track**, which caches an Arrangement instrument source while keeping mixing live.

The higher offline rate does not change the microphone or device playback rate, or recover detail absent from a recording. Finished master export still offers 192 kHz PCM24 and float32.

## Save reusable kits

Open **Saved instruments** from the instrument panel or File menu. Name and **Save current instrument**. It saves the keyboard sampler, all 16 pads and all layers, plus the current synth patch and track insert settings. Independent original-resolution WAV copies are committed to a private local instrument library. The current instrument must be a sampler/drum rack with at least one assigned source.

Start a new project, return to Saved instruments and load the kit on any of the 50 tracks. Loading copies required assets into that project, reuses matching library-source copies already present there, and supports Undo/Redo. It replaces the instrument and inserts; it retains track names, mixer, sends and clips. External sidechain source track numbers in saved effects stay as saved; check them when loading into a different arrangement. All retained pad/sampler sources count, including a currently inactive sampler or drum rack.

The device library holds **256 presets**, subject to available storage. Each preset carries its own audio; this costs disk space. Projects retain the **64-source / 8 GiB stereo-float-equivalent source** limits, and the native bank retains its **128 MiB active audio-page/resident budget**. A load that cannot fit fails and cleans up its new copies. Loading checks saved source integrity. Cancelling an unfinished save/load removes temporary copies. Interrupted saves are cleaned on startup.

Deleting a saved preset requires an explicit in-app confirmation and does not delete copies already loaded into projects. Presets are local to the app's storage. To transfer a kit, load it in a project and save/share the portable .astra archive; a separate preset-exchange format is not included in this phase.

## Engine and compatibility

One shared MIDI voice owns up to four fixed sample players. The project still has **128 shared instrument voices**, so the structural maximum is 512 active sample players if all voices are four-layer stacks. That is a capacity limit, not a measured phone performance promise. Crossfades read both loop positions; multiple layers increase CPU and streaming-page demand. Callback allocation/free regression checks cover layer starts, playback, release, choke, panic, and plan retirement. Source loading, waveform scans, detection and preset disk operations run outside the real-time callback.

Project JSON **version 9** and native wire **version 8** append layer/edge state while retaining migration of older versions. Default old-project sound and freeze fingerprints are preserved; editing layers/fades invalidates stale source freezes. All sources, including those referenced only by extra layers, remain retained for projects and freeze cleanup. New saves require this APK; keep an original portable copy for use with older APKs.

Physical Xperia latency, heat, storage under load and maximum sustained polyphony remain unmeasured. Validation evidence, including the exact tested build, is recorded in [VALIDATION.md](VALIDATION.md).
