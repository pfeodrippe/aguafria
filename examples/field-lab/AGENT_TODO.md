# Pitoco — agent task ledger

Keep this file current as implementation and verification progress. User requests
span the conversation; new steering adds to the goal rather than replacing it.
Do not mark a feature complete based on a mockup, an untested build, or a shader
that visually imitates physics without the underlying simulation.

## Standing requirements

- Use AguaFria Zig, authored with readable Clojure-like forms.
- Flecs is the application backbone for entities, dependencies, inputs, outputs,
  ownership, and revisions. Numerical loops may run outside ECS callbacks.
- Prioritize Houdini-style offline engineering workloads: bake a numerical cache,
  then display/play/scrub the actual cached state. Solver time is independent of
  display time; game integration is a future consumer of the numerical kernels.
- Latest correction: the user saw apparent floating during playback, not only a
  paused reset. Diagnose from measured positions and rendering; do not assume
  pausing is the cause or change physical parameters just to conceal the issue.
- Separate real-world predictive validation from visual plausibility. Record
  model assumptions, units, numerical tolerances, and validation evidence.
- Read primary papers and maintain `RESEARCH_AND_DESIGN.md` with citations.
- Leave blank lines between declarations; do not code-golf.
- Keep reusable numerical/rendering/storage mechanisms below Clojure scene/job
  scripts and data. Ball-specific presets must not become engine assumptions.
- Prefer cohesive files to one file per helper. Keep separate namespaces where
  numerical ownership, native ABI, runtime independence or substantial scope
  justifies them; consolidate trivial wrappers into their owning modules.
- Use ordinary variadic `az/set-many!` for ordered assignment groups:

```clojure
(az/set-many!
  world (ecs/ecs_init)
  source (entity! "sphere_source")
  solver (entity! "mechanical_solver"))
```

## Development workflow

- Use AguaFria's built-in native hot reload while developing; do not confuse the
  standalone release build with the reloadable REPL runtime.
- Keep only the hot-reload development application running until the work is
  finished. The duplicate standalone instance was closed at the user's request.
- [x] Add `clojure -M:desktop`: native first-thread loop and nREPL in one JVM,
  with development dispatch enabled and the panel's native links configured.
- [x] Verify a compatible live declaration edit through an already-compiled
  native caller: camera distance 9.0 → 13.0 → 9.0 in one process. The Flecs
  world, 1,441-state cache, and sampled cached particle state remained unchanged.
- Re-bake after solver edits. Previously computed caches remain historical results.
- External C++ library adapters and GLSL artifacts have their own compilation
  lifecycle. Pitoco UI, exports, plugin host and geometry policy are AguaFria Zig.

## Current milestone — single and three-ball experiments

Completion order: finish and validate both ball examples and their realistic
rendering, then clothing, then paper sheets/folds/crumpling in wind, then rain,
physical audio, and pedal/circuit work.
The full roadmap is now authorized implementation work, not just suggestions.

- [x] Add the new example and cited research/design document.
- [x] Implement the f64 rigid sphere solver with analytical floor impact timing,
  friction, spin, unit quaternion evolution, and energy/impulse observables.
- [x] Make Flecs authoritative for source configuration and body states.
- [x] Build the native Vulkan/GLFW/Dear ImGui application and macOS bundle.
- [x] Add source/solver/output node selection and parameter editing.
- [x] Add pause, single-step, reset, bounded cache, and timeline scrubbing.
- [x] Add the three-ball experiment with frictional pair collisions and spin.
- [x] Render all three rigid bodies, orientations, shadows, and reflections.
- [x] Keep experiment definitions as ordinary Clojure-shaped AguaFria functions.
- [x] Validate the rigid and pair solvers and Flecs replay/capacity behavior:
  11 tests / 44 assertions passed before the soft-body extension.
- [x] Implement and compile-test variadic `az/set-many!`: ordered reads/writes
  and dependent array indices; 4 tests / 8 assertions passed.
- [x] Implement CSV body/tick export with applied configuration JSON.
- [x] Verify interactive export and inspect metadata, trajectory and all particle rows.
- [x] Add actual deformable balls using a tetrahedral XPBD
  interior, elastic constraints, volume preservation, and physical contacts.
- [x] Render the simulated deforming triangle surface; do not fake deformation
  by stretching an otherwise rigid analytic sphere.
- [x] Expose rigid versus deformable modes and material stiffness in the UI.
- [x] Validate deformation, volume behavior, energy accounting, three-body contact,
  and recovery; document the coarse XPBD model and distinguish it from FEM.
- [x] Implement bake-first UI, independent bake batch sizes, immutable playback,
  stop/rebake controls, and measured minimum particle clearance.
- [x] Run compiled physics, deformable contact, Flecs and cache tests:
  16 tests / 65 assertions passed.
- [x] Add and run headless EDN-configured jobs: a three-body, 1-second job
  exported and re-read 241 frames with 129 particle states per frame.
- [x] Replace soft-body sphere shadow proxies with actual deformed-triangle
  projections onto the ground from the point key light.
- [x] Frame all active body bounds; prevent escaped bodies from passing behind
  the fixed camera and producing misleading clipped geometry.
- [ ] Increase mesh resolution and establish spatial/time convergence; retain
  genuine tetrahedral mechanics rather than using a display-only squash.
- [ ] Finish validating the finite-deformation continuum FEM ball mechanics.
  Check material response, impact/contact,
  energy, penetration, mesh and timestep convergence for one and three balls.
- [x] Implement the Stable Neo-Hookean material, PK1 and exact tangent; verify
  energy derivatives, objectivity, linear limit, internal force/torque balance,
  ballistic motion and second-order temporal convergence.
- [x] Add arbitrary tetrahedral dynamic jobs, volume refinement, adaptive Verlet,
  nodal plane contact, physical units and streamed particle caches.
- [x] Connect continuum FEM forces to the one/three-ball Flecs/cache pipeline,
  preserving rigid and XPBD modes. Store model/E/ν in `BallMaterial`.
- [x] Hot-reload the UI solver selector and energy/export path in the existing
  development host. Add an atomic REPL-to-UI mailbox for bake/seek/export/stop.
- [x] Verify nonlinear and ball tests: 9 tests / 56 assertions pass; the complete
  extended suite, including FEM Flecs material/cache replay, passes 31 tests /
  152 assertions.
- [x] Compare independent three-ball headless and UI FEM caches: 62,049 particle
  rows agree within 5e-12; all finite, ground clearance nonnegative.
- [ ] Complete spatial and impact/contact convergence, calibrated material presets,
  higher-resolution cache/rendering, edge-edge CCD, pair friction and self-contact.
