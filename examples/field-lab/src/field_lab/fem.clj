(ns field-lab.fem
  "Constant-strain tetrahedral, small-strain isotropic elasticity in SI units.
  Each job owns its native buffers. No global solver or live-scene mutation."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]))

(az/defstruct Element
  [[:nodes [:array 4 :usize]]
   [:gradients [:array 4 p/Vec3]]
   [:volume :f64]])

(az/defstruct Model
  [[:positions [:slice p/Vec3]]
   [:elements [:slice Element]]
   [:fixed [:slice :bool]]
   [:displacement [:slice :f64]]
   [:load [:slice :f64]]
   [:diagonal [:slice :f64]]
   [:residual [:slice :f64]]
   [:direction [:slice :f64]]
   [:product [:slice :f64]]
   [:mu :f64]
   [:lambda :f64]])

(az/defstruct Report {:layout :extern}
  [[:converged :bool]
   [:breakdown :bool]
   [:iterations :u32]
   [:residual :f64]
   [:relative-residual :f64]
   [:strain-energy :f64]])

(defmacro allocate [type size]
  `(catch ((az/field heap/page_allocator ~'alloc) ~type ~size)
     (debug/panic "Unable to allocate FEM job buffers" [])))

(az/defn create!
  :- [:* Model]
  [[nodes :usize] [elements :usize] [young :f64] [poisson :f64]]
  (let [model (catch ((az/field heap/page_allocator create) Model)
                (debug/panic "Unable to allocate FEM job" []))
        dofs (* 3 nodes)]
    (set! (az/deref model)
          (Model {:positions (allocate p/Vec3 nodes)
                  :elements (allocate Element elements)
                  :fixed (allocate :bool dofs)
                  :displacement (allocate :f64 dofs)
                  :load (allocate :f64 dofs)
                  :diagonal (allocate :f64 dofs)
                  :residual (allocate :f64 dofs)
                  :direction (allocate :f64 dofs)
                  :product (allocate :f64 dofs)
                  :mu (/ young (* 2.0 (+ 1.0 poisson)))
                  :lambda (/ (* young poisson) (* (+ 1.0 poisson) (- 1.0 (* 2.0 poisson))))}))
    (dotimes [i dofs]
      (az/set-many!
        (az/index (az/field model fixed) i) false
        (az/index (az/field model displacement) i) 0.0
        (az/index (az/field model load) i) 0.0))
    model))

(az/defn destroy!
  :- :void
  [[model [:* Model]]]
  ((az/field heap/page_allocator free) (az/field model positions))
  ((az/field heap/page_allocator free) (az/field model elements))
  ((az/field heap/page_allocator free) (az/field model fixed))
  ((az/field heap/page_allocator free) (az/field model displacement))
  ((az/field heap/page_allocator free) (az/field model load))
  ((az/field heap/page_allocator free) (az/field model diagonal))
  ((az/field heap/page_allocator free) (az/field model residual))
  ((az/field heap/page_allocator free) (az/field model direction))
  ((az/field heap/page_allocator free) (az/field model product))
  ((az/field heap/page_allocator destroy) model))

(az/defn set-node!
  :- :void
  [[model [:* Model]] [node :usize] [x :f64] [y :f64] [z :f64]]
  (set! (az/index (az/field model positions) node) (p/v x y z)))

(az/defn set-element!
  :- :bool
  [[model [:* Model]] [index :usize] [a :usize] [b :usize] [c :usize] [d :usize]]
  (let [points (az/field model positions)
        origin (az/index points a)
        ab (p/add (az/index points b) (p/scale origin -1.0))
        ac (p/add (az/index points c) (p/scale origin -1.0))
        ad (p/add (az/index points d) (p/scale origin -1.0))
        determinant (p/dot ab (p/cross ac ad))
        scale (* (p/length ab) (p/length ac) (p/length ad))]
    (when (or (<= scale 0.0) (<= (ak/abs determinant) (* 1.0e-12 scale)))
      (ak/return false))
    (let [gb (p/scale (p/cross ac ad) (/ 1.0 determinant))
          gc (p/scale (p/cross ad ab) (/ 1.0 determinant))
          gd (p/scale (p/cross ab ac) (/ 1.0 determinant))
          ga (p/scale (p/add (p/add gb gc) gd) -1.0)]
      (set! (az/index (az/field model elements) index)
            (Element {:nodes [a b c d]
                      :gradients [ga gb gc gd]
                      :volume (/ (ak/abs determinant) 6.0)}))))
  true)

(az/defn constrain!
  :- :void
  [[model [:* Model]] [dof :usize] [value :f64]]
  (az/set-many!
    (az/index (az/field model fixed) dof) true
    (az/index (az/field model displacement) dof) value))

(az/defn load!
  :- :void
  [[model [:* Model]] [dof :usize] [force :f64]]
  (ak/+= (az/index (az/field model load) dof) force))

(az/defn component
  :- :f64
  [[vector p/Vec3] [axis :usize]]
  (if (ak/== axis 0) (az/field vector x)
      (if (ak/== axis 1) (az/field vector y) (az/field vector z))))

;; Stress is a row-major 3×3 tensor; shear entries are tensor stresses, not
;; engineering strain components. The same constitutive law supplies K*u.
(az/defn element-stress
  :- [:array 9 :f64]
  [[model [:* Model]] [element Element] [values [:slice-const :f64]]]
  (let [^:var gradient (mem/zeroes (az/type [:array 9 :f64]))
        ^:var tensor (mem/zeroes (az/type [:array 9 :f64]))]
    (dotimes [local 4]
      (let [node (az/index (az/field element nodes) local)
            g (az/index (az/field element gradients) local)]
        (dotimes [row 3]
          (dotimes [column 3]
            (ak/+= (az/index gradient (+ (* 3 row) column))
                   (* (az/index values (+ (* 3 node) row)) (component g column)))))))
    (let [trace (+ (az/index gradient 0) (az/index gradient 4) (az/index gradient 8))]
      (dotimes [row 3]
        (dotimes [column 3]
          (set! (az/index tensor (+ (* 3 row) column))
                (+ (* (az/field model mu)
                      (+ (az/index gradient (+ (* 3 row) column))
                         (az/index gradient (+ (* 3 column) row))))
                   (if (ak/== row column) (* (az/field model lambda) trace) 0.0))))))
    tensor))

(az/defn multiply!
  :- :void
  [[model [:* Model]] [values [:slice-const :f64]] [result [:slice :f64]]]
  (dotimes [i (az/field result len)]
    (set! (az/index result i) 0.0))
  (dotimes [index (az/field (az/field model elements) len)]
    (let [element (az/index (az/field model elements) index)
          tensor (element-stress model element values)]
      (dotimes [local 4]
        (let [node (az/index (az/field element nodes) local)
              gradient (az/index (az/field element gradients) local)]
          (dotimes [row 3]
            (dotimes [column 3]
              (ak/+= (az/index result (+ (* 3 node) row))
                     (* (az/field element volume)
                        (az/index tensor (+ (* 3 row) column))
                        (component gradient column))))))))))

(az/defn assemble-diagonal!
  :- :bool
  [[model [:* Model]]]
  (let [diagonal (az/field model diagonal)]
    (dotimes [i (az/field diagonal len)]
      (set! (az/index diagonal i) 0.0))
    (dotimes [index (az/field (az/field model elements) len)]
      (let [element (az/index (az/field model elements) index)]
        (dotimes [local 4]
          (let [node (az/index (az/field element nodes) local)
                g (az/index (az/field element gradients) local)]
            (dotimes [axis 3]
              (ak/+= (az/index diagonal (+ (* 3 node) axis))
                     (* (az/field element volume)
                        (+ (* (az/field model mu) (p/dot g g))
                           (* (+ (az/field model lambda) (az/field model mu))
                              (component g axis) (component g axis))))))))))
    (dotimes [i (az/field diagonal len)]
      (when (and (ak/! (az/index (az/field model fixed) i))
                 (<= (az/index diagonal i) 0.0))
        (ak/return false))))
  true)

(az/defn update-residual!
  :- :f64
  [[model [:* Model]]]
  (multiply! model (az/field model displacement) (az/field model product))
  (let [^{:var :f64} squared 0.0]
    (dotimes [i (az/field (az/field model load) len)]
      (let [r (if (az/index (az/field model fixed) i) 0.0
                  (- (az/index (az/field model load) i)
                     (az/index (az/field model product) i)))]
        (set! (az/index (az/field model residual) i) r)
        (ak/+= squared (* r r))))
    (ak/sqrt squared)))

(az/defn solve!
  :- Report
  [[model [:* Model]] [relative-tolerance :f64] [absolute-tolerance :f64] [limit :u32]]
  (let [dofs (az/field (az/field model displacement) len)
        initial (update-residual! model)
        tolerance (ak/max absolute-tolerance (* relative-tolerance initial))
        ^{:var :f64} norm initial
        ^{:var :f64} rho 0.0
        ^:var report (Report {:converged false :breakdown false :iterations 0
                             :residual initial :relative-residual 1.0 :strain-energy 0.0})]
    (when (ak/! (assemble-diagonal! model))
      (set! (az/field report breakdown) true)
      (ak/return report))
    (dotimes [i dofs]
      (let [z (if (az/index (az/field model fixed) i) 0.0
                  (/ (az/index (az/field model residual) i)
                     (az/index (az/field model diagonal) i)))]
        (set! (az/index (az/field model direction) i) z)
        (ak/+= rho (* z (az/index (az/field model residual) i)))))
    (while (and (> norm tolerance) (< (az/field report iterations) limit))
      (multiply! model (az/field model direction) (az/field model product))
      (let [^{:var :f64} curvature 0.0]
        (dotimes [i dofs]
          (ak/+= curvature (* (az/index (az/field model direction) i)
                              (az/index (az/field model product) i))))
        (when (or (<= curvature 0.0) (ak/! (math/isFinite curvature)))
          (set! (az/field report breakdown) true)
          (ak/break))
        (let [alpha (/ rho curvature)]
          (dotimes [i dofs]
            (when (ak/! (az/index (az/field model fixed) i))
              (ak/+= (az/index (az/field model displacement) i)
                     (* alpha (az/index (az/field model direction) i))))
            (ak/-= (az/index (az/field model residual) i)
                   (if (az/index (az/field model fixed) i) 0.0
                       (* alpha (az/index (az/field model product) i)))))))
      (ak/+= (az/field report iterations) 1)
      (let [^{:var :f64} next-rho 0.0
            ^{:var :f64} squared 0.0]
        (dotimes [i dofs]
          (let [r (az/index (az/field model residual) i)]
            (ak/+= squared (* r r))
            (when (ak/! (az/index (az/field model fixed) i))
              (ak/+= next-rho (/ (* r r) (az/index (az/field model diagonal) i))))))
        (set! norm (ak/sqrt squared))
        ;; Check the true residual before accepting convergence. Restart only
        ;; if the recurrence underestimated it; frequent restarts destroy the
        ;; conjugate directions needed for slender structures.
        (if (<= norm tolerance)
          (do
            (az/set-many!
              norm (update-residual! model)
              next-rho 0.0)
            (dotimes [i dofs]
              (let [z (if (az/index (az/field model fixed) i) 0.0
                          (/ (az/index (az/field model residual) i)
                             (az/index (az/field model diagonal) i)))]
                (set! (az/index (az/field model direction) i) z)
                (ak/+= next-rho (* z (az/index (az/field model residual) i))))))
          (dotimes [i dofs]
            (set! (az/index (az/field model direction) i)
                  (if (az/index (az/field model fixed) i) 0.0
                      (+ (/ (az/index (az/field model residual) i)
                            (az/index (az/field model diagonal) i))
                         (* (/ next-rho rho) (az/index (az/field model direction) i)))))))
        (set! rho next-rho)))
    (set! norm (update-residual! model))
    (az/set-many!
      (az/field report residual) norm
      (az/field report relative-residual) (if (> initial 0.0) (/ norm initial) 0.0)
      (az/field report converged) (and (ak/! (az/field report breakdown))
                                     (math/isFinite norm) (<= norm tolerance)))
    (dotimes [i dofs]
      (ak/+= (az/field report strain-energy)
             (* 0.5 (az/index (az/field model displacement) i)
                (az/index (az/field model product) i))))
    report))

(az/defn displacement
  :- :f64
  [[model [:* Model]] [dof :usize]]
  (az/index (az/field model displacement) dof))

(az/defn reaction
  :- :f64
  [[model [:* Model]] [dof :usize]]
  ;; Product is K*u after solve!, including constrained rows.
  (- (az/index (az/field model product) dof) (az/index (az/field model load) dof)))

(az/defn stress-component
  :- :f64
  [[model [:* Model]] [element :usize] [entry :usize]]
  (az/index (element-stress model (az/index (az/field model elements) element)
                            (az/field model displacement)) entry))
