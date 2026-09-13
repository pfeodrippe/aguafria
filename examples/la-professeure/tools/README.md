# Dialogue recording tools

The studio interface is English. Dialogue, character names and user-created
take/profile names retain their original language; the DAW does not translate content.

## Native recording workspace

Launch the game from its root with `clojure -M:dev:tools:desktop`, then in that
same nREPL:

```clojure
(require '[la-professeure.tools.studio :as studio])
(studio/open!)
```

The studio opens in its **own native window** while the game keeps playing.
Both run in the same JVM/render thread with separate Vulkan resources, input and
preview sounds. F1 focuses the other window; it no longer replaces the game view.
The game is silent while the studio has focus, and throughout recording/countdown
even if you switch to Bitwig. Returning to the game restores its previous manual
mute choice; DAW playback is independent.
Closing the studio window queues Stop and hides it without discarding a take
(F1 from the game shows it again). `(studio/close!)` releases its resources and leaves the game running.
Normal studio bounds are remembered in ignored `build/studio-window.edn`, separate
from game/project settings. Restoration accounts for disconnected displays and
window borders. The current layout minimum is 1100×760 content points. Native
resizing is enabled: drag a window edge or corner like a normal desktop app.
**Hide routing / Show routing** gives the timeline and editor the routing panel's
space without changing any audio connections. The preference is remembered.
API equivalent: `(studio/submit! {:op :view/routing :args {:visible false}})`.
Drag the horizontal divider above **TAKE / EDIT** to allocate more room to tracks
or the text editor; double-click it to reset. The split is remembered, and visible
track rows adapt without stretching controls. API equivalent:
`(studio/submit! {:op :view/editor :args {:top 532}})` (logical window points,
clamped to keep both panels usable).
### Workspace modes

**Edit** is the light timeline/take editor. **Record** focuses on the complete
dialogue, live input/FX meters and saved-take audition. **Takes** compares Dry,
FX and Favorite cards with the same editor below. Click the tabs or press **F2**.
Dry/FX shows the selected version of that type when applicable, otherwise its
latest reference; Favorite retains the explicitly chosen favorite. Click a card
to select, its triangle to play/pause/resume, or double-click to audition.
Play/Space in Takes also only auditions; its RECORD button starts a new take for
the selected passage, without using Edit's armed row.
In **Record**, select a voiced passage, then click **Record new take** (or the
top **RECORD** button). No separate arm or global-REC step is required. Every
press starts a fresh take; existing takes are retained. Selection alone never
opens the microphone. During count-in/capture/tail, the target cannot change.
**Check input** opens only the selected input for ten seconds to show its level.
It does not save audio, send it to Bitwig or play it through speakers. Use
**Stop check** or **Stop** to finish early; **Record new take** closes the check
and begins a fresh take. Stop the check before changing devices or Dry/FX mode.
API: `{:op :routing/check-input :args {:enabled true}}` (false stops it).
This verifies input signal, not external effects or suitable recording gain.
**Play**, **Space** and **Listen to
take** audition from the waveform's selected position, even with REC enabled;
the same button becomes **Pause take** / **Resume take**. It never records.
API: `:transport/audition` toggles this cue-aware audition, while
`:transport/launch` explicitly starts the selected take from the beginning.
Transport and routing
remain shared: switching modes does not restart audio, select another take, move
the cursor or interrupt an active recording/count-in.
API equivalent: `(studio/submit! {:op :view/mode :args {:mode :record}})`;
use `:edit` to return. `(studio/capabilities)` lists implemented modes.

Modes are built-in views, not separate engines or runtime-loaded plugins.
Their descriptors live in `workspace-modes`, with native draw/input handling in
the same studio namespace. To add a mode, add its descriptor, explicit native
dispatch and isolated pointer/scroll regions. Reuse the common transport and
command worker. New project-changing actions must go through the command API,
including its validation, recording guards and undo/history—not mutate takes in
a drawing function. Clear stale drag/modal state on transitions and test that
audio, devices, selection and cursor survive switching. Takes cards use validated
selection/audition commands and reject stale snapshots. Empty Dry/FX slots prepare
Record mode; they never start capture. Physical grid acceptance is still tracked
in `AGENT_TODO.md`.