- [x] Add a reproducible plane-impact study with independent timestep and mesh
  refinement, sampled compression/energy, separation detection, and an integrated
  contact-impulse versus momentum check. Save complete job descriptions and loaded
  solver fingerprints; reject mixed-version studies.
- [x] Run the complete suite with the impact diagnostics: 34 tests / 170 assertions
  pass. The initial nine-case 25 ms study identifies mesh/contact sensitivity much
  larger than timestep sensitivity; complete separation requires a longer run.
- [x] Complete six 60 ms rebound cases: three meshes at 5 and 2.5 µs caps.
  Every case separates without ground penetration or rejected substeps. Finest
  rebound speed changes by 5.87e-9 m/s on timestep halving, versus 0.01435 m/s
  on the last mesh refinement. Recorded energy loss falls from 4.65% to 0.39%.
  These measurements do not finish contact convergence or specimen calibration.
- [x] Add owned mesh-sized single-body buffers so refined continuum states can
  be baked, inspected, scrubbed and rendered directly in the workbench. The Flecs
  output entity owns published caches; UI-thread adoption rejects stale revisions.
- [x] Bake and display 1,209 nodes / 5,120 tetrahedra / 1,280 boundary triangles
  across 241 frames. Export all 291,369 particle rows, rest vertices and topology.
- [x] Verify cache orientation, frame independence, Flecs ownership/reset and
  rendered vertex normals/counts. Complete suite: 36 tests / 188 assertions pass.
  A live stale-result test leaves the displayed cache unchanged.
- [x] Verify live replay after acknowledged seeks to ticks 0, 180 and 100. Confirm
  new export writes by modification time, then compare all four file hashes:
  particle, trajectory, reference and connectivity exports are byte-identical.
- [x] Extend the refined cache/job path to three interacting bodies, with one
  synchronized owned cache group published through Flecs.
- [ ] Add UI-authored refined bake requests. Refined source controls currently
  use Clojure job authoring.
- [x] Implement a mesh-sized native nearest-feature contact query with a refittable
  triangle hierarchy and angle-weighted pseudonormal signs. Validate closed,
  consistently outward-oriented vertex-manifold input, permitting unused volume
  nodes. Test concave boundaries, triangle features and incremental refits:
  4 native-backed tests / 108 assertions pass in the existing hot-reload host.
- [x] Run the complete suite after the contact-query addition: 40 tests /
  296 assertions, zero failures or errors (`contact-full-suite-log.txt`).
- [x] Connect the query to joint refined FEM stepping, inelastic mass-weighted
  reactions, Coulomb friction, shared timesteps and all-body rollback. Four
  coupled tests / 38 assertions pass, covering free flight, impulses, invalid
  initial Jacobians and a refined three-body collision through separation.
- [x] Complete a 150 ms isolated three-body impact at 205 nodes / 640 tets each.
  Outer rebound velocities are -0.967915 and +0.967899 m/s; maximum total momentum
  error is 7.23e-16 kg m/s and energy loss is 1.16%. Save the full source,
  solver fingerprints and sampled history in `exports/coupled-three-refined.edn`.
- [x] Bake and publish a one-second three-body floor experiment: 615 nodes,
  1,920 tets, 241 synchronized frames, 10,080 substeps and no rejected steps.
  Minimum accepted J is 0.6423. Inspect the fresh native viewport at tick 100:
  `exports/coupled-three-live.png` shows 28.3% compression and zero floor gap.
- [x] Verify 148,215 live particle rows, 723 trajectory rows, 615 reference nodes
  and 1,920 cells with correct body IDs. Positions and velocities are finite;
  minimum floor height is zero. Vertical momentum balances gravity plus the
  integrated ground impulse within 9.16e-11 N s. Preserve the verified files in
  `exports/coupled-live/` so headless test exports cannot replace the evidence.
- [x] Acknowledge paused live seeks to ticks 0, 180 and 100, then verify a fresh
  export with byte-identical hashes for all four CSVs. Save the replay manifest
  in `exports/coupled-live/replay-verification.json`.
- [ ] Complete coupled impact time/mesh convergence, edge-edge contact, CCD,
  angular-momentum accounting and self-contact. Discrete vertex/surface residuals
  do not prove intersection-free trajectories.
- [x] Refine the actual spherical FEM boundary, validate positive element
  orientation and mean-ratio quality, and preserve authored mass using the
  actual reference volume. Keep fixed-polyhedron studies explicitly separate.
- [x] Move reusable sphere geometry into the independent pure Clojure scripting
  library; verify volume, continuum/lumped inertia, manifold topology, scaling
  and invalid inputs without loading AguaFria (5 tests / 49 assertions).
- [x] Verify native spherical reference volumes, mass, rest energy and free flight.
  The complete compiled suite now passes 47 tests / 391 assertions.
- [x] Bake and display spherical three-ball FEM: 3,627 nodes / 15,360 tetrahedra,
  241 frames, 10,080 substeps, no rejections, minimum accepted J 0.669784.
  Verify all 874,107 particle records against mass-weighted trajectories and
  gravity/ground impulse; preserve exports in `exports/spherical-live/`.
- [x] Verify cache playback and fresh re-export after seeks 0/180/100: all five
  file hashes match. Inspect screenshots at ticks 0, 100 and 240; tick 100 shows
  27.3% compression with zero floor gap. OS capture again required foregrounding
  the existing window to replace a stale frame. Direct Vulkan readback below
  now supersedes OS capture for frame evidence.
- [x] Run six spherical plane impacts (refinements 0/1/2, step caps 5/2.5 µs).
  Finest time-step velocity difference is 2.90e-7 m/s, but the last spatial
  difference is 0.01797 m/s. This does not complete contact convergence.
- [ ] Complete realistic ball rendering from the simulated surfaces: sufficient
  surface resolution, physically based materials, lighting and contact shadows;
  verify complete bakes visually before moving on to clothing.
- [x] Capture and inspect a fresh refined FEM viewport in development PID 37705:
  `exports/refined-peak.png`, tick 100, 31.1% compression and zero ground gap.
  Its mesh counts and measurements match the exported cache. The earlier stale
  XPBD screenshot is superseded by this verified FEM capture.
- [x] Add renderer-owned Vulkan frame export/readback with cache revision/tick
  tagged at geometry construction. Copy after the color pass, wait for the GPU,
  save native RGB PPM, and optionally encode lossless PNG plus EDN provenance.
  Three native/format tests cover 26 assertions; the full suite passes
  50 tests / 417 assertions in `readback-full-suite-log.txt`.
