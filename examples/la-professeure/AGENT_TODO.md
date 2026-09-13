# La Professeure — active implementation and QA

This is the working checklist, not a completion claim. Keep game and studio in
separate windows. Preserve recordings, Markdown IDs and existing user edits.
The wider DAW roadmap is in `tools/DAW_IMPLEMENTATION_PLAN.md`.

## Short status — what is actually left

The dialogue-recording workflow is implemented; final platform/hardware acceptance
is not complete. Do not restart the whole roadmap or repeat completed tests.

Final physical acceptance needs a person/device, not further feature development:

1. Record a short spoken take with the selected microphone, Stop, then Listen to
   take; confirm intelligible playback and no unwanted game audio while in Studio.
2. Use the actual trackpad to scroll and pinch the Edit timeline; confirm smooth,
   pointer-anchored interaction. Native event/zoom math is already verified.
3. With a physical audio interface/headphones available, disconnect/reconnect
   during monitoring or a disposable take; confirm visible interruption and
   recovery. Current output inventory has speakers and virtual/aggregate devices,
   not a physical detachable headphone/interface endpoint.

No automated result should be relabelled as these physical checks. The same
remaining acceptance boundary has been reported across consecutive goal passes;
once the final exact-source regression terminates, pause automated pursuit for
that external confirmation instead of adding features or repeating passing probes.

Final exact-source regression completed successfully: **128 tests / 2,848
assertions, zero failures/errors**, `/tmp/professeure-final-acceptance-regression.log`.
This includes the final native overlay-sizing change. The app remains running;
automated pursuit is blocked only on the physical acceptance above, not a build,
login or failing-test problem. Resume with physical-test results or a concrete
newly observed defect. Do not claim the full goal achieved without that evidence.

- Verified: Dry/FX capture, saved-take playback signal, cancellation, publication
  and game hot reload, separate windows, native mouse/keyboard interaction and
  current Vulkan-rendered layout. Latest isolated suite: 128 tests, 2,848
  assertions, zero failures/errors.
- Still unverified: physical pinch delivery, real microphone/headphone quality,
  audible speaker playback and physical device unplug/reconnection.
- IME marked-text presentation and candidate anchoring are now implemented in a
  small AppKit composition view. Actual input-method/compositor acceptance and
  general shaping of the committed Vulkan draft are not established by the probe.
- Latest audio state: selected MacBook Pro Speakers became unmuted at volume 13
  during the final visual check, without an agent audio-setting change. Native
  playback reports signal; listener confirmation and physical-device checks
  remain distinct from that evidence. Keep the user's current settings unchanged.
- Full arranger/MIDI/plugin parity is a separate expansion, not a reason to keep
  rebuilding the dialogue recorder before handing it over.

Fresh status inspection: worker running with no new failures (historical count
193), no recording and no current alert. Inspected actual Vulkan frame
`/tmp/professeure-status-studio.png`; the output-mute warning is visible and the
selected passage, waveform and record/audition controls are readable. System mute
and recordings were not changed.

## Current verification strategy — native interaction through nREPL

The goal is not complete, but desktop login is not a blocker for native QA.
Per the user's September 12 direction, inject clicks and other supported events
through Aguafria into the application's production hit-testing path; inspect
actual Vulkan readback and native audio/state evidence. Keep the existing JVM.
Use OS input only for OS-specific delivery, focus and composition checks, with
the existing foreground guard. Native injected clicks are not physical mouse
acceptance, and cached WindowServer images are not fresh renderer evidence.

Earlier sleep/login interruptions blocked OS-input attempts, not this workflow.
The resumed pass completed Record -> PREPARING -> capture -> Stop -> saved-take
playback through native button injection. Actual-window video:
`/tmp/professeure-preparing-native-events.mp4` (615 frames, 10.693 seconds).
Inspected frame `/tmp/professeure-preparing-visible.png` shows PREPARING and
"Opening audio devices. Stop to cancel." with Stop available. The real BlackHole
test take contains 1.1 seconds of audio, peak 0.05141075; playback produced
1,323 non-silent output frames. This used virtual test audio, not the microphone.

A second injected Record/Stop probe caught real preparation phase 1 and returned
to idle with no capture started and no take created. Inspected fresh Vulkan
readback `/tmp/professeure-native-gpu-after-cancel.png` shows the cancellation
message and restored controls. The successful capture's labelled QA take remains
in history; all original takes and dry/wet/selected/published references were
preserved (project revision 159). Nothing was published to the game.
Latest full isolated regression: 121 tests / 2,722 assertions, zero failures/errors.

### Follow-up: late preparation cancellation race

The repeatable native test subsequently reproduced a later timing window: Stop
was clicked in phase 1, but the worker had already passed its initial check and
continued opening the device. It saved a 0.17-second unwanted QA take. This
contradicts a general cancellation-complete claim despite the earlier passing
probe. The QA file `1dae35d2-e5f1-42b8-a013-d89c27800a38-dry.wav` is retained and
labelled; original take references were restored (revision 162), nothing published.

- [x] Verify the final start-commit fix. Native callbacks are held during device
  opening: no PCM retention or effects send. A render-thread commit rechecks
  native/API Stop, then changes session ownership, visible capture phase and the
  callback gate together. Cancelled preparation closes devices without a take or
  recovery journal. Test both callback paths and late UI/API Stop in the isolated
  suite, then repeat real native Record/Stop and successful Dry/FX capture.
  Passed: **123 tests / 2,739 assertions**, zero failures/errors, in
  `/tmp/professeure-start-commit-regression.log`. Eight consecutive live native
  Record/Stop probes caught phase 1 and created no take/project change. Inspected
  fresh Vulkan frame `/tmp/professeure-start-commit-cancelled.png`: idle controls,
  original selected waveform and explicit cancellation message.
  Successful Dry capture: 1.13 s, peak 0.05141075, 1,323 non-silent audition frames;
  FX return: 2.13 s including tail, peak 0.00318486, 1,323 non-silent audition
  frames. Files `4a5aba57-75b0-44dc-a56f-b3f485872609-dry.wav` and
  `38c76153-8517-4196-948e-ebee7d7de366-{dry,wet}.wav` remain labelled QA history.
  Original takes and dry/wet/selected/published references all preserved, revision
  167. No game publication. Hot-reloaded into the existing JVM, no restart.
- [x] Make preparation cancellation reproducible without desktop login through
  `live-preparation-cancel-qa!`. It uses native button hit-testing, explicit
  BlackHole input, no forced initialization delay, and guarded cleanup that
  preserves unexpected QA takes and restores original references.

### Native pinch and visible timeline acceptance — September 12

- [x] Implement a macOS Studio-only magnification bridge. AppKit queues bounded
  events in a stable native library; the production render tick consumes them.
  Zoom stays anchored under the pointer, clamps to 2–60 seconds and disables
  Follow. Record/Takes mode, menus, other panes and the game window reject zoom.
  Detach removes the event monitor; repeated attachment is idempotent.
- [x] Hot-reload and verify the real native queue: 30 -> 18.195919 seconds,
  with the pointer's time remaining exactly 25 seconds. Game-window input was
  rejected. A 1,000-event burst through the 64-entry queue yielded 11.036377
  seconds versus the expected 11.036383, with no lost aggregate magnification.
  Original project/take state stayed unchanged (revision 167).
- [x] Replace arbitrary fractional ruler labels with stable readable intervals.
  Inspected actual Vulkan readback `/tmp/professeure-pinch-readable-ruler.png`:
  labels use 2-second intervals after zoom, with unclipped controls and waveform.
- [x] Inspect fresh game and Record-mode Vulkan frames, not cached OS images:
  `/tmp/professeure-game-current-native.png` and
  `/tmp/professeure-studio-current-native.png`. Game text is clean; the visited
  branch is dimmed and the unvisited sibling remains available. Studio controls
  are English; existing user-authored take names remain untouched.
- [x] Isolated regression: **125 tests / 2,802 assertions, zero failures/errors**,
  `/tmp/professeure-pinch-ruler-regression.log`.
- [ ] Physical-finger/AppKit gesture delivery acceptance. Injecting into the same
  native queue verifies downstream behavior, not an actual hardware gesture.

### Keyboard passage navigation follow-up

- [x] Add Up/Down and Page Up/Down navigation to Edit's track list, Record and
  Takes. Keep selection visible, allow held-key repeats, preserve transport and
  track arm state, and block navigation during capture/pending commands or text
  entry. Device menus keep their existing keyboard ownership.
- [x] Live nREPL input through the production key callback selected passage 12,
  scrolled the list to offset 4, left the command queue empty and did not change
  the project. Inspected fresh Vulkan frame
  `/tmp/professeure-keyboard-passage-visible.png`: highlighted last passage,
  matching full script, disabled audition for its absent take. Restored prior
  selection, scroll, text focus and cursor; no recording or publication.
- [x] Bound extreme finite pinch magnification before exponential conversion,
  avoiding f32 overflow while retaining the 2–60 second zoom limits.
- [x] Isolated regression: **126 tests / 2,843 assertions, zero failures/errors**,
  `/tmp/professeure-passage-keyboard-final.log`. Initial QA failed on construction
  of inferred bool/C ABI values, not an app crash; typed native helpers now cover
  those fields and exercise the production key callback. Tests cover all three
  modes, bounds, repeat/release/modifier handling, focus/capture/pending-command
  guards, empty lists, silent selection and preserved playback/arming ownership.

### IME key ownership — September 12

- [x] Inspect actual GLFW Cocoa key/composition ordering and Apple text-input
  contracts. Prevent Enter/Backspace/Delete/arrows/Escape from editing/submitting
  the application draft while macOS owns marked text. Normal committed character
  delivery remains intact. Discard composition on mode/route/take changes or blur.
- [x] Hot-reload the adapter and native handlers into the existing JVM. The
  actual-view `ime-keyboard-qa-contract!` returned 0: six editing keys preserved
  `QA`, committed text arrived exactly once as `QAé`, and only the next Enter
  queued Rename. Draft/history/command state restored in the same render task.
  Project stayed at revision 167; no take rename, audio change or publication.
- [x] Inspect `/tmp/professeure-ime-composition-hint.png` from the real Vulkan
  target: the amber composition underline is visible. Existing persistent audio
  warning covers the normal status hint; do not claim that hint was visible.
  Switching back to Record cleared native marked text; prior UI state restored.
- [x] Add marked-text presentation and candidate anchoring using a dedicated
  public AppKit text-input client for the focused name field. Keep committed
  editing/undo/Rename in Aguafria and the rest of the UI in Vulkan. Unique native
  class names support replacing the content-addressed bridge without swizzling.
  Live contract returned 0: a real first-responder key event inserts once;
  Japanese/French/Chinese marked text and caret geometry match; commit reaches
  the existing character callback once; subsequent Enter alone queues Rename.
  Inspected `/tmp/professeure-ime-native-preedit.png` (native view raster) and
  `/tmp/professeure-after-ime-studio.png` (actual Vulkan frame after restoration).
  Project revision remains 167, worker failures remain 193, no capture/alert.
  Final isolated regression: **128 tests / 2,848 assertions, zero failures/errors**,
  `/tmp/professeure-ime-presentation-regression.log`; process exited successfully.
  No further feature expansion in this pass, per the user's wrap-up direction.
- [x] Inspect actual WindowServer composition without activating/moving Studio
  or Codex. `/tmp/professeure-ime-final-window.png` shows the native marked text
  alongside the Vulkan editor, tightly sized and no longer covering the waveform.
  The first capture exposed an overly wide background; fixed natural text sizing
  and added an exact width check to the native probe. Explicit module recompile
  was required to bind the changed bridge (re-evaluating unchanged definitions
  alone retained the previous bridge). Focused live contract passes after that
  recompile; previous full 128/2,848 suite predates this small sizing adjustment.
  Original UI/draft restored; project revision 167 preserved; Codex remained
  foreground. This closes overlay compositing, not human candidate interaction.
- [ ] Full physical input-method acceptance. The native probe invokes AppKit
  protocol methods, not a human IME. General shaping of committed draft text
  remains a separate limitation; do not extend scope further in this pass.
- [x] Fresh isolated regression: **127 tests / 2,844 assertions, zero
  failures/errors**, `/tmp/professeure-ime-final-regression.log`. The first launch
  read the test file while it was being edited and stopped with a reader error;
  the fresh run used settled sources. Do not edit loaded test sources during
  startup again.

### Selected-output mute diagnosis — September 12

- [x] Distinguish the retained low-level test-take warning from the current
  microphone. Read-only macOS check: output volume 75, input volume 25, output
  muted. Studio selects MacBook Pro Speakers, with no capture or input check.
- [x] Read the selected device's master mute by Core Audio UID on the control
  worker. Renderer/API consume an expiring, device-specific snapshot; unsupported
  or stale states are Unknown. Never alter system mute/volume or open a device.
- [x] Live UI/API both report Muted for the actual speakers. Inspected real Vulkan
  frame `/tmp/professeure-selected-output-muted.png`: amber output selector and
  preserved playback/recording controls. Project remains revision 167. Read-only
  system check still reports the original volume/mute values.
- [x] Resolve the new diagnostic's pointer-nullability and native-clock boundary
  mistakes. Worker failure count stabilized at 193 after correction; prior errors
  remain historical evidence. Cleared only the resulting diagnostic-error banner
  through the public API, not any project/media state.
- [x] Fresh isolated regression: **128 tests / 2,848 assertions, zero
  failures/errors**, `/tmp/professeure-output-mute-final.log`. Covers empty/long/
  unknown UID, cache states, wrong-device rejection, expiry and the existing
  Studio suite. Subsequent live read still has failure count 193, no active
  recording, unchanged revision 167 and selected-output state Muted.
- [ ] Audible hardware playback requires an explicit decision about the current
  mute setting. Asked asynchronously; leave it unchanged without a reply.

## Remaining work — reconciled priority list

Do not treat the old unchecked historical entries as new work or repeat a passing
recording cycle simply because they still mention an earlier lock-screen block.
The core Dry/FX recording, saved-take playback, publication watcher, transport,
resize, scrolling and native cancellation paths have concrete evidence below.

1. Fresh game/Studio layout and dialogue revisit checks passed above. Continue
   only demonstrated visual issues; do not repeatedly rerun completed audio or
   layout checks as a substitute for closing the remaining input/hardware gaps.
2. Input gaps: physical pinch delivery; full platform IME pre-edit presentation
   and general
   complex-script shaping are not covered by the passing Unicode editing tests.
   Keep ordinary wheel/Option-zoom evidence distinct from physical trackpad use.
3. Hardware/audio gaps: real-source microphone gain/acoustics still need checking;
   do not infer a current microphone problem from a saved QA take's warning.
   The selected speakers are currently system-muted (verified above).
   Physical headphone monitoring and unplug/reappearance are not proven
   by the successful BlackHole/owned-device interruption probes.
4. Keep the full DAW expansion roadmap explicit in the implementation plan
   (multitrack clip editing, automation/MIDI/plugins, packaging/accessibility).
   These are not secretly implemented by the dialogue recorder and must not be
   advertised as finished Bitwig parity.

## Completion checkpoint — 2026-09-12

The recording workflow is implemented and undergoing acceptance, not finished.
Verified paths include original-note live sync, capture-time script identity,
saved-take audition, global pause/resume across passage changes, trimmed copies,
and OS-driven resize/minimize/scroll interactions. Older entries below retain
their original dates and limitations; a later pass supersedes only the specific
case it retested, not every related acceptance gate.

An earlier awake-desktop pass resolved that interruption. Kept JVM 64167
running and activated Studio without minimizing or repositioning other apps.
Fresh OS captures now match the native mode. Dry Publish mouse input, final-width
playhead motion, and a consolidated mouse-driven Record/Stop/Listen/Publish path
all passed below. Full isolated regression remains green (117 tests / 1,129
assertions). These close those specific gates, not the entire objective or the
broader DAW roadmap; retain the remaining acceptance items and limitations.

Next, in order:

- [x] Fix preparation/cancellation ordering. A new isolated worker regression
  reproduced eight failures: the UI phase stayed STOPPED during initialization,
  and both native UI Stop and queued API Stop still allowed zero-count-in capture
  to start. PREPARING now precedes initialization; its countdown clock is cleared
  until initialization succeeds. The worker checks pending Stop before starting
  capture, leaving normal command consumption/acknowledgment in place. No added
  count-in delay for successful starts. Isolated suite: **121 tests / 2,720
  assertions, zero failures/errors**, `/tmp/professeure-preparing-after.log`
  (before: `/tmp/professeure-preparing-before.log`). Covers both Stop sources,
  unsuccessful initialization returning to idle, and normal zero-count-in start.
  Hot-reloaded into nREPL 64351 without restarting JVM 64167. Project revision
  157, take entries and selection unchanged; no recording or publication done.
  Actual OS-input acceptance remains open: the guarded driver returned exit 4
  because the desktop was asleep/locked, and sent no input. The window capture
  `/tmp/professeure-startup-checkpoint.png` was inspected for layout only; it is
  not evidence of fresh startup/cancellation behavior on that sleeping desktop.

1. Finish remaining device/error-state acceptance. Transient PREPARING and
   native-button cancellation are now verified above. Virtual loopback and
   owned-device interruption are verified;
   physical microphone/headphone quality and hardware removal are not implied.
2. Continue platform input acceptance: actual IME pre-edit/composition and
   physical trackpad gestures are not established by Unicode boundary tests.
3. Reconcile remaining combined-workflow gates against the evidence below.
   Do not repeat completed minimum/wide layout or cursor-motion checks merely
   because their historical entries still describe an earlier interruption.
   Broader DAW capabilities remain in the roadmap, not a prototype-completion claim.

- [x] Extended the opt-in disposable-JVM interruption probe to dry capture, FX
  return, and FX source/send. All three real BlackHole cases passed using the
  production stop-detection/interrupted-save/remember path. Preserved each stream's
  available frame count, original dialogue fingerprint and Interrupted status;
  repeated polling emitted no duplicate save/event. Reopened the same endpoints
  and received new PCM without restarting the application/device context.
  `/tmp/professeure-fx-interruption-verified.log` records:
  dry 48,000 frames; return-stop wet/dry 51,360 / 49,440 frames; source-stop
  wet/dry 56,160 / 48,000 frames. Both wet files have nonzero waveform peak
  0.00318486. Original dry fixture passes the native audio validator. Artifacts
  are retained under `build/recording-qa/interrupted-{54405c27-ef00-46da-a4c3-07e23ae11eae,
  e1864be6-26fd-4064-a449-96cfaaeafb58,3bf1231a-0207-4d5f-a4f7-0ad62f33d5c5}/`.
  The disposable fixture substitutes project storage, checkpointing and UI
  callbacks; it does not prove durable-journal failure handling, physical device
  removal, OS interaction, or full Bitwig UI setup. It uses no physical mic and
  never redefines the running Studio's callbacks. Initial custom CLI invocation
  omitted nREPL from its test classpath and exited before audio; the corrected
  invocation completed with exit 0. Running project/takes/selection remain exactly
  unchanged at revision 157, no active capture. Cancellation wording now describes
  preparation as well as count-in: "Recording cancelled before capture started."
  Full isolated suite after these changes: **121 tests / 2,722 assertions,
  zero failures/errors**, `/tmp/professeure-fx-interruption-regression.log`.
  Hot reload into the existing app passed; worker remains running and idle.
  The desktop briefly passed the input guard after a bounded display wake, then
  returned to loginwindow before the video helper became ready. The helper
  refused before clicks/capture; no new video or take was made in the live app.

- [x] Fix composed-character name editing, including the native display path.
  The original `e` + U+0301 probe stopped at byte 1 inside the character;
  six new navigation fixtures reproduced 12 failures before the fix.
  Studio now uses stateful utf8proc 2.11.0 extended grapheme boundaries for
  arrows, deletion, mouse hit-testing, and whole-character load/paste truncation
  at 120 bytes. Joined neighbors settle the caret after edits. Display scratch
  is NFC-composed for the Latin atlas without rewriting stored name bytes or
  allocating on the heap. This is not full complex-script shaping or IME support.
  Dependency pinned to `d7bf128df773c2a1a7242eb80e51e91a769fc985`; embedded Zig
  builds the 374 KB generated/ignored archive, activated by Studio only. License
  notices are included under `tools/resources/licenses/`. No game standalone
  dependency or system-installed Unicode library is required.
  Final isolated suite: **120 tests / 2,689 assertions, zero failures/errors**,
  `/tmp/professeure-grapheme-verified.log`. The native byte-offset wrapper passes
  all 766 Unicode 17.0 GraphemeBreakTest vectors in both directions, including
  offsets inside multibyte encodings. Editor tests cover delete/undo, mouse
  boundaries, capacity, joining an existing accent, and equal NFC/NFD glyph widths.
  Intermediate retries failed (source-load syntax, deduplicated framework flags,
  and an optional-pointer cast); these are not acceptance results. Corrected
  argument handling preserves repeated flag pairs and the scratch cast is typed.
  Reloaded into existing nREPL 64351 / JVM 64167 without restart or state migration.
  Native input contract returns 0; library argument count is one. Actual guarded
  OS paste entered `QA café` (decomposed): Backspace produced `QA caf`, Cmd+Z
  restored the original bytes, and Left/Shift+Right selected bytes 6–9 together.
  Inspected `/tmp/professeure-composed-name-selected.png`: one correctly drawn,
  selected `é`. Text-only row selection cleared the draft; returning to Mangue
  restored `Retour aligné`. No Rename/Enter/save was sent. Original project
  revision 157, all take entries, selected passage and published WAV hash remain
  unchanged. Record mode and stopped transport restored. A malformed first QA
  command stalled the client before connecting; stopped only that verified client,
  not the app, and the corrected OS run passed. Fresh 240-frame live sample:
  119.994 submitted FPS, interval p99 8.620 ms/max 8.712 ms, UI-build p95 0.521 ms,
  zero intervals over 16.67 ms. This is live-dev cadence, not GPU timestamp timing.

- [x] September 12 checkpoint rerun: **117 tests / 1,129 assertions, zero
  failures/errors**, isolated JVM (`/tmp/professeure-checkpoint-regression.log`).
  Inspected fresh game and Studio OS captures at
  `/tmp/professeure-checkpoint-game.png` and
  `/tmp/professeure-checkpoint-studio.png`. The game shows the visited door-handle
  choice dimmed beside the unvisited knock choice. Guarded OS key 1 opens the
  already-visited branch, Space reveals its response, and the visible authored
  child is “Lire l’autocollant.” (`/tmp/professeure-checkpoint-nested.png`).
  OS Backspace restores the parent; F1 returns to the separate Studio window.
  Selected take, stored take entries and project revision 157 are unchanged;
  no capture, publication or source edit in this pass. The composed-accent probe
  ran and restored its byte buffer within one render-queue callback, without
  editing a saved name. This pass does not claim new source-watch, physical-audio
  or IME acceptance. One initial read-only status query used the wrong arity;
  corrected to the existing zero-argument API before proceeding.

- [x] Make effects optional for publication, not just recording. Studio's default
  Dry capture previously reached an enabled Publish button that rejected dry
  takes. Publish now accepts selected dry or processed recordings from the
  passage's history, with the same native finite/silence/clipping validation and
  atomic replacement. Isolated real-WAV regressions cover both kinds, silent and
  clipped audio, missing selections, unsafe IDs and unchanged source bytes.
  Full suite: **117 tests / 1,129 assertions, zero failures/errors**
  (`/tmp/professeure-dry-publication.log`). Reloaded into the existing JVM.
  Selected an existing labelled QA dry take and inspected the actual Vulkan
  frame at `/tmp/professeure-dry-publish-ready.png`. Native Publish-button input
  advanced the real game's voice revision 11 → 12; restoring the original WAV
  advanced 12 → 13. Original SHA-256 and all take references are restored; the
  recovery copy remains under `build/recording-qa/`. An initial capture attempt
  raced the selected-take refresh and refused while busy; waiting for idle fixed
  that QA precondition. The guarded OS click was refused, so this is native UI
  plus actual watcher evidence, not a new physical-input acceptance claim.

- [x] Identify why OS capture/input stopped reflecting the live renderer.
  macOS reported main display 1 inactive (`active=0`) and asleep (`asleep=1`);
  foreground PID 165 was `loginwindow`. The render thread and native UI continued
  running, but the desktop was unavailable for current visual/input acceptance.
  Both `tools/test/studio_input.swift` and `tools/test/window_video.swift` now
  preflight active awake displays and the login-window foreground, failing with
  explicit exit status 4 before input/clipboard changes or encoder setup. Tested
  the actual sleeping-desktop path: both refused, paste refusal preserved the
  clipboard change counter, and no video file was created. Video argument usage
  still exits 2. No display wake, login bypass or application restart attempted.
  Final visual/mouse acceptance needs the desktop awake and unlocked; this
  diagnosis does not close the motion or physical Publish gates.

- [x] Record and analyze actual track-playhead motion, not just a still.
  `tools/test/window_video.swift` verifies window ID + owner PID and uses a
  desktop-independent ScreenCaptureKit window filter, with audio/microphone off.
  The first built-in `screencapture -v -l` attempt captured the display instead;
  its guarded click refused another app's window. That failed recording was
  permanently deleted. A first standalone Swift attempt needed AppKit's
  WindowServer initialization; the helper now initializes without activation.
  Wrong-owner capture refuses without creating a file. A successful synchronized
  Studio-only video contains 690 frames; the moving track line appears in 342
  frames (4.60 s), with zero missing/duplicate-line frames or backward steps.
  X advances 490 → 686 physical pixels; largest forward step is 2 px. H.264
  thresholded width is 3–4 px for the nominal 4-px line. Native audio telemetry
  agrees: 203 playing samples, 0 → 4.676 s, no x reversals, pixel-aligned values.
  Evidence: `/tmp/professeure-studio-motion-synced.mp4` and inspected
  `/tmp/professeure-motion-playing.png`. Playback uses the native button path;
  it is not a new OS-click acceptance claim. Video rate isn't a 120-FPS claim.
- [x] Finish the take-editor motion follow-up. Video inspection revealed that
  Edit showed only trim handles while Record had a playback cursor. Both now
  render the same frame-sampled cursor, with a consistent 2-point width and
  bounded right edge. Added a native vertex-geometry regression for both modes.
  First thin-line capture verified movement but the H.264 color detector missed
  three frames; don't infer real rendering dropouts from compressed pixels alone.
  The subsequent width-adjusted video failed in macOS before its first sample;
  no file or acceptance evidence was produced. Repeat the final-width capture.
  Three analyzer tests reject stationary footage and detect missing/duplicate
  lines and reverse motion. All original project/take data remains unchanged.
  Final native regression: **115 tests / 1,095 assertions, zero failures/errors**
  (`/tmp/professeure-playhead-final-retry.log`). An earlier run had two compile
  errors from the new test's untyped runtime integer branch; explicit `:u32`
  expectation fixed it. Final-width Vulkan readback inspected at
  `/tmp/professeure-editor-final-width-gpu.png`: one full-height cursor at physical
  x=564, exactly 4 pixels wide, separate from trim handles. The later 60-Hz
  recording helper stalled without a file; stopped only its verified PID 2387,
  and added a process-local deadline for stalled OS awaits. This is not evidence
  of a game/Studio stall. Restored Record mode, stopped playback, and verified
  project/takes equal the pre-QA snapshot. Final-width OS motion remains open.
  On the next verification pass, OS still capture returned Edit while the live
  native mode query and actual Vulkan attachment both showed Record. CG window
  metadata confirmed window 133843 belongs to the current JVM 64167 at the
  expected bounds. Treat `/tmp/professeure-studio-window-current.png` as stale
  composition evidence, not the current UI. Resolve capture/presentation
  freshness before another motion run; merely getting a PNG or an encoder-start
  callback does not establish that the current window is being observed.
  Resumed with an awake desktop: synchronized ScreenCaptureKit readiness and
  guarded OS Play input using `live-playhead-video-qa!`. Final video
  `/tmp/professeure-editor-synchronized-final-2.mp4` has 618 frames; 247 moving
  frames span 0.825–5.087 s, x=498→1458 px, with zero missing/duplicate lines or
  backwards steps. H.264 thresholded width is 2–4 px; largest forward step 10 px.
  Inspected `/tmp/professeure-editor-final-playing.png`: both track and editor
  cursors visible. Native 120-sample cursor advances 0.377→4.300 s with no
  reversals and 205,065 non-silent output frames by the last sample. Decoder
  rawvideo DTS quantization warnings did not affect decoded frame count or
  strictly increasing source timestamps. This is motion evidence, not an FPS
  benchmark. An earlier unsynchronized clip failed the motion assertion; its
  telemetry probe referenced an absent accessor. Another guarded click refused
  foreground ownership. Neither failed attempt is counted. Final replay stopped,
  Record view restored, take references unchanged. Three analyzer tests pass.

- [x] Close Dry Publish physical-input acceptance. A guarded OS click on Publish
  advanced game voice revision 14→15, and restoration advanced it to 16 with the
  original hash. Original SHA-256 and all take references match the pre-QA state.
  This supersedes only the earlier native-only limitation for Dry Publish.
- [x] Consolidated resumed recording acceptance: guarded OS passage selection,
  Record, Stop, Listen and Publish. A new labelled 220/330 Hz virtual QA source
  passed through the configured FX send/return; dry/wet pair
  `6cd4c398-af34-4329-a8a9-074daef8e840` is retained. The wet take is 2.79 s,
  peak 0.003185, has the captured Mangue fingerprint and Recorded status, and
  audition outputs 9,702 non-silent frames at 0.377 s. Inspected actual window
  capture `/tmp/professeure-resumed-live-recording.png`: correct frozen text,
  RECORDING state, live waveform and input/return meters. The configured return
  is quiet (-50 dBFS); the UI warns, rather than claiming good recording level.
  Signal presence does not itself identify or certify a particular effect.
  Publish advanced the real game watcher 17→18; restoring original bytes advanced
  18→19. Prior routing and selected/dry/wet/published pointers restored; no QA
  tone remains published and the test source is stopped. This is virtual audio,
  not a physical microphone/headphone listening test. Full isolated suite passes
  117 tests / 1,129 assertions in `/tmp/professeure-resumed-acceptance-tests.log`.
- [x] Reinspect current minimum/wide Edit and Record compositions. Actual OS
  captures at 1100×760 and 1400×900 logical points show readable text, visible
  transport, routing, waveform and take controls without overlap. Wide evidence:
  `/tmp/professeure-final-record-wide.png` and
  `/tmp/professeure-final-edit-wide.png`; minimum evidence is the resumed Record
  capture and final Edit motion frame above. Switched modes with guarded OS
  clicks, resized through the normal render-thread window helper, then restored
  1100×760 and Record. This pass verifies composition and mode clicks, not a new
  physical window-edge drag. Existing mouse/trackpad resize acceptance remains
  separately documented. No recorded media or take selection changed by resizing.

- [x] Scope the take-name editor to its selected target. Reproduced a loaded
  empty passage with zero duration but the preceding “Retour aligné” name.
  Switching target now resets the name and ends the old target's name edit;
  empty targets clear it. Unchanged saved-waveform refresh preserves a draft.
  Native reset and isolated host/cache regressions pass in the full suite:
  **111 tests / 1,075 assertions, zero failures/errors**
  (`/tmp/professeure-take-name-ownership.log`). Reloaded into the existing JVM.
  Actual Vulkan attachment capture `/tmp/professeure-empty-take-name-gpu.png`
  shows passage 8, no take, empty name placeholder, and no previous name.
  Normal API selection back to Mangue restored “Retour aligné”. Project revision
  stayed 135; no stored names/takes changed. Restored Record mode and row offset.
  Physical name-edit/switch attempts did not reach Studio; focused-field and
  selected-passage assertions failed. AX focus/hit testing then returned errors,
  and full-desktop capture was black (`/tmp/professeure-name-input-diagnostic.png`).
  These attempts are NOT OS interaction acceptance; GPU/API checks are distinct.
- [x] Finish empty-take affordances. Rename, Favorite, Mark A, Compare, Reset,
  Trim copy and Publish require an idle, loaded, nonempty selected take. An empty
  waveform explains how to get a take; it has no fake trim handles or drag hint.
  Profile naming remains available. Full isolated suite: **111 tests / 1,075
  assertions, zero failures/errors** (`/tmp/professeure-empty-controls-final.log`).
  Reloaded into the existing JVM. Inspected both actual Vulkan capture
  `/tmp/professeure-empty-controls-final-gpu.png` and macOS window capture
  `/tmp/professeure-empty-controls-os.png`. Eight native hit-test clicks on the
  disabled actions/waveform preserved project revision 135, transport, selection,
  trim, event cursor and pending action. These are native, not OS-input clicks.
  Selecting the real Mangue take restores enabled controls and its saved waveform.
