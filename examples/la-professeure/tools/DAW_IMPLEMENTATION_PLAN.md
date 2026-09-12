# An extensible native DAW for La Professeure

## Direction

Active issue-by-issue acceptance checklist: [`../AGENT_TODO.md`](../AGENT_TODO.md).

Global transport and passage audition have distinct ownership. Pause/Resume and
Space control the loaded sound even after selecting a different or unrecorded
passage; Resume preserves its cursor and never schedules recording, including
when Edit's REC toggle is enabled. Passage/card Listen remains selection-specific.
The mouse and keyboard use one native transport-action selector. OS-input QA
on 2026-09-12 reproduced and fixed a selection-dependent Resume failure; measured
PCM/cursor progression and regression evidence are recorded in the checklist.

The DAW uses English for its interface and generated messages. Content language
is independent: dialogue, character names and existing take/profile names must
not be translated or rewritten when the interface changes.

### Authoring source and recording freshness

The configured source is now the original Obsidian note, with the previous
recording-ID registry explicitly retained across the path change. After user-
granted macOS access, the running watcher passed insertion/edit/removal of a
temporary voiced passage in the original note. Same ID survived the edit,
revision advanced, Studio updated, and the note was restored byte-for-byte.
Never substitute a copied note silently.

New Markdown passages must populate Studio rows automatically. Changed voiced
text must visibly require review while preserving all previous takes. Existing
reconciliation tracks revisions; Record shows explicit freshness labels and
Edit/Takes show compact badges for each passage's selected take, with the full
reason on hover. New takes store captured-content fingerprints. Moving a passage must not
invalidate its audio; editing speech or speaker must. Derived FX/trim/recovery
takes retain their source fingerprint, not the latest script's fingerprint.
An edit during recording must not certify old spoken text as current.

Capture presentation owns a copy of the spoken script and its stable passage ID;
never borrow text from the reloadable story buffer or use a row index as capture
identity. Count-in previews are refreshed at actual capture start. Record mode
keeps that script/live waveform visible through capture and FX tail. Markers
resolve current rows by ID, so removal simply leaves no matching row. Edit can
show another row's saved waveform without attributing the live PCM to it. The
public recording-busy guard still rejects changing selection/starting another
take; mode changes remain allowed. Native reorder/removal and host snapshot
regressions pass. A 19.43-second real BlackHole capture also passed a concurrent
edit of the original Obsidian note: stable ID, frozen original spoken text,
continuous PCM, and a saved take marked changed against the new words. The note
was restored byte-for-byte; the take matched the restored text. Prior selected
takes/routing were restored, the labelled QA take retained, and nothing published.
Physical input and derived-audio end-to-end acceptance remain separate gates.

Derived-audio acceptance now includes an actual sample-exact trimmed copy and a
real Bitwig processed return retaining the capture-time fingerprint. The latter
also passes saved-take playback through the native output callback. Its periodic
QA signal has ambiguous correlation peaks, so alignment correctly leaves the
original return untouched. A separate real uniquely identifiable return passed
positive alignment at 3,424 frames / 71.3333 ms: the generated copy is sample-exact
and plays through the native output. Its legacy source remains Unverified in both
derived versions. Interrupted-take recovery provenance now passes isolated real
filesystem/fault tests described below; physical end-to-end acceptance remains
separate. Correlation magnitude alone is not evidence of correct latency.

### Active operation versus next-recording preferences

An offline effects pass must identify itself as **FX pass**, independently of the
Dry/Live FX preference for the next microphone recording. Its input meter means
the saved take being sent, not the microphone. Live waveform selection follows
the active recorder operation. Capture start clears the previous saved waveform
and publishes current ownership/meter labels in one render task; later worker
refreshes may supply signal measurements but must not expose stale take data.
Implementation and 157 targeted assertions pass. Live screenshots establish the
new capture-start behavior, but final draw-label publication is still held by an
imported audio-state schema conflict; see the active checklist. Do not claim the
complete live visual update until that publication succeeds and is reinspected.

An isolated native C-struct alias/state reload regression passes in synchronous
and asynchronous modes, preserving mutated data, schema and address across two
reloads. The constant-reload namespace passes 28 assertions. This narrows but does
not clear the long-running Studio's retained-reference mismatch; the live visual
acceptance gate remains open. Matching state sizes alone must not authorize an
audio-object migration.