- [x] Verify ticks 0/100/240 and repeated tick 100 at 2,560 × 1,640 pixels in
  PID 37705. Full-frame RGB hashes match on replay and with the app hidden.
  Keep the native window/device and Flecs cache while upgrading the swapchain.
  Final UI evidence and pixel hashes: `exports/vulkan-readback-final/`.
- [x] Adopt completed caches before geometry construction so mesh and inspector
  observe the same revision. Add a capture button with completion/error status.
- [x] Consolidate single-body cache baking into nonlinear-job, coupled cache
  baking into coupled-job, and VTK export into fem-job. Remove the three helper
  files; put capture verification with the existing live inspection functions.
- [ ] Extend capture into asynchronous frame sequences and broader swapchain
  resize/format recovery. Current single captures block for GPU/file completion;
  unsupported surfaces and resource/file failures are explicit states.
- [x] Visually verify framed three-body playback, mesh contact shadows, export,
  stopping a bake with 65 retained states, and a fresh complete rebake.
- [x] Compare headless and UI caches: 31,089 matching particle records within
  4.88e-11; the complete UI run has 185,889 finite particle records and no
  ground penetration.
- [x] Update README and research notes with implemented behavior and limitations.

## Clothing — immediately after the completed ball examples

Status: planned; no garment solver, rendered garment, or clothing selector exists yet.

- [ ] Research cloth/shell mechanics, measured fabric constitutive behavior,
  bending, anisotropy, friction, and robust contact using primary references.
- [ ] Implement Clojure-authored cloth/garment sources and offline simulation
  jobs, including gravity, stretch/shear, bending, damping and constraints.
- [ ] Implement cloth/body contact and self-collision with thickness and friction;
  verify penetration, stability, timestep and mesh convergence.
- [ ] Add garment seams, attachment constraints and reproducible drape examples.
- [ ] Validate drape, extension and bending against analytic or measured references;
  distinguish calibrated fabrics from illustrative presets.
- [ ] Render the simulated cloth surface with realistic fabric shading, thickness,
  fine-scale normals, lighting and shadows. Finish ball rendering first.
- [ ] Expose material/contact inspection, cache playback and export in the UI.

## Paper — immediately after clothing

Status: planned; no paper solver, rendered paper, or paper selector exists yet.

- [ ] Read primary research on paper/sheet mechanics, orthotropy, bending,
  buckling, permanent creasing, crumpling and sheet–air interaction. Choose a
  shell/FEM or other documented physical formulation suitable for these effects.
- [ ] Implement flat/unfolded sheets, folded sheets, and crumpled paper sources
  in the Clojure-like authoring interface, including multiple interacting sheets.
- [ ] Model measured thickness, mass per area, directional stretch/bend response,
  and permanent folds/creases. Distinguish calibrated paper stocks from examples.
- [ ] Simulate throwing, gravity, wind-driven flutter/tumbling and landing from
  the actual evolving geometry. Couple air loads to orientation/deformation;
  validate reduced aerodynamic models and add resolved flow where necessary.
- [ ] Implement paper self-contact, inter-sheet contact, floor/obstacle contact
  and friction, with robust handling of tightly folded/crumpled configurations.
- [ ] Validate bend/crease response, falling and wind-driven trajectories against
  analytic or measured references; check mesh/time convergence and energy balance.
- [ ] Add reproducible offline bakes, wind/material inspection, playback and export.
  Render actual sheet thickness, two-sided surfaces, folds and contact shadows.
  Complete clothing first; do not substitute visual flutter for physical mechanics.

## Physical rain simulation — after paper

- [ ] Read primary work on raindrop size distributions, terminal velocity,
  aerodynamic drag, wind coupling, deformation/breakup, and impact dynamics.
- [ ] Define the scale of the first research scene and fidelity/performance
  budgets. Distinguish resolved water surfaces from reduced drop models.
- [ ] Implement reproducible rainfall intensity and physically meaningful drop
  sizes/masses, gravity, air drag, wind, and collision event data.
- [ ] Add impact/splash and wet-surface behavior; account for mass transferred
  into surface films, puddles, splash droplets, or drains.
- [ ] Add real-time views and inspector fields with SI units, seeded replay,
  particle/film budgets, conservation checks, and exported measurements.
- [ ] Validate terminal velocities, rainfall flux, impact velocities, and water
  mass balance against published or measured reference data.

## Physically based rain audio — requested next

- [ ] Research drop-impact excitation, surface modal vibration, water cavity/
  bubble resonance, radiation, and propagation models using primary sources.
- [ ] Derive sound excitation from actual simulated impacts: drop mass and
  velocity, contact surface, location, wetness, and material properties.
- [ ] Implement surface resonators and/or suitable reduced acoustic models.
  Add water/film sounds where a documented model supports them.
- [ ] Couple simulation events to a sample-clock audio engine with deterministic
  timestamps and bounded queues; decouple graphics rate from audio processing.
- [ ] Route stereo/multichannel audio through a real audio interface. Provide
  device/channel selection, meters, recording, and reproducible offline renders.
- [ ] Validate spectra, decay times, level trends, timing, and spatial behavior
  against recordings with known materials and rainfall conditions.
- [ ] Provide reusable physical sound nodes for other experiments; do not label
  generic noise or an unrelated rain loop as physically simulated rain audio.

## Physical pedal/circuit/audio-interface simulator — requested next

- [ ] Research circuit simulation and circuit-derived audio models: modified
  nodal analysis, DAE integration, Newton iteration, and wave digital methods
  where appropriate. Compare primary references and existing open models.
- [ ] Define circuits in Clojure-like forms: components, nets, ports, parameters,
  initial conditions, and explicit physical units; represent the graph in Flecs.
- [ ] Implement and validate resistor/capacitor/inductor networks, sources,
  grounding, and voltage/current probes; start with analytic RC/RLC references.
- [ ] Add nonlinear diode, transistor (BJT/MOSFET), and op-amp models with
  documented device parameters, operating points, and convergence diagnostics.
- [ ] Add magnetics/electromagnetics where the circuit requires them: coupled
  inductors, transformer saturation/hysteresis and parasitics. Use field solvers
  for geometries where a lumped approximation is insufficient; do not imply
  full electromagnetic simulation when using a circuit equivalent.
- [ ] Build a pedal first from a concrete circuit schematic, not a generic DSP
  waveshaper. Compare operating points, transient response, transfer curves,
  frequency response, harmonics, and parameter sweeps against SPICE/measurements.
- [ ] Implement real audio-interface input/output wiring (instrument input,
  gain/impedance assumptions, channels, monitoring, bypass, and patch connections).
- [ ] Keep the callback real-time safe: bounded work, no allocation/compilation
  or blocking in the audio callback, denormal handling, and atomic graph updates.