The Edit workspace opens with
**Tracks**: dialogue track headers aligned with real take clips and a seconds
ruler. Each row is a dialogue take, **not** a full clip arranger. Empty passages say **Not recorded**; narration/choices
remain visible as context. The selected take's waveform is decoded from its WAV.

Single-click a clip or ruler to position without playing. **Play**, a track's
triangle, or a double-click auditions; **Pause / Resume** retains the audio
engine's actual PCM cursor. **Space** toggles playback, **Home** rewinds and
**Escape** stops/cancels. Editing a name consumes text keys, including Space.
The take-name field supports click/drag selection, a visible caret, arrow keys,
Shift-selection, Home/End, Backspace/Delete and Cmd/Ctrl+A/C/X/V. Double-click
selects the whole name. Long names scroll within the field; its 120-byte limit
never splits a UTF-8 character. Pasted tabs/newlines become spaces. Enter saves
the name; Escape leaves editing without submitting it. This is a lightweight
single-line editor with grapheme-aware navigation. On macOS, a small native
composition overlay shows marked text beside the caret and anchors the input
method's candidate window there. Enter confirms the composition; a later Enter
saves the name. Escape cancels composition without submitting the name. Committed
text still uses the game's font renderer; arbitrary complex-script shaping is
not guaranteed. Actual hardware/input-method acceptance is still pending.
**+ / −** zoom from 2 to 60 seconds; **All** shows the full
60-second range. The arrows beside the ruler pan time; arrows beside **Script**
page through passages. **Script** shows the complete Markdown, with indentation.
Click a passage to focus its full text below; **Up / Down** scroll it independently.
In the Edit track list, Record passage list or Takes view, keyboard **Up / Down**
selects the previous/next passage; **Page Up / Page Down** moves one visible page.
Selection stays visible and does not start playback or recording. Text-entry and
device-menu focus retain their own keys; capture locks passage navigation.
Its stable ID identifies the recording and matching Bitwig track. Wheel/two-finger
scrolling targets the pane under the pointer; horizontal scrolling or Shift+wheel
pans time. Option+wheel or macOS pinch over the Edit timeline zooms around the
pointer. Timeline/track scrollbars are draggable; **Follow** controls automatic
playback following. Manual zoom turns Follow off. The lower waveform seeks
unless you grab one of its trim edges.

### Record a passage

If the listening-output selector says **Output muted (macOS)**, check that
device's macOS Sound settings. The playback meter can show audio even while the
hardware is muted. Studio reports the selected device's master mute and never
changes system volume or mute automatically.

1. Switch to **Record** and select a voiced passage. The full scrollable script
   is shown with input/FX meters below it. Text-only rows cannot be recorded.
2. Click **Mic / input…** and choose your actual microphone from the list.
3. For processed recording, choose **BlackHole 16ch** for both **Send 1/2 > Bitwig…**
   and **FX return 3/4…**. In Bitwig, monitor an audio track from input 1/2,
   apply its effects, and route its output to 3/4. Do not route it back to 1/2.
4. Choose **Dry** (microphone only) or **FX** (paired dry and processed audio).
5. Click **Record new take** or the top **RECORD** button. Count-in is off by
   default; optionally enable 3 seconds. **STOP** saves the take and FX tail.
   To record another passage, select it and press Record again—no arm step.
6. **Play** / **Listen to take** auditions without recording. **Publish to game** publishes the selected
   validated wet take to the running game. No Bitwig save/export is needed.

For the traditional **Edit** workflow only, the track's red circle selects the
recording target; enable global **REC**, then **Play / REC**. That Edit arm never
overrides the selected passage when pressing Record in the focused Record view.
API: `(studio/command! {:op :record/start :args {:id "voice-your-id"}})` starts
a new take for that stable Markdown ID, using the current Dry/FX mode and count-in.
`:transport/stop` saves or cancels count-in; `:transport/audition` never records.

**Listening output…** selects playback/monitoring output. Live monitoring requires headphones
to avoid acoustic feedback. Selecting a device is not proof of an effects return:
check both input and return meters. With BlackHole selected as the source (for QA),
source audio must arrive on channels 5/6, not the return pair 3/4.
API clients can inspect `(:devices (studio/query))` and use `:routing/select` with
`:source`, `:send`, `:return`, and `:headphones` device indices.

