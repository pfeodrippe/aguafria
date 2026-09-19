# Field Lab: procedural engineering in AguaFria Zig

## Solver decision review — 2026-09-13

### Independent elastic-wave impact benchmark

An elastic bar striking a rigid wall tests wave propagation and contact together.
The [Ansys VM265 verification case](https://ansyshelp.ansys.com/public/Views/Secured/corp/v252/en/ans_vm/Hlp_V_VM265.html)
uses this problem: a compression wave reflects at the free end and returns to
the contact end before separation. Pitoco's variant uses free three-dimensional
tetrahedra with Poisson ratio zero and SI dimensions; it does not reproduce
VM265's constrained shell discretization. The longitudinal wave and stress/velocity
relations follow [Bower, Applied Mechanics of Solids, section 4.3](https://solidmechanics.org/Text/Chapter4_3/Chapter4_3.php).

For length L, cross-sectional area A, density ρ, modulus E and incoming speed v:
c = sqrt(E/ρ), contact duration = 2L/c, force = ρcvA and total impulse = 2ρALv.
The center velocity rises linearly from -v to +v during ideal contact. These
reference functions use no FEM solver output. Force comparisons integrate the
analytical pulse over each sampling interval, including bins straddling impact
or release. Hyperelastic corrections are limited by requiring v/c ≤ 0.001.

The first 36-node / 48-tet pilot uses L=0.1 m, A=0.0004 m², E=1 MPa,
ρ=1000 kg/m³ and v=0.01 m/s. Its strain scale is 0.0003162. Reference mass,
force, contact duration and impulse are 0.04 kg, 0.126491 N, 6.32456 ms and
0.0008 N s. With 256 maximum steps per contact duration and 128 output bins,
the measured rebound ratio is 0.87699, force-pulse relative L1 error 0.15587 and
mechanical energy loss 0.16570. All steps complete, with 113 rejected trials
retried. This is evidence of numerical error, not a validation pass.

The pilot saved complete data in `build/rod-impact-probe.edn`; its ad-hoc launcher
then failed on a malformed cleanup expression. The permanent CLI uses structured
`try/finally` and atomic study records, retaining partial samples on numerical
failure and refusing to overwrite earlier records. Spatial/time comparisons
completed in `build/rod-impact-refinement-study.edn`. Reference, integration,
assumption validation and failure-publication checks pass 6 tests / 51 assertions
(`build/rod-impact-final-tests.log`).

Reproduction entry point:

```sh
clojure -M:impact-study '{:benchmark :rod-impact
                         :cases [{:divisions [1 8 1]}
                                 {:divisions [1 16 1]}
                                 {:divisions [1 8 1] :steps-per-contact 512}]
                         :output "exports/rod-impact-study.edn"}'
```

### Variable-step BDF2 implementation

[Convergent IPC, section 8.2](https://arxiv.org/html/2307.15908v1) compares BDF2
with implicit Euler and Newmark for contact-pressure accuracy and dissipation.
It motivates testing another integrator, not assuming that higher order alone
fixes nonsmooth contact. The variable-step coefficients and backward-Euler startup
follow the [Waiwera time-stepping formulation](https://waiwera.readthedocs.io/en/latest/setup_time.html#bdf2).

`variational.clj` now implements BDF2 in AguaFria Zig. Apply the same BDF2 derivative
to x'=v and Mv'=f. For step ratio r=h/h_previous, define
s=h(1+r)/(1+2r) and q=r²/(1+2r). The position predictor is
x_hat=x_n+q*Δx_previous+s*(v_n+q*Δv_previous)+s²*g.
Minimize the existing inertial potential plus s² times the elastic/contact
potential, then compute v_next=(x_next−x_n−q*Δx_previous)/s. The friction origin
is x_n+q*Δx_previous with effective duration s; the actual collision path remains
x_n to x_next. The existing CCD, volume certificate, Newton tolerances and
rollback rules remain active. No new C++ implementation is introduced.

The workspace owns accepted displacement/velocity increments, previous duration
and contact impulse. History persists across output frames and bounded work
batches. Failed trials never commit history. Switching away from BDF2 invalidates
it on the next accepted step; direct external state edits require a new context
or explicit `reset-history!`. A history timestamp rejects reuse after an external
time change. Step growth is capped at 2×, below the classical sufficient
zero-stability limit 1+sqrt(2); that bound is not a nonlinear-contact stability
proof. See [Akrivis et al., variable two-step BDF](https://link.springer.com/article/10.1007/s10543-024-01007-y).

Contact impulse is integrated independently from force: ΔI_next=q*ΔI_previous+s*F_next.
This is BDF2 quadrature for I'=F, not a value reconstructed from momentum change.
Reported sample-average forces therefore use the method's integrated impulse;
they should not be described as instantaneous endpoint forces. Constant gravity
still contributes exactly M*g*h per accepted velocity step.

The independent elastic dilation reference gives phase errors 2.14363e-4,
5.41387e-5 and 1.35563e-5 at 2 / 1 / 0.5 ms: near-fourfold reduction under halving.
Splitting the run into output intervals preserves the trajectory; injecting a
failed solve after accepted history produces the same subsequent result. Initial
coefficient, dilation, friction and two-body contact checks pass 4 tests / 138
assertions (`build/bdf2-focused-tests.log`). Evidence:
`build/bdf2-dilation-evidence.edn`. The broader regression passes 35 tests / 632
assertions, adding variable-step ballistics, history reset, authored settings and
cache publication (`build/bdf2-regression.log`). The completed rod comparison
uses the same solver identity and physical inputs, changing only integration or
the stated joint refinement:

| Method | Axial cells | Force relative L1 error | Rebound ratio | Mechanical energy loss |
| --- | --- | --- | --- | --- |
| Backward Euler | 32 | 5.600% | 0.95626 | 6.001% |
| BDF2 | 32 | 29.267% | 0.96496 | 2.409% |
| BDF2 | 64 | 8.245% | 0.97899 | 1.202% |
| BDF2 | 128 | 2.147% | 0.98738 | 0.601% |

BDF2 reduces dissipation but increases force error at the matched 32-cell
resolution. Joint refinement reduces its force error substantially; this does not
qualify a default change. Evidence: `build/bdf2-rod-comparison-study.edn`.
The 128-cell result is in `build/bdf2-rod-128-study.edn`; the inspected plot
`build/bdf2-rod-comparison.png` / `.pdf` compares all four runs after verifying
identical solver identities, analytical references and output times. The 256-cell
study is running to test a 1% force/rebound benchmark target. This is a target for
those measured quantities in this specific small-strain experiment, not a general
research certification.

The coarse three-ball BDF2 scene completes 0.6 s / 145 frames at a 0.5 ms cap,
using 43 nodes / 80 tetrahedra per body: 1,301 accepted steps and two rejections.
Minimum reported J is 0.39532. Integrated floor impulse is 256.82266 N s; momentum
balance residual components are [-2.70e-7, 5.41e-11, -5.06e-8] N s. Independent
final-coordinate determinants remain positive, and reference-volume lumped mass,
centers and velocities agree with recorded observables. These are final-coordinate
checks and do not independently verify intermediate contact paths or convergence.
Evidence: `build/bdf2-three-ball-study.edn`,
`build/bdf2-three-ball-final-verification.edn`.

The new native workspace and experimental method label are loaded in the original
application. A three-frame BDF2 smoke bake passes, preserving the historical
revision 61 / 145-frame / tick-72 cache. The standalone build also passes. Evidence:
`build/bdf2-live-smoke.edn`, `build/bdf2-presentation-reload.edn`,
`build/bdf2-standalone-build.log`. Keep BDF2 experimental and backward Euler default.

### Small-strain energy precision and coupled refinement

The nonlinear solve was spending excessive work on backtracking at small
strains. An independent 80-digit evaluation of the original stable Neo-Hookean
energy identified cancellation between large, nearly equal terms. At axial
strain 1e-8, E=1 MPa and ν=0, the old binary64 calculation returned
2.2670383e-11 Pa instead of 4.9999998e-11 Pa: a 54.66% error. A rotated 1e-10
strain could even produce a negative energy instead of approximately 5e-15 Pa.
This was an implementation accuracy defect, not a reason to relax tolerances.

`hyperelastic.clj` now evaluates the same constitutive energy through
G=FᵀF−I near a proper rotation, using compensated products/sums and a series for
z−log(1+z). The determinant increment uses d/(1+sqrt(1+d)); the energy rearranges
its cancelling linear terms before evaluating them. The original expression
remains for large strain and inverted configurations. PK1 stress and its
analytical tangent are unchanged. The precision oracle in
`tools/verify.py material` uses Python Decimal at 80 digits and exact binary64
input values, independently evaluating the original expression.

All 48 exported cases pass the oracle (the old expression passed 8/48), including
rotation, axial strain, shear, compression and inversion. Two additional compiled
regressions pass 49 assertions, including an independent Clojure BigDecimal
energy reference and energy-gradient/PK1 consistency. Existing hyperelastic and
implicit suites pass 25 tests / 274 assertions, and the standalone build passes.
Evidence: `build/hyperelastic-compensated-precision.json`,
`build/compensated-energy-tests.log`, `build/compensated-material-regression.log`
and `build/compensated-material-standalone-build.log`.

For the original rod discretization, the corrected material reduces Newton
iterations from 13,368 to 4,196, backtracks from 336,697 to 86,679 and rejected
trials from 113 to 31. The first output interval falls from 575 iterations and
15,905 backtracks to 11 iterations and zero backtracks. Recorded wall time falls
from 121.1 s to 36.9 s, but background load differed; work counts provide the
stronger comparison. Physical discretization error remains.

Following the joint refinement principle in
[Convergent IPC, section 8.3](https://arxiv.org/html/2307.15908v1), the completed
corrected-material study halves axial cell size, maximum timestep and contact
distance together. Initial gap stays 2 μm, Courant number stays 0.03125, and
contact-distance/cell-size stays 8e-5. These ratios are our chosen experiment,
not the paper's numerical parameter values.

| Axial cells | Maximum steps/contact | Contact distance | Rebound speed / incoming | Force relative L1 error | Mechanical energy loss |
| --- | --- | --- | --- | --- | --- |
| 8 | 512 | 1 μm | 0.88953 | 19.004% | 13.555% |
| 16 | 1024 | 0.5 μm | 0.93185 | 10.068% | 8.844% |
| 32 | 2048 | 0.25 μm | 0.95628 | 5.602% | 5.997% |
| 64 | 4096 | 0.125 μm | 0.97131 | 2.737% | 4.113% |

All cases complete, retain positive sampled element Jacobians and have momentum
balance residuals below 8.3e-11 N s. The monotone error reduction supports the
refinement direction. The finest case still underpredicts rebound by 2.87% and
loses 4.11% mechanical energy with no prescribed damping or friction; it is not a
validation pass. Next compare further refinement and temporal integration on
this benchmark before accepting a higher-order method for three-ball contact.
Evidence: `build/compensated-rod-refinement-study.edn`,
`build/compensated-rod-fine-study.edn`, and the inspected four-level plot
`build/compensated-rod-comparison.png` (also PDF). All four cases have identical
solver identities, references and output times. Earlier results
in `build/rod-coupled-refinement-study.edn` use the original energy and are retained
as historical evidence, not mixed into this convergence table.

An additional 32-cell case halves only the timestep (4096 maximum steps/contact)
with contact distance fixed at 0.25 μm. Rebound improves from 0.95628 to 0.96101,
and energy loss decreases from 5.997% to 4.752%, but force error increases from
5.602% to 7.341%. Temporal refinement alone does not monotonically improve this
contact pulse. The CLI now accepts explicit `:integration :newmark` for comparison
against the same analytical reference; backward Euler remains the default.
Unknown integration names are rejected; the later BDF2 implementation is described above. The corrected-energy Newmark
rod case completes at 32 cells / 2048 steps/contact / 0.25 μm. All physical
settings, compiled solver identities, analytical references and output times
match the corresponding backward-Euler run. Newmark improves rebound to 0.97318
and has only 0.00105% net mechanical energy gain, but force-pulse L1 error rises
to 59.333% (backward Euler: 5.602%). This demonstrates why energy conservation
alone cannot qualify contact accuracy. It is consistent with the paper's
pressure-oscillation caution, but does not prove that mechanism explains every
error. Newmark remains an explicit research option, not the UI default; earlier
failed three-ball experiments are retained. Evidence:
`build/compensated-rod-newmark-study.edn`,
`build/compensated-rod-integration-comparison.edn`. Benchmark tests pass 6 tests /
51 assertions in `build/rod-integration-options-tests.log`.


The live reload must also rebuild native callers that can inline material
expressions. Updating only the material entry point left the FEM assembly and
cache writer using old expressions. `live/reload-solvers!` now reloads those
callers under the existing exclusive solver guard and mailbox reservation. A
completed two-frame three-ball bake verifies identical direct/cached initial
elastic energies around 1.7e-29 J, all steps complete, and the pre-existing
revision 61 / 145-frame / tick-72 cache remains intact. Evidence:
`build/compensated-cache-live-smoke.edn`. An isolated cache/playback regression
and mailbox-failure guard pass 2 tests / 19 assertions
(`build/compensated-cache-regression-ready.log`). The initial ad-hoc test launcher
omitted the extension-host link setup; the corrected launcher uses the same setup
as `field-lab.test-runner`. Historical caches retain their original
measurements; the fix applies to newly baked data.

### Completed mesh and boundary sensitivity

The same-version three-ball experiment completes at both 43 and 205 nodes per
body (80 and 640 tetrahedra), at a fixed 0.5 ms timestep cap and 100 μm contact
distance. Authored dimensions, density, material, initial velocities, gravity,
friction and output times match. Refinement projects new boundary points onto
the intended sphere, changing the represented volume as well as FEM resolution.

| Body | Maximum center trajectory difference | Final center velocity difference |
| --- | --- | --- |
| Left | 22.587 mm | 0.06540 m/s |
| Middle | 12.376 mm | 0.08182 m/s |
| Right | 31.844 mm | 0.08581 m/s |

The coarse and refined meshes have masses of 13.582969 and 15.024653 kg per
ball; the analytic sphere at the authored density has mass 15.550884 kg.
Relative mass errors are -12.65% and -3.38%. These results expose substantial
mesh/geometry sensitivity. They do not establish convergence or isolate the
error due to FEM interpolation. The completed fixed-polyhedron study preserves
the boundary and mass (13.582969 kg/body), while refining 43 to 205 nodes. Maximum
center differences remain 13.082 / 12.716 / 14.117 mm. Thus curved-boundary change
is not the only source of mesh sensitivity. The refined run has positive final
cell volumes, minimum reported J=0.36496 and 24 rejected trials. Evidence:
`build/fixed-domain-comparison.edn`. Both mesh studies predate the compensated
energy calculation and remain historical sensitivity measurements.

The refined run accepts 1,409 steps and retries 18 rejected trials, with minimum
reported J = 0.467679 and final mechanical energy 72.201737 J. Its measured
external contact impulse is [0.656538, 288.633855, 0.213289] N s; momentum balance
residual is [4.97e-8, -2.36e-10, 9.28e-9] N s. Independent determinants computed
from final particle coordinates remain positive in all bodies and reproduce
the reported final volume ratios within 3e-15. These final-coordinate checks
do not independently certify every intermediate configuration. Different mesh
topologies are compared through body observables, not by arbitrary node indices.
Evidence: `build/mesh-comparison.edn`, generated by `build/compare-mesh.clj` from
`build/mesh-coarse-ipc-study.edn` and `build/mesh-refined-ipc-study.edn`.

### Contact-distance sensitivity and cached solver identity

All three 0.6 s runs at contact distances 100 / 50 / 25 micrometres complete
with the timestep cap fixed at 0.25 ms and 43 FEM nodes per ball. Source data
other than contact distance, solver identity and output times match exactly.

| Contact-distance change | Maximum center difference | Maximum final-node difference | Maximum final nodal-velocity difference |
| --- | --- | --- | --- |
| 100 → 50 μm | 0.25685 mm | 0.56956 mm | 0.02233 m/s |
| 50 → 25 μm | 0.16457 mm | 0.62246 mm | 0.01942 m/s |

Center sensitivity decreases, but final-node sensitivity is not monotone.
These runs do not establish contact convergence. Mechanical final energies are
62.4724 / 62.5142 / 62.5223 J. Minimum reported J values are approximately
0.44689 / 0.44641 / 0.44618. Rejected trials total 1 / 0 / 1 and are retried;
all runs complete. Signed contact impulses and finite momentum residuals remain
in each record. Evidence: `build/clearance-comparison.edn`, generated by
`build/compare-clearance.clj` from `build/clearance-{100um,50um,25um}-study.edn`.
This is a fixed-mesh, fixed-timestep sensitivity study. The sharper potential
may require temporal refinement. The 25 μm / 0.125 ms run now completes with
4,896 accepted steps and no rejected trials. Relative to 0.25 ms, maximum center,
final-node and final nodal-velocity differences are 0.67629 mm, 1.74999 mm and
0.10878 m/s. Final mechanical energy rises from 62.5223 to 63.1776 J. The measured
temporal sensitivity exceeds the distance-only changes; neither test establishes
convergence or uniquely explains the nonmonotonicity. Evidence:
`build/clearance-25um-time-comparison.edn`,
`build/clearance-25um-125us-study.edn`.

### Profiled conservative rounding

A native sample of the refined three-ball bake locates significant work in the
positive-volume path certificate and its C libm `nextafter` calls. The geometry
module now uses Zig's standard `math.nextAfter` for the same outward rounding.
The interval formulas, round-to-nearest requirement, subdivision budget and
failure behavior are unchanged. The code does not depend on libm errno or
floating-point exception flags; this replacement preserves values, not those
side effects. Tests cover every binary64 exponent with three boundary mantissas
and both signs, including zeros, subnormals, infinities and NaNs. All 24,576
directional comparisons agree with Java Math.nextUp/nextDown (NaN class checked).

The identical 205-node / 640-tet positive-path microbenchmark takes a median
47.69 ms per 100 calls with libm and 26.65 ms with the native operation, across
three alternating samples: 1.79× for this certificate only. Both versions agree
on translation, shear, inversion and a path crossing zero volume despite a
positive final determinant. This is not a full-bake speed claim. Evidence:
`build/outward-rounding-benchmark.json`, `build/benchmark-outward-rounding.py`,
`build/outward-rounding-evidence.edn`, `build/refined-ipc-sample.txt`.

The full implicit regression passes 18 tests / 226 assertions with the optimized
geometry library. Independent native ABI checks also pass (maximum relative
gradient error 9.76e-9; Newton matrix residual 8.89e-9). The complete coarse
three-ball trajectory at 1 ms retains 728 accepted steps and four rejected
trials. Maximum center, final-node and final-velocity differences from the prior
binary are 4.47e-14 m, 1.22e-13 m and 1.58e-12 m/s. The comparison checks identical
scene data and output times, and records changed binary identities. Wall times
have different competing workloads and cannot support a full-bake speed claim.
Evidence: `build/native-rounding-implicit-tests.log`,
`build/native-rounding-abi-verification.json`,
`build/native-rounding-trajectory-agreement.edn`.

The UI now identifies the method attached to the cached result. Flecs owns a
separate `ScriptedSolver` component, avoiding any resize of existing
`ScriptedScene` storage during hot reload. New publications copy the method
through the same ownership mailbox as their cache. Historical supplementation
requires both the source hash and cache revision to match on the owning thread.
Unknown metadata is displayed as unrecorded, never inferred from next-job radios.
The original revision 61 displays "Implicit IPC / backward Euler". Native tests
cover valid/stale/busy publication, metadata reset and code mapping (22 assertions).
The standalone build succeeds. Export hashes before/after the UI change agree
for all CSVs, configuration and source provenance. Evidence:
`build/cached-solver-label-captures/verification.edn`,
`build/cached-solver-export-agreement.json`.

### Measured contact impulses

Implicit reports now contain `:contact-impulse` (x/y/z, N s) and
`:ground-impulse` (its vertical component). AguaFria sums the contact-potential
gradient after convergence and integrates the force only when the step is
accepted: h*f1 for backward Euler, h*(f0+f1)/2 for Newmark. It does not derive
this quantity from a velocity difference or a momentum residual. Internal body
contact forces cancel under a common translation; in the current model the
remaining external contact resultant belongs to the fixed floor, including
friction. This interpretation must be extended if other fixed boundaries or
actuators are introduced. It is not a per-body pressure or pair-impulse measure.
The discrete balance follows the [Convergent IPC formulation, section 7](https://arxiv.org/html/2307.15908v1#S7).

The full coarse 0.6 s three-ball case at a 1 ms cap completes with the same four
retried steps. The integrated contact impulse is
[-0.335956322, 258.430373596, 0.031811393] N s. Subtracting gravity and this
measured impulse from momentum change leaves
[8.334e-8, -8.299e-11, 1.590e-7] N s. The solve uses a 1e-7 m/s residual tolerance;
these finite residuals are recorded, not rounded to zero. Final nodal positions
and velocities differ from the prior solver by at most 1.12e-13 m and
1.74e-12 m/s. The measurement has not materially changed the trajectory.
Evidence: `build/implicit-measured-impulse-study.edn`,
`build/implicit-impulse-trajectory-agreement.edn`. Tests cover signed friction
impulses, pair-force cancellation and exclusion of rejected trials. The full
implicit suite passes 17 tests / 224 assertions.

Repeating that complete case at a 1e-8 m/s nonlinear tolerance, with the same
scene, timestep and solver identity, gives momentum errors
[-3.593e-9, -7.526e-11, 3.020e-9] N s. Final nodal positions change by at most
1.228e-8 m and velocities by 1.584e-7 m/s. Nonlinear solve error in this case
is much smaller than the previously measured timestep sensitivity. Both cases
take 728 accepted steps and four rejected trials. Observed wall times are
90.15 / 91.33 s; these are single-run timings with concurrent host work.
Evidence: `build/implicit-tight-tolerance-study.edn`,
`build/implicit-tolerance-comparison.edn`.

New scripted publications include these reports in their hashed source envelope,
which native CSV exports already retain. Old reports without impulse fields stay
unmeasured; summaries use null rather than manufacturing a zero or deriving a
measurement from the conservation equation being tested. This measurement closes
one diagnostic gap; it does not establish temporal/spatial/material accuracy.

### Time integration follow-up

The implicit solver now supports authored `:integration :newmark` alongside the
default `:backward-euler`. This is the beta=1/4, gamma=1/2 incremental potential
in [Convergent IPC, sections 5–6](https://arxiv.org/html/2307.15908v1#S5):
the inertia predictor includes the old internal/contact force, the potential
multiplier is h²/4, and velocity is 2(x1−x0)/h−v0. Friction uses that same endpoint
velocity mapping: its displacement origin is x0+h*v0/2 and its effective
duration is h/2. The old-force evaluation instead uses origin x0−h*v0/2.
These affine origins never replace the physical collision geometry. CCD,
positive-volume filtering, convergence checks and rejected-step rollback remain
in force. The numerical implementation is AguaFria Zig; the existing IPC C++
adapter only exposes its friction origin setter.

An independent scalar RK4 reference for uniform dilation of a regular tetrahedron
checks the implementation without using the native solver as its own reference.
At 4 / 2 / 1 ms, the measured phase errors are 1.404e-4 / 3.524e-5 / 8.820e-6
(dimensionless stretch and velocity scaled by 50/s), approximately fourfold
improvement per halving. At 1 ms the relative mechanical-energy error is
5.698e-5 for Newmark versus 0.09869 for backward Euler over 0.04 s. This is a
specific unforced elastic oscillator, not a general energy-conservation claim.
Ballistic motion, rollback, authored settings and ground friction pass as well.
Evidence: `build/newmark-dilation-evidence.edn`, `build/newmark-regression.log`
(17 tests / 182 assertions), `build/newmark-native-verification.json`.

The paper's section 8.2 warns that Newmark can produce pressure oscillations
with sharp barriers, while BDF-2 and implicit Euler can suppress them. Therefore
Newmark is an explicit research option; the UI/default remains backward Euler.
Contact-distance, timestep and mesh studies must accompany any default change.
The same three-ball collision fails with Newmark at both 1 and 0.5 ms caps,
at 0.125671 s and 0.144087 s respectively. Rejected-step retries reach their
minimum admissible step without solving; neither run is published. Evidence:
`build/newmark-three-ball-1ms-failure.edn` and
`build/newmark-three-ball-500us-failure.edn`. This is a negative qualification
result, not proof that all failures are explained by the paper's instability
discussion. The 0.25 ms case also stops at 0.146939 s
(`build/newmark-three-ball-250us-study.edn`, including native failure details).
No Newmark preset qualification is claimed. The original
backward-Euler cases at these caps completed. Study failures now retain native
diagnostics in `:failure` instead of only the exception message.

The refined backward-Euler case completed and was published in the original
window as revision 61: 145 frames / 0.6 s, 3 × 205 nodes / 640 tetrahedra,
0.5 ms maximum step. Its 1406.79 s wall time includes concurrent verification
work; it is not a speed benchmark. The exported source is
`bf237e29f2fe0a11c58b2a4f741e8ff7a5d5540f528bb5e92640f946413d1805`.
Independent CSV verification recomputes all sampled element determinants and
lumped-mass centers/velocities: minimum J 0.467906, minimum y 7.594e-7 m,
maximum center discrepancy 7.22e-16 m, and 89,175 particle rows. A deliberately
inverted exported element is rejected. This checks saved states, not the
continuous trajectory between them. Ground impulse is unavailable, so the
verification explicitly reports momentum balance as unchecked, with null
impulse/residual. Final mechanical energy is 72.1810 J from 83.2689 J initially;
this alone does not separate friction from numerical dissipation. Evidence:
`build/refined-ipc-verified-export/verification.json`,
`build/refined-ipc-framed-captures/verification.edn`. Repeated same-tick native
captures have identical RGB hashes. The displayed cache remains this verified
backward-Euler result, not any failed Newmark attempt.

Changing the native export list also exposed a stale-library build bug. CMake
now declares `variational.exports` as a link dependency, so a changed export
surface forces relinking before a source-fingerprinted library is published.
The new symbol and independent native adapter checks pass with the rebuilt
library. Historical compiled libraries are preserved for existing jobs.

### Nonlinear contact foundation

This review responds to numerical errors, not just display quality. The present
explicit FEM / discrete contact-repair path is not the preferred foundation for
general, large-deformation offline contact workloads. Retain it for comparison;
prioritize the existing implicit variational path and qualify it before making
it the default. An algorithm name alone does not validate this implementation.

[SideFX's solve-method documentation](https://www.sidefx.com/docs/houdini/finiteelements/solvemethod.html)
recommends global nonlinear (GNL) over the legacy single-linearization method.
[Its collision documentation](https://www.sidefx.com/docs/houdini/finiteelements/collisions.html)
describes continuous surface detection and deliberately bounded soft repulsion.
Thus Houdini is not an error-free oracle, and copying its UI or increasing a
collision-pass count does not reproduce its nonlinear solve.

[Incremental Potential Contact, Li et al. 2020](https://ipc-sim.github.io/file/IPC-paper-350ppi.pdf)
provides a suitable alternative: minimize the implicit incremental potential,
with collision-filtered line search and inversion protection. Section 5 also
states that friction lagging is not guaranteed to converge. Pitoco must retain
failure/rollback when its requested momentum residual cannot be met.

[Convergent IPC, Li et al.](https://arxiv.org/abs/2307.15908)
addresses refinement consistency of the original discrete barriers. The
[Toolkit configuration](https://ipctk.xyz/tutorials/convergent.html) requires
area weighting, the improved-max collision set, and physical barriers together.
Our pinned adapter enables all three. This makes it a defensible candidate, not
a completed convergence result. Contact activation distance still needs study.

Current executable evidence: `build/ipc-snapshot-three-ball-study.edn` contains
all 145 frames of the same coarse three-ball scene at 1 ms and 0.5 ms caps.
Both finish; rejected trials total 4 and 0. Minimum reported J values are
0.447785 and 0.446206. The center trajectory changes by 2.2959 mm, final nodes by
4.8732 mm, and final nodal velocities by 0.25991 m/s. These differences are too
large to claim temporal qualification. Final mechanical energies are 59.8502 J
and 61.4311 J, from the same 75.2789 J initial state. Physical friction and
backward-Euler numerical damping both contribute; do not attribute all loss to
one of them. The completed 0.25 ms case has final energy 62.4724 J. Comparing 0.5 / 0.25 ms
reduces maximum center differences to 1.2768 mm, final-node differences to
3.1317 mm, and final velocity differences to 0.18312 m/s. This downward trend is
consistent with refinement but is not a requested-error qualification. Source
and solver identities match (`build/ipc-snapshot-time-comparison.edn`). Spatial
refinement and a less dissipative integration scheme still need investigation.

`build/ipc-snapshot-agreement.edn` compares the native snapshot with the original
development-dispatch run at 1 ms. Maximum final position difference is
6.50e-14 m; velocity difference is 1.50e-12 m/s. Both have four rejected trials.
The old final-frame-only reading of zero rejections was incorrect. Snapshot
execution took 112.79 s versus 154.40 s for development execution; other host work
was not fully controlled, so this is observed timing rather than a speed guarantee.


The inspector had a separate bake entry that bypassed authored jobs and rebuilt
43-node legacy balls. That routing is now intercepted, when the controller is
attached, before the legacy loop consumes the action. A released native request
copies the inspector configuration; the controller builds ordinary scene data
and uses the selected method/refinement. Busy requests leave the published cache
intact and show job status. Isolated tests cover one/three bodies, parameter
preservation, immutable mailbox snapshots and no fallback (4 tests / 129
assertions). Native implicit/lifetime/persistence tests separately pass 15 tests /
153 assertions. The final standalone binary builds without opening another GUI.
Standalone authored IPC scheduling still needs a native worker or an attached
controller; this change does not make the JVM mandatory for existing playback.


| Error being checked | Control / evidence required |
| --- | --- |
| Nonlinear momentum residual | Authored velocity tolerance (m/s), checked before accepting a step |
| Time integration error / backward-Euler damping | Same scene at successively smaller steps; compare motion and energy |
| Spatial/contact discretization | Mesh and barrier-distance refinement with unchanged physical inputs |
| Geometric admissibility | Whole-path CCD, positive volume, valid initial mesh, rollback on failure |
| Real specimen accuracy | Measured geometry, constitutive data, friction and independent experiments |

The previous explicit compiled benchmark retained exact histories/reports and
reduced the paired median from 8.597 s to 1.446 s on its 60 ms test. That is an
execution-speed result, not a remedy for contact-model error. The next native
snapshot includes the implicit FEM/Newton loop as well, preserving its tolerances
and pinned IPC adapter. The GUI can remain hot reloadable while each bake uses a
fixed solver generation. A changed generation invalidates the bake.


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

The streaming checker `tools/verify.py mesh` reconstructed nodal masses
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

`tools/verify.py mesh` now accepts comma-separated per-body node/cell
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
through `tools/verify.py abi`. Longer isolated impact results and remaining
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

## Coupled position repair resolves the authored mutual-contact stall

The subsequent rejected-trial diagnostics identify position residual, not a
constitutive failure or the explicit stability condition: one rejected trial
had penetration 1.0000456e-9 m, minimum Jacobian 0.9762, and stability number
2.6085e-5 against the 0.25 limit. Increasing sequential contact sweeps from 48 to
128 did not remove the stall and was discarded.

The fallback now solves the interacting position constraints together. For the
current signed vertex/face and plane gaps g and their Jacobian J, it minimizes
one half dx^T M dx subject to g + J dx >= 0. Its dual system uses the existing
normal-complementarity solver with A = J M^-1 J^T. A length scale of 1e-6 m scales
the right-hand side for numerical conditioning and is undone when applying the
position corrections; it does not change the constrained minimizer or introduce
a contact clearance. Normals and barycentric weights are re-evaluated for up to
eight solves. Capacity is 128 constraints; capacity/solve failures reject the
trial. The same penetration limit and material/stability checks remain in force.
Velocities are resolved separately; position repair does not inject an impulse.

This derives a coupled version of mass-weighted constraint projection, related
to the projection framework in [Müller et al., Position Based Dynamics (2006)](https://matthias-research.github.io/pages/publications/posBasedDyn.pdf).
The tetrahedral constitutive forces and integration remain FEM; the paper is not
being cited as validation of this hybrid contact algorithm. Vertex/face position
repair is still a discrete method and does not establish continuous collision
freedom or cover all edge crossings. IPC remains the separate continuous-contact
research path. Contact convergence and measured-material validation remain open.

Collision geometry also now uses the actual FEM point after the rest/displacement
round trip, rather than the requested point before floating-point rounding.
Focused tests verify center-of-mass preservation, unchanged momentum/kinetic
energy, and exact equality between surface and represented FEM positions across
unequal masses. A material-collapse trial verifies rejection, complete rollback,
and preservation of the reduced retry step across a host yield.

The full authored box/tetrahedron collision now bakes, publishes and renders all
145 frames (0.6 seconds). The first completed run took 37.49 seconds. Three
subsequent runs with the same mesh/materials measured:

| Maximum step (microseconds) | Wall seconds | Rejected steps | Minimum Jacobian | Maximum sampled penetration (m) |
| --- | --- | --- | --- | --- |
| 100 | 20.23 | 0 | 0.39459 | 9.987e-10 |
| 50 | 40.42 | 0 | 0.39822 | 9.934e-10 |
| 25 | 58.27 | 0 | 0.39966 | 7.648e-10 |

Temporal qualification is not complete: maximum center-trajectory differences
are 8.34 mm then 7.66 mm; maximum final-node differences are 4.38 cm then 3.22 cm.
The sequence improves, but neither the small number of levels nor the remaining
differences justify a convergence claim. Mesh refinement must be studied too.
Total energy never exceeds its initial 103.19438 J beyond roundoff in these runs;
final energies are 71.1540, 71.1571 and 70.8348 J. This includes inelastic contact
and friction and is not an isolated test of constitutive damping.

The displayed 50-microsecond cache and the corresponding study have identical
canonical source SHA-256 dced24498940a32389e167378b3f6617fa7e59ea223ed99ec8901e87bb49e225.
An independent CSV verifier recomputes mass-weighted centers/velocities from all
8,990 particle rows and reference tetrahedra. Maximum errors are 2.22e-16 m and
8.88e-16 m/s; vertical momentum balance error is 9.60e-12 N s using the separately
recorded integrator ground impulse. No sampled vertex is below the floor.

Evidence: `build/position-block-bake.edn`, `build/position-block-collision.png`,
`build/position-block-step-study.edn`, `build/position-block-step-summary.edn`,
and `build/position-block-verified-export/verification.json`. Reproduce the
numerical study with `coupled-job/scene-step-study!` and the original
`scenes/solid-impact.clj` data.

The authored three-ball floor/collision run also completes all 145 frames at
50 microseconds, with 615 nodes and 1,920 tetrahedra. Its first full run took
99.74 seconds, rejected no steps, and reached minimum J = 0.469137 at tick 43
(0.17917 seconds). The independent export verifier checks all 89,175 particle
rows, body masses, reference topology, and source identity. No sampled vertex
is below the floor. Vertical momentum balance error is 2.46e-11 N s; energy
decreases from 83.26888 J to 76.00391 J, including friction/inelastic contact.
Evidence: `build/three-ball-full-bake.edn`,
`build/three-ball-full-verified-export/verification.json`, and
`build/three-ball-framed.png`. Source SHA-256:
c16898757f637aa87dabeccd2f531c2d9e328a7ec030c1f72384a310154e0a67.

The existing development window retains revision 53 while shader/presentation
changes are applied. Camera framing now uses actual cached FEM bounds and body
height; a Frame (F) control makes small authored solids visible. The native
capture verifies floor/mesh alignment and visible three-body deformation.
The complete physics regression passes 92 tests / 2,217 assertions; the updated
standalone executable/app build passes as well (`build/position-block-regression.log`,
`build/position-block-framing-build.log`). This is implementation verification,
not a completion claim for physical calibration, garments, paper, or later work.

All three authored-ball time-step runs complete without rejected steps:

| Maximum step (microseconds) | Wall seconds | Minimum Jacobian | Final energy (J) |
| --- | --- | --- | --- |
| 100 | 48.39 | 0.46695 | 75.97747 |
| 50 | 93.50 | 0.46914 | 76.00391 |
| 25 | 181.85 | 0.46911 | 76.01813 |

The 100-to-50-microsecond comparison changes center trajectories by at most
0.3181 mm, final node positions by 0.5796 mm, and final node velocities by
0.03707 m/s. Halving again reduces those differences to 0.1172 mm, 0.2373 mm,
and 0.01421 m/s. This establishes decreasing temporal sensitivity for this
specific mesh and duration; it is not an exact-error estimate or mesh/material
qualification. Maximum sampled penetration is 4.04e-17 m across these runs.
Evidence: `build/three-ball-time-study.edn`, `build/three-ball-time-summary.edn`.
The existing `coupled-job/summarize-scene-run` and `compare-scene-runs` functions
produce the measurements and reject comparisons with changed scenes, solver
versions, output times, or particle counts. Missing impulse measurements leave
momentum balance unavailable. Ten focused assertions check these reporting rules.

The explicit velocity phase previously repeated full force/stability-bound
assembly even when positions were unchanged. `coupled-fem/refresh-motion!` now
compares represented FEM points against the synchronized surface and recomputes
only kinetic energy/momentum when they match. Per-body summation order is
preserved. Any changed point triggers the original full observer. This is
valid for the present velocity-independent elastic force model; it is not a
general rule for future velocity-dependent constitutive forces.

A repeated full three-ball bake took 59.44 seconds versus 93.50 seconds before
the change. All measured histories, final particle positions/velocities and
solver reports are exactly equal. Native checks also compare the fast path
against full assembly for unequal meshes/densities and changed velocities, and
verify full reevaluation after an explicit position change. Evidence:
`build/motion-refresh-three-ball.edn`. Neither wall time nor exact agreement
on this workload establishes a performance or determinism guarantee for others.

The box/tetrahedron verification also has exactly equal histories, final particle
state and reports, taking 34.53 seconds versus 40.42 seconds previously
(`build/motion-refresh-box.edn`). The full isolated regression passes 94 tests /
2,231 assertions (`build/motion-refresh-regression.log`). The subsequent UI mesh
selector passes two focused tests / 88 assertions, including legacy command
defaults, rejection of busy/invalid requests, actual source topology, and
preserved physical inputs. The final standalone rebuild passes
(`build/mesh-selection-tests.log`, `build/mesh-selection-build.log`).

The finer authored three-ball UI job completes and publishes revision 54:
145 frames, 3,627 nodes / 15,360 tetrahedra, zero rejected steps, minimum
J = 0.363457 and maximum sampled penetration 3.18e-17 m. The independent
verifier checks 525,915 particle records and reference-mesh mass measures.
Minimum sampled y is zero; vertical momentum balance error is 2.45e-11 N s.
Energy decreases from 85.44361 J to 79.02800 J. Native captures show the
actual refined surface at tick 43 (`build/three-ball-refined-contact.png`)
and at the minimum-J tick 139 (`build/three-ball-refined-peak.png`).

The new `verify.py mesh --compare-to <verified-export>` option checks
the baseline manifest and compares identically sampled center trajectories.
The 205-to-1,209-node refinement changes centers by up to 7.7866 mm and center
velocities by 0.13049 m/s. The material, density, gravity, friction, nominal
sphere shape/size, initial velocity and integration inputs match; solver
fingerprints match the optimized coarse run, whose trajectories were already
shown to equal the archived coarse export exactly. This provenance check is
separate from the CSV tool's exported-field comparison.

Density stays fixed while the spherical boundary approximation improves:
per-body volume changes from 0.01365878 to 0.01401550 m³ and mass from
15.02465 to 15.41705 kg. This is a curved-domain refinement study; its differences
include geometry/mass approximation as well as finite-element resolution.
The finer mesh still needs a time-step study and another spatial level.
These results do not complete spatial qualification or material calibration.

Evidence: `build/three-ball-refined-full.edn`,
`build/three-ball-refined-verified-export/verification.json`,
`build/three-ball-spatial-provenance.edn`. Fine source SHA-256:
36ca134287c7622740c8f7e479f5f8e0fe6da3a800bb7cb96ea3dface0fbb52f.

### Detailed embedded rendering (2026-09-13)

Primary source: Doug L. James, *Phong Deformation: A better C0 interpolant for
embedded deformation*, ACM TOG 39(4), 2020,
[DOI 10.1145/3386569.3392371](https://doi.org/10.1145/3386569.3392371).
The old Pixar PDF address redirects to a library page. The complete nine-page
paper was obtained from the current library's publication index:
[Pixar author PDF](https://research.pixar.com/docs/2020.SiggraphPapers.J.pdf).
Sections 3–6 were read, including regularization and the accuracy limitations.

The implemented interpolant is equation 14:

```
x_render(X) = sum_i beta_i x_i + 1/2 sum_i beta_i F_i (X - X_i)
```

For each reference vertex, adjacent cell centroids define `r_k` and unit vectors
`u_k`. Equations 24–27 give `A = sum u_k u_k^T`, `b = -sum u_k`,
`(A + I) lambda = b`, and normalized weights proportional to
`(1 + lambda dot u_k) / |r_k|`. We use the paper's epsilon = 1. The vertex
reconstruction weights and inverse reference bases are precomputed; frame
updates reconstruct gradients, transfer positions and recompute triangle normals.
This is C0 interpolation. Third-order accuracy requires suitably accurate vertex
gradients; sparse/boundary neighborhoods do not generally satisfy that condition.
It is not higher-order FEM and does not improve the underlying contact solve.

Pitoco keeps this data in independently owned `embedding/Render` allocations;
`mesh-cache/Cache` and historical simulation data retain their layouts. The caller
must keep the immutable source alive. Every render point has a containing cell
and barycentric weights. Binding rejects nonfinite/outside points (dimensionless
boundary tolerance 1e-12). No negative-weight extrapolation beyond that tolerance
is used. Generic point binding alone does not establish triangle containment
across cavities in a nonconvex source; that validation remains required for
arbitrary authored render assets.

The first viewport adapter creates a 1,106-point / 2,208-triangle sphere only
when the source boundary passes spherical and convex checks. Its radius is
99.9% of the minimum center-to-boundary-plane distance. This makes its rest
geometry fit inside the current inscribed simulation polyhedron; the resulting
inset is reported in the UI. This preview is deliberately distinguishable from
the measured contact boundary. A future enclosing simulation cage authored from
the detailed asset is needed to reduce that mismatch. A Phong preview crossing
a body's enabled ground plane uses the linear barycentric interpolant for that
body/frame, with a visible method count. The measured mesh is used if linear
transfer also fails. No individual vertex is clamped. This does not yet
certify every embedded triangle or inter-body contact at every interpolated time.

A `[V]` viewport button/key switches the two representations on the same cached
frame. The worker, velocities, FEM particles, contact forces and cache cursor
are unaffected. Render allocations are retired on cache revision changes and
application shutdown. Generic authored binding, velocity transfer, normal
attribute transfer and nonconvex primitive coverage are still open work.

### Final BDF2 rod refinement

`build/bdf2-rod-256-study.edn` completed with 256 axial cells, Tc/16384 maximum
step and 31.25 nm barrier distance, maintaining the physical 2 micrometre gap.
Force-pulse relative L1 error is **1.026435%**; rebound-speed error is **0.769167%**;
mechanical energy loss is **0.303289%**. Four trials were rejected; minimum J is
0.99958619 and momentum-balance residual is -6.0893e-11 N s. Wall time: 746.09 s.
The stated 1% force target **fails**; the rebound target passes. The five-case
comparison now verifies matching solver identities, references and sample times.
No extra run was launched merely to cross this threshold, and this benchmark
does not certify the ball, shell or future multiphysics workloads.

### Separating rod discretization errors (2026-09-13)

The previous 32/64/128/256 sequence changed axial resolution, timestep and barrier
clearance together. Its decreasing error is useful evidence for that sequence,
but does not identify a spatial order, temporal order or dominant error source.
Its Cartesian cell aspect ratio also grows from 6.4 to 51.2 because the transverse
cell size remains 20 mm. Aspect ratio alone does not establish the cause of the
observed contact oscillations.

Research informing the next experiment:

- [SideFX FEM solve methods](https://www.sidefx.com/docs/houdini/finiteelements/solvemethod.html)
  recommends global nonlinear solving for new projects. Pitoco already assembles
  nonlinear material/contact forces and uses a global Newton solve; calling a
  solver implicit does not establish impact accuracy.
- [SideFX mesh guidelines](https://www.sidefx.com/docs/houdini/finiteelements/geometry.html)
  calls for inspecting mesh quality and avoiding small angles/irregular elements.
  This motivates measuring cell dimensions and section distortion independently
  of rendering resolution.
- [SideFX collisions](https://www.sidefx.com/docs/houdini/finiteelements/collisions.html)
  describes bounded soft collision forces and surface continuous detection.
  Pitoco's IPC barrier is a different contact formulation; it must be qualified
  on its own numerical evidence rather than presented as Houdini's algorithm.
- [Dabaghi et al., weighted mass redistribution](https://arxiv.org/html/1601.00778v1),
  introduction and sections 3–4, explains why contact-node inertia and time
  integration can cause artificial oscillations. Their mass redistribution
  preserves total mass while removing contact-node inertia, and is studied for
  a one-dimensional Signorini problem with a clamped end. This is a candidate
  research direction, not evidence that modifying masses in our three-dimensional
  IPC problem would be correct. No masses were changed.
- [Bleyer's P1 mass-lumping derivation](https://comet-fenics.readthedocs.io/en/latest/demo/tips_and_tricks/mass_lumping.html)
  gives row-sum mass as element volume divided by its vertex count. Pitoco's
  rho*volume/4 tetrahedral contribution agrees with that rule; this does not
  make its wave dispersion or contact response exact.

The benchmark now saves maximum axial velocity and height spread across each
material cross-section, transverse speed/kinetic energy, and the endpoint
resultant of the contact gradient. These are sampled diagnostics, not continuous
extrema. The endpoint force is distinct from the method-consistent impulse
divided by the output interval used in the existing force-pulse error.
Manufactured planar and warped fields verify section indexing and mass-weighted
energy. The comparison helper rejects changes to multiple controls, physical
parameters, solver identity or output times. Baseline and variants hold physical
gap, material, speed, output cadence and tolerances fixed, changing only timestep,
clearance, transverse divisions or axial divisions. No visual playback changes
are part of this headless qualification.

The first isolated run is preserved as a failure in
`build/rod-isolated-controls-study.edn`. Its baseline reproduces the earlier
32-cell result exactly. Halving only the timestep fails at accepted time
0.0062751447318876614 s, leaving 8.966785647324116e-15 s to the requested output.
That remaining interval is much smaller than the smallest accepted step
(1.9301011109426144e-7 s). Newton exhausts its budget with velocity residual
1.1807295085767832e-8 m/s against the unchanged 1e-9 m/s tolerance.

This identifies an output-boundary scheduling defect separately from impact
accuracy. [PETSc's TSAdapt source](https://petsc.org/release/src/ts/adapt/interface/tsadapt.c.html)
explicitly handles this class of problem: MATCHSTEP adjusts the final steps to
avoid an unreasonably small last interval. Pitoco now uses the same principle:
if the remaining interval fits in one permitted step, take it; if it lies between
one and two permitted steps, split it equally; otherwise take the permitted step.
Pitoco keeps a strict step cap, including BDF2's growth cap, rather than using
PETSc's optional small increase. The remaining physical interval is integrated;
the existing roundoff threshold and nonlinear tolerance were not increased.
The solver fingerprint records the new boundary policy. Regression covers
near-integer output boundaries, all three integrators, exact reported end time,
bounded minimum step and ballistic displacement.

With that fix, all five cases complete in
`build/rod-isolated-controls-fixed-study.edn`. Regression passes 30 tests / 415
assertions, including the original BDF2 output-partition and rejection-history
checks. The comparison verifies identical solver identity, physical reference
and output times; each variant changes exactly one setting.

| Changed control | Force-pulse L1 error | Rebound error | Mechanical energy loss |
| --- | ---: | ---: | ---: |
| Baseline: [1 32 1], Tc/2048, 250 nm | 31.0446% | 3.5032% | 2.4089% |
| Timestep Tc/4096 | 49.3267% | 2.8546% | 1.5770% |
| Clearance 125 nm | 12.1252% | 3.5778% | 2.5159% |
| Transverse mesh [2 32 2] | 20.0819% | 3.5055% | 2.4273% |
| Axial mesh [1 64 1] | 5.2287% | 1.9953% | 1.0659% |

The inspected plot is `build/rod-controls-comparison.png` (also PDF/CSV), with
verification in `build/rod-controls-comparison.edn`. No case passes the force or
rebound 1% targets. The scheduling fix changes some adaptive step sequences;
its benefit is completing the formerly failing solve, not uniformly reducing
physical error. The old baseline and new baseline must not be mixed as the
same solver version.

These experiments isolate sensitivities, not a unique cause or convergence
order. Transverse refinement alone does not remove section velocity variation.
Halving clearance lowers the output-averaged force error but raises the maximum
sampled endpoint force from 4.16 to 8.94 times the reference. Therefore the next
force audit must measure accepted-step force/impulse histories and output-bin
averaging independently. A lower binned L1 metric can hide short force spikes;
it must not be used alone to promote a contact formulation or integrator.

The optional rod setting `:audit-forces? true` now observes each accepted native
step through `advance!` with `:maximum-attempts 1`. It records accepted begin/end
times, the difference in cumulative method-consistent contact impulse, and the
endpoint contact-gradient resultant. Rejected attempts do not contribute records.
A failure retains the accepted prefix of its current output interval alongside
the existing completed output samples. Observation does not recompute forces or
modify the accepted state.

For accepted step k, let e_k = I_k - I_ref,k, where I_ref,k is the exact integral
of the analytical pulse over that step. The audited metric is sum(abs(e_k))/I_ref.
The output-bin metric instead sums abs(sum(e_k)) within each bin. By the triangle
inequality it can be smaller when positive and negative force errors cancel.
Both metrics, their difference, endpoint and step-average peaks, accepted-step
count, time coverage and impulse reconciliation are retained. These remain
discrete measurements; neither a sampled maximum nor this norm bounds continuous
contact force between accepted states.

The two 32-cell audits complete in `build/rod-accepted-force-study.edn`:

| Clearance | Accepted steps | Step-average force L1 | Output-bin force L1 | Maximum accepted endpoint / reference |
| --- | ---: | ---: | ---: | ---: |
| 250 nm | 3,138 | 53.7382% | 31.0446% | 7.0805 |
| 125 nm | 3,296 | 28.7777% | 12.1252% | 10.1023 |

The observer tests pass 11 tests / 127 assertions, including a manufactured
alternating force whose error vanishes when averaged into one bin, rejected-step
exclusion, failure-prefix retention and exact native output equality with audit
enabled/disabled. Independent comparison to the earlier 32-cell runs verifies
identical solver/physical settings, saved observables and summaries. One earlier
contact-energy report differs by one ULP; all other report fields match exactly.
Time coverage errors are zero and impulse mismatches are below 9e-19 N s.
The verifier is `build/export-force-audit.clj`; results are in
`build/force-audit-verification.edn`. The inspected full-impact and early-contact
plot is `build/force-audit-comparison.png` (also PDF; CSV step/bin data retained).

This provides evidence for the force-oscillation concern discussed in
[Convergent IPC section 8.2](https://arxiv.org/html/2307.15908v1#S8.SS2), but does
not identify a unique cause in Pitoco. The next comparison keeps clearance and
timestep fixed while refining the axial mesh, then changes only timestep on the
finer mesh. Transverse resolution stays fixed, so this cannot establish general
three-dimensional mesh convergence.

The audited axial/time comparison also completes. All cases keep clearance at
250 nm, material, physical gap and output cadence fixed:

| Axial cells | Maximum timestep | Accepted-step force L1 | Output-bin force L1 | Rebound error | Energy loss |
| --- | --- | ---: | ---: | ---: | ---: |
| 32 | Tc/2048 | 53.7382% | 31.0446% | 3.5032% | 2.4089% |
| 64 | Tc/2048 | 14.1175% | 5.2287% | 1.9953% | 1.0659% |
| 128 | Tc/2048 | 3.2725% | 1.6015% | 1.1557% | 0.5817% |
| 128 | Tc/4096 | 13.5774% | 3.3025% | 1.1574% | 0.4708% |

The last three runs took 55.01 / 50.07 / 147.79 seconds, respectively.
`build/audited-mesh-time-verification.edn` verifies matching solver and experiment
fingerprints, physical reference and output times, exactly one changed control
between adjacent rows, full accepted-time coverage and reconciled impulses.
The source study is `build/rod-audited-mesh-time-study.edn`; no case qualifies.
The timestep sensitivity of force remains large despite nearly unchanged rebound.
Further time refinement alone is not justified as an accuracy fix. An independent
1D bar comparison can isolate contact-boundary inertia and spatial effects before
attempting a production contact/mass-discretization change. The weighted mass
redistribution literature discussed above provides a candidate comparison, not
authorization to treat a 1D formulation as a validated 3D replacement.

### Independent contact-boundary reference (2026-09-13)

`tools/verify.py rod` independently assembles a uniform, linear P1 bar:
length 0.1 m, area 0.0004 m², Young's modulus 1 MPa, density 1000 kg/m³,
incoming speed 0.01 m/s and initial gap 2 micrometres. It uses a free far end,
zero damping and a rigid frictionless obstacle. Its characteristic-wave reference
has force rho*c*v*A for 2L/c after impact. This is a different boundary-value
problem from the clamped example in
[Dabaghi et al.](https://arxiv.org/html/1601.00778v1).

The comparison retains total mass 0.04 kg. Standard variants use row-sum lumping
or the consistent element mass matrix. Redistributed variants use the paper's
element weights w0=0, w1=2 and all remaining weights 1, then row-sum lumping.
The zero-inertia boundary is eliminated through static force balance. One variant
retains the same independently reconstructed IPC physical squared-distance
barrier (250 nm clearance, 1000 Pa parameter); the other imposes hard Signorini
contact. The element weighting is not arbitrary deletion of boundary mass.

The dimensionless ODE uses SciPy DOP853, splitting integration at the analytical
pulse edges to integrate absolute force error. Massive boundary positions use
a logarithmic coordinate to preserve positive trial gaps. No native Pitoco
solver or generated solver code is imported. Five self-check groups cover mass
and free translation, barrier gradients, static boundary balance, reduced energy
gradients and analytical lumped/consistent free-bar eigenvalues.

The first refinement pair (rtol 1e-10/1e-12, maximum steps L/(32Nc)/L/(64Nc))
failed at the 32-cell lumped model: rebound changed by 2.18e-7 and common-time
force by 0.00148 of the plateau. Its failed record remains in
`build/independent-rod-reference-verified.json`. Keeping the same acceptance
thresholds, the tighter pair (rtol 1e-12/2.5e-14, steps L/(64Nc)/L/(128Nc))
passes all 12 comparisons. Maximum differences: integrated force error 2.13e-7,
rebound ratio 2.72e-9, common-time force ratio 1.63e-5. The 12 fine runs have
sampled relative energy drift below 3e-12.

| Cells | Lumped + barrier force L1 | Consistent + barrier | Redistributed + barrier | Redistributed + hard contact |
| ---: | ---: | ---: | ---: | ---: |
| 16 | 178.4113% | 170.2588% | 8.6524% | 9.0127% |
| 32 | 167.7921% | 156.2931% | 4.7726% | 5.0768% |
| 64 | 152.1767% | 134.7190% | 2.5850% | 2.7741% |

The large force errors persist after tightening integration; the full consistent
mass matrix alone does not remove them. Redistributing mass reduces the error
under the unchanged barrier law, without dissipative integration explaining the
result. The 64-cell redistributed/barrier rebound error is still 1.1668%, so
neither force nor rebound meets the 1% target. Peaks are sampled diagnostics,
not continuous bounds. This identifies a mechanism in this 1D model; nonlinear
3D tetrahedra, transverse motion and surface contact quadrature remain distinct.

Completed evidence: `build/independent-rod-reference-tight.json` (24 cases, 12
refinement comparisons, NumPy/SciPy versions and matching source SHA), its log,
and `build/independent-rod-reference-comparison.png` / PDF. The figure is generated
by `build/plot-independent-rod-reference.py` from completed fine-run traces.

For a possible multidimensional extension,
[Monjaraz Tec, Gross and Krack](https://arxiv.org/pdf/2111.07693), section 2,
combines static boundary equilibrium with interior dynamics and discusses
massless component-mode synthesis. Its development explicitly assumes linear
elasticity and linear, fixed contact kinematics; nonlinear materials and moving
contact directions are future work there. It therefore supplies a comparison
architecture, not a justified replacement for Pitoco's large-deformation balls.
No production solver, body masses or live cache changed in this experiment.

### Native inertia audit and 3D redistribution feasibility (2026-09-13)

Further research reviewed
[Tkachuk's variational mass-matrix thesis](https://elib.uni-stuttgart.de/server/api/core/bitstreams/8c7605a1-9e25-45ee-a874-617637706589/content),
sections 4.3.3, 5.1 and 5.2.4. It requires compatibility/rank conditions for
singular matrices and distinguishes rigid-motion consistency from preservation
of total mass. Its illustrated 3D singular element uses modified 27-node
interpolation with separate velocity/momentum spaces, not the current four-node
tetrahedron. This rules out treating a change to scalar nodal weights as an
implementation of that formulation.
[Ligurs and Renard's frictional-contact study](https://doi.org/10.1093/qjmam/hbr004) also
distinguishes normal and tangential inertia; a frictionless 1D result cannot
justify removing both indiscriminately.

The new AguaFria `impact-study/inertia-audit` integrates current position and
velocity fields over reference tetrahedra. It uses material mass rho0*V0 and
the exact P1 product integral V0*(1+delta_ij)/20. The calculation returns both
integrated and actual nodal mass, inertia, kinetic energy and linear/angular
momentum. Tensors and angular momentum share the integrated center as origin;
local coordinates avoid subtraction of large world-coordinate second moments.
No simulation storage is modified. `audit-inertia!` wraps authored solid data
and records the native implementation identity. Rod impact reports now retain
the audit before and after integration.

Analytical tests cover a uniform simplex with spin and translation, doubling
its current size without changing material mass, a translated origin at 2^25 m,
and a sheared box. The tetrahedron exposes the expected factor-five nodal versus
integrated inertia about its center. The full focused suite passes **14 tests /
241 assertions**, including force-audit trajectory preservation. Evidence:
`build/inertia-audit-radius-tests.log`.

For the actual ball source, radius 0.15 m, density 1100 kg/m³ and a prescribed
2 rad/s rigid spin, the tensor Frobenius errors are:

| Nodes / tetrahedra | Fixed polyhedron | Refined spherical boundary |
| --- | ---: | ---: |
| 43 / 80 | 36.6898% | 36.6898% |
| 205 / 640 | 9.82356% | 10.2889% |
| 1209 / 5120 | 2.84203% | 3.05225% |

These compare two mass discretizations on each identical represented domain;
they do not include error relative to a geometrically exact sphere. The fixed
polyhedron's integrated inertia stays constant through refinement, while its
nodal inertia approaches it. Source data, native identity and all measurements
are retained in `build/ball-inertia-study.edn`. The independent Python verifier
uses four-point degree-two quadrature at physical points, rather than the native
algebraic sum formula. All six cases pass at rtol 1e-11 / atol 1e-12:
`build/ball-inertia-verification.json`, `build/verify-ball-inertia.py`, and the
points/cells/metrics CSV files.

There is also a simple necessary feasibility test for a particular proposed
replacement: make every surface node massless and assign positive scalar masses
only to interior nodes, while retaining total mass M, center c and inertia I.
It must satisfy trace(I)/(2M) <= max_interior |x-c|². For the spherical sequence,
required mean squared radii are 0.0123455 / 0.0131944 / 0.0134225 m², while the
largest available interior squared radii are 0 / 0.005625 / 0.01265625 m².
**Every spherical level fails this bound.** The fine fixed-polyhedron case passes
the necessary bound, which does not prove a full moment-preserving redistribution
exists. `surface-massless-radius-bound` makes this distinction explicit.
The Clojure check retains matching native implementation identities in
`build/ball-inertia-radius-bound.edn`; an independent face-incidence/radius check
agrees in `build/ball-inertia-radius-verification.json`.

This rejects a scalar, all-surface transfer on these meshes. It does not reject
normal-only inertia changes, an explicitly restricted contact patch, mixed
interpolation or a non-diagonal operator. Those require their own nonlinear
energy/force derivation and rigid-motion checks. Production masses and the live
cache remain unchanged. The next contact implementation must address this
operator/interpolation choice rather than copy the 1D weight rule.

### Coupled tetrahedral inertia implementation (2026-09-13)

The implicit context now has an experimental consistent-mass option. For a P1
tetrahedron its scalar element matrix is rho0*V0*(I+11^T)/20, independently
applied to each spatial component. Native AguaFria code applies the matrix
without assembling it densely. A Jacobi-preconditioned CG solve computes its
inverse action; the element inequality Ml/5 <= Mc <= Ml bounds the preconditioned
condition number by five. The option verifies uniform-density nodal row sums
before reconstructing each body's reference density. It cannot be switched
after an accepted step.

The objective uses 1/2*delta^T*M*delta, its gradient uses M*delta, and nonlinear
convergence measures the inverse-mass-scaled velocity residual. Newmark computes
its initial force acceleration using the same inverse action; BE/BDF2 retain
their applicable affine predictors. Sparse assembly adds the local Mc-Ml
correction after elastic Hessian projection, through the existing native block
interface. No C++ implementation was added. Context observables integrate kinetic
energy with Mc; mass, center, linear momentum and uniform-gravity potential use
the unchanged row sums. The frequency bound is conservatively multiplied by five.

The first mixed-density inverse test exposed a weakness in stopping on a global
weighted residual alone: maximum component-relative solution error was 1.48e-8,
failing the unchanged 1e-9 target. Adding the
[LAPACK componentwise backward-error check](https://www.netlib.org/lapack/explore-html/db/d65/group__la__lin__berr_ga56eebc95b5d984d77c0dc2e444e98e6a.html)
fixed that manufactured test, but the rod exposed why it was the wrong universal
acceptance criterion. The original failure logs remain available.

First, transverse and vertical gradient maxima differed by about 1e14. The
inverse action now normalizes its RHS independently per body and spatial axis
by max_i(|rhs_i|/sqrt(Ml_ii)). These are independent scalar blocks, so this
transformation preserves the equations. It is not automatically applicable to
a future direction-coupled operator. A regression repeats the actual first-step
rod configuration instead of relying only on positive manufactured loads.

Second, a later iteration failed the componentwise 1e-14 check at a zero-load
node, even after scaling. Independent SciPy Cholesky factorization of the
exported mass matrix found weighted relative solution errors below 6.68e-16
on all axes. The zero-load node's componentwise backward error was 6.85e-12,
while the corresponding block's relative residual was 2.53e-16. Evidence:
`build/consistent-mass-failure-independent.json`, its CSV inputs and
`build/verify-mass-failure.py`. Continuing CG to resolve such relative tails
was not a useful measure of the accuracy required by the nonlinear solve.

The final acceptance criterion recomputes the true residual and requires
||r||_(Ml^-1)/||rhs||_(Ml^-1) <= 1e-12 separately for every body and axis,
including after rescaling to the original equations. Values are normalized
before squaring. Since the symmetrically preconditioned mass matrix has
spectrum in [1/5,1], this yields a relative Ml-weighted solution-error bound
of 5e-12 in exact arithmetic. This follows the relationship between residual,
conditioning and forward error described in
[Templates for the Solution of Linear Systems, stopping criteria](https://www.netlib.org/templates/templates.html).
It replaces the componentwise acceptance rule; that routine remains available
as a diagnostic. The iteration cap remains 64 and the weighted residual
tolerance remains 1e-12. Finite-precision arithmetic and model discretization
still need the independent regression and physical benchmark checks.

This operator addresses the measured nodal inertia approximation and supplies
coupled-mass infrastructure. It does not remove contact-boundary inertia. The
independent 1D results already show that consistent mass alone does not cure
contact-force spikes. Contact qualification and a suitable mixed formulation
remain separate requirements.

The controlled native rod pair now completes with identical authored geometry,
material, integration, clearance, timestep cap, solver identity and output times;
only `:mass-model` changes. The lumped initial and sampled observables match the
prior audited baseline exactly. Final consistent kinetic energy agrees with the
independent element-integral audit within 8.48e-22 J, and accepted-step impulses
reconcile with output impulses within 1.09e-18 N s. Regression: **47 tests /
916 assertions**, no failures or errors.

| Measured quantity | Lumped | Consistent |
| --- | ---: | ---: |
| Accepted-step force L1 error | 53.7382% | 16.3821% |
| Output-bin force L1 error | 31.0446% | 9.3599% |
| Rebound-speed error | 3.5032% | 2.2224% |
| Mechanical energy loss | 2.4089% | 1.1025% |
| Accepted endpoint peak / analytical plateau | 7.0805 | 4.6573 |
| Rejected trials | 1 | 30 |
| Wall time on this run | 29.11 s | 87.00 s |

This is sensitivity to the inertia model at fixed numerical controls, not a
convergence study. The smaller errors come with increased cost and do not meet
the physical qualification target. Evidence:
`build/consistent-mass-scaled-rod-study.edn`,
`build/consistent-mass-study-verification.edn`,
`build/consistent-mass-block-norm-regression.log`.

The resulting force histories are exported as CSV and plotted in
`build/consistent-mass-comparison.png` / PDF; both the full impact and early
contact are shown, retaining accepted-step spikes that output averaging hides.
The plot was visually inspected. The guarded live reload succeeds at revision
3 and retains 145 frames / cursor 72. Its backend and mass-policy metadata
match the study. Two Vulkan readbacks match the previous RGB hash exactly.
The standalone executable and Pitoco.app bundle build successfully without
opening another GUI. See `build/consistent-mass-live-reload.edn`,
`build/consistent-mass-live-captures/`, `build/consistent-mass-standalone-build.log`
and `build/consistent-mass-package.log`. The historical cache is not rebaked
or relabelled by the reload. The authored UI continues to use the default
lumped operator; consistent mass is an explicit benchmark/native-context option.

Earlier output-boundary-fix delivery checks: the standalone executable and macOS
bundle build successfully;
the controlled reload reports the new boundary policy while retaining revision
3, 145 cached frames and cursor 72. Two subsequent tick-72 captures are identical
and match the previous preview RGB hash. See
`build/output-boundary-live-reload.edn`, `build/output-boundary-live-captures/`,
`build/output-boundary-standalone-build.log` and `build/output-boundary-package.log`.
The existing cached trajectories remain historical results from their original
solver; reloading does not rebake or relabel them as new physics results.

The historical 145-frame cache exposed unconstrained Phong floor excursions of
0.4694 / 0.1082 / 0.4055 mm for the three bodies, despite its nonpenetrating FEM
boundary. This is why the floor fallback is required. The sphere's rest inset is
2.8103 mm. These are display-transfer measurements, not changes to the physics.
A manufactured two-tetrahedron shear reproduces the overshoot with all physical
vertices at or above the floor, and verifies exact linear fallback.

Surface emission now accepts an explicit vertex capacity and preflights all
bodies before writing. The short-buffer check uses a one-vertex sentinel and
requires zero emitted vertices with unchanged memory. The initial isolated
integration harness had allocated only 8,192 vertices for a 13,248-vertex preview
and crashed (`build/hs_err_pid18901.log`); its corrected allocation is 32,768, and the
production caller supplies the actual 524,285 slots after its background triangle.
The running GUI was not used for this failing test. Code/test files must remain
unchanged while a process is loading them to avoid mixed source revisions.

Final embedding verification passes 9 tests / 124 assertions and the standalone
build. The current viewport uses this code in the original JVM/window, preserving
revision 61, 145 frames and cursor 72. The linear fallback activates on 4 / 1 / 6
frames; minimum displayed heights are 0.0770 / 0.0556 / 0.0145 mm. Native Vulkan
captures at ticks 100 and 72 reproduce identical pixels on repetition. Evidence:
`build/embedding-live-inspection.edn`, `build/embedding-live-reload.edn`,
`build/embedding-visual-comparison.edn`, `build/embedding-final-captures/`.

The remaining sharp lighting band has a separate candidate cause in
`shadeBall`: the environment is sampled once in the mirror direction, with a
hard floor/sky branch, even though the BRDF has nonzero roughness. Proper
specular environment lighting integrates the BRDF over incident directions;
roughness should broaden that response. See the primary
[Filament reference, specular IBL integration and sampling](https://google.github.io/filament/main/filament.html#lighting/imagebasedlights/distantlightprobes/specularbrdfintegration).
The rough specular environment correction is now implemented as described below;
improved mesh shadows remain open. The same-frame comparison confirms that the
underside band is not fully explained by that one-mirror-direction term.


### Rough specular environment integration (2026-09-13)

Primary source: Eric Heitz, *Sampling the GGX Distribution of Visible Normals*,
JCGT 7(4), 2018, [complete paper](https://jcgt.org/published/0007/04/01/paper.pdf).
Sections 1–5 and appendices A/B were read. The shader transforms the view into
an ellipsoid's hemisphere configuration, samples its projected area, and maps
sampled normals back to GGX. Reflection gives
`pdf(L) = G1_Smith(V) D(H) / (4 NoV)` and estimator weight
`F G_Schlick(V,L) / G1_Smith(V)`. The existing approximate Schlick BRDF geometry
term is retained, so cancelling it against the different exact Smith sampling
term would be incorrect. Cancelling the common `NoV` algebraically keeps their
ratio finite at grazing angles without altering the sampled view direction.

The final shader uses 128 deterministic Hammersley samples. Rejected reflected
rays below the normal hemisphere contribute zero. The lower world hemisphere
is a constant distant ground proxy; the upper hemisphere is the procedural
studio environment. The viewport grid is excluded from this lighting proxy.
This is not scene ray tracing. Direct-light/material constants remain as before
for comparison; their calibration, the diffuse ambient approximation, consistency
between direct/indirect Fresnel parameters, and inter-body occlusion remain work.

`tools/verify.py lighting` compares a CPU implementation of the estimator
with independent tensor Gauss-Legendre hemisphere quadrature. Reflection symmetry
lets the reference integrate azimuth on [0, pi]; quadrature nodes cluster near
the opposite-view direction and resolve the narrow grazing lobe. Uniform azimuth
quadrature at the original resolution was insufficient there and was replaced.
The 256-to-512 quadrature refinement changes the reference by at most 2.04725e-7
relative across 36 cases: roughness 0.26 / 0.49 / 0.8, NoV 0.001 / 0.01 / 0.05 /
0.15 / 0.5 / 1, and Fresnel F0 0.055 / 1. Maximum reference reflectance is
0.985857; single-scattering GGX may lose energy, so unity is an upper bound.

The prior 128-sample NDF estimator reaches 21.2151% relative error over eight
view azimuths. Visible-normal errors at 64 / 128 / 256 / 4096 samples are
1.71908% / 0.950809% / 0.416856% / 0.0165049%. The bounded 128-sample acceptance
is 1% on this grid, with reference refinement below 1e-6. It passes. This does
not establish error bounds for arbitrary roughness, colored/high-frequency
lighting, or actual GPU arithmetic. Both checker and shader hashes are recorded
in `build/environment-integration-final.json`.

GLSL compilation and SPIR-V validation pass. The existing window uses the new
pipeline with cache revision 61 unchanged. Captures of ticks 100 / 72 / 100 / 72
repeat pixel-exactly (`build/environment-vndf-captures/verification.edn`). A
normals-only diagnostic was captured separately and the production shader
restored. The underside lighting band is still visible in the production image;
the direct-only diagnostic reproduces it while the specular-environment-only
capture does not (`build/environment-direct-diagnostic/` and
`build/environment-indirect-diagnostic/`). This identifies the responsible lighting
contribution, not a proof that all normal transfer is correct. Finite emitters and
bounced illumination are the next lighting work. Hard projected mesh shadows also remain. This is a validated improvement to one
lighting term, not a claim of finished rendering or simulation accuracy.


The standalone build passes (`build/environment-standalone-build.log`) and
produces `build/pitoco` and `build/Pitoco.app`; neither was launched. The
production SPIR-V was restored after diagnostic captures and its hash matches
the validated candidate. Evidence hashes are in
`build/environment-final-provenance.json`.


### Finite studio emitters and multiple importance sampling (2026-09-13)

Primary references read: PBRT fourth edition,
[12.4, Area Lights](https://pbr-book.org/4ed/Light_Sources/Area_Lights), and
[2.2.3, Multiple Importance Sampling](https://pbr-book.org/4ed/Monte_Carlo_Integration/Improving_Efficiency).
A uniform disk sample has area density `1/A`; its direction density at the
receiver is `distance² / (A cos_emitter)`. For equal sample counts, balance MIS
sums two sample means of `f_r NoL / (pdf_area + pdf_GGX)`, one sampled on the
emitter and the other from the GGX visible-normal distribution. A reflected ray
must hit the emitting side of the disk. Below-surface, behind-emitter and missed
samples contribute zero. This handles finite illumination extent, not occlusion.

`mesh.frag` implements that integral with 128 samples per technique per light.
The shared `visibleHalfVector` function also serves environment reflection.
The two preview disks are centered at (-3,12,4) and (4,3,-3), face the origin,
and have radii 4 and 2 metres. Their radiance is fixed by the on-axis relationship
`E_reference = pi L R² / (d² + R²)`, using the previous RGB light strengths as
reference irradiances. A moving receiver therefore sees proper distance and
emitter-orientation changes. These are explicit studio choices, not calibrated
real lamps. The old ambient term, distant environment proxy and hard projected
ground shadows remain; direct/emitter visibility and bounced illumination are
not solved. No simulation cache or physical collision data was changed.

The existing `tools/verify.py lighting` now has `--area-lights`. It compares
uniform-area, balance-MIS and power-MIS estimates against independent polar disk
quadrature (Gauss-Legendre in squared radius, uniform azimuth). The 108 cases
cover roughness 0.26 / 0.49 / 0.8, normal tilt 0 / 60 / 85 / 100 degrees, NoV
0.15 / 0.5 / 1, and distance/radius pairs (4,2), (13,4), (sqrt(34),2), with eight
rotations for the sample estimates. The 128-to-256 reference refinement has
maximum absolute difference 1.1181e-6. Analytical coaxial irradiance checks at
four distances agree within 2.89e-13 relative; a reversed emitter contributes zero.

The 128-sample uniform-area estimator reaches 34.401% relative error in this
sweep. Balance MIS at 128 samples per technique reaches 8.149%; these do not
have equal sample cost. At equal 256 total samples, uniform-area maximum error
is 10.992%. Balance MIS at 512 per technique reaches 3.921%; power MIS reaches
2.622% there. The strict 1% sampling target FAILS. This is retained in
`build/area-light-final-check.json`; `--area-lights --check` exits 1. The analytical
formula checks pass, and the previous 36 constant-environment checks still pass
(`build/environment-area-regression.json`). A broad integration-accuracy claim
would be incorrect; progressive/refined rendering remains needed.

Actual Vulkan captures also compare 128 / 512 / 2048 samples per technique on
the same two frames. Body masks come from the normals-only versus shaded image
at identical geometry, camera and tick. At tick 100, the 128-to-2048 difference
has mean absolute error 0.13644 RGB8 levels, 99th percentile 1 and maximum 5
across 173,312 body pixels. Tick 72 gives 0.13647 / 1 / 4 across 223,107 pixels.
The 512-to-2048 means are 0.04786 / 0.04815, maxima 2. These are tone-mapped,
quantized image differences, not linear-radiance truth. The 2048 result itself
is a finite estimate. See `build/area-light-gpu-density.json` and the respective
capture directories. The production 128-sample shader was restored afterwards.

The native viewport now shows broader highlights and smoother underside shading.
Repeated production captures at ticks 100 / 72 match exactly. Cache revision 61,
145 frames and restored cursor 72 are preserved. GLSL compilation, SPIR-V
validation and standalone build pass (`build/area-light-standalone-build.log`);
no extra app was launched. Source/binary/evidence hashes are in
`build/area-light-final-provenance.json`. Hard shadows, light transport, authored
lighting controls and contact/render transfer qualification remain before calling
the ball-rendering milestone complete.


### Native construction for shared triangle visibility (2026-09-13)

The existing contact surface already owns triangle points, indices, a refittable
binary hierarchy and vertex adjacency. Its previous construction path is a
Clojure median split. The new `contact-mesh/build-hierarchy!` fills that same
layout entirely in AguaFria Zig, enabling standalone callers to construct it.
The original host `build!` remains unchanged while the renderer is connected.

Primary source read: PBRT fourth edition,
[7.3, Bounding Volume Hierarchies](https://pbr-book.org/4ed/Primitives_and_Intersection_Acceleration/Bounding_Volume_Hierarchies),
especially construction and the bucket surface-area heuristic. Pitoco uses 12
buckets on the longest bounding-box-centroid axis. Prefix/suffix scans select
the minimum sum of child surface area times triangle count. Single-triangle
leaves preserve the existing Surface layout and its `2 * faces - 1` capacity.
Coincident centroid bounds use an index split. Starting at depth 32, equal-count
splits cap worst-case total depth at 49 for 80,000 faces; this fits the existing
64-entry closest-point traversal stack. Preorder child offsets follow subtree
sizes, allowing the existing reverse-order refit to operate unchanged.

The builder checks counts, finite coordinates bounded by 1e50 and every vertex
index before topology or adjacency writes. Repeated indices and zero-area
triangles are accepted for unsigned geometry infrastructure, not certified as
closed contact solids. Failure means the supplied geometry must not be queried
until restored or rebuilt successfully. Success fills leaves, parent/child links,
vertex incident offsets/indices, and bottom-up bounds. Temporary work is owned
and freed; existing Surface and TreeNode layouts remain unchanged.

The isolated regression passes 21 tests / 348 assertions. New checks cover tree
coverage and adjacency; deterministic rebuilding; 400 closest-point comparisons
against existing convex/concave surfaces; incremental versus full refit;
nonfinite/out-of-range coordinate and index rejection before topology writes;
and allocation capacity. The 80,000-face coincident-centroid case reaches depth
17. A deliberately skewed 1,000-face distribution reaches depth 42 and exercises
the depth cap. Evidence: `build/native-hierarchy-tests.log`.

An existing comparator `geometry/edge-less?` also required private visibility:
its public development bridge has C calling convention, while Zig's sort expects
a native-ABI comparator. Making the local helper `az/defn-` fixes that mismatch.
The callback has no external caller. It does not change sorting semantics.

This construction work does not yet alter viewport shadows. Next, the renderer
needs a bounded storage buffer for triangle geometry/hierarchy, synchronized by
its existing single in-flight fence. Serialization must conservatively round
bounds around the same float32 points used for rendering. GPU ray traversal needs
edge-consistent intersections, self-intersection handling and actual emitter
visibility in both MIS techniques. PBRT's
[ray-aligned triangle intersection discussion](https://pbr-book.org/4ed/Shapes/Triangle_Meshes)
was reviewed for this next step; a robust GPU implementation is not yet present.
Cache revision, tick and measured/embedded mode must invalidate the geometry
packet. Projected floor shadows remain until the connected path is verified.


The full standalone application build passes
(`build/native-hierarchy-standalone-build.log`). A separate ReleaseSafe native
smoke executable invokes the 80,000-face construction/audit directly and exits
successfully. `otool -L` lists only `/usr/lib/libSystem.B.dylib`, with no JVM or
application host dependency. Its single end-to-end run took 0.135 s, including
allocation, construction, structural audit and destruction; this is not a
representative performance benchmark. Build/run evidence is in
`build/native-hierarchy-smoke-build.log` and
`build/native-hierarchy-smoke-result.json`. The original GUI and its simulation
cache were not used for the native tests or restarted.

## Displayed-triangle visibility (tested preview implementation)

The display hierarchy now has a bounded, owned GPU packet: a 16-word scene
header, per-body counts, 32-byte hierarchy nodes, float32 positions, and uint32
triangle indices. The serializer rounds lower/upper bounds outwards with
`nextAfter`, and rejects invalid data or insufficient space before writing a
body. The scene header is published only after all bodies succeed. Revision,
tick, measured/embedded mode, buffer pointer, and buffer capacity participate in
invalidation. This packet describes the displayed geometry, not a new simulation
or contact certificate.

The shared Vulkan renderer allocates coherent host-visible storage and a fragment
storage-buffer descriptor. Its existing single in-flight fence protects updates;
the following queue submission orders the host writes before shader reads, per
[Vulkan's host write ordering guarantees](https://docs.vulkan.org/spec/latest/chapters/synchronization.html#synchronization-submission-host-writes).
The descriptor and shader pipeline must be installed together. The shader loader
also checks for truncation and has a 256 KiB capacity; this shader exceeded the
previous 64 KiB limit.

Traversal uses a 64-entry stack with bounds checks and a node-count work limit.
Malformed addresses, indices, or exhausted traversal return an error that fails
dark. Slab intersection handles both signed zeros explicitly. The ray-aligned
triangle shear follows [PBRT 4e section 6.5](https://pbr-book.org/4ed/Shapes/Triangle_Meshes),
using symmetric compensated float32 products for shared edges. This is an
adaptation: it does not claim equivalence to PBRT's float64 edge fallback. GPU
regression cases are generated by `tools/verify.py visibility`, whose
reference uses independent float64 Moller–Trumbore algebra and exhaustive
triangle traversal, including optional snapshots of actual published packets.

Visibility is evaluated for each sample in both finite-emitter MIS techniques;
it is not a separate average multiplied into the lighting integral. The ground
uses a Lambertian area integral, with visibility inside that sum. A successful
packet disables the old projected shadow triangles. The old projection remains
a fallback when packet publication fails.

[PBRT's numerical-error analysis](https://pbr-book.org/4ed/Shapes/Managing_Rounding_Error)
explains why an arbitrary fixed ray epsilon can create acne or detached shadows.
Our primary positions still come from raster interpolation, so their error is
not the bounded ray-hit error used by PBRT. The present origin guard is
32 float32 epsilons times world-coordinate scale, along the geometric triangle
normal. **It remains a preview tolerance**, not a proof for arbitrary model
scales or tightly separated surfaces. Deriving raster-position error bounds,
checking thin gaps, shadow-sampling convergence, indirect mesh visibility and
physically consistent material parameters remain open work.

The first isolated integration regression passes 31 tests / 622 assertions,
including topology serialization, outward bounds, nonfinite/short-output
rejection, frame publication, capacity changes with the same pointer, cursor
changes, and measured/embedded topology switching. Evidence:
`build/gpu-visibility-regression.log`. Live/GPU results are recorded below only
after they are actually run.


The production Vulkan checks passed 230 cases: the 110 primitive cases plus 120
rays through all three actual display hierarchies, compared against exhaustive
float64 intersections of the serialized positions/triangles. The published packet
contained 145,756 words for this scene. Capture and oracle evidence:
`build/visibility-gpu-primitives.json`, `build/visibility-gpu-packet.json` and
`build/visibility-packet-72.bin`.

Floor samples were increased from 32 to 256 after inspection exposed banding.
One completed 1024-sample comparison at tick 100 measured mean absolute floor
RGB8 error 0.1620 -> 0.02949 and maximum 15 -> 4. It is a quantized display
comparison, not radiometric convergence (`build/visibility-floor-density.json`).
The dense candidate then aborted the development process after a Vulkan fence
wait returned failure. Disassembly resolves the failing call to
`vkWaitForFences`; the old assertion discarded the actual VkResult. A driver
workload timeout/device loss is suspected but not proven. The new error handler
prints the VkResult. The dense candidate is **rejected for live use**. Splitting
expensive rendering into bounded submissions and recovering device failures are
open requirements, rather than treating arbitrary sampling increases as safe.

The 256-sample production shader was restored. One replacement development JVM
(port 52800) restored all 145 cached frames from the previously verified export,
without integration. Positions and velocities came from the saved decimal
round-trip data; observables were re-evaluated. At ticks 100 and 72, the viewport
crop x=[450,1950), y=[320,1180) is byte-identical to the pre-crash production
capture. Repeated full images within the new revision 3 are also identical.
Evidence: `build/visibility-recovered-captures/verification.edn` and
`build/visibility-recovery-pixel-comparison.json`. This verifies recovery of the
visible state, not a new physical convergence result.

The final standalone application build also passes after the improved Vulkan
error reporting (`build/gpu-visibility-final-standalone-build.log`). Source,
binary and evidence hashes are recorded in `build/visibility-provenance.json`.

## Bounded raster submissions and presentation ownership

Sustained use of the 256-sample, untiled renderer also failed. The improved error
handler captured `VkResult -4` in `build/visibility-recovery-desktop.log`:
[`VK_ERROR_DEVICE_LOST`](https://docs.vulkan.org/refpages/latest/refpages/source/VkResult.html).
This contradicts any inference that the earlier short capture sequence proved
sustained stability. The driver-level reason for losing the device remains
unconfirmed.

Inspection found a separate, definite synchronization defect. The renderer
rotated two presentation-completion semaphores by CPU frame index. Waiting for a
submission fence does not prove that presentation has finished consuming its
semaphore. [Khronos's swapchain semaphore reuse guide](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)
prescribes one completion semaphore per acquired image, reused after that
image's acquisition wait. The implementation now follows that rule. It does not
establish that this defect was the sole cause of the observed device loss.

The new optional renderer path holds one acquired swapchain image for a complete
frame and splits rasterization into at most 256-by-256-pixel regions in Pitoco.
The frame builder runs once; host geometry and the copied push constants remain
fixed across submissions. Each tile waits for the previous fence before reusing
the command buffer. Only the first submission consumes the acquisition
semaphore, and only the last signals the image's presentation semaphore. UI and
readback are recorded once, after the last scene tile.

The first pass clears and stores both color and depth. Subsequent passes load
and store them, with an explicit attachment memory dependency. The passes retain
identical attachment formats/subpasses/dependencies; only load/store operations
and initial layouts differ, which [Vulkan render-pass compatibility](https://docs.vulkan.org/spec/latest/chapters/renderpass.html#renderpass-compatibility)
allows. The mesh pipeline uses a dynamic scissor while retaining the full-image
viewport. The full render area is retained so the final UI pass can cover the
window. This prioritizes correctness; attachment bandwidth and CPU submission
cost still need profiling. A pixel bound is not an absolute execution-time bound
for arbitrary shaders and geometry.

The validation environment now includes Khronos validation layers 1.4.357.0.
Homebrew also updated Vulkan loader/headers, SPIR-V tools/headers, and glslang as
dependencies; `build/vulkan-validation-install.log` records this change. Thus a
successful new run cannot by itself isolate the code fix from loader-version
effects. Synchronization validation is enabled via the documented
[`VK_VALIDATION_VALIDATE_SYNC=1`](https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/main/docs/syncval_usage.md)
setting in the verification run. Results are recorded only after execution.

### SideFX comparison: scheduling is separate from the integrator

SideFX documents both progressive image updates and bucket rendering in
[Karma Render Properties](https://www.sidefx.com/docs/houdini/nodes/lop/karmarenderproperties.html).
Karma CPU buckets partition an image, and checkpoint files can resume interrupted
work. This supports adopting bounded spatial work and persisted results in
Pitoco. It does **not** establish that our Vulkan scissor implementation is
Karma's implementation, nor that a fixed tile size prevents every device timeout.
Pitoco currently presents only a completed tiled frame; progressive accumulation,
render checkpoints and device reconstruction remain separate work.

SideFX's [FEM setup guidance](https://www.sidefx.com/docs/houdini/finiteelements/setup.html)
connects failures to measurable conditions: tet quality, positive mass, linear
solve iteration limits, substeps and collision passes. Its
[FEM collision documentation](https://www.sidefx.com/docs/houdini/finiteelements/collisions.html)
also explicitly describes soft repulsion and possible penetration; surface CCD
and static SDF collision have different guarantees. Thus “Houdini-like” is a
workflow reference, not a claim that every Houdini method is exact or that one
method is universally best. Pitoco's existing IPC path still requires the
recorded spatial/time convergence and material calibration before research use.

### Executed validation and the failed dense tile experiment

Khronos core/synchronization validation actually loaded through a project-local
manifest pointing to Homebrew's absolute dylib path. It found the missing
`VK_KHR_get_physical_device_properties2` instance dependency for portability
and a color LOAD dependency missing from the new continuation render pass.
Both were corrected. The next run logged no validation errors and produced
identical repeated captures; its viewport crops at ticks 100 and 72 were exactly
equal to the earlier untiled production images (`build/tiled-pixel-comparison.json`).
The isolated native regression remained 32 tests / 787 assertions passing.

The subsequent 1024-sample floor candidate **still terminated with device loss**
before its first capture, even with 256-pixel tiles. Evidence is retained in
`build/tiled-validation-desktop-3.log` and the 04:32:38 macOS crash report.
This refutes treating that tile size plus clean synchronization validation as a
sufficient safety condition. The optimized native stack merged the error
branches, so it did not establish which Vulkan call failed. Operation-labelled
checks and submit-through-fence wall timing were added for the next run. Smaller
64-pixel tiles are a candidate workload reduction, not a demonstrated cure.

The [MoltenVK configuration reference](https://github.com/KhronosGroup/MoltenVK/blob/main/Docs/MoltenVK_Configuration_Parameters.md)
documents driver log levels and the distinction between device and physical
device loss. Diagnostic runs enable driver logging. `MVK_CONFIG_RESUME_LOST_DEVICE`
is deliberately not enabled: silently continuing past a failed submission would
not establish that a saved frame is complete or correct.

### Work decomposition for the next offline renderer

Read the architecture and formulation sections of Laine, Karras and Aila,
[“Megakernels Considered Harmful: Wavefront Path Tracing on GPUs” (HPG 2013)](https://research.nvidia.com/sites/default/files/pubs/2013-07_Megakernels-Considered-Harmful/laine2013hpg_paper.pdf).
The paper separates logic, material evaluation and ray casting using bounded
queues and persistent path state. It explains how divergence and register use
can harm a single large kernel, while recording the extra memory/bandwidth cost
of splitting work. Its NVIDIA performance results are not measurements of our
Apple GPU and do not establish the instruction that caused our hang.

[PBRT 4e §15.1](https://www.pbr-book.org/4ed/Wavefront_Rendering_on_GPUs/Mapping_Path_Tracing_to_the_GPU)
discusses this same trade-off and adopts a wavefront GPU renderer, with selective
stage fusion. For Pitoco, the implementation decision is to bound samples/rays
per invocation as well as pixels per dispatch, retain linear radiance and sample
identity between jobs, and resolve completed work into the viewport separately.
The current scissor scheduler only supplies the pixel partition. The shader's
16/32/32 preview profile reduces the problematic nested workload; it does not
implement wavefront queues or floating-point accumulation. Those remain required
before offering dense offline quality presets again.

The diagnostic 64-pixel run subsequently provided the missing driver evidence:
Metal reported `VK_TIMEOUT`, command-buffer error code 2, and
`kIOGPUCommandBufferCallbackErrorHang` in a render encoder. The operation-labelled
panic identifies `vkWaitForFences`, frame 77, tile 342. No dense candidate was
loaded in that run; the old 128/256/128 profile itself was enough to trigger it.
`build/tiled-validation-desktop-4.log` preserves the driver messages. This is a
GPU execution hang; the exact shader instruction or driver defect remains
unidentified. Clean Vulkan synchronization validation does not resolve it.

The replacement 16/32/32 profile's viewport differs from the preceding profile
by mean absolute RGB8 0.24057 at tick 100 and 0.24505 at tick 72 (specified crop),
with channel maxima 17 and 26. This is an explicit quality trade-off, not evidence
of unchanged lighting or radiometric convergence. See
`build/preview-quality-difference.json`. Repeated captures within the new profile
remain pixel-identical. Native regression and the standalone application build
pass with operation-labelled diagnostics and workload timing enabled.

### Frame failure containment and macro correctness

The native renderer now converts frame-execution device loss into a stopped
state. It exits before further commands, marks queued readbacks failed, and
returns false to the application; Pitoco pauses and changes its window title.
CPU cache ownership is unchanged. Device recreation is not implemented.
An isolated injected-error test exercises the early return and a subsequent
frame request without initializing a GPU; it also checks that the frame callback
is never executed. The full focused regression passes 33 tests / 794 assertions.

The cross-namespace failure macro exposed an AguaFria emitter defect: syntax
quotation produces fully qualified symbols, and dotted namespace names were
being emitted as illegal unquoted Zig import aliases. Import aliases now use
quoted identifiers where needed, consistently at declaration and reference
sites. The 30-test / 137-assertion emitter suite passes, and the failure macro
compiles and executes through the native test path. This keeps the renderer
helpers ordinary Clojure macros without a special language construct.

Final execution evidence: the lighter profile completed 60 seeks across five
cached times over 375.95 seconds without logged Vulkan validation errors or
Metal hangs. Maximum sampled completed-frame submission time was 25.166 ms;
this is not a worst-case guarantee. Failure containment was then hot-reloaded
into the same JVM. The 145-frame cache survived, and repeated post-reload full
images at ticks 100/72 exactly match their pre-reload preview images. The final
standalone application build passes. See `build/preview-stress-result.edn`,
`build/containment-reload-pixel-comparison.json`, and
`build/device-loss-containment-standalone-build.log`.

### Mixed tetrahedral contact candidate: element construction (2026-09-13)

[Hauret, 2010, author-posted full text](https://www.researchgate.net/publication/222546512_Mixed_interpretation_and_extensions_of_the_equivalent_mass_matrix_approach_for_elastodynamics_with_contact)
provides a nonlinear action with independent displacement, velocity and momentum
spaces (§2.1). The compatibility of those spaces is essential; choosing a
singular matrix arbitrarily is insufficient. Section 3.2 mentions bubble
enrichment as an extension. Its numerical example uses hexahedra and a simple
transfer preserving total mass, without enforcing the inertia tensor (§4.1.1).
The paper also distinguishes normal and tangential boundary inertia (§3.2,
Remark 11). It therefore supplies a relevant framework, but does not validate
the particular tetrahedron or a frictional ball solver implemented here.

The following is our derived candidate, not a formula attributed to that paper.
It avoids transferring point masses to the existing interior vertices. It adds
four internal vector displacement coefficients per tetrahedron and uses a
separate discontinuous affine velocity field. This changes the approximation
space and the work required per element; it is not a cheap replacement for the
existing four-node element.

For barycentric coordinates l_0,...,l_3, define

    B = l_0*l_1*l_2*l_3
    Psi_i = 168*B*(9*l_i - 1)
    Phi_i = l_i - Psi_i
    u(X) = sum_i Phi_i(X)*q_i + sum_i Psi_i(X)*r_i
    p(X) = sum_i l_i(X)*r_dot_i

The q coefficients share the ordinary vertex displacement trace across faces.
The r coefficients are internal projection coefficients, not positions of
interior material nodes. On every face B=0, hence Psi=0 and Phi=l. The shape
functions sum to one, and q_i=r_i reproduces every affine displacement exactly.

The coefficient 168 follows from exact simplex moments. With A_ji=integral
l_j*l_i and H_ji=integral l_j*B*l_i, choose Psi_i=sum_k B*l_k*(H^-1*A)_ki.
The simplex identity

    integral_T product_i l_i^a_i
      = 6*V*product_i factorial(a_i) / factorial(3 + sum_i a_i)

gives Psi_i above and

    integral_T l_j*Phi_i = 0
    integral_T l_j*Psi_i = V*(1 + delta_ij)/20.

Consequently the L2 projection of u onto P1 is sum_i l_i*r_i. Taking both
velocity and momentum spaces as discontinuous P1 yields zero inertia for q
and the ordinary positive definite consistent tetrahedral mass block for r.
This is a mixed projection, not mass scaling or an assertion that all points
on the body's surface physically have zero density. It retains the exact
kinetic energy and mass moments for affine velocity fields, including rigid
translation and rotation about the rest configuration. It does not retain
all higher-order velocity content of the enriched displacement field.

The native implementation is `src/field_lab/mixed_tetra.clj`. It evaluates the
eight scalar basis functions and rest-world gradients, the mixed mass entries,
kinetic energy, and integrated Stable Neo-Hookean energy/gradient/exact Hessian.
Its quadrature is constructed natively using bounded Gauss-Legendre root solves,
then mapped to the tetrahedron with the positive Duffy Jacobian. Six points per
axis integrate the degree-eight rest-stiffness polynomials exactly; nonlinear
constitutive terms require quadrature refinement. The independent verifier
uses rational polynomial algebra and exact monomial integrals, rather than
repeating the native quadrature or stress/tangent implementation.

This is an element implementation, not an integrated contact solver. Before
integration, verify positive stiffness of the massless boundary block, the
correct six rigid stiffness modes, finite-deformation objectivity and quadrature
sensitivity. Then implement global assembly with shared q and cell-owned r,
consistent body loads/observables, and a constrained implicit solve. Its singular
mass must not be passed to the current invertible-mass residual helper.

The enriched deformation gradient varies inside each tetrahedron. Existing
linear-tetrahedron inversion tests do not apply: positivity at quadrature points
alone cannot certify a positive Jacobian throughout an element or along a line
search. The whole-element/path bound described below now supplies a conservative
sufficient test. It must be integrated into the solver before the candidate
can advance production ball states. Normal-only contact treatment, tangential
waves/friction, moving contact geometry and space/time convergence remain open.
The live GUI and its historical cache are unchanged by this isolated work.

The element checks now pass **11 tests / 458 assertions**. They include finite
rotation objectivity of non-affine energy and forces, torque balance, a sheared
rest tetrahedron, and malformed quadrature/volume/inversion rejection.
`build/mixed-tetra-verified-tests.log` retains the result. The initial failed
mass-energy test was a Clojure fixture error (`into` onto a sequence reordered
coefficients); correcting it to a vector restored the intended boundary/internal
ordering without changing native inertia code.

Independent rational polynomial integration verifies the partition and all P1
projection moments exactly. For E=10000 Pa, nu=0.3 and the unit reference tet,
the native rest Hessian differs from that independent matrix by at most
8.01e-11 N/m. The full stiffness has exactly six rigid modes; its smallest
non-rigid eigenvalue is 633.85 N/m. The massless boundary block is positive
definite (smallest eigenvalue 23790.21 N/m). Static condensation retains six
rigid modes and no additional zero modes. The internal mass block is positive
definite; the twelve boundary displacement DOFs are the intended mass nullspace.
These checks establish properties of this element at rest, not global contact
well-posedness or physical convergence.

For the recorded non-affine displacement, quadrature orders 6/8/10/12 give
energies 8.4028445271 / 8.4029452902 / 8.4029468877 / 8.4029468823 J. Successive
changes shrink from 1.01e-4 to 1.60e-6 to 5.42e-9 J. This is one smooth
quadrature study; it does not establish a universally sufficient order.

#### A conservative whole-element and path bound

[Johnen, Remacle and Geuzaine, geometrical validity of curvilinear elements](https://www.gmsh.info/doc/preprints/gmsh_curved_preprint.pdf)
explain why point sampling cannot establish positive Jacobians and use Bernstein
polynomial bounds. Our first implementation uses the convex-hull property on
the deformation-gradient entries, followed by outward interval evaluation of
the determinant. It is simpler and looser than their adaptive determinant-basis
method; it must not be described as that full algorithm.

Let d_i=r_i-q_i and F_a=I+sum_i q_i outer g_i, where g_i=grad l_i.
The degree-four Bernstein representation of F has 35 controls, only fourteen
of which need distinct formulas:

    22 controls: F_a
    alpha=(0,2,1,1), missing j and doubled k:
        F_a + 14*(9*d_k-sum_i d_i) outer g_j       (j != k; 12 controls)
    alpha=(1,1,1,1):
        F_a + 126*sum_i d_i outer g_i
            - 7*(sum_i d_i) outer (sum_i g_i).

The independent verifier reconstructs all polynomial basis derivatives from
these controls and checks exact rational equality. Native arithmetic uses the
existing strict interval operations, including outward rounding. Taking the
component envelope of both endpoint control sets also contains F at every
point along a straight coefficient path. A positive determinant lower bound
therefore certifies the entire element throughout that path. Nonfinite inputs,
overflow or a non-default rounding mode return an unbounded/inconclusive result.
A bound spanning zero is inconclusive; it does not classify the element as
inverted. Subdivision or tighter bounds remain necessary for useful acceptance
of larger deformations. Boundary collision detection remains a separate check.

A manufactured counterexample makes this distinction testable. With all q=0,
r_1,x=0.058869298663277475 m and other r=0, all 216 six-point-per-axis Gauss
samples have J>0.091. At barycentric coordinates
[0, 0.5310234280197912, 0.2344882859901044, 0.2344882859901044], J<-0.091.
The new interval bound refuses to certify this state. Another test has valid
half-turn endpoints but a collapsing midpoint and similarly remains uncertified.
Thus `Response.valid` only describes successful quadrature evaluation; callers
must separately obtain a positive path certificate before accepting a step.

Final element evidence is retained in `build/mixed-tetra-native-evidence.edn`,
`build/mixed-tetra-path-bounds.edn`, `build/mixed-tetra-independent.json` and
`build/mixed-tetra-verified-tests.log`. The independent verifier rejects stale
native source fingerprints. The standalone ReleaseSafe probe, including the
path bound, builds and executes with exit code 0; its sole dynamic dependency
is libSystem. See `build/mixed-tetra-standalone-final-build.log` and
`build/mixed-tetra-native-smoke`. Read-only live status still reports 145 frames,
cursor 72, no active bake and no failure. The experimental element is not yet
connected to any authored ball job or viewport mode.


### Native global assembly for the mixed candidate

The element construction now has a global operator in `mixed_tetra.clj`.
For V mesh vertices and C cells, the coefficient vector contains V shared
boundary coefficients followed by four private coefficients per cell, each a
three-vector. Sharing q enforces the linear face trace; r remains discontinuous.
This is a native AguaFria Zig assembly; its Clojure-facing tests are callers.

For a backward-Euler step h, eliminate the P1 velocity through
`r_dot_new = (r_new-r_old)/h` and define `r_hat = r_old+h*r_dot_old`.
The incremental potential is

```
I(q,r) = sum_cells E_cell(q,r)
       + sum_cells (r-r_hat)^T M_cell (r-r_hat)/(2 h^2)
       - sum_cells rho*V/4 * g dot sum_i r_i
       - generalized_loads dot (q,r).
```

These are our discrete equations specialized from the mixed displacement/velocity
setting, not a claim that this particular element or backward-Euler choice is
Hauret's published contact algorithm. The current kernel assembles this potential,
its gradient and exact local Hessians. `tangent-product!` gathers/scatters those
cached 24-by-24 element blocks, avoiding a dense global matrix. The local blocks
still cost 576 doubles per cell, and energy/derivative evaluation remains expensive;
this is not a performance qualification.

Uniform gravity loads only r because `integral Phi_i = 0` and
`integral Psi_i = V/4`. The arbitrary-load input is an already integrated vector
of generalized forces. For example, constant reference-face traction contributes
`area*traction/3` to its three q vertices and nothing to the bubble functions.
A separate integration layer is needed for nonuniform/follower loads. Inertial
prediction on q is intentionally ignored; solving those equations is an algebraic
equilibrium problem. The operator never inverts the singular global mass matrix.

The assembly regression passes 14 tests and 515 assertions, including all previous
element checks, finite differences of the entire 39-component two-cell potential,
a directional tangent check, invalid inputs and analytic free fall. Two connected
tets with 120/60 kg/m^3 densities, 1/6 and 1/3 m^3 volumes and different materials
satisfy every backward-Euler equation under uniform translation. This is an
analytic solution check, not a completed nonlinear trajectory solver.

`tools/verify.py tetra` separately integrates rational shape polynomials,
transforms gradients to the second cell and assembles global stiffness and mass.
Native tangents agree within 1.90e-10 N/m at h=10 and 20 ms. The global stiffness
has six rigid modes, its 15-by-15 q block is positive definite, and the mass has
15 zero eigenvalues with a positive definite private block. Independent NumPy
linear solves recover the analytic free-fall displacement at both timesteps.
Evidence is retained in `build/mixed-assembly-tangent-{10ms,20ms}.csv`,
`build/mixed-assembly-final-independent.log` and `build/mixed-tetra-independent.json`.

The nonlinear constrained driver, its stopping criterion, global path certification,
contact reactions and full observables are still required. A successful assembly
response does not certify geometry or accept a step. The element's conservative
Bernstein/interval bound must guard the higher-order interior, with separate
boundary collision detection. Existing ball jobs continue using their prior
solver; no clothing or paper scenario is implied by this assembly milestone.


### Constrained mixed backward-Euler prototype

The native `field-lab.mixed-solver` now turns the assembled incremental potential
into constrained steps. Its optimization structure is informed by
[Bertsekas, Projected Newton Methods (1982)](https://web.mit.edu/dimitrib/www/ProjectedNewton.pdf)
and [PETSc's bounded Newton line-search implementation](https://petsc.org/main/src/tao/bound/impls/bnk/bnls.c.html).
Bertsekas motivates combining projection with second-order directions and a
sufficient-decrease search; PETSc provides a concrete bounded Newton implementation.
Our code is a smaller experimental implementation, not an implementation of every
safeguard in PETSc or a claim to inherit the paper's convergence theorem.

The solver operates on componentwise displacement bounds. Equal bounds prescribe
supports. Lower bounds on the shared q vertices' y displacements describe a
horizontal ground plane; upper bounds describe a ceiling. Bounds on all vertices
of the linear boundary trace protect the entire face from that plane. This does
not handle general triangle/triangle contact, self-contact or friction.

Each iteration identifies binding constraints with correctly signed forces and
solves the remaining Newton equations using Jacobi-preconditioned CG over cached
element tangent products. Nonpositive curvature/nonfinite recurrence rejects that
Newton direction. Projection and an Armijo decrease test guard the update; a
failed Newton search retries with diagonally scaled projected gradient descent.
The first-order stopping test is the infinity norm of the free or incorrectly
signed bound forces, in newtons. Fixed supports contribute reaction rather than
residual. This does not certify a local/global minimum or physical accuracy.

Every trial also needs a positive whole-element Jacobian bound along both the
current-iterate-to-trial segment and the original-timestep-start-to-trial segment.
The latter prevents accepting a Newton path whose final physical interpolation
would cross an inversion. The initial state must already be feasible and certified;
the solver does not silently move it into bounds. An inconclusive certificate
rejects the trial. Result status distinguishes success, invalid inputs, uncertified
initial geometry, invalid initial energy, iteration exhaustion and failed line
search. Scratch iterates from a failed solve must not be published to a job/cache.

The regression passes **18 tests / 553 assertions**, including prior element and
assembly tests. Native free fall reaches force residual below 2.9e-12 N. The
10 ms floor step takes three Newton iterations, with a 1.27e-8 N residual and a
0.954 whole-path Jacobian lower bound. Ceiling contact, fixed supports, global
momentum balance, invalid bounds and hidden-inversion rejection are covered.

An independent NumPy/SciPy verifier evaluates rational shape polynomials at NumPy
Gauss points and implements the original constitutive energy/PK1 separately.
Bound-constrained SLSQP followed by a root solve on the identified free variables
recovers native solutions within 1.81e-13 m (10 ms) and 2.13e-14 m (5 ms).
The active constraints and reaction signs agree. This independently checks the
nonlinear algebraic solution, not contact accuracy against a physical experiment.
`tools/verify.py mixed`, `build/mixed-solver-evidence.edn` and
`build/mixed-solver-independent.json` preserve the evidence.

A repeated-step test drops the same two-cell 40 kg body from a 1 mm gap under
9.81 m/s^2 gravity. Runs of 40 ms complete at timesteps 1, 0.5 and 0.25 ms,
with 40, 80 and 160 accepted steps. Maximum per-step momentum-balance error is
5.73e-12 N s and every physical path has a Jacobian lower bound above 0.753.
The analytic free-flight contact time is about 14.278 ms; the sampled first
contact times are 14, 14.5 and 14.25 ms, respectively.

| Timestep | Peak ground reaction | Final mechanical energy |
| --- | --- | --- |
| 1 ms | 372.63 N | 0.25805 J |
| 0.5 ms | 396.35 N | 0.31609 J |
| 0.25 ms | 409.45 N | 0.35127 J |

Initial mechanical energy is 0.3924 J. Energy decreases at every accepted step,
but backward-Euler dissipation remains substantial and affects the force history.
These runs demonstrate repeated certified contact steps and timestep sensitivity;
they do not establish temporal/mesh convergence or qualify the impact model.
A suitable integration scheme, rod benchmark and general mesh/job adapter are next.
The inspected plot is `build/mixed-solver-trajectory.png` (also PDF), with full
states in EDN and observables in CSV. The standalone ReleaseSafe floor-contact
probe also builds and exits 0, linking only libSystem; its execution uses no JVM.

The application ball modes remain unchanged. Normal/tangential behavior,
large-deformation boundary contact, tighter geometric bounds, renderer work and
the later clothing/paper/rain/audio/circuit scenarios remain open requirements.

### Owned mixed jobs and the first rod refinement study (2026-09-19)

`field-lab.mixed-job` owns the native mesh, coefficient fields and workspace.
Clojure scene data supplies tetrahedra, initial fields, density/material and an
optional frictionless horizontal plane. Allocation/configuration failures release
owned storage. A successful step publishes displacement, velocity, time, step
count and the accepted contact reaction together. A failed solve may overwrite
scratch workspace but preserves these accepted observables. The Clojure callback
scope releases storage in `finally`; a native standalone ownership/contact probe
also executes with exit 0 in ReleaseSafe, linking only libSystem.

Mass, momentum, kinetic energy and angular momentum use the consistent private
P1 velocity field and its moment-compatible displacement projection. Angular
momentum is the integral of `(X + u) × rho*v`; it is not computed by placing the
private coefficients at invented interior points. Tests include rigid translation
and spin, heterogeneous two-cell fixture agreement, invalid initial fields and
failed-step rollback. The updated mixed regression passes 22 tests / 588
assertions (`build/mixed-rod-run.log`).

For the plane, the accepted upward reaction is the sum of positive y residuals
at binding lower-bound boundary coefficients. Backward Euler associates impulse
`dt * reaction` with that accepted step. This resultant is computed from the KKT
residual, independently of the momentum difference used to audit it. The current
adapter has no general boundary/contact or friction implementation.

`run-mixed-rod-impact!` uses the existing free-end rod reference: L=0.1 m,
width=0.02 m, E=1 MPa, rho=1000 kg/m³, nu=0, speed=0.01 m/s, gap=2 µm, no
body force. Wave speed is sqrt(E/rho); reference force is rho*c*v*A, contact
duration Tc=2L/c and rebound speed equals incoming speed. The strain scale is
3.16e-4. The tetrahedral material is nonlinear, so this small-strain reference
has finite-strain approximation error; it is not an exact nonlinear solution.
The transverse mesh remains one Cartesian cell per direction, with six tetrahedra
per Cartesian cell. Axial refinement alone is not full spatial convergence.

| Axial cells | Step | Accepted steps | Force pulse L1 error | Peak force / reference | Rebound / incoming | Energy loss |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 2 | Tc/32 | 50 | 27.06% | 2.143 | 0.70275 | 46.22% |
| 2 | Tc/64 | 99 | 29.47% | 3.111 | 0.76638 | 34.20% |
| 4 | Tc/64 | 99 | 15.23% | 1.865 | 0.79017 | 32.24% |
| 4 | Tc/128 | 197 | 16.85% | 2.224 | 0.83826 | 23.58% |

The first exploratory run used Debug; the three controlled refinement runs use
ReleaseSafe. All 445 accepted steps are plane-feasible and have positive whole
physical-path Jacobian certificates (minimum bound above 0.9988). Maximum
per-step momentum discrepancy is 2.07e-11 N s. This verifies accounting and
feasibility, **not impact accuracy**. At fixed step, axial refinement reduces
force error. Halving the step reduces dissipation but increases the sampled
initial contact force peak. The rebound deficit and force errors remain large;
these results do not demonstrate asymptotic convergence or meet the earlier
1% impact targets. Do not replace the production ball solver on this evidence.

The inspected four-panel plot is `build/mixed-rod-comparison.png` (also PDF and
CSV). Raw cases are `build/mixed-rod-first.edn` and
`build/mixed-rod-refinement.edn`, including source/definition provenance,
authored mesh, material and every accepted step. The production study entry
point is `clojure -M:impact-study` with `:benchmark :mixed-rod-impact`; README
contains an executable example. It atomically checkpoints data, refuses to
overwrite existing results and retains accepted prefixes on failure. The shared
IPC/mixed persistence regression passes 3 tests / 28 assertions, including an
injected mixed failure that must never become `:completed`.

Re-reading [Dabaghi et al., §§4.2–5](https://arxiv.org/html/1601.00778v1)
reinforces the need to qualify both force and energy when changing integration.
Their analysis and hybrid scheme concern a linear one-dimensional Signorini
problem with a particular redistribution. They do not prove stability or
accuracy of this three-dimensional enriched hyperelastic element. Next work is
a suitable less-dissipative integration treatment with independent free-motion
and contact checks, followed by controlled temporal, axial/transverse mesh and
quadrature studies. Simply switching to midpoint/Newmark and accepting improved
energy retention would leave contact-force behavior unqualified.

### SDIRK2, small-strain precision and independent modal evidence (2026-09-19)

The experimental mixed adapter now implements Alexander SDIRK2. The original
[Alexander paper abstract](https://epubs.siam.org/doi/10.1137/0714068) was accessible;
its full text was not read. The coefficients and two-stage structure were checked
against the primary [MOOSE implementation](https://raw.githubusercontent.com/idaholab/moose/master/framework/src/timeintegrators/LStableDirk2.C).
Our native equations are derived from the Runge–Kutta tableau, not copied source.
For gamma=1−1/sqrt(2), each constrained displacement solve has effective step
gamma*h. Stage two uses prediction u0+2(1−gamma)h*v1+(2gamma−1)h*v0; the accepted
velocity is (u2−u0−(1−gamma)h*v1)/(gamma*h). Only private displacement coefficients
have inertia. Boundary coefficients satisfy the constrained elastic residual.

The accepted impulse is h*((1−gamma)*F1+gamma*F2); the endpoint force remains F2.
The rod harness audits momentum against this impulse rather than h*F2. Neither
stage publishes state. Both must succeed, and the initial-to-stage and
stage-to-stage deformation paths must have positive whole-element Jacobian
bounds before committing positions, velocity, clock and reactions together.
Status 6 reports an uncertified connecting path. This establishes the implemented
transaction and geometric checks; it does not establish contact order or accuracy.

Smooth-motion tests uncovered precision loss from explicitly forming I+H before
computing tiny strains. `evaluate-gradient` now computes the metric strain,
determinant change and stress increments directly from H near identity, with the
same material formula and a general-deformation fallback. Tests cover dilation
at 1e-12 through 1e-8 and agreement around the fallback. The mixed element uses
this path; the production ball constitutive entry point is unchanged. The final
mixed/material regression passes **36 tests / 738 assertions**, including an
actual second-stage failure, unchanged accepted state and successful retry.

This improves precision but does not eliminate all line-search stagnation. At
1e-11 N tolerance, backward Euler's 32-step modal run still reaches its iteration
limit on tick 9 with residual 5.14e-11 N. The retained log is
`build/sdirk-mode-stable-final.log`. The successful study uses 1e-9 N (100 times
stricter than the adapter default), explicitly stored in its exported evidence.

The independent modal oracle builds exact-polynomial stiffness and consistent
mass, eliminates massless coordinates, and solves the generalized eigenproblem.
The chosen frequency is 35.03057 rad/s; initial velocity amplitude is 1e-4 m/s.
Over 0.7 periods, native nonlinear displacement/velocity are compared to the
small-amplitude sinusoid in a mass-weighted phase-space norm. This reference is
a linearization, not an exact nonlinear solution. All six ReleaseSafe runs pass:

| Steps | Backward Euler relative error | SDIRK2 relative error |
| ---: | ---: | ---: |
| 16 | 0.448688 | 0.0133598 |
| 32 | 0.259837 | 0.00335508 |
| 64 | 0.140103 | 0.000839714 |

Fine/medium error ratios are 0.5392 and 0.2503 respectively. The repository helper
`mixed-job-test/write-mode-evidence!` refuses to overwrite CSV output; the Python
oracle rejects incomplete cases, excessive residuals and incorrect error ratios.
`build/sdirk-mode-independent.json` records results, tolerances and source hashes.
README contains the reproduction commands. The standalone ReleaseSafe SDIRK
contact executable was rebuilt after the precision change and exits 0, linking
only libSystem; it does not require a JVM at runtime.

The matched rod rerun on the changed sources remains unfavorable for impact:

| Method, 4 axial cells, Tc/128 | Force L1 error | Peak/reference | Rebound/incoming | Energy loss |
| --- | ---: | ---: | ---: | ---: |
| Backward Euler | 16.85% | 2.224 | 0.83826 | 23.58% |
| SDIRK2 | 75.47% | 4.075 | 0.89093 | 6.90% |

Both runs finish 197 accepted steps; all plane heights are feasible and physical
paths are certified. Maximum step momentum discrepancy is 4.60e-11 N s. SDIRK2
retains more energy but produces stronger force ringing; it is **not promoted**.
Raw results and definition fingerprints are in
`build/mixed-rod-precision-comparison.edn`. Transverse/spatial convergence and
contact qualification remain open, as does the stricter precision limit above.

The primary preprint [You, Zheng and Li, 2026](https://arxiv.org/html/2602.08094v1)
discusses integration dissipation and proposes energy-controlled A-search, with
an animation-oriented bias toward low-frequency motion. Its energy behavior
does not establish engineering force accuracy for our mixed formulation.
A-search is not implemented here. The present measured force failure is why
energy retention alone cannot select the production method. The next numerical
gate is controlled contact force/rebound/energy convergence. Clothing, paper,
rain/audio and circuit simulation remain later, unimplemented milestones.

### Isolating mixed-contact force ringing (2026-09-19)

The next investigation separates time integration, nonlinear material effects,
and the spatial approximation. `tools/verify.py mixed --rod-study` reads
completed authored rod data exported to JSON. It assembles the same 3D mesh using
independent exact-polynomial linear stiffness and consistent private inertia.
Each constrained step is solved through a contact compliance matrix and dual
nonnegative least squares. It uses neither native quadrature nor Newton/CG.
Stationarity, gap, complementarity and momentum residuals are checked explicitly.
The linearization is restricted to the nu=0, gravity-free rod benchmark.

At the original amplitude and four axial cells, backward Euler's independent
force history differs from native by 0.00201% in normalized L1. SDIRK differs by
2.204%, failing the unchanged 0.5% comparison gate. This failure is retained in
`build/mixed-rod-linear-comparison.json` (status `comparison-failed`); it must not
be used as a passing native validation. Lowering speed from 0.01 to 0.001 m/s
and gap from 2 µm to 0.2 µm preserves impact time and lowers strain by ten.
The native SDIRK run completes 197 steps; independent force discrepancy drops to
0.0605%, passing the gate. This supports sensitivity to finite-strain effects
in the original oscillatory history, rather than identifying a native time-step
implementation error. It does not prove nonlinear correctness for all impacts.

For a second independent check, eliminate the massless boundary through static
contact equilibrium at each adaptive DOP853 evaluation, and integrate only the
private dynamic coefficients plus accumulated contact impulse. This estimates
the time-continuous limit of the chosen spatial model. It is not the spatial
continuum. Displacement, velocity and impulse are nondimensionalized using
speed*contact-duration, speed, and mass*speed. An initial unscaled absolute
error-budget run failed its refinement check; that log remains preserved.
With scaled tolerances 1e-9 and 1e-11, the small-amplitude force error changes by
4.57e-8 and rebound ratio by 3.38e-8. The tighter run has sampled relative energy
drift 1.35e-9 and momentum discrepancy below 4.75e-19 N s. Yet its force error
is **80.927%**, and rebound/incoming is **0.90016**. Therefore temporal accuracy
and energy retention alone cannot repair this coarse spatial approximation.
Completed evidence is `build/mixed-rod-final-independent.json`; it includes input
and source hashes, dependency versions, traces and comparison outcomes.

Native ReleaseSafe refinements on frozen solver sources give:

| Axial cells | SDIRK step | Force L1 error | Peak/reference | Rebound/incoming | Energy loss |
| ---: | --- | ---: | ---: | ---: | ---: |
| 4 | Tc/128 | 75.47% | 4.075 | 0.89093 | 6.90% |
| 4 | Tc/256 | 83.78% | 4.268 | 0.89536 | 3.02% |
| 8 | Tc/128 | 29.69% | 2.935 | 0.94535 | 3.53% |

The two new refinement cases complete 590 accepted steps, stay plane-feasible,
and retain positive physical-path certificates; momentum discrepancy stays
below 1.98e-11 N s. Their raw artifact is
`build/mixed-rod-ringing-refinement.edn`. The smaller-amplitude study is
`build/mixed-rod-small-amplitude.edn`. Axial refinement helps at this fixed step,
but transverse refinement and a time-resolved spatial sequence are still needed.
In particular, numerical filtering changes when mesh frequencies change, so the
29.69% result alone cannot establish spatial convergence or meet the 1% target.

The primary [massless-boundary study, §2.1](https://arxiv.org/html/2111.07693v1)
reports difficulties with higher-order integrators and uses a conditional Verlet
scheme with static boundary equilibrium. Its linear, fixed-contact setting is
useful for this diagnostic but does not validate large-deformation balls.
[Dabaghi et al., §5](https://arxiv.org/html/1601.00778v1) derive a hybrid scheme
for their particular one-dimensional redistribution. Neither paper proves our
enriched tetrahedron's accuracy. The measured error now directs the next work
toward controlled spatial/formulation convergence, with temporal resolution
checked independently. No additional damping, production integrator switch or
new UI scene was introduced by this diagnostic.

The eight-cell independent comparison subsequently passes: native/linear force
history difference is 0.0170%. Adaptive integration gives force L1 error
42.1822% and rebound ratio 0.949523; tightening scaled tolerances changes these
by 1.50e-7 and 1.95e-8, with sampled relative energy drift 1.31e-10. Thus axial
refinement improves the time-resolved spatial model too (coarse force error
80.927%, fine 42.182%). This is only a two-mesh axial trend, not full spatial
convergence. `build/mixed-rod-eight-independent.json` has status `verified` for
its independent comparison and tolerance checks, **not** for physical impact
accuracy. The inspected figure `build/mixed-contact-diagnosis.png` / PDF shows
native refinement, independent small-amplitude forces, energy and rebound.
The next gate is a time-resolved spatial sequence including transverse
resolution, with the existing force/rebound targets unchanged.

### Exact block tangents and frozen mixed kernels (2026-09-19)

Refinement exposed the cost of the mixed element: each quadrature point rebuilt
24 directional material tangents, repeating invariant and cofactor work. The
native kernel now contracts the exact Hessian into 3×3 blocks for each pair of
shape gradients. Its material derivative agrees with Eq. 21 and the determinant
cross-product structure in §4.5 of
[Smith, de Goes and Kim (2018)](https://www.tkim.graphics/NEO/StableNeoHookean2018.pdf).
The author's PDF was downloaded and those sections read after the web reader
hit its size limit; the old Pixar URL redirects to a library landing page.

For gradients a,b, deformation F and cofactor C, the contracted block is

    s*(a dot b)*I + d*(F*a) outer (F*b)
      + lambda*(C*a) outer (C*b) - p*[F*(a cross b)]_cross
    s = mu*(1 - 1/(1+F:F))
    d = 2*mu/(1+F:F)^2
    p = lambda*(det(F)-alpha).

This is our direct contraction of the existing material tangent. Invariants and
F/C gradient products are shared; opposite node-pair blocks are transposes.
The determinant contribution stays polynomial, with no inverse F. Quadrature,
material energy/stress, mass, geometry certificates and convergence criteria
are unchanged. No reduced integration, Hessian projection or artificial damping
was introduced. The full nonlinear finite-difference Hessian tests still pass.
Independent exact-polynomial checks find maximum absolute errors 8.00e-11 for
the rest element and 2.04e-10 for the two-cell assembled tangent. Rigid nullspaces,
positive private mass and the free-fall solve retain their expected properties.
Fresh evidence is under `build/block-hessian/`.

Seven warm ReleaseSafe samples of 512 changing-input element evaluations show
median time decreasing from 2.203 s to 1.722 s (1.28× throughput). Returning a
changing Hessian checksum prevents unused-work elimination; checksum difference
is roundoff-sized. Raw timings and source hashes are
`build/mixed-element-before.edn` and `build/mixed-element-after.edn`.

A larger avoidable cost was that mixed studies still used reloadable numerical
calls, while the coupled solver already had a frozen native-kernel scope. That
existing scope now supports the mixed adapter through one private exported step
boundary. It compiles ReleaseSafe code with fixed numerical dependencies, records
source/artifact hashes, owns the foreign-call buffers and rejects changed solver
definitions before another call. The application configuration remains unchanged.
The scope is synchronous and specific to the same compiler/layout generation;
it is not the planned stable external plugin ABI. Native create/configure/read/
destroy remain usable from the authoring JVM, and no C++ solver was added.

Mixed rod studies default to `:execution :snapshot`; explicit
`:execution :reloadable` retains a development comparison. The kernel mode and
provenance appear in the experiment record. Tests compare both BE and SDIRK
trajectories, positions, energies, momenta and contact reactions across both
execution paths. They force a failed step after accepted motion, verify rollback
and retry, reject changed definitions, and check that compilation does not alter
the hot-reload configuration. The combined suite passes **37 tests / 795
assertions**; the persistence/reference harness passes **3 tests / 36 assertions**.

The matched four-axial-cell SDIRK bake completes 197 accepted steps in 108.043 s
through reloadable calls and 8.082 s through the frozen kernel: **13.37× for this
measured case**. Settings, physical source fingerprints, initial conditions and
every accepted sample compare exactly. Both timings exclude kernel compilation;
an earlier frozen run took 12.177 s, so this is not a universal performance
guarantee. `build/mixed-execution-comparison.edn` records the comparison and kernel
hashes. The freshly rebuilt standalone SDIRK probe also exits successfully.

The same frozen kernel completes the following spatial studies:

| Mesh divisions (x,y,z) | Step | Force L1 error | Peak/reference | Rebound ratio | Energy loss | Bake seconds |
| --- | --- | --- | --- | --- | --- | --- |
| [1 4 1] | Tc/128 | 75.47% | 4.075 | 0.89093 | 6.90% | 8.082 |
| [1 8 1] | Tc/256 | 41.04% | 3.257 | 0.94450 | 2.49% | 27.027 |
| [1 16 1] | Tc/256 | 14.41% | 2.476 | 0.97266 | 1.64% | 54.470 |
| [2 8 2] | Tc/256 | 38.39% | 4.238 | 0.92987 | 4.17% | 95.922 |

These runs total 1,376 accepted steps, with nonnegative sampled boundary height,
certified path Jacobian lower bounds at least 0.9950 and maximum step momentum
discrepancy 4.83e-11 N s. Evidence is `build/mixed-rod-spatial-snapshot.edn`.
Axial refinement helps, but the transverse case improves force L1 error only
slightly while worsening rebound, energy loss and peak force. Neither the 1%
force target nor the 1% rebound target is met. The finer meshes still need an
independent temporal-resolution check; these finite-step results do not prove
spatial convergence. The experimental formulation remains unqualified, and
clothing, paper/wind, rain/audio and circuit simulations remain unfinished.
The inspected `build/mixed-spatial-refinement.png` / PDF plots the unsmoothed
accepted-step forces, center velocity and mechanical energy for all four cases.

### Resolved time error and rejected space restrictions (2026-09-19)

The faster frozen kernel enabled 3,139 further native accepted steps without
altering material, contact, mass or tolerances:

| Mesh | Native step | Raw force L1 error | Rebound ratio | Energy loss | Seconds |
| --- | --- | --- | --- | --- | --- |
| [1 16 1] | Tc/512 | 23.313% | 0.973368 | 0.880% | 109.29 |
| [1 16 1] | Tc/1024 | 31.298% | 0.973469 | 0.283% | 218.41 |
| [2 8 2] | Tc/512 | 37.340% | 0.931066 | 2.204% | 194.63 |

All sampled boundary heights stay nonnegative, path certificates stay positive
(minimum lower bound 0.9931), and step momentum discrepancy is at most
1.71e-11 N s. These are solver invariants, not accuracy evidence. The completed
record is `build/mixed-rod-fine-time.edn`.

Independent exact-polynomial linear contact at the original Tc/256 step agrees
with the native force history to 0.01534% on [1 16 1] and 0.21425% on [2 8 2],
passing the existing 0.5% linear/native gate. Adaptive DOP853 at two tolerances
gives force errors 29.4913% and 35.8410%, respectively, and rebound ratios
0.973788 and 0.933408. Tightening tolerance changes force errors by 3.15e-8 and
1.18e-6; fine-run sampled relative energy drift is below 5.07e-10. The independent
record `build/mixed-rod-fine-independent.json` verifies these checks, not physical
qualification. The sixteen-cell 14.414% result was partly numerical filtering.

`compare-mixed-time-refinement` now compares native histories using the same
observation windows, by summing accepted fine-step impulses without force
interpolation. It requires matching physics/fingerprints, complete contiguous
histories, consistent cumulative impulse and nested output boundaries. It keeps
raw errors alongside rebinned errors so averaging cannot conceal oscillations.
For [1 16 1] at a common Tc/256 window, native errors are 14.414%, 22.224% and
28.508% for Tc/256, Tc/512 and Tc/1024. The fine integral is preserved within
2.17e-18 N s. However, Tc/1024 still differs from the independent time-limit
**force history** by 27.694%, even though their scalar errors look close. This is
why scalar force error alone is insufficient to claim temporal convergence.
Evidence: `build/mixed-rod-time-comparison-final.json`, with input/source hashes.
The comparison/persistence/reference tests pass **5 tests / 56 assertions**.

Reviewing [Monjaraz Tec et al., §§3.1–3.2](https://arxiv.org/html/2111.07693v1)
confirms that their massless reduced models retain boundary flexibility while
approximating inertia; their static/dynamic split is not equivalent to our local
enrichment. MacNeal and modified Craig–Bampton provide published alternatives
for linear models, but this paper does not justify transplanting them unchanged
into the nonlinear ball solver.

Before changing native layouts, two restrictions of our existing polynomial
space were evaluated independently. They are our diagnostic constructions, not
the paper's methods. First, retain enrichment in cells incident on the lowest
vertices and impose r=q in the remaining cells. Because Phi+Psi=l, those cells
become ordinary P1 tetrahedra. Second, retain enrichment everywhere but identify
the r coefficients associated with the same rest vertex, making projected P1
velocity continuous. Both use congruent stiffness/mass transformations and audit
all affine velocity moments. No mass is removed or rescaled.

Neither restriction improves the tested contact response. At eight axial cells,
the boundary-star and continuous-velocity force errors are 51.091% and 79.448%,
versus 46.397% for the original time-resolved model at the same Tc/256 windows
(`build/mixed-eight-common-window.json`). The earlier 42.182% value used wider
Tc/128 windows and must not be used as this comparison's baseline. At sixteen
cells, boundary-star gives 42.888% versus 29.491%. The transverse boundary-star
case fails the unchanged rebound-tolerance check (difference 1.10e-5 versus a
1e-5 gate); the sixteen-cell continuous-velocity case fails its force-tolerance
check. These failed records remain in `build/mixed-localized-enrichment.json`
and `build/mixed-continuous-velocity.json`. No native implementation was promoted.

The verifier exposes these experiments through `--restriction boundary-star`
or `--restriction continuous-velocity`, and distinguishes `diagnostic-complete`
from native comparison verification. Its generalized static/dynamic partition
reproduces the original sixteen-cell adaptive result within 1e-12. All original
plane bounds remain checked; contact on a mass-carrying coordinate is unsupported
in this adaptive diagnostic and a violated bound rejects the run. The next
formulation decision must address spatial wave/contact accuracy, not simply
remove degrees of freedom or add damping. Clothing and later scenarios remain
unfinished.
The inspected `build/mixed-time-resolution.png` / PDF presents the common-window
forces, numerical energy loss and error comparison, with the failed qualification
explicitly identified.
