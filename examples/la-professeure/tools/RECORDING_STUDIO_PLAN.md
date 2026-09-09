# Dialogue recording studio

The tool owns recording and publication. Bitwig (or another audio application)
owns effects; saving its project is not mistaken for rendering audio.

## Live effects recording (current implementation step)

- Add an explicit **Direct + FX** record button beside the existing dry/pass controls.
- Native source duplex device captures the selected microphone, retains dry PCM,
  and sends only BlackHole 1/2. A separate native capture device retains only
  BlackHole 3/4. No JVM, allocation or file writes in either audio callback.
- For repeatable virtual-input QA, selecting BlackHole as the source reads only
  5/6, never its send or return buses. Label that routing in the UI.
- Stop silences the send, then captures the configured one-second effects tail.
  Both buffers are bounded to 60 seconds. Save paired immutable dry/wet takes;
  only valid processed audio is publishable. Keep the original dry recording.
- Test channel isolation, stop/tail, bounds and paired WAV encoding natively;
  test actual Bitwig routing with a quiet virtual input and interact with the
  live UI. Publish the returned take into the running game, then remove the
  promoted QA copy from shipping resources while retaining the take history.

## Recording workstation upgrades (requested 1–6)

- [x] Confidence-gated dry/return alignment, non-destructive compensated take;
  retain the originals and report uncertainty instead of trimming blindly.
- [x] Countdown/cancel, selectable tail, elapsed time, input/return meters,
  latched clipping warning and waveform.
- [x] Opt-in headphone return monitoring with explicit device selection,
  low starting volume, disallowed loopback/speaker routes and automatic stop.
- [x] Named takes, A/B comparison, trim-to-new-file and preferred selection.
- [x] Append-only PCM recovery journals with durable progress metadata, recover
  interrupted takes on reopen; no disk work in audio callbacks.
- [x] Persisted named routing presets, resolve device names after re-enumeration,
  fail visibly on missing/ambiguous devices; never change global defaults.
- [x] Native/host regression tests, journal interruption recovery, visual UI
  interaction and same-JVM publication QA. Physical headphones may be unavailable;
  refuse an unsafe substitute and report that hardware limit explicitly.

## Layout and workflow

- Native Aguafria Zig code lives in `tools/src/la_professeure/tools/`, never in
  the shipping game's entry point. Reuse the project's miniaudio/Vulkan support.
- Show the exact Markdown passage, stable recording ID, matching `[LP:id]`
  Bitwig track name, take state and routing status in a development visualizer.
- Keep the full script scrollable on the left, including choices and unvoiced
  narration. Click any passage to focus its full text on the right; long focused
  passages scroll independently. Navigation must never start microphone capture.
- Reuse the game's Vulkan renderer and loaded serif atlas. Both game and tools
  share a native Flecs world with per-passage runtime state, keyed by stable IDs.
- Explicitly select a microphone and record a dry take. No automatic microphone
  activation. Alternatively load an existing WAV for repeatable effects passes.
- Send dry audio to BlackHole channels 1–2. Bitwig reads 1–2, applies its effects,
  and returns processed audio on 3–4. The tool captures only 3–4 and never sends
  that return back into the effects input. Other applications can use this route.
- Stop recording before WAV encoding. No disk I/O, allocation or JVM work in the
  audio callback. Bounded buffers, explicit overflow/error status, device shutdown
  before freeing or replacing buffers.
- Store immutable dry/processed takes per passage. Publish only a completed,
  validated processed take; never overwrite source recordings. Game playback
  must select by passage ID, not replace the background music with dialogue.

## Verification gates

- [x] Native device enumeration and explicit selection.
- [x] Deterministic send/capture routing and bounded recording tests.
- [x] Actual BlackHole → Bitwig effects → BlackHole return capture.
- [x] Full-script/focused-passage visualizer, visually inspected in live Vulkan.
- [x] Persistent take history, preview and selection.
- [x] Processed take publication and matching in-game voice playback, hot reload.
- [x] Document setup, latency/tail behavior and tested limits.
- [x] Final real mouse/keyboard visual acceptance of the expanded controls.

2026-09-09 verification: native recorder tests and fresh Flecs lifecycle tests
pass (2 tests / 18 assertions). Device enumeration found BlackHole 16ch, built-in
microphone and aggregate input. A real 144,000-frame BlackHole duplex pass wrote
a stereo WAV; no Bitwig effect was configured for that test, so it is not evidence
of a processed return. No microphone was recorded. Full script, focus selection
and scrolling were verified with live-window screenshots; input QA used the same
native hit-testing via `click-at!` because synthetic macOS button events did not
reach GLFW reliably. The game JVM stayed running throughout.

