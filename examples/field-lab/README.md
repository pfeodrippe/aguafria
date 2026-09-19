# Pitoco

A native Houdini-inspired experiment workbench written with **AguaFria Zig**.
Flecs owns the procedural scene and authoritative body state. Vulkan renders
the actual deformable triangle surfaces, or analytical rigid spheres, with
material seams, studio lighting, and ground shadows. Dear ImGui provides the inspector, graph, and timeline.

Two inspector presets are available: **Single ball** and **Three balls**.
**Authored jobs** opens the offline Clojure-scene controls for one ball, three
balls, and a box/tetrahedron collision. New authored jobs default to **Implicit
FEM / continuous contact** (convergent IPC); the explicit/discrete path is retained
as a comparison option. With the authored controller attached, the main **BAKE / Apply & bake** controls
also route through that worker, preserving the inspector's mass, radius, launch,
spin, material, body count and duration. Busy requests cannot silently fall back
to the old coarse solver. This changes future bakes, not an already displayed cache.
The explorer and numerical-contract panel identify the **cached** method from
Flecs provenance. Changing the next-job selection cannot relabel an existing
cache. Old caches without method metadata show "Cached solver not recorded".
Set **Duration** there and choose
**43 / 205 / 1209** FEM nodes per ball (80 / 640 / 5120 tetrahedra). These change
the volume simulation mesh, not just display tessellation. The box/tetrahedron
retains the mesh authored in its script. The same scenes can be driven from the
development REPL (see below). The three-ball preset
launches two spheres toward a third. **Continuum FEM is the default** for new development sessions. It uses a
finite-deformation Stable Neo-Hookean solid with adaptive substeps and ground/
convex-body contact. The solver selector also retains XPBD and rigid modes.
The default inspector-driven jobs use 43 nodes and 80 tetrahedra per body.
Clojure-authored refined jobs also bake and display mesh-sized single-body and
coupled three-body simulations. Verified runs include 1,209 nodes / 5,120 tets
for one ball and 3,627 nodes / 15,360 tets total for three interacting balls.
Contact convergence, continuous collision handling and calibrated materials
remain active work. **Clothing, paper, rain and circuit/audio simulation are not
implemented or rendered yet.** They follow the solid-contact and rendering milestone.

Use **Frame (F)** in the header (or press **F** outside a text field) to fit the
simulated bodies in the viewport. Framing uses their current FEM vertices and
center height, so small authored solids no longer disappear in a distant view.
The current finer authored three-ball example completes all 145 frames of a
0.6-second collision with 3,627 nodes / 15,360 tets. The recorded minimum element
Jacobian is 0.3635; independent export checks verify 525,915 particle rows and
vertical momentum balance within 2.45e-11 N s. These are homogeneous soft-solid
examples, not calibrated sports balls. `build/three-ball-refined-contact.png`
shows the actual cache. Refining from 205 to 1,209 nodes per body changes center
trajectories by up to 7.79 mm: spatial qualification is still incomplete.

## Offline verification tools

Run `python3 tools/verify.py --help` from this directory. One command covers
`material`, `mesh`, `abi`, `tetra`, `mixed`, `rod`, `lighting`, and `visibility`;
use `python3 tools/verify.py COMMAND --help` for options. Material precision,
mesh-export and native ABI checks use Python's standard library. The mechanics
and rendering reference modules load NumPy/SciPy only when selected; reading a
rendered capture additionally uses Pillow. None is a Pitoco runtime dependency.

The independent material check uses 80-digit Decimal arithmetic, the element
check integrates rational polynomials, and the dynamics references use SciPy.
They check outputs produced by the native AguaFria Zig code. Keeping independent
arithmetic helps detect errors that native self-comparisons could miss.

The former eight `verify_*.py` scripts are consolidated into `verify.py`,
`mechanics.py`, and `rendering.py`. Commands below use the new entry point;
archived reports retain their original filenames and source hashes.

## Development with AguaFria hot reload

Run the native application and nREPL in the **same process**:

```sh
clojure -M:desktop
```

The host prints its nREPL port and writes `.desktop-nrepl-port`. Connect the editor
to that port, then re-evaluate the changed AguaFria declaration normally. AguaFria
compiles and publishes new native dispatch targets; existing callers pick up
compatible changes. `(az/await! 'field-lab.surface)` waits for that namespace's publication when needed. The host runs
GLFW on macOS's first OS thread, using the same arrangement as `simple-game`.
There is no Pitoco-specific hot-reload engine or separate window process.

GLSL edits also need shader compilation and Vulkan pipeline replacement. In the
existing development REPL, call `(field-lab.build/prepare-shaders!)`, then
`(field-lab.app/request-shader-reload!)`. Replacement runs on the render thread
after submitted GPU work finishes; it preserves the displayed simulation cache.

Use this path for development. `:build` below remains the standalone release path,
which deliberately uses source-only loading and disables development dispatch.
A separate `:nrepl` does not control an already-running standalone executable.

For solver declaration or native-library layout changes in an attached idle
development session, use `(field-lab.live/reload-solvers!)`. It reserves the
authored-job mailbox while AguaFria recompiles, keeping the existing cache
playable. It refuses to start during a bake or while a direct REPL
`coupled-job/with-system!` scope owns native solver storage. A failed reload keeps new bakes
disabled; fix the source and call it again. This coordinates storage ownership
around AguaFria's existing hot reload, rather than introducing a second compiler.

### Bake performance

The desktop host explicitly uses `ReleaseSafe` with `:reloadable? true`; hot reload
does not force Debug compilation. Standalone builds also use `ReleaseSafe`.
Development dispatch can still cost indirect calls and prevent some inlining;
each authored implicit bake now compiles a frozen native solver snapshot. The
window retains development hot reload; a running bake rejects changed solver
declarations. Native allocation ownership and cancellation checks remain intact.
Vulkan currently renders cached geometry; it does not accelerate FEM assembly.

The implicit snapshot reproduces a complete 0.6 s coarse three-ball run to
6.50e-14 m in final nodal positions and 1.50e-12 m/s in final nodal velocities.
Observed wall times were 154.40 s through development dispatch and 112.79 s through
the snapshot; these are not a controlled multi-run performance benchmark.
The three-pair explicit microbenchmark separately measured medians of 8.597 s and
1.446 s with identical histories/reports. Faster execution does not fix model error.

Run persistent implicit timestep studies without opening another window:

```sh
clojure -M:scene-study scenes/three-ball-ipc.clj \
  '{:maximum-steps [0.001 0.0005] :execution :snapshot :output "build/my-study.edn"}'
```

The output filename must be new. Completed cases are saved atomically; cancellation
preserves them. Scene `:bake` data can specify `:velocity-tolerance` (m/s, default
1e-7) and `:maximum-newton-iterations` (default 100). This tolerance controls the
nonlinear momentum solve, not timestep or mesh accuracy; they are rejected for
a non-IPC scene. IPC scenes also accept `:integration :backward-euler` (default)
or the experimental `:integration :newmark` (beta=1/4, gamma=1/2) and
`:integration :bdf2` methods. BDF2 preserves accepted-step history across output
frames, uses variable timestep coefficients with growth capped at 2×, and starts
with backward Euler when no history exists. Rejected trials preserve history.
Near an output boundary, the solver fits the final two steps within the step
cap to avoid a tiny leftover solve. Already aligned schedules retain the same
clock-roundoff rule. This scheduling safeguard does not establish contact accuracy.
Create a new context or call `variational/reset-history!` after externally editing
an existing context's positions or velocities. Its contact impulse is the BDF2
integral of separately evaluated contact forces, not a momentum-derived value.
Newmark has verified second-order
behavior on an independent elastic-oscillation benchmark and less numerical
damping there; sharp-contact pressure oscillations remain a separate concern.
It is an experimental authored research option: the three-ball sharp-contact
qualification currently fails at 1, 0.5 and 0.25 ms. Keep backward Euler for these
presets; Newmark's oscillator result does not qualify collision accuracy.
The CLI logs progress every 24 output ticks. Missing penetration or
ground-impulse measurements are reported as `nil`, never invented as zero.