If an owned input/FX device stops unexpectedly, Studio stops capture and retains
available audio as **Interrupted take** entries. An empty return does not discard
the dry recording. Listen before using these partial takes. If saving fails,
**AUDIO STOPPED** means the PCM is retained and **Stop** retries saving. Input
checks and monitoring report their own stopped-device warning; a monitor failure
does not stop healthy capture devices. Studio never silently chooses another mic.

Device menus mark the configured device **Selected**. Up/Down and Home/End move
keyboard focus without changing the route; Enter applies it. Escape or a click
outside closes the menu without activating the control underneath. Empty lists
show a reconnect message, and unavailable pagination buttons are disabled.
The selected marker confirms configuration only—not that audio is reaching it.

**Undo / Redo** (Command/Ctrl-Z and Shift-Command/Ctrl-Z) undo/redo saved
take edits. WAVs are never deleted by undo. Playback stops before changing the
selected take through history. Game publication is an explicit, non-undoable
boundary; undo does not retract audio already published to the game.

**Play mix** prepares and plays the selected take from every recorded passage
simultaneously, with a shared native 48 kHz sample clock and visible playhead.
Play/Pause, Space and seeking then control that mix. **Take mode** or Stop
returns to single-take audition; a track's triangle always auditions that take.
The first mixer supports 16 clips and 120 seconds of total decoded source audio
(about 46 MB of bounded PCM storage), with gain, stereo balance, fades, mute and
solo. Initial gain is `0.5 / clip-count`; the output clamps at ±1 for safety.
Mix preparation fails visibly on invalid/missing media or capacity exhaustion.
No audio callback enters Clojure, reads files or allocates memory.

While in mix playback, **Boucle** repeats the selected region; **A** and **B**
place its start and end at the playhead. Position the ruler first, then click A/B.
The tinted timeline band shows the region (green when enabled). Loop edges use a
half-open, sample-accurate range: A is played, B wraps to A. Enabling a loop with
the playhead outside it starts at A on the next audio block. Pause preserves the
cursor even outside the loop; resume returns to A if needed. Invalid regions are
rejected without changing playback. Stop/re-preparing a mix returns to take mode;
preparing or editing clip settings disables its old loop. Loop selection is
temporary transport state, not yet saved in the project. This first loop is exact,
not automatically crossfaded—arbitrary boundaries may click; choose matching
waveform boundaries or fade the source when preparing seamless material.

This is a **temporary mix snapshot**, not a saved multitrack composition. Stop
before editing clip settings; loading/re-preparing clears those settings. Existing
take edits and their undo history are persistent, but mix offsets/gains are not yet.
The current timeline viewport covers 60 seconds; longer native schedules are not
fully navigable in this first integration. Take edits stop the snapshot so new
waveforms cannot silently replace the source still being heard.

## Control API (existing nREPL)

`(studio/worker-status)` reports worker health without touching native code, even
while a Zig edit fails to compile. After fixing an error, a stopped worker can be
restarted with `(studio/restart-worker!)` without reopening windows or recordings.
This refuses to replace a live worker; already-consumed commands are not replayed.

The UI's recording/playback/editing jobs and external commands use the same
serialized worker. No additional server or JVM is started. Call from a client
thread, not from an audio/render callback:

```clojure
(studio/capabilities) ; implemented operations and argument schemas
(studio/query)        ; immutable transport/selection/takes/view snapshot
(studio/command! {:op :transport/play})
(studio/command! {:op :transport/pause})
(studio/command! {:op :transport/seek :args {:seconds 5.0}})
(studio/command! {:op :selection/passage :args {:id "your-stable-passage-id"}})
(studio/command! {:op :take/name :args {:name "Favorite take"}})
(studio/command! {:op :take/trim :args {:from 5 :to 95}}) ; new WAV, original retained
(studio/command! {:op :transport/stop})
(studio/command! {:op :project/undo})
(studio/command! {:op :project/redo})
(studio/command! {:op :mix/prepare})
(studio/command! {:op :mix/loop :args {:from 1.0 :to 2.5 :enabled true}})
;; Arguments and :view/:loop-selection are seconds; :mix/:loop reports exact PCM frames.
(studio/command! {:op :mix/clip
                 :args {:id "your-stable-passage-id" :start 2 :gain 0.25
                        :pan 0 :fade 0.01 :mute false :solo false}})
(studio/command! {:op :mix/play})
(studio/command! {:op :mix/stop})
(studio/events-since 0)
```