- [ ] Measure round-trip latency, xruns, sample-rate behavior, oversampling/
  anti-aliasing, and CPU use. Provide offline reference renders at higher accuracy.
- [ ] Expose schematic/patch views, circuit probes, spectra and waveforms in the
  live UI, with reproducible circuit presets and exported recordings/data.

## Broader engineering workbench

- [ ] Editable, validated DAG; cycle detection, typed ports, units and revision
  invalidation; persistent projects and deterministic parameter sweeps.
- [ ] Scalar Poisson FEM with manufactured solutions and mesh refinement.
- [x] Tetrahedral linear elasticity with patch and cantilever tests: native
  matrix-free stiffness, Lamé material law, prescribed displacements, integrated
  body/traction loads, Jacobi PCG, explicit convergence, stress and reactions.
- [x] Verify continuum spatial convergence with a quadratic manufactured solution:
  gradient errors halve at subdivisions 2/4/8. Refine a clamped beam through
  48/384/3,072/24,576 tetrahedra; finest tip displacement is 0.185493 mm against
  a 0.2 mm slender-beam reference. This does not validate nonlinear ball impact.
- [x] Add Clojure/EDN FEM jobs with owned native buffers, validation, loaded
  solver fingerprints, changed-solver rejection, EDN and VTK stress exports.
- [x] Run the complete compiled suite: 21 tests / 90 assertions pass. Verify
  exported EDN roundtrip and VTU connectivity/field counts (153 nodes, 384 tets).
- [ ] Integrate these FEM jobs and their diagnostics into the Flecs/UI workflow.
- [ ] Electrostatics and thermal conduction with flux/energy validation.
- [ ] Nonlinear constitutive materials and calibrated deformable contact.
- [ ] Worker scheduling, cancellation, stale-result rejection, and a documented
  handoff between Flecs and packed numerical buffers.

## Pitoco extension and scripting boundary

- [x] Name the application Pitoco; preserve existing implementation namespaces
  while the current development session owns live caches.
- [x] Keep standalone compiled native and independent of JVM/nREPL/AguaFria
  hot reload. External Clojure programs own their scripting REPLs.
- [x] Add a versioned C ABI with explicit sizes, copied command payloads, bounded
  mailbox, command tickets, and owning-thread lifecycle callbacks.
- [x] Load/unload native plugins and route commands through host function pointers.
  Validate ABI, required callbacks, unique IDs, and partial-load cleanup.
- [x] Compile a Clojure + AguaFria Zig example into a native shared library;
  verify load, command, unload, and repeated attachment in a process with no JVM.
- [x] Provide a pure Clojure client in its own deps.edn project. Its optional nREPL
  never loads AguaFria or embeds a JVM in Pitoco. The file transport and FFI submit
  use the same native queue; test against a separate compiled host process.
- [x] Verify external control of the existing window: load the compiled plugin,
  rewind the 241-frame refined three-ball cache, unload it, and restore tick 100.
  Keep a plugin attached across an application development recompile and verify
  its callback again. Build the standalone executable without launching a second
  GUI; inspect its native dependencies for absence of JVM/development runtime.
- [ ] Expose typed simulation job/mesh/material payloads through this boundary,
  including refined coupled FEM jobs currently authored in the development JVM.
- [ ] Register plugin-defined solver, geometry, renderer and audio nodes with
  Flecs IDs, typed ports, units, ownership, capabilities, and cancellation rules.
- [ ] Add staged plugin replacement with state migration, drain/join of active
  jobs and callbacks, and failure recovery. Current replacement is explicit
  unload then load of a compiled library; it is not automatic code hot reload.
- [ ] Extend command completion into durable job/export results and diagnostics.
  Current tickets acknowledge native dispatch, not completion of an export.

Extension integration verification: the complete compiled simulation suite still
passes 45 tests / 364 assertions after adding the host. Extension checks pass 23
Clojure assertions plus native ABI/lifecycle assertions. External seek/export
replay retained all four CSV hashes; 17-digit exports contain 148,215 particle
rows, 615 reference nodes and 1,920 tetrahedra. Vertical momentum balance residual
is -5.27223e-11 N s. Evidence is in `exports/coupled-live-17/` and
`exports/pitoco-plugins-live.png`.

Post-consolidation verification: a fresh process passes 17 tests / 234 assertions
covering static FEM export, single/coupled cache ownership and export, boundary
geometry, spherical mass/free flight and native frame encoding. The complete
pre-consolidation suite passed 50 tests / 417 assertions. No native solver/storage
ABI was moved, and the live revision-32 cache remains available. See
`consolidation-suite-log.txt` and `readback-full-suite-log.txt`.

Final build/evidence check for this pass: `readback-final-build-log.txt` confirms
both the arm64 executable and Pitoco.app build. Native dependency inspection shows
no JVM or development-host library. No standalone GUI was launched. After the
headless tests, an external controller restored the live export; all five hashes
match `exports/spherical-live/sha256.json`. The existing host is paused at tick
100, revision 32, with all 241 frames retained.

## Generic scripted solids — current integration

- [x] Put tetrahedral box generation in independent `pitoco.geometry`; add one
  cohesive scene script, `scenes/solid-impact.clj`, returning ordinary Clojure data.
- [x] Generalize coupled cache baking/publication to one through three bodies
  with different topology, material, density, gravity and initial conditions.
- [x] Store scripted provenance/gravity/floor settings with the cache owner in
  Flecs; preserve the existing native cache/group layouts across hot reload.
- [x] Render authored solid boundaries, remove ball seams for these meshes, and
  show generic source labels instead of ball-only parameter sliders.
- [x] Export per-body physical metadata and a SHA-256-addressed normalized scene
  including solver fingerprints. Keep external standalone job submission open.
- [x] Bake the separate box/tetrahedron floor drops: 62 nodes, 112 tetrahedra,
  145 frames, 12,096 substeps, zero rejected substeps, minimum J 0.4334854.
  Adopt in existing GUI revision 33; repeated tick-80 Vulkan pixels match.
- [ ] Resolve the generic inward box/tetrahedron contact stall at
  t=0.2503255887 s (751 rejected substeps in the failed output interval).
  Reproducer and failure report: `exports/scripted-contact-failure/`.
  Do not conceal this limitation with the successful separate-drop demonstration.
- [ ] Expose scene loading, typed job submission and completion through the
  standalone extension interface; current scene publication uses the dev JVM.
- [ ] Replace remaining ball-named compatibility entities/configuration with a
  versioned generic scene registry when live storage can be migrated safely.

