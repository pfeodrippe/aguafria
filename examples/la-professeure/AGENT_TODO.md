# La Professeure — active implementation and QA

This is the working checklist, not a completion claim. Keep game and studio in
separate windows. Preserve recordings, Markdown IDs and existing user edits.
The wider DAW roadmap is in `tools/DAW_IMPLEMENTATION_PLAN.md`.

## Current QA interruption

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
    Actual switching during a microphone capture remains in broader acceptance.
- [ ] Add an optional take-grid workspace based on concept 5, reusing its clear
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
  - [ ] Finish native maximize/restore, minimum-size and playback/recording resize
    acceptance. Earlier captures taken while locked remain invalid visual evidence.
    Minimum-size QA caught and fixed a real ordering bug: GLFW ignored platform
    size limits while RESIZABLE was still false. Enable resizing before installing
    limits. An actual edge drag now stops at 1100×760. Playback-resize QA passed:
    cursor 0.1194→0.6523 s, output signal advances, both windows render, four
    swapchain resizes, unchanged device/selection/project. Original paused cursor
    restored. Recording resize and maximize/restore remain separate checks.
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
- [ ] Test Record/Play/Stop, arm/disarm, cancellation, missing input, audio failure,
  mouse and keyboard, device menus, disabled-state explanations and recovery.
- [ ] Record a known signal through Bitwig effects from our UI; measure the return,
  replay the take and verify publication hot reloads in the real game.
  Recording and measurement passed below; repeat publication/replay acceptance
  after the transport changes. Do not publish silence or QA tones unintentionally.
- [ ] Improve control hierarchy, spacing, recognizable icons and hit targets.
  Dear ImGui is allowed, not required; changing toolkit alone is not the fix.
- [x] Replace append-only take-name entry with a visible caret, mouse placement
  and drag selection, Shift/arrows, Home/End, Delete/Backspace and clipboard shortcuts.
  Respect the 120-byte API limit without splitting Unicode characters.
- [x] Add bounded local draft Undo/Redo, independent of project history. Paste is
  one operation; restore text/caret/selection, and clear redo after a new edit.
- [ ] Extend text-entry acceptance: IME/composed graphemes and
  long-name drag/autoscroll behavior. These are not implied by codepoint editing.
- [x] Verify OS Option-scroll pointer-anchored zoom and Shift-scroll horizontal
  pan, without moving track rows or leaving a modifier stuck. Native pinch is
  still a separate, unimplemented gesture; do not conflate it with scrolling.

Manuals consulted: [Recording Clips](https://www.bitwig.com/userguide/latest/recording_clips/)
and [Transport](https://www.bitwig.com/userguide/latest/the_window_menus_transport_area/).
The initial dialogue recorder supports one armed passage, not full multitrack
Arranger/Launcher parity. Do not imply features that are not implemented.

## Existing fixes needing final visual/interaction acceptance

- [ ] Verify the stable, single, pixel-aligned DAW playhead in motion.
- [ ] Verify the game is silent while studio is focused or recording, without
  muting studio monitoring/playback; preserve the game's manual mute choice.
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