Existing hardware on this Mac: BlackHole 16ch, built-in microphone/speakers and
an aggregate device. Do not change global audio defaults or existing Bitwig
projects. Real routing QA uses an isolated project and a quiet test signal.

## Actual effects-return QA — 2026-09-09

In the isolated **La Professeure FX Return QA** Bitwig project, Audio 2 reads
BlackHole stereo 1/2 and writes stereo 3/4. Its Tool device has -6 dB gain and
both channel polarities inverted. The native studio's **Effets / retour** button
was exercised through its `click-at!` UI hit-testing, not by calling the recorder
directly. Ordinary synthetic macOS clicks did not reliably reach GLFW.

The existing quiet `lesson.wav` fixture was imported as a dry QA take; this is
not a voice recording. The same live game JVM captured and saved the processed
return, with no microphone capture or global audio-device change.

- Dry: `build/recording/voice-173791faf357254b/520aa91c-90b1-4650-9b77-c336f37e87ac-dry.wav`
- Return: `build/recording/voice-173791faf357254b/03fb0654-0ba2-4915-9093-be6aad539ace-wet.wav`
- Both: stereo, 48,000 Hz, float32 WAV. Dry 96,000 frames (2 s); return
  144,000 frames (3 s, including the configured 1 s tail).
- Return peak: 0.00599508; RMS: 0.001606996; no clipped samples.
- Both channels align at 3,400 samples / 70.833 ms; correlation **-0.99999980**;
  fitted total-route gain **-0.06308255**, approximately **-24 dB**.
  Inverted polarity and nonzero captured samples demonstrate an actual effects
  return, not a copied dry WAV. Total-route gain includes track/device levels;
  it is not an isolated measurement of the Tool's -6 dB setting.

Follow-up: bypass capture `d03b05e2-d064-40f4-831b-667dd3bf937d-wet.wav`
had positive correlation 0.99999980 and gain 0.12586615. The enabled/bypassed
gain ratio is **-5.999994 dB**, matching Tool's -6 dB setting. Bitwig's original
**Aggregate Device**, 44.1 kHz, auto 128 samples, was restored and visually
confirmed; its audio engine restart was applied. Both QA tracks were disarmed.
Global macOS defaults were not changed.

Publication QA: the native studio button hit-testing published the processed
take to its passage ID. Entering “Se retourner” selected voiced node 3 and
incremented voice revision to 1. Selecting/publishing the other processed take
incremented voice revision to 2 in the same JVM, without restarting the game.
Ambience revision stayed at 1. The take index also survived a tool/JVM restart.
The first audio-binding expansion required one controlled JVM restart because
the native engine state compatibility guard rejected a new schema; the subsequent
publication/replacement tests used the same new JVM (port 49339).

Native tests passed (3 tests / 25 assertions), including rejection tests for
silent, clipped and NaN input. ReleaseFast compilation passed. An earlier visual
attempt was blocked by macOS locking; those captures were stale, not evidence.
The subsequent unlocked acceptance test below supersedes that blocked attempt. Automatic
latency trimming and a real microphone/voice acting session are not tested.

The promoted QA tone was moved out of `resources/voices/` (and the standalone
resource copy) into `build/recording-qa/published-return-20260909.wav` and
`standalone-return-20260909.wav`. Original immutable takes remain in the take
history; no test tone ships as recorded dialogue. No user recording was removed.
The game remains running with the recording workspace attached. Ordinary source
reevaluation of the playback changes was also tested after the controlled
restart: preview succeeded and voice revision advanced from 2 to 3.

Final unlocked visual acceptance used actual macOS CGEvent mouse/keyboard input
(not `click-at!`) on the same JVM/window: script scrolling, passage selection,
preview, Record/Stop on the explicitly displayed BlackHole input, previous-take
selection, Publish, and F1 returning to the real dialogue. The 62,622-frame dry
capture was silent and correctly retained with a publication-refusal warning.
The valid processed take published successfully; F1 resumed voiced node 3,
voice revision 5, ambience still revision 1, no active recording session.
Screenshots: `build/recording-qa/studio-interaction.png`,
`studio-record-stop.png`, `studio-published-ui.png`, `studio-passage-focus.png`,
and `game-voice-ui.png`.

Visual QA caught an empty choice hover rectangle in a branch without choices.
The frame function was fixed and re-evaluated alone; the next live screenshot
confirmed its removal without restarting. Passage focus now clears the previous
passage's operation message, and unvoiced context no longer shows an empty LP ID.

