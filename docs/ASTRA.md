# Astra Phase 5

Version 0.5.0-phase5, Android 14+, green/black. Astra is an assistant panel inside the actual DAW. Generated edits affect the same MIDI sequencer, synth, mixer, effects and WAV export as manual edits. It is not a separate mockup or an audio-generation service.

## Use on your phone

1. Install over Phase 4. Tap **Astra** next to File.
2. Choose an example, type a request or tap **Speak**. Speak opens the installed Android recognition activity, asks it to prefer offline recognition and places the words in the text box for review. Availability/offline behavior depend on that app; keyboard dictation also works. Recognition may interrupt audio focus. Restart audio when ready.
3. **Offline preview** understands the supported grammar below. It has no network dependency. **Ask Gemini** uses the Gemini API after you add a key in settings.
4. Read the summary and exact numbered edits. **Apply edits** commits the batch; one **Undo** restores the entire previous project state. If you changed anything since previewing, generate a fresh proposal. Nothing is applied automatically, including audio feedback.
5. Launch a generated clip in its indicated Session slot, or ask to place it in Arrangement. Applying a proposal does not start playback.

## Gemini without paid setup

The selected model is `gemini-3.5-flash-lite`. Google's official model/pricing pages, checked November 15, 2026, describe audio input, structured text output and free-tier input/output. Availability, quotas, eligible regions and terms remain Google's. Astra does not claim unlimited or permanently free access.

For direct requests, tap **Gemini settings → Get free-tier key** to open Google AI Studio. Use your own project **without Cloud Billing**; Google's docs say this is the free tier without a payment method. Enter the key only into Astra's masked field. The app cannot verify whether a key belongs to a paid project. If billing is enabled there, requests can cost money. Astra never creates a billing account, upgrades a tier, retries repeatedly or switches to another model when quota is exhausted.

If you prefer the Gemini app/website, type your request and tap **Copy for Gemini**. Paste the copied prompt there, optionally attach your audio there, and ask for the JSON response. Return to **Command JSON**, paste it and tap **Validate JSON**. Then review and apply. This manual route needs no API key in Astra and remains subject to that Gemini service's own limits. Never paste credentials into a chat.

No live Gemini request was performed during this build: no user Google credential was available. REST serialization, response parsing, bounded audio encoding, validation and error/cancellation paths have local tests; real model/schema availability still needs a connected phone session.

## Listening

**Attach audio** uses Android's document picker. Supported advertised formats: WAV, MP3, FLAC, Ogg/Opus, AAC, M4A, AIFF and WebM. The attachment is read locally and displayed by name/size. **Ask Gemini** sends it with your text and project summary. Removing it prevents subsequent requests from including it. Choosing a file alone does not upload it.

Limit: 8 MiB encoded source bytes per request; MP3 is convenient for longer tracks. The limit is about request size and phone memory, not a 60-second DAW import cap. There is no continuous live microphone/engine stream or automatic whole-project render/upload. To analyze your arrangement, export it or attach an appropriate audio render. For a large WAV, use a smaller excerpt or externally encoded MP3. Waveform editing/export continue to use the full original DAW project audio.

Gemini provides qualitative musical feedback and may propose edits to supported controls. It is not a calibrated loudness, key, tempo or stereo-phase meter. A mixed track does not expose the isolated stems. The prompt directs it to disclose uncertainty and not pretend to have heard missing audio.

Requests contain text plus project name, signature/BPM, selected clip notes, per-track names/current preset names/volume/pan/mute/solo/sidechain, occupied scene numbers and arrangement end. They do not include every clip, every patch value, source filenames, private asset paths or all project audio. Attached audio is an explicit addition. Google states free-tier content may be used to improve its products. Feedback history and attachments are held for the current service session, not saved in project files.

## Offline examples

- `Write a syncopated 145 BPM Riddim quarter-note bassline in F minor`
- `Create a rolling 138 BPM Psytrance bassline in F minor`
- `Create a trance melody in Bb minor on track 4 in scene 2 for 8 bars`
- `Load a metallic FM growl on Track 2 and map LFO 1 to the Comb Filter cutoff`
- `Route Track 1 Kick to the Sidechain input of the Sub Bass on Track 3`
- `Set tempo to 128 BPM`

The offline parser is an explicit grammar with original deterministic phrase algorithms, not an offline language model. Unknown wording and extra operations are rejected. Defaults: selected track, first free scene (selected scene if empty), F minor, four signature-aware bars, seed zero; generated phrases keep the current track synth. Root/minor/major names, track/scene and bar count can be specified. Pattern styles: riddim/dubstep, psytrance, trance, house, kick, snare and hats/hi-hat. Drum patterns are MIDI only and require a suitable synth patch. Factory aliases map metallic FM growl to FM Alloy, riddim bass to Liquid Riddim, trance lead/supersaw to Trance Prism, sub bass to Sub Pressure and donk to FM Donk. More flexible wording and custom melodic phrases use Gemini or JSON.

## Command contract

Envelope: `{"version":1,"summary":"Reviewable explanation","commands":[...]}`. A feedback-only answer has an empty commands array. Unknown fields/operations, duplicate keys, type coercion, nonfinite or out-of-range values, trailing text, deeply nested JSON and oversized batches are rejected. One complete `json` code fence is accepted for manual paste. Track/scene/slot numbers below are **one based**. Beat positions start at zero and are quarter notes.

