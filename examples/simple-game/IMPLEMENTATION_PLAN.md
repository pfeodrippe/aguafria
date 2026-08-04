# Simple Game implementation plan

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
- [ ] Create a bounded Box3D world and sphere-particle pool whose bodies retain
  real X/Y/Z positions and velocities while the current renderer projects them
  to the shared 2D scene.
- [ ] Emit particles from the circle on the same authoritative Flecs click
  transition used by GLFW and nREPL.
- [ ] Initialize miniaudio once and play a generated, dependency-free click
  waveform from that same transition.
- [ ] Make Box3D bodies, particle slots, and audio engine/device state
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
- [ ] Ensure clean resource destruction for desktop and browser shutdown paths.

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
- [ ] Document which ABI/layout edits require dependent reevaluation or explicit
  state migration, while compatible body edits update existing live entities.

## Verification and performance

- [x] Unit-test hit testing, animation, shader cycling, and render
  packet generation through real native Aguafria calls.
- [x] Integration-test that generated C bindings compile and call real Flecs.
- [x] Verify a hot edit changes a running Flecs-backed world without restarting.
- [x] Smoke-test native Vulkan startup/render/shutdown where Vulkan is available.
- [x] Compile the browser target and run a browser interaction check, including
  five native GLFW clicks, the five-effect wrap, native FPS drawing, and a clean
  browser console.
- [ ] Compare Debug hot-reload and ReleaseFast standalone results for behavioral
  equivalence.
- [ ] Record cold/warm binding, C dependency, Aguafria, and final-link timings;
  preserve Zig/C caches so repeated edits remain fast.

## Completion criteria

The slice is complete when the desktop and browser programs exhibit the same
hover/click/shader-cycle behavior, the game logic passes through real Flecs,
the generated binding namespaces can be required and inspected, three classes
of code edit are demonstrated in a live nREPL session, and both standalone
outputs run without Clojure or the JVM.
