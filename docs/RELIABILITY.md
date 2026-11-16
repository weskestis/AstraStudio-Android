# Phase 12 — Arrangement freeze, recovery and performance

Development 0.12.0-phase12 / versionCode 13. Android 14+, Sony Xperia 1 IV target, green/black interface. The 50-track project capacity and 192 kHz / 24-bit master path remain available.

## Freeze a whole track

Select a track, then **Freeze track** in the bottom device rack. Place its clips in Arrangement first. Choose 48, 96 or 192 kHz and 0–30 seconds of instrument release tail, then **Freeze full track**. The default is 192 kHz / 32-bit float with ten seconds of tail. Playback pauses during rendering; the operation is cancellable. When complete, the panel shows the frozen rate and **Unfreeze track**.

The cache covers beat zero through the entire Arrangement end, including silent gaps and the chosen instrument tail. It taps the track before insert effects, fader/pan, sends and master. The original notes, audio clips, sampler assignments, instrument patch and source files stay in the project. Playback skips the frozen track's synth/sample voices and ordinary/warped clip source processing. Effects, sidechains, track mix, volume/pan/FX-macro automation, sends and master still run. Filter-cutoff automation belongs to source synthesis and is baked into the freeze.

This is **Arrangement source freeze**. Session clips and the keyboard in Session continue to use the original instruments. Keys/MIDI cannot layer a second live instrument over a frozen Arrangement track; unfreeze or use Session for that. Freeze is not an effects-chain or Session-scene freeze, a flattened project, or an increase to the shared 128-voice pool. Heavy insert effects can still dominate processing after freezing.

Source edits automatically invalidate affected freezes: instrument/patch, notes, audio trim/warp/pitch, clip placement, source-filter automation, tempo and Arrangement length. A source fingerprint excludes mixer/effects changes and portable filename changes. Unfreezing re-enters notes already held at the current beat. Seeks and looping address the cached musical position using the tempo map. Stopping early does not play future phrases; reaching the Arrangement end allows the cached release tail.

Undo/Redo restore the original or frozen version, including the correct audio bank when an asset ID has been reused. Unused freeze assets leave the active project on unfreeze/invalidation, freeing their sample slots. Files remain on disk for Undo and recovery. If a cache is also assigned to a clip or sample instrument, it remains an ordinary project asset. Portable saves include the sources and active freeze audio.

### Fidelity and resource limits

- Caches are stereo 32-bit float, without PCM quantization/dither or normalization. Master PCM24 conversion still dithers only at final output. The dry-source rendering path skips downstream processing that the tap would otherwise discard.
- A frozen source retains the selected rate. Exporting a 48 kHz cache at 192 kHz cannot recreate detail absent from that cache; unfreeze and render at 192 kHz when that matters. Output still uses the selected native DSP rate for effects/master.
- One cache consumes one of the project's **64 asset slots**, along with its original audio sources. Fifty frozen tracks therefore need at least fifty free/available slots; a project already using many samples cannot freeze every track without managing those assets.
- Stereo float storage is **23.04 / 46.08 / 92.16 MB per minute** at 48 / 96 / 192 kHz. The **2 GiB file limit** and **8 GiB source-frame equivalent project limit** apply. At 192 kHz one cache holds approximately 23.3 minutes including tail. The panel estimates storage; insufficient room or an oversized render is rejected before rendering.
- Larger caches stream through the existing 2 MiB per-asset pages under the 128 MiB active audio-page/resident ceiling. DSP/UI/export memory is additional. Freeze reduces source DSP work; it does not claim to reduce every kind of RAM or guarantee fifty heavy tracks in real time.
- Choose enough tail for the instrument; audio after the selected tail is not cached. Export has its own tail setting for the still-live effects. Projects exceeding the global voice pool can change voice-stealing behavior as tracks freeze, so exact equivalence is not promised for overloaded projects.

## Autosave recovery

Autosave retains the current project plus **three previous distinct saves**. Writes sync a temporary file and atomically replace the target in the same directory. Existing Android AtomicFile backups are repaired before migration. Incomplete `.pending` files cannot replace a valid current project.

At startup, the current JSON and its referenced audio are checked. If either is unusable, Astra tries the backups newest first. A damaged current file is quarantined when possible (up to three damaged copies retained); recovery is reported. If none is usable, Astra reports the failure rather than claiming that an empty project was recovered. Original audio files are retained. This is local recovery, not a cloud backup or a power-loss durability guarantee for every filesystem/hardware combination.

Open **File → Recovery** to refresh the available copies and restore one. Entries show the original save time and project name. Restore is undoable. Selection is bound to a content digest, so a concurrently rotated entry cannot restore a different project accidentally. **File → Save portable project** remains the independent backup/export workflow; uninstalling or clearing app data removes private recovery copies.

JSON **version 7** adds explicit frozen-track metadata. Versions 1–6 still load; versions 1–5 retain their 16-to-50-track migration. Native wire **version 6** adds the corresponding validated trailer and still accepts versions 1–5. Older APKs cannot read the new format. Keep an older portable copy if you need to return to an earlier APK.

## Performance panel

**File → Performance** reports device/build, audio rate/buffer, current and highest polled DSP utilization, voice counts, observed xruns, queue drops, late commands, missed source frames, read errors, Java/native heaps and active audio pages. **Reset readings** starts a new observation window; **Copy performance report** copies the text for troubleshooting. No project/audio is sent anywhere by this panel.

CPU/voice peaks are polling observations and can miss brief spikes. An xrun is an output stream underrun/overrun report; source-read misses have a separate meaning. The software buffer duration is not measured round-trip latency. Heap values exclude some graphics/shared memory. Stream restarts/unknown xrun readings are handled without fabricating negative counts. Editor panels omit meter-only updates to avoid redrawing static controls at the polling rate.

For physical Xperia validation, use the same project, output route and buffer setting, play its busiest section for several minutes, then copy a report. Compare the live and frozen versions; record Android/build, battery/charger state, audible dropouts and route. Thermal endurance, microphone/output latency, USB behavior and maximum sustained DSP require that real-device pass. Host and emulator evidence are recorded separately in [validation](VALIDATION.md).
