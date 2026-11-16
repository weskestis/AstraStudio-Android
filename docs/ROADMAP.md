# Astra Studio — full workstation roadmap

## Product goal

Build a professional Android DAW with Ableton Live-level workflow depth and sound-design capability. This remains the development objective. A first release candidate is a milestone within that objective, not its replacement or an automatic stopping point.

Target device: Sony Xperia 1 IV (XQ-CT62), Android 14+. Preserve the green/black interface, phone-only installation and file workflows, hierarchical Back navigation, original recordings, and public source repository. EDM, riddim, dubstep, trance and house remain the production focus. Sound quality and repeatable project/export behavior take priority over adding controls that are disconnected from the engine.

The current baseline is Phase 13, version 0.13.0-soundpacks, with 50 tracks, native 192 kHz mastering, Arrangement source freeze, autosave recovery and performance reporting, a reusable sound-pack library and imported wavetables. See [validation](VALIDATION.md) for the tested source and APK. This is a foundation with working features and explicit limits, not a claim of full parity.

## Milestones and numbering

The earlier proposal of five additional phases described a possible first-release path. It was not an estimate for completing the full workstation goal. There is no evidence-backed final phase count or completion date yet. Large workstreams must be broken into reviewable, installable increments; their phase count can grow without shrinking the product goal.

| Milestone | Scope | Completion evidence |
| --- | --- | --- |
| Phase 9 — tempo-following audio | Per-clip warp state, source tempo/downbeat, independent pitch and time, saved timing information; Session and Arrangement behavior, including tempo-map changes | Musical landmarks remain aligned in playback, seeks, loops and export; originals and Undo survive edits; transient, tonal, stereo and mixed-material fixtures are evaluated. Any cache/render preparation is visible and cancellable. |
| Phase 10 — storage and project scaling | Bounded audio streaming for project playback, prefetch/seek handling, memory accounting, cache lifecycle and freeze/bounce support | Large source files play without loading every sample into RAM; callback allocation/I/O checks remain clean; seeks, route changes and underruns are handled; capacity is measured on the Xperia. Raising counters alone does not complete this milestone. |
| Phase 11 — 50-track capacity | Expand every project/audio/control path to 50 tracks, 400 clips and 2,048 regions; migrate existing projects and add direct track selection | Dense project round trips, old-format migration, all-track audio/sidechain/JNI export checks, virtualized phone UI, Back and rotation. Physical live-playback capacity remains unmeasured. |
| Phase 12 — freeze and reliability | Full Arrangement source freeze/unfreeze, selectable float cache quality, three-generation recovery and performance reports | Source/master audio equivalence, tempo/seeks/tails, asset reuse and Undo, recovery corruption fixtures, Android navigation/rotation/export. Physical Xperia measurements remain open. |
| Phase 13 — sound-pack library | All seven Rift pack parts in a persistent searchable library; original-resolution oscillator wavetables; editable sampler/pad/clip assignments | Validate every supplied asset, source integrity, pack installation/repair, portable projects, native export and phone navigation. |
| Subsequent feature increments | Close the workflow, recording, MIDI, sound-design, mixing and extension gaps in the ledger below | Each feature has a usable UI, functioning engine behavior, saved state, Undo where appropriate, matching export behavior and relevant regression coverage. This is multiple work packages, not an assumption that all fit in one APK. |
| Quality milestones, starting with Phase 12 | Device profiling, audio quality, stability, accessibility and long-session testing | Publish device/build/route-specific measurements and reproduce regressions. Apply this work throughout development, not only at the end. |
| Release milestones, starting with Phase 13 | Recovery, migration, tutorials, packaging, signing/update compatibility and release readiness | A release candidate has an explicit supported feature/capacity list and passes its acceptance checks. Shipping one does not retire unfinished capability rows. |

Phase 10 implements bounded source streaming, direction-aware prefetch, separate complete-data export readers, memory/miss diagnostics, instrument phrase bounces and finished-WAV sharing. Phase 12 adds Arrangement source freeze/unfreeze; physical Xperia capacity/route/thermal measurements and effects/Session freeze remain open in this workstream. The next feature increments continue the ledger rather than treating these partial milestones as full workstation parity.

## Capability ledger

These are project requirements and acceptance criteria. “Partial” means an implemented starting point exists, not that the full capability is done. The Live 12 documentation is the reference baseline for the comparison; changes in the reference product should be recorded explicitly rather than silently moving the target.