- [x] Complete A/B readiness and selected-waveform playback identity. This is
  optional human listening to dry/processed audio or alternate performances of
  the same passage, not automatic quality scoring. The UI distinguishes no A,
  A marked/select B, listen A/B, and unavailable files. The API rejects same-take
  and unmarked comparisons before opening audio. A removed from the passage's
  history is unavailable even if its file remains. Reference A does not drive
  selected B's playhead or per-take pause; global transport still controls it.
  Full isolated suite: **114 tests / 1,094 assertions, zero failures/errors**
  (`/tmp/professeure-comparison-final.log`). Hot-reloaded the existing Studio.
  Native Mark A click produced “A marked: select B”; disabled Compare preserved
  state, and direct API comparison returned `:comparison-not-ready`/`:select-b`.
  Inspected `/tmp/professeure-comparison-marked.png` and
  `/tmp/professeure-comparison-playing-b.png`. For actual audio, temporarily set
  the nondurable reference to an existing dry take while keeping the selected
  processed take unchanged: A output 113,337 non-silent frames and completed at
  2.38 s; B's native button click output 10,143 non-silent frames at 0.248 s.
  A's first “still playing” assertion was too late for the short clip; completion
  plus actual PCM/cursor establishes playback, not that failed assertion. No
  acoustic listening claim. The pair setup was a controlled reference fixture,
  not a physical take-selection test. Restored reference, Record mode and stopped
  transport; project/take selection remained exactly unchanged at revision 135.
- [x] Guarded OS-input Record → Stop → Listen → Publish acceptance. The click
  driver reads the Studio's current content position for each click, checks the
  foreground PID, and AX-hit-tests the destination before mouse-down. An initial
  stale-coordinate attempt aborted before Record and restored routing. Retried
  with dynamic coordinates: five OS clicks recorded a 2.87-second BlackHole →
  Bitwig → Studio return and auditioned 3,528 non-silent output frames. The saved
  take has the Mangue capture-text fingerprint and Recorded status. Inspected
  `/tmp/professeure-os-recording-fx.png`: live script, waveform, meters and Stop.
  Bitwig's actual window shows input 1/2, return 3/4 and its Tool effect; no
  Bitwig settings were changed. This uses a labelled 220/330 Hz QA signal, not
  a new physical microphone/acoustic-listening claim. QA dry/wet files remain
  in history; prior take references and routing were restored.
  Actual OS Publish click advanced game voice revision 6 → 7 with a changed
  content hash; restoring the original WAV advanced 7 → 8 and restored the hash.
  SHA-256 confirms byte-exact original restoration. A recovery copy remains in
  `build/recording-qa/voice-backup-2047bbd7-b2d1-4d5f-ba16-fe891b5f409e.wav`.
  The opt-in helper now preserves an idle game's remembered voice node instead
  of assuming it must be unset. Original selection/publication references were
  restored and both transports stopped; no sample was left published.
  Final isolated regression suite: **114 tests / 1,094 assertions, zero
  failures/errors** (`/tmp/professeure-os-workflow-final.log`).
- [x] Repeat physical take-name editing/switching with guarded OS input.
  Actual mouse focus, System Events Cmd+A, and clipboard-preserving paste entered
  `QA – fenêtre éphémère`. Inspected `/tmp/professeure-name-draft-os.png`.
  Clicking the text-only first row cleared the draft and focus; clicking Mangue
  restored `Retour aligné`. Project and all take entries stayed byte-for-value
  unchanged; no Enter/Rename/save was sent. Reusable opt-in
  `live-name-draft-os-qa!` repeats this path and restores selection/mode/scroll.
  Its first cleanup used a host write to an inferred Boolean without a writable
  schema; replaced with the existing native reset function. The complete retry
  passed and restored Record mode. This verifies accented paste, not IME input.
  Final isolated suite: **114 tests / 1,094 assertions, zero failures/errors**
  (`/tmp/professeure-name-os-final.log`).
- [x] Capture transient PREPARING at device startup. Closed by the September 12
  native-event/video pass at the top of this file, including a separate real
  phase-1 Stop cancellation with no take created. The following earlier OS-input
  attempt remains historical evidence, not a prerequisite for native testing.
  Require a verified focused
  window/field before typing; a failed nREPL assertion does not make the shell
  command fail, so never chain subsequent typing behind an unchecked assertion.
  Latest retry: OS click focused the name field, but Cmd+A/Unicode input did not
  change its text. The foreground process subsequently resolved to macOS
  `loginwindow` (PID 165); desktop capture was black while individual window
  capture remained readable. No Enter/save was sent and no stored name changed.
  The opt-in input helper now accepts `--target-pid PID` and checks foreground
  immediately before any input/clipboard operation in that invocation. Wrong-PID
  text test exited 3 without sending input. Use the guard on subsequent OS tests;
  it supplements, not replaces, verifying the correct window/field in that JVM.
  Do not rely on a focus check from an earlier tool call.

- [x] Active game voice focus suppression, not just its Boolean flag. Navigated
  to the real published Mangue passage: playing=true, native sound volume 0.8,
  cursor 2,646. OS F1 focused Studio: same voice still playing, volume 0.0,
  cursor 51,597. Studio audition independently produced 18,963 non-silent output
  frames while the game voice stayed at zero gain. F1 back restored game gain
  0.8 at cursor 992,691. This measures the actual miniaudio sound gain/cursor and
  Studio PCM callback, not an external acoustic recording. An initial assertion
  compared the new audition counter to a previous audition's total; that was
  invalid because counters reset on start. Recorded snapshots establish the
  nonzero current output instead. Restored prior game branch/page/offset and
  stopped both voices; no take or project edits.
- [x] Inspect real Edit and Record layouts at 1100x760 and 1500x950. Screens:
  `/tmp/professeure-studio-{edit,record}-{minimum,wide}-review.png` (four files).
  Controls, selected script, meters and bottom waveform remain inside panels.
  Wide Record exposes more passages; Edit keeps the user-controlled split.
  Found stale take-name data during this pass; fixed below. This is static
  layout acceptance, not new proof of every drag/scroll gesture at both sizes.
  Restored window bounds and Record mode after inspection.
- [x] Make startup meter status agree with PREPARING. The routing snapshot now
  supplies the actual countdown predicate: zero-count-in startup says opening
  audio devices, not waiting for count-in. Dry's unused FX return remains
  bypassed. Added state/label/absent-peak regressions. Last-take level warnings
  now identify themselves as “Last take” rather than looking like live meters.
  Routing change suite: 109 tests / 1,061 assertions, zero failures/errors
  (`/tmp/professeure-routing-preparation-final.log`).
- [x] Fix selected-take name refresh at the JVM/native boundary. Actual selected
  entry was “Retour aligné” while Edit retained a prior QA name. Reading the
  inferred native focus flag returned `[0]`, truthy in Clojure, skipping refresh.
  Added a typed native `name-focused?` getter and use it at that host decision.
  An attempted explicit state type changed the schema and was rejected; reverted
  it without migration, audio-object changes or restarting. The worker retains
  two historical schema errors, is running, and current module await succeeds.
  After normal passage selection and completed waveform loading, inspected
  `/tmp/professeure-studio-name-verified.png`: the actual selected name is correct.
  The earlier `...-name-corrected.png` was captured during loading and is NOT
  a passing screenshot. Added native false/true/false Boolean regression; its
  setup must use a native setter because inferred state also rejects host scalar
  construction. Final isolated rerun: 110 tests / 1,067 assertions, zero
  failures/errors (`/tmp/professeure-name-focus-verified.log`). The earlier
  `...-name-focus-final.log` is the failed host-scalar fixture, not a pass.
  No stored take was renamed; project revision remains 135.

- [x] Interrupted-recording recovery is one retry-safe project transaction.
  Reproduced eight failures: two project revisions per dry/wet pair and duplicate
  take history after finalization failure. Recovery now persists stream origins,
  commits the pair as one undo step, and finalizes a previously committed import
  without duplicating it. WAV names use durable-PCM hashes; reuse verifies exact
  PCM and rejects damaged files instead of overwriting them. Real temporary-file
  tests cover captured/legacy provenance, project-write failure, finalization
  failure, reopening persisted project state, durable prefix lengths, originals
  retained, exactly two WAVs and no duplicate history. Final full isolated suite:
  **108 tests / 1,051 assertions, zero failures/errors**. Log:
  `/tmp/professeure-recovery-final.log`. No live recovery scan was invoked.
- [x] Publish recovery changes into the existing Studio JVM via nREPL reload.
  Studio module await succeeds; project/selection/transport are unchanged,
  revision remains 128 and the running worker has zero failures. Inspected the
  idle Record layout at `/tmp/studio-recovery-reloaded.png`.
- [x] Repeat post-reload OS mouse audition with verified unobscured hit targets.
  AX hit testing identified Codex over Studio despite Java being frontmost.
  Temporarily moved (never minimized) Codex, then restored its exact position.
  Actual clicks played at 0.3675 s, paused at 0.8728125 s, selected unrecorded
  passage 8 without losing the paused engine, resumed globally to 1.3321875 s,
  and stopped. Non-silent output counters advanced. Inspected
  `/tmp/studio-unobscured-pause.png` and
  `/tmp/studio-unrecorded-paused-other.png`. Earlier `studio-recovery-pause`
  images show STOPPED and remain failed attempts, not acceptance evidence.
- [x] OS-driven game branch and typewriter visuals: root choices, completed-leaf
  enclosing choices with visited entry dimmed, and nested authored choices.
  Inspected `/tmp/professeure-acceptance-root.png`, `...-leaf.png`, and
  `...-nested.png`. Navigation changed visited state; it was not reset.
- [x] OS-click Live FX capture and audition, then actual game publication watcher.
  Explicit virtual 220/330 Hz input passed through the existing Bitwig route;
  saved dry/wet pair `33147828-edcb-4f28-87dd-2de1ed9665bf` has 129,120/181,440
  valid frames and captured script identity. Mouse audition produced 11,907
  non-silent frames at 0.4318125 s. Publication through the control API changed
  the live game's voice revision 2 -> 3; restoring original bytes advanced it
  to 4 with the original hash. This did not test clicking Publish itself.
  Retained labelled QA pair, restored original selected/dry/wet/preferred/
  published pointers, routing and undo history; final project revision 135.
  Tone stopped. No physical microphone or authored-note edits.
- [x] Correct zero-count-in startup guidance. A real capture screenshot showed
  COUNT-IN with zero count-in while devices opened. Phase 1 now distinguishes
  an active countdown from PREPARING / Opening audio devices. Reloaded native
  getters verify both new strings. Full isolated suite: 109 tests / 1,057
  assertions, zero failures/errors (`/tmp/professeure-preparing-final.log`).
  Actual corrected startup screenshot remains an acceptance task.
- [x] Fix and retest quick game keyboard navigation. Two OS System Events
  Backspace taps left parent 6 unchanged; a 25 ms CGEvent press reached parent 4.
  Game key polling can miss press/release between frames. Enable GLFW retained
  keys only on the game window. After reload, three quick taps independently
  returned 6 -> 4. Later input attempts lacked focus and are not passes; moved
  only the game temporarily to expose its titlebar, clicked it, and verified
  native focus + retained-key mode. A one-second hold returned 6 -> 4 exactly
  once. F1 game -> Studio -> game passed with the expected suppression flags.
  This flag check is not measurement of active game sound becoming silent.
  Inspected `/tmp/professeure-keyboard-final.png`: correct returned branch,
  completed French text, visited choice dimmed. Restored game position 314,111;
  Codex was neither moved nor minimized during this keyboard pass. Added bounded
  `hold-key` OS QA support and opt-in native focus/input-mode inspection helpers.
  Full isolated rerun: 109 tests / 1,057 assertions, zero failures/errors
  (`/tmp/professeure-retained-keys-final.log`).

- [x] Real Bitwig processed-return provenance and playback. The 19.43-second
  labelled QA capture went through the configured Bitwig return, producing a
  valid 20.43-second wet WAV including the one-second tail. Its original script
  fingerprint survived; it matches the current words and requires review for
  different words. Retained as “QA Bitwig provenance - processed return” at
  `build/recording/voice-173791faf357254b/ac3c093e-5642-469a-9d1d-4be3a23b1357-wet.wav`.
  Periodic test audio produced correlation 0.99486 but effectively zero peak
  distinctness; automatic alignment was correctly rejected and no aligned copy
  created. This does not establish exact latency or positive alignment accuracy.
  The return is quiet (peak 0.003244); low-level guidance remains visible.
  Public take audition advanced 0.3032 → 1.1301 seconds and non-silent output
  frames 10,584 → 50,274, with capture inactive. Original selected/dry/wet/
  preferred pointers restored; transport stopped. Project 95 → 102 records QA
  source selection, retained return, naming, audition and restoration. No
  microphone capture, authored-note edit, Bitwig setting change or publication.

- [x] Positive real Bitwig alignment and audition. A known 4.7499375-second
  legacy dry take produced a uniquely correlated return: 3,424 frames /
  71.3333 ms, correlation approximately 1.0, peak distinctness 0.41949. The
  aligned copy has 272,573 stereo frames and its decoded PCM equals the raw
  returned PCM after exactly that offset, byte-for-byte. Both originals remain.
  `build/recording/voice-173791faf357254b/94fb7327-a7b3-4e6e-a2b3-9d2941c2e4b2-aligned.wav`
  is named “QA Bitwig - aligned return”. A legacy source has no script hash:
  neither derived version invents one, so it correctly remains Unverified.
  Native audition advanced 0.349125 → 1.1025 s and non-silent output frames
  14,994 → 51,156. This tests the current route, not universal effect latency.
- [x] Finish active-FX presentation publication/visual acceptance. Found that
  offline Process FX retained the next-recording Dry label and Edit used that
  preference to select live waveform data. New native helpers derive active
  capture type from recorder mode; idle/count-in retains the user's preference.
  Added FX pass / Live FX labels and Take send meter wording. A real screenshot
  then caught stale prior-take waveform and bypass labels at capture start.
  `begin-capture-presentation!` now publishes cleared live PCM, current meter
  labels, ownership and cleared old warning in one render task. Capture guidance
  and waveform headings distinguish the return from saved-take audition.
  Native regression: 9 tests / 157 assertions, zero failures/errors.
  OS capture `/tmp/studio-coherent-fx-signal.png` proves the cleared waveform and
  Take send / FX waiting labels, but still shows old surrounding draw text:
  this is PARTIAL publication evidence, not final visual acceptance.
  `az/await!` after file reload failed on Studio `voice_decoders`, and exact
  publication of `draw-record-workspace!` failed on Studio `voices`. Earlier
  global await also hit scene `voices`. Investigate changed schema identities
  of imported miniaudio structs; no fields were intentionally changed here.
  Do not use a blind memcpy/zero migration on live audio objects. Old active
  Studio decoder/sound/engine state sizes are 1104/2048/1360 bytes respectively.
  Small native helper publications and actual audio still work; worker is alive,
  historical failures 37 → 39. Source definitions are current; some old draw
  implementations remain in the live process. Repeat complete visual verification
  after resolving publication, including the new live-return heading/guidance.
  Three real FX passes retained six QA raw/aligned files. Original source/take
  pointers restored, transport stopped, project 102 → 125 (QA and restoration).
  No microphone recording, original-note edit, Bitwig setting change or publish.
  **Superseded by fresh-process acceptance (2026-09-12):** actual saved-take
  processing now shows FX pass, SEND, LIVE FX RETURN and processing guidance;
  prior waveform is cleared and audition is disabled during capture. Inspected
  `/tmp/studio-active-fx-final.png`. Saved 162,240 stereo frames (3.38 seconds)
  as `b57cb51a-d7d2-43ad-b411-40413d319e2a-wet.wav`, named “QA active FX screen
  - verified”. Legacy provenance remains Unverified; no fingerprint invented.
  Original selected/dry/wet/preferred pointers restored; worker failures zero.
  Project 125 → 128 retains only the QA history addition/name/restoration.

