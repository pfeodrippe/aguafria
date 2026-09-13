(ns field-lab.nonlinear-fem
  "Finite-deformation tetrahedral FEM with explicit, stability-limited dynamics.
  Linear FEM supplies reference geometry only; forces come from hyperelastic P."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.hyperelastic :as elastic]))

(az/defstruct Dynamics
  [[:mesh [:* fem/Model]]
   [:material elastic/Material]
   [:velocities [:slice p/Vec3]]
   [:masses [:slice :f64]]
   [:forces [:slice p/Vec3]]
   [:bounds [:slice :f64]]
   [:saved-displacement [:slice :f64]]
   [:saved-velocity [:slice p/Vec3]]
   [:gravity p/Vec3]
   [:floor :bool]
   [:friction :f64]
   [:time :f64]])

(az/defstruct Observables {:layout :extern}
  [[:elastic-energy :f64] [:kinetic-energy :f64] [:potential-energy :f64]
   [:minimum-jacobian :f64] [:frequency-squared-bound :f64]
   [:minimum-height :f64] [:mass :f64] [:center p/Vec3] [:momentum p/Vec3]])

(az/defstruct AdvanceReport {:layout :extern}
  [[:completed :bool] [:substeps :u32] [:rejected :u32] [:time :f64]
   [:minimum-jacobian :f64] [:normal-impulse :f64]])

(az/defn position
  :- p/Vec3
  [[state [:* Dynamics]] [node :usize]]
  (let [mesh (az/field state mesh)
        displacement (az/field mesh displacement)]
    (p/add (az/index (az/field mesh positions) node)
           (p/v (az/index displacement (* 3 node))
                (az/index displacement (+ (* 3 node) 1))
                (az/index displacement (+ (* 3 node) 2))))))

(az/defn create!
  :- [:* Dynamics]
  [[mesh [:* fem/Model]] [density :f64] [young :f64] [poisson :f64]]
  (let [nodes (az/field (az/field mesh positions) len)
        state (catch ((az/field heap/page_allocator create) Dynamics)
                (debug/panic "Unable to allocate nonlinear FEM state" []))]
    (set! (az/deref state)
          (Dynamics {:mesh mesh :material (elastic/material young poisson)
                     :velocities (fem/allocate p/Vec3 nodes)
                     :masses (fem/allocate :f64 nodes)
                     :forces (fem/allocate p/Vec3 nodes)
                     :bounds (fem/allocate :f64 nodes)
                     :saved-displacement (fem/allocate :f64 (* 3 nodes))
                     :saved-velocity (fem/allocate p/Vec3 nodes)
                     :gravity (p/v 0.0 -9.81 0.0) :floor false :friction 0.0 :time 0.0}))
    (dotimes [i nodes]
      (az/set-many!
        (az/index (az/field state masses) i) 0.0
        (az/index (az/field state velocities) i) (p/v 0.0 0.0 0.0)))
    (dotimes [index (az/field (az/field mesh elements) len)]
      (let [element (az/index (az/field mesh elements) index)
            mass (* 0.25 density (az/field element volume))]
        (dotimes [local 4]
          (ak/+= (az/index (az/field state masses) (az/index (az/field element nodes) local)) mass))))
    state))

(az/defn destroy!
  :- :void
  [[state [:* Dynamics]]]
  ((az/field heap/page_allocator free) (az/field state velocities))
  ((az/field heap/page_allocator free) (az/field state masses))
  ((az/field heap/page_allocator free) (az/field state forces))
  ((az/field heap/page_allocator free) (az/field state bounds))
  ((az/field heap/page_allocator free) (az/field state saved-displacement))
  ((az/field heap/page_allocator free) (az/field state saved-velocity))
  ((az/field heap/page_allocator destroy) state))

(az/defn configure!
  :- :void
  [[state [:* Dynamics]] [gravity p/Vec3] [floor :bool] [friction :f64]]
  (az/set-many!
    (az/field state gravity) gravity
    (az/field state floor) floor
    (az/field state friction) friction))

(az/defn set-particle!
  :- :void
  [[state [:* Dynamics]] [node :usize] [point p/Vec3] [velocity p/Vec3]]
  (let [mesh (az/field state mesh)
        offset (p/add point (p/scale (az/index (az/field mesh positions) node) -1.0))]
    (dotimes [axis 3]
      (set! (az/index (az/field mesh displacement) (+ (* 3 node) axis)) (fem/component offset axis)))
    (set! (az/index (az/field state velocities) node) velocity)))

(az/defn deformation
  :- elastic/Matrix
  [[state [:* Dynamics]] [element fem/Element]]
  (let [origin (position state (az/index (az/field element nodes) 0))
        ^:var gradient (elastic/zero)]
    ;; Relative coordinates remove translation before forming the gradient.
    (dotimes [index 3]
      (let [local (+ index 1)
            point (position state (az/index (az/field element nodes) local))
            basis (az/index (az/field element gradients) local)]
        (set! gradient (elastic/add gradient (elastic/outer (p/add point (p/scale origin -1.0)) basis)))))
    gradient))

(az/defn elasticity-bound
  "Conservative Frobenius-norm bound on dP/dF, including volumetric curvature."
  :- :f64
  [[gradient elastic/Matrix] [parameters elastic/Material]]
  (let [invariant (elastic/inner gradient gradient)
        cofactors (elastic/cofactor gradient)
        mu (az/field parameters mu)
        lambda (az/field parameters lambda)
        pressure (* lambda (- (elastic/determinant gradient) (az/field parameters alpha)))
        denominator (+ 1.0 invariant)]
    (+ (* mu (- 1.0 (/ 1.0 denominator)))
       (/ (* 2.0 mu invariant) (* denominator denominator))
       (* lambda (elastic/inner cofactors cofactors))
       (* 2.0 (ak/abs pressure) (ak/sqrt invariant)))))

(az/defstruct ElasticObjective {:layout :extern}
  [[:elastic-energy :f64] [:minimum-jacobian :f64]])

(az/defn elastic-objective!
  "Implicit iterations need elastic energy and optionally forces. Explicit stability
  bounds and telemetry belong to the explicit step and saved-frame paths."
  :- ElasticObjective
  [[state [:* Dynamics]] [forces :bool]]
  (let [mesh (az/field state mesh)
        elements (az/field mesh elements)
        ^:var result (ElasticObjective {:elastic-energy 0.0 :minimum-jacobian 1.0e30})]
    (when forces
      (dotimes [node (az/field (az/field state forces) len)]
        (set! (az/index (az/field state forces) node) (p/v 0.0 0.0 0.0))))
    (dotimes [index (az/field elements len)]
      (let [element (az/index elements index)
            gradient (deformation state element)
            response (elastic/evaluate gradient (az/field state material))
            volume (az/field element volume)]
        (az/set-many!
          (az/field result elastic-energy)
          (+ (az/field result elastic-energy) (* volume (az/field response energy-density)))
          (az/field result minimum-jacobian)
          (ak/min (az/field result minimum-jacobian) (az/field response jacobian)))
        (when forces
          (dotimes [local 4]
            (let [node (az/index (az/field element nodes) local)
                  basis (az/index (az/field element gradients) local)]
              (set! (az/index (az/field state forces) node)
                    (p/add (az/index (az/field state forces) node)
                           (p/scale (elastic/apply-vector (az/field response pk1) basis)
                                    (- volume)))))))))
    result))

(az/defn evaluate!
  :- Observables
  [[state [:* Dynamics]]]
  (let [mesh (az/field state mesh)
        nodes (az/field (az/field mesh positions) len)
        ^:var result (Observables {:elastic-energy 0.0 :kinetic-energy 0.0 :potential-energy 0.0
                                  :minimum-jacobian 1.0e30 :frequency-squared-bound 0.0
                                  :minimum-height 1.0e30 :mass 0.0
                                  :center (p/v 0.0 0.0 0.0) :momentum (p/v 0.0 0.0 0.0)})]
    (dotimes [node nodes]
      (az/set-many!
        (az/index (az/field state forces) node) (p/v 0.0 0.0 0.0)
        (az/index (az/field state bounds) node) 0.0))
    (dotimes [index (az/field (az/field mesh elements) len)]
      (let [element (az/index (az/field mesh elements) index)
            gradient (deformation state element)
            response (elastic/evaluate gradient (az/field state material))
            volume (az/field element volume)
            tangent-bound (elasticity-bound gradient (az/field state material))
            ^{:var :f64} weighted-sum 0.0]
        (az/set-many!
          (az/field result elastic-energy)
          (+ (az/field result elastic-energy) (* volume (az/field response energy-density)))
          (az/field result minimum-jacobian)
          (ak/min (az/field result minimum-jacobian) (az/field response jacobian)))
        (dotimes [local 4]
          (let [node (az/index (az/field element nodes) local)
                basis (az/index (az/field element gradients) local)]
            (ak/+= weighted-sum (/ (p/length basis) (ak/sqrt (az/index (az/field state masses) node))))))
        (dotimes [local 4]
          (let [node (az/index (az/field element nodes) local)
                basis (az/index (az/field element gradients) local)]
            (set! (az/index (az/field state forces) node)
                  (p/add (az/index (az/field state forces) node)
                         (p/scale (elastic/apply-vector (az/field response pk1) basis) (- volume))))
            (ak/+= (az/index (az/field state bounds) node)
                   (/ (* volume tangent-bound (p/length basis) weighted-sum)
                      (ak/sqrt (az/index (az/field state masses) node))))))))
    (dotimes [node nodes]
      (let [point (position state node)
            velocity (az/index (az/field state velocities) node)
            mass (az/index (az/field state masses) node)]
        (az/set-many!
          (az/field result mass) (+ (az/field result mass) mass)
          (az/field result center) (p/add (az/field result center) (p/scale point mass))
          (az/field result momentum) (p/add (az/field result momentum) (p/scale velocity mass))
          (az/field result kinetic-energy)
          (+ (az/field result kinetic-energy) (* 0.5 mass (p/dot velocity velocity)))
          (az/field result potential-energy)
          (- (az/field result potential-energy) (* mass (p/dot (az/field state gravity) point)))
          (az/field result minimum-height) (ak/min (az/field result minimum-height) (az/field point y))
          (az/field result frequency-squared-bound)
          (ak/max (az/field result frequency-squared-bound) (az/index (az/field state bounds) node)))))
    (set! (az/field result center) (p/scale (az/field result center) (/ 1.0 (az/field result mass))))
    result))

(az/defn kick!
  :- :void
  [[state [:* Dynamics]] [dt :f64]]
  (let [mesh (az/field state mesh)]
    (dotimes [node (az/field (az/field state masses) len)]
      (let [mass (az/index (az/field state masses) node)
            force (az/index (az/field state forces) node)
            old (az/index (az/field state velocities) node)
            ^:var velocity (p/add old (p/scale (p/add (p/scale force (/ 1.0 mass))
                                                      (az/field state gravity)) dt))]
        (dotimes [axis 3]
          (when (az/index (az/field mesh fixed) (+ (* 3 node) axis))
            (if (ak/== axis 0) (set! (az/field velocity x) 0.0)
                (if (ak/== axis 1) (set! (az/field velocity y) 0.0)
                    (set! (az/field velocity z) 0.0)))))
        (set! (az/index (az/field state velocities) node) velocity)))))

(az/defn drift!
  :- :void
  [[state [:* Dynamics]] [dt :f64]]
  (let [mesh (az/field state mesh)]
    (dotimes [node (az/field (az/field state masses) len)]
      (let [velocity (az/index (az/field state velocities) node)]
        (dotimes [axis 3]
          (when (ak/! (az/index (az/field mesh fixed) (+ (* 3 node) axis)))
            (ak/+= (az/index (az/field mesh displacement) (+ (* 3 node) axis))
                   (* dt (fem/component velocity axis)))))))))

(az/defn ground-contact!
  "Inelastic nodal contact with a rigid plane and Coulomb impulse friction.
  Elastic rebound comes from FEM strain energy, not a restitution coefficient."
  :- :f64
  [[state [:* Dynamics]]]
  (when (ak/! (az/field state floor)) (ak/return 0.0))
  (let [mesh (az/field state mesh)
        ^{:var :f64} impulse 0.0]
    (dotimes [node (az/field (az/field state masses) len)]
      (let [point (position state node)]
        (when (<= (az/field point y) 0.0)
          (let [velocity (az/index (az/field state velocities) node)
                delta (ak/max 0.0 (- (az/field velocity y)))
                tangent (p/v (az/field velocity x) 0.0 (az/field velocity z))
                speed (p/length tangent)
                factor (if (> speed 0.0) (ak/max 0.0 (- 1.0 (/ (* (az/field state friction) delta) speed))) 1.0)]
            (az/set-many!
              (az/index (az/field mesh displacement) (+ (* 3 node) 1))
              (- (az/field (az/index (az/field mesh positions) node) y))
              (az/index (az/field state velocities) node)
              (p/v (* factor (az/field velocity x)) (ak/max 0.0 (az/field velocity y)) (* factor (az/field velocity z)))
              impulse (+ impulse (* delta (az/index (az/field state masses) node))))))))
    impulse))

(az/defn checkpoint!
  :- :void
  [[state [:* Dynamics]] [restore :bool]]
  (let [mesh (az/field state mesh)]
    (dotimes [i (az/field (az/field mesh displacement) len)]
      (if restore
        (set! (az/index (az/field mesh displacement) i) (az/index (az/field state saved-displacement) i))
        (set! (az/index (az/field state saved-displacement) i) (az/index (az/field mesh displacement) i))))
    (dotimes [i (az/field (az/field state velocities) len)]
      (if restore
        (set! (az/index (az/field state velocities) i) (az/index (az/field state saved-velocity) i))
        (set! (az/index (az/field state saved-velocity) i) (az/index (az/field state velocities) i))))))

(az/defn advance!
  "Velocity Verlet with a mass-scaled tangent bound and step rejection.
  No wall-clock budget. A failed step leaves the last accepted state available."
  :- AdvanceReport
  [[state [:* Dynamics]] [duration :f64] [maximum-step :f64]]
  (let [target (+ (az/field state time) duration)
        ^:var observation (evaluate! state)
        ^:var report (AdvanceReport {:completed false :substeps 0 :rejected 0
                                    :time (az/field state time)
                                    :minimum-jacobian (az/field observation minimum-jacobian)
                                    :normal-impulse 0.0})]
    (while (< (az/field state time) target)
      ;; Accumulated floating-point time can leave a sub-ulp physical interval.
      ;; Snap only that roundoff remainder, not an unresolved integration step.
      (when (<= (- target (az/field state time)) (* 3.552713678800501e-15 (ak/max 1.0 (ak/abs target))))
        (az/set-many!
          (az/field state time) target
          (az/field report time) target
          (az/field report completed) true)
        (ak/return report))
      (when (or (<= (az/field observation minimum-jacobian) 0.05)
                (ak/! (math/isFinite (az/field observation elastic-energy)))
                (ak/! (math/isFinite (az/field observation frequency-squared-bound))))
        (ak/return report))
      (let [remaining (- target (az/field state time))
            ^:var h (ak/min remaining (ak/min maximum-step
                                             (/ 0.35 (ak/sqrt (ak/max 1.0 (az/field observation frequency-squared-bound))))))
            ^{:var :f64} contact-impulse 0.0
            ^{:var :bool} accepted false]
        (checkpoint! state false)
        (while (ak/! accepted)
          (when (or (<= h 1.0e-12) (ak/== (+ (az/field state time) h) (az/field state time)))
            (ak/return report))
          (kick! state (* 0.5 h))
          (drift! state h)
          (az/set-many!
            contact-impulse (ground-contact! state)
            observation (evaluate! state))
          (if (and (> (az/field observation minimum-jacobian) 0.05)
                   (math/isFinite (az/field observation elastic-energy))
                   (math/isFinite (az/field observation frequency-squared-bound))
                   (<= (* h h (az/field observation frequency-squared-bound)) 0.25))
            (do
              (kick! state (* 0.5 h))
              (ak/+= contact-impulse (ground-contact! state))
              (set! accepted true))
            (do
              (checkpoint! state true)
              (az/set-many!
                observation (evaluate! state)
                h (* 0.5 h)
                (az/field report rejected) (+ (az/field report rejected) 1)))))
        (az/set-many!
          (az/field state time) (if (ak/== h remaining) target (+ (az/field state time) h))
          (az/field report substeps) (+ (az/field report substeps) 1)
          (az/field report time) (az/field state time)
          (az/field report normal-impulse) (+ (az/field report normal-impulse) contact-impulse)
          (az/field report minimum-jacobian)
          (ak/min (az/field report minimum-jacobian) (az/field observation minimum-jacobian)))))
    (set! (az/field report completed) true)
    report))

(az/defn particle-velocity
  :- p/Vec3
  [[state [:* Dynamics]] [node :usize]]
  (az/index (az/field state velocities) node))

(az/defn particle-force
  :- p/Vec3
  [[state [:* Dynamics]] [node :usize]]
  (az/index (az/field state forces) node))
