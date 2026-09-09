# La Professeure — verification, 2026-09-08

Tested on macOS / Apple M3 Max, MoltenVK 1.4.0, embedded Zig 0.16.0.
This is infrastructure and a narrative scene placeholder, not a finished game.

## Passed

- `clojure -M:dev:test`: 2 tests, 19 assertions, no failures/errors. Covers
  animation-sync validation and transactional shader compilation, including
  invalid source and recovery without modifying either published SPIR-V stage.
- Both SPIR-V stages passed `spirv-val --target-env vulkan1.2`.
- Actual GLFW/Vulkan windows captured and inspected, not just a build check.
  Serif text, French accents, backdrop, rain frames and response choices render.
- Same JVM on nREPL 57480: native scene and text declarations edited/evaluated
  while running. Renderer allocation was explicitly rebuilt at a frame boundary
  for the larger atlas; JVM, window and audio engine were preserved. This resource
  size change is different from ordinary shader/body edits.
- Shader edit made the stage visibly brighter while UI colors stayed unchanged.
  Intentional GLSL syntax error produced a file/line diagnostic, retained both
  published SPIR-V hashes and the active pipeline, and kept animation running.
  Correcting the source restored the original lighting without a restart.
- Configurable animation-export watcher: valid PNG loaded, malformed PNG
  rejected with the previous visuals retained, valid replacement recovered.
  This test used a local exported file, **not a real iPad device**.
- Audio: atomically replacing the generated WAV with invalid content kept
  `audio_ready=true`, revision 1, and incremented the failure counter. Restoring
  the valid WAV installed revision 2, in the same engine/window. No direct
  Bitwig integration or subjective audio-quality assessment is claimed.
- Native animation function: times `[0, .125, .5, 1]` at 8 fps returned
  frames `[0, 1, 4, 0]`.
- Live `animation-fps` constant edit 8 → 12 installed without restarting:
  native snapshot at time 911.5507 reported frame 2, matching floor(time×12)%8.
  Restored 8 fps. SPACE held the timeline at 854.8301 while rendered frames
  advanced from 102358 to 106965; a second SPACE resumed it.
- Moving the real pointer into the stage updated GPU light coordinates to
  `[0.27272728, 0.39473686]`; the resulting scene was captured and inspected.
- Keyboard input delivered to the dev window changed the dialogue response;
  F1 showed/hid the developer atelier.
- `clojure -M:dev:standalone`: ReleaseFast native executable built (latest
  reported compile time 6.710 s). Launched from `build/standalone`, captured
  the updated narrative view and clicked choice 2: the displayed response
  changed to “Un souffle. Ou le vent, peut-être.”
- Initial native executable was about 1 MiB. `otool -L` showed system frameworks
  and the external Vulkan loader, no JVM or project-built shared miniaudio/GLFW.
  Resources are separate; this is not a self-contained macOS app bundle yet.

Current screenshots (regenerable, git-ignored): `build/qa/narrative-dev.png` and
`build/qa/narrative-standalone.png`. The standalone QA window was closed after
testing; the updated nREPL dev window was left running.

## Remaining / boundaries

- Choose/test the user's actual iPad authoring app and delivery path. The current
  hook watches an exported PNG, including a locally synchronized folder. There
  is no direct network discovery, tablet app, or guaranteed device-to-game latency.
- Lighting currently modulates the painted layer with a radial light; it is not
  a normal-mapped/occlusion/shadow system. The backdrop also contains painted light.
- Higher-level animation manifests, full Unicode shaping, multiple audio stems
  and crossfades are future expansion, not implemented claims.
- Native input was checked with keyboard in dev and mouse in standalone;
  automated focus/click delivery to the Java-owned window was inconsistent.
- Other OSes and packaged Vulkan/MoltenVK redistribution are not certified.

## Startup issues fixed during testing