`submit!` returns immediately; `command!` waits up to five seconds. If it returns
`:pending`, poll `(studio/result request-id)`. Supply `:request-id` to deduplicate
retries; reusing an ID for a different command is rejected. Queue capacity is 64;
results and events retain 256 completed entries. Expired results are `:unknown`;
an event cursor with `:resync? true` requires a new query snapshot. Recording
commands completing means *scheduled/started*, not that a future recording is saved.
While recording/counting down, only Stop is accepted. Invalid commands don't stop
capture. Published audio remains an explicit command.

`query` includes the project ID, revision and undo/redo labels. Supply
`:expected-revision` on a command to reject stale edits with `:revision-conflict`;
completion includes `:project-revision`. History is bounded to 64 edits and
persists across reopen. A failed project write leaves the previous in-memory
revision intact. This is a single control-owner document, not cross-process
collaborative editing or a complete multitrack project format.

Trusted dev extensions can call `register-command!` with a namespaced operation,
`:description`, `:validate` predicate and `:handler` function. They run on the
control worker, never the audio thread; keep them bounded. Core operations cannot
be replaced. A handler may use `submit!` for deferred follow-up work, but calling
blocking `command!` from a handler is rejected instead of waiting on itself.
This is not a sandbox or a plugin audio processor API.
Persistent multitrack arrangements, full clip/project editing, MIDI and plugin hosting are subsequent
phases in [the DAW roadmap](DAW_IMPLEMENTATION_PLAN.md), not current capabilities.

## Native workspace architecture

The transport remains at the top, the take/waveform editor below, and the routing
inspector on the right. This is native Aguafria Zig using the **same Vulkan
renderer and serif font atlas**, not a browser or Swing window. Both game and
workspace share the game's Flecs passage entities.

The native recorder and workspace live in `tools/src/la_professeure/tools/`.
The Clojure portion schedules work and allocates unique take paths. The audio
callback, channel routing and WAV decoding/encoding are native. Microphone
capture starts with **Record new take** in Record mode, after the optional
countdown, using the visibly selected input. Edit retains arm + REC + Play;
**Stop** saves a dry WAV under `build/recording/<passage-id>/`. Back up takes;
this directory is ignored by Git. Capture is bounded to 60 seconds at 48 kHz.

Recording in **FX** mode captures while you speak: the selected microphone is sent to
BlackHole 1/2, Bitwig processes it and returns on 3/4. Our tool simultaneously
retains the dry microphone and processed return as paired takes. **Stop**
silences the send, retains the selected effect tail, then saves both WAVs and
selects the processed take. **Listen to take → Publish to game** makes it available to the live
game. No intermediate dry recording pass, Bitwig recording, export or Save is
needed. Bitwig's input monitoring must be enabled on the effects track. Use
headphones if also monitoring externally; never feed the return back to the send.

The track-row triangle and **Listen to take / Resume take** only audition saved
audio, even with REC enabled. In Record mode the main **PLAY** control also only
auditions; **RECORD** starts a fresh take for the selected passage. The clock
distinguishes **PLAYING**, **PAUSED**, **COUNT-IN**, **RECORDING**, and **FX TAIL**.

For a repeatable virtual source, selecting **BlackHole 16ch** as the microphone
reads **5/6** (not 1/2). Route a source there; send and return remain on 1/2 and
3/4. An ordinary microphone uses its first stereo pair. The native recording
devices are explicitly selected; system audio defaults are unchanged. Live takes
reserve the selected tail within the 60-second buffer limit.
The meter uses a square-root amplitude display scale, not calibrated dB.