Final ReleaseFast rebuild succeeded in 5.897 s; the native executable is
2,248,176 bytes (resources are separate). It launched and rendered the Markdown
dialogue correctly (`build/recording-qa/standalone-dialogue.png`). macOS locked
before its final mouse-interaction check: `standalone-interaction.png` is not
evidence of a successful click. The extra standalone process was terminated;
the original development JVM remains running. The final status-message/empty-ID
changes were evaluated live but still need a fresh unlocked visual check.

The second publication QA copies were moved to
`build/recording-qa/published-return-ui-final.wav` and
`standalone-return-ui-final.wav`. The immutable take history, including the
silent UI capture, is preserved. The original dry test fixture was restored as
the passage's processing input and its published flag cleared. No QA tone remains
in the game or standalone dialogue resources.

### Remaining visual checks completed — 2026-09-09

With macOS unlocked, the same development JVM (PID 43463, nREPL 49339)
accepted an actual mouse click on the unvoiced context passage. Its status
changed to the context-specific message and no empty LP ID appeared:
`build/recording-qa/studio-polish-verified.png`.

The existing ReleaseFast executable was launched again without rebuilding.
An actual mouse click selected **Se retourner**, rendering **LA MANGUE** and
the corresponding Markdown passage. Backspace returned to the parent and the
`2` key selected **Inspecter la voiture**, rendering its narration and nested
choices. Screenshots: `standalone-choice-verified.png` and
`standalone-keyboard-verified.png` in the same QA directory. These successful
unlocked checks supersede the preceding blocked attempts. The temporary
standalone window was closed through its macOS close button; the original
development game remains running. No additional QA audio was published.

### Live effects recording verified — 2026-09-09

Implemented **Direct + FX**: source duplex capture/send and independent return
capture, both native. Stop requests silence, captures a one-second tail, then
writes paired dry/wet WAVs with the same UUID stem. Encoding failure retains the
session/buffers and blocks new recording until recovery; closing the workspace
with an active take is refused. Microphone capture is explicit, never automatic.

All work was loaded in the existing JVM, PID 43463 / nREPL 49339. The recorder
was stopped during code reevaluation. A compile-time numeric type error in the
new test was fixed and retried without restarting. Final native tests:
**4 tests, 35 assertions, zero failures/errors**, covering stereo microphone
mapping, isolated virtual input/send/return buses, null buffers, bounds, stop
silence, tail capture, paired WAV encoding and existing validation/Flecs tests.

Hardware acceptance used `tools/test/la_professeure/live_signal.clj` as an
explicit quiet virtual source on BlackHole 5/6, not a prerecorded dry pass and
not microphone/room capture. The **actual macOS mouse** selected a passage,
pressed Direct + FX, Stop, take navigation and Publish. The recorder read 5/6,
retained the dry signal, sent 1/2 through Bitwig Audio 2's Tool, and retained 3/4.
No Bitwig recording, export or Save was used.

Paired take stems under `build/recording/voice-173791faf357254b/`:

- Effect enabled: `2d122629-55a2-41d9-83b1-eaa63da27196`.
  Dry 729,855 frames; wet 781,824 frames, stereo float32, 48 kHz.
- Bypassed: `d10c0730-e768-43e3-9037-4018ff0f738b`.
  Dry 154,791 frames; wet 205,878 frames.
- Measured effect/bypass ratio: **-6.000007 dB** in both channels.
  Correlation: approximately **-0.9999923** enabled and **+0.9999923** bypassed.
  All samples finite; no clipping. `tools/test/compare_live.py` reproduces this
  comparison. Its periodic-signal alignment is not a latency calibration.

Screenshots in `build/recording-qa/`: `live-fx-recording.png`,
`live-fx-saved.png`, `live-fx-game-publication.png`. UI publication and F1/choice
navigation played the live-captured effect take (voice revision 6), then replacing
it with the bypass take hot-reloaded that same game voice (revision 7). No JVM
restart. The promoted QA tone was moved to `live-fx-published-return.wav`; paired
immutable source takes remain in history, and no tone ships as dialogue.

The QA signal generator is stopped; both recorder devices are closed. Bitwig's
original Aggregate Device / 44.1 kHz / auto 128-sample setup was restored and
visually confirmed, and the QA track disarmed with Tool re-enabled. Global audio
defaults and unrelated projects were not changed. For another effects session,
select BlackHole and enable the QA/effects track's input monitoring again.

