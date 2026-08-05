# Simple Game — Superseded Circle-Demo Plan

This original circle/counter plan was explicitly replaced by
`COCO_HOUSE_FACTORY_IMPLEMENTATION_PLAN.md`. It remains only as historical
context; its implementation and web-specific architecture are not active.

This example is a hand-written Aguafria game, not a Zig-to-Clojure conversion.
Its purpose is to prove that an ordinary game can keep native Zig performance
and C-library interoperability while gaining a Clojure/nREPL development loop.

## Product slice

The first playable scene contains one circle and a counter:

- The circle is idle until the pointer enters it.
- Hovering animates the circle continuously.
- Clicking the circle increments a counter rendered to its right.
- Every click emits a small bounded burst of Box3D sphere particles from the
  large circle and plays a short miniaudio sound.
- Counter values select five visibly different fragment-shader effects; the
  shader selection repeats with `counter % 5`.
- Three French UTF-8 phrases with accents are loaded from three real Source
  family TrueType fonts and rasterized through stb_truetype on both targets.
- A generated six-frame water-orb animation is loaded through the same native
  animation API from either one spritesheet or a list of individual sprites.
- The bottom-left timing display reports rolling CPU work for the last 120
  frames, excluding Flecs target-FPS sleeps and swap/present waits.
- Gameplay, GLFW input, Flecs timing, scene construction, effects, counter
  glyphs, and FPS glyphs are the same Aguafria code in desktop and browser
  builds. Only the final graphics operation is target-specific.

## Architecture

```text
Clojure/nREPL
    |
    v
Aguafria Vars (components, systems, frame, platform backends, shaders)
    |
    +---- generated Flecs bindings ---- upstream Flecs C master
    |
    +---- generated Box3D bindings --- upstream Box3D C API/main
    |
    +---- generated miniaudio bindings + implementation
    |
    +---- generated stb_truetype + stdio bindings ---- runtime TTF loading
    |
    +---- shared animation pack ---- spritesheets or individual PNG sprites
    |
    +---- generated platform bindings
              |
              +---- desktop: GLFW + Vulkan
              |
              +---- web: Emscripten GLFW + WebGL 2
```

`simple-game.host/frame!` is the single native input/Flecs frame for both
targets, and `simple-game.scene/draw-frame` is the single scene renderer. The
Vulkan and WebGL backends each implement only the graphics operation needed to
execute that shared scene. JavaScript instantiates the Emscripten module and
calls its native start function; it owns no pointer state, clock, game loop,
game state, FPS calculation, or drawing.

The application code never hides Zig source in `az/defraw`. External C symbols
are produced mechanically: `zig translate-c` translates a header to Zig and
the existing Aguafria converter translates that Zig into ordinary namespaces
containing `az/defextern`, `az/defconst`, `az/defstruct`, and related forms.

There is no application-owned C bridge. Flecs, GLFW, Vulkan, and browser C
headers become require-able Aguafria namespaces, and the application calls
those external APIs directly from pure Aguafria-generated Zig. Flecs is
compiled from the vendored upstream amalgamated C source for both development
libraries and standalone artifacts; all game/platform code remains inspectable
and hot-reloadable as Aguafria Vars.

## Dependency policy

- [x] Vendor the current `master` of `SanderMertens/flecs` and record its exact
  commit in `vendor-lock.edn`.
- [x] Vendor the current `master` of GLFW for native window/input/Vulkan surface
  creation and record its exact commit.
- [x] Vendor current Khronos Vulkan headers and record their exact commit.
- [x] Add Box3D `main` and miniaudio `master` as real submodules and record
  their exact commits.
- [x] Add Source Sans, Source Serif, and Source Code Pro as real submodules and
  record the exact release commits used by runtime font loading.
- [x] Provide an idempotent update command which fetches the newest configured
  branches, updates the lock data, and regenerates bindings.
- [x] Never require an existing JVM Flecs wrapper or pre-generated Flecs binding.
- [x] Keep dependencies inside `examples/simple-game/vendor`; do not change the
  repository's git index while developing this example.

## Reusable C binding generation

- [x] Add a library API outside `aguafria.zig` for translating one C header with
  Zig and converting the result into a well-formatted Aguafria namespace.
- [x] Accept include directories, preprocessor definitions, target/cpu options,
  extra compiler arguments, namespace, output path, and overwrite policy.
- [x] Return inspectable timings, commands, Zig version, declaration counts,
  fallback counts, source paths, and whether outputs were cached/written.
- [x] Surface `translate-c` and conversion diagnostics with source locations and
  actionable Rust-style error context.