An independent elastic-wave contact benchmark is available through the same
impact-study command:

```sh
clojure -M:impact-study '{:benchmark :rod-impact
                         :audit-forces? true
                         :cases [{:divisions [1 8 1]}
                                 {:divisions [1 16 1]}
                                 {:divisions [1 8 1] :steps-per-contact 512}]
                         :output "exports/rod-impact-study.edn"}'
```

This bakes a small-strain tetrahedral bar into a frictionless rigid plane using
the implicit IPC solver. Analytical wave propagation supplies independent
predictions for contact force, duration, impulse and center velocity. The report
retains input geometry, solver and experiment fingerprints, complete samples,
rebound error and numerical energy loss. Records are written atomically, preserve
partial samples on failure and require a new output filename. The benchmark
currently exposes substantial numerical error; completed runs are not automatically
labelled validated. See `RESEARCH_AND_DESIGN.md` for results and assumptions.
With `:audit-forces? true`, each output also retains accepted-step forces and
impulses, excluding rejected trials. The `:force-audit` summary reports step-level
L1 error and peaks alongside output-bin error, time coverage and impulse
reconciliation. Use this audit for force qualification: output averaging can
hide severe short-duration spikes. This observer does not change integration
settings or the output cadence.

An independent Python/SciPy **1D reference**, separate from the native solver,
isolates contact-node inertia. Run it in an environment with NumPy and SciPy:

```sh
python3 tools/verify.py rod \
  --output exports/independent-rod-reference.json \
  --cells 16 32 64 \
  --formulations lumped-barrier consistent-barrier redistributed-barrier redistributed-signorini
```

It requires a new output file, checks analytical free-bar frequencies and energy
gradients, and compares two tight integration settings for every case. Results
retain source/version identity, force/velocity/gap/energy histories and failed
comparisons. This research tool does not alter production masses or qualify the
3D ball simulation. The completed comparison and limitations are recorded under
"Independent contact-boundary reference" in `RESEARCH_AND_DESIGN.md`.

For a native check of an authored body's mass discretization, call
`impact-study/audit-inertia!` with a normal solid description. It compares exact
integration of the tetrahedra's current linear position/velocity fields with
the actual nodal masses, returning mass, center, inertia tensors, kinetic energy,
linear/angular momentum and relative differences. Density and integration
weights refer to the rest configuration. Rod study results now include this
`:inertia-audit` at the initial and final states. These measurements distinguish
mass-discretization error from geometry and contact error; they do not advance
or change the supplied simulation state.

The implicit rod benchmark also accepts `:mass-model :consistent` (default
`:lumped`). The consistent P1 tetrahedral operator couples neighboring node
accelerations and integrates kinetic energy over the represented solid. It is
an experimental numerical option, not a massless contact method or a claim that
force oscillations are solved. Backward Euler, Newmark and BDF2 use the selected
operator. Direct native callers select it when creating `variational/with-context!`
and use `variational/body-observables!` for matching kinetic-energy telemetry;
`Dynamics`' explicit solver continues to use nodal masses. Every body currently
requires uniform reference density. The rod's transverse-energy diagnostic is
still explicitly labelled as a nodal-mass measurement under this option.

In the controlled 32-cell rod comparison, consistent mass lowers accepted-step
force L1 error from 53.74% to 16.38%, but increases measured time from 29.11 s to
87.00 s. Both retain contact-force spikes and remain unqualified. The defaults
are unchanged. See `build/consistent-mass-comparison.png` and the matched study
and verification described in `RESEARCH_AND_DESIGN.md`.

`field-lab.mixed-tetra` contains a separate experimental element for the next
contact formulation: enriched displacement with a linear face trace, independent
P1 velocity, native hyperelastic energy/forces/stiffness, and a conservative
Bernstein/interval inversion bound over the whole element and coefficient path.
It passes 11 tests / 458 assertions and independent exact-polynomial checks.
It is not connected to ball jobs or the viewport yet; global mixed assembly and
contact validation remain open. The derivation and limitations are recorded in
`RESEARCH_AND_DESIGN.md`.

The explicit solver now avoids reassembling elastic forces after its velocity
phase when every position still matches the synchronized contact geometry.
It refreshes kinetic energy/momentum in the original summation order and falls
back to full evaluation if a position changed. A matched 0.6-second three-ball
bake took **93.50 s before / 59.44 s after** (36% less wall time); every sampled
history, final particle position/velocity, and solver report matched exactly.
This is one measured workload, not a general speedup guarantee. Evidence:
`build/motion-refresh-three-ball.edn`. The box/tetrahedron also matches exactly:
40.42 s before / 34.53 s after (`build/motion-refresh-box.edn`).

The September 12 optimization removes explicit stability-bound and telemetry
calculations from implicit Newton/line-search evaluations. It also computes each
element matrix-vector product once for its three components. These changes are
AguaFria Zig, retain the constitutive/contact equations and solver tolerances,
and require no new C++ implementation.

A two-body IPC impact benchmark (86 nodes, 160 tetrahedra, 12 ms simulated,
100 us maximum step, 0.0001 m clearance) took a median
**2.917 s before / 2.263 s after**, three runs each in
the same hot-reload process. This is about **22% less wall time**, not a speedup
claim for all scenes. Raw evidence is in `build/bake-performance-before.edn` and
`build/bake-performance-verified.edn`. The latter includes actual compiler
commands, solver fingerprints, host information, iteration reports, and sampled
physical results. Repeated IPC runs were not bit-identical before or after the
optimization. The focused material/implicit physics suite passed 13 tests and
88 assertions; this is not an engineering calibration or mesh-convergence claim.

To repeat the measurement without changing the viewport:

```clojure
(require '[field-lab.coupled-job :as joint])

(future
  (joint/benchmark! {:output "exports/bake-performance.edn"}))
```

The defaults run three small two-body impacts. `:contact-method :discrete` selects
the other solver; `:bodies`, `:refinement`, `:seconds`, and `:maximum-step` select
the workload. Compile time is excluded. Avoid other bakes during measurements.

Implicit jobs now yield to their host after eight attempted time steps, including
failed attempts. A native `AdvanceTask` retains the absolute target, reduced step
cap and cumulative retry/report state. `advance-batch!` returns status 6 when its
budget is exhausted, at the last accepted state. Resume the same task on the same
workspace with unchanged solver parameters; only status 0 means completion.
Native callers can own this protocol without a JVM. `advance-native!` remains an
uninterrupted entry point for callers that explicitly want it.

The Clojure `variational/advance!` wrapper checks thread interruption between
batches and accepts an optional final options map:

```clojure
{:maximum-attempts 8
 :cancelled? (fn [] @cancel-requested?)
 :on-progress (fn [report] (reset! latest-progress report))}
```

Callbacks run on the calling worker after native work. Cancellation throws with
`:cancelled? true` and the last cumulative `:report`, preserving accepted state
and releasing the advance task. Each Newton attempt and line search is bounded,
but an individual sparse/contact operation can still be expensive: this provides
work limits, not a guaranteed wall-clock cancellation latency. The development
window's Authored jobs panel now uses these callbacks for progress/cancellation.
The first three-run batched benchmark measured a 2.472 s median on the workload
above (`build/bake-performance-batched.edn`), versus 2.263 s before batching.
This overhead needs profiling; do not attribute the earlier 22% reduction to the
current batched wrapper without accounting for it.