The retained-reference cause is now reproduced and fixed: literal C member names
were incorrectly resolved as same-named local constants. Generated miniaudio
bindings additionally use explicit keyword selectors (`:ma_sound`); `c-api` is a
real declared Var. Runtime/native reload regressions pass 129 assertions and
emitter tests pass 134. The full headless Studio suite, with actual dialogue/font
asset initialization, passes 107 tests / 870 assertions.

After an idle clean restart, both adding and restoring a visible native heading
published through whole-namespace reload while preserving concrete audio state
addresses and schemas. Actual OS mouse audition/pause/resume/stop passed; saved
project checksum and revision stayed unchanged. See the dated active checklist
for screenshots and precise measurements. This clears the publication blocker.
The fresh process subsequently passed a real active-FX screen check: FX pass,
SEND and LIVE FX RETURN labels, processing guidance, cleared prior waveform and
disabled audition were inspected in `/tmp/studio-active-fx-final.png`. The valid
3.38-second returned WAV remains as a labelled QA take; original selection and
preferred/source pointers were restored. This verifies the configured route and
current screen, not every device or effects chain.

### Interrupted-recording transactions

Recover dry and wet durable PCM prefixes as one project edit. Retain captured
dialogue identity (or its absence for legacy recordings) and mark both versions
Interrupted; recovery must never certify them against today's edited text.
Persist each stream's journal identity with its take entry before finalizing the
journal. A retry after project commit must finalize that journal without adding
duplicate history entries. Reuse an existing recovered WAV only after matching
its PCM exactly; originals are never overwritten. Fault tests cover failure at
project commit and at journal finalization, including reopening persisted state.
Initial fault reproduction: eight failures, zero errors. Final full isolated
suite: 108 tests / 1,051 assertions, zero failures/errors, including damaged-WAV
rejection. The changes reload into the existing Studio JVM without changing the
project or selection; worker failures remain zero. No live user recovery journal
was imported for this test. See the active checklist for physical QA limitations.

Track status and recording controls must not overlap. Edit rows use 56-pixel
spacing, with 54-pixel content and the second-line snippet/status below the
30-pixel Play/Arm controls. Takes uses its existing 62-pixel card spacing. Render,
hit-testing, visible-row bounds and QA input coordinates must change together.

### Maintainable native UI code (2026-09-10)

Keep cohesive UI responsibilities as private helpers in `studio.clj`, not a
collection of small namespaces. The Edit/Record/root drawing functions orchestrate
toolbar, tracks, script, take editor, transport and routing sections. Put binding
pairs and sequential statements on separate lines; name complicated conditions
before passing them into drawing calls. Preserve native type metadata.

Related native updates use the private `set-state!` macro with a vector of
field/value pairs. It expands to ordered assignments: later values see earlier
updates. It is not atomic; audio/control-thread atomics remain explicit. Use
keywords for callback parameter-name data (`:output`, `:frame-width`,
`:frame-height`) and real Vars for type references. Reuse the existing compiler's
macro expansion and keyword-name support rather than adding a parallel mechanism.

The JVM control side follows the same rule: guarded execution and dispatch are
separate from named mix, history, selection/arming, routing and take-edit helpers,
all in this same namespace. `set-native-state!` uses the same pair validation to
expand ordered `az/set-value!` calls; it does not schedule work or make updates
atomic. Keep it inside an explicit render callback. Expanded API regression:
27 tests / 208 assertions, including mutation order and invalid-route/UTF-8 guards.

Regression checkpoint: 14 focused native tests / 114 assertions, 16 isolated API
tests / 129 assertions, and callback emission 1 test / 2 assertions passed.
Rounded controls exposed the former 16K vertex limit; the allocation, atlas
offset and checked writer now share a 128K limit. Six dense scratch layouts
stress both modes, script, 32 waveforms and a device picker without submitting
fake content, touching devices or altering recordings. Both native windows were
restarted and their independent renderer state verified.

