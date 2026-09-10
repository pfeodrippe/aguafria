# An extensible native DAW for La Professeure

## Direction

Active issue-by-issue acceptance checklist: [`../AGENT_TODO.md`](../AGENT_TODO.md).

The DAW uses English for its interface and generated messages. Content language
is independent: dialogue, character names and existing take/profile names must
not be translated or rewritten when the interface changes.

### Selected visual direction and mode boundaries (2026-09-09)

The five generated references and the user's selection are recorded in
[`design/2026-09-09-daw-concepts/README.md`](design/2026-09-09-daw-concepts/README.md).
Use the light editing desk as the base, a script-first Record workspace, and the
last concept's clear transport icons. A take-grid view is a future mode, not a
promise of Bitwig-style clip scheduling. Keep the common transport/routing and
one recording session. Mode-specific actions may expand beyond presentation,
but must use the existing validated command/undo path. Native layout and input
dispatch stay explicit, in the studio namespace; no separate engine per mode.

Current native implementation: Edit/Record tabs and F2, API `:view/mode`, mode
discovery through `capabilities`, light surfaces, IBM Plex Sans controls, serif
dialogue, triangle/circle transport icons, and focused input/return meters. Idle
meters are gated by real device activity, not stale held peaks. Deferred shader
polish must preserve readable type and simple controls, with Retina performance
and dev/release checks—not add glow/noise or mask usability problems.

Record-mode workflow default: save the current global REC enable value on entry,
enable REC, and restore the saved value on exit. Re-selecting the current mode is
idempotent and respects manual toggles. This preference transition never opens
devices or interrupts capture/count-in/playback. Track arm remains explicit; the
transport says **Play / REC** when starting it will record. **Listen to take** is
an explicit audition path independent of global REC.

Acceptance checkpoint: 14 targeted tests / 93 assertions passed (mode/input
isolation, pointer navigation, divider bounds, font gutters/metrics, name editing,
playhead alignment and API validation). Physical clicks switched Edit/Record and
launched a saved take. Switching back to Edit kept playback running: PCM cursor
0.5145→1.7364 seconds and non-silent output frames 24255→82908. Project revision
36 and selected take were unchanged. Restored the user's paused cursor to
1.010625 seconds. Fresh light Edit/Record screenshots and the 1100×760 Record
layout were inspected in `build/recording-qa/studio-*-light-*.png`.
Capture-mode transitions, long-text scrolling, maximize/restore and recording
resize remain distinct open checks; this is not whole-DAW completion.

### Recording transport correction

