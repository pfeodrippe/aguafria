# Field Lab: procedural engineering in AguaFria Zig

The first milestone is a native, inspectable sphere-impact experiment. The longer
term product is a Houdini-inspired procedural workbench for mechanical, electrical,
thermal, and coupled-field research. Flecs is the scene and data backbone; numerical
methods remain replaceable AguaFria Zig modules.

## Scope and architecture

The executable uses AguaFria declarations compiled with the repository's pinned
Zig 0.16.0 compiler. Physics, scene storage, history, panel layout/actions, native file exports, the
plugin/command host, and the application loop are authored in Clojure forms that
emit Zig. There is no JVM in the executable. A generic C++ boundary exposes
Dear ImGui primitives; external IPC/Eigen and Tight Inclusion operations retain
C++ adapters. GLSL shaders use the shared Vulkan renderer. GLFW uses `GLFW_NO_API`.

The first graph is `sphere_source -> mechanical_solver -> telemetry_output`.

- Flecs owns named source, solver, output, and ball entities. `DependsOn` pairs
  encode pipeline dependencies and `ChildOf` places the ball under the solver.
- `BallConfig` on the source and `BallState` on the ball are authoritative ECS
  components. `BallMaterial` on the solver stores model/E/ν; deformable bodies
  additionally own packed `DeformableBody` state.
  Every baked tick reads those components and publishes the solver result.
- The solver accepts values and returns a value; it has no ECS or rendering
  dependency. A future worker can operate on an immutable input snapshot and
  publish a completed result at an ECS synchronization boundary.
- A bounded contiguous cache holds up to 14,401 states (60 seconds at 240 Hz).
  It lives outside ECS tables to avoid repeated structural mutations. Scrubbing
  publishes a cached state into Flecs. Playback only reads the cache. A new bake
  resets initial conditions and replaces the cache under a new revision.
- `ecs_progress` advances the application world. This first fixed graph is
  explicitly orchestrated; it is not yet a generic topological graph scheduler.
- Node selection and parameter edits work. Rewiring, adding arbitrary nodes,
  persistent project files, and general solver registration are future work.

Flecs should coordinate revisions, dependencies, ownership, and completed outputs.
Do not create one ECS entity per FEM degree of freedom: use packed solver buffers
owned by a mesh/field component and expose mesh-level entities for selection.

## Papers and technical sources read

These are primary sources, with implementation decisions separated from summaries.

### Rigid-body dynamics and collision