At that milestone, automatic alignment and configurable tails were still absent;
the workstation upgrade below supersedes those limits. Hardware QA used a virtual
source, not a spoken microphone session. Speaker monitoring is intentionally refused.

## Workstation upgrade acceptance — 2026-09-09

Same JVM (PID 43463, nREPL 49339), no restart. Tools remain under `tools/src`;
game/standalone imports were not changed. Native callbacks do no allocation,
filesystem I/O, or JVM work. Offline alignment/editing and durable metadata run
on the development worker. The native journal copies only the acquire-published
immutable PCM prefix, then flushes/fsyncs before committing its frame count.

Automated suites: 5 native tests / 46 assertions plus 3 host tests / 21 assertions.
Coverage includes inverted 731-frame delay recovery, periodic ambiguity refusal,
silence/NaN/short returns, no-overwrite trimming, immutable originals, committed
prefix recovery, channel isolation, monitoring gain clamp, clipping latch, null
inputs, invalid devices, and exact/missing/ambiguous device-name resolution.

Actual mouse/keyboard actions verified the three tabs, accented `Essai été` name,
countdown cancellation without opening audio, A/B alternation, 5–95% trimming to
a new file, preferred take, profile save/cycle/reconnect, and rejection of BlackHole
as headphones. Screenshots are under `build/recording-qa/workstation-*.png`.

A fresh Direct + FX take through Bitwig, stem
`ce0d76a0-c7cd-4105-b7ec-40bc9f6529b8`, captured 24.30094 s dry and 27.38363 s wet
with a three-second selected tail. During capture its manifest committed 1,140,426
dry and 1,143,954 wet frames. The processed WAV passed native validation.
The two-tone fixture's correlation was high but ambiguous (distinctness 0.00000484),
so automatic alignment correctly retained the original without trimming. The
aperiodic delayed/inverted regression independently verifies accepted compensation.

Recovery via the UI reconstructed 4,800 committed frames from a journal containing
6,000 frames; the extra uncommitted prefix was not published. A trimmed processed
take played in the actual game after F1/choice navigation (voice revision 10).
Replacing it from the tool hot-reloaded the same game passage to revision 11.
The promoted QA tone was moved out of shipping resources; immutable takes remain.

QA caught and fixed a native UTF-8 numeric-inference compile error and a display
throttle bug that could skip elapsed-time refreshes. Screenshots were checked only
after the updated native drawing declaration was compiled and published.

No physical headphone endpoint is connected: monitor channel isolation, clipping,
gain cap, and rejection paths are tested, but audible headphone latency/feedback
acceptance remains open. A real spoken microphone session also remains open.
Recovery is tested against an interrupted-journal fixture, not a power-loss test.
Bitwig's original Aggregate Device / 44.1 kHz setup was restored; its QA track is
disarmed and the virtual signal generator stopped. No user recordings were deleted.
# DAW workspace pass

- [x] Persistent transport, dialogue track headers, seconds ruler and real take clips.
- [x] Zoom/pan, audio-engine playhead and click-to-audition; no invented waveform data.
- [x] Full Markdown script view plus a focused text/take editor; retain non-destructive edits.
- [x] Persistent source / external FX send / return / headphone inspector.
- [x] Verify actual native UI interaction and screenshots, then rerun recording regressions.

The arrangement is a dialogue-take browser: each row starts at its own zero, not a
simultaneous multitrack composition. Bitwig remains an external effects host, not
an embedded/licensed audio engine. Our application owns recordings and publication.

DAW-layout QA (2026-09-09): inspected Bitwig's actual Arrange and Mix views and
restored Arrange without changing its project/audio settings. Rebuilt the native
workspace in the existing JVM/nREPL 49339. Actual macOS clicks selected Pistes,
auditioned the clip at 5.0 s (PCM cursor 5.386 s after dispatch), stopped playback,
zoomed 30 → 15 s, and dragged trim-in to 9%. REC + FX followed by Stop cancelled
the countdown with no remaining armed/recording session. Readable fractional
ruler labels were checked in a subsequent screenshot. Script view and full
focused text were visually inspected. Native/offline regression: 9 tests,
80 assertions, zero failures/errors. No spoken microphone or physical-headphone
session was claimed for this layout pass; prior paired-Bitwig recording evidence
is below. The workspace is left open, stopped, with monitoring disabled.

Local screenshots (ignored): `build/recording-qa/daw-first.png` (Script),
`daw-tracks.png`, `daw-playhead.png`, `daw-zoom-trim.png`, `daw-final.png`.
