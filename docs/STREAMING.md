# Phase 10 — streamed project audio and finished-track sharing

## Playback and storage

Project imports now allow **2 GiB per source file**, **64 audio assets** and **1,073,741,824 source frames** across the project (8 GiB when expressed as stereo float32). One asset is limited to 268,435,456 frames. Those are storage/format limits, not a promise that every possible project can run in real time. Track/scene/voice limits remain **50 / 8 / 128**.

Sources whose decoded stereo size is at most 1 MiB remain resident. Larger sources allocate **64 pages × 4,096 stereo float frames = 2 MiB** of audio page storage per asset, independent of file duration. The active asset bank therefore has a **128 MiB audio-page/resident ceiling**. This figure excludes synth/effect/warp state, the UI, page metadata, decoder buffers, temporary import/archive copies and independent offline readers. Project replacement can briefly retain old assets until existing immutable playback plans retire; an export holds its own bounded pages as well. It is not a whole-process RAM cap.

**Master** displays the current bank's audio-page memory, equivalent source size, streamed-file count, missed source-frame reads and I/O errors. Counts are cumulative for the loaded asset instances. A missed-frame count measures missing read requests (including interpolation/warp lookahead), not unique lost output frames or elapsed dropout duration.

All source validation and complete waveform peak scans happen on the file worker. Loading is cancellable between scan blocks. Mono/stereo PCM16, PCM24, PCM32 and float32 WAV values follow the existing conversion rules; Android decoding handles other import formats. Source samples are checked for nonfinite values and limited to the existing ±8 float range. No new normalization, lossy codec or resampling is introduced by caching.

A shared disk worker fills pages. Bounded lock-free requests, atomic page pins and a small per-voice lookup hint connect live readers to it. Allocation, file seeking/reading, mutexes and ownership destruction stay outside the audio callback. Prefetch follows cursor direction and warms clip/zone starts; queued Session launches request their media ahead of the launch boundary. The same read interface is used by ordinary audio clips, warp, sampler, pads and preview.

A cold seek or overloaded storage can still exhaust prepared pages. Live playback outputs silence for unavailable samples, keeps musical time moving, requests the missing page and reports misses; it never blocks the callback or replays the wrong old page. A subsequent read resumes from the current source position when the page arrives. This does not guarantee inaudible recovery. Bouncing instrument clips, a larger audio buffer or fewer simultaneous readers can reduce pressure. Actual Xperia sustained throughput, thermal behavior, route changes and end-to-end latency remain device-test work.

Exports, resampling and Stretch open **separate synchronous readers on the rendering worker**. They wait for their own file reads rather than capturing live underrun silence. Read failures fail the render. Independent file positions and page storage keep a simultaneous export from evicting playback pages. Audio sources stay on disk; page caches disappear when their owning plans/assets retire. No persistent decoded cache copies are created.

Phase 11 project JSON is **version 6**; versions 1–5 load and migrate the original 16-track scene stride. Native wire version 5 carries all 50 tracks and accepts older versions 1–4 with migration. Older APKs cannot read version-6 projects. Portable archive extraction remains bounded and transactional, with fresh local filenames. Originals and undoable source references are retained.

## Bounce instrument to clip

Open **Resample → Bounce instrument to clip**. A new 48 kHz float audio clip appears in the next empty scene on the selected track. The original remains editable and the operation supports Undo/Redo.

This taps one clip cycle **before insert effects, track faders, sends and master**. The new audio clip retains clip automation and uses the existing track rack/mixer, so the effects and master are not baked twice. Source BPM and Warp are set on the bounce so its timing can follow later tempo changes. Playing the bounced clip avoids regenerating that clip's instrument/voices. Original source/preset changes do not regenerate an already bounced file.

This is a phrase bounce. It is not persistent whole-track freeze/unfreeze, and instrument release tails that cross repeated cycle boundaries can differ. The existing **Resample to browser** still renders a selected phrase or the whole Arrangement through the complete mix, with an optional tail. Full-track freeze remains in the roadmap.

## Share finished track

Open **File → Share finished track** (also **Share track** in the device rack). Choose 24-bit PCM WAV or 32-bit float WAV, 44.1–192 kHz and a 0–30 second effect tail, then **Render finished track**. Sharing defaults to PCM24; the existing editing/export dialog continues to default to float32.

After completion:

- **Share WAV** opens Android's share sheet with the audio file and temporary read permission. Choose an installed compatible app, then finish sending there.
- **Save WAV as…** copies that same completed file through Android's document picker without rerendering or changing it.
- **Delete export** removes only that finished file after confirmation. Projects and source recordings are kept.

Completed WAVs remain available after rotation, Back, app restart and later project changes. New renders get distinct filenames. Files are not deleted when the share sheet closes, because receiving apps may read them later. Partial/cancelled renders are not listed and are removed. Storage is checked before rendering; a full disk or source error is reported. Finished WAVs use ordinary RIFF size limits. Their maximum size is separate from the 2 GiB project-import limit.

The provider exposes only the finished-track directory, is not exported, and grants read access only for the selected content URI. No receiving app gets project or recording paths. Nothing is uploaded automatically. Returning from the share sheet returns to the sharing panel; Back from that panel returns to the workspace.

SoundCloud's documented Android upload flow selects a saved audio file from its file picker. If SoundCloud is absent from the share sheet, save the WAV and select it in SoundCloud's Upload screen. There is no SoundCloud login/API integration in Astra. Email and messaging services apply their own attachment limits; compressed sharing formats are future work.

References:

- [Android file sharing setup](https://developer.android.com/training/secure-file-sharing/setup-sharing)
- [Android Sharesheet](https://developer.android.com/develop/ui/compose/sharing/send)
- [SoundCloud Android upload](https://help.soundcloud.com/hc/en-us/articles/360043080874-Upload-using-the-Soundcloud-App)