The explicit Verlet path accepts the same callback options through
`coupled-job/advance!`. Its native `ExplicitTask` retains a frame's absolute
target, cumulative report, and reduced retry step. Own it with
`create-explicit-task!` / `destroy-explicit-task!` and call
`advance-explicit-batch!` on that same task. Do not advance its assembly elsewhere
while the task is active. Read `explicit-progress`: status 0 is resumable, 1 is
complete, and 2–5 report input/state/step/contact failure. An attempted step,
including a rejected one, consumes budget; a zero budget does not advance state.
Cancellation is handled by the host between batches and releases task storage.
An initial macOS sample of the active bake worker put 1518 of 1548 sampled stacks
under the native bake call (`build/bake-worker-sample.txt`). Collision-safe step
selection, contact evaluation, element Hessian projection and the sparse solve
all appeared prominently. This guides subsequent native optimization; it does
not measure the cost of development dispatch within that native work.

Element Hessian symmetrization, PSD clamping and reconstruction now run in
AguaFria Zig, calling the system Accelerate eigensolver directly. The experimental
IPC path therefore requires macOS 13.3+; solver provenance records the provider,
ABI and OS version. The remaining C++ element adapter only inserts the projected
block into the external sparse matrix. The same three-run benchmark measured a
2.358 s median after this move (`build/bake-performance-aguafria-projection.edn`).
The modest difference from 2.472 s is workload-specific and needs broader checks.
Focused tests pass 12 tests / 129 assertions. A standalone projection executable
(`build/verify-projection`) runs and links only Accelerate and libSystem, verifying
this kernel independently of the JVM and development dispatch. This does not
claim the whole authored IPC job/UI path is available in the standalone app.

[Tiger Style](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/TIGER_STYLE.md)
motivates batching work, explicit resource bounds and eliminating repeated work.
Pitoco follows some of those principles, but is not fully optimized or audited
against that guide. Remaining targets include profiling contact versus FEM versus
sparse solves, reusable matrix storage,
deterministic parallel assembly, and comparing optimized development dispatch
with standalone kernels. Shortening duration or reducing refinement reduces work
but changes the experiment; increasing time steps needs error/convergence checks.

Solver edits affect the next **Bake**; existing cached frames remain the results
of the earlier solve. Presentation-function edits can affect the next frame.
Changing a long-running function's body affects its next invocation, and breaking
state layouts require migration or restart under AguaFria's normal rules. The
Pitoco panel and exporters now compile from AguaFria Zig and update through its
native reload path. External library adapters and GLSL shaders have separate
compilation lifecycles. The extension registry stays in a stable native AguaFria
host library across presentation reloads.

## Native implementation ownership

The development window now has an **Authored jobs** button in the header. Its
panel provides duration, one/three-ball and box/tetrahedron presets, progress,
and cancellation. A single optional Clojure controller runs these scene-data jobs
and publishes complete caches through Flecs. Desktop startup attaches that worker;
standalone builds show the controls as offline until a controller is available.
External standalone job submission is still pending.

Cancellation is cooperative. IPC and explicit FEM both check between bounded
native batches. The explicit task retains its target, accepted state and reduced
retry step across batches. In the live box/tetrahedron test, cancellation during
mutual contact acknowledged in 0.125 seconds and retained the displayed cache.
This is a measured case, not a universal wall-time bound.

The 0.6-second box/tetrahedron collision now completes and renders all 145 frames.
A coupled mass-weighted position solve resolves the mutual-contact stall that
previously appeared near 0.253 seconds. It retains the existing penetration limit
and checks element validity afterward. Complete runs at 100, 50 and 25 microsecond
maximum steps had no rejected steps. An independent particle-export check passes
the mass/velocity and vertical momentum balances.

The result is still not physically qualified: the 50-to-25-microsecond change
moved some final nodes by 3.2 cm, and mesh convergence/material calibration remain
open. Completing this numerical fix does not complete clothing/paper.

Pitoco's application code is AguaFria Zig. The former `native/panel.cpp`,
`native/extension_host.cpp`, and `native/interval_geometry.hpp` are removed.

- `src/field_lab/panel.clj`: layout, keyboard/camera controls, playback actions,
  numerical labels, native CSV exports, and scene JSON.
- `src/field_lab/host.clj`: bounded native command mailbox, completion history,
  plugin lifecycle, SDK callbacks, and the optional external Clojure file bridge.
  This host builds with libc alone; it does not embed a JVM.
- `src/field_lab/geometry.clj`: outward-rounded interval arithmetic, separating
  axes, cubic volume certificates, edge generation, displacement round trips,
  and ground/positive-volume line-search backtracking.

Only `native/ccd.cpp` (Tight Inclusion) and `native/variational.cpp` (IPC Toolkit
and Eigen) remain as C++ application dependencies. They convert C arrays into
external library types, own those library objects, invoke library operations,
and report boundary failures. The shared ImGui C adapter exposes generic
controls/drawing primitives; all Pitoco labels, layout, and actions are Zig.
The C++ files under `test/native` are independent ABI/plugin interoperability
fixtures, not the application implementation.

Validation includes the standalone native export probe, native plugin lifecycle
and external Clojure protocol tests, 744 analytic CCD queries, and 824 published
reference queries. The latter retain 14 conservative false positives and no false
negatives at tolerance 1e-8. The full regression run passes 87 tests / 1,154
assertions; see `build/aguafria-port-tests.log`. After changing the internal
adapter entry points to bypass retired foreign links, live contact/variational
checks pass another 27 tests / 442 assertions. Final native contact checks are
in `build/aguafria-packaged-variational-checks.json`. This migration does not
complete material calibration, mesh/contact convergence, clothing, or paper.

## Build and run

From this directory, on Apple Silicon macOS with JDK 22+, Clojure CLI, `glslc`,
and a Vulkan loader/MoltenVK installation available to the shared native example:

```sh
clojure -M:build
./build/pitoco
```

The build uses AguaFria's pinned Zig compiler, prepares the shared native
libraries, compiles shaders, and also creates `build/Pitoco.app` for launching
from Finder. The executable uses `ReleaseSafe`; no JVM is needed to run it.
Run the plain binary from this example directory so relative shader paths resolve.
The app bundle carries its own shaders and dependency license notices.

```sh
clojure -M:test
clojure -M:nrepl
```

Tests execute compiled Zig for analytical physics, sphere collisions, Flecs graph
storage, cached replay, capacity handling, and deformable contact/energy behavior. The nREPL alias prints its port and
supports inspecting the same source modules; the standalone app is a separate
process and does not share that REPL's native world.

## Use the workbench

- Select **Single ball** or **Three balls** in the top bar. Switching starts a fresh offline bake.
- Choose **Rigid**, **XPBD**, or **Continuum FEM** in the scene panel. Select `mechanical_solver` to
  change effective stiffness (Pa), gravity, and friction. Apply starts a new cache.
- **Bake** computes the chosen duration without a wall-clock deadline.
- **Space / Play cache / Pause** controls cache playback; **Step** reads the next tick.
  Playback follows elapsed wall time at the selected rate, skipping display
  samples when rendering falls behind. It does not run the solver. Playing uses
  simpler lighting with hard triangle shadows; pausing restores detailed lighting.
  The viewport labels the active lighting mode. Development scripts can call
  `(field-lab.live/play!)` through the UI-thread mailbox.
- **R / Bake** restores initial conditions and recomputes. Select a source or solver node, edit
  parameters, then **Apply & bake experiment** to apply them together.
- Right-drag the viewport to orbit; use the wheel to dolly. The camera follows
  the active bodies and keeps their bounds in frame.
- Shortcuts: **1 / 3** selects the source, **D** toggles the material model,
  **Esc** stops a bake, **Home** rewinds, **Left / Right** reads adjacent ticks,
  and **E** exports the cache.
- Scrub the timeline to inspect cached states. Playing from a previous tick
  reads the same cached results. Playback never executes the solver or changes the cache.
- Select `telemetry_output` for total system energy and ball 01 measurements.
  In rigid mode, impact counts sum body contact events, so a pair contact counts
  twice. Soft mode shows ball 01 height compression and volume/rest-volume ratio.