Verification for generic scene integration: **52 tests / 504 assertions pass**
(`generic-full-suite-log.txt`). New assertions cover differing mesh sizes and
materials, analytic free flight with different vector gravities, Flecs group
ownership/reset, metadata export and invalid scene rejection. Pure authoring also
runs in the independent scripting project without AguaFria.

The existing window is paused at tick 80 / revision 33 with 145 frames. Its
externally requested export passes independent CSV/source-hash verification:
8,990 particle rows, masses 11.52 and 6.25 kg, min Y=0 m, center discrepancy
2.22e-16 m, velocity discrepancy 8.88e-16 m/s and vertical momentum residual
-7.30e-12 N s. Repeated Vulkan capture pixels match. Evidence and the hashed
source are preserved in `exports/scripted-solid-live/`; the prior spherical
baseline is preserved separately.

Final standalone check: `generic-standalone-build-log.txt` confirms both the
arm64 executable and Pitoco.app were rebuilt successfully. Native dependency
inspection shows no JVM or development-host library. No standalone GUI was
launched; the existing development window retains the scripted cache.

## Contact conditioning investigation — continuation

- [x] Reproduce the inward box/tetrahedron failure and preserve its accepted
  checkpoint in `exports/scripted-contact-failure/checkpoint.edn`.
- [x] Fix interior-triangle contact normals: use the geometric face normal,
  rather than normalizing a nearly zero reconstructed point difference. Preserve
  radial edge/vertex directions. A rotated-box regression checks gaps down to
  +/-1e-12 m and zero, plus distinct edge/vertex directions.
- [x] Full compiled regression suite: **53 tests / 518 assertions pass**
  (`contact-normal-full-suite-log.txt`).
- [x] Diagnose the remaining replay failure without silently restarting an
  ambiguous job. Native clock probes measured only 4.73e-8 s of advancement over
  1,476 observation calls. Explicitly stop the owned replay, confirm its future
  terminal, and remove temporary diagnostic hooks. The existing cache is retained.
- [x] Bound and measure an isolated contact step: 4,096 position and velocity
  sweeps still leave closing-speed residuals 8.48e-7 and 9.32e-7 m/s. The active
  bottom-face constraints have nearly opposing Jacobian rows; merely raising the
  sweep count does not resolve the conditioning.
- [ ] Replace/extend closest-vertex contact construction with robust edge contact
  and collision prevention; assess a coupled contact solve for nearly dependent
  constraints. Keep the current 1e-9 m / 1e-7 m/s tolerances meaningful.
- [ ] Add normal per-job native cancellation/progress reporting. Current Clojure
  cancellation only checks between output intervals; this investigation needed
  temporary diagnostic hooks to terminate a native step that collapsed.

No inward-impact success is claimed. The face-normal fix addresses a distinct,
verified numerical defect; generic collision completion remains open. Probe
sources live under ignored `build/contact-probe.clj` and
`build/contact-clock-probe.clj`; reports are under
`exports/scripted-contact-failure/` and `contact-*-log.txt`. Temporary clock/cancel
hooks are removed from the running solver and are not in production source.

Final check for this continuation: standalone executable and Pitoco.app build
successfully (`contact-normal-standalone-build-log.txt`), with no JVM or shared
development-host dependency. No standalone window was launched. All six live
export hashes again match the preserved revision-33 cache. The original 23
coupled-FEM declaration fingerprints exactly match their pre-probe values after
restoration (`contact-restored-fingerprints-log.txt`); only the intended contact
normal change remains in source. No contact replay is still running.


## Coupled normal solver and reusable scene boundary — continuation

- [x] Keep scripted scene choices above the shared FEM/contact/cache pipeline;
  document the current native/JVM submission boundary in README. Add no new
  solver helper namespaces.
- [x] Implement a bounded dense normal complementarity solver with active-set
  pivots, physical-unit residual checks and explicit singular/invalid failures.
- [x] Verify nearly opposed, duplicate and releasing/activating contact rows.
  The focused compiled suite passes 12 tests / 199 assertions.
- [x] Independently check the saved four-row contact system using 80-digit
  active-set enumeration: impulse error <= 2.28e-13 N s. Check applied impulses
  for kinetic-energy decrease and linear/angular momentum balance.
- [x] Integrate a normal-block fallback after local velocity sweeps, with bounded
  incremental friction corrections and all-body rollback on block failure.
  The isolated saved friction trial completes with zero closing residual.
- [x] Build standalone executable/app successfully, without a JVM or shared
  development-host dependency. Do not launch a second GUI.
- [x] Complete the full inward-impact replay with production source held fixed:
  145 frames, 17,551 accepted substeps, 21,585 rejected attempts, minimum J
  0.39559976, maximum sampled penetration 9.99999992e-10 m.
- [x] Publish revision 34 to the existing window and inspect Vulkan captures
  at ticks 0/65/144/65. Repeated tick-65 full-frame pixels match. Independently
  verify 8,990 particle records and vertical momentum to 1.84e-11 N s.
  Save complete evidence in `exports/scripted-impact-live/`; keep older evidence.
- [x] Full compiled regression suite: **56 tests / 557 assertions pass**
  (`normal-block-full-suite-log.txt`). Independent geometry tests pass 5 / 49;
  the scene script also evaluates in the standalone Clojure scripting project.
- [ ] Extend contact geometry/CCD and establish mesh/time convergence; the new
  normal solver does not finish these requirements or a global Coulomb solve.

The first instrumented replay reached 0.6 s; publication was correctly rejected
because diagnostics changed solver fingerprints during its bake. Its future is
terminal and native diagnostic hooks were removed. The subsequent production
replay completed with unchanged solver fingerprints and was published. The
Clojure interval observer is also removed; both futures are terminal.
`scenes/solid-impact.clj` now reproduces the inward-moving scene. The earlier
floor-drop evidence remains intact. Full-trajectory angular momentum, contact
convergence and collision prevention are still open.


## Continuous feature detection — continuation

- [x] Read Wang et al. (2021), sections 5–6, and inspect the reference CCD API.
  Integrate pinned Tight-Inclusion vertex/face and edge/edge queries through a
  C ABI and AguaFria Zig, keeping Clojure authoring in contact-mesh.
- [x] Compile double precision with strict floating-point operations, explicit
  conservative rounding filters, bounded iterations and explicit native failure.
  Report conservative time bounds and achieved tolerance, including limited solves.
- [x] Record the linked CCD archive SHA-256 and pinned dependency sources in
  coupled job provenance. Package dependency licenses and source references.
- [x] Focused compiled geometry suite: 8 tests / 169 assertions pass, covering
  between-frame crossings, near misses, coplanar/degenerate edges, initial
  contact, minimum separation and iteration/input limits.