| Area | Current implementation (0.13.0) | Required development / acceptance |
| --- | --- | --- |
| Session performance | Partial: launch grid, scenes, quantization | More flexible scene/clip capacity, launch behaviors, follow actions, capture of a Session performance into Arrangement, and reliable live editing with deterministic timing. |
| Arrangement editing | Partial: regions, splits, duplicates and basic zoom | Multiselection and linked editing, fades/crossfades, consolidation, detailed clip properties, flexible lanes, group organization, and touch editing without accidental panel closure. |
| Warping and granular sound | Partial: offline stretch plus source-tempo/downbeat clip warp | Persistent warp markers, transient/downbeat tools, automatic musical alignment, material-appropriate algorithms and granular manipulation. Verify sound and timing independently; a mode label alone is insufficient. |
| Audio recording | Partial: independent microphone takes and extraction | Recording directly into Session/Arrangement, punch/loop recording, latency compensation, input routing/monitoring, supported USB/multichannel inputs, take lanes and comping. Measure input/output timing on real hardware. |
| MIDI composition | Partial: piano roll, velocity, quantize, legato and basic MIDI capture | MIDI file interchange, multi-clip editing, groove/swing, chance and generative note tools, retrospective capture, scale/tuning tools and production-ready MIDI effects. |
| Expressive MIDI and controllers | Partial: basic MIDI 1/2 notes and panic CCs | Per-note expression, pitch/pressure/timbre capture and editing, mapping/learn, controller feedback, external MIDI output and clock/sync. Validate the actual advertised protocol features with hardware. |
| Automation and modulation | Partial: four clip automation targets and synth modulation matrix | Consistent parameter IDs and automation across mixer/devices/racks, Arrangement envelopes, curves, recording/touch/latch behavior, relative modulation, and predictable manual override/re-enable behavior. |
| Wavetable and FM instruments | Partial: native dual wavetable and four-operator FM engines, imported full-cycle wavetable sources | Direct cycle drawing, deeper modulation workflows, expanded voice/unison controls, patch browser/preset management, and quality modes with measured aliasing/CPU behavior. |
| Sampler and drum instruments | Partial: keyboard sampler, 16 pads, trimming/loops/choke, resampling | Multisample/velocity zones, slicing, layered pads, instrument/device layering, granular controls and efficient resampling. Every editable parameter must affect both playback and export. |
| Broader instrument coverage | Starting synth/sample engines exist | Audit the reference instrument roles and develop the missing synthesis/percussion/sampling capabilities and original sound library. Track functional coverage individually rather than equating a preset count with instrument depth. |
| Effects and creative processing | Partial: eight reorderable native effect types per track | Expand the effect palette, processing options and modulation; support flexible device instances and deeper racks. Validate spectral, dynamic, timing and stereo behavior against documented design targets. |
| Mixing and routing | Partial: 50 tracks, fixed device capacities, two shared returns and sidechain | Group/bus routing, more flexible sends/returns, instrument/audio/MIDI racks, channel I/O, latency reporting/compensation and explicit cycle handling. Preserve deterministic routing in live and offline paths. |
| Project capacity and resource use | 50 tracks, 8 scenes / 400 slots, 2,048 regions, 128 shared voices, 64 assets; Arrangement source freeze, bounded streamed pages, 2 GiB imports and 8 GiB source-frame equivalent capacity | Replace early structural limits where feasible with scalable project data, bounded active resources, streaming and freezing. Publish measured active-playback capacity separately from stored track/layer count. No invented promise of unlimited simultaneous DSP on the phone. |
| Precision, metering and delivery | Native 44.1–192 kHz offline rendering, float processing/export, dithered PCM24 delivery, sample-peak limiter | True-peak/loudness metering and limiting where specified, selectable offline quality, high-quality conversion, stems and flexible output formats/rates. Check phase, timing, headroom and dither across repeated edits/exports. Higher rate/bit-depth labels alone do not establish higher fidelity. |
| Browser and content management | Partial: persistent 20,000-sound library, Rift/WAV ZIP installation, search/categories/favorites, previews, editable assignments and portable projects | Search/tags, preview workflows, preset/device/rack assets, missing-media recovery, collect/relink, user templates and scalable content indexing. Keep original recordings recoverable and exportable. |
| Extension ecosystem | No external plug-in host or custom-device authoring environment | Investigate a native device/extension architecture, custom-device authoring and third-party integration. Track VST/AU, Max-device and Live-project compatibility as separate unresolved requirements; a native extension SDK cannot be presented as proof that those formats work. Record feasibility and any required adaptations explicitly. |
| Analysis and intelligent tools | Local pitch/spectrum analysis; bounded Astra commands and optional Gemini requests | Broader musical analysis, audio-to-MIDI and stem-separation workflows where feasible, richer safe engine commands, useful project context and preview/Undo. Keep local tools usable without an AI account; network operations remain explicit. |
| Interchange and collaboration | Portable native projects, WAV export and Android file sharing | Stems, MIDI interchange, robust imports, external-device synchronization and a documented interchange strategy. Third-party service/project compatibility needs its own demonstrated support rather than an assumed checkbox. |
| Reliability and usability | Three-generation autosave/recovery, portable saves, Undo with asset-bank restoration, performance reports, tested nested Back and rotation | Transactional recovery, crash-safe assets, migrations, large-project undo strategy, thermal/storage-pressure handling, accessible controls, tutorials and a polished phone/tablet workflow. |

## Quality gates for every capability

1. Describe the exact supported user workflow and its limits before calling it complete.
2. Implement the whole path: editor/control, engine, project persistence, undo/recovery where relevant, and export.
3. Keep heavy processing, memory ownership changes and I/O out of the real-time callback. Measure the actual callback and detect overloads rather than silently degrading saved audio.
4. Use objective fixtures appropriate to the change and listening evaluation where the claim concerns perceived sound. Track limitations such as artifacts from extreme time/pitch changes.
5. Run relevant native, Kotlin and Android regression checks. Retain reproducible test inputs and identify the exact tested APK/source revision.
6. Treat emulator functionality and physical Xperia audio/performance results as separate evidence. Publish measured operating profiles instead of making desktop-equivalent performance promises.
7. Keep a capability marked partial until its missing behavior is implemented and verified. A release milestone never silently removes outstanding scope.

## Reference sources

Compared on 2026-11-15; links describe the benchmark, not Astra's current implementation.

- [Live edition comparison](https://www.ableton.com/en/live/compare-editions/)
- [Live 12 reference manual](https://www.ableton.com/en/live-manual/12/toc/)
- [Audio clips and warping](https://www.ableton.com/en/live-manual/12/audio-clips-tempo-and-warping/)
- [Comping](https://www.ableton.com/en/live-manual/12/comping/)
- [Automation](https://www.ableton.com/en/live-manual/12/automation/)
- [MPE editing](https://www.ableton.com/en/live-manual/12/editing-mpe/)