Use the documented [Bitwig Arranger recording sequence](https://www.bitwig.com/userguide/latest/recording_clips/):
track input and arm, global Record enable, then Play. Record enable and capture
are separate states. The initial implementation arms one dialogue passage, uses
seconds (not a musical bar count-in), preserves old takes and makes a new take
on each capture. It is not full Arranger/Launcher parity. Default count-in is zero;
optional count-in must visibly decrease, and failure/cancellation must return to
idle. See [Bitwig transport](https://www.bitwig.com/userguide/latest/the_window_menus_transport_area/).

### Immediate usability corrections (2026-09-09)

- [x] Disable the accidentally retained 50 ms QA loop; sample the PCM cursor once per UI frame and draw one pixel-aligned mix playhead.
- [x] Silence only game sounds while studio has focus; preserve manual mute and protect active recordings across focus changes.
- [ ] Eliminate stray glyph-atlas marks in both windows, inspect French text at native scale.
- [ ] Make the game text-only, with UTF-8-safe typewriter reveal and an instant-reveal control. Keep Markdown authoritative.
- [ ] Make recording discoverable with the conventional red-circle icon and explicit device selectors for microphone, effects send, effects return and headphones.
- [ ] Verify input selection and the existing Bitwig processed-return recording path from the actual UI; distinguish routing configuration from a measured audio round-trip.

The focus/playhead regression tests passed 41 assertions. Actual window focus
transitions changed game dialogue/background gain from 0/0 in studio to 0.8/0.35
in the game; mixer playback continued independently. A fresh unlocked screenshot
(`build/recording-qa/daw-playhead-playing.png`) shows one continuous mix playhead.

### Required: simultaneous game and studio windows

Both windows must support ordinary native resizing independently. For the studio,
extra space expands the timeline and editor instead of stretching glyphs; panel
dividers and visibility controls allocate space at small sizes. Transport remains
reachable at a documented minimum size. Persist window bounds and panel sizes,
clamping restored bounds to available displays. Verify framebuffer/swapchain
recreation, pointer mapping and uninterrupted audio while resizing. GUI scaling is
a separate user preference, as in [Bitwig's window anatomy](https://www.bitwig.com/userguide/latest/anatomy_of_the_bitwig_studio_window/).
Studio native resizing is enabled, including the existing running window. Keep
the remaining acceptance checks explicit; they no longer gate enabling the flag.

Implemented foundation: independent logical studio canvas; expanding timeline,
waveform and text; matching pointer coordinates; swapchain recreation; a 1100×760
content-point minimum; and debounced normal-window bounds in ignored
`build/studio-window.edn`. Restoration fits a currently available monitor work area
including decorations. Invalid settings are reported without changing audio or
project data. Routing collapse is implemented and its visibility is saved; it
does not disconnect or mute audio. The track/editor divider is implemented with
fixed-height virtualized rows. An unlocked native edge drag and fresh screenshot
verified resizing from 1100×760 to 1283×844 without stretched text. Smaller-screen
layout/GUI scaling, maximize/restore and recording-continuity checks remain open.

The game and studio must be two independent native windows, not F1-switched
views. Keep the existing JVM and main/render thread, but give the studio its own
GLFW window, Vulkan resources, input callbacks and preview sound. Game animation,
dialogue and audio continue while editing. Share Markdown/Flecs passage identities
and publish completed assets through the existing live watchers.

- [x] Separate native window and renderer ownership; no replacement game view.
- [x] Independent input callbacks, transport and preview sound; closing/reopening the studio leaves the game running.
- [x] Capture and inspect both windows; verify simultaneous native frame progress and live audio publication.
- [ ] Repeat physical mouse/trackpad/focus QA with both windows after macOS is unlocked.

Verified 2026-09-09 in the existing JVM (nREPL 49339), without restarting the game:
separate GLFW handles, Vulkan devices/surfaces/mapped heaps; both frame counters
advance; studio pause holds 0.3215625 seconds while game audio advances from
82,026 to 101,871 PCM frames. The studio's injected click follows its real hit-test
and command worker: Publish created the selected validated wet WAV under its stable
passage ID, and the running game's voice revision changed 20 → 21 while studio
playback continued. This was existing test audio, not a new microphone recording.
Shader publication refreshed both pipelines (revision 0 → 2); atlas reload and
studio close/reopen retained valid separate renderers. Per-window screenshots were
inspected (`build/recording-qa/studio-separate-window.png`, `game-separate-window.png`).
After the final source reload, injected native window-close also hid the studio,
queued Stop (transport at zero, not playing), and reopening resumed studio frame
updates while the game continued. Final captures are `studio-separate-window-final.png`
and `game-separate-window-final.png` in the same QA directory.
The whole-display capture was black because the frontmost application was
`loginwindow`; unsuccessful OS clicks are not counted as passed interaction QA.
Per-window captures under lock can retain an earlier compositor image, so these
images verify separate layouts, not visual freshness after every subsequent edit.
Fresh unlocked visual/interaction QA remains open; native state assertions do not
replace it.

Regression results: mixer 29 assertions, native studio/take tests 117, control API
40; all passed. The compiler's binding-type metadata dependency fix passed 16
targeted assertions (including the existing metadata fingerprint checks). It keeps
`^{:var T}`, `:zig/type` and `:tag` dependencies in declaration-only hot slices.
The broader DAW roadmap below is still in progress, not declared complete.

Build a real audio workstation whose first demanding workflow is dialogue production.
The recording tool must grow into an arrangement, editing, mixing and automation
environment, not merely resemble one. Markdown dialogue and live game publication
are integrations with that workstation, not assumptions built into its audio engine.

The implementation remains native Aguafria Zig for rendering, input, audio processing,
playback and recording. Clojure in the existing development JVM manages project data,
commands, background jobs and adapters. No second JVM is required. A future standalone
tool can replace that control-plane host without changing the native engine contract.

Bitwig is an optional, separately running effects/control integration. Its native
devices are not an embeddable library offered by the documented controller API.
The current supported path is audio send/return plus acknowledged controller commands.
Do not promise arbitrary headless Bitwig rendering, an embedded Bitwig UI, or access
to every Bitwig internal. Our project must remain readable and useful without it.
The public controller documentation describes transport, tracks and device mappings,
not a replacement audio-engine SDK.[^controllers]

## What mature DAWs actually provide

An arranger and a launcher are different sequencers: one places events on a fixed
timeline, while the other triggers clips/scenes interactively. They need explicit
ownership when both can drive a track. Bitwig exposes separate track/global stopping
and return-to-arranger controls; launch buttons indicate active clips.[^launcher]
Our existing independent dialogue rows are neither a multitrack arranger nor a full
launcher. Keep that label honest until the scheduler below exists.

Transport has a playhead, a start marker and a loop region with different meanings.
Bitwig supports keyboard/global transport, marker placement, loop switching and loop
edge dragging.[^playback] These concepts should be engine state—not animations
calculated from the UI clock. Selection alone must not unexpectedly produce sound.

Arrangement interaction includes timeline navigation, adaptive ruler divisions,
selection tools, visible track controls and playback-follow behavior. Bitwig supports
keyboard zoom, modified scrolling, ruler dragging and trackpad gestures.[^arrange]
For our GLFW interface, implement two-axis scrolling and a documented modifier for
pointer-anchored zoom first. GLFW exposes scroll offsets for mouse wheels/touchpads;
its portable input API does not provide a general native pinch/magnification event.
Do not advertise pinch until a platform-specific bridge is implemented and tested.
Use callbacks so very short clicks/key presses cannot disappear between frames.[^glfw]

Routing is a patchbay, not a single input dropdown. Ableton's documented model includes
track sources/destinations, monitoring, MIDI routing, internal submixes and resampling.[^routing]
Our equivalent needs channel maps, pre/post-fader sends, return buses, a master and
a separate cue output. External effects add measured round-trip latency and drift
to the ordinary graph-latency problem.

Automation stores parameter changes on a timeline; manual overrides and the return
to automation must be explicit. Bitwig distinguishes track/clip automation and
modulation; Ableton documents automation recording, breakpoint editing and override
state.[^automation][^liveautomation] The design must distinguish base parameter value,
automation value and modulation contribution rather than overwrite one float.

Audio editing is non-destructive source-range manipulation with fades, gain and timing
operations, not repeated re-encoding after every drag. Bitwig's audio-event tools
include layered events, stretching and fades.[^audioevents] Our recordings remain
immutable, with clips referencing ranges; destructive rendering is an explicit job.

Device/plugin hosting is a separate subsystem. Bitwig offers multiple isolation
policies with resource/stability tradeoffs and visible crashed-plugin recovery.[^hosting]
CLAP supplies an audio-plugin ABI, not a complete host. Its process contract provides
buffers, frame counts, sample-time and ordered events; ports and latency have their
own lifecycle constraints.[^clap][^process][^ports][^latency] Native CLAP hosting needs
scanning, activation, parameter/state support, UI integration and crash isolation.

API control must cover ordinary editing and inspection, not a handful of hidden
developer functions. ReaScript demonstrates an extensive scripting surface including
actions, project/track/media access and undo integration.[^reascript] The recommendation
here is a discoverable, versioned command/query/event contract shared by the UI,
nREPL clients and future network/controller adapters. nREPL can host custom middleware;
it need not be replaced by a private protocol.[^nrepl]

## Capability map and implementation order

“Existing” below means inspected implementation, not production certification. Planned
features must not be represented by enabled decorative controls.

| Area | Required capabilities | Baseline | Delivery |
|---|---|---|---|
| Transport | Play/pause/resume/stop, rewind, seek, loop, precise position | Preview/stop only | P1–P2 |
| Navigation | Two-axis scroll, anchored zoom, scrollbars, follow, focus | Buttons; no wheel callbacks | P1 |
| Editing interaction | Select, multi-select, drag thresholds, snap, tool cursors, shortcuts | Single selection, percent trim | P1–P3 |
| Tracks | Audio/MIDI/group/return/master, stable IDs, ordering, color, arm | Dialogue rows, stable passage IDs | P2–P4 |
| Arrangement | Multiple clips per lane, overlap policy, source offset, fades, crossfades | Independent take rows | P2 |
| Clip launcher | Slots/scenes, launch quantization, stop/arranger ownership | None | P4 |
| Recording | Arm, monitoring, pre-roll, punch, loop recording, takes, comping | Dry/wet pair, countdown/tail | P1–P3 |
| Audio engine | Sample clock, bounded graph, scheduling, resampling, streaming | miniaudio capture/preview | P2 |
| Mixer | Gain/pan/mute/solo, groups, sends, returns, master/cue, meters | Fixed external send/return | P2–P3 |
| Audio editing | Trim/split/slip/duplicate/move, fades, clip gain, stretch | Trim-to-copy, A/B, preferred | P2–P3 |
| MIDI | I/O, timestamped notes/CC, piano roll, quantization, MPE | None | P4 |
| Tempo | Beat/sample mapping, signature map, tempo changes, count-in | Seconds only | P4 |
| Automation | Read/write/touch/latch, curves, gestures, override/re-enable | None | P3 |
| Modulation | Macros, nested racks, modulation routes, bounded evaluation | None | P5 |
| Devices | Bypass, parameters, preset/state, latency/tails, chain order | External Bitwig FX only | P3–P5 |
| Plugins | CLAP scan/load/process, state/UI, sandbox and crash recovery | None | P5 |
| External FX | Explicit maps, source/return meters, latency, reconnect, health | BlackHole 1/2 send, 3/4 return | P1–P3 |
| Browser | Media search/preview, import, tags, device/preset search | Take prev/next | P2–P3 |
| Project | Schema/migrations, relative media references, Save As, collect media | Takes/routing EDN | P1–P2 |
| Undo/redo | Transactions, gesture coalescing, redo invalidation, bounded history | None | P2 |
| Recovery | Autosave, PCM journal, missing-media relink, crash fault tests | Durable PCM prefix recovery | P2–P3 |
| Export | Mix/stems, ranges, sample rate/bit depth, tails, dither, metadata | Individual validated WAV publication | P3 |
| API | Discovery, query, command, completion/error, events, revisions, adapters | Public helper functions only | P1–P2 |
| Collaboration | Multiple clients, conflict handling, exclusive recording owner | None | P2 |
| Accessibility | Focus, keyboard navigation, descriptions, scaling, contrast | Mouse-driven serif UI | P1–P3 |
| Performance | RT deadline/xruns, graph load, UI latency, waveform caching | Some bounds, no DAW benchmark | Every phase |
| Game integration | Markdown IDs, voice mapping, live asset replacement | Implemented | Preserve |
| Animation/iPad | External asset bridge, change validation, live preview | Separate game infrastructure | Integrate, not audio core |
| Quality | Actual UI/audio tests, long sessions, device loss, corrupt assets | Native/offline + manual QA | Every phase |

The broader feature map is informed by the reference-manual organization of Live
and Bitwig; it is not a claim that either product exposes identical APIs or behaviors.[^live]
Export needs its own explicit workflow and media management needs missing-file and
collection operations, both covered separately by mature DAW documentation.[^export][^files]

## Architecture and ownership

```text
Native UI / nREPL / future CLI / controller adapter
               |
       versioned commands + queries
               |
  serialized control owner — project revision — undo history
       |                    |                 |
 native engine plan    media/job workers   adapter commands
       |                                      |
 sample-clock audio graph               Bitwig / other FX host
       |                                      |
       +---------- explicit audio I/O ---------+
               |
      bounded telemetry snapshots → UI / event clients
```

Use cohesive modules by responsibility, not a namespace per operation. Keep the
current `studio` as UI/control integration and `recorder` as capture while extracting
a real engine/project module only when its independent responsibility exists.
No dependency from this example to another game example.

### Project document

The target document has a schema version, project UUID and monotonic revision.
Stable track, clip, media, device and parameter IDs survive reordering. A dialogue
passage ID is metadata attached to a track/clip, never an array index used by an API.
Store editable project data separately from selection, window geometry, playhead,
meters and device handles. Do not serialize native pointers.

Audio sources are immutable assets with content identity, sample rate, channels and
length. Clips reference source frames plus timeline position, gain, fades and a
stretch mode. Recordings use project-relative paths; imported external media can be
collected explicitly. Missing files are visible and relinkable. Never silently switch
to another same-named input or take. Existing take indices need an explicit migration,
retaining their original files and recording IDs.

Transactions validate first, write a durable project revision, then publish a new
engine plan. Undo reverses project edits, not microphone capture or deleting originals.
A drag produces one undo transaction rather than hundreds. Completed recording is
an immutable media addition; undo can remove its clip reference while retaining audio.
Publication to the game and external host commands are side effects with acknowledgements,
not ordinary reversible edits. Keep them outside speculative undo replay.

### Native engine

Use one monotonic sample clock and deterministic block processing. Transport time,
clip evaluation, automation and loops use sample positions; display seconds are
derived. Preallocate bounded buffers/queues. No Clojure, disk I/O, mutex waits or
unbounded allocation inside audio callbacks. Decode/read ahead and compute waveform
levels on background workers; graph changes are validated and swapped at safe block
boundaries, with old resources released only after readers finish.

miniaudio already provides decoding, sound groups, node graphs, gain/pan and playback
facilities; reuse those where they fit instead of confusing a device library with a
finished DAW scheduler.[^miniaudio] Add our own native scheduling/mixing contract with
an offline render entry point so numerical tests run the same processing code.

Routes are typed channel connections. Reject accidental zero-delay cycles. Support
intentional feedback only through a bounded delay node and safe gain. Separate source,
external send, external return and headphone/cue routes. Report disconnected devices,
latency uncertainty, drift, clipping and dropouts. External return cannot automatically
be sent back to itself. Plugin delay compensation must handle parallel paths, changing
latency and render tails, not just a one-time waveform shift.

### Command/query/event API

Commands are data with an operation name, arguments, request ID and optional expected
project revision. Stable IDs identify targets. Return acceptance/completion/error
explicitly; acceptance of recording is not completion of its future audio capture.
Cancellation and timeout semantics must prevent blind retries from recording twice.
Use bounded queues and preserve a bounded deduplication/result window. No user-visible
numeric action codes; those may be internal UI messages only.

Queries expose capabilities, project snapshot, transport, routing, tasks and telemetry.
Event consumers use sequence/revision cursors, bounded history and a resync signal if
they fall behind. Never make audio processing wait on a client. A request must state
whether it changes project data, transport, devices or external applications.

The existing trusted nREPL is the first access path. Do not open a new network service
by default or expose arbitrary code evaluation to untrusted clients. Later add nREPL
operations and a CLI as thin adapters over the same commands. A future network-facing
deployment requires an explicit trust/security policy; local extensibility does not
mean unrestricted remote filesystem/audio access.

### Extensible devices and adapters

Device descriptors declare ports, parameter types/ranges/units, state version,
latency/tail behavior, RT requirements and supported execution modes. Native DSP,
external FX and plugins share that descriptor shape but do not fake capabilities they
lack. Unsupported operations return structured errors. Extensions register namespaced
commands/queries with schemas, not monkey-patched UI event handlers.

Bitwig adapter requests must target the explicitly managed project/track/device,
confirm identity, report observed state and acknowledge completion. The installed
controller API documentation also exposes track banks, transport, remote connections,
parameter setters and observers.[^localapi] UI automation is for integration setup/QA,
not the continuous control API. Bitwig Save is not our recording/export command.

## Interaction contract for the first implementation

1. Prominent play/pause, stop and rewind controls with geometric icons and text.
   Each recorded track gets an explicit launch button. Disabled controls explain why.
2. Space toggles play/pause, Home rewinds and Escape stops/cancels. Text-field focus
   consumes editing keys; typing a space must never start playback or recording.
3. Single-click selects/positions the marker without playing. Double-click or a play
   button auditions. Ruler/body dragging seeks; trimming starts only at visible handles.
4. Vertical wheel/two-finger movement scrolls the pane under the pointer. Horizontal
   movement pans time. Shift+vertical scroll pans; Option+vertical scroll zooms around
   the pointer. Buttons remain available. Native pinch is a separate future bridge.
5. Scrollbars and viewport indicators make hidden content discoverable. Follow-playback
   is explicit. Manual pan/zoom must not immediately be undone by camera-style following.
6. Hover help, selected/playing/paused/recording states, drag cursor and boundaries are
   visible. Controls never perform a different operation because their label was clipped.
7. Preserve full Markdown, stable IDs, real waveforms, A/B, non-destructive takes,
   guarded monitoring, recovery and publication. Never auto-open a microphone on navigation.

## Phases and acceptance gates

### P1 — Interaction and control foundation (verified)

- [x] Complete capability research and commit-ready Markdown roadmap.
- [x] Discoverable bounded command/query/result API used by the existing UI action worker.
- [x] Native play/pause/resume/seek/rewind state and per-track launch implementation; live PCM cursor checks pass.
- [x] Callback-driven mouse/key/scroll handling, focused text input, anchored zoom and pane scroll; native behavior tests pass.
- [x] Implement seek marker, trim handles, hover help and scroll/viewport controls.
- [x] UI/API equivalence tests; actual quick clicks, keys, scrolls and drags in the live window.

P1 is not a multitrack DAW completion claim. Exit requires a usable recording editor
and an extensible control surface with honest capability discovery.

#### P1 verification log — 2026-09-09

- Existing game JVM/nREPL 49339, no second JVM. Reloaded the native controls;
  explicit schema migration preserved the two transport flags while adding their
  host-visible Boolean type annotations. No recorded files were replaced.
- Native audio/editing/navigation/key-focus suite: 11 tests, 90 assertions passing.
  API validation/deduplication/queue/event/extension/capture-guard suite: 4 tests,
  32 assertions passing. These tests are in `studio_api_test.clj`
  and included in `:studio-test`; run independently of the render thread.
- Live selected 27.383625-second WAV: seek to 5 seconds; play advanced to 5.6155624;
  pause held at exactly 5.643125 in samples taken 600 ms apart; resume advanced to
  6.267875; Stop reported zero and not playing/paused. These are engine cursor
  readings, not UI animation timers. Audibility through physical headphones was
  not reverified during this pass.
- Native hit-test injection on the visible-layout Play coordinates reached the
  same command worker and emitted a UI command event. A waveform-body click
  positioned at 13.6918125 seconds while retaining trim 0–100%. These are injected
  native input checks, **not** a substitute for physical mouse/trackpad QA.
- Earlier visual blocker (resolved in the live verification below): macOS capture showed the “ChatGPT is using your Mac”
  privacy screen; capturing the Java game window returns an old game view even
  though the studio draw counter advances. Java is unavailable through the CUA
  app surface. Do not use those stale captures as evidence of the new UI. Finish
  quick-click, trackpad, drag, cursor and F1 round-trip QA on an accessible live
  desktop before marking P1 complete. Previous DAW-layout screenshots only
  establish the earlier baseline, not these new controls.
- The studio is left open, selected on `voice-173791faf357254b`, transport stopped,
  with no active recording. APIs can be exercised in the existing nREPL. Physical
  device defaults and the Bitwig project were not changed by this P1 pass.
- Still P2+: simultaneous tracks, versioned project/undo, editing/automation,
  mixer, MIDI, CLAP hosting and full DAW production hardening are not implemented.

#### P1 follow-up — 2026-09-09

- Fixed vertical scrollbar grab offset and inverse thumb mapping: grabbing the
  thumb preserves position, dragging reaches both limits, and lists shorter than
  the viewport remain at zero. Added native regression coverage.
- Name editing now shows an I-beam cursor and focus underline. Disabled launch
  controls distinguish an active recording from a passage without a take.
- Extension handlers cannot synchronously wait on their own command worker;
  `command!` returns a structured reentrancy error before enqueueing. Asynchronous
  `submit!` follow-up work remains available and is tested.
- Current regression totals: 12 native/editing tests with 95 assertions and
  5 API tests with 35 assertions, all passing. Studio reopened, transport stopped.
- At this point desktop access was still unverified: capture contained the studio but retained
  13.7 seconds while the live engine query reports zero. A 15 ms OS mouse click
  did not reach the app. Requested that the privacy screen be dismissed and the
  game brought forward; do not mark the physical-interaction gate complete yet.

#### P1 live verification — access restored, 2026-09-09

- Native CoreGraphics mouse/key/wheel events now reach the existing JVM's GLFW
  window. Fresh window captures agree with the audio cursor. These are actual OS
  event-path tests, not hand-operated trackpad hardware or a physical headphone
  listening test. The stale images above are not used as evidence.
- Short Play clicks, per-track launch, Space, Home and Escape work. Fixed a real
  defect: the per-track Pause icon previously relaunched the take; it now pauses.
  Its cursor held at 0.882 seconds, then Space resumed to 1.3965. Home rewound
  during playback; Escape stopped at zero. A separate pause held at exactly
  5.4134374 in two readings 400 ms apart.
- Single clip click positioned to 12.336449 seconds without playing; a double
  click launched the same take (0.312375 seconds and playing). Waveform-body click
  positioned at 13.6918125 without changing trim 9–100%. Edge dragging changed
  trim from 0–100% to 9–100% without writing or replacing any take.
- Wheel scrolling moved track offset 1 to 4 and back. Option-wheel zoom changed
  15 seconds to 11.799418 while preserving 7.5 seconds under the pointer. Manual
  horizontal pan retained zoom and disabled follow. Vertical thumb dragging
  reached both offsets 6 and 0 on the loaded 12-passage document.
- OS Unicode input appended ` été`; Space/Backspace/Escape in the name field did
  not operate transport or recording. The edit was not saved. F1 switched to the
  real Markdown-driven game and back; both rendered views were captured and
  inspected. Script view renders readable, clipped paragraphs independently of
  the focused passage editor.
- Clicked REC+FX then Stop during its three-second countdown. Query reported
  armed then cancelled, no active recording, and no microphone opened.
- Corrected clock labels: loading a preview is not displayed as REC; countdown
  and active capture have separate state. Callback detach/reopen and all tests
  were rerun after the fixes: 12 native/editing tests, 95 assertions; 5 API tests,
  35 assertions; zero failures/errors. No JVM restart was needed.
- Reusable opt-in OS input helper: `tools/test/studio_input.swift`. It requires
  explicitly supplied global coordinates and a foreground game window; it never
  launches itself. Ignored evidence in `build/recording-qa/`: `daw-live-playing`,
  `daw-track-paused`, `daw-option-zoom`, `daw-real-trim`, `daw-real-pan`,
  `daw-game-roundtrip`, `daw-studio-roundtrip`, `daw-script-view` PNGs.
- Left studio open, playback stopped, trim reset, timeline at zero. No recording,
  route/device changes, or take publication occurred in these interaction checks.
- P1 is complete; the **whole DAW is not**. P2 is the next implementation phase.

### P2 — Project model and real multitrack audio

Next playback increment: atomic native loop-region publication, half-open frame
range [A,B), exact wrap within an audio block, paused seek without transport motion,
UI loop toggle/A/B cursor markers, visible timeline region and matching API.
Use the same callback for deterministic multi-wrap/block-partition tests. A loop
does not promise click-free boundaries for arbitrary source samples; crossfaded
looping is separate work, and must not silently alter sample-accurate editing.

- [x] First usable project-history slice: schema-1 take document, legacy-index migration,
  durable metadata edits, 64-entry undo/redo, API revision checks, native buttons/shortcuts.
- [ ] Versioned project/media/track/clip schema, explicit migration and project save/load.
- [ ] Undo/redo transactions, gesture coalescing, revision conflict checks and restart recovery.
- [ ] Native multitrack scheduler, shared sample clock, fades, gain/pan/mute/solo and buses.
- [ ] Real movable/splittable clips, multi-selection, snap, source offset and missing-media UI.
- [ ] Offline deterministic render tests, overlapping-clip sums and sample-accurate loop tests.

#### P2 project-history implementation — 2026-09-09

The first document is deliberately the actual take editor's state, not an unused
multitrack abstraction. `takes.clj` owns document validation, migration, durable
commit and bounded undo/redo. `studio.clj` uses it for all take-index writes.
`build/recording/project.edn` becomes authoritative after migration; the original
`takes.edn` and every WAV remain intact. Existing absolute paths remain supported;
portable collected-media references and the full track/clip schema are still pending.

- Atomic persistence happens before publishing the next in-memory revision. A failed
  write retains the previous document. Unsupported/corrupt documents are rejected,
  not replaced by a sample or legacy fallback. History survives reopening.
- Undo affects take names, selection, preferred references and new take references;
  it never deletes WAVs or reverses microphone capture. Publication clears history
  because replacing the game's voice is a non-undoable external side effect.
  Undo/redo stops preview before swapping the selected take and refreshes its name.
- Native `Annuler` / `Rétablir` buttons and Command/Ctrl-Z / Shift-Command/Ctrl-Z
  dispatch the same `:project/undo` and `:project/redo` commands. Query exposes ID,
  revision and labels; `:expected-revision` rejects stale edits before execution.
  Completion events include the resulting project revision.
- Native/codec/project tests: 13 tests, 117 assertions. API tests: 6 tests,
  39 assertions. **156 assertions pass**, including disk-write failure, corruption,
  history bounds, redo invalidation, no-op revisions, immutable WAVs and revision
  conflicts. Keyboard regression includes the command modifiers.
- Live game JVM: migrated real take index at revision 0, renamed a selected take,
  clicked Undo (revision 2) and Redo (3), reopened with preserved history, then
  used OS Command-Z (4) / Shift-Command-Z (5). Final Undo restored its original
  `Essai été` name at revision 6. A stale revision-0 rename was rejected and the
  entire take index remained equal before/after. Captured and inspected the native
  controls in `build/recording-qa/daw-undo-redo.png`; final capture is
  `daw-history-verified.png`. No recorded audio was changed or published.
- Not yet implemented: cross-process document locking, portable media collection,
  clip gesture coalescing, simultaneous tracks, mixer and the remaining P2–P6 work.
  The current serialized owner is the existing studio worker in the game's JVM.

### P3 — Production audio workflow

- [ ] Punch/loop recording and comping; media browser/import/relink/collect.
- [ ] Automation lanes with gesture lifecycle, parameter ranges and override state.
- [ ] Mixer patchbay, cue monitoring, external-FX health and latency/drift compensation.
- [ ] Mix/stem export, ranges, formats, tails and explicit game publication jobs.
- [ ] Device removal/reconnect, disk-full and long-recording fault tests.

### P4 — Musical sequencing and performance

- [ ] Tempo/signature mapping, metronome and MIDI I/O/note editing/quantization.
- [ ] MPE/note expression and timestamped automation.
- [ ] Launcher scenes/quantization with explicit track sequencer ownership.
- [ ] Controller mapping/learn and editable keyboard shortcuts.

### P5 — Devices, plugins and advanced sound design

- [ ] Native effect chains/macros and preset management, versioned state.
- [ ] CLAP host lifecycle and offline conformance fixture before third-party plugins.
- [ ] Plugin scanning/crash isolation, restart, UI hosting and delay compensation.
- [ ] Nested racks/modulation and advanced time stretching, with numerical/performance tests.

### P6 — Product hardening

- [ ] Scalable layout, keyboard navigation/accessibility, searchable actions and help.
- [ ] Headless batch API/CLI, event subscriptions, adapter SDK/examples and compatibility tests.
- [ ] Packaged standalone tool, signed/notarized platform builds and reproducible artifacts.
- [ ] Stress/soak tests and user sessions on actual audio interfaces and trackpads.

## Test and performance policy

Every phase uses actual native rendering and audio processing, not a mock web view.
Keep deterministic fixtures separate from real recordings. Evidence must identify
whether it came from offline DSP, simulated input, physical/native UI events, or a
real external effects round-trip. A saved screenshot is not evidence that pause,
recording or scrolling works.

Proposed budgets, to measure rather than advertise as achieved: 120 Hz UI when the
display permits, pointer feedback within one rendered frame, cached project selection
under 50 ms p95, no audio deadline misses in a 30-minute reference session, and bounded
memory while scrolling thousands of clips. At 48 kHz, 128 frames provide 2.667 ms per
audio block; UI timing does not establish whether the audio deadline was met. Report
test hardware, buffer size, track/device count and worst-case load with every result.

Regression gates include transport cursor stability while paused; no accidental
capture during keyboard editing; scroll/zoom anchor preservation; immutable originals;
undo/reload equivalence; corrupt project rejection; missing plugins/media; host timeouts;
bounded queue saturation; duplicate request handling; device loss and audio silence on
unsafe route failure. Hot reload must not replace active callback resource layouts.

## Sources

Primary online documentation was consulted on 2026-09-09. “Latest” documents can move;
pin implementation dependencies and validate against the locally installed host API.
The recommendations, roadmap and proposed performance budgets are design decisions,
not claims made by the reference products.

[^arrange]: Bitwig, [The Arrange View and Tracks](https://www.bitwig.com/userguide/latest/the_arrange_view_and_tracks/).
[^playback]: Bitwig, [Playing Back the Arranger](https://www.bitwig.com/userguide/latest/playing_back_the_arranger/).
[^launcher]: Bitwig, [The Clip Launcher](https://www.bitwig.com/userguide/latest/the_clip_launcher/).
[^automation]: Bitwig, [Automation](https://www.bitwig.com/userguide/latest/automation/).
[^audioevents]: Bitwig, [Working with Audio Events](https://www.bitwig.com/userguide/latest/working_with_audio_events/).
[^hosting]: Bitwig, [Plug-in Handling and Options](https://www.bitwig.com/userguide/latest/vst_plug-in_handling_and_options/).
[^controllers]: Bitwig, [MIDI Controllers](https://www.bitwig.com/userguide/latest/midi_controllers/).
[^export]: Bitwig, [Exporting Audio](https://www.bitwig.com/userguide/latest/exporting_audio/).
[^live]: Ableton, [Live Reference Manual](https://www.ableton.com/en/live-manual/12/).
[^routing]: Ableton, [Routing and I/O](https://www.ableton.com/en/manual/routing-and-i-o/).
[^liveautomation]: Ableton, [Automation and Editing Envelopes](https://www.ableton.com/en/live-manual/12/automation-and-editing-envelopes/).
[^files]: Ableton, [Managing Files and Sets](https://www.ableton.com/en/live-manual/12/managing-files-and-sets/).
[^reascript]: Cockos, [ReaScript](https://www.reaper.fm/sdk/reascript/reascript.php) and [API Reference](https://www.reaper.fm/sdk/reascript/reascripthelp.html).
[^miniaudio]: miniaudio, [Programming Manual](https://miniaud.io/docs/manual/index.html).
[^clap]: CLAP project, [Audio Plugin API](https://github.com/free-audio/clap).
[^process]: CLAP, [Process contract](https://github.com/free-audio/clap/blob/main/include/clap/process.h).
[^ports]: CLAP, [Audio ports extension](https://github.com/free-audio/clap/blob/main/include/clap/ext/audio-ports.h).
[^latency]: CLAP, [Latency extension](https://github.com/free-audio/clap/blob/main/include/clap/ext/latency.h).
[^glfw]: GLFW, [Input guide](https://www.glfw.org/docs/latest/input_guide.html).
[^nrepl]: nREPL, [Middleware design](https://nrepl.org/nrepl/design/middleware.html).
[^localapi]: Installed Bitwig controller API: `Contents/Resources/Documentation/control-surface/api/com/bitwig/extension/controller/api/ControllerHost.html` and `Parameter.html` inside `/Applications/Bitwig Studio.app`. Read-only local inspection; online discovery entry point is the MIDI Controllers manual above.