- [x] Cache by header content, transitive compiler inputs/options, Zig version,
  and target so unchanged REPL sessions do not regenerate bindings.
- [x] Generate require-able Flecs and platform binding namespaces.
- [x] Test the reusable API with a deterministic fixture and the real Flecs,
  GLFW, Vulkan, and browser headers; generated bindings must contain no
  `az/defraw` fallback.

## Aguafria game model

- [x] Define normal/extern component schemas for position, circle appearance,
  pointer interaction, and counter state.
- [x] Create the Flecs world, register component metadata, create the circle
  entity, and install an update system through generated bindings.
- [x] Keep hit testing, hover transitions, animation phase, click-edge handling,
  counter incrementing, and `counter % 5` shader selection in Aguafria Vars.
- [x] Expose deterministic scalar functions to the JVM so behavior can be tested
  without opening a window.
- [x] Make live world/counter/shader state inspectable from Clojure.
- [x] Keep frame-time-independent animation and handle window resizing and
  high-DPI pointer coordinates.
- [x] Create a bounded Box3D world and sphere-particle pool whose bodies retain
  real X/Y/Z positions and velocities while the current renderer projects them
  to the shared 2D scene.
- [x] Emit particles from the circle on the same authoritative Flecs click
  transition used by GLFW and nREPL.
- [x] Initialize miniaudio once and play a generated, dependency-free click
  waveform from that same transition.
- [x] Make Box3D bodies, particle slots, and audio engine/device state
  inspectable from Clojure without putting either dependency in the final JVM.

## Graphics and input

- [x] Define one shared render packet passed from the Aguafria frame to either
  backend without application-owned C glue.
- [x] Implement a desktop Vulkan backend with validation-friendly errors,
  swapchain recreation, a procedural circle, and five shader effects.
- [x] Implement an Emscripten WebGL 2 backend with matching visual semantics and
  browser-safe input/main-loop handling.
- [x] Render a readable counter to the right of the circle without platform font
  dependencies (small shader/SDF or embedded bitmap glyphs).
- [x] Load real TTF files at runtime through generated stdio bindings, bake
  Latin-1 glyphs with upstream stb_truetype, and render three accented French
  phrases in visibly distinct fonts from the shared scene code.
- [x] Cache font geometry once so per-frame rendering does not parse or bake
  fonts, while keeping loaded-font and glyph-span state inspectable from nREPL.
- [x] Generate a real transparent six-frame sprite animation and keep its
  source spritesheet as an editable project resource.
- [x] Add a reusable asset packer which accepts either a grid spritesheet or a
  list of independent sprite files and produces the same compact native pack.
- [x] Add hot-reloadable animation clips/players with looping, clamping,
  pause/restart, FPS control, frame views, and inspectable snapshots.
- [x] Render the same animated asset from shared scene code through desktop
  Vulkan and browser WebGL without JavaScript animation or rendering logic.
- [x] Ensure clean resource destruction for desktop and browser shutdown paths.
  Desktop waits for Vulkan idle and destroys synchronization, command, image,
  swapchain, device, surface, instance, Flecs, Box3D, miniaudio, window, and
  GLFW state in ownership order. Browser `pagehide` calls exported `web-stop`,
  which cancels Emscripten's loop and releases the same shared game/GLFW state.

## Development and standalone workflows

- [x] Provide `deps.edn` aliases for dependency sync, binding generation, native
  preparation, nREPL, tests, desktop run/build, and web build/serve.
- [x] In development, load Flecs/platform native libraries once and let compatible
  Aguafria function edits publish through stable dispatch without rebuilding C.
- [x] Demonstrate three live edits while the same game window and Flecs world stay
  alive: shader math, click/counter behavior, and a comptime-produced render type.
- [x] Show active compilation/version statistics from the REPL.
- [x] Build a ReleaseFast desktop executable with no JVM/runtime dependency.
- [x] Build browser `.wasm` plus HTML/JavaScript assets and run it locally.
- [x] Route nREPL click requests atomically to the engine frame thread so the
  exact shared transition remains synchronous without racing Flecs/Box3D/audio.
- [x] Document in `simple-game.core` which ABI/layout edits require dependent
  reevaluation or explicit state migration, while compatible body edits update
  existing live entities.

## Verification and performance

- [x] Unit-test hit testing, procedural animation, sprite animation, shader
  cycling, and render packet generation through real native Aguafria calls.
- [x] Integration-test that generated C bindings compile and call real Flecs.
- [x] Verify a hot edit changes a running Flecs-backed world without restarting.
- [x] Smoke-test native Vulkan startup/render/shutdown where Vulkan is available.
- [x] Compile the browser target and run a browser interaction check, including
  five native GLFW clicks, the five-effect wrap, native FPS drawing, and a clean
  browser console.
