# La Professeure

Aguafria Zig narrative-RPG prototype, not an educational game. Painted atmosphere,
French dialogue in Libre Baskerville, animated rain and 2D lighting.
Rendering is direct Vulkan, using GPU-address-based data inputs inspired by
[No Graphics API](https://www.sebastianaaltonen.com/blog/no-graphics-api).
GLFW handles window/input; miniaudio handles sound. No raylib or other game engine.

Run from this directory:

```sh
clojure -M:dev:prepare
clojure -M:dev:desktop
```

Connect your editor to `.nrepl-port`. The `:dev` alias uses this checkout; without
it the project resolves the published Aguafria artifact. See the comment in
`la-professeure.core` for the live workflow. macOS native windows require the
desktop entry point's main-thread JVM option.

In desktop dev mode, edits to `resources/shaders/mesh.vert` or `mesh.frag`
compile in the background and replace the pipeline on the render thread.
Invalid edits retain the previous pipeline; diagnostics appear in the REPL
process output and `@la-professeure.core/status`. Disable this watcher with
`-J-Dla-professeure.shader-reload=false`. This is independent of Debug versus
ReleaseFast optimization. Standalone contains neither the JVM watcher nor glslc.
Shader logic edits must preserve the current vertex/root-data interface;
GPU resource/layout changes require an explicit safe resource rebuild.

Press **1–3** or click a response. **F1** toggles the development atelier;
SPACE pauses animation, L toggles the light, and M mutes audio.

`dialogue.edn` selects the Markdown input for both dev and standalone builds.
It now points to `resources/dialogue/la-voiture.md`, copied from the author's
Obsidian note. All scene text and `::` choices come from that file, including
nested branches. Tabs are normalized to four spaces without rewriting the source.
`le-seuil.md` is only a
test sample, not the game's default story. Nothing silently falls back to it.
In dev mode, saving the Markdown or changing `:source` reloads automatically;
invalid edits retain the previous valid dialogue and report an error in
`@la-professeure.core/status`. Use `-J-Dla-professeure.dialogue=/path/story.md`
to override the file for a dev session or standalone build.
The copied note is not a live iCloud connection: edit the local copy, or point
`:source` at a readable synced Markdown file. The original note is untouched.
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
layout. macOS uses MoltenVK; install the Vulkan SDK/loader and `glslc` first.
Zig itself is supplied by Aguafria. Assets are explicitly placeholder art.

Implementation and verification status: [plan](IMPLEMENTATION_PLAN.md),
[QA](QA.md). Asset and font provenance: [art notes](resources/art/ART_NOTES.md).