| Operation | Fields and behavior |
| --- | --- |
| `tempo` | `bpm` 30–300, changes the whole-project tempo |
| `pattern` | `track`, `scene`, `style`; optional `root` (F), `scale` (minor), `bars` (4, range1–16), `seed` (0–1,000,000), `name`, `replace` (false). Original deterministic MIDI phrases |
| `midi_clip` | `track`, `scene`, `length`, `notes`; optional `name`, `root`, `scale`, `replace`. Each note: `beat`, `length`, `pitch` 0–127, optional `velocity` .001–1. Max256 notes; every note fits the clip |
| `preset` | `track`, `preset`: exact factory name, case insensitive. Replaces the complete track synth patch |
| `synth` | `track`, `parameter`, `value`. Parameter uses a generated `SynthSchema.keys` name such as `Filter1Cutoff` or `FmIndex`; its native range/integer contract applies |
| `lfo` | `track`, `lfo`1–4, `beats` .0625–16; optional `shape` sine/down/up/square, `retrigger` true. Tempo-sync enabled; beats are quarter notes per cycle |
| `modulation` | `track`, `source`, `destination`, `amount`−1..1; optional `slot`1–16. Reuses matching route or first free slot, otherwise requires explicit replacement |
| `sidechain` | `sourceTrack`, `targetTrack`, different tracks; optional `threshold`−60..0dB, `ratio`1..20, `attack`.1..100ms, `release`5..2000ms, `mix`0..1. Enables the target compressor; defaults−28dB/6:1/1ms/100ms/100% |
| `mix` | `track` plus at least one of `volume`0..2 linear, `pan`−1..1, `mute`/`solo` boolean, `sendA`/`sendB`0..1 |
| `effect` | `track`, generated `FxSchema.keys` `parameter`, `value`. Cross-band thresholds are validated. Use `sidechain` rather than a raw `ScSource` |
| `place` | `track`, `scene`, `beat`: places an existing clip in Arrangement; cannot overlap another region on that track |

Modulation sources: `off`, `lfo1`–`lfo4`, `env1`–`env3`, `velocity`, `key`. Targets: `osc_a_position`, `osc_b_position`, `fm_amount`, `pitch`, `filter1_cutoff`, `filter2_cutoff`, `amplitude`, `osc_a_warp`, `osc_b_warp`, `filter1_resonance`, `filter2_resonance`, `fm_index`, `filter1_drive`, `filter2_drive`, `osc_a_level`, `osc_b_level`.

Bounds: 256 KiB command JSON, depth12, 32 commands, existing project capacities. Occupied MIDI clips require explicit `replace:true`; audio clips cannot be replaced by Astra. Replacing a source changes all arrangement regions referring to it; the preview says so. All validation happens before project adoption. Every edit in an accepted proposal is one history entry, although normal DSP parameter publication still occurs through existing mailbox/queue boundaries rather than one cross-device sample transaction.

Example (choose an empty track6/scene1):

```json
{
  "version": 1,
  "summary": "Create a four-bar F minor riddim phrase with a metallic FM patch.",
  "commands": [
    {"op":"tempo","bpm":145},
    {"op":"preset","track":6,"preset":"FM Alloy"},
    {"op":"pattern","track":6,"scene":1,"style":"riddim","root":"F","scale":"minor","bars":4,"seed":7},
    {"op":"place","track":6,"scene":1,"beat":0}
  ]
}
```

## Security and remaining limits

The public APK contains no shared Gemini secret. Individual keys use Android Keystore AES-GCM and AtomicFile under noBackupFilesDir; Android backup is disabled. They are never saved in Compose state bundles, projects, source control or logs. A rooted/compromised device remains outside that protection. A future owner-funded public service should use a protected backend or Firebase instead of shipping an owner key.

HTTPS endpoint and model are fixed. Redirects are refused. Network timeout/cancellation and response/attachment sizes are bounded; errors do not print provider response bodies or credentials. No arbitrary URLs, filesystem commands, code execution, native addresses or file operations are available to generated commands. Late/cancelled results are discarded. Gemini errors never trigger an automatic paid-model fallback. Google model responses can be wrong; only validated, previewed musical edits enter the existing JNI/control path.

Not included: live Gemini voice conversation, on-device neural LLM, autonomous background mixing, microphone recording, MIDI expression/controller mappings, tempo maps, arbitrary graph routing, new sampler/warp DSP or accurate music transcription. Phase4 editor/DSP limits still apply. Physical Xperia input, speech recognition, latency, thermal limits and live provider behavior need device testing.

## Primary references

- [Gemini free-tier pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini 3.5 Flash-Lite capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)
- [Gemini audio understanding](https://ai.google.dev/gemini-api/docs/audio)
- [GenerateContent REST contract](https://ai.google.dev/api/generate-content)
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output)
- [API key security](https://ai.google.dev/gemini-api/docs/api-key)
- [Firebase/Gemini billing and free projects](https://firebase.google.com/docs/ai-logic/pricing)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)