- [x] Isolate foreign-type state reload without touching live audio. Added a
  native regression in `test/aguafria/zig/constant_reload_test.clj`: C-imported
  `div_t` struct alias → persistent array → native mutation, then two unchanged
  provider/consumer reloads. Both synchronous and asynchronous compilation retain
  the nonzero state address, schema fingerprint and mutated value. Full constant
  reload namespace: 2 tests / 28 assertions, zero failures/errors (2026-09-12).
  This does **not** reproduce or resolve the running Studio conflict. Inspection
  locates that conflict in differing constant-reference fingerprints embedded in
  otherwise identically named audio state types; their type-dependency lists are
  empty. Next: establish why the retained references diverged before attempting
  publication. Do not weaken schema validation based on matching byte sizes.

- [x] Reproduce and fix the literal-field identity bug. The dependency refresher
  treated the selector in `(field c-api ma_sound)` as a reference to the
  same-named constant. A focused regression failed on both unwanted metadata and
  unstable repeated fingerprints before the fix. Reference walking now treats
  selectors as identifier literals; dependency scanning still retains the full
  field form for explicit namespace-member resolution. Runtime + native reload:
  24 tests / 129 assertions; emitter: 29 tests / 134 assertions, all passing.
  This corrects future identities, not already-owned live audio objects.
- [x] Make generated miniaudio member selectors explicit keywords. `c-api` is
  a declared Var initialized by `cImport`; generated exports now use
  `(az/field c-api :ma_sound)` and analogous keywords. Regenerated the actual
  file; generator/game regressions pass 9 tests / 102 assertions.
- [x] Complete the full headless Studio suite with its real asset setup. Added
  the missing nREPL test dependency. First full run: 107 tests / 842 assertions,
  five failures and one error. Missing native font metrics/dialogue initialization
  explained four failures and the error; the scrollbar expectation still used
  six rows instead of the current four. Corrected setup/expectations; full rerun
  passes 107 tests / 870 assertions, zero failures/errors. This is headless native
  and API coverage, not a replacement for visual or physical-device acceptance.
- [x] Clean startup, actual live drawing reload and mouse audition after the fix.
  Old idle process 97079 could not complete Studio close because its pending
  compilation hit the same schema guard. Confirmed no session/armed recording,
  exited that JVM without forcing migration, verified termination, and launched
  only La Professeure again. New JVM 64167, nREPL 64351; game window 133836,
  Studio 133843. Restored selection and routing; Bitwig/other projects untouched.
  Changed the native Record heading to `DIALOGUE RECORDING / LIVE`, reloaded the
  whole Studio namespace and awaited publication, then restored the heading and
  reloaded again. Both publications succeeded. Three concrete sound/decoder/engine
  state addresses (15767636960 / 15767901224 / 15767557160) and schemas remained
  unchanged, not empty evidence. Inspected `/tmp/studio-live-reload-proof.png`
  and `/tmp/studio-clean-runtime-record.png`; game text/choices also rendered
  correctly in `/tmp/professeure-clean-runtime-game.png`.
  Real OS mouse clicks started, paused, resumed and stopped selected-take playback.
  Pause held at 0.7993125 s / 37044 signal frames; resume reached 1.323 s / 61740.
  `/tmp/studio-verified-paused.png` shows matching Resume controls and held cursor.
  Final transport stopped, session nil, worker alive with zero failures.
  Project revision 125 and SHA-256
  `ebf86670c4677f18c67489279784cf5f045d7ab7e60ad29bf86496313251b965`
  unchanged across restart, reloads and playback. No takes created/published.
  Actual active-FX capture labels remain a separate visual gate below.

## Current priority: readable studio code, then visual polish

- [x] Verify original Obsidian-source live save. Selected the exact path using
  Obsidian's Copy absolute file path command; `dialogue.edn` now points to that
  original, not the project copy. Preserve the old ID registry explicitly with
  `:recording-registry`. User granted macOS access; existing JVM watcher recovered
  without restart. Temporarily appended voiced `^qa-studio-sync`: real watcher
  grew the loaded story from 12 to 13 nodes and Studio showed Needs recording.
  Edited that line: same ID, revision 1 → 2. Removed the temporary passage and
  verified the note byte-for-byte against its pre-test content. Story returned
  to 12 nodes; original five voice IDs/revisions retained. Inspected actual window
  `studio-original-dialogue-new-passage.png`. Registry 53bd60bd3216dcb0 retained.
  Project stayed revision 82; no takes created/deleted and no voice published.
- [ ] Expose dialogue completeness clearly in Studio. Existing reconciliation
  creates new IDs/rows and marks edited speech `:needs-review`, preserving takes,
  Record rows now show Needs recording / Changed - review take / Recorded /
  Unverified / Interrupted. Captures retain a speaker+text SHA-256 fingerprint
  from start, not save time; FX, aligned/trimmed and recovery versions retain
  source provenance. Legacy/imported takes remain unverified. Query exposes
  source state and visible passage statuses. Native status uploads validate ID
  and revision, rejecting obsolete row positions. API selection scrolls its row
  into view and clears text scrolls. Actual screenshot inspected:
  `studio-dialogue-needs-recording-settled.png`.
  Tests cover changed words/speaker, restored content, unknown/interrupted takes,
  session/derived metadata and stale native uploads. Final regression: 43 API
  tests / 369 assertions; 31 native tests / 221 assertions, zero failures/errors.
  Remaining: FX/alignment/recovery end-to-end provenance acceptance and physical-input
  acceptance. Original-note concurrent capture passed below; Edit/Takes now have
  compact status badges for each passage's selected take. Recorded means
  matching script, not proof of signal quality or publication.
  Cache visible script snapshots until publication, viewport or take state changes;
  don't fetch native text on every meter tick. First implementation caused 5–6
  intervals above 16.67 ms per 240 frames; cache eliminated these in all three
  measured views. Cache-invalidation regression passed in the final isolated run.
- [x] Verify real trimmed-copy script provenance (2026-09-12). Selected the
  labelled original-note capture from the earlier concurrent-edit test, invoked
  the public Trim command for 20–80%, and named the new retained copy
  “QA provenance - middle 60 percent”. Result is 559,584 stereo frames / 11.658 s;
  decoded PCM exactly equals the selected sample range from its source. The
  persisted source fingerprint survives unchanged: matches current dialogue,
  becomes Changed for different words. The latter is a comparison assertion,
  not another edit to the author's Obsidian note. Evidence:
  `build/recording/voice-173791faf357254b/a5411075-7923-45c8-a4a5-af78dfbd314a-trim.wav`.
  Original selected/dry/wet pointers restored with guarded cleanup; original
  recordings untouched, trim controls reset. Project 90 → 95 reflects selecting
  the QA source, creating/naming its copy, and restoring pointers/selection.
  No audio publication or microphone capture. FX/alignment/recovery acceptance
  remains separate; this closes the actual trim path only.
- [ ] Finish capture/script ownership acceptance. The live script previously
  reread the selected row, and capture markers used a stored row number. Record
  mode now owns copied script text and a stable capture ID through count-in,
  recording and FX tail. Reordered/deleted rows cannot rewrite that text or move
  the recording marker to a different ID. Its waveform follows the capture;
  Edit still displays the selected passage's saved waveform, never unrelated live
  PCM. This does not enable changing selection through the recording-busy API/UI
  guard. New capture query data includes the script snapshot and its fingerprint.
  Native reorder/removal contract passed; 33 native tests / 223 assertions and
  46 isolated API tests / 385 assertions passed. Real virtual-audio capture with
  fault-injected selection/mode changes retained the correct script/ID and saved
  valid PCM. First probe hit the recording-busy guard as expected; the next
  clicked Listen before its waveform upload enabled it. QA now waits for that
  explicit UI readiness. Final `live-capture-owner-qa! 3 7` passed: captured 1.41 s
  from BlackHole, peak 0.0514107; validated native WAV and actual audition produced
  882 non-silent output frames with cursor 0.0275625 s and capture phase 0.
  Across Record → Edit → Record, capture counts advanced 48,480 → 50,880 → 54,720;
  Record retained the original spoken text/ID while Edit displayed the other row.
  Evidence: `build/recording/voice-173791faf357254b/05379e77-6030-4ab7-9bbe-f7a4e9210ac7-dry.wav`.
  Three labelled QA takes retained from these probes; prior selected/dry/wet
  references restored by the guarded helper. Project 82 → 88 reflects these
  additions/restorations, not authored edits; original routing/monitor state
  restored, transport stopped, worker alive with unchanged 37 historical failures.
  No microphone capture or game publication. Physical-input acceptance remains
  distinct/open; original Markdown concurrent-edit acceptance passed below.
  Inspected fresh 2200×1520 Vulkan readback after cleanup:
  `/tmp/studio-capture-owner-idle-gpu.png`. Correct original Mangue waveform,
  stopped transport, selected row, readable controls and restored routing. This
  is the actual GPU color attachment, not lock-screen OS-composition evidence.
- [x] Capture while editing the original Obsidian note (2026-09-12). A real
  BlackHole test-tone capture ran for 19.43 s while the authored Mangue line was
  temporarily extended. The watcher advanced revision 1 → 2 with the same ID;
  Studio's capture script retained the original words. Sample counts advanced
  48,000 → 927,840, and the saved take retained the original fingerprint and
  reported `:changed` against the edited text. Native WAV validation and audition
  passed: peak 0.0514107, 882 non-silent output frames, cursor 0.0275625 s.
  Restored the original note byte-for-byte: revision 3, same ID, QA take status
  `:recorded` against restored words. Evidence: retained labelled test take
  `build/recording/voice-173791faf357254b/f952a24a-8997-4c65-b318-d548c5915b7f-dry.wav`.
  Original selected/dry/wet pointers and routing restored; project 88 → 90
  records the QA addition/restoration. No microphone capture or publication.
  This verifies real capture/watcher/provenance, not physical mouse interaction.
- [x] Add readable Edit/Takes passage-status badges. Checking / No take / Review /
  Recorded / Unverified / Interrupted / Text only; full reason on hover. Badges
  describe the selected take, not every Dry/FX/Favorite card. Fresh GPU inspection
  caught overlap with Play/Arm buttons; Edit rows now use 56-pixel spacing and
  54-pixel content, with status/snippet below controls. Updated hit regions,
  waveform centering, viewport bounds and the row-audition QA helper together.
  Inspected fresh 2200×1520 GPU images of Edit and the fully loaded Takes view:
  `/tmp/studio-edit-status-badges-final.png` and
  `/tmp/studio-takes-status-badges-settled.png`. No badge/button overlap; saved
  waveforms and selected FX take remain visible. Final regression passed:
  34 native tests / 237 assertions and 46 isolated API tests / 385 assertions,
  zero failures/errors. Restored Record mode, original selection and stopped
  transport. Native test initially reused a closed slice; fixed test lifetime.
  Enlarged row spacing also required updating the bounded divider's maximum
  height so its existing 32-row cap remains reachable on large windows.
  Physical OS composition/input remains unverified while macOS is locked.
- [x] Eliminate the brief previous-passage waveform/Play-enabled state while the
  worker refreshes a newly selected passage. Observed transient then correct
  settled state in live QA; invalidate presentation immediately on selection,
  without discarding any existing playback engine or takes. Test rapid switches.
  Implemented stable passage-ID ownership for uploaded waveforms: stale decoded
  results are rejected on the render thread; pending selections hide old bars,
  disable audition/trim, and cancel take-specific drags. Existing playback stays
  independent; top Pause/Space targets the active transport across selections.
  Native ownership test covers A → B → A without changing cached PCM/preview;
  host test rejects obsolete upload callbacks. Regression passed: 32 native
  tests / 222 assertions and 44 isolated API tests / 375 assertions, no failures.
  Actual macOS click selected unrecorded passage 8; inspected
  `/tmp/studio-waveform-unrecorded-live.png`: correct text, empty waveform,
  disabled Play/Listen/Previous/Next. Later physical playback/rapid-switch QA
  could not proceed: foreground PID 165 was loginwindow; clicks did not start
  playback and must not be counted as acceptance. Retry when desktop is unlocked.
  Unlocked acceptance now passed (2026-09-12): added a bounded 2–32-click burst
  to the Swift OS-input driver. Sixteen OS clicks alternated recorded passages
  2/4 with unrecorded passage 8. Across 128 render-thread snapshots, all three
  selections were observed; zero unrecorded-row positive durations and zero
  invalid-owner positive durations. Capture phase remained 0 throughout. This
  samples live behavior, not every presented frame; the native stale-owner and
  delayed-upload regressions cover the rejection contract. OS screenshot
  `/tmp/studio-rapid-switch-os.png` captured the resulting empty passage view.
  The actual pause/resume-after-selection check and fix are recorded below.
- [x] Fix global Resume after changing passage (2026-09-12). Desktop was unlocked;
  real macOS clicks started the Mangue take, selected unrecorded passage 8 and
  paused. This exposed disabled Resume and a Space key path that attempted the
  new empty passage instead of the paused sound. Added shared native transport
  action selection and paused-sound detection; global Resume is independent of
  selected passage and takes precedence over Edit's REC toggle. Passage-specific
  audition still targets that passage. Removed the selection guard from the
  low-level resume operation; it retains the loaded sound and cursor.
  Retested with OS mouse events and System Events Space: paused at 1.672125 s,
  mouse Resume advanced to 2.1315 s, subsequent Space resume to 3.068625 s;
  non-silent output frames 78,939 → 100,989 → 145,971. Capture phase stayed 0.
  Inspected OS screenshot `/tmp/studio-resume-enabled-os.png`: Resume enabled,
  passage 8's waveform correctly empty and its Listen button disabled. Screenshot
  retains the previous failed probe's “Select a take” alert; no new alert produced
  by successful resume. All three native audition controls also passed real PCM
  cue/pause/hold/resume/stop checks after the fix. 15 targeted native tests / 170
  assertions and 47 isolated API tests / 388 assertions passed. Original selection,
  stopped transport, project revision 90 and every take entry preserved. Rapid
  repeated switching and remaining trackpad/resize cases remain separate gates.
  Restored Mangue selection; project revision 82, routing, monitoring and stopped
  transport match the pre-QA snapshot. No recording or publishing in this pass.
- [x] Finish frame-timing instrumentation acceptance (240-sample bounded native
  ring and API distributions). Baseline actual submitted cadence: Edit 120.44,
  Record 119.90, Takes 120.01 FPS. This is dev cadence, not standalone release or
  p95 evidence. Only disposable profiling storage was explicitly migrated during
  hot reload; no recording/project data reset. Validate distributions and tests.
  Current Record-view sample: 240 intervals, 120.02 FPS, p95 9.154 ms, p99
  9.236 ms, maximum 9.332 ms; zero intervals over 16.67 ms. UI build p95
  0.530 ms, render-call wall time p95 1.749 ms (not GPU timestamp timing).
  Native ring/reset contract passed 1 test / 1 assertion. Full regression and
  other-view distributions subsequently passed below and in the checkpoint.
  Worker recovered from the
  explicit profiling-layout migration guard (historical failure count now 37).
  After script-cache fix, measured 240 frames per mode: Edit 119.98 FPS / p99
  8.769 ms / max 8.841 ms; Record 120.01 / 8.767 / 8.886; Takes 120.00 /
  8.826 / 9.025. Zero intervals >16.67 ms in each view. UI-build p95 all <0.5 ms.
  These are steady live-dev samples, not interaction/device-load/release gates.
- [x] Simplify the routing panel with a collapsed-by-default Routing tools
  section for profiles, reconnect, offline FX processing and recovery. Keep
  everyday device and monitor controls visible. Expose the presentation toggle
  through `:view/routing-tools` and `query :view`; no project mutation or device
  reopening. Device selectors and monitoring toggles now visibly disable during
  capture/input checks, with explanatory hints. Existing control guards remain.
  Real macOS clicks expanded/collapsed the section at 1100×760; inspected
  `studio-routing-tools-expanded.png` and `studio-routing-tools-collapsed.png`.
  Project, recording, routing, monitor and transport snapshots were unchanged.
  A real BlackHole input check kept the mic selector disabled: physical click
  left menu 0, checking true, controls available false. Inspected
  `studio-routing-disabled-check.png`, including the visible disabled controls
  and Stop-check hint. Stopped the check and restored the original mic/routing;
  no take created, no audio published, project stayed revision 82. A probe used
  the wrong host query path for input-check state; authoritative native accessor
  and the screenshot confirmed it was running. Native regression: 29 tests /
  219 assertions. Isolated API: 39 tests / 343 assertions. Zero failures/errors.