**Process FX** sends that passage's dry take through BlackHole 16ch channels
1/2 and captures channels 3/4 plus the selected tail. Both ends must explicitly
be BlackHole; other devices are refused for this operation. Bitwig must be
configured to receive 1/2, process the signal and output 3/4, without routing the
return back to 1/2. No audio-device global defaults are changed by this tool.

**Previous / Next** select immutable takes for the focused passage.
**Play** previews the selection through the studio's independent audio output.
**Publish to game** accepts only a processed, non-silent, unclipped take and atomically
writes `resources/voices/<passage-id>.wav`. Take history and selection persist in
`build/recording/project.edn` (schema 1). On first open, the old `takes.edn` is
validated and migrated; the original is retained but is no longer the live index.
Unsupported/corrupt project files fail closed, without falling back to old takes.
Back up that directory: it contains recordings, not
disposable compiler caches.

The game plays published voices in Markdown order when entering a dialogue
branch. Missing recordings stay silent. In development it checks the current
voice every half second and loads a changed WAV without restarting the game;
candidate loading must succeed before the old sound is released. Standalone
builds copy published voices and compile out that polling. Ambience is separate.

## Recording, editing, and routing

- **Transport**: choose a 0/3-second countdown (Stop cancels before opening
  audio), 0/1/3/5-second tail, and automatic alignment. Input/return meters and
  elapsed time update during capture; clipping stays latched until the next take.
  The waveform is normalized for visibility; held meters report digital peak dBFS,
  not physical sound pressure or perceived loudness.
- **Align: on**: estimate dry-to-return delay from up to two seconds of audio,
  searching 0–1 second. A confident, distinct match produces a new aligned WAV;
  dry and full return remain untouched. Silence, non-finite samples, ambiguous
  periodic sounds, or heavily altered effects can be refused without cutting audio.
- **Takes**: click the name field and type (Unicode supported), then
  **Rename**. While editing, Cmd/Ctrl+Z undoes the draft and Cmd/Ctrl+Shift+Z
  redoes it (Ctrl+Y also works). Paste is one edit; up to 32 draft edits are
  retained independently of project Undo. Mark A, select B, and alternate
  **Compare A/B**. Drag the amber
  edges of the lower waveform to select a percentage range; **Reset**
  restores 0–100%. **Trim copy** creates another immutable take.
  **Favorite** persists your preference; publishing remains explicit.
- **Recover**: after an interrupted session, recover WAVs from the last committed
  PCM checkpoint. The worker flushes audio and atomically updates its manifest
  approximately once per second. Uncommitted audio may be lost. Original PCM
  remains available; this is process-crash recovery, not a backup/power-loss guarantee.
- **Listening output stopped**: playback pauses and retains its position. Press
  **Resume** to retry that output, or explicitly choose another listening device.
  The top transport controls the active mix even in Record mode; **Listen to
  take** auditions the selected take instead. Recovery never changes audio files.
- **Routing**: name/save a profile and use **Next profile → Reconnect** to
  re-enumerate and resolve exact device names. Missing or duplicate names fail
  closed. Profiles store our source/send/return/headphone choices and timing,
  not Bitwig's plugin state or system defaults.
  Expand **Routing tools +** for profile management, reconnect, **Process take FX**
  and **Recover takes**. Input/output selectors and monitoring remain visible
  when this section is collapsed. Device controls are disabled during recording
  or an input check; stop that activity before changing the route. Automation
  can expand it with `{:op :view/routing-tools :args {:visible true}}`.
- **Monitoring** is opt-in for the next FX take, off after reconnect.
  The **Monitor level** fader controls only live headphone monitoring, from 0%
  (silent) to the existing 50% cap. Click or drag it; this does not enable
  monitoring, alter recordings, or change saved-take playback gain. Automation
  uses `{:op :routing/monitor-level :args {:percent 15}}`; `query` reports the
  current value under `:monitoring`. The level can be adjusted during capture
  without changing the capture target or route.
  It reads only return 3/4 and never writes back to the effects bus. Gain starts
  at 15%, capped at 50%. Only names containing Headphones, AirPods, or Casque
  are currently accepted; speakers/BlackHole/aggregate outputs are refused.
  This conservative name filter does not identify every USB audio interface.

