# Phase 11 — 50-track capacity

Development 0.12.0-phase12, Android 14+, Sony Xperia 1 IV target.

## Working with 50 tracks

Tap **Track … / 50** beside Session and Arrangement. Select a track to jump to its clip column or timeline row without swiping through all previous tracks. The current scene is retained. Back closes the picker, closes detail panels, then returns Arrangement to Session; rotation retains the chosen track. Green/black styling and 48 dp controls remain.

A track can host the existing synth or sampler/drum instrument, audio clips, eight insert types, four automation lanes and two shared effect sends. Track count is distinct from device layering or unlimited parallel instrument racks. Multiple tracks can be arranged and mixed together. The virtual keyboard, clip editor, recorder extraction, stretch, bounce, Astra proposals and sidechain-source picker accept all 50 tracks. Physical MIDI channels retain the current 1–16 to Track 1–16 mapping; controller banks are future work.

| Resource | Phase 12 limit |
| --- | --- |
| Mixer / arrangement tracks | 50 |
| Session scenes | 8 |
| Total clip slots | 400 |
| Arrangement regions | 2,048, nonoverlapping within each track |
| Instrument voices | 128 shared across the project |
| Notes / automation points | 256 notes per clip; 64 points per each of four lanes |
| Source assets | 64 |
| Imported source file | 2 GiB |
| Source-frame total | 8 GiB stereo-float equivalent |
| Active audio pages / resident data | 128 MiB; excludes DSP, UI, retained plans and export readers |
| Native project description | 1,000,000 doubles |
| JSON project description | 32 MiB UTF-8 |
| Master WAV | Stereo PCM24 or float32, 44.1–192 kHz; standard RIFF size limit |

## Project compatibility

JSON version 8 adds imported-table assignments and source cycle metadata; version 7 adds freeze metadata and includes an explicit track count of 50. Versions 1–5 require their original 16-track arrays, then expand new tracks with empty/default state. Clip and region slots migrate from `scene * 16 + track` to `scene * 50 + track`; simply increasing a list size would move old clips onto incorrect tracks. Older sample instruments, tempo maps and warp state retain their existing version migrations. Native wire version 7 also accepts versions 1–6 and remaps clip, region and warp slots.

For example, old slot 127 (Scene 8, Track 16) becomes slot 365, while new slot 399 is Scene 8, Track 50. Malformed dimensions and out-of-range legacy slots are rejected. Portable archives and autosave use the same codec. New saves cannot open in the older APK; retain an original portable copy when backward compatibility is needed.

## Audio and resource behavior

Kotlin and C++ capacity constants are generated from `tools/capacity-schema.json`. JNI export arrays, mixer state, sequencer snapshots, per-track meters and the master-meter sentinel share those dimensions. There are 128 preallocated patch slots and 128 effects slots, so publishing all 50 tracks before a callback does not exhaust the former 32-effect-slot mailbox. Bypassed racks skip disabled unit processing after their enable fades settle; fixed latency histories stay warm, and enabled delay/reverb tails are not silenced based on input level.

All effects and warp state are prepared off the callback. No allocation, free or lock was added to render processing. The 128-voice pool is unchanged; adding storage for tracks does not multiply the maximum polyphony by 50. High sample rates increase DSP memory. A local Release test rendering 50 basic audio layers for 0.25 seconds at 192 kHz took 0.668 seconds, with 424.9 MiB process peak RSS across the capacity suite. This is a host fixture, not a phone benchmark or total app-memory estimate. The output is compared against an isolated Track 50 export to verify all fifty contributions, not just the file header.

Physical Xperia CPU, thermal endurance, storage latency and maximum sustained active DSP remain unmeasured. Use the device-rate playback engine and its CPU/xrun meters, and freeze demanding Arrangement tracks or bounce instrument phrases as needed. Offline master export may take longer than the song duration. Group buses, extra return buses, controller banking, effects/Session freeze and deeper device layering remain in the broader roadmap.

Phase 12 freezes consume one of the same 64 asset slots per track. Originals and active caches count toward source storage, and unused caches release their active slots when unfrozen while files remain for Undo/recovery. See [freeze/recovery details](RELIABILITY.md).

See [validation evidence](VALIDATION.md) for the tested package and CI result.

Phase 13 adds a separate on-disk sound-pack library, up to 20,000 sounds. Installed packs and previews do not occupy the project's 64 source slots. Imported oscillator tables share the 128 MiB active source-memory budget with PCM/pages. See [sound packs](SOUND_PACKS.md).