- **Export cached run** writes `exports/trajectory.csv` (one row per body/tick)
  and `exports/experiment.json` (the applied inputs, solver version, body count,
  and fixed step). Soft mode also writes `particles.csv` with all 43 particle
  positions and velocities per body/tick. Rigid-only angular orientation and
  impulse columns are `nan` for soft bodies, whose full state is in the particle
  file. An export replaces the previous files. For the bundle, GLFW
  makes `build/Pitoco.app/Contents/Resources` the working directory, so its
  `exports` folder is there. The plain binary writes into this example's `exports`.

The app starts by baking six simulation seconds, then loops playback of that
cache. The viewport explicitly distinguishes BAKING, CACHE PLAYBACK, and CACHE
PAUSED. Change Duration before baking; Stop bake retains the partial cache.
The UI processes bounded batches of numerical steps for responsiveness, without
changing the timestep or dropping simulation time to meet a display deadline. The cache stops at 60 seconds instead of growing
without bound. Velocity controls are SI values. The three-ball source uses the
magnitude of Launch X (minimum 0.5 m/s) for the two incoming spheres; their initial
Z offsets are authored in `experiments.clj` and Launch Z applies only to single-ball.

## Clojure-shaped experiment definitions

`src/field_lab/experiments.clj` defines the sources using ordinary AguaFria
functions, typed constructors, `let`, and ordered assignment. There is no separate
text language or string-based expression evaluator. For example:

```clojure
(az/defn single-ball contacts/Sample
  [[config p/Config]]
  (contacts/Sample
    {:bodies [(p/initial config) (p/initial config) (p/initial config)]}))
```

The current graph is fixed; node selection and parameter editing work, while
arbitrary graph editing and coupled engineering fields remain future milestones.
Linear and finite-deformation tetrahedral FEM are now implemented.

Read [RESEARCH_AND_DESIGN.md](RESEARCH_AND_DESIGN.md) for the papers, equations,
architecture, validation evidence, and numerical limitations. This is a working
mechanical simulation prototype, not a validated material-specific engineering
package. XPBD here is a coarse elastic network with tetrahedral volume constraints,
**not continuum FEM**. Restitution and rolling-loss inputs apply only to rigid mode;
soft rebound comes from stored elastic energy and damping. Pair contact uses a
convex-surface approximation; no self-collision, fracture, edge-edge CCD, or
calibrated constitutive law is implemented. Soft shadows project the actual simulated triangles onto the floor from the key
light; analytic reflections are disabled in soft mode. Ground-relative soft energy uses zero height at the plane; rigid energy
uses zero height at the supported sphere center.

Sphere-plane collision in rigid mode uses analytical event timing. Rigid pair
collision uses eight substeps and four contact passes, suited to bounded inputs;
it is not general high-speed sphere-sphere CCD. XPBD mode uses four substeps and
six constraint iterations per tick. FEM mode uses adaptive velocity-Verlet substeps
and a finite-deformation constitutive law. The fixed 60-second soft cache reserves about 89 MB
(three bodies, 43 particles, position and velocity as f64); it is separate from
the smaller rigid summary cache and Flecs component storage.

[AGENT_TODO.md](AGENT_TODO.md) tracks the order: finish both ball experiments and
rendering, then clothing, then flat/folded/crumpled paper thrown into wind,
then physical rain and impact-driven audio, circuit/device-based pedal simulation,
and audio-interface input/output.

## Headless jobs

Compute a physical cache without opening a window:

```sh
clojure -M:bake '{:bodies 3 :seconds 6 :model :xpbd :stiffness 10000 :output "exports/three-balls.edn"}'
```

The argument is ordinary Clojure EDN data. An optional `:config` map overrides
source parameters such as `{:gravity 9.81 :height 3.5}`. `:model :rigid` selects
the impulse solver. Output contains metadata, applied configuration, mesh topology
and mass fractions, every tick, and all active bodies' measured particle state.
The job streams the cache to disk, uses a fixed numerical step, and has no graphics
or wall-clock pacing. Run jobs in separate processes; the native scene owns one
Flecs world per process. The current cache bounds jobs to 60 simulated seconds.

The development entry point was smoke-tested with a live compatible function
change: an existing native caller observed camera distance 9.0 → 13.0 → 9.0
without restarting. The same Flecs world and 1,441 cached states were retained,
and a sampled cached particle frame was unchanged.

## Static continuum FEM jobs

A separate native tetrahedral **linear elasticity** solver computes displacement,
stress, strain energy and reactions from Young's modulus and Poisson ratio. It is
currently headless for arbitrary static meshes. The ball viewport has a separate
finite-deformation FEM solver; it does not use small-strain elasticity for impacts. Use the existing development REPL:

```clojure
(require '[field-lab.fem-job :as fem-job])

(def beam-result
  (fem-job/solve! (fem-job/cantilever 4)))

(:report beam-result)

(fem-job/write-results! beam-result "exports/beam.edn")
```

Or run a job without starting another application window:

```sh
clojure -M:fem
clojure -M:fem path/to/job.edn exports/result.edn
```

The default is a clamped beam with refinement 2. Author jobs as ordinary Clojure
maps, serializable as EDN:

```clojure
{:mesh {:points [[0.0 0.0 0.0] [1.0 0.0 0.0]
                 [0.0 1.0 0.0] [0.0 0.0 1.0]]
        :cells [[0 1 2 3]]}
 :material {:young-Pa 2000000.0 :poisson-ratio 0.3}
 :constraints [[0 0 0.0] [0 1 0.0] [0 2 0.0]
               [1 1 0.0] [1 2 0.0] [2 2 0.0]]
 :loads [[1 0 10.0]]
 :relative-tolerance 1.0e-9
 :absolute-tolerance 1.0e-10
 :max-iterations 10000}
```

Constraints are `[node axis displacement-metres]`; loads are
`[node axis force-newtons]`. Axes 0/1/2 mean x/y/z. Repeated loads add.
Results contain the complete job and loaded solver fingerprints. Failed solves
remain explicitly unconverged, and the CLI exits unsuccessfully. Converged jobs
also write `result.edn.vtu` for ParaView: use **Warp By Vector** with
`displacement_m`, scale 1 for physical deformation, and color by `von_mises_Pa`
or `stress_Pa`.

Verification covers affine patches, rigid motion, boundary conditions, reactions,
a manufactured-solution refinement study and a refined cantilever. See
`RESEARCH_AND_DESIGN.md` for errors and assumptions. Clothing follows completion
of the ball simulations and rendering; rain, physical audio and circuits remain
scheduled in `AGENT_TODO.md`.

## Finite-deformation FEM and live authoring

In the running `:desktop` REPL, author and queue bakes with ordinary Clojure maps:

```clojure
(require '[field-lab.live :as live])

(live/bake! {:model :fem
            :bodies 3
            :seconds 2.0
            :stiffness 10000.0
            :config {:mass 20.0 :height 0.5 :vx 1.1 :vz 0.0 :spin 0.0}})

(live/status)

;; After the bake finishes, seek pauses the actual cached state.
(live/seek! 110)

(live/export!)
```

These commands use an atomic mailbox. The first native UI thread applies them;
the REPL does not mutate Flecs or ImGui concurrently. `:stiffness` is Young's
modulus in Pa in FEM mode and an effective network stiffness in XPBD mode.
Poisson ratio is currently fixed at 0.4 in the ball UI. Mass and radius describe
an illustrative homogeneous solid, not a calibrated hollow sports ball.
`BallMaterial` on the Flecs solver entity stores the model, modulus and Poisson
ratio. Failed integrations stop with an explicitly partial cache.

The same fixed-format ball solver supports headless jobs:

```sh
clojure -M:bake '{:model :fem :bodies 3 :seconds 2.0 :stiffness 10000.0 :config {:mass 20.0 :height 0.5 :vx 1.1 :vz 0.0 :spin 0.0} :output "exports/fem-three.edn"}'
```

For arbitrary tetrahedral meshes and uniform volume refinement, use
`field-lab.nonlinear-job` or `clojure -M:fem-dynamics`. Its default job is an
illustrative 5 cm solid ball with density 1100 kg/m³, E=100 kPa and ν=0.4. It
streams every particle at fixed output times; adaptive integration runs independently
of display time. `refine` splits every tetrahedron into eight, including interior
edges, while preserving the exact reference domain for mesh studies.

The material energy, stress, exact tangent, internal force balance, ballistic
motion and timestep convergence have numerical tests. Three-ball headless/UI
agreement covers 62,049 particle records within 5e-12. The legacy explicit UI bridge uses
coarse convex vertex/face pair constraints, lacks edge-edge CCD and pair friction,
and needs impact/mesh convergence and material calibration before predictive use.
The task ledger keeps those and realistic rendering ahead of clothing.

Measure plane-impact mesh and timestep sensitivity with:

```sh
clojure -M:impact-study
```

This runs 80, 640 and 5,120 tetrahedra at maximum steps of 10, 5 and 2.5 µs,
using the same polyhedral domain, mass, material and 1 m/s downward velocity.
The default duration is 60 ms, with measurements every 0.25 ms. Gravity and
friction are zero so integrated ground impulse can be checked directly against
vertical momentum change. Each case records its complete job, loaded native
fingerprints, compression, center trajectory, energy and element-volume bounds.
The EDN report compares successive steps on each mesh and successive meshes at
the smallest step. Results are saved after each case, with an explicit completion
flag; a hot-reloaded solver invalidates comparisons between different versions.

Peak values are sampled, and contact times are reported as intervals. Refine
`:sample-dt` separately when checking peak measurements. A positive upward
velocity is not called a completed rebound while contact continues; inspect
`:contact-free-at-end?`. These are numerical sensitivity measurements, not
experimental calibration or a guarantee that contact is sufficiently resolved.

## Refined FEM cache in the live workbench

The running development host can now display a mesh-sized single-ball FEM job:

```clojure
(require '[field-lab.live :as live])

(def refined-run
  (future
    (live/bake-refined! {:refinement 2
                        :seconds 1.0
                        :maximum-step 0.0001
                        :stiffness 10000.0
                        :config {:radius 0.45 :mass 20.0 :height 0.5
                                 :vx 0.0 :vz 0.0 :spin 0.0}})))

;; Deref returns the job description and a queued publication result.
(select-keys @refined-run [:frames :nodes :tetrahedra :queued])

;; The UI thread publishes it, or rejects it if the scene revision changed.
(live/refined-result)

(live/seek! 100)

(live/export!)

;; Keep the full source mesh, initial state and loaded native fingerprints.
(spit "exports/refined-job.edn"
      (binding [*print-length* nil *print-level* nil]
        (pr-str @refined-run)))
```

Refinement 0/1/2 produces 43/205/1,209 nodes and 80/640/5,120 tetrahedra.
The output cadence stays at 240 Hz; durations must be a whole number of ticks.
The native particle-storage budget is 384 MB. Computation runs on the calling
REPL worker, and `future` leaves that session available for other work. Cancelling
the future before publication releases the unfinished native cache between output
ticks. The existing displayed cache stays available while the new job bakes.

Completed storage transfers to a `MeshCache` component on the Flecs output entity.
Only the UI thread adopts or destroys published caches. Playback and scrubbing
read immutable saved particle states; rendering recomputes surface normals from
the actual boundary triangles. Returning to a coarse experiment releases the
refined cache. Refined source controls are read-only for now; author another job
in Clojure or use the source/material selectors to return to the coarse modes.

Export writes every particle to `particles.csv`, body measurements to
`trajectory.csv`, rest vertices to `reference.csv`, tetrahedra to `cells.csv`,
and configuration to `experiment.json`. Save the returned job descriptor alongside
these files to retain the maximum timestep and solver fingerprints.

The verified one-second job above has 241 frames and 291,369 particle records.
Its minimum body height is 0.620165 m, versus an initial diameter of 0.9 m, with
zero minimum ground clearance. A fresh viewport capture at tick 100 confirms the
31.1% compression. This historical run used the fixed polyhedral reference
domain (`:geometry :polyhedron`). New refined jobs default to spherical boundary
refinement, described below. Calibration and final rendering fidelity remain
unfinished.

## Refined three-body playback

The joint FEM path uses the current deformed triangle boundaries, barycentric
contact reactions, Coulomb friction, a shared adaptive clock and all-body step
rollback. It bakes all bodies before handing the complete group to Flecs and the
viewport. It remains a discrete vertex/surface contact method: edge crossings,
continuous collision detection and general self-contact are still pending.

In the existing development REPL:

```clojure
(require '[field-lab.live :as live] :reload)

(def three-run
  (future
    (live/bake-three-refined!
      {:refinement 2
       :geometry :sphere
       :seconds 1.0
       :maximum-step 0.0001
       :config {:mass 20.0 :height 0.5 :vx 1.5 :vz 0.0 :spin 0.0}})))

;; After the worker completes, confirm UI-thread adoption.
(select-keys @three-run [:frames :bodies :nodes :tetrahedra :queued])
(live/refined-result)
(live/seek! 100)

;; Save source geometry, physical parameters and solver fingerprints.
(spit "exports/three-refined-job.edn"
      (binding [*print-length* nil *print-level* nil]
        (pr-str @three-run)))
```

The verified spherical one-second run contains 241 frames, 3,627 nodes and 15,360 tetrahedra
across all three bodies. The outer balls launch toward the middle ball while all
three fall onto the floor. The current native window can play and scrub that
published cache. Source/material selectors return to the coarse experiments;
refined bake authoring still uses Clojure. Reference and connectivity CSVs now
include a `body` column, matching particle and trajectory exports; node indices
are local to each body.

### Spherical reference geometry

Refined jobs now project newly refined boundary nodes onto the authored sphere
and validate every tetrahedron before solving. This changes the physical FEM
reference mesh, not just its shading. Elements remain linear tetrahedra. At
refinement 2 the reference volume differs from an analytic sphere by 0.861%,
compared with 12.655% for the seed polyhedron. Requested body mass is preserved
using density = mass / actual mesh volume. Use `:geometry :polyhedron` to retain
the old fixed-domain refinement for numerical comparisons.

The optional scripting library can generate this mesh without loading AguaFria:

```clojure
(require '[pitoco.geometry :as geometry])

(def ball-source
  (geometry/sphere {:radius 0.45
                    :center [0.0 0.95 0.0]
                    :refinement 2}))

(:metrics ball-source)
```

This is pure Clojure mesh authoring. Submission of arbitrary mesh/solver jobs
through the external bridge remains pending; the bake helpers above currently
run in the development JVM.

The spherical three-ball bake took about 596 seconds for one simulated second,
with 10,080 shared substeps and no rejected steps. Its 874,107 exported particle
records agree with the mass-weighted body trajectories within 9e-15 in position
and velocity; vertical momentum balances gravity and ground impulse within
6e-11 N s. All five export hashes remain identical after playback and scrubbing.
Evidence is preserved in `exports/spherical-live/`, with source descriptions in
`exports/spherical-three-job.edn` and verified viewport captures at ticks 0, 100
and 240 in `exports/spherical-three-{start,contact,end}.png`.

The compiled suite now passes 50 tests / 417 assertions (including frame capture);
the independent pure Clojure
geometry suite (`cd scripting && clojure -M:geometry-test`) passes 5 tests / 49
assertions. These checks establish mesh, integration and replay properties.
They do not establish calibrated material behavior or converged impact stresses.