The first outlined-control/card pass is implemented, not visually accepted.
Locked-session window captures remained stale even as native mode, vertex count
and frame counters changed. Recheck live minimum/wide layouts and physical input
after unlock; do not diagnose a publication failure from those cached images.
Follow-up GPU readback verified the current normal-frame layout. A private-helper
probe then re-evaluated only `draw-record-script!`: its temporary heading appeared
in the real GPU frame, and restoring the file's declaration restored the original
heading. No namespace reload, explicit `recompile!` or app restart was requested. Worker
health stayed running with zero failures; physical input remains a separate gate.

### Worker resilience and live audition (2026-09-10)

Keep native command consumption inside the worker's error boundary. A failed
Zig edit previously terminated the future before the old handler ran, leaving
rendering alive but the take display and command queue unserviced. Guard the
whole iteration. Compiler failures retain active audio/session state and report
`:waiting-for-code`; cleanup failures are contained, and interruption is not
swallowed. Failed device cleanup cancels count-in without discarding unsaved PCM.
`query` includes bounded worker health and an `:alive?` flag; `worker-status`
reads it without entering native code, even when full query cannot run. A stopped
worker rejects new requests with `:worker-stopped`; `restart-worker!` starts one
only when the prior future is terminal and the window is still open. It never
replays commands that were already consumed.

Twenty-one isolated API tests / 154 assertions passed. Live fault injection failed
the native action accessor six times; the real worker stayed alive and recovered
without changing project revision 59. `live-audition-qa!` now exercises selected
passage/waveform seek, Listen/Pause/Resume, view switching and Stop through native
hit testing with the real playback engine. The warm pass held a paused PCM
cursor exactly for 180 ms, advanced non-silent output, and never recorded despite
REC being enabled. A cold pass exceeded the observation threshold and remains a
latency investigation. Fresh GPU-frame appearance now passes (see below);
OS mouse/trackpad acceptance is still a separate open gate.

### Selected visual direction and mode boundaries (2026-09-09)

The five generated references and the user's selection are recorded in
[`design/2026-09-09-daw-concepts/README.md`](design/2026-09-09-daw-concepts/README.md).
Use the light editing desk as the base, a script-first Record workspace, and the
last concept's clear transport icons. The take-grid view compares Dry / FX /
Favorite takes; it does not imply Bitwig-style clip scheduling. Keep the common transport/routing and
one recording session. Mode-specific actions may expand beyond presentation,
but must use the existing validated command/undo path. Native layout and input
dispatch stay explicit, in the studio namespace; no separate engine per mode.

Current native implementation: Edit/Record/Takes tabs and F2, API `:view/mode`, mode
discovery through `capabilities`, light surfaces, IBM Plex Sans controls, serif
dialogue, triangle/circle transport icons, and focused input/return meters. Idle
meters are gated by real device activity, not stale held peaks. Deferred shader
polish must preserve readable type and simple controls, with Retina performance
and dev/release checks—not add glow/noise or mask usability problems.

Routing control clarity: replace ambiguous Vol +/- buttons with a labelled
Monitor level fader and a visible percentage. Its 0–50% range retains the existing
monitoring cap, independent of microphone/FX recording and take audition gain.
Dragging tracks the pointer outside the rail with clamping, ends on release, and
cancels when routing is hidden or a device menu is open. It never opens a device
or enables monitoring. `:routing/monitor-level` uses the same atomic native setter
through the validated command API; `query :monitoring` reports level and enable
state independently. Preserve authored take names, even if they are French.

Progressive disclosure: keep input, FX send/return, listening output and monitoring
visible; collapse profile/reconnect/offline-processing/recovery controls under
Routing tools by default. Its state is presentation-only and shared across views,
not persisted in the audio project. `:view/routing-tools` and the view query expose
the same toggle. Expanded tools fit above the status bar at 1100×760. Disabled
device/monitor controls explain capture/input-check ownership instead of looking
actionable and then rejecting the click. Monitor-level adjustment remains allowed.

Record-mode workflow correction (September 12): no manual arming. The selected
voiced passage is the target; **Record new take** and the top **RECORD** button
submit `:record/start` with its stable Markdown ID. Each start retains old takes
and creates a fresh recording. Selection alone never opens devices. Count-in,
capture and FX tail own a frozen target. Text-only/removed passages are rejected.
**Play**, **Space** and **Listen to take** audition independently of global REC.
Traditional track arm/global REC remains confined to the Edit workflow. Existing
REC preference save/restore across mode changes is retained for compatibility,
but does not govern the focused Record action. Switching modes never interrupts
capture/count-in/playback.