- [x] Independent analytic study: 744 queries, comprising 372 known crossings
  and 372 clear truncated intervals at different dyadic times/scales/translations;
  zero false negatives or positives on this set, maximum time underestimate
  5.97e-8. Preserve source and all query results in `exports/ccd/`.
- [x] Invoke the C ABI from a standalone native command-line executable and
  from the existing development session. Rebuild standalone app/executable;
  do not launch another GUI. The displayed cache remains revision 34.
- [x] Full compiled regression suite: **59 tests / 602 assertions pass**
  (`ccd-full-suite-final-log.txt`), plus final focused validation 8 / 169.
  Fix the native-provenance key to remain compatible with the existing sorted
  symbol-key map; verify coupled bake and export paths after that correction.
- [x] Restore the live historical export through the external bridge. All six
  artifact hashes match the preserved revision-34 impact cache. All CCD study
  futures are terminal; the original window remains paused at tick 126.
- [ ] Integrate swept broad-phase candidates and CCD into substep/contact response,
  including edge constraints, initial-contact treatment and all correction paths.
  The default integrator remains discrete; an opt-in CCD drift path is now
  implemented below, with unresolved contact qualification work.
- [x] Exercise 824 published queries from two upstream sample directories,
  verifying exact rational-to-binary64 conversion. At tolerances 1e-6/1e-8/1e-10,
  each run misses zero collisions and reports 21 conservative false positives;
  precision-limited counts are 6/14/14. Preserve all results and source hashes.
- [ ] Broaden dataset coverage and perform contact mesh/time convergence. The
  analytic and selected upstream checks do not reproduce the full benchmark or
  prove simulator correctness. Decreasing tolerance alone did not remove the
  sample false positives; integration must handle conservative zero-time results.


## Whole-mesh continuous queries — continuation

- [x] Add native motion buffers borrowing immutable topology, endpoint validation,
  and swept triangle bounds with outward-rounded separation expansion. Preserve
  the existing Surface/Body/Assembly and live cache layouts.
- [x] Traverse paired swept BVHs; test both vertex/face directions and all nine
  edge pairs per candidate. Keep conservative earliest bounds, explicit primitive
  failures and a total traversal/query budget. No helper namespace added.
- [x] Expose scoped Clojure mesh data queries accepting open sheets. Keep closed
  signed-distance validation separate. State the initially-disjoint assumption:
  this is not an initial intersection/containment test or contact response.
- [x] Focused compiled geometry tests pass 12 / 239: edge-only between-frame
  crossings, changing shape, hierarchy pruning, minimum separation, outward
  rounding, stale refits, nonfinite inputs and explicit work exhaustion.
- [x] Execute 120 whole-mesh queries in the existing development session: 60 known
  edge-only crossings and 60 clear paths, zero misclassifications on this set,
  maximum time underestimate 2.99e-8. Preserve source/results in `exports/ccd/`.
- [ ] Connect swept queries to drift and every position-correction path, with
  initial-contact handling and edge response. Do not label current scene bakes
  collision-free merely because the standalone query layer passes its tests.
- [ ] Finish contact mesh/time convergence and realistic ball rendering before
  garment mechanics/rendering, followed by paper and the remaining simulations.
- [x] Audit all 144 cached heterogeneous-solid intervals with reusable motion
  buffers. Preserve 99 clear, 44 conservative zero-time and one incomplete
  result; a larger traversal budget completes that interval as a possible edge
  contact. Record achieved tolerances and the initially-disjoint/linear-path
  limitations. All study futures are terminal; no scene state was mutated.
- [x] Rebuild the standalone executable/app, with no JVM or development-host
  linkage. Keep the single original development GUI and its revision-34 cache.
- [x] Full compiled regression suite passes **63 tests / 674 assertions**
  (`swept-mesh-full-suite-log.txt`), including coupled bake/export and readback.
- [x] Restore the live historical export after the tests through the external
  bridge; all six artifact hashes match the preserved revision-34 baseline
  (`swept-mesh-restored-export-hashes-log.txt`). Original window remains paused
  at tick 126 with 145 frames, no bake running and no extra standalone GUI.


## Continuous FEM stepping — active milestone

- [x] Add opt-in native FEM Verlet with staged endpoints, whole-mesh CCD checks,
  vertex/face and edge/edge impulses, coupled proximity response and exact rollback.
  Expose it through owned jobs and authored scenes as `:contact-method :continuous`.
- [x] Keep the default contact method discrete while the new path is qualified.
- [x] Preserve sub-ulp displacement bits during velocity-only impulses.
- [x] Diagnose a time-zero false positive at a 3.448e-9 m positive gap under
  1e-8 m query tolerance. Retry the entire sweep at bounded finer precision;
  retain explicit failure for unresolved possible hits.
- [x] Save the actual failing numerical checkpoint as a focused regression.
  Eight contact tests / 173 assertions pass, including edge response, floor
  momentum balance, rollback, free flight and the resting-gap precision retry.
- [ ] Complete the longer two-/three-body studies, contact/clearance/time/mesh
  convergence and realistic rendering before moving to clothing.
- [ ] Implement clothing, then flat/folded/crumpled/multiple paper in wind,
  then physical rain/audio and circuit-derived pedals/device I/O. None of these
  later simulation types is implemented or visible in the viewport yet.

- [x] Complete the final 12 ms three-body continuous study: 243 accepted steps,
  three rejections, 116,190 CCD queries. Preserve inputs/reports/provenance.
- [x] Verify authored continuous free-fall cache dispatch: two frames, position
  error 4.18e-18 m. Full suite before precision refinement: 70 tests / 843
  assertions; final affected focused suite: 8 tests / 173 assertions.
- [x] Build the standalone executable/app without launching it; hot-reload the
  numerical helpers into the original development GUI, preserving its cache.
- [ ] Resolve prolonged two-body contact beyond the local saved-checkpoint fix.
  The final precision-refined 12 ms run was cancelled after more than five
  minutes in the 3–4 ms interval. Keep that explicit cancellation record; do not
  count it as completed physics. Investigate normal conditioning, positive-gap
  contact response and bounded integration work/progress reporting.
- [x] Restore the historical revision-34 exports after testing through the external
  bridge; all six SHA-256 hashes match. Original GUI is paused at tick 144 with
  145 frames and no bake. All current study/build/test processes are terminal.

## Active continuation — variational contact, 2026-09-12

Continue through the roadmap without treating these intermediate checks as the
end of the task. Clothing/paper/rain/pedals are still outstanding. The original
GUI and its historical cache have not been changed by this continuation.

