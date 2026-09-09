# La Professeure — infrastructure first

Independent Aguafria Zig project. No dependency on another game example.
Desktop first; the first scene is a narrative-RPG infrastructure prototype,
not a classroom/teaching game. iPad animation authoring and Bitwig exports are
development workflows, not gameplay widgets. Vulkan owns rendering, GLFW only owns window/input, and miniaudio owns
audio playback. Application and renderer code are Aguafria, compiled by embedded
Zig. Shared native dependency recipes come from `examples/shared`, never another
game. The discarded raylib backend is not part of this project.

## Rendering direction — No Graphics API

Follow [Aaltonen's article](https://www.sebastianaaltonen.com/blog/no-graphics-api):
explicit memory ownership, GPU-address-based shader inputs, small root data,
bounded upload storage, few pipelines, and explicit synchronization/lifetimes.
Keep actual Vulkan handles and hazards visible in the backend; do not introduce
a retained-mode engine or a broad RHI layer.

Local capability probe: Apple M3 Max / MoltenVK 1.4.0 reports buffer device
addresses, scalar block layout and timeline semaphores. The upstream
[NoGraphicsAPI implementation](https://github.com/sebbbi/NoGraphicsAPI) requires
additional extensions and excludes ARM. It is a reference, not a dependency.

- [x] Verify the GPU-address vertex-fetch path in the real window.
- [x] Verify packed RGBA font/sprite reads by GPU address and actual lighting.
- [x] Check explicit CPU-write/GPU-read lifetime safety and bounded allocation.
- [x] Add capability checks with actionable startup errors (no silent renderer substitution).
- [ ] Replace prototype linear-heap image sampling with hardware-sampled images
      when expanding beyond this small atlas; preserve data-oriented inputs.
- [ ] Introduce timeline-retired frame slices and asset generations when overlap
      is needed. The initial one-frame fence is conservative, not the final fast path.

## First acceptance gate

- [x] Native window with readable French text and a clear placeholder label.
      Bundled OFL Libre Baskerville, measured advances, high-resolution glyphs;
      original painterly backdrop, restrained narrative layout, optional F1 atelier.
- [x] Frame-rate-independent 2D spritesheet animation, pause/resume and speed.
- [x] Mouse-positioned 2D light affecting the scene but not the interface.
- [x] File-based music playback; reload an exported WAV without restarting.
      Preserve the current valid track when a replacement cannot be loaded.
      Bitwig exports files; no direct Bitwig API integration is claimed.
- [x] iPad sync contract: versioned animation description, sequence/revision,
      transport timestamp and explicit disconnected status. Local fake packets
      test validation. No iPad app, discovery or network connection yet.
- [x] Development export-file watcher for animation PNGs; configurable synced
      path, validation, frame-boundary publication, error retention/recovery.
- [ ] Test export from the user's actual iPad authoring app/device. Select an
      app/transport before claiming direct, real-time device integration.
- [x] One JVM/nREPL desktop workflow, editable native declarations, and a
      standalone ReleaseFast build from the same scene.
- [x] Dev shader watcher: compile both stages away from the render thread,
      publish a complete pipeline at a frame boundary, preserve the last working
      pipeline after syntax/Vulkan errors, then verify recovery after correction.
      `:reloadable?` and `-Dla-professeure.shader-reload=false` control the JVM
      watcher, not optimization level. Standalone has no JVM watcher/compiler.
- [x] Tests for timeline/sync, native compilation, live edit, asset replacement,
      standalone startup and an actual screenshot of the scene.

Verification evidence and remaining limits live in QA.md, not the README.

## Organization

Keep a few substantial namespaces: `build` (native dependency/assets/package),
`scene` (native scene, timeline, lighting, audio ownership), `sync` (the future
iPad contract and local validation), `core` (nREPL host and workflow commands),
and `gpu` (explicit Vulkan resource ownership). Generated native bindings are
ordinary Aguafria Vars. Do not hand-write C bridges.

## Deferred product choices

Choose the iPad animation app and export protocol together before implementing
network sync. Support spritesheets first; layered rigs/IK are later decisions.
Add crossfades, multiple music stems and sample-accurate transport after the
single-track ownership/reload path is verified. Desktop macOS is the first
tested target; other platforms are not implicitly certified.