- [x] Replace ambiguous monitoring Vol +/- buttons with a labelled, draggable
  Monitor level fader and current percentage. Preserve the 0–50% cap; never
  enable monitoring or change recording/audition gain by adjusting the fader.
  Hidden routing or a device popup cancels dragging. Validated API command
  `:routing/monitor-level` and query `:monitoring` expose the same setting;
  bounded gain adjustment is permitted during capture without changing routing.
  Live macOS click set 10%; dragging beyond the left rail set 0% and release
  cleared drag state. Monitoring stayed off, no capture started, take gain stayed
  1.0, transport stayed stopped and project revision stayed 82. Restored the
  original 15% through the API. Inspected the actual native-window screenshot
  `build/recording-qa/studio-monitor-fader-10.png`, including the clear level label,
  fader and explanatory hover hint. First native interaction/layout pass:
  6 tests / 27 assertions, zero failures/errors. Full native regression passed:
  28 tests / 218 assertions. Isolated API regression passed: 39 tests / 336
  assertions. Native callback fixtures verify zero monitor output at 0%, scaling
  at the capped 50%, and unchanged pre-monitor return metering. No failures or
  errors. These callback fixtures are not headphone listening acceptance.
- [x] Detect unexpectedly stopped owned capture/source, monitor and input-check
  devices using miniaudio's atomic device-state accessor on the control worker.
  Do not inspect uninitialized devices or treat a normal start/stop transition
  as failure. Cancel stopped checks, disable stopped monitoring without stopping
  healthy capture, and save each nonempty interrupted stream independently.
  Mark retained entries `:interrupted?`; never auto-publish or substitute devices.
  Encoding/checkpoint failure keeps the session/PCM and shows AUDIO STOPPED;
  Stop retries saving rather than waiting forever for a dead FX tail callback.
  Isolated regression: 36 tests / 309 assertions, zero failures/errors, covering
  empty-return/dry retention, monitor isolation, one-shot warnings and save failure.
  In the actual Studio, stopping its BlackHole preflight instance produced the
  visible warning in `studio-input-device-stopped.png`; reopening the input check
  succeeded. No preflight take/project mutation or system routing changes.
  A preliminary live capture injection was rejected by the recording-busy guard;
  that guard remains intact. The helper's normal Stop cleanup retained the QA
  capture and restored prior take references. This was not an interrupted-save
  pass. Isolated real-engine acceptance subsequently passed: captured 48,480
  frames (1.01 s) from BlackHole, deliberately stopped only that owned device,
  observed mask 1, ran the production interrupted-save handler, and decoded the
  retained WAV at peak 0.051410746. Session cleared, mask returned to zero, and
  both stopped-device/interrupted-recording events were emitted. Retained evidence:
  `build/recording-qa/interrupted-93dd24ac-bd62-44d7-9e1e-33b76daf9db2/dry.wav`.
  This isolated test uses actual native capture/encoding/decoding but does not
  claim OS unplugging or saved-take audition in that isolated process.
  Final native regression: 27 tests / 217 assertions, zero failures/errors,
  including AUDIO STOPPED guidance. Live workspace restored to Record, selected
  passage 4, idle capture/check/playback and original routing. Worker remains
  alive with historical failure count 36. Project revision 80 → 82 is the
  retained normal QA capture plus guarded reference restoration from the rejected
  injection, not a user edit or a published voice change.
- [x] Recover stopped audition/mix outputs without throwing away playback position.
  Keep decoded take audio or mix PCM, pause visibly, and retry the selected output
  explicitly on Play. Failed retries must not reload the take from zero. The top
  Play/Space controls retain an active mix in Record mode; the lower Listen to
  take control remains separate. The worker emits `:audio/output-stopped` once
  per stopped instance and shows an actionable warning.
  Live native-button acceptance passed for both outputs: audition held at
  0.3399375 s and resumed to 0.47775 s with increasing non-silent output frames;
  mix held at frame 14,994 and resumed to 21,168 with its mix identity intact.
  Inspected actual Vulkan captures `studio-output-stopped.png` and
  `studio-mix-output-stopped.png` in `build/recording-qa/`: paused transport,
  retained waveform and readable recovery notice. No recordings, take selections,
  publication or project revision changes (revision 82 throughout). These tests
  stop our own device instances, not physical hardware. Native regression:
  27 tests / 217 assertions, zero failures/errors. Isolated API regression:
  38 tests / 324 assertions, zero failures/errors, including failed retry retention
  and stopped-output event/warning behavior. Updated control fixtures to include
  mix state and inject compile failure at the worker's new first native accessor.
  Actual macOS mouse input also started audition (20,286 non-silent output
  frames), then a bounded replay/pause check paused at 0.569625 s. Inspected
  `studio-playback-os-paused.png`, including the native window decoration,
  Resume controls and retained playhead. An earlier unbounded second click came
  after the 5.7-second take ended and correctly restarted it; it is not counted
  as pause evidence. Restored stopped transport at zero, empty mix, original
  output and project revision 82; dismissed only the injected QA warning.
- [ ] Extend failure acceptance to actual hardware removal/reappearance and
  backend callback stalls. FX-pair owned-instance interruption now passes the
  disposable real-PCM probes in the checkpoint. A stopped instance test does
  not prove every backend's device-removal behavior or unchanged-but-stalled
  audio callbacks.
- [x] Fix long take-name selection dragging: edge scrolling is elapsed-time
  based rather than one character per rendered frame, and pointer hit-testing
  stays inside the visible field. Both edges stop at valid UTF-8 positions;
  the right edge retains visible text instead of scrolling into an empty field.
  Native 30/120 FPS checks agree. Actual OS held drags at 1100×760 selected a
  99-byte accented draft to the end (caret 99, anchor 0, view 62) and back to
  the start (caret 0, anchor 99, view 0); release cleared dragging. Inspected
  `studio-name-drag-right.png` and `studio-name-drag-left.png` in
  `build/recording-qa/`. The Swift QA driver has an explicit bounded `drag-hold`
  gesture. No rename was submitted, no audio device opened, and project revision
  stayed 80. Original draft/history and Record view restored. Expanded native
  regression: 27 tests / 214 assertions, zero failures/errors. Two existing
  editing tests now preserve draft history as well as text/caret. IME and
  composed-grapheme editing are not covered by this codepoint-based test.
  QA-only setup mistakes (writing an inferred native bool from the JVM and
  queueing a host focus helper from the render thread) were rejected/timed out;
  the live JVM recovered without restart. Final mouse tests used OS field focus
  and the host focus helper off-render; neither mistake is counted as a pass.
- [x] Verify long dialogue scrolling beyond the short authored note. Clip glyph
  geometry and atlas UVs at the pane boundary instead of popping whole lines;
  wrap overlong words between UTF-8 codepoints. Match the scroll hit area to the
  visible pane. A runtime-only, clearly labelled 24-line French fixture reached
  its final line using OS scrolling at normal and minimum 1100×760 window sizes.
  The authored script was restored without modifying Markdown or recordings.
  Inspected `studio-long-script-clipped.png`,
  `studio-long-script-wrapped-bottom.png`, and
  `studio-long-script-minimum-bottom.png` in ignored `build/recording-qa/`.
  Native geometry checks cover half-pixel UV movement and an unbroken accented
  word. Regression: 23 native tests / 207 assertions, zero failures/errors.
- [x] Repeat the actual Bitwig FX round trip after the direct-record changes.
  Used the existing FX Return project: input 1/2, output 3/4, Tool gain −6 dB
  with both polarities inverted. Record → Stop → saved-take audition passed for
  effect-on and bypass captures. Native control hit tests, not physical microphone
  or OS Record clicks. Float WAV analysis measured −5.9999995 dB between captures,
  inverted effect correlation ≈−1, bypass ≈+1, finite samples and zero clipping.
  Test alignment was disabled because a periodic fixture cannot calibrate latency;
  original alignment/routing restored. Tool was re-enabled after bypass testing.
  Four QA WAVs remain in passage `voice-173791faf357254b` history, bases
  `61e33812-a5f4-415b-b583-492439d997b9` (effect) and
  `11c06d0d-bc19-4901-bc24-4e29cc7d92ed` (bypass). Original selected/dry/wet
  references restored; project revision 71 → 77. Game publication is checked
  separately, not inferred from the successful return recording.
- [x] Verify that processed-take publication reaches the running game after the
  transport changes. `live-voice-publication-qa!` used the public select/publish
  commands and observed the ordinary game file watcher (no manual reload call).
  Voice revision advanced 2 → 3 with the effect take, then 3 → 4 when the original
  WAV was restored. Game was muted throughout. Restored original SHA-256
  `16d33eaa0f656a86aec502198a1679468242d2acefdebe2026d383046175703f`, mtime,
  selected/published references, and idle game voice state; project revision
  77 → 80 reflects select/publish/guarded restoration. A recovery WAV remains at
  `build/recording-qa/voice-backup-362d1f8d-66a6-4184-a068-a70be1087ed7.wav`.
  Final actual screenshot `studio-publication-restored.png` shows the authored
  passage and original saved waveform. Capture/input check/playback are idle;
  the worker is running and its historical failure count remains 36.
- [x] Add explicit ten-second input preflight in Record mode: capture-only peak
  meters, no PCM retention, file, FX send or speaker monitoring. Stop automatically
  or on Stop/Record; freeze routing during the check. Verify native metering,
  control API lifecycle, virtual-input signal and actual button interaction.
  Verified with BlackHole on Dry channels 1/2 and FX-source channels 5/6:
  input about −26 dBFS, no return verification claim, automatic close and Record
  count-in handoff. No takes/project changes (revision 71), physical mic or output
  monitoring. Actual mouse clicks changed check inactive → active → inactive;
  `studio-input-check-active.png` shows current meters and the unchanged saved
  waveform. Its pause-shaped Stop icon was then corrected to a square. Device
  selectors visibly disable during checks instead of silently ignoring clicks.
  Regression: 22 native tests / 206 assertions and 33 isolated API tests / 275
  assertions, zero failures/errors. Native virtual-input helpers restore capture
  routing and Edit's armed row; worker historical failure count remains 36.
  Fresh minimum-size Record `studio-check-minimum.png` and OS-clicked Edit
  `studio-edit-mouse-final.png` were inspected: controls, script and waveform fit.
  Wider acceptance below (long-text scrolling, FX round trip, polish) stays open.
- [x] Distinguish configured devices from current signal evidence in the routing
  panel and API. The shared `:routing-signal` snapshot reports not-checked,
  count-in, bypassed, listening-without-signal, or signal-present. Inactive
  devices do not inherit old held peaks. The FX meter tooltip explains send
  1/2 and return 3/4; signal presence alone is never reported as verified effects.
  The live BlackHole test measured input −25.779 dBFS, displayed a −26 dBFS held
  peak and correctly kept the return bypassed in Dry mode. No mic or gain changes.
- [x] Fix saved-waveform display after live capture overwrites its bins. Invalidate
  the saved-file cache while showing live PCM, even if the same saved take is
  restored afterward. Extract the cohesive waveform refresh section within
  `studio.clj`; retain immutable-file caching on idle frames. A regression proves
  live → same saved file reload and live → empty passage clearing. Live QA now
  checks all 128 restored bins and duration against the selected WAV.
  Final test take `35e2d95e-6e89-4f34-830b-ccc58876ecc0-dry.wav` recorded 1.06 s
  at peak 0.05141075, replayed with 882 non-silent frames at the first sample,
  then restored the original 5.702729 s voice waveform exactly.
  Four small synthetic WAVs from this pass are retained and labelled in passage
  `voice-49d1de6484f07313` history; original dry/wet/selected references restored.
  Revision 63 → 71 covers those additions and guarded reference restoration.
  Two preliminary QA attempts failed: GPU capture correctly refused active
  recording, then the helper used a previous take's counter as a new-take
  baseline. Removed the unsafe capture attempt and corrected the per-launch
  counter assertion; neither failure is reported as an end-to-end pass.
  Latest regression: 21 native tests / 205 assertions and 31 isolated API tests /
  261 assertions, zero failures/errors. Actual final minimum-size Record screenshot
  `studio-record-os-refreshed.png` shows the selected waveform, readable English
  routing states and no overlapping route heading. Intermediate resize captures
  were stale; a later Edit click was not delivered, so that remains open below.
  Restored 1367×836, Record, passage 4, idle audio. Worker failure count remains 36.
- [x] Simplify focused Record mode: the selected voiced passage is the target;
  pressing Record starts a fresh take directly, without a separate arm toggle or
  global-REC-then-Play sequence. Selection alone never opens the microphone.
  Keep audition separate, freeze the target during count-in/capture/tail, reject
  text-only passages clearly, and preserve existing takes. Test switching from
  one passage to another, recording again, cancellation and visible UI guidance.
  Traditional track arming can remain in Edit for multitrack-style workflows;
  it must not redirect the focused Record action to an old passage.
  Implemented `:record/start` with a stable passage ID captured by the native
  click. Record-mode Play/Space/Listen only audition. Native: 18 tests / 166
  assertions; isolated API: 30 tests / 248 assertions, no failures/errors.
  Live native hit tests selected rows 2 → 6 → 2 and used both Record buttons
  with global REC off: each count-in targeted the correct passage and Stop
  cancelled it without capture or project/take edits. UI state was restored.
  Inspected actual 2734×1672 Vulkan frame `studio-direct-record.png`; no manual
  Arm/Disarm control. That initial pass was at loginwindow, not OS mouse QA;
  September 12 OS acceptance is recorded below.
  Real saved-audio audition also passed after the change: cue 1.42568 s,
  paused/held exactly 1.6186042 s, non-silent output advanced 882 → 10584
  frames through mode switches, capture stayed at phase zero. State restored.
- [x] Repeat direct-record start → capture → save → audition for two passages
  with a known signal, beyond the verified native count-in/cancellation path.
  Preserve the user's newer takes and distinguish that evidence from earlier
  end-to-end recording checks of the old transport flow.
  `live-direct-record-capture-qa! [1 5]` sent a test-only 220/330 Hz signal to
  BlackHole, clicked the native Record/Stop/Listen controls and saved 1.04 s and
  1.06 s WAVs. Both passed native validation (peak 0.05141075) and replayed with
  non-silent output, capture phase zero. No physical microphone or game publish.
  Retained files `dbdf7e44-5552-4a2e-b9d3-eaf4c7938db2-dry.wav` and
  `f3b120aa-51e0-45c5-892b-bd0a989e49f0-dry.wav` in their passage recording
  directories; labelled `QA direct Record - 220/330 Hz test`. Revision 60 → 63
  includes both tests and guarded restoration of previous dry/wet/selected
  references. Original microphone/output/UI restored; worker remains running.
- [x] Implement the requested take-grid workspace: passage rows and Dry / FX /
  Favorite cards, shared take editor and transport/routing. Card selection and
  audition use validated commands with immutable snapshot/stale-click checks.
  Empty recording slots prepare Record mode without starting capture. Test mode
  transitions, REC restoration, API guards, paging/scrolling and native visuals.
  Native regression now passes 17 tests / 166 assertions; isolated API passes
  28 tests / 228 assertions. Grid visual/audition acceptance remains open.
  Follow-up: inspected the actual 2734×1672 Vulkan grid and fixed an older
  selected take missing from its Dry/FX column. `studio-takes-selected.png`
  now highlights the same "Retour aligné" shown by the editor. Takes Play/Space
  only audition; legacy arming is confined to Edit. Isolated API: 30 tests /
  252 assertions passed. Native card hit testing played/paused/resumed real audio
  (cue 1.42568 s, paused/held 1.5910417 s), kept capture at zero, and preserved
  project revision 63 across mode switches. September 12 follow-up passed live
  OS card playback, mode switching, pixel-scroll, empty-card preparation and
  minimum-size visual checks; see evidence below. This does not close the wider
  Edit/Record interaction and visual-polish checklist.

### September 12 — live desktop acceptance

- Inspected fresh WindowServer screenshots of Studio window 131628, not just
  GPU readbacks. `studio-takes-os.png` and `studio-takes-os-playing.png` show
  the selected FX card matching the editor, its Play changing to Pause, the
  transport advancing and the output meter responding. Native state confirmed
  actual playback at 0.55125 s with no recording. Stop rewound to zero.
- OS clicks switched Record/Takes. A complete pixel-scroll gesture moved the
  grid from offset 0 to 8 and revealed passages 9–12; inspected
  `studio-takes-os-scrolled.png`. These are injected OS mouse/trackpad-style
  events, not a claim of human-finger or pinch-gesture testing. Some initial
  events did not arrive; no handler failure was reproduced once delivery was
  confirmed by native input/state changes. Do not count those dropped attempts
  as successful tests or infer an unverified cause.
