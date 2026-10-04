# La Professeure

Aguafria Zig narrative-RPG prototype, not an educational game. Currently text-only,
with Markdown-driven French dialogue in Libre Baskerville and typewriter reveal.
Art, animation and lighting assets are retained for later use.
Rendering is direct Vulkan. Vertex and atlas data share one mapped allocation,
exposed to the Aguafria SPIR-V shaders through two storage-buffer bindings.
GLFW handles window/input; miniaudio handles sound. No raylib or other game engine.

Run from this directory:

```sh
clojure -M:dev:prepare
clojure -M:dev:desktop
```

Connect your editor to `.nrepl-port`. The project uses this Aguafria checkout
for ordinary runs and precompilation. The `:dev` alias enables asynchronous
native publication and fast optimization. See the comment in
`la-professeure.core` for the live workflow. macOS native windows require the
desktop entry point's main-thread JVM option.

In desktop dev mode, saved edits to `src/la_professeure/shaders.clj` compile through
Zig into one validated SPIR-V module and replace the pipeline on the render thread.
Invalid edits retain the previous pipeline; diagnostics appear in the REPL
process output and `@la-professeure.core/status`. Disable this watcher with
`-J-Dla-professeure.shader-reload=false`. This is independent of Zig's
optimization mode. Standalone contains no JVM watcher or shader compiler.
Studio follows successful game shader publications in its own renderer.
Evaluating a shader form in memory alone does not trigger the file watcher.
Shader logic edits must preserve the current vertex/root-data interface;
GPU resource/layout changes require an explicit safe resource rebuild.

## JVM precompilation

```sh
clojure -X:dev:precompile
```

Use the same `:dev` compiler settings as the desktop session. Preparation
analyzes the game and Studio namespaces without calling their native bodies.
Supported handlers are linked into one immutable bundle in `~/.aguafria/zig`;
the coverage report is saved to `.aguafria/precompile/game-studio.edn`.
Ordinary JVM calls use the same artifact keys and lookup: an AOT hit loads that
bundle, while a new signature or changed compiler configuration can compile a
new artifact. Native namespace/state images remain separate from the handlers.

## Playing

Press **1–3** or click a response. **Space** reveals the text immediately;
**Backspace** returns to the parent choices. **F1** switches to the separate Studio
window; **M** mutes dialogue audio. Background music is disabled.

`dialogue.edn` selects the Markdown input for both dev and standalone builds.
It now points directly to the author's original Obsidian `La Voiture.md`.
This machine-specific path requires macOS permission to read iCloud Drive.
All scene text and `::` choices come from that file, including
nested branches. Tabs are normalized to four spaces without rewriting the source.
`le-seuil.md` is only a
test sample, not the game's default story. Nothing silently falls back to it.
In dev mode, saving the Markdown or changing `:source` reloads automatically;
invalid edits retain the previous valid dialogue and report an error in
`@la-professeure.core/status`. Use `-J-Dla-professeure.dialogue=/path/story.md`
to override the file for a dev session or standalone build.
The old project copy is not watched. `:recording-registry` explicitly retains
the existing recording-ID sidecar when moving to the original source, so changing
the path does not start a new identity registry. The tool never edits the note.
The development-only [Bitwig adapter](tools/README.md) has its own
Clojure `tools/deps.edn` project, or can run in the game's JVM via `:tools`.

The iPad is an **animation-authoring device during development**, not a gameplay
screen. Export an eight-frame 768×96 PNG spritesheet to
`resources/demo/animation.png`, or select a synced export path at launch:

```sh
clojure -J-Dla-professeure.animation=/absolute/path/to/export.png -M:dev:desktop
```

Valid PNG changes are repacked off-thread and uploaded at a frame boundary;
invalid exports keep the previous visuals. This works with a locally synced
file; direct iPad networking/app integration is not implemented or device-tested.
Bitwig follows the same export workflow with `resources/demo/lesson.wav`.
Export to a temporary file and atomically rename it to avoid partial writes.

Standalone, without the JVM:

```sh
clojure -M:dev:standalone
cd build/standalone
./la-professeure
```

The current backend requires Vulkan 1.2 buffer device addresses and scalar block
layout. macOS uses MoltenVK; install the Vulkan SDK/loader and SPIRV-Tools
(`spirv-opt` and `spirv-val`) first. Shader sources use Aguafria and Zig's
`SpirvType`; there is no GLSL build step.
Zig itself is supplied by Aguafria. Assets are explicitly placeholder art.

Implementation and verification status: [plan](IMPLEMENTATION_PLAN.md),
[QA](QA.md). Asset and font provenance: [art notes](resources/art/ART_NOTES.md).
