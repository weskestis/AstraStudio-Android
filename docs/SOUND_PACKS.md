# Sound packs and imported wavetables

Version **0.13.0-soundpacks / versionCode 14**, Android 14+. Install over Astra 12 with the same development signing certificate.

## Install the Rift collection

Open **Samples → Sound packs → Install pack parts**. Select the seven existing `.riftpack` files together in Android's document picker. No conversion or unpacking is required. Each part is independently verified and installed; progress is shown. Cancelling or a failed part preserves completed parts. Selecting the same part again replaces/repairs it without adding duplicate sounds.

| Collection | Files | Samples | Wavetables |
| --- | --- | ---: | ---: |
| MAYGE Arcane | `RIFT_MAYGE_Arcane_Part_1.riftpack`, `RIFT_MAYGE_Arcane_Part_2.riftpack` | 672 | 6 |
| Ember Riddim | `RIFT_Ember_Riddim_Part_1.riftpack`, `RIFT_Ember_Riddim_Part_2.riftpack` | 334 | 0 |
| MAG Trance Source | `RIFT_MAG_Trance_Source_Part_1.riftpack`, `RIFT_MAG_Trance_Source_Part_2.riftpack` | 150 | 0 |
| Trance Starter | `RIFT_Trance_Starter.riftpack` | 38 | 0 |
| Total | 7 files | 1,194 | 6 |

The installed library is separate from the current project. It persists across new projects and app restarts. A library supports up to 20,000 sounds; each pack supports 4,096 entries, a 2 GiB archive and up to 4 GiB expanded content, subject to actual free storage. Ordinary ZIPs of supported PCM WAVs also work. Other compressed audio formats can still be selected through Project samples → Import samples. Original multipart RARs are not accepted directly.

The seven downloads total about 2.41 GB. Installed original content occupies about 2.65 GB, plus small indexes. Allow about **6 GB free** when downloading and installing all parts on the same phone, plus room for projects and exports. The installer handles one part at a time. Project copies consume additional space only for sounds actually used. Pack bytes are user-installed; the public APK/source does not contain these third-party recordings.

## Browse, play and edit

Search by sound, collection, category, filename key or BPM. Filter by collection/category or favorite sounds. The list displays 40 results per page so thousands of sounds do not create thousands of Compose views. Parts installed/expected and sound counts are visible. Favorite choices persist independently of projects.

Open a sound to see its waveform, duration, source rate and stereo/mono status. Preview reads from disk for up to 10 seconds, pauses the transport and does not consume a project asset slot or change the composition. Preview volume is reduced according to source peak; the audio file itself is unchanged. Back and Stop preview end audition. Wavetable source preview plays its stored cycles; load an oscillator and use Keys to audition it as a pitched instrument.

- **Use in sampler** loads an independent project source, ready for MIDI/Keys. **Sampler / drums** provides exact frame trim/loop points, reverse, pitch, root note, attack, decay, sustain, release, gain and pan. Filename keys initialize the sampler root in octave 4; this is metadata, not detected pitch, and stays editable.
- When opened from a drum pad, **Use on pad** loads that selected pad. Velocity, choke groups and envelopes remain editable.
- **Add audio clip** uses the selected track's next available clip slot. If a pack supplies source BPM, Warp starts enabled using that tempo; its source BPM, downbeat, pitch and enable state are editable. Unlabelled audio retains ordinary playback at its source rate.
- **Add to project samples** makes a sound available in the project browser and Sample Lab without changing an instrument. Sample Lab can zoom, analyze and extract portions; exports and project copies preserve the installed original.
- **Load oscillator A/B** assigns a wavetable to that synth oscillator. It retains the patch's other controls and selects the wavetable synth. Raise the oscillator level if the existing patch has it muted. **Edit synth / modulation** opens position, tuning, FM, warp, filters, envelopes and the modulation matrix. **Use factory bank A/B** removes an imported assignment.

Loading stays on the sound's detail page. Use the explicit Edit button, Back or Close to leave. Assignment and sound edits have Undo/Redo; installed parts and library favorites are global content, outside project Undo. Portable `.astra` saves include selected sample/wavetable audio and all project edits, so reopening on another phone does not require installing the pack.

## Fidelity and bounds

Every supplied sample and wavetable is copied byte for byte: no resampling, destructive normalization, lossy encoding or truncated long files during installation. The six MAYGE tables retain **all 256 cycles at 2,048 points**, including original float headroom. Native preparation creates frequency-limited mip levels off the audio thread. Levels retain at least 512 phase points for large tables to reduce interpolation images; source cycles are not reduced. The existing oversampled synth, modulation and export paths use those tables.

Prepared source PCM and mip memory count against the existing **128 MiB active source bank**. Large ordinary samples stream; imported oscillator tables are resident, shared by all assignments of that source. A 256 × 2,048 table uses under 12 MiB including its stereo source representation and mips. Installation never prepares the whole library as an active native bank. Track capacity remains 50, shared voices 128 and project source assets 64; browsing/previewing all 1,200 sounds does not consume those 64 slots. Higher export rates do not reconstruct frequencies missing from a source recording.

JSON project version 8 adds source cycle metadata and per-track oscillator assignments; native wire version 7 adds the corresponding source IDs. Earlier projects migrate with factory oscillator assignments. Existing Phase 12 freezes retain their fingerprints when no custom table is assigned; custom table changes invalidate the affected freeze. The original sources survive Undo, recovery and portable saves.

The source packs' creator information and text documents are retained. The 84 Ember presets for external synths/racks, eight Ableton racks, original FL template and MAG MIDI files were not playable Rift pack assets and are not converted into Astra instruments by this update.

## Verification

`SoundPackTests` covers large catalogs, persistence, favorites, independent parts, reinstall, hashes, malformed data, cancellation, path checks, repair, float headroom, table dimensions, legacy migration, freeze invalidation and filename roots. `SoundPackAudit` runs the actual Kotlin installer/PCM reader against all seven user-supplied files locally. The source audio is not uploaded to public CI.

`astra_imported_table_tests` verifies retained cycles, morphing, frequency-limited levels, native memory accounting, malformed assignments, Track 50 synthesis, pointer retirement, 192 kHz PCM24 export and audible export changes, with allocation/free tracking on the callback. Its optional directory argument audits all installed WAVs with the native reader. Android instrumentation covers install, browse/search, preview, assignment, editing, Undo/Redo, favorites, rotation/Back, imported oscillators, portable saves and JNI rendering. See [validation](VALIDATION.md) for actual run results and the verified APK.

Device-specific sustained performance and heat still require testing on a physical Xperia.