- Clicked the empty Dry card for passage 12: it selected
  `voice-b1d0c354a43f2401` and opened Record, with no countdown/capture and
  project revision 63 unchanged. `studio-empty-card-record.png` shows the exact
  passage, Record new take, and disabled audition for the empty take.
- Resized the real Studio to 1100×760 points and inspected
  `studio-takes-minimum-os.png` (2200×1576 including title bar). Cards, transport,
  routing and editor controls fit without overlapping. Restored 1367×836.
- In Record, OS clicks selected passages 2 and 6 in turn, then pressed Record
  new take with a three-second count-in. Each selected ID was correct; Stop
  cancelled both before capture. Restored count-in zero, passage 4, Record mode,
  offset zero, original window size and idle transport. No physical microphone
  capture, new take, game publication or project revision change in this pass.
- Worker remains running; historical failure count 36 did not increase. The
  earlier saved-signal recording QA and latest 18-test/166-assertion native and
  30-test/252-assertion isolated API runs remain the regression evidence.

## Continuing implementation checklist
- [x] Fix Edit-row audition resuming through the REC-enabled global transport.
  Route its triangle through the dedicated audition command; test Play/Pause/
  Resume with REC enabled and prove capture stays idle and recordings unchanged.
  Also preserve an already-selected passage's positioned cue. The old reset let
  a weak test wait until audio caught up; the regression now explicitly checks
  retained seek position. Both actual-audio/native-hit paths (`:edit-row` and
  `:record-button`) retain 0.98554164 s, pause exactly for 180 ms and resume
  non-silent output. Edit paused at 1.1784792 s; Record at 1.1692916 s. Capture
  phase stays zero, both windows advance, and project revision 59 is unchanged.
- [x] Clarify actual playback state and the armed recording target. Playing or
  paused audio must take precedence over REC-enabled status. Record mode must
  identify the armed passage even when a different (possibly text-only) passage
  is selected. Preserve explicit arming and inspect the GPU-rendered result.
  Added pure state labels/guidance and bounds-checked arm identification. Native
  regression: 16 tests / 146 assertions, no failures/errors. Inspected actual
  playing/paused GPU frames and the different-selected/armed case at 2200×1520:
  `studio-audition-playing.png`, `studio-audition-paused.png`, and
  `studio-armed-target.png`. No microphone capture or publication.
  Isolated command/API suite rerun: 27 tests / 208 assertions, no failures/errors.
  A transient desktop session also produced fresh matching OS screenshots and
  delivered passage selection; audition clicks did not arrive reliably and
  focus returned to loginwindow. Do not mark physical playback acceptance done.
- [x] Finish opt-in Vulkan color-attachment capture and compare Edit/Record/game
  pixels against native state. Use transfer-source support checks, image/host
  barriers, coherent readback memory and a completed GPU fence; release scratch
  resources after each capture. No recording or project publication. Capture QA
  also exposed duplicate raw `build_frame` exports when both app callbacks are
  referenced: callbacks need C calling convention, not duplicate linker exports.
  Corrected declarations compile together and both renderers remain independent.
  `capture-window-qa!` replays the most recent normal frame's mapped vertices
  through the actual GPU pipeline, without re-entering app drawing/input. Native
  regression: 15 tests / 112 assertions, no failures/errors. Record, Edit and game
  PNGs at 2200×1520 match native state (Record 13,407 vertices, Edit 20,652).
  BGRA/RGBA decoding is explicit; all capture scratch was released. This verifies
  current rendering/publication, not physical input or macOS composition.
- [x] Keep the Studio command/display worker alive across a failed dev edit.
  Live audition QA found that `take-action!` was outside the error boundary:
  an earlier Zig compile failure terminated its future while the windows kept
  rendering. Move the whole iteration into a guarded cycle; retain native PCM
  and scheduled/active session ownership on compiler failures, report bounded
  health through `query` and native-independent `worker-status`, and reject new
  API commands when the worker is terminal.
  `restart-worker!` explicitly revives a stopped worker without reopening engines
  or media, and refuses to replace a live worker. Generic cleanup errors cannot
  escape the cycle; failed device cleanup cancels pending count-in, while
  interruption still stops the worker. Isolated API: 21 tests / 154
  assertions passed. Six injected compiler failures in the real worker left it
  alive and recovering to `:running`, with project revision 59 unchanged.
- [x] Exercise saved-take audition using actual native button hit testing and
  the real output engine, with global REC enabled. `live-audition-qa! 5` selected
  and loaded the passage, cued to 0.98554 s, played, paused at 1.18767 s, held that
  exact cursor for 180 ms, resumed, switched Edit→Record during playback, and
  stopped/unloaded/rewound. Non-silent output frames advanced 1323→10584; both
  renderers advanced. No microphone, capture, publication or project edit.
  State/engine ownership was restored. This is queued native input, not OS mouse
  acceptance. An earlier cold observation exceeded the test's 500 ms cue-proximity
  bound; investigate cold-start observation/latency separately from the warm pass.
- [x] Obtain fresh GPU-frame evidence before closing visual/publication QA.
  macOS remains locked. Window captures can show the old Edit selection and an
  already-removed heading while native state reports Record and another passage;
  some waveform/alert pixels do update. Do not assume this is only WindowServer
  caching or only a publication bug. Renderer-side captures now match the normal
  frame's native mode, selection, microphone route and updated rounded controls;
  the removed overlapping heading is absent. The separate game's Markdown text
  and choices render correctly. Physical input/composition QA remains open below.
- [x] Fix the frame-budget failure found during this visual pass. Queued Edit
  input hit the checked 16,384-vertex limit after adding rounded controls and
  closed the idle JVM (no recording active). Allocate enough for 32 visible
  waveforms, labels, controls and the device picker; stress-test dense layouts,
  then restart both windows with the original project and routing.
  The shared allocation/atlas-offset/writer limit is now 131,072 vertices
  (8 MiB of vertex storage per window). Six scratch-frame layouts cover Edit,
  Script, Record, 32 visible waveforms and the device picker up to 2560×2160;
  the largest measured frame used 70,152 vertices. Both independent renderers
  are running again. Project revision 59 and all real WAVs are unchanged.
- [x] Recheck apparent partial hot publication after extracting private draw helpers:
  generation 40 reported success while the view retained its old implementation.
  Full module publication (41) appeared to apply the visible change. Later QA
  established that the locked desktop returns stale window content: native mode
  changed Edit→Record and vertices changed 22,122→15,081, but the capture still
  showed Edit and old loading labels. This is NOT a confirmed hot-reload defect.
  Re-evaluated ONLY private `draw-record-script!` through nREPL, changing its
  heading to `PRIVATE HELPER LIVE QA`; the normal GPU frame showed that exact
  text (13,431 vertices). Re-evaluating the source form restored `DIALOGUE
  RECORDING` (13,407 vertices). Both captures were inspected. Only one declaration
  was evaluated; no namespace reload, explicit `recompile!` or restart was requested.
  Worker failures remained zero.
  This verifies current private-helper reload, not the ambiguous past event.
  Neither a registry generation nor a cached screenshot proves OS presentation.
- [x] Split oversized drawing functions into named UI responsibilities within
  `studio.clj`: toolbar, timeline, track rows, script, take editor and routing.
  Keep input order and audio/session ownership unchanged. Do not scatter these
  sections into new namespaces. `draw-edit-workspace!`, `draw-record-workspace!`
  and `draw!` now orchestrate private sections in that same namespace.
- [x] Expand packed bindings, sequential statements and complicated conditions;
  keep one binding pair per line and clearly separated branches. Verify the
  formatting preserves source forms, including native type metadata. The bulk
  formatting pass compared token structure; transport labels, availability and
  colors now use named local values instead of deeply nested call arguments.
- [x] Apply the same readability rules to the JVM command/API branches in
  `studio.clj`. Keep guarded execution separate from dispatch; extract mix-loop,
  clip, history, selection/arming, routing and take-edit helpers in this same
  namespace. Expand command schemas/mappings and completion handling. Group
  related host writes with `set-native-state!`, sharing ordered-expansion validation
  with `set-state!`; render-thread scheduling remains explicit. Isolated API:
  27 tests / 208 assertions passed, covering order, invalid-route non-mutation,
  row-zero arming, UTF-8 name bounds and history restoration. Live audition after
  the refactor held a paused cursor exactly at 1.1784792 s, resumed non-silent
  output, switched views, then stopped without recording. Revision 59 unchanged.
- [x] Add a small `set-state!` macro for related native state changes, with one
  field/value pair per line. Preserve sequential evaluation; document that it is
  not atomic and never replace cross-thread atomic operations. Test expansion,
  malformed input, evaluation order and actual native use. Eight expansion/error
  assertions pass, plus the native contract proves later assignments see earlier
  values. Use the existing Aguafria macro expansion mechanism, not a new engine.
- [x] Use keywords for callback parameter-name data (including `FrameBuilder`
  `:output`, `:frame-width`, `:frame-height`), not unresolved bare symbols. Verify
  identical Zig emission and the running renderer; retain actual type references
  such as `mesh/GpuVertex` as resolvable Vars. Emitter regression: 1 test / 2
  assertions; keyword and symbol spellings emit identical Zig parameter names.
- [x] Compile/publish the refactored native module and run targeted regressions.
  Latest native run: 14 tests / 114 assertions, no failures/errors (grouped state,
  cursor ownership, mode isolation, routing, pointer/layout, meters and dense
  frames). Isolated API suite: 16 tests / 129 assertions, no failures/errors.
  Native resize to 1427×881 and restore to 1100×760 preserve separate renderers.
- [ ] Inspect both Edit and Record in the actual native window after unlock,
  including mouse/trackpad input, take audition and final minimum/wide layouts.
  September 10 QA was blocked by `com.apple.loginwindow`; `studio-*-budget-fixed`
  captures contain stale content and MUST NOT be presented as fresh acceptance.
- [ ] Replace the still-rustic flat control-panel presentation with the selected
  light design: clear transport hierarchy, quieter utility controls, balanced
  script space and grouped routing. Inspect normal and minimum-size screenshots.
  First native pass implements outlined rounded controls, distinct active REC,
  light grouped routing/meter surfaces and subtle panel edges. An overlapping
  routing heading found in the initial frame was removed. Normal-frame GPU captures
  at 1100×760 and 1427×881 logical points now verify these changes, with bounds
  restored afterward. Finish interaction acceptance and polish rather than treating
  rounded controls alone as a completed product design.

Code conventions for subsequent work: keep related helpers in this namespace;
extract by responsibility, not by an arbitrary line limit. One binding or state
assignment pair per line; separate statements and nontrivial branches. Use
`set-state!` only for related ordered assignments, with explicit atomics left
visible. Callback parameter names are keyword data; type/function references
remain real Vars. Preserve metadata and verify emitted/native behavior.

## Current QA interruption

- [x] Fix the opt-in resize QA entry point: an nREPL call to the raw native
  helper bypassed the render queue and triggered Cocoa's main-thread assertion.
  Rename the raw helpers to private `*-native!` functions; public resize/maximize
  helpers now enqueue work and return a promise. No capture was active when the
  assertion closed the JVM. The temporary scroll-test prose was never saved to
  Markdown. Add a regression ensuring the public helpers only queue work.
- [x] Restart both native windows after that QA mistake. Project ID and revision
  59, selected take, microphone/send/return/output, and cursor 3.0135 s restored.
  Normal Markdown dialogue restored; no synthetic scroll text saved. Both
  public queued resize and physical Up-button interaction worked after restart.
- [x] Diagnose the window-focus crash: macOS asserted its main queue when the
  native GLFW focus function was called directly from an nREPL session thread.
  Route the public JVM focus helper through the render queue; retain a separate
  native callback for game F1, which already runs on that thread.
  API regression including queued focus: 8 tests / 61 assertions passed.
- [x] Re-run live focus and publication/playback acceptance when the desktop is
  unlocked. The latest locked-session captures are not fresh visual evidence.
  No publication occurred during this interrupted check; the existing published
  WAV is unchanged, with a verified backup in
  `build/recording-qa/publication.dtmM5z/original.wav`.
  Follow-up: desktop is unlocked; API and physical Publish-button/focus checks
  passed (details below). The mouse tests needed fresh window coordinates.

## Language

- [x] Use English for the studio/DAW interface, including controls, routing,
  help, errors and newly generated history labels. Keep Markdown dialogue,
  character names and existing user take/profile names unchanged.
- [ ] Verify English UI at native size, including device menus, recording states
  and actionable warnings. The DAW must not assume its content is French.
  Main workspace, input menu, REC READY state and missing-arm warning inspected
  in `build/recording-qa/studio-english*.png`. Existing take names were preserved.
  English recording guidance + level tests: 2 tests / 21 assertions passed.
  API regression: 7 tests / 57 assertions passed. No microphone capture was started
  for the missing-arm test; restored REC off and dismissed the warning.

## Next: dialogue navigation

- [x] Disable background music entirely for now, including startup/hot reload;
  keep recorded dialogue and studio playback enabled. Preserve audio files.
- [x] At the end of a choice branch, show the nearest enclosing Markdown choices
  alongside the response, without requiring Backspace or inventing choices.
- [x] Dim already visited choices slightly; keep them readable and selectable.
- [ ] Verify nested branches, leaf responses, revisiting, choice pagination and
  Markdown hot reload. Inspect the actual game window, not only return values.

## Next: studio recording interaction

- [x] Research clear, simple DAW interfaces and generate five distinct polished
  UI concepts (user request). Compare recording discoverability, dialogue focus,
  routing clarity, editing efficiency, keyboard/API control and resize behavior.
  Saved five images, exact prompts, references and the user's design decision in
  `tools/design/2026-09-09-daw-concepts/`. Concepts are design references, not
  evidence of implemented functionality.
- [ ] Complete acceptance of the light native Edit / Record workspaces: shared
  transport/routing, sans-serif controls, serif dialogue, clean transport icons.
  First screenshots inspected; finish physical switching, long-text scrolling,
  playback/recording continuity and minimum-size checks.
  - [x] 14 targeted tests / 93 assertions passed: mode/input isolation, pointer
    navigation, divider bounds, Unicode editing, font metrics/gutters and API.
  - [x] Physical Edit/Record tab clicks and Record-mode "Listen to take" work.
    Switched to Edit during real playback: PCM cursor 0.5145→1.7364 seconds,
    output signal frames 24255→82908, same selected take and project revision 36.
    Restored the user's paused cursor to 1.010625 seconds afterward.
  - [x] Inspected fresh `studio-edit-light-verified.png`,
    `studio-record-light-verified.png` and `studio-record-light-minimum.png`
    (1100×760). Full selected prose, transport, meters and take playback fit.
    Fixed stale nonzero idle meter bars; input/FX activity now follows actual
    devices and instantaneous levels, independently from held peak history.
  - [x] Verify requested Record-mode REC default: enable on entry, restore the
    previous value on exit (both on/off), respect manual toggles and repeated tab
    clicks. Never start/stop capture by changing a view. Keep explicit track arm,
    unambiguous Play / REC, and separate Listen to take audition.
    Native transition regression passed for both prior on/off, manual override and
    repeated mode selection. Physical Edit→Record→Edit verified off→on→off with
    no capture, cursor change or project mutation; inspected
    `studio-record-auto-ready.png`. Left Record visible, REC ready but not capturing.
    Actual capture switching passed using a synthetic signal through the real
    BlackHole/Bitwig route, below. Human microphone/headphone acceptance remains
    separate; no microphone recording was started by those synthetic checks.
  - [x] Actual Play/REC → count-in/capture → Stop → FX tail/save checks, including
    physical Edit/Record tab changes and resize during capture. Saved the same
    capture across 1427×881 → 1100×760 → 1283×844 and mode changes. A separate
    capture crossed native maximize (1728×1051) and restore (1427×881).
    Both renderer frame counters continued; inspected fresh count-in, recording
    and tail screenshots. Fixed misleading idle guidance during active capture,
    disabled arm/take/count-in controls, red capture waveform, and the custom
    count-in value label. Record mode no longer shows nonfunctional trim handles.
  - [x] Measured both saved real Bitwig returns: finite PCM, zero clipped samples,
    zero silent 10 ms blocks after dry startup (377 and 241 checked blocks).
    Dry durations 3.88/2.52 s, wet durations 4.96/3.61 s including FX tail;
    measured channel gain −0.0630957 and correlation approximately −1 for the
    configured inverting effect. Periodic alignment is NOT latency calibration.
    Retained all four QA WAVs, labelled their history entries, restored pre-QA
    dry/wet references with revision guards, and published nothing to the game.
    Evidence stems: `1662adf1-cbfd-4460-83df-a47ee3c0b83e` and
    `45028d6c-8aba-4eaa-a1a3-eb6d024c1cc5` in
    `build/recording/voice-173791faf357254b/`.
  - [x] Focused native regression after those fixes: 7 tests / 80 assertions,
    zero failures/errors. Also fixed fractional Record-list wheel accumulation
    and the return meter in the live-monitor callback (offline callback fixture;
    this is not yet a real headset-monitor verification).
  - [x] Fix and visually verify the long-script final line. Physical scrolling reached the
    bottom without changing selection, transport or project revision 59, but
    revealed a strict-boundary bug hiding the final line. Use inclusive bounds
    with subpixel float tolerance. After restart, verified the final marker
    "END OF SCROLL QA" at 1100×760 in
    `studio-record-long-script-boundary-fixed.png` using native scroll input;
    physical Up moved the pane by 100 points. Added boundary and queued-window
    tests: latest focused run 9 tests / 89 assertions, zero failures/errors.
  - [x] Diagnose the injected wheel delivery failure: phase-less pixel events
    were not delivered after a fresh JVM launch. The opt-in Swift QA driver now
    sends begin/change/end gesture phases. Four −3-pixel gestures reached the
    native callback and accumulated to list offset 1 plus fraction 1.2, without
    changing selection, transport or project revision.
  - [ ] Finish physical long-script wheel and real trackpad acceptance after
    unlock. September 12 phased OS scroll events reached the long fixture's
    final line at minimum size (evidence above). That completes injected OS
    wheel delivery, not human-operated trackpad or native pinch acceptance.