Clothing and paper are **planned, not implemented**. Neither has a rendered
scene or selector yet. Their order and physical requirements are recorded in
[AGENT_TODO.md](AGENT_TODO.md), after completion of the ball milestones.

## Optional scripting and native plugins

Pitoco standalone is compiled native code. It does not require a JVM, nREPL,
AguaFria development dispatch, or a running Clojure compiler. An external Clojure
program may own its own nREPL and reload its scripts, then send commands through
Pitoco's optional bridge. Native plugins can be authored with Clojure + AguaFria
Zig and compiled into a shared library before attachment.

The first SDK supports plugin load/unload, plugin commands, status, seek,
pause/play, stop, and export dispatch. Both the C ABI and the external client
use the same native command queue. Custom solver, mesh, renderer, and audio-node
registration are future interfaces; the current plugin example rewinds playback.
See [EXTENSIONS.md](EXTENSIONS.md) for the standalone workflow, Clojure library,
ABI ownership rules, and the executable plugin example.

Verification: the coupled FEM and UI/export suite passes **45 tests / 364
assertions**. The extension tests compile an AguaFria plugin, load and unload it
three times in a native-only host, and drive that separate process from a Clojure
client (**23 Clojure assertions**, plus native ABI/lifecycle assertions).
Run extension checks with `./test/extensions.sh` on macOS.

## Capture the rendered simulation frame

**Capture rendered frame** saves `exports/frame.ppm` directly from Vulkan after
GPU completion. It includes the whole client area (scene and inspector), without
OS window decoration. The PPM header records the renderer frame number, cache
revision, simulation tick and Vulkan color format. The tick is the one used to
build the mesh; a capture request does not seek or pause playback. The saved
image therefore remains attributable while playing or when the window is hidden.

In the existing development REPL:

```clojure
(require '[field-lab.live :as live])

(live/capture! "exports/contact.ppm")

;; Wait for :saved before reading the file. Completion remains until acknowledged.
(live/capture-status)

(live/acknowledge-capture!)
```

The native writer needs no JVM and is included in standalone. An optional external
Clojure program can encode a lossless PNG and an EDN sidecar with the captured tag
and RGB SHA-256:

```clojure
(require '[pitoco.frame :as frame])

(frame/png! "exports/contact.ppm" "exports/contact.png")
```

Readback uses a bounded request slot and at most 64 MiB of staging buffer payload.
It supports RGBA/BGRA 8-bit UNORM/sRGB surfaces with transfer-source usage and
coherent host memory. Unsupported surfaces/resource failures and file errors
produce explicit failure states. Capturing waits for the GPU and writes a file
on the render thread; it is an inspection/export operation, not a real-time video
encoder. The current swapchain upgrade requires unchanged dimensions and format.
The generic renderer's broader resize/recovery work remains separate.

`(live/verify-captures! {:ticks [0 100 240 100]})` verifies captures across seeks
without reinitializing the scene and leaves the last tick paused. Repeated full-frame pixel
hashes also include UI hover/input state, which must remain unchanged for equality.

Job code is grouped by solver responsibility: `field-lab.nonlinear-job/bake-cache!`
for a single refined ball, `field-lab.coupled-job/bake-cache!` for the coupled ball
preset, and `field-lab.fem-job/write-vtu!` for static FEM results. The previous
`refined-job`, `coupled-cache-job` and `fem-export` helper files have been removed.
`coupled-job/bake-scene!` also accepts generic solid scene descriptions; the ball
cache helper now delegates to that implementation. General external scene/job
submission is not implemented yet.

## Reusable engine and authored scenes

Pitoco keeps scene choices above the numerical implementation. A Clojure script
produces mesh/material/initial-condition data; the shared job layer validates it
and drives native FEM/contact kernels. Completed particle caches, provenance and
playback belong to the workbench/Flecs layer. Adding an example should normally
mean adding or changing scene data, rather than copying a solver or renderer.

This separation is implemented for tetrahedral solids. Cloth, paper, fluids and
circuits still need their own physical models and suitable data contracts; they
cannot become correct simulations merely by relabeling the solid solver.
External JVM scripting and native plugins will submit through the same command
boundary as it grows. The current standalone bridge does not yet accept typed
scene-bake jobs. Development hot reload is optional tooling, not a requirement
of a baked scene or the standalone application.

## Author solid scenes as Clojure data

`scenes/solid-impact.clj` returns a `:pitoco/solid-scene-v1` map containing
`:bodies` and `:bake`. Each body supplies a tetrahedral mesh, elastic material,
density, gravity, floor/friction settings and initial positions/velocities in SI
units. The example drops a box and a tetrahedron with different masses and
moduli, initially moving toward each other at 0.6 m/s each. Its geometry is generated by the independent `pitoco.geometry` library.

In the existing development REPL:

```clojure
(require '[field-lab.live :as live])

(def scene-data (load-file "scenes/solid-impact.clj"))

(def bake-job (future (live/bake-scene! scene-data)))
```

The numerical solver runs offline. Once all frames are complete, the UI thread
adopts the owned cache group and its `ScriptedScene` provenance component in
Flecs. The viewport renders the cached tetrahedral boundary and labels the scene
as **Authored solids**. Failed or stale jobs cannot replace the displayed cache.
The two existing ball presets remain available.

Export writes per-body mass, modulus, Poisson ratio, gravity, floor and friction
metadata, plus the reference mesh and particle/trajectory CSV files. A
content-addressed `exports/scenes/<SHA-256>.edn` records the normalized scene and
solver fingerprints. `experiment.json` references that source; a generic solid
is not described by a fictitious shared ball radius/mass.

Current limits: one to three connected, freely moving tetrahedral solids;
240 Hz output; 60 seconds maximum; bounded mesh/frame storage. Dynamics use the
existing Stable Neo-Hookean material and sampled vertex/face contact. General
contact convergence, CCD, prescribed dynamic loads, clothing and paper solvers
remain unfinished. The inward-moving box/tetrahedron trial now completes with
the coupled normal-contact fallback. Its 0.6-second bake takes 17,551 accepted
substeps and 21,585 rejected attempts: position-contact convergence is still
costly. Earlier failure evidence remains in `exports/scripted-contact-failure/`.
Completion of this case does not validate contact for arbitrary geometry.

Ordinary external Clojure programs can generate this scene data without AguaFria.
`live/bake-scene!` currently belongs to the development host; typed job submission
through the standalone FFI/file bridge is still pending. Standalone does not
require a JVM or development hot reload.

Verification: **59 tests / 602 assertions pass**, including near-contact face-normal,
normal-complementarity and continuous-feature regressions. Final focused input
validation passes 8 tests / 169 assertions. The live scripted scene has
145 frames and 8,990 particle records; independent export reconstruction closes
vertical momentum within 1.84e-11 N s. Captures and reproducible source data are
in `exports/scripted-impact-live/` (revision 34, including a capture at tick 65). The earlier
separate floor-drop baseline remains in `exports/scripted-solid-live/`. Clothing and paper have no solver/rendered
implementation yet; their work remains tracked in `AGENT_TODO.md`.


## Continuous collision queries

`field-lab.contact-mesh/sweep!` exposes native conservative CCD for linearly
moving features. For `:vertex-face`, give `[vertex face-a face-b face-c]` at
both endpoints; for `:edge-edge`, give `[edge-a0 edge-a1 edge-b0 edge-b1]`.
Each point is `[x y z]` in metres. Options are `:separation`, `:tolerance`,
`:maximum-time` (normalized to the supplied interval), and `:maximum-iterations`.
The result contains `:possible-contact?`, conservative `:time`,
`:achieved-tolerance` and `:precision-limited?`. A positive result is not an exact
impact time; an iteration-limited result may be intentionally conservative.

The linked Tight-Inclusion implementation and dependencies are pinned in
`field-lab.build`; the build uses strict floating-point arithmetic. Coupled job
provenance includes the actual native archive hash. Its C ABI also runs from a
native command-line executable without a JVM. Dependency licenses/source refs
are included in the packaged app.