C-import include paths are scoped to the miniaudio Zig module. GLFW explicitly
uses the linked Vulkan loader. Font-atlas generation runs with Java AWT headless
so it cannot capture macOS's event loop from GLFW. The latter required a JVM
restart; subsequent visual and asset iterations used the same JVM.

## Markdown / Bitwig acceptance — 2026-09-09

### Actual author note selected (follow-up)

The previous screenshots used our `le-seuil.md` sample, not the author's note.
Copied **La Voiture** through Obsidian's UI without changing the original note.
`dialogue.edn` now selects `resources/dialogue/la-voiture.md` for dev and
standalone. Every tab normalizes to four spaces in the native parser, including
inline tabs; raw Markdown is preserved. Removed the hard-coded story banner and
placeholder subtitle. All narrative text, headings and choices come from Markdown.

Verified the real branches through the same live JVM: root choices are
`Se retourner.` / `Inspecter la voiture.`, followed by `Ouvrir la poignée.` /
`Frapper à la porte.`, then `Lire l’autocollant.`. Native screenshots:
`build/recording-qa/la-voiture-root.png`, `la-voiture-inspect.png`,
`la-voiture-open.png`, `la-voiture-sticker.png`.
Tests: 7 tests, 58 assertions, zero failures/errors; includes native/Clojure
parser parity for the copied note, tab normalization, exact branch choices and
missing-source rejection. This local copy does not automatically track iCloud;
edits to the selected readable Markdown file do hot reload.

The updated standalone rebuilt successfully (Zig compile/link 8.790 seconds),
launched and rendered the same opening text and two choices. Its compiled story
asset was byte-identical to dev's; screenshot `la-voiture-standalone.png`.
Closed the standalone QA process afterwards; left the existing dev JVM running
at the opening scene.

### Earlier recording-adapter acceptance

- Final automated suites: game/parser 5 tests / 50 assertions; adapter 5 tests /
  25 assertions; controller contract checks passed, including ordinary-Save
  guards, occupied-slot refusal and recovery after a failed import.
- Real Bitwig Studio 5.3.12, isolated project `La Professeure Dialogue QA`:
  four voiced passages created four audio tracks beside two existing tracks.
  Repeating sync created nothing. Imported the demo WAV into `rain`; its clip
  name was `lesson` and its waveform was visible. This was an imported test tone,
  not a microphone recording.
- The source watcher handled edited `rain` and `knock`, removed `listen`, and
  added `window`. The existing audio clip survived. A custom `knock` track label
  (set through the controller API to simulate a user edit) survived. Removed
  `listen` stayed in the DAW, archived in the manifest. Seven tracks remained,
  including both unrelated tracks. Importing over an occupied slot was refused.
- Ordinary Bitwig Save completed (`:modified false`); closing and reopening the
  native `.bwproject` reproduced the exact track-name/first-clip snapshot.
  QA project: `build/recording-qa/La Professeure Dialogue QA/`.
- Same game JVM and window throughout: selected nested choices on the render
  thread, edited the example Markdown live, verified the changed text in a native
  screenshot, then supplied an unknown speaker and verified the prior valid text
  stayed visible while a line-numbered error was reported. Restored the sample.
- Six-choice and two-scene fixtures verified choice pagination and scene switching.
  French `œ`, curly quotation marks, em dash and ellipsis use the actual font
  glyphs. These are not a claim of general Unicode shaping support.
- Latest ReleaseFast binary launched and its root dialogue rendering was captured
  and inspected. This final build's input was not retested: macOS UI automation
  reported the Mac locked. Nested interaction was verified in the dev runtime;
  earlier standalone keyboard/mouse verification is recorded above.

Screenshots under `build/recording-qa/`: `game-nested.png`,
`game-hot-reloaded.png`, `game-invalid-retained.png`,
`game-choice-page-two.png`, `game-second-scene.png`, `game-standalone.png`.
The user's Obsidian note was never modified. Initial Bitwig project creation and
save location are UI steps; track synchronization is automatic once enabled.
