# Studio design directions

Five AI-generated visual proposals, 2026-09-09. These are **not screenshots of
implemented features**. The exact built-in generation prompts are in
[PROMPTS.md](PROMPTS.md). Images were inspected individually.

## Decision

The user selected the **light editing desk** as the main visual direction, with
task-specific modes such as the **script-first booth** during dialogue recording.
Use the light palette consistently; borrow the booth's focus and hierarchy, not
an unrelated dark theme. The user also selected concept 5's clear controls/icons
as a shared reference and requested an optional take-grid layout. Mode changes
must preserve audio, recording ownership, selected passage and project data.
Requested workflow exception: entering Record temporarily enables global REC;
leaving restores its previous setting. This must never itself start or stop capture.
Future modes may add workflows, but their actions use the same command API and
history rather than creating a second engine or hidden business-logic path.

| Concept | Strongest use | Keep | Avoid copying literally |
|---|---|---|---|
| [1. Recording console](01-recording-console.png) | Timeline editing | Explicit transport and routing | Duplicated transport; busy chrome |
| [2. Daylight editing desk](02-daylight-editing-desk.png) | Default workspace | Clear sans-serif controls, blue selection, quiet rules | Decorative/unsupported controls; cramped dialogue inspector |
| [3. Script-first booth](03-script-first-booth.png) | Recording dialogue | Large complete script, visible input and arm state, take audition | Hidden routing details; unnecessary top/bottom transport duplication |
| [4. Routing workbench](04-routing-patchbay.png) | Configuring external FX | Separate send/return/output, signal-flow explanation | Invented latency or connection status; suggesting an output feeds the input |
| [5. Take launcher](05-take-launcher.png) | Comparing takes | Dry/FX/favorite distinction, per-take audition | Implying full musical clip-launcher scheduling already exists |

## Reference findings

- Bitwig separates track arm, global Record enable and transport Play. Its input,
  output and monitoring controls have distinct jobs. Preserve those semantics,
  but explain the selected track's readiness near the recording controls.
  [Recording clips](https://www.bitwig.com/userguide/latest/recording_clips/).
- Bitwig keeps persistent transport controls and prioritizes menus when space
  becomes narrow. Use icons plus readable labels for primary actions and put
  less frequent operations in task-specific areas.
  [Transport and menus](https://www.bitwig.com/userguide/latest/the_window_menus_transport_area/).
- Live distinguishes linear arrangement from clip launching. Its overview and
  show/hide controls guide our navigation; do not call an independent-take list
  an arranger until it actually schedules editable arrangements.
  [Arrangement](https://www.ableton.com/en/live-manual/12/arrangement-view/),
  [Session](https://www.ableton.com/en/live-manual/12/session-view/).
- REAPER's scripting surface is a useful DX reference: user-facing operations
  should also be available as discoverable commands, not separate hidden paths.
  [ReaScript](https://www.reaper.fm/sdk/reascript/reascript.php).
- IBM Plex Sans provides readable workbench typography, with its OFL license
  retained alongside the bundled font. Keep Libre Baskerville for authored prose.
  [IBM Plex](https://github.com/IBM/plex/).

## Implementation and acceptance

- [x] First native pass: light neutral surfaces, blue selection, red for record/error states;
  readable secondary labels, no stretched type when resizing.
- [x] Sans-serif UI metrics and matching caret/hit-testing; serif dialogue body.
- [x] Explicit Edit / Record modes, also exposed via the existing command API.
- [ ] Record mode shows complete scrollable dialogue, current input/return meters,
  armed passage, clear capture state, and saved-take playback. Mode switching is
  available while recording and never changes devices or transport.
- [x] Keep mode-specific pointer/scroll regions isolated: invisible edit controls
  cannot respond in Record mode. Dismiss stale drag state when switching.
- [ ] Verify actual native screenshots and physical clicks at minimum and larger
  sizes, record/playback transitions, long dialogue and hot reload.
- [ ] Later: take-rack browsing and a dedicated routing workspace. No decorative
  buttons for unimplemented operations or fabricated diagnostics.
- [ ] Shader polish after interaction acceptance: antialiased icons/edges, subtle
  panel separation and selection feedback. Keep text crisp, avoid bloom/noise over
  controls, honor reduced motion, and measure frame cost at Retina scale. Verify
  development shader reload and release rendering; no cosmetic pass substitutes
  for correct hit testing, input routing or legible contrast.

The images contain invented sample content and design-only elements (command
search, MIDI-style controls, latency badges, etc.). Only implemented operations
belong in the actual application. In particular, no background music is enabled.

Native QA checkpoint: 14 tests / 93 assertions passed. Fresh Edit and Record
screenshots inspected at 1427×881; Record also inspected at the 1100×760 minimum.
Physical tab clicks and saved-take audition passed, including uninterrupted
playback during switching. Project/take unchanged and the original paused cursor
restored. Capture transitions, long text and broader resize acceptance remain open.