- [x] Record a 120-frame work-only average separately from wall cadence, using
  Flecs processing time plus input/physics/render-command work while excluding
  target-FPS sleep, `vkQueuePresent`, and `glfwSwapBuffers` waits.
- [x] Run the complete repository suite after the game changes: 108 tests and
  3,099 assertions with zero failures.
- [x] Compare Debug hot-reload and ReleaseFast standalone results for behavioral
  equivalence. The headless `clojure -M:behavior` artifact links the same game
  module and native dependencies, checks the Debug-tested hit/animation/shader
  contract in ReleaseFast, and exits 0.
- [x] Record cold/warm binding, C dependency, Aguafria, and final-link timings;
  preserve Zig/C caches so repeated edits remain fast. A rendered binding cache
  now avoids reparsing and rewriting the complete project catalog on hits.

## Completion criteria

The slice is complete when the desktop and browser programs exhibit the same
hover/click/shader-cycle behavior, the game logic passes through real Flecs,
the generated binding namespaces can be required and inspected, three classes
of code edit are demonstrated in a live nREPL session, and both standalone
outputs run without Clojure or the JVM.

## Latest verified evidence

- Fresh three-mode revalidation: the example suite passed 6 tests/43
  assertions; the ReleaseFast behavior probe exited 0 after a 245 ms link;
  and the standalone executable rebuilt in 474 ms and remained stable through
  a native-window smoke run.
- Fresh same-JVM publication revalidation: an invalid edit produced a precise
  Zig unused-parameter diagnostic without killing the game; the corrected
  single-form edit forced shader 4 on counter 20 in the existing Flecs world,
  and restoring the source produced shader 1 on counter 21 (`21 mod 5`) at
  desktop frame 24,207, without a namespace reload or JVM restart.
- Fresh browser revalidation: the ReleaseFast WebAssembly object rebuilt in
  241 ms; the page rendered three accented French phrases, animation, native
  FPS/work timing, and a real click changed counter 0 to 1, changed the shader,
  and emitted particles with an empty browser console.
- Repository Kaocha suite: 108 tests, 3,099 assertions, zero failures.
- Simple-game Kaocha suite: 6 tests, 43 assertions, zero failures, including
  deterministic loop/clamp selection and real spritesheet/sprite-list loading.
- Generated animation assets: six 48x48 frames; spritesheet pack is 13,600
  bytes/1,940 spans and separate-sprite pack is 14,279 bytes/2,037 spans.
- Compatible live `frame-at-time` edit in one running JVM: about 1.6 seconds to
  publish a forced frame and about 1.9 seconds to restore the original selector,
  with no namespace reload or JVM restart.
- Compatible live `shader-for-count` edit in one running JVM: 1,372 ms native
  build; restoring the original body took 1,306 ms. Subsequent clicks used each
  implementation without restarting and preserved the live Flecs world.
- Warm ReleaseFast standalone link: 502 ms with vendored C/C++ artifacts cached;
  final executable is approximately 2.2 MiB on macOS arm64.
- Fresh measured ReleaseFast standalone run: 41.42 seconds including JVM graph
  loading and cached dependency preparation; its native final link was 598 ms.
- ReleaseFast headless behavior probe: exit 0; 273 ms native link and 22.0
  seconds for the complete fresh-JVM command.
- Nine unique bindings with an empty Aguafria cache: 59,973 structural
  declarations, zero fallbacks/raw, 72.60 seconds. A no-change generation of
  all desktop/web specs now takes 3.24 seconds end-to-end; individual rendered
  cache hits take 34–225 ms and do not rewrite their outputs.
- Five static C dependencies with an empty Zig cache: 19.47 seconds total
  (Flecs 10.41 s, Box3D 1.80 s, miniaudio 5.23 s, stb_truetype 0.62 s, GLFW
  1.43 s). Cached preparation of all dev/standalone artifacts takes 2.02 s
  including JVM startup.
- Same-JVM compatible gameplay publication remains the development path:
  measured body edits publish in about 1.3–1.9 seconds and preserve live state.
- Animation-integrated ReleaseFast link: 460 ms; browser Zig object build:
  213 ms, with both animation packs cached and included in their outputs.
- Browser interaction: three real Source-family fonts and French accents are
  visible; the generated mascot changes frames; one native click updates the
  counter/shader, emits Box3D particles, and leaves the browser console clean.
- Native 120-frame measurement: 8.078 ms average wall cadence versus 0.694 ms
  work-only average. The remaining time is frame pacing/presentation wait and
  is intentionally excluded from the bottom-left `MOY` value.
