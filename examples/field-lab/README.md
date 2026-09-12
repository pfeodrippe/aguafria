# Pitoco

A native Houdini-inspired experiment workbench written with **AguaFria Zig**.
Flecs owns the procedural scene and authoritative body state. Vulkan renders
the actual deformable triangle surfaces, or analytical rigid spheres, with
material seams, studio lighting, and ground shadows. Dear ImGui provides the inspector, graph, and timeline.

Two experiments are available: **Single ball** and **Three balls**. The latter
launches two spheres toward a third. **Continuum FEM is the default** for new development sessions. It uses a
finite-deformation Stable Neo-Hookean solid with adaptive substeps and ground/
convex-body contact. The solver selector also retains XPBD and rigid modes.
The default inspector-driven jobs use 43 nodes and 80 tetrahedra per body.
Clojure-authored refined jobs also bake and display mesh-sized single-body and
coupled three-body simulations. Verified runs include 1,209 nodes / 5,120 tets
for one ball and 3,627 nodes / 15,360 tets total for three interacting balls.
Contact convergence, continuous collision detection and calibrated materials
remain active work.

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

Use this path for development. `:build` below remains the standalone release path,
which deliberately uses source-only loading and disables development dispatch.
A separate `:nrepl` does not control an already-running standalone executable.

Solver edits affect the next **Bake**; existing cached frames remain the results
of the earlier solve. Presentation-function edits can affect the next frame.
Changing a long-running function's body affects its next invocation, and breaking
state layouts require migration or restart under AguaFria's normal rules. The
C++ panel adapter and GLSL shaders remain separately compiled artifacts; this
entry point does not claim automatic live reload for those files.

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
(az/defn single-ball
  :- contacts/Sample
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
agreement covers 62,049 particle records within 5e-12. The UI bridge still uses
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

The compiled suite passes 47 tests / 391 assertions; the independent pure Clojure
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