David Baraff, *Rigid Body Simulation I—Unconstrained Rigid Body Dynamics* and
*II—Nonpenetration Constraints*, SIGGRAPH 1997 course notes.
[Part I](https://www.cs.cmu.edu/~baraff/sigcourse/notesd1.pdf),
[Part II](https://www.cs.cmu.edu/~baraff/sigcourse/notesd2.pdf).

The notes distinguish smooth motion from instantaneous collision impulses and
resting contact. Part II, section 8, derives impulses from relative contact
velocity, inverse masses, and rotational inertia. We specialize that construction
to one solid sphere and an immovable horizontal plane. The impact time is found
analytically for constant gravity; the remaining part of the tick is then advanced
with the new velocity. Tangential impulses include the contact lever arm so
friction creates physical spin.

### Compliant and deformable mechanics

Macklin, Müller, and Chentanez, *XPBD: Position-Based Simulation of Compliant
Constrained Dynamics*, MiG 2016, DOI 10.1145/2994258.2994272.
[Paper](https://matthias-research.github.io/pages/publications/XPBD.pdf).

The formulation carries an accumulated constraint multiplier and scales compliance
by the square of the time step. Its update gives a connection to elastic energy
and force estimates absent from ad-hoc PBD stiffness. The deformable extension implements this approach; finite iteration counts still
require convergence checks. The separate rigid solver does not claim that its
restitution coefficient is a constitutive material law.

Macklin et al., *Small Steps in Physics Simulation*, SCA 2019.
[Paper](https://matthias-research.github.io/pages/publications/smallsteps.pdf).

The authors compare many small XPBD steps with a larger step using many solver
iterations, finding advantages in constraint error and numerical damping in their
examples. Our decision is to keep rendering and simulation clocks separate and to
record the fixed numerical step as experiment data. Rigid mode uses exact ballistic segments. The retained XPBD mode uses four XPBD
substeps per tick; neither constitutes reproduction of the paper's benchmarks.

### FEM and engineering fields

Long Chen, *Introduction to Finite Element Methods*, iFEM documentation.
[Derivation and implementation](https://webapps.math.uci.edu/~chenlong/ifemdoc/fem/femdoc.html).

The Poisson example develops the weak formulation, a piecewise-linear basis,
element-wise assembly, and boundary conditions. This provides a concrete next
milestone: a scalar elliptic FEM solver on triangles, then tetrahedra. An
implementation should expose the mesh, boundary sets, assembled operator, source
term, and residual instead of presenting a colored surface as sufficient evidence
of correctness. The same operator structure can support thermal conduction and
electrostatics with their appropriate coefficients and units.

Jonathan Richard Shewchuk, *An Introduction to the Conjugate Gradient Method
Without the Agonizing Pain*, 1994.
[Technical report](https://www.cs.cmu.edu/~quake-papers/painless-conjugate-gradient.pdf).

The quadratic-minimization derivation motivates conjugate gradients for symmetric
positive-definite systems and explains conditioning and preconditioning. Planned
FEM nodes must report residuals and stop reasons. CG is not a universal solver:
indefinite systems, unconstrained nullspaces, or nonsymmetric coupled problems
need different treatment. Start with constrained scalar Poisson and Jacobi
preconditioning before adding more elaborate methods.

### Rendering

Pharr, Jakob, and Humphreys, *Physically Based Rendering*, fourth edition,
[Spheres](https://www.pbr-book.org/4ed/Shapes/Spheres) and
[Microfacet theory](https://www.pbr-book.org/4ed/Reflection_Models/Roughness_Using_Microfacet_Theory).

Analytic ray/sphere intersection produces a smooth silhouette independent of a
triangle tessellation. The shader uses a GGX-style specular lobe, Fresnel,
roughness, two studio lights, sampled sphere occlusion, and a single floor
reflection. It is an interactive approximation with tone mapping, not a converged
path tracer. Quaternion-rotated seams make the simulated orientation visible.

## Ball numerical contract

Coordinates and quantities use SI: metres, seconds, kilograms, radians, joules,
and newton-seconds. Y is up; the floor is `y = 0`. The source's drop gap is the
height of the bottom of the sphere, so initial center height is `gap + radius`.
The ball is an ideal homogeneous rigid solid sphere, `I = 2 m r² / 5`.

During free flight:

```
p(t+h) = p(t) + h v(t) + (h²/2) g
v(t+h) = v(t) + h g
```

The collision event solves `center_y(t) - radius = 0`. A cancellation-resistant
quadratic root is used for downward flight; zero gravity has its own linear case.
At impact, `v_normal_after = -e * v_normal_before`. The normal impulse is
`m * (v_normal_after - v_normal_before)`.

Tangential contact velocity is `u = v + omega × arm`, with `arm = (0,-r,0)`.
The sticking impulse magnitude is `|u_t| / (1/m + r²/I)`, capped by `mu * Jn`.
Apply its linear and angular increments in sequence. For a sphere that starts
sliding without spin and reaches pure rolling, `v_final = 5/7 * v_initial`.

At rest, the support impulse over a step is `m*g*h`; contact friction uses that
budget. Rolling resistance is an explicitly phenomenological speed decrement
`rolling*g*h`, clamped to avoid reversing motion. Vertical spin has exponential
damping while supported. Rebounds below 0.03 m/s enter resting contact; this
intentional energy loss prevents an infinite sequence of microscopic impacts.
A 16-event cap per step falls back to support for extreme inputs.

Orientation advances by multiplying a unit quaternion exponential built from the
angular velocity, followed by normalization. Rendering reads this orientation;
it does not invent spin, squash, or deformation. Mechanical energy includes
translation, rotation, and gravitational potential measured from supported center
height. Impulse telemetry is per tick, not peak contact force: instantaneous rigid
impulses cannot predict stress or force-time curves.

No aerodynamic drag, Magnus effect, finite contact patch, compliant deformation,
ball-ball contact, arbitrary meshes, or material calibration is included. Those
are additional models with their own validation requirements.

## Research roadmap and acceptance gates

| Stage | Working capability | Validation gate |
| --- | --- | --- |
| 1, this example | Rigid sphere, Flecs scene, timeline, measurements | Closed-form free fall, rebound, energy, rolling, high-speed CCD |
| 2 | Project persistence, editable DAG, schema-checked ports, revision cache | Cycle rejection, invalidation, save/load and deterministic replay |
| 3 | Poisson FEM, triangular mesh, Dirichlet/Neumann boundaries | Manufactured solution; residual plus mesh-refinement error |
| 4 | 3D linear elasticity on tetrahedra | Rigid-mode/nullspace handling, patch test, cantilever comparison |
| 5 | Electrostatics and steady thermal conduction | Parallel-plate capacitance; conservation of flux; mesh convergence |
| 6 | XPBD / nonlinear elasticity | Material-unit calibration; time-step and iteration studies |
| 7 | Coupled fields and external solvers | Explicit coupling order, unit checks, conservation and convergence |

Mechanical extensions should expose displacement, strain, and stress with an
explicit constitutive model. Electrical extensions should begin with
`-div(epsilon grad(phi)) = rho`, then derive `E = -grad(phi)` and energy.
Full-wave electromagnetics needs appropriate edge elements or a separate FDTD
solver and its own stability/dispersion studies; it is not covered by scalar
Poisson FEM. Thermal conduction uses conductivity and heat-source coefficients.
All fields should carry association (vertex/cell), units, and mesh revision.

A future solve request should contain configuration, mesh hash, boundary tags,
solver/version identifiers, tolerances, and input revision. A completed result
should contain fields, convergence history, timing, and diagnostics. The UI should
publish a result only if its input revision still matches; this permits cancellable
worker jobs without moving numerical loops into ECS callbacks.

## Validation and operational limits

The test suite evaluates the compiled AguaFria solver, not a second implementation.
It checks analytic flight and collision quantities, elastic energy conservation,
monotone dissipative energy, nonpenetration, rolling velocity, quaternion norm,
step partition agreement, zero gravity, and a 1000 m/s downward collision.
Scene integration checks should additionally verify reset, cache seek/replay,
Flecs storage and dependency pairs, and cache capacity.

The initial native host inherits the shared example's macOS GLFW/Vulkan build
recipe. The fixed 1280×820 window avoids unsupported shared swapchain resizing.
Offline baking advances fixed numerical steps independently of wall time.
Playback clamps long display intervals for comfortable viewing; it only selects
cached ticks and cannot alter the solved trajectory. The run stops at cache
capacity. Headless Clojure jobs execute the same kernel without a graphics loop.

Keep the code readable: blank lines between declarations, descriptive names, and
`az/set-many!` for sequential assignment groups. This is an ordinary Clojure macro
expanding to ordered `set!` forms; later values and targets observe earlier writes.

## Three-ball extension

The second implemented experiment uses the same source/solver/output graph, three
Flecs `BallState` components, and a packed three-body sample in the cache. Inactive
bodies carry `EcsDisabled` in the single-ball experiment. Both sources are authored
in `experiments.clj` as ordinary Clojure-shaped AguaFria functions; no independent
expression language is introduced.

In rigid mode, each three-body tick contains eight substeps. Each substep advances all spheres
against the floor with the existing ballistic solver, then performs four ordered
pair-contact passes. Equal and opposite normal and Coulomb tangential impulses
update linear and angular velocity. The tangential effective inverse mass sums
both bodies' translational and rotational contributions. Pair overlap correction
is split equally; a final floor projection keeps centers above their radii.

Sphere-sphere contact is discrete at the substep boundary. This is adequate for
the UI's small scene and bounded input ranges, but high-speed pair CCD, arbitrary
mass ratios, dense stacks, and simultaneous-contact convergence need further
work. Position correction can introduce small energy errors; the conservative
pair benchmark isolates collisions from gravity and penetration corrections are
checked separately. The single-ball analytical solver remains unchanged.

The shared Vulkan triangle renderer now offers an optional 128-byte fragment
push-constant payload. Field Lab uses three sphere center/radius vectors, three
quaternions, camera parameters, and viewport/body-count data. The existing vertex
ABI and instanced-camera path remain unchanged. The fragment shader intersects
all active rigid spheres for visibility, shadows, and floor reflections. Soft
mode streams the measured triangle surface and its projection from the key light
onto the ground; it does not use spherical shadow proxies or reflections.

CSV exports include every active body and tick; JSON records the applied source
configuration. Pair contacts increment both bodies' event counters. The impulse
column remains the accumulated **floor** normal impulse during the tick; it does
not include sphere-sphere impulses. Total system energy is summed in the UI,
while position, speed, and floor impulse refer to ball 01.

## XPBD deformable-body extension (retained comparison mode)

This mode advances 43 mass-lumped particles and 80 positively oriented
center-connected tetrahedra per sphere. A subdivided icosahedron supplies 42
surface nodes and 80 surface triangles. The generated mesh has 162 unique radial
and surface edges. Flecs `DeformableBody` components own positions and velocities;
packed copies feed the numerical kernel, the 60-second replay cache, and the
streamed Vulkan surface. The solver node is now named `mechanical_solver`.

This is an **XPBD elastic network**, following the compliant constraint formulation
of [Macklin et al., 2016](https://matthias-research.github.io/pages/publications/XPBD.pdf)
and the substep motivation of
[Small Steps, 2019](https://matthias-research.github.io/pages/publications/smallsteps.pdf).
Tetrahedra alone do not make it a continuum FEM constitutive model. A convergent
FEM displacement/strain formulation is implemented separately below; material
calibration remains a milestone. The implementation uses four substeps and six constraint sweeps per
240 Hz tick. Multipliers persist across sweeps and reset each substep.

For an edge, `C = length - rest_length`, with elastic energy `0.5 k C²` and
`k = E V_unit r / (L_total L_edge)`. Here E is an effective network stiffness in
Pa, not a measured Young's modulus. For each tetrahedron, `C = V/V_rest - 1`,
energy is `0.5 (20 E) V_rest C²`; the factor 20 supplies a relatively stiff bulk
response. XPBD uses compliance divided by the squared substep duration, and each
particle receives an inverse-mass-weighted constraint-gradient correction. Masses
are assembled from one quarter of each incident tetrahedron's rest mass.

Prediction integrates gravity. Ground projection enforces `y >= 0` for all nodes;
velocity reconstruction includes the projection. A Coulomb-limited ground
velocity correction dissipates tangential motion. Pair contact tests vertices
against the other body's convex surface and applies opposite mass-weighted
reactions to the vertex and barycentrically weighted face nodes. Internal velocity
damping of 2/s acts relative to each body's mass-weighted mean, preserving linear
momentum in free motion. Soft rebound comes from elasticity, not the rigid
restitution coefficient. Rotational motion emerges from particle velocities.

Reported soft mechanical energy is particle kinetic energy plus the two elastic
energies above and gravitational potential `m g y_COM`, relative to the ground.
The trajectory file contains center-of-mass summaries and energy; `particles.csv`
contains the actual state. Angular-velocity, quaternion, and impulse summary
columns are NaN in soft mode because no single rigid orientation or reported
aggregate impulse is defined. The JSON names the solver and effective stiffness.
The topology is deterministically generated in `soft_mesh.clj`.

### Verification and scope

Compiled native tests cover mesh orientation/mass, drop compression and recovery,
volume retention, ground nonpenetration, a bounded energy check, three-body elastic
contact with total momentum/energy checks, and replay of every particle through
Flecs. The live default single-ball run exported 2,689 ticks and 115,627 particle
records. Its largest measured height compression was about 21.5% at 0.875 s;
minimum particle height was exactly zero. These are implementation observations,
not a comparison to a physical specimen.

The coarse polyhedral surface is visible and honestly rendered. It is unsuitable
for stress certification or a material prediction without refinement studies and
calibration. Pair contact assumes convexity and lacks edge-edge contact, self
collision, continuous collision detection, and inversion handling. Large strains,
high velocities, and substantially different material/time-step combinations need
additional validation. Soft ground shadows project the deformed triangles from a point key light;
analytic sphere reflections are disabled for soft geometry. This is hard-shadow
projection on the plane, not a full area-light/global-illumination solution.

The user clarified that this is primarily a Houdini-style offline workload.
The application now bakes numerical states first, then plays or scrubs that
immutable cache. Bake chunks are bounded only to let the UI service events;
there is no real-time solver budget, catch-up clamp, or display-driven timestep.
The bake target is captured when starting the job. Playback rate affects viewing
only. Stopping a bake retains its partial cache; rebaking starts a new revision.
The viewport distinguishes baking from cached playback and displays actual
minimum particle clearance from the ground.

The apparent floating report must be diagnosed rather than dismissed as pausing.
The inspected default run had minimum particle heights 3.5 m at t=0, 2.2712 m
at 0.5 s, and zero at 0.85 s; average vertical velocity at 0.5 s was -4.905 m/s.
At 10 s minimum clearance remained zero and vertical motion was small. These
measurements establish falling/contact in that run, but do not by themselves
establish the cause of every perceived viewport discrepancy. Mesh-aware shadow projection has since replaced the spherical proxy. Camera and
contact appearance are checked against the particle clearance shown in the UI.

### Offline verification and viewport correction

The final numerical suite passed 16 tests and 65 assertions. In particular,
baking 230 ticks in one batch versus batches of 16 produces exactly the same
particle state, and seeking through the cache leaves its size and content intact.
The headless EDN job was exercised with three deformable bodies for one second:
241 frames, three bodies and 129 particle states per frame, including exported
mesh topology. It uses the same native kernel without GLFW initialization.

The fixed camera could previously be passed by bodies moving away after collision.
The camera now tracks their mean horizontal position and expands its distance to
contain all active body bounds. CPU surface projection and the background ray
camera share this convention. This is a presentation change; baked particle
positions and velocities are unchanged.

The completed three-body UI export contains 185,889 finite particle records
(1,441 ticks × 3 bodies × 43 nodes) with minimum height zero. Comparing its first
second to the independent headless run covered 31,089 records; the largest
position/velocity difference was 4.88e-11, consistent with CSV decimal rounding.
Stopping a bake was also checked in the UI: it retained 65 states, and a new
bake subsequently completed and resumed cached playback.

## Continuum FEM foundation: static tetrahedral elasticity

`fem.clj` implements a separate small-strain 3D continuum solver. The ball
viewport has a separate nonlinear FEM mode. Linear elasticity cannot replace a
tumbling ball's finite rotations and large contact strains.

The inverse reference edge matrix gives the four constant basis gradients
`g_i = ∇N_i`. We use `∇u = Σ u_i ⊗ g_i`, `ε = (∇u + ∇uᵀ)/2`,
`σ = 2με + λ tr(ε)I`, `μ = E/[2(1+ν)]`, and
`λ = Eν/[(1+ν)(1−2ν)]`. Weak equilibrium assembles
`(K u)_i = Σ_t V_t σ_t g_i`, with physical force convention `−div σ = f`.
NGSolve's [3D Solid Mechanics tutorial](https://ngsolve.org/ngsolve/docs/i-tutorials/wta/elasticity3D.html)
provides the constitutive law, weak form and boundary conditions used as the
formulation reference. Stress is a symmetric tensor in Pa; strain energy is
`uᵀKu/2` in joules. Edge springs do not substitute for the constitutive law.

The native solver traverses elements to apply stiffness without storing a sparse
matrix. Prescribed displacements stay fixed while preconditioned conjugate
gradients solve the free degrees of freedom. Jacobi entries are
`Σ_t V_t [μ |g_i|² + (λ+μ) g_i,a²]`. Convergence requires the freshly computed
free force residual to satisfy `max(atol, rtol |r_initial|)`, including forces
induced by nonzero prescribed displacements. Nonpositive curvature, nonfinite
residuals and exhausted iterations cannot report convergence. Support reactions
retain the constrained rows of `Ku−f`.
[Shewchuk's 1994 report, sections 11–12 and appendix B3](https://www.cs.cmu.edu/~quake-papers/painless-conjugate-gradient.pdf)
is the iterative-method reference. Restarting every fifty iterations initially
caused poor beam convergence. The corrected solver preserves conjugate directions
and checks/restarts only when candidate convergence needs residual correction.

Jobs are ordinary Clojure maps or EDN with points, tetrahedra, material, forces
and prescribed displacements. Helpers generate conforming boxes and integrate
constant body forces and surface tractions. Each job owns and frees native arrays.
Validation rejects invalid materials, degenerate elements, disconnected/unused
points, conflicting constraints and supports leaving rigid modes unconstrained.
Meshes must still be valid nonoverlapping conforming tetrahedralizations; these
checks do not provide full geometric mesh-quality certification.

Results include the complete job, loaded native declaration fingerprints,
convergence report, displacement, residual/reaction and cell stress. A solver
version change during a job rejects the result for rerunning. Converged VTK XML
exports retain reference coordinates plus displacement, stress and von Mises
stress. A displacement scale of one displays physical deformation; larger scales
are visual magnification.

### FEM verification evidence

- An affine patch with an interior free node reproduces displacement, uniaxial
  and shear stress, strain energy and balanced reactions. Reversing tetrahedron
  winding preserves results. Infinitesimal rigid motion produces negligible stress.
- The manufactured solution `u = (0.001 x², 0, 0)` on a unit cube uses analytical
  constant body force and exact boundary displacements. Four-point tetrahedral
  quadrature exactly integrates squared gradient error. At subdivisions 2, 4
  and 8, L² gradient errors are `2.88675135e-4`, `1.44337567e-4` and
  `7.21687836e-5`: first-order convergence. Relative residuals are below `1e-9`.
- A 1 × 0.1 × 0.1 m clamped beam with E=2 GPa, ν=0.3 and 10 N end load was
  refined from 48 to 24,576 tetrahedra. Mean tip deflections are 0.0388222,
  0.0937985, 0.1535845 and 0.1854931 mm. The slender Euler–Bernoulli reference
  `FL³/(3EI)` is 0.2 mm; the finest result is 7.25% below it. This establishes a
  refinement trend, not experimental accuracy. Coarse P1 tetrahedra are too stiff
  in bending; the beam reference itself is a reduced dimensional model.

This kernel currently assumes homogeneous isotropic static small-strain linear
elasticity. Finite deformation, dynamics, contact, plasticity, mixed incompressible
formulations and calibration remain pending. Displacement-only tetrahedra can
lock near incompressibility. Arbitrary static FEM jobs remain headless; the
separate finite-deformation solver is integrated with the Flecs ball pipeline.

## Finite-deformation continuum FEM for the balls

`hyperelastic.clj` implements the regularized Stable Neo-Hookean model from
[Smith, de Goes and Kim (2018), §§3.3–3.4 and 4.2](https://www.tkim.graphics/NEO/StableNeoHookean2018.pdf).
The PDF's equation pages were read and visually checked. With `I_C = F:F`,
`J = det F`, the implementation uses:

```
mu     = (4/3) mu_Lame
lambda = lambda_Lame + (5/6) mu_Lame
alpha  = 1 + 3 mu/(4 lambda)
W = mu/2 (I_C−3)
    + lambda/2 [(J−alpha)^2 − (1−alpha)^2]
    − mu/2 ln[(I_C+1)/4]
P = mu (1−1/(I_C+1)) F + lambda (J−alpha) cofactor(F)
```

Subtracting the rest constant makes `W(I)=0`. Reparameterization makes the
small-strain tangent agree with the supplied E and ν. Cofactors are evaluated
with column cross-products; the exact directional tangent differentiates this
expression. Material evaluation stays finite at inverted/collapsed elements, but
that alone does not make a physical simulation of an inverted element valid.

`nonlinear_fem.clj` assembles forces `f_i = −Σ V P ∇N_i` on arbitrary reference
tetrahedra. Lumped nodal mass is one quarter of each incident element's mass.
It integrates velocity Verlet with a conservative mass-scaled tangent norm bound,
maximum-step control, and rejection when the new configuration exceeds the bound
or approaches an invalid element. These bounds control stability, not integration
error; timestep refinement remains necessary. Elements with J≤0.05 stop the job
if smaller steps cannot resolve the event. Roundoff-sized time remainders are
handled separately from failed physical substeps.

Ground contact projects penetrating nodes onto y=0, removes incoming normal
velocity and applies Coulomb-limited tangential impulses. Rebound comes from
stored hyperelastic strain energy. There is no rigid restitution impulse or
artificial shape-restoring spring in the elastic force assembly. This discrete
nodal contact dissipates energy and is not a converged continuum contact solution.

`ball_fem.clj` connects the material/dynamics to the existing 43-node Flecs body
and cache representation. Three bodies share a substep and convex vertex/face
constraints. Pair position corrections have equal mass-weighted reactions;
velocities receive the corresponding corrections before the second force kick.
This is a continuum FEM material with approximate contact, not the earlier XPBD
edge/volume material renamed. The old XPBD and rigid models remain selectable.
Temporary native work buffers are reconstructed per output tick; Flecs owns the
persistent particle states, and `BallMaterial` owns model/E/ν on the solver node.
This bridge still needs persistent packed job buffers, general mesh/cache sizes,
edge-edge/continuous contact, pair friction, self-contact and contact convergence.

### Numerical and application evidence

- Material tests compare all nine energy derivatives to PK1 and all nine tangent
  directions to finite differences. Rotation objectivity, zero rest stress,
  agreement with linear elasticity, and finite degeneracy evaluation pass.
- Assembled internal force and torque sum to numerical zero. A particle force
  matches a perturbation of the assembled elastic energy. An unconstrained solid
  falls ballistically without developing strain.
- Halving the maximum step in a free elastic oscillation gives the expected
  approximately fourfold reduction in position differences for velocity Verlet;
  the tested energy drift is below 1%.
- Uniform 1-to-8 refinement produces 80/640/5,120 tetrahedra while preserving
  total reference volume. This checks topology/domain preservation; it does not
  yet establish spatial convergence for impact.
- The arbitrary-mesh default solid-ball drop baked 241 output frames using
  14,645 substeps. Minimum y was zero, minimum height 0.0731934 m versus 0.1 m
  initially, minimum J≈0.71477, and peak elastic energy≈2.00074 J. Initial energy
  was 2.71433 J; the maximum stayed at that value within numerical roundoff.
- Native one/three-ball bridge tests verify compression, energy bounds, positive
  element volume, reversal of the outer bodies and total momentum conservation.
- A two-second three-body job ran through both the live UI and independent
  headless process. All 62,049 particle records are finite; position/velocity
  differences are at most 4.9998e-12 (CSV rounding). Minimum y is zero, and the
  smallest measured body height is 0.668545 m for initial diameter 0.9 m.
- The live UI was updated in the existing PID through AguaFria reload. The C++
  adapter was rebuilt manually with compatible entry points. An atomic mailbox
  now queues Clojure-authored bake/seek/export requests for the first UI thread.
  Seeking pauses playback; exported JSON explicitly identifies the FEM solver,
  Young's modulus and ν. Fresh visual verification is still pending: macOS
  capture returned an older window frame despite progressing native telemetry.

These are numerical implementation checks and application consistency checks.
They are not experimental validation of a rubber/foam specimen or proof that the
coarse contact and rendering meet the final requested fidelity. Finish those ball
milestones before moving on to clothing, then rain/audio and circuits.

### Plane-impact mesh and timestep study

`impact_study.clj` adds a reproducible contact study to the arbitrary-mesh solver.
It drops the same 0.05 m radius polyhedron from 0.001 m clearance at 1 m/s, with
E=100 kPa, ν=0.4 and density 1100 kg/m³. Gravity and friction are disabled.
The polyhedral mass is 0.503072929 kg and initial kinetic energy 0.251536464 J.
Uniform refinement preserves the domain; it does not turn the initial faceted
shape into a smoother sphere or calibrate its material.

Every mesh is tested at multiple timestep caps. The report records center
trajectories, sampled body height and energy, minimum accepted J, ground impulse,
and contact-time brackets. With gravity disabled, integrated ground impulse must
equal the change in vertical momentum. A positive center velocity alone does not
establish separation; the final sample must have positive clearance and its last
measurement interval must contain no contact impulse.

The initial 25 ms study ran 80/640/5,120 tetrahedra at 10/5/2.5 µs caps. All nine
cases completed without rejected substeps or negative ground clearance. At the
smallest cap, successive mesh changes in minimum body height were 1.378 mm and
0.841 mm. At fixed mesh, halving the cap from 5 to 2.5 µs changed that height by
less than 9 nm. This establishes that spatial/contact discretization dominates
the timestep sensitivity of this particular measured quantity. Contact was still
active at 25 ms, so its upward velocities were not reported as completed rebounds.

The default study extends to 60 ms. Saved cases include complete input meshes and
initial states, applied integration controls and loaded native fingerprints.
Mixed-version comparisons are rejected. Reports preserve completed cases if a
later case fails and carry an explicit `:complete?` flag. Peak values still depend
on the measurement cadence; contact times are intervals, not exact event times.
This is an impact sensitivity study, not experimental validation or a converged
three-body contact result.

The 60 ms runs at a 5 µs cap give:

| Nodes | Tetrahedra | Minimum height (mm) | Rebound speed (m/s) | Total energy loss |
| ---: | ---: | ---: | ---: | ---: |
| 43 | 80 | 86.98988 | 0.943003 | 4.64519% |
| 205 | 640 | 85.61230 | 0.978528 | 1.48995% |
| 1,209 | 5,120 | 84.77153 | 0.992883 | 0.38675% |

The last contact-impulse interval ends by 36.25 ms in all three runs, and each
body has positive clearance at 60 ms. Ground
clearance is nonnegative throughout the sampled trajectories; the smallest
accepted element J on the finest mesh is approximately 0.69138. Integrated
contact impulse agrees with vertical momentum change within 1.5e-15 N·s in
these three runs. The material has no damping; measured energy loss includes
numerical contact dissipation. Residual elastic vibration also contributes to
the difference between center rebound speed and incident speed.

The last two meshes still differ by about 0.841 mm in minimum height and
0.01435 m/s in rebound speed. These results support increasing resolution; they
do not establish a converged local contact stress or acceptable error for an
engineering application. At the time of this impact study, the ball viewport
used the 43-node bridge. The following section records the subsequent refined
single-body cache integration; refined three-body contact is still pending.

All six 60 ms cases at 5 and 2.5 µs caps completed with no rejected substeps.
On the finest mesh, timestep halving changes final rebound speed by
5.87e-9 m/s and the maximum center-trajectory difference is 1.27e-9 m.
The corresponding last mesh refinement changes rebound speed by 0.01435 m/s
and the maximum center-trajectory difference is 0.491 mm. These are differences
between numerical solutions, not error estimates against an exact solution.
The complete compiled suite, including impact diagnostics, passes 34 tests /
170 assertions. Raw reports are `exports/fem-impact-study.edn` (25 ms) and
`exports/fem-impact-rebound.edn` (60 ms); rerun with `:seconds 0.06` and
`:maximum-steps [0.000005 0.0000025]` for the latter experiment.

### Refined continuum cache in the workbench

`mesh_cache.clj` now owns arbitrary-sized reference vertices, tetrahedra, oriented
boundary faces, and independent per-frame positions, velocities and observables.
`refined_job.clj` builds this storage privately on a REPL worker. The numerical
kernel writes complete frames directly into native storage, avoiding one foreign
call per copied particle. The output entity receives a `MeshCache` component only
after the whole job completes. A revision-checked atomic handoff transfers lifetime
management to the UI thread; stale results are destroyed without replacing the
current cache. Reset and shutdown release published buffers.

Timeline seeks publish cached body summaries through Flecs and render the stored
boundary positions. No numerical stepping occurs during this playback. The mesh
renderer uses outward-oriented boundary triangles, accumulated vertex normals and
shadows projected from those same deformed triangles. Source controls for a
refined cache are currently read-only; further refined bakes are authored in
Clojure. Rigid, XPBD and coarse three-body modes remain available.

A one-second single-body job used radius 0.45 m, mass 20 kg, 0.5 m drop gap,
E=10 kPa, ν=0.4, gravity 9.81 m/s² and ground friction 0.35. At refinement 2 it
contains 1,209 nodes, 5,120 tetrahedra and 1,280 boundary faces. All 291,369 exported
particle records across 241 frames are finite. Minimum height is 0.620164783 m
at tick 100 (0.416667 s), giving 31.1% compression; minimum ground clearance is
zero. A fresh native-window capture, `exports/refined-peak.png`, shows these same
counts and measurements and a visibly compressed surface in contact with the floor.
This resolves the earlier lack of fresh FEM viewport evidence.

The compiled suite passes 36 tests / 188 assertions, including outward face
orientation, retained-frame independence, ballistic motion, Flecs ownership/reset,
and emitted vertex counts/unit normals. A live stale-publication check confirmed
that an incorrect source revision is rejected while the displayed cache remains
unchanged.

Live replay was checked after explicit UI acknowledgement at ticks 0, 180 and
100. Modification times confirmed a newly completed export before comparing
hashes: particle, trajectory, reference and connectivity files were byte-identical
to the original export. The independent compiled replay tests above also pass.

These refined results still use the original polyhedral reference domain and an
illustrative homogeneous material. Smoother sphere geometry, physical
calibration, generalized UI job controls and final rendering
quality remain pending. Save the returned Clojure job descriptor with CSV exports
to retain its full source state, maximum timestep and native fingerprints.

### Closest-feature contact geometry

Bærentzen and Aanæs, *Signed Distance Computation Using the Angle Weighted
Pseudonormal* (2005), determine the sign of the distance from the closest surface
feature on a closed, oriented manifold. Face interiors use their face normal;
edges combine their incident normals; vertices weight incident unit normals by
the corner angle. Ordinary area-weighted rendering normals do not provide the
same sign guarantee. [Primary paper](https://backend.orbit.dtu.dk/ws/portalfiles/portal/3977815/B_rentzen.pdf).

`contact_mesh.clj` implements that signed query with nearest-triangle barycentric
coordinates and a balanced bounding-volume hierarchy. It refits all bounds after
a batch update, or incident leaves and ancestors after an individual contact
correction. The broad phase only tests a bounding box; solid membership comes
from the signed nearest-feature query. This removes the convex half-space
assumption from the new query layer.

The Clojure builder rejects nonfinite vertices, degenerate triangles, open edges,
inconsistent winding, pinched non-manifold vertices and inward components before
allocating native storage. Unused interior FEM nodes are permitted. Initial
self-intersections and intersections introduced by deformation are not detected
by this builder; the signed query requires a valid, non-self-intersecting surface.

Native tests exercise triangle interior/edge/vertex regions, signed cube queries,
incremental refits under translation, and a concave L-shaped prism. Seventy-five
spatial queries compare hierarchy results against exhaustive nearest-triangle
searches. The concave tests distinguish exterior notch points from interior arm
points. These are geometry tests; they do not validate a contact time integrator.
The four contact tests pass 108 assertions in the existing development host.
The complete separate-process suite passes 40 tests / 296 assertions with no
failures or errors; see `contact-full-suite-log.txt`.

The subsequent section records connection of this query layer to joint refined
dynamics and the live three-body cache. Edge-edge contact and continuous
collision detection remain open. In particular, this is not an implementation of IPC's variational
barrier/contact algorithm and has no intersection-free trajectory guarantee.
See [Incremental Potential Contact](https://ipc-sim.github.io/) for that distinct
formulation and its requirements.

### Coupled refined FEM and live three-body caches

The contact-query layer is now connected to `coupled_fem.clj`. All bodies share
one velocity-Verlet substep. A rejected candidate restores every body's position
and velocity, then refits the contact hierarchies before retrying. Accepted
states satisfy positive element-Jacobian and tangent-bound checks, sampled
vertex penetration ≤1 nm, and closing-speed residual ≤1e-7 m/s. The tolerance is
an algorithmic stopping criterion, not an estimate of physical accuracy.

For a source node of mass m and triangle weights wᵢ with node masses mᵢ, the
inverse effective mass is 1/m + Σwᵢ²/mᵢ. Normal impulses remove closing relative
velocity, and opposite barycentric reactions act on the other body. The
Coulomb tangential impulse is limited by both μ times the normal impulse and
the impulse needed to stop relative slip. Bridson, Fedkiw and Anderson (2002),
§7.1 and §7.3, describe the interpolation and friction construction for cloth;
this implementation uses unequal FEM lumped masses. Their full method also
includes geometric collision handling absent here.
[Primary paper](https://www.cs.ubc.ca/~rbridson/docs/cloth2002.pdf).

Position repair is a separate mass-weighted projection. It is not a variational
IPC solve. Energy and angular-momentum effects of position repair, unresolved
edge-edge crossings, contact-order dependence and mesh/time sensitivity require
further study. Passing discrete residual checks cannot establish an
intersection-free trajectory or validated engineering contact stresses.

Four native-backed coupled tests pass 38 assertions. They check freely moving
bodies against independent integrations, unequal-mass frictional impulses
(including the Coulomb limit and non-increasing kinetic energy), preservation of
an invalid initial state on failure, and a refined three-body impact through
separation. The impact uses three radius-0.05 m solids with E=10 kPa, ν=0.4,
density 1,100 kg/m³, no gravity and zero friction. Initial outer velocities are
±1 m/s; the middle body starts at rest.

At 150 ms, with 205 nodes / 640 tetrahedra per body and a 25 µs step cap, outer
velocities are -0.967914864 and +0.967898764 m/s. Both neighboring boundary boxes
have positive x gaps, about 32.96 mm. Minimum accepted J is 0.63106; maximum
sampled vertex penetration is 1.05e-17 m. Maximum total linear-momentum error
across output samples is 7.23e-16 kg m/s, and total mechanical energy falls by
1.159%. These are isolated numerical measurements, not a convergence result.
The full source, loaded solver fingerprints, 1 ms history and step reports are
in `exports/coupled-three-refined.edn`.

`mesh_group.clj` owns synchronized per-body caches without changing the existing
single-cache memory layout. Flecs stores the group on the output entity. The
same revision-checked mailbox adopts a complete group or destroys a stale result.
The renderer uses each body's own boundary, and CSV reference/connectivity rows
carry body-local node indices with explicit body IDs.

A separate one-second floor experiment uses radius 0.45 m, mass 20 kg per body,
0.5 m drop gap, E=10 kPa, ν=0.4, gravity 9.81 m/s² and friction 0.35. The outer
bodies launch inward at 1.5 m/s. It completed 10,080 substeps with no rejections;
minimum accepted J is 0.642318 and maximum sampled pair penetration is
1.31e-16 m. The 241-frame group was published into the existing development
process. `exports/coupled-three-live.png` confirms three distinct deformed meshes,
615 total nodes / 1,920 total tets, tick 100, 28.3% maximum compression and zero
minimum floor clearance. Source state and reports are saved separately in
`exports/coupled-three-floor-job.edn`.

Fresh live exports contain 148,215 particle rows, 723 body-summary rows, 615
reference vertices and 1,920 tetrahedra, with the three body IDs represented in
both topology files. All particle positions/velocities and continuum summary
fields are finite; rigid-only orientation/impulse columns retain their documented
NaN markers. The exported minimum particle y coordinate is zero. Total vertical
momentum changes by -138.5217295552 kg m/s, consistent with gravity (-588.6 N s)
plus the integrated floor impulse (450.0782704447085 N s) within 9.16e-11 N s.

The files are preserved in `exports/coupled-live/`. After acknowledged paused
seeks to ticks 0, 180 and 100, file modification times confirmed another complete
export. Its particle, trajectory, reference and connectivity hashes exactly
matched the preserved originals; `replay-verification.json` records the hashes.

## Pitoco extension boundary — 2026-09-12

The optional extension host now has a versioned C ABI, copied command payloads,
a bounded command queue, owning-thread plugin lifecycle callbacks, and a local
file transport used by an independent Clojure library. The native plugin example
is authored in Clojure + AguaFria Zig and compiled with standalone dynamic-library
output. Native-only tests exercise its lifecycle and callback-issued host command;
the external Clojure test program verifies it does not load `aguafria.zig`.

The extension host is shared across development application generations, while
standalone statically includes it. A live plugin remained attached across an
application recompile and subsequently rewound the refined three-body cache.
Plugin attach/unload is explicit library lifecycle, not a reliance on AguaFria
hot reload inside standalone. The initial contract has playback/plugin commands;
custom solver/mesh/render/audio-node registration and durable job results remain
future work. See EXTENSIONS.md for exact ABI, transport, and lifetime constraints.

## Spherical reference meshes and measured impacts — 2026-09-12

Refining the original tetrahedra without moving their boundary preserved an
80-face polyhedron at every level. The new `pitoco.geometry/sphere` source
performs conforming 1-to-8 refinement, projects boundary nodes onto the unit
sphere, checks cell orientation, and finally scales/translates the reference
mesh. Boundary extraction, geometry measurements and mesh construction are pure
Clojure and do not load AguaFria. The native FEM solver receives these actual
reference nodes and cells; rendering uses their deformed boundary triangles.

Persson and Peraire, *Curved Mesh Generation and Mesh Refinement using Lagrangian
Solid Mechanics* (2009), discuss boundary-constrained refinement and the risk of
inverted elements after boundary movement. Their elasticity and continuation
method propagates mesh deformation through the interior. Section IV.B also
addresses linear mesh refinement. Our restricted spherical source uses boundary
projection and explicit rejection of invalid cells; it does **not** implement
the paper's elastic mesh repair or high-order curved elements. Linear element
shape quality must be checked separately from an orientation/Jacobian test.
[Primary paper](https://persson.berkeley.edu/pub/persson09curved.pdf).

For each tetrahedron, shape quality is measured with
q = 12(3V)^(2/3) / sum(edge length squared). The constructor rejects nonpositive
volume or minimum q ≤ 0.01. This is a source validity threshold, not a solver
accuracy estimate. Unit-sphere measurements are:

| Refinement | Nodes | Tetrahedra | Volume error | Minimum q | Continuum I/m about x |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 | 43 | 80 | 12.6547% | 0.77954 | 0.365792 |
| 1 | 205 | 640 | 3.3839% | 0.60835 | 0.390944 |
| 2 | 1,209 | 5,120 | 0.8606% | 0.34459 | 0.397703 |
| 3 | 8,177 | 40,960 | 0.2161% | 0.24696 | 0.399424 |

The analytic unit sphere has I/m = 0.4 m². Exact tetrahedral integration supplies
continuum inertia; the separate lumped-inertia diagnostic uses the solver's
nodal mass measure. These must not be conflated. Full measurements are saved in
`exports/spherical-geometry-study.edn`. Native cache jobs preserve a specified
mass with density M/Vh. Impact convergence studies instead hold physical density
fixed, allowing reference mass to converge with the geometry. Fixed-polyhedron
benchmarks retain `:geometry :polyhedron` and must not be silently compared as
though their domain were the newly projected sphere.

### Six spherical plane impacts

A radius-0.05 m solid, density 1,100 kg/m³, E=100 kPa and ν=0.4 starts 1 mm above
a frictionless plane with downward speed 1 m/s and no gravity. Each run covers
60 ms, sampled every 1 ms. All six separate before the end, with no rejected
steps, no sampled ground penetration and no measured total energy gain.

| Refinement | Mass (kg) | Final upward speed, 5 µs (m/s) | Final upward speed, 2.5 µs (m/s) | Energy loss, 2.5 µs |
| --- | ---: | ---: | ---: | ---: |
| 0 | 0.503073 | 0.943002771 | 0.943001784 | 4.6455% |
| 1 | 0.556469 | 0.967459522 | 0.967459530 | 2.0689% |
| 2 | 0.571002 | 0.985426587 | 0.985426876 | 0.7609% |

Momentum minus floor impulse differs by at most 1.56e-15 N s. At the finest
mesh, halving the step cap changes final speed by 2.90e-7 m/s. The last mesh
refinement changes it by 0.01797 m/s; contact and spatial error remain material.
Geometry convergence alone is not mechanical convergence. Source descriptions,
solver fingerprints and histories are in `exports/spherical-plane-impact.edn`.
These are illustrative solid parameters, not measured rubber or a hollow ball.

### Spherical three-body cache in Pitoco

The new live group has 1,209 nodes and 5,120 tetrahedra per body. Each has radius
0.45 m, mass 20 kg, reference volume approximately 0.378418507 m³, E=10 kPa and
ν=0.4. Gravity is 9.81 m/s², floor friction 0.35 and initial drop gap 0.5 m;
the outer bodies launch inward at 1.5 m/s. One simulated second took 595.7 s of
wall time and 10,080 shared substeps, with no rejections. Minimum accepted J was
0.669784 and maximum sampled pair penetration 6.94e-17 m.

The existing development window adopted the 241-frame group as revision 32.
Verified images show initial state, tick 100 (27.3% compression, zero minimum
floor clearance) and tick 240. At the last frame the bodies are rebounding;
positive clearance there is consistent with the simulated trajectory. An
occluded-window capture initially returned a stale tick-100 image despite the
status reporting tick 240. Foregrounding the existing window produced the
verified tick-240 capture. Renderer-owned, frame-tagged readback remains needed.

The streaming checker `tools/verify_mesh_export.py` reconstructed nodal masses
from reference tetrahedra and checked every one of 874,107 particle records.
Maximum mass-weighted center error was 4.00e-15 m and velocity error 8.44e-15 m/s.
Minimum particle y was zero. Energy fell from 604.170 J to 591.939 J (2.0244%).
Vertical momentum changed by -134.779205964 N s, balancing gravity (-588.6 N s)
and ground impulse (453.820794036 N s) within 5.70e-11 N s. Fresh exports after
paused seeks to ticks 0, 180 and 100 match all five original file hashes.
`exports/spherical-live/` preserves the files, verification and replay manifests.

The compiled suite passes 47 tests / 391 assertions, and independent scripting
geometry tests pass 5 tests / 49 assertions. Edge-edge contact, CCD, self-contact,
angular-momentum accounting, calibrated materials and final physically based
rendering remain open. Clothing and paper remain planned and have no rendered
implementation yet. The scripting bridge supports command/plugin lifecycle;
typed geometry and solver-job submission through that boundary is still pending.

## Renderer-owned frame provenance

Pitoco now reads the actual Vulkan color attachment into a host-visible coherent
staging buffer. Swapchain creation requests transfer-source usage when supported
and disables clipping so occluded pixels remain available. Existing development
swapchains can be upgraded on the render thread without replacing the Vulkan
device, window or Flecs world, provided their dimensions and format are unchanged.
After the render pass, barriers make color writes available to transfer reads,
copy the image to the buffer, make transfer writes visible to host reads, and
restore the image's presentation layout. The CPU waits for the submission fence
before reading pixels. These requirements follow the Vulkan
[copy command contract](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdCopyImageToBuffer.html)
and [swapchain creation contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkSwapchainCreateInfoKHR.html).

The mesh builder records cache revision and tick before drawing. Cache adoption
has moved ahead of mesh construction so the following inspector observes the same
revision. The native PPM writer puts those identifiers, renderer frame number and
Vulkan format in its header. Only supported four-channel 8-bit formats are accepted;
RGB compaction preserves row order and the captured bytes. The optional Clojure
PNG encoder records their SHA-256 and frame metadata in an EDN sidecar. Native
capture itself has no JVM dependency.

Live captures at ticks 0, 100 and 240 succeeded at 2,560 × 1,640 pixels. Repeated
tick 100 produced identical whole-frame RGB hashes. Hiding PID 37705 did not
change any of those pixel hashes. This proves capture/playback consistency for
that state and renderer configuration, not physical accuracy or final rendering
quality. A request captures the next frame actually rendered; it does not itself
pause or seek. UI input/hover changes can legitimately change a full-frame hash.

The capture slot retains completion/error status until acknowledged. GPU resources
are released after file writing, and file failures are reported separately from
unsupported/resource failures. Staging payload is bounded at 64 MiB. The first
implementation waits and writes synchronously on the render thread; asynchronous
image sequences, broader swapchain recovery and an external bridge capture command
remain future work.

The authoring code was also consolidated: `nonlinear-job/bake-cache!` owns
single-solid cache baking; `coupled-job/bake-cache!` owns coupled cache baking;
`fem-job/write-vtu!` owns static FEM export. The corresponding three helper files
were removed. Native solver/storage namespaces and their ABI identities stay
separate; existing cached results remain valid. Reusable mechanisms belong below
scene scripts/data, while one/three-ball UI presets and fixed cache limits remain
explicit specializations to generalize.

Capture verification adds three tests / 26 assertions. They exercise the actual
native writer on a known 2 × 2 color pattern in both BGRA and RGBA layouts, verify
PNG channel order and row orientation, retain frame metadata, reject malformed
payloads, test busy/oversized requests and report file errors. The complete suite
passes 50 tests / 417 assertions (`readback-full-suite-log.txt`). Final UI images
and repeated RGB hashes are in `exports/vulkan-readback-final/`; the standalone
capture build also succeeds without launching a second GUI.

Post-consolidation verification: a fresh process passes 17 tests / 234 assertions
covering static FEM export, single/coupled cache ownership and export, boundary
geometry, spherical mass/free flight and native frame encoding. The complete
pre-consolidation suite passed 50 tests / 417 assertions. No native solver/storage
ABI was moved, and the live revision-32 cache remains available. See
`consolidation-suite-log.txt` and `readback-full-suite-log.txt`.

## Generic solid scene/data boundary — 2026-09-12

`coupled-job/bake-scene!` now consumes `:pitoco/solid-scene-v1` descriptions:
meshes, per-body density/material/gravity/contact settings, initial state and
integration controls are scene data. `pitoco.geometry/box-mesh` is pure Clojure;
`scenes/solid-impact.clj` composes it with a refined tetrahedron. The solver is
still the existing finite-deformation tetrahedral model, not a new constitutive
method. The ball preset delegates to the same cache mechanism.

Complete cache groups transfer to the UI thread, which owns them through Flecs.
An added `ScriptedScene` component carries per-body gravity/floor flags and the
SHA-256 of a saved normalized scene plus loaded solver fingerprints. Existing
cache layouts are unchanged. Export recovers physical masses and per-body
material constants from each cache and references the source artifact. This
avoids describing unlike solids as identical balls. Ball-named compatibility
entities remain internally; this is not yet a general plugin-defined node graph.

The displayed example uses separate floor drops of a 0.4 m box (11.52 kg,
E=5 kPa, nu=0.3) and a 0.5 m right tetrahedron (6.25 kg, E=20 kPa, nu=0.25).
There are 27/35 nodes and 48/64 tetrahedra respectively. Its 0.6 s bake at 240 Hz
contains 145 frames, uses a 50 microsecond maximum step, completes 12,096
substeps with no rejection, and reports minimum J=0.4334854149. These illustrative
material choices have no experimental calibration.

An earlier variant launched the solids toward each other at +/-0.6 m/s. It
failed to advance past t=0.2503255887 s, with 751 rejected substeps in the failed
interval. The valid cache remained displayed. Preserve
`exports/scripted-contact-failure/source.clj` and `report.txt` for contact-solver
work; the successful floor drops do not resolve or validate that interaction.
No contact tolerances were relaxed to make the scene succeed.

The generic path remains bounded to one through three free connected bodies,
fixed 240 Hz output, 20,000 nodes and 160,000 cells per body, and eight million
cached particles total. Triangle-stream and contact-surface capacities are also
checked. Arbitrary geometry still inherits the current sampled vertex/face
contact limitations, with no CCD or edge-crossing guarantee. External Clojure
can generate the source data independently, but standalone typed job submission,
clothing, paper and arbitrary plugin-defined solver nodes remain pending.

`tools/verify_mesh_export.py` now accepts comma-separated per-body node/cell
counts and uses each body's mass and gravity for reconstruction and momentum
balance. It also validates/copies the source hash artifact. The unchanged
three-ball baseline still passes: 874,107 particle records, center error below
4.0e-15 m and vertical momentum residual -5.69e-11 N s. This checks that the new
metadata format does not weaken the existing uniform-body verification.

Final generic export verification reconstructs all 8,990 particle samples using
reference tetrahedral lumped masses. Maximum center/velocity discrepancies are
2.22e-16 m and 8.88e-16 m/s. Minimum sampled height is zero. The measured vertical
momentum change is -0.01896808986 N s; gravity plus 104.5752519101 N s of ground
impulse closes the balance within 7.30e-12 N s. Total energy changes from
99.9957825 J to 75.8686976 J; the impact/friction scheme is dissipative. These
checks establish internal consistency, not material calibration or convergence.

The full compiled suite passes 52 tests / 504 assertions, including analytic
free flight of unlike solids under different vector gravities and generic
cache/export ownership. An external Clojure controller requested the live export.
Vulkan captures at ticks 0/80/144/80 match the revision-33 cache; repeated tick-80
RGB hashes are identical. Data, source artifact, report and images are under
`exports/scripted-solid-live/`. Pure scene authoring also runs from the independent
scripting project without loading AguaFria.

The final standalone executable and Pitoco.app build successfully
(`generic-standalone-build-log.txt`). Native dependency inspection contains no
JVM or development-host library. The build was not launched; the existing
hot-reload window remains the only Pitoco GUI used for this work.

## Near-contact face-normal conditioning — 2026-09-12

The inward-moving box/tetrahedron failure is reproducible at
0.2503255886986857 s. Its checkpoint contains contacts only 1.58e-15 m from a
triangle, with all three barycentric weights positive. The previous contact
normal came from normalizing the reconstructed closest-point difference. That
subtraction loses tangential accuracy as the separation approaches floating-point
resolution. In an independent rotated-cube probe, an exact normal
[0.8, 0.36, 0.48] became [0.8000373, 0.3598280, 0.4800668] at a -1e-12 m gap.
The velocity stopping tolerance is 1e-7 m/s, so this angular noise is significant.

For a closest point in a triangle's interior, the signed-distance gradient is
the geometric face normal. The implementation now uses it directly, preserving
the signed radial direction for edge/vertex Voronoi regions and the existing
pseudonormal fallback at zero distance. The same probe now returns the geometric
normal to rounding precision for gaps from 1e-3 to +/-1e-12 m and at contact.
Neither the 1e-9 m penetration tolerance nor the 1e-7 m/s velocity tolerance changed.

Bærentzen and Aanæs establish the angle-weighted pseudonormal's inside/outside
classification for closed oriented meshes, identify the face case with its face
normal, and discuss numerical uncertainty near zero distance in section 5.1.
Their sign-classification result does not itself validate a contact response or
CCD. The velocity-conditioning fix above follows the face's distance gradient;
the impact replay and conservation tests are separate evidence.
[Primary paper](https://backend.orbit.dtu.dk/ws/portalfiles/portal/3977815/B_rentzen.pdf).

The normal correction alone does not complete the inward-impact case. Its replay
entered severe step collapse near t=0.2501646 s. Temporary native clock probes
recorded 0.25016451416017216 s at observation 1,739 and
0.2501645614624188 s at observation 3,215. The owned job was explicitly stopped,
its future confirmed terminal, and the probes removed. Its last report had 988
accepted substeps and 14,768 rejected attempts in that output interval; minimum
J remained 0.99849993 and penetration stayed below 4.01e-12 m. This is a contact
convergence failure, not an inverted/degenerated FEM state or a missing gravity
term. The previously displayed revision-33 cache was not replaced.

A bounded trial from the saved failure checkpoint takes one 50 microsecond step
and records residuals after 1, 8, 48, 256, 1,024 and 4,096 sweeps. Even at 4,096,
position-stage closing speed is 8.48e-7 m/s, and the subsequent velocity-stage
residual is 9.32e-7 m/s. Penetration is only 3.0e-12 m. The worst contacts pair a
box bottom vertex and a tetrahedron bottom vertex with reaction weights close
to one on the opposing node. Both nearest faces point almost downward, so the
relative-velocity constraint rows are nearly opposed. Their tiny differences
couple to substantial horizontal slip. This diagnosis comes from the saved
contact normals, barycentric weights and velocities in
`exports/scripted-contact-failure/sweep-study.edn`; it is not a proof that all
contact failures have the same cause.

No larger iteration cap or relaxed velocity tolerance was adopted. Robust edge
contact/collision prevention and a coupled solve for dependent constraints remain
required work. Per-job native cancellation/progress is also required: polling a
Clojure future cannot interrupt an already-running native interval.

The completed face-normal change passes 53 tests / 518 assertions, including
the existing three-body impacts and the new near-face/edge/vertex regressions.
This supports the local normal correction, not completion of arbitrary-shape
contact. The source probe and before/after values are in
`near-face-before-log.txt` and `contact-normal-reload-log.txt`.

The standalone executable/app build also succeeds after the normal correction,
without JVM/development-host dependencies. All original 23 coupled-FEM
implementation/schema records match their pre-probe fingerprints after removing
the temporary hooks. The live historical cache export is restored and all six
artifact hashes match its preserved baseline. No new inward-impact cache was
published and no contact replay remains running.


## Coupled normal contact — Dantzig impulse adaptation

Baraff's 1994 paper, sections 4.1–4.4, describes Dantzig complementarity pivots
with active contact sets and principal linear solves. Its section 3 uses
force/acceleration variables and explicitly excludes impact. Pitoco adapts the
normal algorithm to impulses/velocities; it does not claim to implement the
paper's complete friction extension.
[Primary paper](https://www.cs.cmu.edu/~baraff/papers/sig94.pdf).

The new native normal system forms A = J M^-1 J^T and b = J v, then solves
lambda >= 0, w = A lambda + b >= 0 and lambda_i w_i = 0. Rows are scaled by
the square root of their diagonal. Active principal systems use partial pivoting;
singular pivots, invalid input, iteration limits and failed physical-unit KKT
checks return failure. No diagonal regularization hides dependent constraints.
The dense capacity is 128 rows. Input must be a symmetric PSD Delassus matrix;
the standalone matrix wrapper checks finite values/symmetry/positive diagonal,
not a general proof of positive semidefiniteness.

On the preserved 50-microsecond checkpoint trial, four normal constraints solve
in three pivots. An independent 80-digit Decimal enumeration of every active
subset agrees within 2.28e-13 N s. Applying the impulses leaves closing speed
3.41e-16 m/s, linear momentum error 1.43e-14 N s and angular momentum error
5.00e-16 kg m^2/s. Kinetic energy decreases. These are isolated impulse checks,
not a trajectory-level conservation or convergence claim.

The integration fallback gathers vertex/face rows plus participating floor rows
when local velocity sweeps fail. It alternates a global normal solve with
sequential Coulomb-limited tangential increments, for at most 64 passes. Each
tangential increment uses the current velocity and cannot increase kinetic
energy in exact arithmetic. This is not a full global Coulomb complementarity
solve. Failed blocks roll back the whole tentative step. Existing position
repair and sampled contact geometry remain; edge-edge contact and CCD are open.

The bounded friction trial completes with four contacts and three pivots,
zero measured closing residual, penetration 3.06e-12 m and kinetic energy
56.27789946 -> 56.27086695 J. Evidence is under
`exports/scripted-contact-failure/`: `normal-system.edn`,
`normal-decimal-oracle.json`, `normal-physical-verification.edn` and
`friction-block.edn`. No new source helper files were needed.


The subsequent production replay completes all 145 frames (0.6 s) without
changing solver fingerprints. It accepts 17,551 substeps and rejects 21,585
attempts; minimum J is 0.39559976 and maximum sampled penetration is
9.99999992e-10 m. The large rejection count identifies position-contact
convergence as continuing work. This result supersedes the earlier incomplete
inward-impact attempt for these particular meshes and parameters.

The existing window adopts revision 34. Vulkan captures at ticks 0/65/144/65
were inspected; repeated tick-65 full-frame pixels match. Independent particle
export reconstruction verifies all 8,990 records, minimum floor height zero,
center error <= 2.23e-16 m and velocity error <= 1.34e-15 m/s. Energy is
103.19438250 -> 71.12099160 J, with maximum 103.19438250000609 J. Vertical
momentum balances gravity plus 99.97854452347096 N s of ground impulse within
1.84e-11 N s. This does not establish trajectory angular-momentum conservation,
material calibration, contact convergence or intersection-free motion.

Evidence, normalized hashed scene source and the complete job report are in
`exports/scripted-impact-live/`. The previous floor-drop and spherical evidence
remain intact. All 56 compiled tests / 557 assertions and 5 pure geometry tests /
49 assertions pass; standalone executable/app builds succeed without JVM or
shared development-host linkage. No second GUI was launched. Both replay
futures are terminal, native diagnostic hooks are removed, and the temporary
Clojure interval observer is restored to the production function.


## Conservative continuous feature queries

Wang et al., *A Large Scale Benchmark and an Inclusion-Based Algorithm for
Continuous Collision Detection* (TOG 2021), sections 5–6, use inclusion predicates
with floating-point error filters and time-ordered subdivision. Their result is
a conservative impact bound; a positive result can be a false positive, and
iteration-limited solves report their achieved tolerance. This addresses missed
between-frame feature crossings, separately from collision response.
[Primary paper](https://continuous-collision-detection.github.io/tight_inclusion/CCD-benchmark-paper-350ppi.pdf).

Pitoco now links the reference Tight-Inclusion library at commit
`6f84001a790a9d3362b7c13d410037140d83fa2c`, with pinned Eigen 3.4.0 and spdlog
1.15.3. A single native adapter serves AguaFria Zig and native callers. Its build
uses double precision, disables fast math and contraction, and leaves timer and
queue-size overrides disabled. The adapter supplies the equation-(5)
max(|coordinate|,1)^3 filter explicitly, with upward-rounded products, and the
reference minimum-separation constants when required. It does not rely on the
upstream automatic-filter branch's coordinate clamping.
[Reference implementation](https://github.com/Continuous-Collision-Detection/Tight-Inclusion/tree/6f84001a790a9d3362b7c13d410037140d83fa2c).

The API checks finite inputs, normalized time in (0,1], positive tolerance,
1–1,000,000 iterations, round-to-nearest mode and coordinates <= 1e50 in absolute
value. Minimum separation must remain below the largest clamped absolute input
coordinate on each axis, as required by the selected reference interface. Large
absolute coordinates can make conservative filters loose. It accepts initial
contact without trying to force a nonzero impact time. Native failures are
explicit; a clear result alone means that the queried linear feature trajectories
do not collide under this predicate's contract. This does not describe the curved
motion of a rigidly rotating object or all paths of a deformable simulation.

The final focused suite covers 169 assertions, including rejection of unknown
option names. The additional analytic study varies
known collision times over k/32, scales 1/8, 1 and 8, and two translations for both
feature types. All 372 crossings are reported, all 372 intervals ending before
the known impact are clear, and maximum reported time underestimate is
5.960464477539063e-8. No query in that study exhausted precision. Separate
coplanar/minimum-separation tests exercise conservative early termination; their
returned time is checked against achieved spatial tolerance, not mistaken for an
exact impact time. Full source/results and the native archive hash are preserved
in `exports/ccd/`. This is a targeted analytic study, not the paper's large dataset.

The native command-line smoke executable reports the crossing at
0.4999999962747097 with tolerance 1e-8 and needs no JVM. The same query succeeds
in the existing development session after reload, preserving displayed revision
34. No standalone GUI was launched. The integration into the actual solver's
swept broad phase, edge-contact response and correction paths remains unfinished;
current scene bakes must not yet be called intersection-free.


A second study uses the upstream `unit-tests` and
`erleben-cube-internal-edges` sample sets at commit
`a744562c7a842e9eee033fcea290e75a4a8e7b4e`. All rational input coordinates were
verified exactly representable as binary64 before comparison with the published
Boolean ground truth. The eight input CSV hashes, preparation/study source,
data license and complete results are preserved in `exports/ccd/`.
[Published sample data](https://github.com/Continuous-Collision-Detection/Sample-Queries/tree/a744562c7a842e9eee033fcea290e75a4a8e7b4e).

| Requested spatial tolerance | Queries | Published collisions | Missed collisions | Conservative false positives | Precision-limited queries |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1e-6 m | 824 | 216 | 0 | 21 | 6 |
| 1e-8 m | 824 | 216 | 0 | 21 | 14 |
| 1e-10 m | 824 | 216 | 0 | 21 | 14 |

The unchanged false-positive count shows that reducing requested tolerance alone
does not resolve these ambiguous cases under the current filters and iteration
budget. This matters for integration: repeatedly shrinking a step around a
conservative zero-time result can stall. Initial-contact/positive-clearance
handling and collision response must be designed together. These results cover
two selected sample directories, not the complete published benchmark.


Final regression verification passes 59 tests / 602 assertions, with a separate
final focused run of 8 tests / 169 assertions for query input validation. The
first full run found a provenance key-type mismatch in the existing sorted map;
the native-library key now uses the same symbol type as the module keys, and the
coupled bake/export regressions pass. All study futures are terminal. Re-export
through the external bridge restores all six files with hashes identical to the
preserved revision-34 impact cache. The existing GUI remains paused at tick 126.
No native scene initialization, standalone GUI launch or cache replacement was
needed for this CCD work.


## Swept triangle hierarchies

The feature predicates now have a whole-mesh query layer in `contact-mesh`.
For linear vertex motion, each coordinate over an interval is bounded by its
endpoint extrema; every triangle point is a convex combination of those vertices.
The union of both endpoint boxes therefore bounds the entire triangle sweep.
Internal BVH nodes union child boxes. Minimum-separation expansion rounds each
upper bound toward positive infinity before pruning a pair. The paired traversal
splits an internal node deterministically, testing all six vertex/face and nine
edge/edge combinations when both nodes are leaves. Subsequent feature queries
can stop at the earliest conservative bound already found.

This first-contact construction requires initially disjoint triangle surfaces.
Feature coincidence at time zero is detected, but arbitrary initial triangle
intersections and solid containment require separate validation. Open sheets
are allowed because this is an unsigned query; they are not passed through the
closed-surface pseudonormal sign contract. Motion buffers borrow private immutable
topology and own separate endpoints/bounds, preserving the live scene ABI.

Traversal work and primitive iterations are bounded separately. A traversal or
stack limit returns an incomplete native status, converted to a Clojure exception.
It must never authorize a drift. A primitive may conservatively return before
its requested precision: the minimum-separation test returns time 0.449951171875
for the known 0.45 event with achieved spatial tolerance 4.8828125e-4 m. The test
checks the spatial error against that reported tolerance rather than assuming an
exact impact time. Initial zero-time results are retained, not silently skipped.

The focused suite passes 12 tests / 239 assertions. The additional mesh study
uses three separated interlaced triangle pairs with staggered known collision
times, shuffled face storage, reversed query order, scales 1/8, 1 and 8, and two
translations. All vertices miss the opposing face; only edge crossings detect
contact. Across 120 queries (60 crossings and 60 clear truncated intervals),
there are zero missed crossings or false positives on this set, no precision
exhaustion and a maximum time underestimate of 2.9802322387695312e-8.
Each hit requires at most 45 feature queries; clear truncated intervals prune
at the root. Full source and records are in `exports/ccd/mesh-study.clj` and
`mesh-results.edn`. This is an analytic check, not a scalability benchmark.

The default integrator still uses discrete vertex/face corrections. At this
query-layer milestone, proposed drifts and corrections were not yet connected
to CCD; the subsequent experimental stepping work is recorded below.
No clothing or paper solver/rendered scene is introduced by this query work.

A further read-only audit reuses native motion buffers for all 144 intervals of
revision 34's heterogeneous solid cache (27/35 nodes, 48/64 boundary triangles).
With a 2,000-unit traversal budget and 10,000 iterations per primitive, 99 intervals
report clear, 44 report possible contact at time zero, and interval 59 exhausts
traversal work. Twenty-five possible results are precision-limited. Repeating
only the incomplete interval with a 100,000-unit budget completes in 655 node
visits and 2,310 feature queries, returning an edge/edge bound of 0.96875 within
that interval, with achieved tolerance 2.197265625000222e-4 m. The original
incomplete report remains in the saved evidence.

This is a query-layer audit of linear interpolation between stored 240 Hz frames,
not of all accepted internal substeps. Possible results do not establish actual
intersection, and the audit does not validate initial disjointness. The repeated
zero-time and limited-precision reports make the initial-contact requirement
concrete: merely rejecting every possible result would stall near contact.
`exports/ccd/cache-sweep-study.clj` and `cache-sweep-results.edn` preserve inputs'
artifact hashes, budgets, all interval results and the bounded retry. The six
preserved revision-34 artifact hashes were checked against the original manifest.

The complete compiled regression suite passes 63 tests / 674 assertions
(`swept-mesh-full-suite-log.txt`), including coupled bake/export and readback.
The standalone executable and app also build successfully without JVM or shared
development-host linkage. The live window still shows revision 34, tick 126.
After the full suite, an export through the external bridge restored all six
artifacts with hashes matching the preserved revision-34 baseline. Both mesh
study futures are terminal, and the original window remains paused with 145
frames and no bake running. No standalone GUI was launched.


## CCD-checked FEM drifts and finite-gap contact

[Bridson, Fedkiw and Anderson (2002), sections 5–7](https://www.cs.ubc.ca/~rbridson/docs/cloth2002.pdf)
describe separating internal dynamics from collision filtering of average
velocities, proximity treatment, mass-weighted feature impulses and repeated
collision checks after responses. Their cloth construction motivates this contact
architecture; it does not make the present tetrahedral solid solver a cloth model.
Their thickness repulsion and impact-zone failsafe are not implemented here.

The new opt-in `advance-continuous!` advances shared FEM Verlet substeps with
candidate endpoint buffers. Vertex/face rows use barycentric weights; edge/edge
rows use closest-segment interpolation. The effective inverse mass is J M^-1 Jᵀ.
Normal response is inelastic, with rebound supplied by the elastic material;
tangential increments are dissipative and bounded by each normal impulse's
Coulomb budget. Normal impulses conserve total linear and angular momentum.
Friction across a positive numerical gap introduces a couple bounded by gap times
tangential impulse; tests measure that bound rather than asserting exact angular
conservation at finite clearance.

The near-feature pass can reactivate earlier constraints, so a deduplicated
128-row normal complementarity solve follows difficult sequential sweeps. Candidate
face-pair storage is bounded at 8,192 pairs. Capacity, ambiguous geometry, CCD work
limits, pass limits and invalid steps fail explicitly. Rejected attempts restore
positions and velocities; velocity-only impulses now assign velocity directly,
preserving displacement bits smaller than an ulp of the reference position.

Every accepted candidate path has passed the paired mesh query. This statement
is conditional on initially disjoint surfaces and the linear endpoint path being
tested. It does not validate arbitrary initial triangle intersections, solid
containment, self-contact, or FEM element inversion between endpoints. No pair
position corrections are applied by this experimental integrator. The default
UI and authored-scene contact method remains discrete.

The first two-body 12 ms experiment stopped at 3.286796255409718 ms. Its last
selected feature had a 3.448340928817155e-9 m gap, essentially zero normal velocity
and a conservative time-zero result at 1e-8 m requested/achieved tolerance.
Removing normal velocity again or shrinking the timestep could not remove that
spatial ambiguity. A bounded retry now lowers the requested query tolerance, down
to 1e-14 m, and rechecks the entire proposed drift. Unresolved conservative hits
still fail. Replaying that checkpoint succeeds for drifts from 1.5e-12 to 1e-5 s.
A checked-in numerical fixture retains its mesh, material, positions and velocities
without the generated source-provenance dump. The regression confirms that the
coarser query remains conservative while the tighter query certifies the path.
Focused contact checks pass 8 tests / 173 assertions; this local fix alone
does not establish convergence.


The final three-body 12 ms run completes in 243 accepted steps, three rejected
attempts and 116,190 primitive queries. Integrated pair normal impulse is
0.019438060273934882 N s. Full inputs, reports and loaded native fingerprints are
in `exports/continuous-contact/precision-head-on-3.edn`. Earlier two-body failures
and their checkpoints remain alongside it. The precision-refined two-body run
was explicitly cancelled after more than five minutes in the interval after
3 ms; `precision-head-on-2.edn` records that cancellation and does not claim a
simulation result. Finer query precision fixes the saved local failure but does
not yet make extended two-body contact practical. Positive-clearance response,
normal conditioning and timestep/contact convergence remain open investigations.

An authored scene with `:contact-method :continuous` bakes two native cache
frames for free fall, with position error 4.174178364069192e-18 m after 84 substeps.
This verifies dispatch and cached output, not contact convergence. The full suite
before the precision refinement passes 70 tests / 843 assertions; the affected
focused tests after it pass 8 tests / 173 assertions. The standalone executable
and app build, with no JVM or development-host linkage. The helpers are reloaded
in the existing GUI; its 145 historical frames remain paused at tick 144, without
a new bake or standalone GUI instance.

After testing, external-bridge export restores all six historical revision-34
artifacts with SHA-256 hashes matching the preserved baseline. Study, build and
test processes are terminal. The slow two-body trial exited through explicit
cancellation; no completed result was inferred from its partial log.

### Variational contact integration (active implementation)

[Li et al. 2020, Incremental Potential Contact](https://ipc-sim.github.io/file/IPC-paper-350ppi.pdf),
sections 4–5, motivate minimizing an incremental potential while filtering every
candidate trajectory for contact and inversion. We use displacement-based
friction with a smoothing length `h * epsilon_v`; normal forces and tangent bases
remain fixed during each Newton solve and are refreshed before accepting momentum
balance. The paper does not guarantee convergence of those lagged updates.

The [IPC Toolkit simulation guide](https://ipctk.xyz/tutorials/simulation.html)
provides surface/full-volume derivative mappings and contact building blocks.
Pitoco assembles its existing tetrahedral FEM tangent in AguaFria, projects each
12-by-12 block to PSD, and solves a mass-plus-scaled-tangent system in a native
backend. The objective is `0.5*(x-xhat)' M (x-xhat) + h²*(elastic + barrier +
friction-displacement-potential)`, with `xhat=x0+h*v0+h²*g`. Friction potential is
dissipative and is not included in stored mechanical energy.

[The convergent formulation guide](https://ipctk.xyz/tutorials/convergent.html)
informs area weighting, improved-max collision sets, and the physical barrier
parameterization. Pressure is authored in Pa; the present 1000 Pa study value is
a numerical parameter, not material calibration. Native code pins Toolkit commit
`478876f30bf8ea768772dd8983c26a1a801ad976` and reports the linked library hash.
Our integration still needs complete temporal, spatial, and contact-clearance
qualification; using Toolkit does not by itself establish research accuracy.

Independent finite differences and Coulomb/floor/inversion checks are executable
through `tools/verify_variational.py`. Longer isolated impact results and remaining
failures are tracked in `AGENT_TODO.md`. The explicit impulse integrator remains
available for comparison; its prolonged near-zero-gap stall is not resolved by
this alternative integrator.

### Native element projection

The element Hessian projection used by the solid Newton solver now lives in
AguaFria Zig (`variational/project-element!`). It symmetrizes each 12-by-12 block,
uses the system Accelerate LP64 `dsyev$NEWLAPACK` eigensolver, clamps negative
eigenvalues, and reconstructs a symmetric positive-semidefinite block. This is
Newton-direction stabilization; the constitutive energy and force are unchanged.
[LAPACK's DSYEV contract](https://www.netlib.org/lapack/explore-html/d8/d1c/group__heev_ga8995c47a7578fef733189df3490258ff.html)
specifies column-major eigenvectors and a workspace of at least `3*n-1` doubles.
The fixed 12-DOF implementation uses 480 scratch doubles, checks status/finite
results and leaves its input unchanged on failure. It requires macOS 13.3 or newer
for this system symbol; the provider/ABI and OS version enter solver provenance.
The C++ element adapter now only inserts already-projected values into the
external sparse container. Dense known-spectrum checks at scales 1e-12, 1 and
1e12, an independent sparse insertion check, and a standalone native executable
verify the change. The subsequent migration below removes the remaining
handwritten UI, host, and geometry support from C++.

### Clothing and paper: next discretization

[Baraff and Witkin, Large Steps in Cloth Simulation (1998)](https://www.cs.cmu.edu/~baraff/papers/sig98.pdf),
sections 4–5, describe triangle material coordinates for directional stretch and
shear, hinge bending, and deformation-mode damping. Their motivation for an
implicit solve is relevant to textiles with strong stretch resistance and weak
bending resistance. Their timestep acceptance criterion targets animation;
Pitoco must additionally measure accuracy and convergence for research workloads.

[Grinspun et al., Discrete Shells (2003)](https://multires-1.cms.caltech.edu/pubs/ds.pdf),
sections 2–3, use differences between current and reference dihedral angles with
reference edge/dual-height weights. Nonzero reference curvature distinguishes a
shell from an initially flat sheet. The paper also explains why naively making
tetrahedra extremely thin is numerically problematic, and uses automatic
differentiation for consistent energy gradients and Hessians. This provides a
basis for both fabric bending and paper with folds; permanent crumpling still
requires an additional constitutive plasticity model and its own validation.

Planned Pitoco implementation decisions:

- Use triangle FEM membrane energy in material coordinates, authored in N/m,
  with independent warp/weft/shear response, and surface density in kg/m².
- Use a hinge bending modulus in N·m and a reference angle per interior edge.
  Do not infer measured textile bending solely from a homogeneous solid modulus.
- Reuse the collision-filtered incremental potential, sparse assembly, and
  rollback machinery. Open cloth boundaries require an open-surface validation
  path; they must not be forced through the current closed-solid BVH validator.
- Add exact prescribed-position constraints by eliminating constrained Newton
  degrees of freedom, not by assigning enormous masses.
- Verify rigid-motion invariance, membrane patch response, cantilever bending,
  timestep/mesh convergence, and thickness-aware self-contact before treating a
  rendered garment as physically qualified. Paper crease evolution, orthotropy,
  and wind coupling follow the clothing implementation.


## AguaFria ownership of application and geometry support

The C++ panel, export writer, extension host, and interval header have been
removed. `panel.clj` owns the visible layout, input actions, graph and timeline,
and writes native CSV/JSON through libc. `host.clj` uses fixed-capacity storage
for one pending command, 64 completion tickets, and 16 plugins; callbacks run
outside the mailbox lock on the owning thread. Its separate native library
retains the registry across UI hot reloads. The optional JVM client remains an
external program speaking the same data-only protocol.

`geometry.clj` implements strict outward rounding, fixed-axis separation,
Bernstein determinant subdivision, topology edge sorting, reference/displacement
round trips, and floor/positive-volume backtracking. IPC supplies the surface
collision-free fraction; AguaFria applies the ground and tetrahedron admissibility
policy. The Eigen and IPC objects stay behind their C++ boundary. A fingerprinted
AguaFria object is linked into both external solver adapters; changing these
predicates changes their recorded native binary hashes.

Regression evidence: 744 analytic CCD queries retain zero false positives and
false negatives. The 824 published reference queries retain zero false negatives,
14 conservative false positives and 10 precision-limited results at tolerance
1e-8. The final contact adapter retains maximum relative gradient error
9.765e-9, sparse residual 8.889e-9, projected-element residual 1.878e-9, friction
force ratio 0.5 and ground-safe fraction 0.0004. These are code-migration checks,
not new evidence of physical calibration or completed convergence.

The native UI was captured in the original process without resetting the cached
simulation. A standalone export probe verifies the metadata/topology schema and
retains NaN for rigid-body observables that do not apply to deforming FEM bodies.
Native plugin lifecycle and separate Clojure transport tests pass. The full
regression run records 87 tests and 1,154 assertions with no failures/errors.

The live migration uses distinct internal `pitoco_aguafria_*` entry names so
retained implementations cannot silently call the earlier C++ geometry/host
libraries. The public plugin SDK remains v1. Final live contact and variational
checks pass 27 tests / 442 assertions after those entry changes; the standalone
bundle is rebuilt and its native derivative/feasibility checks pass again.

## Resumable explicit stepping and the authored solid contact stall

The explicit integrator now yields between bounded batches of attempted Verlet
steps. Its native task retains the frame target, cumulative report, and the
reduced step after a rejection. Before yielding it restores rejected trial
positions/velocities and retains the accepted clock; it does not turn a partial
frame into a completed cache. Retrying from the original maximum step after each
host yield would repeat unsuccessful work indefinitely, so the regression case
compares the full retry report and accepted positions/velocities across budgets
of 1, 8 and 64 attempts. They agree exactly in this explicit test. This is not a
claim of cross-platform determinism or of deterministic IPC output.

The authored box/tetrahedron case exposes repeated mutual-contact repair
failures near 0.253 seconds, before either object reaches the floor. A bounded
probe records 256 attempts with 164 rejections in one frame, an accepted time of
0.2526625 seconds, minimum accepted step 3.125e-6 seconds, minimum Jacobian 0.9762,
and sampled penetration close to the 1e-9-metre acceptance limit. No floor impulse
was recorded in that frame. The earlier ground-contact diagnosis was incorrect.
The finite-step rejection pattern motivates examining coupled position repair
and time-step recovery; the evidence does not justify relaxing penetration
acceptance or declaring this collision converged.

The same live UI job was cancelled at tick 60 / step 89. Acknowledgment took
0.1245 seconds and retained the 31-frame displayed cache at revision 51. This
verifies cancellation during the troublesome mutual contact rather than only
before computation begins. Bounded batches preserve responsiveness; they do not
solve the underlying contact stall, accelerate all workloads, or bound the wall
time of an individual collision/linear operation.

Evidence: `build/explicit-contact-bounded-probe.edn`,
`build/explicit-ui-cancel-check.edn`, `build/explicit-cancel-ui.png`, and
`build/explicit-batch-live-verification.edn`. The three focused tests pass
1,029 assertions covering zero work, accepted-state cancellation/resumption,
and rejected-step continuation.

The complete isolated regression passes 90 tests / 2,183 assertions with zero
failures/errors (`build/explicit-batch-tests.log`), including the previous
three-body impact and implicit contact checks. The standalone executable and
app bundle rebuild successfully (`build/explicit-batch-build.log`). These verify
the implementation change; the longer authored collision remains unqualified.