The default scene integrator still uses discrete contact. The opt-in continuous
path described below now checks proposed FEM drifts; general existing-contact
handling and convergence remain required work.
Evidence is in `exports/ccd/`; these checks do not establish full research
validation or completion of clothing/paper simulation.


Verification includes 744 analytic queries and 824 selected upstream queries at
three tolerances. The upstream sets have zero missed collisions and 21
conservative false positives per run. Tightening tolerance alone did not remove
those false positives; the research notes record precision limits and the
required integration work. All six historical cache-export hashes still match
after the reload and tests.


`contact-mesh/sweep-meshes!` queries two meshes described as
`{:start [[x y z] ...] :end [[x y z] ...] :faces [[a b c] ...]}`. It builds
paired hierarchies of swept triangle bounds, then checks six vertex/face and nine
edge/edge features per candidate face pair. Open sheets and disconnected triangle
sets are accepted. This API is unsigned and assumes initially disjoint surfaces;
it does not test solid containment or arbitrary pre-existing intersections.
Initial feature coincidences can return time zero. Motion is linear per vertex.

Options are `:separation`, `:tolerance`, `:maximum-work` (node visits plus feature
queries), and `:maximum-iterations` per feature. Results include the conservative
time bound, achieved tolerance, face indices, feature kind, candidate count and
work counters. Invalid inputs and exhausted traversal budgets throw with explicit
status/reason; they never return clear. The native `MotionSurface` API supports
reusing endpoint arrays and refitting swept bounds without rebuilding topology.
The Clojure convenience API scopes and releases all owned native allocations.

The mesh study in `exports/ccd/mesh-study.clj` checks 120 queries with shuffled
face order, reversed mesh order, three scales and five analytic impact times.
All 60 edge-only crossings and 60 clear truncated paths classify correctly;
maximum time underestimate is 2.99e-8. Focused geometry tests pass 12 tests /
239 assertions. These checks validate the query layer, not simulation contact
response, self-contact, clothing mechanics or convergence.

The full regression suite passes **63 tests / 674 assertions** after the swept
mesh work. The standalone executable/app build also passes; the original GUI's
cache remains revision 34. The saved-cache audit records conservative zero-time
and precision-limited results, plus a budget failure and its bounded retry;
those results motivate the remaining contact-response work.


## Experimental continuous FEM stepping

Authored solid scenes accept `:bake {:contact-method :continuous :clearance 1.0e-5}`
alongside their duration and maximum step. The existing default is `:discrete`.
`coupled-job/advance-continuous!` also exposes this path for owned headless studies.
The implementation runs in native AguaFria Zig and does not need JVM callbacks
inside the numerical loop.

Each proposed Verlet drift is checked with the swept mesh hierarchy. Near-feature
normal/friction impulses use actual vertex/face or edge/edge interpolation and
nodal masses. A coupled normal solve handles mutually reactivated constraints.
Every changed trajectory is checked again; failed attempts restore the checkpoint.
Pair contacts do not repair positions in this path. Floor response and FEM forces
remain part of the same shared step.

A bounded precision retry handles conservative zero-time results at small positive
gaps: it reduces the requested query tolerance and repeats the whole sweep. It
never treats an unresolved possible hit as clear. Failure diagnostics retain the
gap, normal velocity, conservative time and achieved tolerance. The saved
3.448e-9 m resting-gap regression passes, along with edge impact, momentum,
floor-force balance, rollback and free-flight checks: **8 tests / 173 assertions**.

This is experimental: initial disjointness is a precondition, arbitrary initial
intersections/containment and self-contact are not validated, and contact/mesh/time
convergence remains unfinished. Clearance is a numerical parameter, not material
calibration. The GUI's historical cache is not evidence for this new integrator.


Final verification: the full suite before the precision retry passed **70 tests /
843 assertions**; all affected focused checks after that fix pass **8 tests /
173 assertions**. Authored continuous free fall bakes two cache frames with a
4.18e-18 m position error. The final three-body 12 ms study completes in 243
accepted steps with three rejections and 116,190 CCD queries. The two-body run
remains incomplete: after the saved checkpoint fix, the full run was stopped for
excessive runtime in the 3–4 ms interval. This is a recorded cancellation, not a
successful bake. Further existing-contact handling and convergence are required.
The standalone executable/app builds, and the numerical helpers hot-reload into
the original development window without replacing its historical cache.

### Experimental variational solid bakes

An authored solid scene can select `:contact-method :ipc` in `:bake`, with
`:clearance` in metres and `:barrier-pressure` in Pa. For example:

```clojure
(assoc scene :bake
       {:seconds 0.5
        :maximum-step 0.0001
        :contact-method :ipc
        :clearance 0.0001
        :barrier-pressure 1000.0})
```

This uses backward-Euler tetrahedral FEM, an area-weighted IPC physical contact
barrier, and iterated Coulomb friction. Bakes use the same cache/publication path
as other authored solid scenes. Reports include rejected steps, the smallest
accepted timestep, Newton residual, and separate contact and friction potentials.
Failures retain the last accepted state and do not publish a partial cache.
Initial floor-enabled vertices must be strictly above the ground plane.

The backend is native and its bundled dylib depends only on macOS system
libraries. Optional JVM scripting invokes the same native calculation; it is
separate from AguaFria's development hot reload. The standalone bundle includes
this backend and its dependency notices. This does not yet provide a new
standalone UI for configuring IPC jobs. Complete mesh/contact convergence and
material calibration remain tracked in `AGENT_TODO.md`.

Run the independent native derivative/contact checks with:

```sh
python3 tools/verify.py abi \
  build/Pitoco.app/Contents/Frameworks/libpitoco_variational.dylib
```

`V` (or the **Embedded preview / Measured mesh** button) compares an experimental
Phong-deformed detailed sphere with the measured FEM boundary on the same cache.
The preview is available for convex spherical cages and reports its smaller rest
radius as **Rest inset**. It does not rebake or change collisions. Ground-crossing
Phong frames use the contained linear interpolant, identified in the UI; an
invalid linear frame falls back to the measured surface. General render assets still
need primitive containment and contact validation; see `RESEARCH_AND_DESIGN.md`.


The viewport now integrates rough specular environment reflection with GGX
visible-normal sampling. The ground/environment remains a distant lighting
approximation, and mesh shadows still use projected silhouettes. Numerical
lighting checks (NumPy required) are reproducible with:

```sh
python3 tools/verify.py lighting --check --output build/environment-lighting.json
```

This checks CPU integration against independent quadrature under constant
lighting; it does not certify GPU arithmetic or scene light transport.


Finite disk studio lights now soften the direct illumination using multiple
importance sampling. They are preview lights; mesh visibility and matching soft
shadows remain unfinished. The broader area-light accuracy check is available as:

```sh
python3 tools/verify.py lighting --area-lights --check --output build/area-light-check.json
```

Its strict 1% sampling target currently **fails** in grazing cases. The recorded
failure is intentional evidence of remaining work, not a passing validation claim.


The deformable viewport now uses triangle visibility for finite-area shadows;
`Triangle shadows / r…` in the left panel confirms a complete display packet.
The old 128/256 direct/floor profile caused a driver-reported GPU hang even
with 64-pixel tiles. The viewport now uses 16 direct MIS samples per technique,
32 ground samples per emitter and 32 environment samples. This reduces both
work and sampling quality. Dense loop presets are not approved live settings;
higher quality requires bounded sample accumulation in floating-point storage. The shared renderer now has bounded raster
tiles, correct per-image presentation semaphores, compatible depth-preserving
passes, and operation-labelled Vulkan errors. Paused inspection requests 64-pixel
tiles; playback uses one submission with simpler lighting. This remains a preview renderer: device recovery, numerical ray-origin
bounds and radiometric convergence are unfinished.