Routing feedback uses the same `:routing-signal` snapshot in the UI and query API.
Device names are configuration, not verification. Inactive devices show not-checked;
Dry mode shows the FX return as bypassed. Active inputs distinguish silence from
signal, while held peak dBFS remains diagnostic history. Count-in never implies an
open microphone. Signal on a return does not establish that Bitwig or any particular
effect produced it (`:effect-verified? false`). No automatic gain change or device
opening is performed by this display/query path.

Live PCM invalidates the selected saved-waveform cache. When recording ends, the
selected WAV is decoded again even if its path is unchanged, or the display is cleared
for an empty passage. The file-backed idle cache remains intact between changes.
This prevents an earlier capture's shape/duration appearing beneath another passage.
Implementation and live/regression evidence are tracked in `AGENT_TODO.md`.

Acceptance checkpoint: 14 targeted tests / 93 assertions passed (mode/input
isolation, pointer navigation, divider bounds, font gutters/metrics, name editing,
playhead alignment and API validation). Physical clicks switched Edit/Record and
launched a saved take. Switching back to Edit kept playback running: PCM cursor
0.5145→1.7364 seconds and non-silent output frames 24255→82908. Project revision
36 and selected take were unchanged. Restored the user's paused cursor to
1.010625 seconds. Fresh light Edit/Record screenshots and the 1100×760 Record
layout were inspected in `build/recording-qa/studio-*-light-*.png`.
Follow-up real-device acceptance: synthetic BlackHole send → existing Bitwig FX →
return capture stayed active through physical mode changes, resize, native
maximize and restore. Inspected `studio-record-count-in-light.png`,
`studio-record-maximized-light.png` and `studio-record-tail-light.png` in
`build/recording-qa/`. Two dry/wet pairs passed finite/clipping/gap checks (377 and
241 non-startup 10 ms dry blocks; no silence); measured the configured inverting
effect's −0.0630957 gain. These were synthetic signal tests, not microphone or
headphone-monitor listening acceptance. All four QA files remain labelled in
history, previous dry/wet pointers were restored, and no QA audio was published.

Native fixes accompanying that acceptance: phase-specific guidance, disabled
capture-incompatible controls, a true count-in setting label, red live waveform,
Record-mode playback cursor instead of unusable trim handles, fractional passage
scroll accumulation, and live-monitor return-meter updates. Focused regression:
7 tests / 80 assertions, zero failures/errors. Final long-script scrolling exposed
a bottom-boundary clipping bug; inclusive subpixel-tolerant bounds and targeted
tests passed; the final marker is visible at minimum size. Latest focused run:
9 tests / 89 assertions, zero failures/errors. Physical Up-button scrolling
worked after restart, but injected wheel events were not received by the GLFW
callback; trackpad delivery remains open, separately from native scroll logic.
A raw QA resize call accidentally bypassed the
render thread and closed the idle JVM; public QA resize/maximize helpers now
queue native work. Both windows restarted with project revision 59 and the user's
selected take/routing/cursor restored. Remaining UI/audio fault tests are tracked
in `AGENT_TODO.md`. This is not whole-DAW completion.

### Recording transport correction

Unexpected device stops are now checked by the control worker using miniaudio's
thread-safe state accessor, with ownership flags preventing uninitialized reads.
An input-check or monitor failure closes only that activity; capture failure
stops producers and retains each nonempty stream as an interrupted take. No
automatic alignment or publication is applied to these partial recordings.
If disk/encoding fails, keep PCM and the session, display AUDIO STOPPED, and let
Stop retry. Reconnect never silently selects a replacement microphone. API events
include `:audio/device-stopped` and `:recording/interrupted` with saved stream paths.
Unit/control coverage and live preflight warning/reopen evidence are in the
checklist; actual hardware removal and stalled callbacks remain separate gates.

