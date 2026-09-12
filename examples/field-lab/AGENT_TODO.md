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
- C++ and GLSL artifacts have their own compilation lifecycle.

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
  the existing window to replace a stale frame; renderer readback stays pending.
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
- [ ] Add renderer-owned frame export/readback to verify the exact rendered FEM
  frame and cache cursor when operating-system window capture is stale.
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