`tools/verify.py visibility` generates a diagnostic shader and checks its
captured GPU result grid against an independent CPU oracle. Its optional
`--packet` input is a paused-frame native hierarchy snapshot. The existing
`AGENT_TODO.md` and `RESEARCH_AND_DESIGN.md` contain the measured results and
remaining qualification work.

For a development validation run on this Homebrew macOS setup, use Khronos
validation layers with synchronization validation enabled. The layer manifest
must resolve `libVkLayer_khronos_validation.dylib` by absolute path; a relative
Homebrew manifest was found but failed to load. Set `VK_LAYER_PATH` to the
manifest directory, `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation`, and
`VK_VALIDATION_VALIDATE_SYNC=1` before `clojure -M:desktop`. Check the loader log
for the inserted layer, rather than assuming the environment enabled it.
`MVK_CONFIG_LOG_LEVEL=4 MVK_CONFIG_DEBUG=1` additionally enables MoltenVK
shader/driver diagnostics (verbose). These settings are diagnostic, not required
for the standalone application.

`aguafria-examples-native.renderer/render-work-status` returns an atomic report
for the last completed frame: low 32 bits are submission count; high 32 bits are
the maximum submit-through-fence duration in microseconds. This measurement
includes host and driver overhead; it is not a GPU timestamp query or a promise
that future frames will fit the same duration.

## Experimental mixed FEM rod study

The native `field-lab.mixed-job` adapter owns tetrahedra, displacement and velocity
coefficients, solver workspace and accepted SI observables. Failed steps preserve
the accepted state, including the contact reaction. It currently supports a
frictionless horizontal plane, backward Euler and experimental SDIRK2. It is not the production ball
solver and does not yet implement body/body contact or clothing.

Run an offline study against the analytical small-strain, free-end rod impact:

```sh
clojure -M:impact-study '{:benchmark :mixed-rod-impact
  :cases [{:divisions [1 2 1] :steps-per-contact 64}
          {:divisions [1 4 1] :steps-per-contact 64}
          {:divisions [1 4 1] :steps-per-contact 128}]
  :output "exports/mixed-rod-study.edn"}'
```

The CLI uses a frozen ReleaseSafe native kernel by default. This allows compiler
optimization across the numerical functions without changing the application's
hot-reload configuration. Set top-level `:execution :reloadable` to measure the
direct development path. Both execute the same equations; the frozen scope
records its artifact hash and rejects solver-definition changes during use.
The internal boundary is valid only for the matching build, not a stable plugin
ABI. Output includes the authored mesh, material, native
definition fingerprints, each accepted step's force, momentum balance, energy,
and certified lower Jacobian bound. Existing output files are never overwritten.
Failed runs retain their accepted prefix and failure report. A completed solve
does not imply accurate impact: assess force, rebound and energy errors across
both timestep and mesh refinement before using this candidate for research.

Set `:integration :sdirk2` in a mixed rod case to exercise the two-stage method.
Its accepted contact impulse is the weighted sum of stage reactions; endpoint
force is recorded separately. At four axial cells and Tc/128, it retains more
energy than backward Euler, but contact-force error worsens from 16.85% to
75.47%. It is **not qualified for impact** and is not enabled in the ball UI.

The independent small-amplitude vibration study checks the integrator away from
contact. It derives a mode from exact-polynomial stiffness and consistent mass,
then compares native coefficients with its analytic trajectory. With Python
NumPy/SciPy available, run these commands from this example directory (the native
CSV must not already exist):

```sh
python3 tools/verify.py mixed --prepare-mode
clojure -J--enable-native-access=ALL-UNNAMED \
  -Sdeps '{:aliases {:evidence {:extra-paths ["test"]}}}' -M:evidence \
  -e '(require (quote aguafria.std) (quote [aguafria.zig :as az]) (quote [field-lab.mixed-job-test :as fixture])) (az/configure! {:optimize "ReleaseSafe"}) (fixture/write-mode-evidence! "build/sdirk-mode-input.edn" "build/sdirk-mode-native.csv") (shutdown-agents)'
python3 tools/verify.py mixed --verify-mode
```

SDIRK2's error drops from 1.336% to 0.3355% to 0.0840% for 16/32/64 steps;
the fine/medium ratio is 0.2503. This uses a 1e-9 N force tolerance. An earlier
1e-11 N run still stalled and remains a recorded limitation; these results do
not claim that all precision or contact problems are fixed.

To diagnose the contact error independently, export a completed mixed rod study
to JSON and run the same Python verifier in rod mode:

```sh
clojure -Sdeps '{:deps {org.clojure/data.json {:mvn/version "2.5.2"}}}' -M \
  -e '(require (quote [clojure.edn :as edn]) (quote [clojure.data.json :as json])) (spit "build/rod-native.json" (json/write-str (edn/read-string (slurp "exports/mixed-rod-study.edn")))) (shutdown-agents)'
python3 tools/verify.py mixed --rod-study build/rod-native.json \
  --output build/rod-independent.json --refinements 1 --semidiscrete
```

The output path must be new. Exact-polynomial linear stiffness, dual NNLS contact
and optional adaptive integration are independent of the native nonlinear solve.
The linear/native force comparison has a 0.5% gate; disagreement exits nonzero
and remains in the output. The default-amplitude SDIRK case fails that comparison
at 2.204%; reducing speed and gap by ten passes at 0.0605%. Adaptive integration
uses scaled displacement/velocity/impulse tolerances and checks two tolerances.
Force histories use the native sample windows, including when integration is
refined inside each window. This is a diagnostic of the discretized model,
**not a passing continuum-impact validation**.

For native timestep comparisons, call
`field-lab.impact-study/compare-mixed-time-refinement` with the coarse and fine
case maps from the saved studies. It requires matching physics and nested accepted
times, sums fine-step impulses into coarse windows, and reports both common-window
and raw accepted-step force errors. This distinction matters: a wider measurement
window can hide force ringing. The helper reports sensitivity and does not assign
a physical-accuracy pass.

The independent verifier also accepts `--restriction boundary-star` or
`--restriction continuous-velocity` with `--rod-study`. These diagnose alternative
linear approximation spaces; they are not native solver modes. The former keeps
enrichment only in cells incident on the initially lowest vertices, with ordinary
P1 tetrahedra elsewhere. The latter identifies projected-velocity coefficients
at shared vertices. Both preserve affine mass moments, but the tested variants
worsened impact-force accuracy and have not been promoted. Keep `--semidiscrete`
enabled to check sensitivity to adaptive integration tolerance; failed checks
remain in the output and exit nonzero.

During frame execution, `VK_ERROR_DEVICE_LOST` now stops further rendering,
rejects queued frame captures and pauses Pitoco, preserving its CPU cache and
running development JVM. The window title reports that the GPU stopped;
`renderer/device-lost?` exposes the state to native/Clojure callers. This is
failure containment, not automatic device reconstruction. Initialization and
other Vulkan failures still report their operation and result explicitly.

The experimental mixed tetrahedral kernel now also assembles a global
backward-Euler potential, residual and tangent with shared boundary coefficients,
private consistent inertia and physical gravity loads. Two-cell analytic and
independent polynomial checks pass; this remains separate from ball jobs until
general contact, job integration and impact benchmarks are completed. See the
current assembly section in `AGENT_TODO.md` for evidence and outstanding work.

`field-lab.mixed-solver` now provides an experimental native constrained step for
fixed supports and axis-aligned frictionless planes. Independent nonlinear solves
agree with it, and 40 ms drop runs complete at three timesteps with certified
positive Jacobians and checked momentum balance. The runs expose substantial
backward-Euler damping; this is not a qualified impact solver or a new UI scene.
Research, numerical evidence and remaining integration work are recorded in
`RESEARCH_AND_DESIGN.md` and `AGENT_TODO.md`.