Audition/mix output-stop acceptance also passes using owned native output
instances. The take keeps its decoded sound/cursor; the mixer closes the stopped
device but retains clip PCM, loop and cursor. The transport pauses and reports
`:audio/output-stopped`; Play explicitly retries the selected output. A failed
retry preserves position instead of loading the take from zero. Record-mode
Play/Space controls the active mix; its separate Listen to take control switches
to take audition intentionally. Both live native-button tests and Vulkan capture
inspection are recorded in the checklist. This does not certify physical unplug
or backends that stop delivering callbacks without changing device state.

Take-name interaction follow-up: dragging beyond either edge scrolls the draft
using elapsed time and bounded speed based on pointer distance, not frame
count. Hit-testing is clamped to the visible field, and the rightmost viewport
keeps the final text visible. Native 30/120 FPS regression and actual held mouse
drags reached both ends of a 99-byte accented draft at minimum size. Releasing
stopped dragging; original draft/history restored, project revision unchanged.
Latest combined native suite: 27 tests / 214 assertions passed. This does not
claim IME composition or grapheme-cluster navigation support.

September 12 acceptance follow-up: Record mode's long script now clips individual
glyph geometry/UVs at fractional scroll offsets, rather than dropping whole lines.
Overlong words wrap between UTF-8 codepoints. A runtime-only French fixture reached
its final line through phased OS scroll events at minimum size; authored Markdown
was untouched. Native regression: 23 tests / 207 assertions passed. Human trackpad,
pinch and composed-text editing remain separate acceptance work.

The direct Record → Stop → saved-take audition path also passed with the real
Bitwig FX route enabled and bypassed. WAV analysis measured the configured −6 dB
difference and polarity inversion, with no clipping. A reversible publication
test then observed the game's ordinary voice watcher advance its revision for
the processed take and again for the restored original. Original bytes (SHA-256),
mtime and selected/published references were restored; the game stayed muted.
`AGENT_TODO.md` records the exact take IDs, screenshots and retained recovery copy.
These checks complete this round-trip acceptance, not the wider DAW roadmap.

This Arranger-style sequence applies to **Edit** only. Focused **Record** uses the
direct selected-passage workflow above; it does not require manual arming or Play.

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

## GPU-frame QA (2026-09-10)