Paired Bitwig routing, UI actions, recovery, and live game publication have been
tested; see `RECORDING_STUDIO_PLAN.md` for evidence. Physical headphone listening
and a spoken microphone session remain unverified on this setup. Bitwig is an
effects processor here: no Bitwig Save or export is needed to record in our tool.

Call `(studio/close!)` before changing recorder resource layouts or restarting
the tool. Do not hot-reload the audio callback while a recording/pass is active.

From the **game root**, run `clojure -M:dev:studio-test` for native channel
isolation/bounds, WAV round-trip, Flecs lifecycle, alignment, trimming, recovery,
and routing-name tests, plus timeline mapping, zoom limits, and trim bounds. This test
does not open a microphone or audio output. Take files created by the codec test
are in a newly allocated system temporary directory.

Development-only Clojure adapter for Bitwig. Markdown parsing and binary asset
compilation run through `la-professeure.recording-tool`, written in Aguafria Zig.
The game loads the compiled dialogue asset; it never needs Bitwig or this adapter.

## Start

From this `tools` directory, with Java 23 or newer:

```sh
clojure -M:dev:repl --bind 127.0.0.1
```

Connect your Clojure editor to the printed port. This project uses the repository's
Aguafria checkout. To use the **same JVM as the game** instead, launch from the
game root with `clojure -M:dev:tools:desktop`; the commands below then need paths
relative to the game root, without the leading `../`.

```clojure
(require '[la-professeure.bitwig :as bitwig]
         '[la-professeure.dialogue :as dialogue])

(bitwig/start!) ; loopback only, OS-assigned port
(bitwig/install! (str (System/getProperty "user.home")
                     "/Documents/Bitwig Studio/Controller Scripts"))
```

