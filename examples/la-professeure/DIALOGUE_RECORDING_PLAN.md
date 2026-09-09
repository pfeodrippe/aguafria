# Interactive Markdown → recording sessions

## Source inspected

Obsidian's **La sociologue / La Voiture**, inspected in the application on
2026-09-08. The user clarified that the tags contain the increment/delta glyph:
`#∆M`, not `#AM`. The original iCloud note is read-only input, never rewritten.
Filesystem access to that vault is currently denied by macOS; use a user-exported
Markdown file for automatic watching. Do not bypass that restriction.

## Authoring contract

- Markdown headings name scenes.
- `#∆V` / `#V` identify **la voiture**; `#∆M` / `#M` identify **la mangue**.
  Also accept the visually similar Greek `Δ`. The initial native parser supports
  these two speaker codes; additional codes require extending its validation.
- `:: Choice text` starts a choice. Indentation nests its response and subchoices.
  Tabs normalize to four spaces in the native parser; the author need not reformat notes.
- Consecutive text lines form a passage; a blank line starts another passage.
- Optional `[id:block-id]` or Obsidian `^block-id` pins a passage's identity across arbitrary moves.
  Without it, a sidecar matches unchanged passages first, then unambiguous edits.
  Ambiguous identity changes fail instead of attaching an old recording to new text.
- Unknown speakers and malformed indentation report source line numbers.
- No Markdown, speaker marker, or internal identifier is spoken by the voice actor.

## Recording mapping and safety

Native parser/compiler: `src/la_professeure/recording_tool.clj`, Aguafria Zig.
It runs inside nREPL or as a standalone `dialogue-tool source.md output.lpdialogue`.
The game consumes the compiled asset; native development entry points are eliminated
when unreferenced. The Clojure adapter is a separate `tools/deps.edn` project in this
example, as requested. Its tiny JavaScript peer exists only to call Bitwig's API.
The pure Clojure parser is currently a differential test oracle, not a runtime fallback.

Initial grouping: one real Bitwig project per scene, one audio track per voiced
passage. Native `.bwproject` files are saved
by Bitwig itself, not synthesized by pretending its proprietary format is JSON.
Use Bitwig's installed controller API for track creation and update, and its UI
for initial project creation/save where the API has no filesystem-save operation.

Each owned track has a stable `[LP:<id>]` prefix, speaker label, and short cue.
A versioned sidecar holds the complete text, branch context, source line, revision,
expected WAV export path, and recording review status. Text edits mark a recording
as needing review, not delete it. Removed passages remain archived in the sidecar;
tracks, clips, takes, effects, routing, arm state and user-created tracks are never
deleted or reset. Never record, start transport, or export over existing takes.
Only sync to the explicitly selected project; project-name/identity mismatch fails.
Auto-sync must be explicitly enabled for a source and project.

## Implementation / acceptance checklist

- [x] Native parser, branching structure, French text, source diagnostics.
- [x] Stable-ID reconciliation: insert, edit, reorder, remove, ambiguous changes.
- [x] Recording manifests and compact actor cue sheets; atomic writes.
- [x] Bitwig controller script + localhost development connection; additive sync
  verified with protocol tests and the real DAW.
- [x] Actual new Bitwig test project with generated audio tracks, saved natively.
- [x] Edit and resync: no duplicate tracks and pre-existing audio clip retained.
- [x] Markdown preview in the native game, with nested choices and hot updates.
- [x] Tests and documented author workflow, including limits of project creation.

2026-09-09 verification: game/parser suite 5 tests, 50 assertions; Clojure adapter
suite 5 tests, 25 assertions; JavaScript controller contract tests passed. Native
ReleaseFast dialogue compiler ran independently (388 KiB, only libSystem linked)
and produced the same binary document as the JVM-hosted native compiler.
The game was visually verified in the same running JVM: nested choices, text
updates, French punctuation, paginated choices and multiple scenes. Invalid input
left the last working dialogue visible. The latest ReleaseFast game was built,
launched and screenshot-verified (about 1 MiB executable). Real Bitwig track
updates, clip/name preservation and native save/reopen passed. See `QA.md` for
the precise checks and `tools/README.md` for setup and safety limits.

## References / verified API surface

- [Bitwig controller installation](https://www.bitwig.com/support/technical_support/how-do-i-add-a-controller-extension-or-script-17/).
- [Official controller API entry point](https://www.bitwig.com/userguide/latest/midi_controllers/).
- Installed Bitwig Studio 5.3.12 API: `Application.createAudioTrack(int)`,
  `Application.projectName()`, `Track.name()`, `ControllerHost.connectToRemoteHost`,
  `RemoteConnection` (incoming messages have a big-endian 32-bit length header).
  Project API does not expose a direct save-to-path method.