Locked-session macOS captures disagreed with native mode and selection, so current
rendering is now verified with opt-in Vulkan readback. `capture-window-qa!` captures
the latest normal frame's mapped vertices through the current GPU pipeline, without
calling app drawing/input again. It checks transfer-source support, uses unclipped
swapchain targets, transitions the color attachment to transfer and back to present,
then copies coherent host memory only after the GPU fence completes. Scratch resources
are released and renderer context layout/audio ownership remain unchanged. This follows
Vulkan's [image-to-buffer copy requirements](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdCopyImageToBuffer.html)
and [synchronization rules](https://docs.vulkan.org/spec/latest/chapters/synchronization.html).

Inspected Edit, Record and the text-only game at 2200×1520, plus Record at 2854×1762.
The pixels agree with mode/selection/device state and show the new rounded controls,
correct REC color, legible Markdown text/choices and no overlapping routing heading.
Native regression: 15 tests / 112 assertions passed; both renderer instances remain
independent. Current captures do not establish physical input acceptance, OS composition,
or reconstruct an earlier ambiguous partial-publication event. Keep those checks open.

## Transport clarity follow-up (2026-09-10)

Historical checkpoint; the manual-arm part is superseded by the September 12
focused-recording correction above.

Edit-row and Record-workspace audition use the same dedicated command, independent
of global REC. The Edit row previously resumed through the main transport, risking
recording instead of resuming; it now preserves the selected passage's cue as well.
The clock reports actual playback before REC-enabled state, and Record mode names
the armed passage even if a different passage is selected. These changes do not
auto-arm, record on mode changes, or edit takes. Native regression: 16 tests / 146
assertions. Both real saved-audio paths passed cue/pause/resume/mode/stop checks;
GPU captures show the actual playing/paused labels and explicit recording target.
Physical input acceptance remains distinct and open where event delivery/focus was
unstable. See `AGENT_TODO.md` for evidence and remaining acceptance work.

September 12 verification: 18 native tests / 166 assertions and 30 isolated API
tests / 248 assertions passed. Native button input selected passages 2 → 6 → 2;
both direct Record buttons scheduled the correct stable IDs with global REC off.
Each 60-second QA count-in was cancelled before capture; no WAV/project edits.
The actual Vulkan frame shows selected-target guidance and no Arm/Disarm control.
Repeat real direct-record PCM capture/save/audition and physical OS interaction
separately; those are not established by this count-in test.

## Recording readiness: bounded input check

Record mode has an explicit capture-only **Check input** action. It uses a separate
native device and atomic peak counters, retains no PCM and produces no output.
The control worker stops it after ten monotonic seconds, on Stop, or before a new
recording. Device and Dry/FX changes are refused until the check is stopped.
The shared routing snapshot supplies both UI meters and API evidence; a nonzero
input is not proof of effects processing or adequate gain. No automatic microphone
opening on passage selection or mode changes.

## Selected waveform ownership

Take-name refresh reads focus through the typed native `name-focused?` boundary.
An inferred native flag exposed as raw `[0]` bytes must never be interpreted as
Clojure truth: that previously suppressed refresh and showed a different take's
name. Native false/true/false tests and a real restored-take screenshot verify
the fix; the full suite now passes 110 tests / 1,067 assertions. Native state
layout and all stored names are unchanged.

The editable name belongs to the selected `[passage ID, take path]`. A new target
ends the previous target's unsaved edit and loads its own name; an empty target
clears the field. Refreshing an unchanged target must not replace its local draft
or reset local undo. Native and host/cache tests plus an actual empty-take Vulkan
capture verify this ownership behavior (full suite: 111 tests / 1,075 assertions).
The physical text-entry/switch test remains separate while OS input is unavailable.

Take-dependent controls share `take-editable?`: the selected waveform must be
loaded, nonempty, and idle. Rename, Favorite, Mark A, Compare, Reset, Trim copy
and Publish are visibly disabled with reasons otherwise. Empty waveforms show
recording guidance instead of trim handles or instructions to drag nonexistent
edges. Profile-name entry remains independent. Full isolated suite still passes
111 tests / 1,075 assertions; actual Vulkan and macOS window captures verify the
empty layout. Native hit-test clicks on seven disabled controls plus the empty
waveform preserve project, transport and trim state. This does not replace the
physical input gate or prove that an A reference has been marked for comparison.

Selection presentation follows stable passage IDs, not the previous worker
refresh. Waveform uploads validate the selected ID atomically on the render
thread. Until matching data arrives, display a loading state and disable
take-specific audition/trim without destroying the independent playback engine.
Global Pause remains available when another passage's take is playing. Native
A → B → A ownership and obsolete host-upload regressions pass; an actual
unrecorded-passage screenshot confirms empty wave/disabled playback. A later
unobscured OS-mouse pass verified Play -> Pause -> select unrecorded passage ->
global Resume -> Stop with advancing PCM counters and inspected paused/empty-wave
frames. This supersedes the earlier lock-screen-blocked check for that sequence,
not all rapid-switch stress cases. See AGENT_TODO.md for evidence and open gates.

## Device startup versus count-in

With count-in set to zero, recording still needs time to open audio devices.
Show **PREPARING / Opening audio devices. Stop to cancel.** during that interval;
show **COUNT-IN** only while its deadline is in the future. Do not report active
capture or display old waveform data before the recorder owns current PCM.
Routing meters use the same countdown predicate, and an unused Dry-mode FX
return stays bypassed. Persistent level warnings identify their source as the
last take, not a live microphone measurement.
The corrected labels are live-reloaded; the full isolated Studio suite passes
109 tests / 1,057 assertions. Capturing the corrected transient UI remains open.

## Game keyboard delivery

The live focus check also verified actual sound gain: the playing game voice
changed 0.8 -> 0.0 -> 0.8 through OS focus switches while its cursor advanced.
Studio audition produced non-silent PCM independently. This tests native audio
state/output callbacks, not an external acoustic recording.

The companion game's polled navigation uses GLFW retained key presses so a
press/release between render frames is not lost[^glfw]. Configure this per game
window at creation; leave Studio's callback-based input unchanged. Quick presses
must navigate once, a held Backspace must not repeatedly climb the dialogue tree,
and F1 must retain independent-window focus behavior. Actual OS tests passed three
short Backspace trials, a one-second hold and F1 in both directions after live
reload. Focus must be verified before interpreting an OS-input test result.

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