- [x] Add a bounded continuous integrator that returns its pending reduced step;
  callers can resume a rejected attempt without repeatedly restarting it.
- [x] Add outward-rounded separating-axis and cubic coplanarity certificates to
  CCD. Uncertain cases retain Tight Inclusion. Focused contact/coupled checks:
  31 tests, 661 assertions passed before the IPC integration below.
- [x] Confirm that the explicit impulse method still stalls near 3.478 ms in the
  saved two-body case. The certificates improve queries but do not fix its dynamics.
- [x] Implement an alternative backward-Euler FEM incremental potential in
  `variational.clj`, using the existing Stable Neo-Hookean gradient/tangent and
  IPC Toolkit v1.6.0 contact derivatives through `native/variational.cpp`.
  Area-weighted improved-max physical barriers, PSD element/contact Hessians,
  sparse LDLT, CCD, whole-path positive-volume checks, and state rollback exist.
- [x] Implement displacement-based Coulomb dissipation with iterated lagged normal
  forces/tangent bases and a 1e-5 m/s smoothing threshold. Keep dissipation separate
  from stored contact energy in reports.
- [x] Independent native finite differences pass: maximum normalized gradient
  error 9.765e-9, Newton matrix residual 8.889e-9, friction/normal force ratio
  0.5. Floor crossing, intermediate tet collapse, and invalid actual initial
  shape checks pass (`tools/verify_variational.py`). Native library SHA-256:
  `f2146278ff28650a518250f88bf04aa0d7f4e409f7a7b0a602763fdbf2129c44`.
- [x] Five variational regression tests / 33 assertions passed, covering free fall,
  contact momentum/energy, ground friction, rollback, and initial tet inversion.
- [ ] Finish long two/three-body time/mesh/clearance studies. With roundoff-aware
  line search both 100 us cases completed 80 ms; the 50 us three-body run stopped
  at 51.6 ms. Adaptive rejected-step retry is now being checked; failures must
  remain visible, never be presented as completed bakes.
- [ ] Verify newly wired authored `:contact-method :ipc` bakes, native provenance,
  standalone packaging/licenses, and the original development viewport.
- [ ] Rerun full suites, CCD reference studies after the certificate changes, and
  restore historical exported files if any verification replaces them.
- [ ] Complete ball contact/refinement/rendering qualification, then implement
  and actually render clothing and paper before moving to rain/audio/circuits.

Latest verification in this continuation:

- [x] Full suite: **81 tests / 1061 assertions**, no failures/errors.
- [x] Current CCD archive `c684ee8cf28a50f23bccb5c68058ca35ac7a9f172ed1857041901f73a494b58a`:
  744 analytic queries, no false positives/negatives; 824 published reference
  queries, no false negatives, 14 conservative positives, 10 precision-limited.
- [x] All six coarse-mesh 80 ms impact cases completed (2/3 bodies, 100/50/25 us
  caps). Maximum sampled penetration is zero. Maximum total momentum error is
  1.05e-12 kg·m/s. Two-body final energy ratios are 0.8550/0.8684/0.8751;
  backward-Euler dissipation decreases with the step cap. The two-body refined
  100 us case also finished; the remaining refined study was explicitly stopped.
- [x] Authored IPC cache integration: six tests / 40 assertions passed before the
  full suite. The standalone app builds. Its packaged native backend exports only
  the intended C functions and depends on libc++/libSystem; relocated-library
  derivative/contact checks pass, with dependency commits/notices bundled.
- [x] Hot-reload new modules into the original GUI without replacing its state.
  Revision 34 remained intact, at the user's current cursor 53. All six historical
  export hashes were restored after the full tests.
- [x] Cancel the slow live IPC bake and stop the extra refined convergence job.
  `build/live-ipc-floor-bake.edn` records the interruption; no partial cache was
  published. Do not restart these jobs without addressing their excessive work.
- [x] Publish and capture the three-ball scene in the original window: revision
  35, 61 frames, 0.25 s, 615 nodes / 1920 tetrahedra. This visible scene uses the
  discrete AguaFria FEM solver, not IPC. Captures: `exports/three-ball-visible/`.
- [ ] Clothing research/discretization notes are read and recorded, but a garment
  solver/render and the paper scenes are still outstanding.

### Bake speed and implementation language (latest user steering)

- [x] Leave playback speed unchanged; the user found the duration control and
  explicitly withdrew the request to slow playback.
- [x] Check actual running compiler commands: `-OReleaseSafe`, hot reload on.
  Neither the desktop nor standalone release path needs Debug for hot reload.
- [x] Read Tiger Style's performance guidance. Remove unnecessary explicit
  stability/telemetry work during IPC Newton iterations and redundant element
  matrix-vector products, in AguaFria Zig. Same physics/tolerances remain.
- [x] Measure three repeated 12 ms two-body IPC impacts before/after: median
  2.917 s versus 2.263 s (22% less wall time). Raw evidence and compiler provenance:
  `build/bake-performance-before.edn`, `build/bake-performance-verified.edn`.
  `coupled-job/benchmark!` makes this measurement repeatable. Focused physics
  checks passed 13 tests / 88 assertions, including exact force/energy agreement
  between full and reduced assembly on a deformed solid.
- [ ] Diagnose existing non-bit-identical IPC repeats; record physical differences
  and deterministic reductions before claiming reproducible bitwise results.
- [ ] Profile FEM, collision queries, sparse assembly/factorization and allocations.
  Compare optimized hot reload and standalone kernels.
- [x] Add native resumable `AdvanceTask` / `advance-batch!` work budgets. Retain
  the absolute target, adaptive step cap and cumulative retry count across yields;
  rejected attempts consume budget. Existing uninterrupted entry remains usable.
- [x] Clojure `advance!` checks interruption between eight-attempt batches and
  accepts cooperative cancellation/progress callbacks. Cleanup preserves the
  caller's interrupt status. Focused tests: 10 tests / 105 assertions passed,
  covering unchanged state across yields, rejected-step rollback and cap retention,
  cancellation before starting and after eight accepted steps, and physical IPC
  contact/friction/cache regressions. `build/variational-batch-tests.log`.
- [x] Measure batching cost on the same workload: median 2.472 s, compared with
  2.263 s before batching. `build/bake-performance-batched.edn`. Profile host
  boundaries/native work before choosing a larger default work budget.
- [x] Sample the actual bake worker in the original JVM, separately from the
  renderer: `build/bake-worker-sample.txt`, thread `clojure-agent-send-off-pool-172`.
  1518 of 1548 sampled stacks were under the native bake call. C ABI subtrees:
  safe-step 265, objective/contact evaluation 198, element projection/assembly
  176, sparse solve 159, trial admissibility 73, begin-step 10. These are stack
  samples, not exact phase timers or a development-versus-standalone comparison.
  The observed worker is dominated by native work; prioritize native collision,
  element Hessian projection and sparse work over JVM tuning. Profiling perturbs
  timings: keep `build/bake-profile-run.edn` out of ordinary timing comparisons.
