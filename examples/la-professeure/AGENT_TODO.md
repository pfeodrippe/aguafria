# La Professeure — active implementation and QA

This is the working checklist, not a completion claim. Keep game and studio in
separate windows. Preserve recordings, Markdown IDs and existing user edits.
The wider DAW roadmap is in `tools/DAW_IMPLEMENTATION_PLAN.md`.

## Next: dialogue navigation

- [x] Disable background music entirely for now, including startup/hot reload;
  keep recorded dialogue and studio playback enabled. Preserve audio files.
- [x] At the end of a choice branch, show the nearest enclosing Markdown choices
  alongside the response, without requiring Backspace or inventing choices.
- [x] Dim already visited choices slightly; keep them readable and selectable.
- [ ] Verify nested branches, leaf responses, revisiting, choice pagination and
  Markdown hot reload. Inspect the actual game window, not only return values.

## Next: studio recording interaction

- [x] Give studio playback its own engine instance and selected output, independent
  of the game's engine. Shared miniaudio code is fine; shared runtime sound graph
  or output/mute state is not. Mix playback must also use the studio output.
- [x] Distinguish saved-take playback from live input monitoring. Rename the latter
  to `Retour direct`; provide a visible output meter and `Sortie d'écoute` selector.
- [x] Add opt-in, bounded `Écoute amplifiée` for very quiet takes, without changing
  stored WAVs, effect processing or game publication. Expose it through the API.
- [ ] Diagnose low recording gain: latest user dry peak 0.0052648 (-45.6 dBFS),
  FX peak 0.0003322 (-69.6 dBFS). Audition boost is not a recording-gain fix.
  Show actual input/return dB levels and an actionable low-level warning.

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
- [ ] Keep device menus modal: no click-through, clear selected input, Escape closes.
  Mouse selection and X close work. OS-injected Escape did not reach the GLFW
  keyboard callback in the current Java-hosted window (instrumentation: zero
  callback events), even with reported focus. Rebinding the callback did not fix
  it; removed that speculative change. Diagnose keyboard delivery next rather
  than claiming that callback hot reload was the proven cause.

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
- Keyboard menu Escape remains unresolved; explicit zero modifier flags did not
  fix it. QA input helper now clears inherited modifiers for deterministic keys.