In Bitwig, add **La Professeure → Dialogue recording** under **Settings →
Controllers**. After restarting the adapter, reinstall the script and reload its
controller so Bitwig gets the new port (toggle this controller's power off/on).
No MIDI device is required.

Create and save a new, flat Bitwig project for this scene. The adapter uses real
Bitwig API calls; it does not fabricate `.bwproject` files. Initial creation/save
is a Bitwig UI step. Save subsequent edits in Bitwig normally.

```clojure
(bitwig/inspect!) ; verify the active project name before synchronizing

(def source "../resources/dialogue/la-voiture.md") ; same input selected in ../dialogue.edn
(def directory "../build/recording/la-voiture")
(def prepared (dialogue/compile! source directory
                               (str directory "/story.lpdialogue")))

;; Inspect scene IDs and complete cues before touching the DAW.
(mapv #(select-keys % [:scene :recording-id :voice :text])
      (:passages (:manifest prepared)))

(def scene (:scene (first (:passages (:manifest prepared)))))
(def project "La Voiture Recording") ; use the exact name of your new recording project
(bitwig/sync-source! source directory project scene)

;; Optional explicit import into the passage track's empty first launcher slot.
;; An occupied slot is refused; synchronization never imports or replaces audio.
(bitwig/import-take! project (:recording-id (first (:passages (:manifest prepared))))
                    "../resources/demo/lesson.wav")
(bitwig/save-project! project) ; ordinary Save, not Save As
(bitwig/inspect!) ; :modified false confirms Bitwig has finished saving

;; Optional: keep this particular scene/project synchronized.
(def stop-watching! (bitwig/watch! source directory project scene))
(stop-watching!)
(bitwig/stop!)
```

Use a Markdown export for Obsidian notes whose vault is inaccessible to the
process. The adapter never rewrites the source note.

## Listening in the studio

Click the triangle beside a recorded passage to audition its selected take.
`Listening output` chooses the device for both take playback and the mix. The
studio and game share engine code but own independent engine/device instances;
game mute and studio focus cannot mute the studio's playback.

`Monitoring` means microphone/FX monitoring **during recording**, not playback
of saved takes. It requires headphones to avoid feedback. Bitwig send 1/2 and
return 3/4 remain separate from the listening output.

For a quiet take, toggle `Original gain` to `Audition boost`. This bounded,
opt-in audition gain does not alter the WAV, Bitwig effects, mix, or published
game voice. The API equivalent is
`{:op :playback/boost :args {:enabled true}}`. Very quiet input/FX recordings still
need their recording gain corrected; audition amplification is not that fix.

During capture, the routing panel shows input and FX **peak dBFS** (held since
the start of the take), separately from the moving bars. Stop ends the send,
then `FX TAIL` keeps recording the chosen effects tail before saving automatically.
Silence, clipping and very low levels produce a persistent warning bar. These
are advisory recording checks; files are retained. `X` or `{:op :alert/dismiss}`
acknowledges the warning. The API exposes the warning in `:alert`, linear held
peaks in `:signal`, and effects-tail capture as `:record-control :phase 3`.

## Identity and recording safety

```markdown
# Le seuil ^le-seuil
#∆V La pluie continue. [id:rain]
:: Entrer.
  #∆M Je vous attendais. ^welcome
```

`#∆V` means la voiture; `#∆M` means la mangue. `#V/#M` and Greek `#ΔV/#ΔM`
are accepted too. Indentation puts the following passage inside a `::` choice.
Identifiers are hidden from spoken/displayed text. Use explicit IDs for scenes
and voiced passages when recording: arbitrary rewrites and moves then retain
identity. Automatic matching handles unchanged passages and unambiguous edits;
ambiguous edits fail with a request for explicit IDs.

Each passage gets one `[LP:id]` audio track. Updates can rename its generated cue
but do not change clips, recordings, effects, routing, gain or arm state. Keep the
`[LP:id]` prefix when giving a track your own name; that name will be preserved.
Removing the prefix makes the track unrecognizable to the adapter.

`recordings.edn` retains IDs, revisions, expected export paths and review status;
`cues.md` contains readable recording text. Preserve/back up this manifest along
with the Bitwig project, especially when relying on automatic IDs. Removed cues
are archived, never deleted from Bitwig. Changed text becomes `:needs-review`;
the adapter cannot judge whether a take has been rerecorded. Export/recording
remains an explicit human action. `import-take!` can explicitly place a WAV in an
empty first launcher slot; it refuses an occupied slot. This version does not
automatically export WAVs. Use the studio's explicit effects pass and publication
controls to link individual voice recordings into game playback.

Current safety limits: a flat project, up to 255 main tracks including unrelated
tracks, and an exact active-project name match. Two projects with the same name
cannot be distinguished; use unique recording-project names. Do not switch,
reorder, or create tracks while synchronization is in progress. On timeout or
disconnect, inspect the project before retrying; partial additions are retained.

## Verification

For a live desktop started with `:studio-test`, GPU rendering can be checked from
nREPL without relying on the desktop compositor's cached window image:

```clojure
(require '[la-professeure.studio-test :as qa])
(qa/capture-window-qa! true "build/recording-qa/studio.png") ; false for game
```

Call this off the render thread while recording is idle, with an existing output
directory. It queues native work, replays the latest normal frame through Vulkan,
and writes a PNG plus returns frame metadata. It briefly waits for the GPU and
recreates that window's targets; use it for QA, not per-frame recording. It neither
opens audio inputs nor publishes a take. GPU evidence does not replace physical
mouse/trackpad or operating-system window-composition checks.

```sh
clojure -M:dev:test
node test/controller_test.js
```

The Clojure tests exercise real loopback sockets, framing, Unicode, reconnects,
installation guards and scene plans. The JavaScript tests run the installed
controller source against a simulated Bitwig host to check additive updates and
preservation. These simulated host checks are supplemented by **real Bitwig
5.3.12 acceptance testing**: four tracks created, repeat sync without duplicates,
a WAV clip retained through text edits, a custom track name preserved, a removed
cue's track retained, a new cue added, and save/close/reopen retaining all seven
tracks (including two unrelated tracks) and the audio clip. See `../QA.md`.

## Dialogue recording status

Record-mode passage rows indicate whether a take is missing, matches the current
script, changed, interrupted or unverified. New recordings remember the speaker
and text version at capture start; later script edits never rewrite that history.
Old/imported takes without this metadata require review. “Recorded” describes a
script match, not audio quality or publication. `studio/query` exposes this under
`:dialogue`, alongside the watched source's status.