- [x] Wire solver progress/cancellation to authored job controls in the UI.
  The header's Authored jobs panel is now connected to one optional development
  Clojure worker, with duration/presets/progress/cancel and UI-thread publication.
  Native readback verifies the controls (`build/authored-controls.png`). An early
  IPC cancellation passed in 0.130 s and retained revision 50 / 970 cached frames.
  The full 0.6-second explicit box/tetrahedron publication check did NOT pass:
  it stalled near tick 61 during contact, exceeded the check timeout, and later
  acknowledged cancellation without publishing. The subsequent bounded probe
  identifies mutual body contact before the floor, correcting the initial
  ground-contact diagnosis.
  `build/authored-publish-check.edn` records the failed publication check.
  A separate 0.125-second pre-impact cache-publication check passed in 1.227 s:
  31 frames, two solids, revision 51, with native rendered evidence in
  `build/authored-solid-publication.png`. This verifies the UI/Flecs/cache path,
  not the failed longer contact scenario. `build/aguafria-ui-build.log` records
  the successful standalone rebuild including the authored controls.
- [x] Make explicit FEM resumable across bounded native batches, retaining the
  target, accepted state, cumulative attempts, and reduced retry step. Count
  rejected trials against the batch budget and restore them before yielding.
  Clojure cancellation/progress callbacks execute between eight-attempt batches.
  Free-flight/resume checks pass 148 assertions. The box/tetrahedron retry case
  passes 881 assertions and produces identical states/reports for 1/8/64-attempt
  batches. Live cancellation during contact at tick 60 / step 89 acknowledged in
  0.1245 s, retaining revision 51 and all 31 displayed frames:
  `build/explicit-ui-cancel-check.edn`, `build/explicit-cancel-ui.png`.
  Full isolated regression passes 90 tests / 2,183 assertions with zero
  failures/errors (`build/explicit-batch-tests.log`). The standalone executable
  and app bundle rebuild successfully (`build/explicit-batch-build.log`); no
  standalone GUI was launched. The original hot-reloaded window remains active.
- [ ] Resolve the explicit box/tetrahedron mutual-contact stall without relaxing
  penetration acceptance or presenting a partial bake as a finished collision.
  `build/explicit-contact-bounded-probe.edn`: 256 attempted steps in one frame,
  164 rejected, time 0.2526625 s, smallest accepted step 3.125e-6 s, sampled
  penetration approximately 1e-9 m, minimum Jacobian 0.9762, no floor impulse.
  Investigate coupled position repair and adaptive retry policy; batching alone
  is not a numerical fix. Check mesh/time convergence of any changed solver.
- [ ] Bound lower-level collision/linear work further if measured cancellation
  latency remains excessive for larger scenes. Attempt quotas and one measured
  cancellation latency are not a wall-time guarantee.
- [x] Move handwritten application/solver support from C++ into AguaFria Zig.
  Delete `panel.cpp`, `extension_host.cpp`, and `interval_geometry.hpp`.
  Port panel layout/actions/exports to `panel.clj`, the bounded plugin/command
  host to `host.clj`, and interval/edge/path algorithms to `geometry.clj`.
  Remaining C++ is the IPC/Eigen and Tight Inclusion boundary plus generic
  shared ImGui calls. Independent C++ ABI fixtures remain tests only.
  Vulkan handles rendering; GPU FEM and deterministic parallel assembly remain
  future work.
- [x] Keep the original PID 37705 and its revision-50 cache during the UI/host
  migration. Verify the AguaFria-rendered UI through native Vulkan readback
  (`build/panel-aguafria.png`). Retire the old host on the owning thread and
  restore its bridge using the new host; no plugins were loaded during migration.
  Verify actual bridge replies from the new registry (ticket sequence restarted).
- [x] Standalone native host passes plugin ABI, lifecycle, failure cleanup,
  reentrant command submission, queue, and external Clojure tests (23 assertions).
  Standalone export probe verifies JSON metadata, per-body topology, 17-column
  trajectory, and deformation NaN fields without altering the live exports.
- [x] Full regression: 87 tests / 1,154 assertions pass. Final geometry build:
  744 analytic queries with zero false positives/negatives; 824 published
  queries at 1e-8 with zero false negatives, 14 conservative false positives,
  and 10 precision-limited cases. Final independent contact/gradient/sparse
  solve/ground/path tests pass (`build/aguafria-geometry-variational-checks.json`).
- [x] Verify the live foreign-library migration, not just configured link paths.
  Unchanged function implementations can retain older foreign-library links;
  distinct `pitoco_aguafria_*` internal entries force the relevant AguaFria
  implementations to change. The public plugin SDK stays at v1. Final live
  contact/variational checks pass 27 tests / 442 assertions, and the rebuilt
  packaged backend passes independent numerical checks. Record the actual
  function generations and library hashes in `build/aguafria-port-verification.edn`.
- [x] Build the standalone native executable after removing the application C++
  sources. Keep standalone execution headless during development; the original
  hot-reload GUI remains the only application window.
- [x] Move 12-DOF element PSD projection into AguaFria Zig, calling Accelerate's
  native LAPACK eigensolver directly. Symmetrization/clamping/reconstruction are
  native AguaFria; C++ only inserts already-projected blocks into Eigen's container.
  The experimental IPC path now requires macOS 13.3+, recorded in provenance.
- [x] Repair the truncated friction-adapter function exposed by a fresh build.
  Independent native gradient/friction/path tests pass, including projected
  sparse insertion with a permuted node order (residual 1.878e-9). Published
  backend SHA-256: `59ea33a7bac99382af06211b675e6ca120edd89bfbf22ad70943c2783eb0e8c4`.
- [x] Projection and physics checks: 12 tests / 129 assertions passed. Known
  dense spectra at scales 1e-12, 1 and 1e12, asymmetric inputs and failure
  immutability are covered. `build/variational-projection-tests.log`.
- [x] Standalone `build/verify-projection` builds/runs without JVM or hot reload;
  `otool -L` shows only Accelerate/libSystem. The packaged contact backend is
  updated; the standalone app's authored IPC job UI remains a separate task.
- [x] Same three-run benchmark: 2.358 s median after the projection move versus
  2.472 s before it. `build/bake-performance-aguafria-projection.edn`. Treat the
  small difference as workload-specific evidence, not a general speedup promise.