- [x] Add an optional take-grid workspace based on concept 5, reusing its clear
  icons throughout. Keep one shared session/engine/command worker; descriptors
  advertise only implemented modes. New workflows use validated commands and
  undo/history. Do not fragment the implementation into tiny namespaces.
- [ ] Add restrained Vulkan shader polish after interaction acceptance: smooth
  edges/icons, subtle panel/selection feedback, crisp text, reduced-motion support
  and measured Retina frame cost. Verify dev shader reload and release output.
- [ ] Complete studio resizing acceptance, following Bitwig's normal desktop-window
  behavior. Native resizing is now enabled in new AND already-open studio windows.
  Add adaptive timeline/editor space, draggable panel dividers and collapsible
  routing; keep transport accessible, fonts undistorted and pointer hit testing
  aligned. Persist window/panel geometry independently from the game. Verify
  narrow/wide sizes, Retina scaling, maximize/restore and live resize during
  playback/recording without audio interruption or Vulkan errors.
  Reference: [Bitwig window anatomy](https://www.bitwig.com/userguide/latest/anatomy_of_the_bitwig_studio_window/)
  distinguishes GUI scaling from native window controls.
  - [x] Check installed Bitwig by actually resizing its window: dragged from
    1472×888 to 1292×788 screen points, inspected both screenshots, then restored
    the original bounds. Text/control sizes stayed constant, the visible timeline
    narrowed, and scrollbars accommodated the smaller working area. Evidence:
    `build/recording-qa/bitwig-before-resize.png` and `bitwig-resized.png`.
    This was an idle-window layout comparison, not a recording continuity test.
  - [x] Implement Vulkan swapchain recreation before enabling the window flag:
    dynamic viewport/scissor, rebuilt depth/framebuffer/command resources,
    per-image presentation semaphores, deferred out-of-date/suboptimal recovery,
    and nonblocking zero-size handling. Device/atlas/application/audio state stays
    owned by its original instance. Live resizing verified below.
  - [ ] Complete resize acceptance beyond the renderer: adaptive layout and mouse
    coordinates, native drag-resize/maximize/minimize, recording continuity,
    display-scale transitions and validation/fault-injection coverage. Evaluate
    presentation-fence retirement (`swapchain_maintenance1`) where available;
    current base-Vulkan retirement uses device-idle, not presentation fences.
  - [x] Recheck current layouts using OS edge dragging and gestures (2026-09-12).
    Studio grew from 1100×760 to 1320×900 content points; inspected
    `/tmp/studio-resized-record-os.png`: more visible rows, reflowed dialogue,
    undistorted text, accessible transport/routing. Selected a recorded passage
    and started Listen using its relocated button, then dragged back to 1100×760
    during playback. Cursor advanced 0.422625 → 2.0120625 s, non-silent output
    frames 18,963 → 95,256, game and Studio each advanced 115 frames. Resize
    counter advanced 38 → 39. Inspected `/tmp/studio-resize-playing-os.png`:
    playback remained active and the restored controls/waveform aligned. This
    verifies continuity/state, not an audio-loopback dropout measurement.
    After resize, phased OS Option-scroll zoomed 30 → 26.607613 seconds with
    anchored start 1.8230124; horizontal scroll panned start to 6.08023, and
    list scroll changed offset 0 → 1 without changing selection/audio. Inspected
    `/tmp/studio-edit-gestures-os.png`. These are OS-injected trackpad-style
    gestures, not a claim of human-operated pinch testing. Restored window size,
    Record mode, timeline, list offset, follow preference and stopped transport;
    project revision 90 and all take entries unchanged. Multi-display, minimize,
    recording-time resize and fault-injection cases retain their own gates.
  - [x] Implement the first adaptive layout pass: grow timeline/waveform and text
    space, anchor routing controls right and lower editor controls bottom, preserve
    point-sized drawing, and use the same dimensions for pointer hit testing.
    Clamp text scrolling after reflow. Native regression: 5 tests / 67 assertions
    passed, including wide-window geometry and moved hit targets.
  - [x] Enable `GLFW_RESIZABLE` now (including the running window), per the user's
    explicit request. Native query returned true; an actual mouse edge drag
    changed 1100×760 content points to 1283×844. Inspected the fresh unlocked
    `studio-native-edge-resized.png`: text is not stretched; extra space expands
    timeline/editor. Further QA remains open, but does not disable the feature.
  - [x] Finish native maximize/restore, minimum-size and playback/recording resize
    acceptance. Earlier captures taken while locked remain invalid visual evidence.
    Minimum-size QA caught and fixed a real ordering bug: GLFW ignored platform
    size limits while RESIZABLE was still false. Enable resizing before installing
    limits. An actual edge drag now stops at 1100×760. Playback-resize QA passed:
    cursor 0.1194→0.6523 s, output signal advances, both windows render, four
    swapchain resizes, unchanged device/selection/project. Original paused cursor
    restored. Recording resize and maximize/restore subsequently passed using
    actual Bitwig-return capture (details above). Minimize/restore, multi-display
    transitions and renderer fault injection remain separate open checks.
  - [x] Implement the draggable horizontal track/editor divider, bounded to keep
    both panels usable; double-click resets its position. Persist the split and
    expose `:view/editor`. Visible track rows now adapt from 3 to 32, with cached
    waveform uploads only when the viewport or take data changes. Live viewport
    QA verified 9-row and 3-row snapshots and unchanged upload counts while idle.
    Actual mouse drag changed the split from 444 to 532 points (6 to 8 rows).
    Inspected fresh `build/recording-qa/studio-divider-resized.png`; controls,
    dialogue and the user's selected recording remain visible and aligned.
    Physical double-click reset restored 444 points / 6 rows. Focused native
    layout, pointer, swapchain, viewport, preferences and API regression passed:
    11 tests / 81 assertions, zero failures/errors. This is not a claim that all
    resize-during-recording or multi-display acceptance is complete.
  - [x] Verify native minimize/restore during playback (2026-09-12). Used the
    Studio title bar's macOS minimize button; did not minimize Codex. Explicit
    GLFW ICONIFIED snapshots confirmed true while hidden and false on restore.
    While hidden, Studio frame count stayed 19,747,797, game frames advanced
    19,882,162 → 19,882,172, cursor 1.4791875 → 1.5435 s, and non-silent output
    69,678 → 72,765 frames. Existing focus-window! restored the window; playback
    continued at 2.113125 s / 100,107 non-silent frames and Studio rendered again.
    Inspected `/tmp/studio-minimize-restored-os.png`: intact layout, active Pause
    controls and waveform cursor. Stopped afterward, no project/take changes.
    Added a native iconified-state QA accessor; initial REPL probes had helper
    errors and are not counted as minimized-state evidence. Cross-display scale
    transitions and minimizing during capture remain distinct open checks.
  - [x] Add a Hide/Show routing button and `:view/routing` API command. Hiding the
    panel reclaims 202 logical points for timeline/editor and shifts their hit
    regions; it does not alter audio devices, monitoring, selection or transport.
    Save visibility with independent window preferences. Native input-path and
    playback continuity checks passed; unlocked visual acceptance is still open.
  - [x] Add a 1100×760 content-point minimum and independent studio window
    preferences in ignored `build/studio-window.edn`. Debounce writes for 500 ms;
    retain normal bounds when maximized/minimized; clamp restoration to a current
    monitor work area including window decorations. Report invalid settings or a
    display too small for this layout instead of applying inaccessible bounds.
    Window-size limits are installed at creation and on the running window;
    native edge dragging passed. GUI scaling/smaller-screen layout remains
    separate work.

- [x] Give studio playback its own engine instance and selected output, independent
  of the game's engine. Shared miniaudio code is fine; shared runtime sound graph
  or output/mute state is not. Mix playback must also use the studio output.
- [x] Distinguish saved-take playback from live input monitoring. Use the
  `Live monitor` control; provide a visible output meter and output selector.
- [x] Add opt-in, bounded `Audition boost` for very quiet takes, without changing
  stored WAVs, effect processing or game publication. Expose it through the API.
- [ ] Diagnose low recording gain: latest user dry peak 0.0052648 (-45.6 dBFS),
  FX peak 0.0003322 (-69.6 dBFS). Audition boost is not a recording-gain fix.
  Actual input/return peak dBFS and an actionable low-level warning are now shown;
  the capture gain itself still needs attention.
- [x] Expose held input/return peak dBFS, distinguish idle from silence during
  capture, and keep warnings visible independently of hover tooltips.
- [x] Show `FX TAIL` after Stop while retaining the real effects tail; save when
  capture finishes. Expose phase 3 in the control API.

- [x] Follow Bitwig's documented Arranger sequence: select input, arm a track,
  enable global Record, then Play. Record enable alone must not open the microphone.
- [ ] Distinguish track selection, track arming, recording mode (dry/FX), global
  record enable, count-in, capture, effects tail and stopped states visibly.
- [x] Default to no count-in. If enabled, show a real decreasing countdown;
  cancel/error must clear it, and no captured clip should appear before capture.
- [x] Draw a growing recording clip, real waveform/meter and audio-clock playhead.
- [x] Keep microphone, send, return and headphone names visible; guard device
  lifecycle so tests cannot invalidate the open studio's device enumeration.
- [ ] Make routing straightforward: microphone -> send 1/2 -> Bitwig effects ->
  return 3/4 -> recorded take; distinguish configured from signal verified.
  Current signal state and explanatory tooltip are implemented and tested above.
  Configuring/running the actual external effects chain still needs the final
  end-to-end publication/interaction pass; no automatic effect-verification claim.
- [ ] Test Record/Play/Stop, arm/disarm, cancellation, missing input, audio failure,
  mouse and keyboard, device menus, disabled-state explanations and recovery.
- [x] Record a known signal through Bitwig effects from our UI; measure the return,
  replay the take and verify publication hot reloads in the real game.
  September 12 repeat passed after the transport changes: effect/bypass
  measurement, saved-take audition and guarded live-game publication/restoration
  are recorded at the top of this file. No QA tone remains published.
- [ ] Improve control hierarchy, spacing, recognizable icons and hit targets.
  Dear ImGui is allowed, not required; changing toolkit alone is not the fix.
- [x] Replace append-only take-name entry with a visible caret, mouse placement
  and drag selection, Shift/arrows, Home/End, Delete/Backspace and clipboard shortcuts.
  Respect the 120-byte API limit without splitting Unicode characters.
- [x] Add bounded local draft Undo/Redo, independent of project history. Paste is
  one operation; restore text/caret/selection, and clear redo after a new edit.
- [x] Verify long-name drag/autoscroll behavior in the native window; see the
  September 12 edge-scroll regression and OS held-drag evidence above.
- [x] Add composed-grapheme editing and verify native/OS accent interactions.
  See the Unicode 17.0 conformance and real-window evidence in the checkpoint.
- [ ] Extend text-entry acceptance to platform IME pre-edit/composition and
  general complex-script shaping. These are not implied by grapheme navigation.
- [x] Verify OS Option-scroll pointer-anchored zoom and Shift-scroll horizontal
  pan, without moving track rows or leaving a modifier stuck. Native pinch was
  subsequently implemented; see the September 12 native-queue evidence above.
  Physical pinch acceptance remains distinct from scrolling.

Manuals consulted: [Recording Clips](https://www.bitwig.com/userguide/latest/recording_clips/)
and [Transport](https://www.bitwig.com/userguide/latest/the_window_menus_transport_area/).
The initial dialogue recorder supports one armed passage, not full multitrack
Arranger/Launcher parity. Do not imply features that are not implemented.

## Existing fixes needing final visual/interaction acceptance

- [x] Verify the stable, single, pixel-aligned DAW playhead in motion.
  Closed by the final-width ScreenCaptureKit video and native cursor samples
  in the completion checkpoint above; not a 120-FPS presentation claim.
- [x] Verify the game is silent while studio is focused or recording, without
  muting studio monitoring/playback; preserve the game's manual mute choice.
  Closed by the actual voice gain/cursor + independent Studio PCM evidence
  above and the game-audio-focus-isolation regression; not acoustic measurement.
- [ ] Verify French glyphs have no stray dots in both windows at native scale.
- [ ] Verify text-only Markdown-driven game and UTF-8-safe typewriter reveal;
  Space/click reveals immediately. Art/animation is deferred by request.
- [x] Keep device menus modal: no click-through, clear selected input, Escape closes.
  Mouse selection, X and Escape through macOS System Events work. Raw CGEvent
  keyboard injection did not reach this Java-hosted GLFW window; that was not
  proof of a broken callback. Routing menus now also block underlying scrolling.
  Follow-up keyboard/mouse acceptance is recorded below.

## QA discipline

- Do not run audio-shutdown tests against an open user recording workspace.
- Record evidence and failures here; automated tests do not replace fresh screenshots
  and interaction. Do not say finished while any required behavior is still broken.
- Binding reload issue: reloading generated C bindings in the same JVM changed
  native alias identities. Current session was restarted; do not claim that library
  issue fixed. Avoid unnecessary C binding reloads during game/studio hot reload.

2026-09-09: existing fresh-session regressions passed 26 tests / 257 assertions.
Those tests shut down recorder enumeration while studio remained open; enumeration
was reinitialized afterward with no active capture. Prevent recurrence and verify names.

### 2026-09-09 follow-up evidence

- Live hot reload in JVM 64041, nREPL 54779. Both native windows remained running.
- 4 targeted native tests / 55 assertions passed: leaf/nested choices, UTF-8 reveal,
  glyph gutters and pixel-aligned cursor. API: 7 tests / 54 assertions passed.
- Fresh game capture: `build/recording-qa/dialogue-leaf-final.png`.
  La Mangue response remains visible with the two enclosing Markdown choices.
- Physical mouse: armed track 4, enabled REC without capture, selected FX and
  pressed Play. Growing red clip and returned waveform inspected in
  `build/recording-qa/fx-return-recording.png`. Stop saved paired WAVs.
- A startup test exposed a direct Clojure call to a Zig extern clock; corrected
  to a native `begin-countdown-clock!` wrapper and repeated successfully.
- Bitwig was using Aggregate Device with track output "Not configured". Set it
  to BlackHole 16ch at 48 kHz; existing track routes then resolved to 1/2 -> 3/4.
  No Bitwig project Save or export was needed to record through its active Tool FX.
- Paired take `833dc9ef-ad0e-4e39-8776-943a1669ec99` under
  `build/recording/voice-173791faf357254b/`: 891360 dry frames, 943200 wet frames;
  finite samples, zero clipping, gain -0.063095736 on both channels and correlation
  -0.999999999999998 against the known signal. Periodic-signal alignment is NOT
  a latency calibration. Take labeled `QA FX retour - 9 sept`; older takes preserved.
- Earlier silent-return QA take also retained, not published or called successful.
- Physical mouse count-in cancellation: capture showed 2.6 s remaining, then Stop
  returned to idle with no recording/session. No actual microphone was opened by
  this cancellation test. Missing armed track returns `:no-armed-track`.
- Selected MacBook Pro Microphone through the visible menu. Final state: global
  REC off, no armed track, no capture/countdown, count-in 0, quiet QA generator off.
- Remaining: broader DAW editing/interaction polish, complete repeat playback and
  game publication QA, automatic silent-return warnings and explicit effects-tail
  status. The DAW is not declared complete or equivalent to Bitwig.

### Independent playback verification

- 2026-09-09: fixed saved-take previews using the game's default engine despite the
  studio output selection. Dedicated engine/device instances now route to the
  chosen studio output. Device selector defaults away from the BlackHole send bus.
- Native inspection: different game/studio engine addresses and device pointers;
  studio device actually reports `MacBook Pro Speakers` (not just a UI label).
- Mix device also opened successfully on `MacBook Pro Speakers`, inspected while
  paused and closed afterward without playing QA media. Selected takes preserved.
- Physical track Play produced nonzero output (peak 0.11799); the selected quiet
  take used explicitly enabled audition gain 602.0732. Source media unchanged.
- Game focus/mute isolation passed while playback continued: cursor 0.55125 ->
  0.8176875 seconds and signal frames 25137 -> 37485. 8 assertions passed.
- Physical Pause held 0.69825 seconds across a 200 ms observation; Resume and Stop
  worked, ending at 0 with no active recording/countdown.
- Audition-gain bounds/opt-in and focus tests: 2 tests / 13 assertions passed.
  API suite: 7 tests / 56 assertions passed. Fresh inspected screenshot:
  `build/recording-qa/studio-independent-playback.png`.
- At this stage raw keyboard injection remained unresolved; the later System
  Events tests below distinguish that injection issue from app behavior.

### Levels, recording errors and effects-tail verification

- 2026-09-09: physical Play with REC enabled and no armed track opened no
  microphone and displayed a persistent actionable warning. Inspected
  `build/recording-qa/studio-missing-arm-alert.png`.
- Physical Play/Stop through the live Bitwig route (quiet synthetic source on
  BlackHole 5/6, not the microphone) showed input -38 dBFS and return -62 dBFS.
  Inspected `studio-live-levels.png`, `studio-fx-tail.png`, and
  `studio-low-return-alert.png` under `build/recording-qa/`.
- Native/API phase changed 2 -> 3 -> 0. `FIN FX` and the return-tail description
  were visible while the clip continued to grow. Auto-save completed.
- QA take `8c9cecac-6898-4691-a4df-4f51ec784e0d`: dry 114240 frames, wet 262080;
  extra return 3.08 s for a configured 3 s tail (includes stream startup offset).
  File peaks 0.012303505 / 0.000776299 matched native meter values to ppm precision.
- QA take labelled `QA niveaux et fin FX - 9 sept`, retained and not published.
  Restored the user's pre-test selected take, MacBook microphone, speakers, REC
  off and 1 s tail. Synthetic generator stopped. No active recording/countdown.
- Warning close button worked physically. API also supports `:alert/dismiss`.
- New targeted regression tests: 2 tests / 22 assertions passed. API suite:
  7 tests / 57 assertions passed. Full destructive device suite was not run in
  the user's live studio. Wider DAW roadmap remains open.

### Keyboard and scrolling acceptance

- 2026-09-09: macOS System Events Escape closed the live routing menu (1 -> 0).
  Space toggled play/pause/resume; Escape stopped. The QA helper now uses that
  working OS keyboard path and retains `raw-key` only for diagnostics.
- OS horizontal pixel scrolling moved timeline start 0 -> 2.4 s. Vertical
  scrolling moved track offset 0 -> 2. With a routing menu open, the same scroll
  left both unchanged. Added a native modal-scroll regression.
- Option-scroll injection did not activate zoom; it scrolled rows instead.
  Investigate modifier delivery before claiming this physical gesture passed.
- QA mistake: directly calling the scroll callback with a null window terminated
  JVM 64041 in `glfwGetCursorPos`. Recording was idle. Added a null-window guard
  and regression. Restarted as JVM 72086 / nREPL 56543; both windows running.
  Project revision 31 and the selected take survived; the passage retains all
  27 takes. Restored microphone, FX mode and selected speaker output.
- Four targeted native input/navigation tests passed 30 assertions, including
  null-window safety, modal scrolling, keyboard actions, trim bounds and scrollbars.
- Physical zoom button changed 30 -> 15 s. Mouse trim drag changed 0 -> 9%, then
  was dragged back; clicking the waveform sought to 2.851 s without playing or
  rewriting media. Physical Play produced cursor 0.322 s, output peak 0.002459
  and 14112 non-silent signal frames on selected output 1. Stop afterward.
- Fresh restart screenshot inspected: `build/recording-qa/studio-restarted-input-qa.png`.
  API suite passed 7 tests / 57 assertions; `git diff --check` passed. Final trim
  restored to 0–100%, timeline to 0–30 s, playback stopped and recording idle.
  Remaining input work: modified trackpad zoom acceptance, stronger text editing,
  and the broader routing/publication/game QA checklist above.

### Name-field interaction

- 2026-09-09: hot-reloaded caret, UTF-8 insertion/deletion, selection and clipboard
  editing in the same live studio (56543). No app restart needed.
- Physical Cmd+A / Cmd+V inserted `Prise été — voix`; Cmd+C matched it exactly.
  QA helper preserves every existing clipboard representation without printing it.
  Shift+Left / Backspace removed the final `x`; mouse drag selected bytes 0–8.
- Fresh screenshot inspected: `build/recording-qa/studio-name-selection.png`.
  Temporary name edits were not submitted to the project or written into a take.
- Six targeted native tests passed 35 assertions; API suite passed 7 tests / 57
  assertions. Tested insertion/deletion across accents and a four-byte emoji,
  replacing a selection at capacity, single-line paste and 119/120-byte load
  boundaries. Restored the original name draft and stopped preview after QA.

### Device selector acceptance

- Selected device is highlighted and explicitly labelled; arrow-key focus has
  a separate indicator. Up/Down, Home/End and Enter support keyboard selection.
  Empty lists explain reconnecting; pagination is disabled at list boundaries.
- Native navigation + modal scrolling tests: 2 tests / 21 assertions passed.
  Tests cover 19-entry paging, empty lists, busy guards, Enter confirmation and
  Space suppression. Test state restoration now uses native boolean helpers;
  an earlier harness attempt failed to restore the simulated device count, which
  was corrected and verified as four real inputs without reopening audio devices.
- Physical Down moved focus 1 -> 2 while microphone stayed 1; Up + Enter closed
  the menu with microphone 1 unchanged. Clicking outside over global REC closed
  the menu with REC still off. Scrolling behind the menu changed neither time
  view nor track offset. Escape closed it afterward.
- Inspected `build/recording-qa/studio-selected-device.png`. The selected marker
  means configured, not signal verified. No capture or take mutation during QA.

### Publication and English UI follow-up

- 2026-09-09, JVM 75468 / nREPL 57187: confirmed live process and unlocked desktop.
  Inspected `studio-current.png` and `game-publication-after.png` under
  `build/recording-qa/`. Studio controls are English; dialogue remains French.
- Physical mouse selected La Mangue. Playback emitted nonzero output on device 1
  (MacBook Pro Speakers), peak 0.00855 with 227115 signal frames. This proves
  native output, not the listener's subjective loudness or corrected mic gain.
- Physical mouse selected the game's first choice. Parent changed 0 -> 2,
  voice node 3 loaded, revision 1 and PCM cursor advanced. Inspected the response
  with both enclosing choices present and the visited first choice dimmed.
- Published the selected aligned wet take through `publish!`, the shared API/UI
  handler. Game revision 1 -> 2, FNV hash 10991937155690257287 ->
  7346268948099316281, cursor advanced to 273731 frames without app restart.
  Published SHA256 matched the selected take (`786c2231…cff19`).
- Restored the prior published WAV and only its project publication pointer after
  validating both original/backup and temporary target hashes. Game revision
  advanced to 3 with the original hash. Restored SHA256 `16d33eaa…03f` verified;
  selected take and all source recordings retained. Project revision 31 -> 33
  records publication and QA restoration explicitly. No microphone capture.
- Physical Publish-button attempts did not establish reliable focus/hit evidence;
  do not count the API success as that interaction test. Final studio transport
  stopped, global REC off, no armed passage/countdown/recording.
- Added resizable/adaptive windows to the required checklist and plan. Inspected
  Bitwig's native window controls (zoom/fullscreen available) and its official
  window/GUI-scaling documentation; actual drag-resize comparison remains open.

### Physical publication and local draft undo

- Fresh bounds explained the failed mouse attempts: studio moved from (343,140)
  to (191,79), so old coordinates landed outside it. Native focus stayed true
  both immediately and after 200 ms; game suppression followed correctly.
- Physical Publish click succeeded: game voice revision 3 -> 4, selected wet
  hash loaded, game volume 0 while studio focused. Physical Play advanced
  0.40425 -> 0.6155625 s; signal frames 18081 -> 28224 on speaker output 1.
  Physical Pause held exactly 2.205 s, Resume/Stop finished at zero.
- Inspected `studio-physical-publish.png` and captured
  `studio-physical-play-english.png` in `build/recording-qa/`.
  Original publication restored with hash validation, as in the preceding check;
  all source recordings retained. Project journal records QA and restoration.
- Hot-reloaded draft Undo/Redo in JVM 75468. Native contract passed all 13
  checkpoints: UTF-8/selection restoration, atomic paste, emoji deletion,
  redo invalidation, 32-edit bounds, new-draft reset, busy guard and no project
  command leakage. API suite passed 8 tests / 61 assertions.
- Physical Cmd+A/paste `Take café — voice`, Cmd+Z and Cmd+Shift+Z restored the
  original and temporary text respectively. Project revision unchanged.
  Inspected `studio-name-redo.png`; temporary draft restored, never submitted.

### Modified scrolling acceptance

- System Events `key down option` left GLFW's held modifier mask at zero, despite
  the studio being focused. This was an injection problem, not evidence of a
  broken zoom handler. Corrected the QA helper to send Cocoa `flagsChanged`
  events for held Option/Shift, matching GLFW's input path. Release is deferred.
- OS Option-scroll moved the view from 0–30 s to start 8.040899 s / span
  13.9182005 s. Pointer anchor stayed at 14.99999952 s (expected 15); track offset
  remained 6. Reverse scroll restored 30 s within floating-point precision.
- OS Shift-scroll panned start to 0.48000187 s without changing span or track
  offset. Native modifier mask was zero after release. Inspected
  `build/recording-qa/studio-option-scroll-zoom.png`.
- Added native modified-scroll assertions. Pointer/scrollbar regression:
  2 tests / 23 assertions passed. No recordings, take edits or publication changes.
  This is OS event-injection acceptance, not a claim of native pinch support.

### Resize renderer foundation (2026-09-09)

- Implemented resize recovery in `gpu.clj`. Swapchain-dependent resources are
  recreated without rebuilding the device or mapped atlas. Viewport/scissor are
  dynamic; presentation semaphores are indexed by acquired image, not frame slot.
  Out-of-date acquisition returns before fence reset; suboptimal acquisition is
  submitted/presented before deferred recreation. Zero extents skip rendering.
- Native extent/result contract and concurrent-bootstrap regression: 2 tests /
  6 assertions passed. Seven native checkpoints cover fixed/clamped/zero extents
  and resize versus device-loss result classification.
- Live studio sizes in screen points: 1300×800, 1200×820, 1400×900, 1150×780,
  1250×850, 1100×760. Retina framebuffer sizes matched every requested size.
  Ten live assertions passed: both frame counters increased, engines/resources
  remained independent, playback stayed active (0.8728 → 1.5986 s), and output
  signal frames increased (40,572 → 75,411). Project revision stayed 35 and the
  selected passage stayed `voice-173791faf357254b`. No take was edited/published.
- Resized the game independently to 1250×800 and restored 1100×760. Studio
  remained 1100×760. Shader reload succeeded after resizing; both windows kept
  rendering. Inspected `studio-after-resize-recovery.png` and
  `game-after-resize-recovery.png` in `build/recording-qa/`.
- Startup/test loading exposed concurrent `load-native!` calls: fixed its
  check/prepare/import/publication sequence with a lock. Regression checks twelve
  concurrent callers initialize once, subsequent calls do not re-import, and
  failed preparation leaves initialization retryable. Cross-process asset
  preparation is still not serialized; don't run two preparers simultaneously.
- These were programmatic real-window resizes and output-callback measurements,
  not recording/dropout or native drag-layout acceptance. `GLFW_RESIZABLE` stays
  disabled until adaptive layout/hit testing is ready. The full DAW remains open.
- References: [Khronos resize tutorial](https://docs.vulkan.org/tutorial/latest/03_Drawing_a_triangle/04_Swap_chain_recreation.html)
  and [presentation semaphore lifetime](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html).

### Adaptive studio layout implementation (2026-09-09)

- Studio canvas dimensions now follow logical window dimensions, independently
  from the game's canvas. Extra width expands timeline, waveform and text; routing
  remains fixed-width. Extra height expands the focused text area and moves the
  lower editing controls. This pass retains six visible track rows.
- Matching time/trim mapping, cursor-centered zoom, popups and routing/name hit
  targets use those dimensions. Text scroll offsets are clamped after reflow.
- Hot-loaded the implementation into the running native studio. Targeted native
  checks passed: 5 tests / 67 assertions, no failures/errors. No audio-device
  routing tests were run against the open recording workspace.
- macOS reports the session locked. `studio-adaptive-*.png` captures were cached
  window images; discard them as visual acceptance evidence. Resume real resize,
  hover/click, text readability and playback/recording checks after unlock. Do not
  report resize or the whole DAW finished on the basis of these unit checks.

### Window preferences and minimum-size work (2026-09-09)

- Added GLFW screen-point bounds/work-area queries and window-size limits. The
  control plane saves only normal studio bounds, separately from project/audio
  state. Writes are atomic, serialized and debounced; persistent write errors do
  not repeatedly hammer the disk or enter the recording stop-on-error path.
- Preference regression: 2 tests / 23 assertions passed. Coverage includes
  negative monitor coordinates, removed monitors, oversized/off-screen bounds,
  invalid settings, minimum size, debounce, forced flush, minimized/maximized
  bounds preservation, and explicit retry after a failed preferences write.
- Live native save/restore cycle passed: saved x=200/y=100, 1350×860; changed to
  1100×760; restored exactly to saved bounds. Finally restored and saved original
  x=343/y=168, 1100×760. Current monitor work area was 1728×1079 at x=0/y=38;
  GLFW reported a 28-point studio title bar. No recordings were edited/published.
- Desktop remains locked. These are native geometry/file tests, not proof of
  readable resized pixels or successful mouse-driven resizing. The resize flag
  stays disabled pending that acceptance; panel dividers/collapse remain open.

### Collapsible routing panel (2026-09-09)

- Added a persistent Hide/Show routing button, separate window-edge/content-edge
  coordinates, and `:view/routing {:visible boolean}`. Timeline/waveform, script
  text, scrollbar and lower editor controls share the expanded content bounds.
  Closing the panel dismisses its popup and stale drag state, without changing
  device selections or audio. Layout-only actions remain available through
  the API during capture; recording-continuity acceptance remains open.
- Native layout tests cover shown/hidden states at 1100 and 1400 points, hit
  regions, centered zoom, popup dismissal and unchanged audio/selection fields.
  Preferences preserve panel visibility, with debounced panel-only changes.
  Final targeted regression: 9 tests / 101 assertions passed, no failures/errors.
- Actual native UI hit-path test toggled four times during saved-take playback.
  Cursor advanced 0.1194375 → 0.753375 s; non-silent output frames 4,410 → 34,839.
  Playback remained active at every sample, selected passage and headphones
  stayed unchanged. Stopped playback and restored visible routing afterwards.
  Reusable opt-in check: `st/live-routing-playback-qa!` (client/REPL thread only).
- A malformed inline QA expression hung the evaluation client, not either app;
  verified the live JVM/worker and stopped only that client (PID 82457). Replaced
  it with the named, bounded QA function above. No app restart was needed.
- Desktop is still locked: internal native hit-path checks do not substitute for
  real mouse/trackpad and fresh pixel inspection. Draggable dividers remain next.
