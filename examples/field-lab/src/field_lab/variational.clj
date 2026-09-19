(ns field-lab.variational
  "Implicit FEM with convergent IPC contact energy and collision-filtered Newton steps.
  Native sparse algebra/contact derivatives complement AguaFria's FEM assembly."
  (:require [clojure.java.io :as io]
            [aguafria.std]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [field-lab.build :as build]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.hyperelastic :as elastic]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.coupled-fem :as coupled]))

(defonce native-library (delay (build/configure-variational!)))

(force native-library)

(az/defextern pitoco_aguafria_variational_create
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- [:optional [:* :anyopaque]]
  [[nodes :u32] [rest [:c-pointer :f64]] [initial [:c-pointer :f64]] [face-count :u32] [faces [:c-pointer :u32]]
   [tet-count :u32] [cells [:c-pointer :u32]] [floor [:c-pointer :u8]]])

(az/defextern pitoco_aguafria_variational_destroy
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :void [[handle [:optional [:* :anyopaque]]]])

(az/defextern pitoco_aguafria_variational_error
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- [:pointer {:size :c :const? true} :u8] [])

(az/defextern pitoco_aguafria_variational_evaluate
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [positions [:c-pointer :f64]] [clearance :f64]
   [pressure :f64] [derivatives :u32] [gradient [:c-pointer :f64]] [energy [:c-pointer :f64]] [friction-energy [:c-pointer :f64]]])

(az/defextern pitoco_aguafria_variational_begin_step
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [start [:c-pointer :f64]] [coefficients [:c-pointer :f64]]
   [duration :f64] [clearance :f64] [pressure :f64]])

(az/defextern pitoco_aguafria_variational_lag_friction
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [positions [:c-pointer :f64]] [clearance :f64] [pressure :f64]])

(az/defextern pitoco_aguafria_variational_friction_origin
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [origin [:c-pointer :f64]]])

(az/defextern pitoco_aguafria_variational_matrix_begin
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [masses [:c-pointer :f64]] [scale :f64]])

(az/defextern pitoco_aguafria_variational_add_projected_element
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [nodes [:c-pointer :u32]] [values [:c-pointer :f64]]])

(az/defextern symmetric-eigenvectors!
  {:zig/prefix "pub extern" :zig/name "@\"dsyev$NEWLAPACK\"" :attrs #{:public}}
  :- :void
  [[vectors [:c-pointer :u8]] [triangle [:c-pointer :u8]] [count [:c-pointer :i32]]
   [matrix [:c-pointer :f64]] [leading [:c-pointer :i32]] [eigenvalues [:c-pointer :f64]]
   [scratch [:c-pointer :f64]] [scratch-count [:c-pointer :i32]] [status [:c-pointer :i32]]])

(az/defextern pitoco_aguafria_variational_solve
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [gradient [:c-pointer :f64]] [direction [:c-pointer :f64]]])

(az/defextern pitoco_aguafria_variational_safe_step
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :f64
  [[handle [:optional [:* :anyopaque]]] [start [:c-pointer :f64]] [direction [:c-pointer :f64]]])

(az/defextern pitoco_aguafria_variational_trial
  {:zig/prefix "pub extern" :attrs #{:public}}
  :- :u32
  [[handle [:optional [:* :anyopaque]]] [start [:c-pointer :f64]] [direction [:c-pointer :f64]]
   [alpha :f64] [output [:c-pointer :f64]]])

(az/defstruct Workspace
  [[:handle [:optional [:* :anyopaque]]] [:assembly [:* coupled/Assembly]]
   [:offsets [:slice :usize]] [:masses [:slice :f64]] [:friction [:slice :f64]] [:has-friction :bool]
   [:consistent-mass :bool] [:densities [:slice :f64]]
   [:mass-input [:slice :f64]] [:mass-product [:slice :f64]] [:mass-solution [:slice :f64]]
   [:mass-residual [:slice :f64]] [:mass-search [:slice :f64]]
   [:mass-rhs [:slice :f64]] [:mass-scales [:slice :f64]]
   [:positions [:slice :f64]] [:base [:slice :f64]] [:trial [:slice :f64]] [:iterate [:slice :f64]]
   [:predicted [:slice :f64]] [:gradient [:slice :f64]] [:direction [:slice :f64]]
   [:contact-gradient [:slice :f64]]
   [:previous-displacement [:slice :f64]] [:previous-velocity-change [:slice :f64]]
   [:previous-step :f64] [:history-time :f64] [:history-ready :bool]
   [:previous-contact-impulse p/Vec3]])

(az/defn capture!
  :- :void [[workspace [:* Workspace]]]
  (let [bodies (az/field (az/field workspace assembly) bodies)]
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            offset (az/index (az/field workspace offsets) body)]
        (dotimes [node (az/field (az/field state masses) len)]
          (dotimes [axis 3]
            (set! (az/index (az/field workspace positions) (+ (* 3 (+ offset node)) axis))
                  (fem/component (dynamics/position state node) axis))))))))

(az/defn scatter!
  :- :void [[workspace [:* Workspace]] [values [:slice :f64]]]
  (let [bodies (az/field (az/field workspace assembly) bodies)]
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            mesh (az/field state mesh)
            offset (az/index (az/field workspace offsets) body)]
        (dotimes [node (az/field (az/field state masses) len)]
          (dotimes [axis 3]
            (set! (az/index (az/field mesh displacement) (+ (* 3 node) axis))
                  (- (az/index values (+ (* 3 (+ offset node)) axis))
                     (fem/component (az/index (az/field mesh positions) node) axis)))))))))

(az/defn create!
  :- [:* Workspace] [[assembly [:* coupled/Assembly]]]
  (let [bodies (az/field assembly bodies)
        ^{:var :usize} nodes 0
        ^{:var :usize} faces 0
        ^{:var :usize} tetrahedra 0]
    (dotimes [i (az/field bodies len)]
      (let [body (az/index bodies i)
            state (az/field body state)]
        (ak/+= nodes (az/field (az/field state masses) len))
        (ak/+= faces (az/field (az/field (az/field body surface) faces) len))
        (ak/+= tetrahedra (az/field (az/field (az/field state mesh) elements) len))))
    (let [workspace (catch ((az/field heap/page_allocator create) Workspace)
                      (debug/panic "Unable to allocate variational FEM workspace" []))
          indices (fem/allocate :u32 (* 3 faces))
          cells (fem/allocate :u32 (* 4 tetrahedra))
          floor (fem/allocate :u8 nodes)
          ^{:var :usize} offset 0
          ^{:var :usize} face-offset 0
          ^{:var :usize} cell-offset 0]
      (defer ((az/field heap/page_allocator free) indices))
      (defer ((az/field heap/page_allocator free) cells))
      (defer ((az/field heap/page_allocator free) floor))
      (set! (az/deref workspace)
            (Workspace {:handle null :assembly assembly :offsets (fem/allocate :usize (az/field bodies len))
                        :consistent-mass false :densities (fem/allocate :f64 (az/field bodies len))
                        :mass-input (fem/allocate :f64 (* 3 nodes)) :mass-product (fem/allocate :f64 (* 3 nodes))
                        :mass-solution (fem/allocate :f64 (* 3 nodes)) :mass-residual (fem/allocate :f64 (* 3 nodes))
                        :mass-search (fem/allocate :f64 (* 3 nodes))
                        :mass-rhs (fem/allocate :f64 (* 3 nodes))
                        :mass-scales (fem/allocate :f64 (* 3 (az/field bodies len)))
                        :masses (fem/allocate :f64 nodes) :friction (fem/allocate :f64 nodes) :has-friction false :positions (fem/allocate :f64 (* 3 nodes))
                        :base (fem/allocate :f64 (* 3 nodes)) :trial (fem/allocate :f64 (* 3 nodes))
                        :iterate (fem/allocate :f64 (* 3 nodes))
                        :predicted (fem/allocate :f64 (* 3 nodes)) :gradient (fem/allocate :f64 (* 3 nodes))
                        :direction (fem/allocate :f64 (* 3 nodes)) :contact-gradient (fem/allocate :f64 (* 3 nodes))
                        :previous-displacement (fem/allocate :f64 (* 3 nodes))
                        :previous-velocity-change (fem/allocate :f64 (* 3 nodes))
                        :previous-step 0.0 :history-time 0.0 :history-ready false
                        :previous-contact-impulse (p/v 0.0 0.0 0.0)}))
      (dotimes [i (az/field bodies len)]
        (let [body (az/index bodies i)
              state (az/field body state)
              mesh (az/field state mesh)
              boundary (az/field (az/field body surface) faces)]
          (set! (az/index (az/field workspace offsets) i) offset)
          (dotimes [node (az/field (az/field state masses) len)]
            (az/set-many!
              (az/index (az/field workspace masses) (+ offset node)) (az/index (az/field state masses) node)
              (az/index floor (+ offset node)) (if (az/field state floor) 1 0)
              (az/index (az/field workspace friction) (+ offset node)) (az/field state friction)
              (az/field workspace has-friction) (or (az/field workspace has-friction) (> (az/field state friction) 0.0)))
            (dotimes [axis 3]
              (set! (az/index (az/field workspace positions) (+ (* 3 (+ offset node)) axis))
                    (fem/component (az/index (az/field mesh positions) node) axis))))
          (dotimes [face (az/field boundary len)]
            (dotimes [local 3]
              (set! (az/index indices (+ (* 3 (+ face-offset face)) local))
                    (ak/intCast (+ offset (az/index (az/index boundary face) local))))))
          (dotimes [cell (az/field (az/field mesh elements) len)]
            (dotimes [local 4]
              (set! (az/index cells (+ (* 4 (+ cell-offset cell)) local))
                    (ak/intCast (+ offset (az/index (az/field (az/index (az/field mesh elements) cell) nodes) local))))))
          (az/set-many!
            offset (+ offset (az/field (az/field state masses) len))
            face-offset (+ face-offset (az/field boundary len))
            cell-offset (+ cell-offset (az/field (az/field mesh elements) len)))))
      (dotimes [index (* 3 nodes)]
        (set! (az/index (az/field workspace base) index) (az/index (az/field workspace positions) index)))
      (capture! workspace)
      (set! (az/field workspace handle)
            (pitoco_aguafria_variational_create (ak/intCast nodes) (az/field (az/field workspace base) ptr)
                                       (az/field (az/field workspace positions) ptr)
                                       (ak/intCast faces) (az/field indices ptr)
                                       (ak/intCast tetrahedra) (az/field cells ptr) (az/field floor ptr)))
      workspace)))

(az/defn destroy!
  :- :void [[workspace [:* Workspace]]]
  (pitoco_aguafria_variational_destroy (az/field workspace handle))
  ((az/field heap/page_allocator free) (az/field workspace offsets))
  ((az/field heap/page_allocator free) (az/field workspace densities))
  ((az/field heap/page_allocator free) (az/field workspace mass-input))
  ((az/field heap/page_allocator free) (az/field workspace mass-product))
  ((az/field heap/page_allocator free) (az/field workspace mass-solution))
  ((az/field heap/page_allocator free) (az/field workspace mass-residual))
  ((az/field heap/page_allocator free) (az/field workspace mass-search))
  ((az/field heap/page_allocator free) (az/field workspace mass-rhs))
  ((az/field heap/page_allocator free) (az/field workspace mass-scales))
  ((az/field heap/page_allocator free) (az/field workspace masses))
  ((az/field heap/page_allocator free) (az/field workspace friction))
  ((az/field heap/page_allocator free) (az/field workspace positions))
  ((az/field heap/page_allocator free) (az/field workspace base))
  ((az/field heap/page_allocator free) (az/field workspace trial))
  ((az/field heap/page_allocator free) (az/field workspace iterate))
  ((az/field heap/page_allocator free) (az/field workspace predicted))
  ((az/field heap/page_allocator free) (az/field workspace gradient))
  ((az/field heap/page_allocator free) (az/field workspace direction))
  ((az/field heap/page_allocator free) (az/field workspace contact-gradient))
  ((az/field heap/page_allocator free) (az/field workspace previous-displacement))
  ((az/field heap/page_allocator free) (az/field workspace previous-velocity-change))
  ((az/field heap/page_allocator destroy) workspace))

(az/defn valid-workspace?
  :- :bool [[workspace [:* Workspace]]]
  (ak/!= (az/field workspace handle) null))

(az/defn error-byte
  :- :u8 [[index :usize]]
  (az/index (pitoco_aguafria_variational_error) index))

(defn native-error []
  (apply str (map char (take-while pos? (map error-byte (range 4096))))))

(az/defn configure-mass!
  "Select inertia before the first step. Each current Dynamics body has uniform
  reference density; verify its nodal row sums before reconstructing that density."
  :- :bool [[workspace [:* Workspace]] [consistent :bool]]
  (when (> (az/field workspace previous-step) 0.0) (ak/return false))
  (when consistent
    (dotimes [index (az/field (az/field workspace mass-input) len)]
      (set! (az/index (az/field workspace mass-input) index) 0.0))
    (let [bodies (az/field (az/field workspace assembly) bodies)]
      (dotimes [body (az/field bodies len)]
        (let [state (az/field (az/index bodies body) state)
              elements (az/field (az/field state mesh) elements)
              offset (az/index (az/field workspace offsets) body)
              ^{:var :f64} total-mass 0.0
              ^{:var :f64} volume 0.0]
          (dotimes [node (az/field (az/field state masses) len)]
            (let [mass (az/index (az/field workspace masses) (+ offset node))]
              (when (or (ak/! (math/isFinite mass)) (<= mass 0.0)) (ak/return false))
              (ak/+= total-mass mass)))
          (dotimes [index (az/field elements len)]
            (ak/+= volume (az/field (az/index elements index) volume)))
          (let [density (/ total-mass volume)]
            (when (or (ak/! (math/isFinite density)) (<= density 0.0)) (ak/return false))
            (set! (az/index (az/field workspace densities) body) density)
            (dotimes [index (az/field elements len)]
              (let [element (az/index elements index)
                    mass (* 0.25 density (az/field element volume))]
                (dotimes [local 4]
                  (ak/+= (az/index (az/field workspace mass-input)
                                    (+ offset (az/index (az/field element nodes) local))) mass)))))
          (dotimes [node (az/field (az/field state masses) len)]
            (let [index (+ offset node)
                  actual (az/index (az/field workspace masses) index)
                  expected (az/index (az/field workspace mass-input) index)]
              (when (> (ak/abs (- actual expected)) (* 1e-10 actual)) (ak/return false))))))))
  (set! (az/field workspace consistent-mass) consistent)
  true)

(az/defn mass-product!
  "Apply the selected mass operator; input/output must be distinct buffers."
  :- :void
  [[workspace [:* Workspace]] [input [:slice :f64]] [output [:slice :f64]]]
  (debug/assert (ak/!= (az/field input ptr) (az/field output ptr)))
  (if (ak/! (az/field workspace consistent-mass))
    (dotimes [index (az/field input len)]
      (set! (az/index output index)
            (* (az/index input index) (az/index (az/field workspace masses) (ak/divTrunc index 3)))))
    (do
      (dotimes [index (az/field output len)] (set! (az/index output index) 0.0))
      (let [bodies (az/field (az/field workspace assembly) bodies)]
        (dotimes [body (az/field bodies len)]
          (let [elements (az/field (az/field (az/field (az/index bodies body) state) mesh) elements)
                offset (az/index (az/field workspace offsets) body)
                density (az/index (az/field workspace densities) body)]
            (dotimes [element-index (az/field elements len)]
              (let [element (az/index elements element-index)
                    weight (* 0.05 density (az/field element volume))]
                (dotimes [axis 3]
                  (let [^{:var :f64} sum 0.0]
                    (dotimes [local 4]
                      (ak/+= sum (az/index input (+ (* 3 (+ offset (az/index (az/field element nodes) local))) axis))))
                    (dotimes [local 4]
                      (let [index (+ (* 3 (+ offset (az/index (az/field element nodes) local))) axis)]
                        (ak/+= (az/index output index) (* weight (+ sum (az/index input index))))))))))))))))

(az/defn mass-backward-error!
  "Check max |rhs-M*x|/(|rhs|+|M|*|x|), using the recomputed residual.
  Both supported mass operators are entrywise nonnegative, so M*|x|=|M|*|x|.
  Diagnostic only; scratch search/product buffers are overwritten."
  :- :f64 [[workspace [:* Workspace]] [rhs [:slice :f64]]]
  (let [search (az/field workspace mass-search)
        product (az/field workspace mass-product)
        safe (* 2.2250738585072014e-308 (ak/as :f64 (ak/floatFromInt (+ (az/field rhs len) 1))))
        ^{:var :f64} backward-error 0.0]
    (dotimes [index (az/field rhs len)]
      (set! (az/index search index) (ak/abs (az/index (az/field workspace mass-solution) index))))
    (mass-product! workspace search product)
    (dotimes [index (az/field rhs len)]
      (let [denominator (+ (ak/abs (az/index rhs index)) (az/index product index))
            residual (ak/abs (az/index (az/field workspace mass-residual) index))]
        (when (or (ak/! (math/isFinite denominator)) (ak/! (math/isFinite residual))) (ak/return 1e300))
        (if (> denominator 0.0)
          (set! backward-error (ak/max backward-error (/ (+ safe residual) denominator)))
          (when (> residual 0.0) (ak/return 1e300)))))
    backward-error))

(az/defn mass-relative-residual!
  "Largest ||r||_(Ml^-1)/||rhs||_(Ml^-1) over independent body/axis blocks.
  Mc lies between Ml/5 and Ml, so this bounds the relative Ml-weighted
  solution error by five times this value. Normalize before squaring."
  :- :f64 [[workspace [:* Workspace]] [rhs [:slice :f64]]]
  (let [bodies (az/field (az/field workspace assembly) bodies)
        residual (az/field workspace mass-residual)
        masses (az/field workspace masses)
        ^{:var :f64} maximum 0.0]
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            offset (az/index (az/field workspace offsets) body)
            count (az/field (az/field state masses) len)]
        (dotimes [axis 3]
          (let [^{:var :f64} scale 0.0
                ^{:var :f64} rhs-squared 0.0
                ^{:var :f64} residual-squared 0.0]
            (dotimes [node count]
              (let [index (+ (* 3 (+ offset node)) axis)
                    value (/ (ak/abs (az/index rhs index))
                             (ak/sqrt (az/index masses (+ offset node))))]
                (when (ak/! (math/isFinite value)) (ak/return 1e300))
                (set! scale (ak/max scale value))))
            (dotimes [node count]
              (let [index (+ (* 3 (+ offset node)) axis)
                    value (az/index residual index)]
                (when (ak/! (math/isFinite value)) (ak/return 1e300))
                (if (ak/== scale 0.0)
                  (when (ak/!= value 0.0) (ak/return 1e300))
                  (let [root-mass (ak/sqrt (az/index masses (+ offset node)))
                        normalized-rhs (/ (/ (az/index rhs index) root-mass) scale)
                        normalized-residual (/ (/ value root-mass) scale)]
                    (ak/+= rhs-squared (* normalized-rhs normalized-rhs))
                    (ak/+= residual-squared (* normalized-residual normalized-residual))))))
            (when (> scale 0.0)
              (let [relative (ak/sqrt (/ residual-squared rhs-squared))]
                (when (ak/! (math/isFinite relative)) (ak/return 1e300))
                (set! maximum (ak/max maximum relative))))))))
    maximum))

(az/defn solve-scaled-mass!
  "Jacobi-preconditioned CG for M*a=rhs. For P1 tets, Ml/5 <= M <= Ml,
  so the preconditioned condition number is at most five. Verify the true
  residual before success; results are private workspace scratch storage."
  :- :bool [[workspace [:* Workspace]] [rhs [:slice :f64]]]
  (let [solution (az/field workspace mass-solution)
        residual (az/field workspace mass-residual)
        search (az/field workspace mass-search)
        product (az/field workspace mass-product)
        masses (az/field workspace masses)
        ^{:var :f64} rho 0.0]
    (dotimes [index (az/field rhs len)]
      (let [value (az/index rhs index)
            preconditioned (/ value (az/index masses (ak/divTrunc index 3)))]
        (az/set-many!
          (az/index solution index) 0.0
          (az/index residual index) value
          (az/index search index) preconditioned)
        (ak/+= rho (* value preconditioned))))
    (when (ak/! (math/isFinite rho)) (ak/return false))
    (when (ak/== rho 0.0)
      (dotimes [index (az/field rhs len)]
        (when (ak/!= (az/index rhs index) 0.0) (ak/return false)))
      (ak/return true))
    (let [threshold (ak/max 1e-300 (* 1e-24 rho))]
      (dotimes [_ 64]
        (mass-product! workspace search product)
        (let [^{:var :f64} denominator 0.0
              ^{:var :f64} next-rho 0.0]
          (dotimes [index (az/field rhs len)]
            (ak/+= denominator (* (az/index search index) (az/index product index))))
          (when (or (ak/! (math/isFinite denominator)) (<= denominator 0.0)) (ak/return false))
          (let [alpha (/ rho denominator)]
            (dotimes [index (az/field rhs len)]
              (ak/+= (az/index solution index) (* alpha (az/index search index)))
              (ak/-= (az/index residual index) (* alpha (az/index product index)))
              (ak/+= next-rho (/ (* (az/index residual index) (az/index residual index))
                                 (az/index masses (ak/divTrunc index 3))))))
          (when (ak/! (math/isFinite next-rho)) (ak/return false))
          (when (<= next-rho threshold)
            (mass-product! workspace solution product)
            (set! next-rho 0.0)
            (dotimes [index (az/field rhs len)]
              (let [value (- (az/index rhs index) (az/index product index))]
                (set! (az/index residual index) value)
                (ak/+= next-rho (/ (* value value) (az/index masses (ak/divTrunc index 3))))))
            (when (and (<= next-rho threshold) (<= (mass-relative-residual! workspace rhs) 1e-12))
              (ak/return true))
            ;; Restart from the explicitly recomputed residual if recurrence
            ;; roundoff was optimistic. No unconverged result is accepted.
            (set! rho next-rho)
            (dotimes [index (az/field rhs len)]
              (set! (az/index search index) (/ (az/index residual index) (az/index masses (ak/divTrunc index 3)))))
            (ak/continue))
          (let [beta (/ next-rho rho)]
            (dotimes [index (az/field rhs len)]
              (set! (az/index search index)
                    (+ (/ (az/index residual index) (az/index masses (ak/divTrunc index 3)))
                       (* beta (az/index search index)))))
            (set! rho next-rho)))))
    false))

(az/defn solve-mass!
  "Equilibrate each independent body/axis before CG. This prevents a large
  vertical load from hiding transverse forces near roundoff. Scalar block
  scaling commutes with these mass operators; direction-coupled operators
  would need a different transformation. Input and solution must not alias."
  :- :bool [[workspace [:* Workspace]] [rhs [:slice :f64]]]
  (let [bodies (az/field (az/field workspace assembly) bodies)
        normalized (az/field workspace mass-rhs)
        scales (az/field workspace mass-scales)
        masses (az/field workspace masses)
        solution (az/field workspace mass-solution)]
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            offset (az/index (az/field workspace offsets) body)
            count (az/field (az/field state masses) len)]
        (dotimes [axis 3]
          (let [^{:var :f64} scale 0.0]
            (dotimes [node count]
              (let [index (+ (* 3 (+ offset node)) axis)
                    value (/ (ak/abs (az/index rhs index))
                             (ak/sqrt (az/index masses (+ offset node))))]
                (when (ak/! (math/isFinite value)) (ak/return false))
                (set! scale (ak/max scale value))))
            (when (ak/== scale 0.0) (set! scale 1.0))
            (set! (az/index scales (+ (* 3 body) axis)) scale)
            (dotimes [node count]
              (let [index (+ (* 3 (+ offset node)) axis)]
                (set! (az/index normalized index) (/ (az/index rhs index) scale))))))))
    (when (ak/! (solve-scaled-mass! workspace normalized)) (ak/return false))
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            offset (az/index (az/field workspace offsets) body)]
        (dotimes [node (az/field (az/field state masses) len)]
          (dotimes [axis 3]
            (let [index (+ (* 3 (+ offset node)) axis)]
              (ak/*= (az/index solution index) (az/index scales (+ (* 3 body) axis)))
              (when (ak/! (math/isFinite (az/index solution index))) (ak/return false)))))))
    ;; Check the original equations too, including rounding during rescaling.
    (mass-product! workspace solution (az/field workspace mass-product))
    (dotimes [index (az/field rhs len)]
      (set! (az/index (az/field workspace mass-residual) index)
            (- (az/index rhs index) (az/index (az/field workspace mass-product) index))))
    (<= (mass-relative-residual! workspace rhs) 1e-12)))

(az/defn body-observables!
  "Telemetry for this context's mass model. Uniform gravity, mass, center and
  linear momentum have identical row sums; kinetic energy needs the full operator."
  :- dynamics/Observables [[workspace [:* Workspace]] [body :usize]]
  (let [state (az/field (az/index (az/field (az/field workspace assembly) bodies) body) state)
        ^:var result (dynamics/evaluate! state)]
    (when (az/field workspace consistent-mass)
      (set! (az/field result kinetic-energy) 0.0)
      (let [elements (az/field (az/field state mesh) elements)
            density (az/index (az/field workspace densities) body)]
        (dotimes [index (az/field elements len)]
          (let [element (az/index elements index)
                ^{:var p/Vec3} sum (p/v 0.0 0.0 0.0)
                ^{:var :f64} squares 0.0]
            (dotimes [local 4]
              (let [velocity (dynamics/particle-velocity state (az/index (az/field element nodes) local))]
                (set! sum (p/add sum velocity))
                (ak/+= squares (p/dot velocity velocity))))
            (ak/+= (az/field result kinetic-energy)
                   (* 0.025 density (az/field element volume) (+ squares (p/dot sum sum)))))))
      ;; A conservative bound follows from M >= Ml/5. This context does not
      ;; invoke the explicit diagonal-mass kick used elsewhere in Dynamics.
      (ak/*= (az/field result frequency-squared-bound) 5.0))
    result))

(defn with-context!
  ([assembly f] (with-context! assembly {:mass-model :lumped} f))
  ([assembly {:keys [mass-model] :or {mass-model :lumped}} f]
   (when-not (#{:lumped :consistent} mass-model)
     (throw (ex-info "Use lumped or consistent tetrahedral mass" {:mass-model mass-model})))
   (let [workspace (create! assembly)]
     (try
       (when-not (valid-workspace? workspace)
         (throw (ex-info "Unable to initialize IPC contact" {:native-error (native-error)})))
       (when-not (configure-mass! workspace (= :consistent mass-model))
         (throw (ex-info "Consistent mass requires uniform reference density within each body" {})))
       (f workspace)
       (finally (destroy! workspace))))))

(az/defstruct Objective {:layout :extern}
  [[:valid :bool] [:energy :f64] [:elastic-energy :f64] [:contact-energy :f64] [:friction-energy :f64]
   [:minimum-jacobian :f64] [:residual :f64]])

(az/defn objective!
  :- Objective
  [[workspace [:* Workspace]] [duration :f64] [clearance :f64] [pressure :f64] [derivatives :bool]]
  (let [bodies (az/field (az/field workspace assembly) bodies)
        scale (* duration duration)
        ^:var result (Objective {:valid false :energy 0.0 :elastic-energy 0.0 :contact-energy 0.0 :friction-energy 0.0
                                 :minimum-jacobian 1.0e300 :residual 0.0})
        ^{:var :f64} barrier 0.0
        ^{:var :f64} friction 0.0]
    (capture! workspace)
    (dotimes [body (az/field bodies len)]
      (let [observation (dynamics/elastic-objective! (az/field (az/index bodies body) state) derivatives)]
        (ak/+= (az/field result elastic-energy) (az/field observation elastic-energy))
        (set! (az/field result minimum-jacobian)
              (ak/min (az/field result minimum-jacobian) (az/field observation minimum-jacobian)))))
    (when (or (ak/! (math/isFinite (az/field result minimum-jacobian)))
              (<= (az/field result minimum-jacobian) 0.0)) (ak/return result))
    (when (ak/!= (pitoco_aguafria_variational_evaluate (az/field workspace handle)
                                             (az/field (az/field workspace positions) ptr)
                                             clearance pressure (if derivatives 2 0)
                                             (az/field (az/field workspace contact-gradient) ptr) (ak/& barrier) (ak/& friction)) 0)
      (ak/return result))
    (az/set-many!
      (az/field result contact-energy) barrier
      (az/field result friction-energy) friction
      (az/field result energy) (* scale (+ barrier friction (az/field result elastic-energy))))
    (when (az/field workspace consistent-mass)
      (dotimes [index (az/field (az/field workspace positions) len)]
        (set! (az/index (az/field workspace mass-input) index)
              (- (az/index (az/field workspace positions) index) (az/index (az/field workspace predicted) index))))
      (mass-product! workspace (az/field workspace mass-input) (az/field workspace mass-product)))
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            offset (az/index (az/field workspace offsets) body)]
        (dotimes [node (az/field (az/field state masses) len)]
          (let [mass (az/index (az/field state masses) node)
                force (if derivatives (dynamics/particle-force state node) (p/v 0.0 0.0 0.0))]
            (dotimes [axis 3]
              (let [index (+ (* 3 (+ offset node)) axis)
                    delta (- (az/index (az/field workspace positions) index)
                             (az/index (az/field workspace predicted) index))]
                (ak/+= (az/field result energy)
                       (if (az/field workspace consistent-mass)
                         (* 0.5 delta (az/index (az/field workspace mass-product) index))
                         (* 0.5 mass delta delta)))
                (when derivatives
                  (let [gradient (+ (if (az/field workspace consistent-mass)
                                      (az/index (az/field workspace mass-product) index)
                                      (* mass delta))
                                    (* scale (- (az/index (az/field workspace contact-gradient) index)
                                                (fem/component force axis))))]
                    (set! (az/index (az/field workspace gradient) index) gradient)
                    (when (ak/! (az/field workspace consistent-mass))
                      (set! (az/field result residual)
                            (ak/max (az/field result residual) (/ (ak/abs gradient) (* mass duration)))))))))))))
    (when (and derivatives (az/field workspace consistent-mass))
      (when (ak/! (solve-mass! workspace (az/field workspace gradient))) (ak/return result))
      (dotimes [index (az/field (az/field workspace mass-solution) len)]
        (set! (az/field result residual)
              (ak/max (az/field result residual)
                      (/ (ak/abs (az/index (az/field workspace mass-solution) index)) duration)))))
    (set! (az/field result valid) (and (math/isFinite (az/field result energy))
                                     (math/isFinite (az/field result residual))))
    result))

(az/defn project-element!
  "Project a symmetric 12-DOF element Hessian onto the PSD cone. AguaFria
  owns symmetrization, eigenvalue clamping and reconstruction; Accelerate
  supplies its native LAPACK eigensolve. Failure leaves the input unchanged."
  :- :bool [[values [:c-pointer :f64]]]
  (when (ak/== values null) (ak/return false))
  (let [^{:var [:array 144 :f64]} vectors ak/undefined
        ^{:var [:array 12 :f64]} eigenvalues ak/undefined
        ^{:var [:array 480 :f64]} scratch ak/undefined
        ^{:var [:array 144 :f64]} projected ak/undefined
        ^{:var :u8} job \V
        ^{:var :u8} triangle \U
        ^{:var :i32} count 12
        ^{:var :i32} scratch-count 480
        ^{:var :i32} status 0]
    (dotimes [row 12]
      (dotimes [column 12]
        (let [a (az/index values (+ (* 12 row) column))
              b (az/index values (+ (* 12 column) row))]
          (when (or (ak/! (math/isFinite a)) (ak/! (math/isFinite b))) (ak/return false))
          ;; LAPACK stores columns. Separate halves avoid overflowing a + b.
          (set! (az/index vectors (+ (* 12 column) row)) (+ (* 0.5 a) (* 0.5 b))))))
    (symmetric-eigenvectors! (ak/& job) (ak/& triangle) (ak/& count)
                             (ak/& (az/index vectors 0)) (ak/& count)
                             (ak/& (az/index eigenvalues 0)) (ak/& (az/index scratch 0))
                             (ak/& scratch-count) (ak/& status))
    (when (ak/!= status 0) (ak/return false))
    (dotimes [mode 12]
      (when (ak/! (math/isFinite (az/index eigenvalues mode))) (ak/return false))
      (set! (az/index eigenvalues mode) (ak/max 0.0 (az/index eigenvalues mode))))
    (dotimes [row 12]
      (dotimes [column (+ row 1)]
        (let [^{:var :f64} sum 0.0]
          (dotimes [mode 12]
            (ak/+= sum (* (az/index vectors (+ (* 12 mode) row))
                          (az/index eigenvalues mode)
                          (az/index vectors (+ (* 12 mode) column)))))
          (when (ak/! (math/isFinite sum)) (ak/return false))
          (az/set-many!
            (az/index projected (+ (* 12 row) column)) sum
            (az/index projected (+ (* 12 column) row)) sum))))
    (dotimes [index 144]
      (set! (az/index values index) (az/index projected index)))
    true))

(az/defn newton-direction!
  :- :bool [[workspace [:* Workspace]] [duration :f64]]
  (when (ak/!= (pitoco_aguafria_variational_matrix_begin (az/field workspace handle)
                                               (az/field (az/field workspace masses) ptr)
                                               (* duration duration)) 0) (ak/return false))
  (let [bodies (az/field (az/field workspace assembly) bodies)]
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            mesh (az/field state mesh)
            offset (az/index (az/field workspace offsets) body)]
        (dotimes [element-index (az/field (az/field mesh elements) len)]
          (let [element (az/index (az/field mesh elements) element-index)
                deformation (dynamics/deformation state element)
                ^{:var [:array 4 :u32]} nodes ak/undefined
                ^{:var [:array 144 :f64]} tangent ak/undefined]
            (dotimes [local 4]
              (set! (az/index nodes local) (ak/intCast (+ offset (az/index (az/field element nodes) local)))))
            (dotimes [column 12]
              (let [local (ak/divTrunc column 3)
                    axis (mod column 3)
                    unit (p/v (if (ak/== axis 0) 1.0 0.0) (if (ak/== axis 1) 1.0 0.0)
                              (if (ak/== axis 2) 1.0 0.0))
                    direction (elastic/outer unit (az/index (az/field element gradients) local))
                    derivative (elastic/tangent deformation direction (az/field state material))]
                (dotimes [row-node 4]
                  (let [gradient (az/index (az/field element gradients) row-node)
                        vector (elastic/apply-vector derivative gradient)]
                    (dotimes [row-axis 3]
                      (let [row (+ (* 3 row-node) row-axis)]
                        (set! (az/index tangent (+ (* 12 row) column))
                              (* (az/field element volume) (fem/component vector row-axis)))))))))
            (when (ak/! (project-element! (ak/& (az/index tangent 0)))) (ak/return false))
            (when (az/field workspace consistent-mass)
              ;; matrix_begin already inserts Ml. Add (Mc-Ml)/h² through the
              ;; existing sparse block interface, whose insertion multiplies h².
              ;; Apply this after elastic PSD projection; it is an inertia
              ;; correction, not an elastic tangent to clamp.
              (let [weight (/ (* 0.05 (az/index (az/field workspace densities) body)
                                  (az/field element volume)) (* duration duration))]
                (dotimes [row-node 4]
                  (dotimes [column-node 4]
                    (dotimes [axis 3]
                      (let [row (+ (* 3 row-node) axis)
                            column (+ (* 3 column-node) axis)]
                        (ak/+= (az/index tangent (+ (* 12 row) column))
                               (* weight (ak/as :f64 (if (ak/== row-node column-node) -3.0 1.0))))))))))
            (when (ak/!= (pitoco_aguafria_variational_add_projected_element (az/field workspace handle)
                                                        (ak/& (az/index nodes 0)) (ak/& (az/index tangent 0))) 0)
              (ak/return false)))))))
  (ak/== (pitoco_aguafria_variational_solve (az/field workspace handle)
                                  (az/field (az/field workspace gradient) ptr)
                                  (az/field (az/field workspace direction) ptr)) 0))

(az/defstruct Report {:layout :extern}
  [[:completed :bool] [:status :u32] [:substeps :u32] [:iterations :u32] [:backtracks :u32]
   [:rejected :u32] [:minimum-step :f64]
   [:time :f64] [:minimum-jacobian :f64] [:contact-energy :f64] [:friction-energy :f64] [:residual :f64]
   [:contact-impulse p/Vec3] [:ground-impulse :f64]])

(az/defstruct AdvanceTask
  [[:duration :f64] [:target :f64] [:step-cap :f64] [:integration :u32] [:report Report]])

(az/defn advance-task
  :- AdvanceTask
  [[workspace [:* Workspace]] [duration :f64] [maximum-step :f64]]
  (let [time (az/field (az/field workspace assembly) time)]
    (AdvanceTask
     {:duration duration :target (+ time duration) :step-cap maximum-step :integration 0
      :report (Report {:completed false :status 1 :substeps 0 :iterations 0 :backtracks 0
                        :rejected 0 :minimum-step 1.0e300 :time time :minimum-jacobian 1.0e300
                        :contact-energy 0.0 :friction-energy 0.0 :residual 0.0
                        :contact-impulse (p/v 0.0 0.0 0.0) :ground-impulse 0.0})})))

(az/defn create-task!
  :- [:* AdvanceTask]
  [[workspace [:* Workspace]] [duration :f64] [maximum-step :f64]]
  (let [task (catch ((az/field heap/page_allocator create) AdvanceTask)
               (debug/panic "Unable to allocate variational advance task" []))]
    (set! (az/deref task) (advance-task workspace duration maximum-step))
    task))

(az/defn destroy-task!
  :- :void [[task [:* AdvanceTask]]]
  ((az/field heap/page_allocator destroy) task))

(az/defn task-step-cap
  :- :f64 [[task [:* AdvanceTask]]]
  (az/field task step-cap))

(az/defn set-integration!
  :- :void [[task [:* AdvanceTask]] [integration :u32]]
  (debug/assert (<= integration 2))
  (set! (az/field task integration) integration))

(az/defn reset-history!
  "Call after externally editing a context's positions or velocities. A new
  BDF2 trajectory starts with backward Euler; rejected trials do not reset it."
  :- :void [[workspace [:* Workspace]]]
  (set! (az/field workspace history-ready) false))

(az/defstruct TimeCoefficients {:layout :extern}
  [[:effective-step :f64] [:history-weight :f64]])

(az/defn time-coefficients
  "Variable-step BDF2 applied to x'=v and M*v'=f. With r=h/hprev,
  effective-step=h*(1+r)/(1+2r), history-weight=r²/(1+2r).
  A zero previous step selects backward Euler startup. Integration 1 is Newmark."
  :- TimeCoefficients
  [[h :f64] [previous-step :f64] [integration :u32]]
  (when (and (ak/== integration 2) (> previous-step 0.0))
    (let [ratio (/ h previous-step)
          denominator (+ 1.0 (* 2.0 ratio))]
      (ak/return (TimeCoefficients {:effective-step (/ (* h (+ 1.0 ratio)) denominator)
                                    :history-weight (/ (* ratio ratio) denominator)}))))
  (TimeCoefficients {:effective-step (if (ak/== integration 1) (* 0.5 h) h)
                     :history-weight 0.0}))

(az/defn net-contact-force
  "Sum forces from the contact potential, independently of momentum changes.
  Internal pair forces cancel; the remaining resultant belongs to the fixed floor."
  :- p/Vec3 [[workspace [:* Workspace]]]
  (let [^{:var p/Vec3} force (p/v 0.0 0.0 0.0)
        gradient (az/field workspace contact-gradient)]
    (dotimes [node (ak/divTrunc (az/field gradient len) 3)]
      (let [index (* 3 node)]
        (set! force (p/add force (p/v (- (az/index gradient index))
                                     (- (az/index gradient (+ index 1)))
                                     (- (az/index gradient (+ index 2))))))))
    force))

(az/defn prepare-incremental-potential!
  "BE uses xhat=x+h*v+h²*g. Newmark (beta=1/4, gamma=1/2) uses
  xhat=x+h*v+h²*g/2+h²*(internal-contact force)/(4*m).
  BDF2 uses accepted displacement/velocity increments and its effective step.
  Friction origins encode endpoint velocities, never alter collision geometry."
  :- :bool
  [[workspace [:* Workspace]] [h :f64] [clearance :f64] [pressure :f64] [newmark :bool]
   [effective-h :f64] [history-weight :f64]
   [initial-contact-force [:* p/Vec3]]]
  (let [bodies (az/field (az/field workspace assembly) bodies)]
    (set! (az/deref initial-contact-force) (p/v 0.0 0.0 0.0))
    (capture! workspace)
    (dotimes [index (az/field (az/field workspace positions) len)]
      (set! (az/index (az/field workspace base) index) (az/index (az/field workspace positions) index)))
    (when (ak/!= (pitoco_aguafria_variational_begin_step
                 (az/field workspace handle) (az/field (az/field workspace base) ptr)
                 (az/field (az/field workspace friction) ptr) effective-h clearance pressure) 0)
      (ak/return false))
    (when newmark
      (dotimes [body (az/field bodies len)]
        (let [state (az/field (az/index bodies body) state)
              offset (az/index (az/field workspace offsets) body)]
          (set! _ (dynamics/elastic-objective! state true))
          (dotimes [node (az/field (az/field state masses) len)]
            (dotimes [axis 3]
              (let [index (+ (* 3 (+ offset node)) axis)]
                (set! (az/index (az/field workspace trial) index)
                      (- (az/index (az/field workspace base) index)
                         (* 0.5 h (fem/component (dynamics/particle-velocity state node) axis)))))))))
      (when (and (az/field workspace has-friction)
                 (ak/!= (pitoco_aguafria_variational_friction_origin
                         (az/field workspace handle) (az/field (az/field workspace trial) ptr)) 0))
        (ak/return false))
      (let [^{:var :f64} barrier 0.0
            ^{:var :f64} friction 0.0]
        (when (ak/!= (pitoco_aguafria_variational_evaluate
                     (az/field workspace handle) (az/field (az/field workspace base) ptr)
                     clearance pressure 1 (az/field (az/field workspace contact-gradient) ptr)
                     (ak/& barrier) (ak/& friction)) 0)
          (ak/return false)))
      (set! (az/deref initial-contact-force) (net-contact-force workspace))
      (when (az/field workspace consistent-mass)
        (dotimes [body (az/field bodies len)]
          (let [state (az/field (az/index bodies body) state)
                offset (az/index (az/field workspace offsets) body)]
            (dotimes [node (az/field (az/field state masses) len)]
              (dotimes [axis 3]
                (let [index (+ (* 3 (+ offset node)) axis)]
                  (set! (az/index (az/field workspace mass-input) index)
                        (- (fem/component (dynamics/particle-force state node) axis)
                           (az/index (az/field workspace contact-gradient) index))))))))
        (when (ak/! (solve-mass! workspace (az/field workspace mass-input))) (ak/return false))))
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)
            offset (az/index (az/field workspace offsets) body)]
        (dotimes [node (az/field (az/field state masses) len)]
          (let [velocity (dynamics/particle-velocity state node)
                force (if newmark (dynamics/particle-force state node) (p/v 0.0 0.0 0.0))
                mass (az/index (az/field state masses) node)]
            (dotimes [axis 3]
              (let [index (+ (* 3 (+ offset node)) axis)
                    point (az/index (az/field workspace base) index)
                    displacement (* h (fem/component velocity axis))
                    gravity (fem/component (az/field state gravity) axis)
                    history-displacement (if (> history-weight 0.0)
                                           (* history-weight (az/index (az/field workspace previous-displacement) index))
                                           0.0)
                    history-velocity (if (> history-weight 0.0)
                                       (* history-weight (az/index (az/field workspace previous-velocity-change) index))
                                       0.0)]
                (az/set-many!
                  (az/index (az/field workspace predicted) index)
                  (if newmark
                    (+ point displacement (* 0.5 h h gravity)
                       (if (az/field workspace consistent-mass)
                         (* 0.25 h h (az/index (az/field workspace mass-solution) index))
                         (/ (* 0.25 h h (- (fem/component force axis)
                                           (az/index (az/field workspace contact-gradient) index))) mass)))
                    (+ point history-displacement
                       (* effective-h (+ (fem/component velocity axis) history-velocity))
                       (* effective-h effective-h gravity)))
                  (az/index (az/field workspace trial) index)
                  (+ point (if newmark (* 0.5 displacement) history-displacement)))))))))
    (when (and (or newmark (> history-weight 0.0)) (az/field workspace has-friction)
               (ak/!= (pitoco_aguafria_variational_friction_origin
                       (az/field workspace handle) (az/field (az/field workspace trial) ptr)) 0))
      (ak/return false))
    true))

(az/defn output-step
  "Fit the final two steps to an output boundary without leaving a tiny remainder.
  The positive step limit includes both the user's cap and BDF2's growth limit."
  :- :f64
  [[remaining :f64] [limit :f64] [clock-roundoff :f64]]
  ;; Preserve an already aligned schedule under the same clock-roundoff rule
  ;; used to finish the task. Never enlarge the accepted step past its limit.
  (if (<= (- remaining limit) clock-roundoff)
    (ak/min remaining limit)
    (if (< (* 0.5 remaining) limit)
      (* 0.5 remaining)
      limit)))

(az/defn advance-batch!
  "Bound work by attempted steps, including rejected steps. Status 6 yields at
  an accepted state; task retains the reduced step cap and cumulative retry limit.
  Other statuses: 1 input, 2 state, 3 solve, 4 line search, 5 Newton limit."
  :- Report
  [[workspace [:* Workspace]] [task [:* AdvanceTask]]
   [clearance :f64] [pressure :f64] [tolerance :f64] [maximum-iterations :u32]
   [maximum-attempts :u32]]
  (let [assembly (az/field workspace assembly)
        bodies (az/field assembly bodies)
        duration (az/field task duration)
        integration (az/field task integration)
        newmark (ak/== (az/field task integration) 1)
        target (az/field task target)
        ^:var step-cap (az/field task step-cap)
        ^:var report (az/field task report)
        ^{:var :u32} attempts 0]
    (defer
      (az/set-many! (az/field task step-cap) step-cap
                    (az/field task report) report))
    (when (or (ak/! (valid-workspace? workspace)) (ak/! (math/isFinite target))
              (ak/! (math/isFinite duration)) (< duration 0.0) (> integration 2)
              (ak/! (math/isFinite step-cap)) (<= step-cap 0.0)
              (ak/! (math/isFinite clearance)) (<= clearance 0.0)
              (ak/! (math/isFinite pressure)) (<= pressure 0.0)
              (ak/! (math/isFinite tolerance)) (<= tolerance 0.0)
              (ak/== maximum-iterations 0) (ak/== maximum-attempts 0))
      (az/set-many! (az/field report completed) false (az/field report status) 1)
      (ak/return report))
    (while (< (az/field assembly time) target)
      (let [remaining (- target (az/field assembly time))
            clock-roundoff (* 3.552713678800501e-15 (ak/max 1.0 (ak/abs target)))
            history-ready (and (ak/== integration 2) (az/field workspace history-ready)
                               (ak/== (az/field workspace history-time) (az/field assembly time)))
            previous-step (if history-ready (az/field workspace previous-step) 0.0)
            ;; Limit growth after a short output interval or rejected attempt.
            ;; This stays below the classical BDF2 zero-stability ratio 1+sqrt(2).
            h (output-step remaining (ak/min step-cap (if history-ready (* 2.0 previous-step) step-cap))
                           clock-roundoff)
            coefficients (time-coefficients h previous-step integration)
            effective-h (az/field coefficients effective-step)
            history-weight (az/field coefficients history-weight)
            ^{:var :bool} converged false
            ^{:var p/Vec3} initial-contact-force (p/v 0.0 0.0 0.0)]
        (when (<= remaining clock-roundoff)
          (when history-ready (set! (az/field workspace history-time) target))
          (set! (az/field assembly time) target)
          (dotimes [body (az/field bodies len)]
            (set! (az/field (az/field (az/index bodies body) state) time) target))
          (ak/break))
        (when (>= attempts maximum-attempts)
          (set! (az/field report status) 6)
          (ak/return report))
        (ak/+= attempts 1)
        (coupled/checkpoint! assembly false)
        (when (ak/! (prepare-incremental-potential! workspace h clearance pressure newmark effective-h history-weight
                                                   (ak/& initial-contact-force)))
          (set! (az/field report status) 2)
          (ak/return report))
        (set! (az/field report status) 5)
        (dotimes [_ maximum-iterations]
          (let [^:var current (objective! workspace effective-h clearance pressure true)]
            (ak/+= (az/field report iterations) 1)
            (when (and (az/field current valid) (<= (az/field current residual) tolerance)
                       (az/field workspace has-friction))
              (when (ak/!= (pitoco_aguafria_variational_lag_friction (az/field workspace handle)
                                                          (az/field (az/field workspace positions) ptr)
                                                          clearance pressure) 0)
                (set! (az/field report status) 2)
                (ak/break))
              (set! current (objective! workspace effective-h clearance pressure true)))
            (az/set-many!
              (az/field report residual) (az/field current residual)
              (az/field report contact-energy) (az/field current contact-energy)
              (az/field report friction-energy) (az/field current friction-energy)
              (az/field report minimum-jacobian)
              (ak/min (az/field report minimum-jacobian) (az/field current minimum-jacobian)))
            (when (ak/! (az/field current valid)) (set! (az/field report status) 2) (ak/break))
            (when (<= (az/field current residual) tolerance)
              (set! converged true)
              (ak/break))
            (when (ak/! (newton-direction! workspace effective-h)) (set! (az/field report status) 3) (ak/break))
            (dotimes [index (az/field (az/field workspace positions) len)]
              (set! (az/index (az/field workspace iterate) index) (az/index (az/field workspace positions) index)))
            (let [^{:var :f64} slope 0.0
                  ^:var alpha (pitoco_aguafria_variational_safe_step (az/field workspace handle)
                                                            (az/field (az/field workspace positions) ptr)
                                                            (az/field (az/field workspace direction) ptr))
                  ^{:var :bool} accepted false]
              (dotimes [index (az/field (az/field workspace gradient) len)]
                (ak/+= slope (* (az/index (az/field workspace gradient) index)
                                (az/index (az/field workspace direction) index))))
              (when (and (math/isFinite alpha) (> alpha 0.0) (< slope 0.0))
                (dotimes [_ 40]
                  (when (ak/== (pitoco_aguafria_variational_trial (az/field workspace handle)
                                                        (az/field (az/field workspace iterate) ptr)
                                                        (az/field (az/field workspace direction) ptr) alpha
                                                        (az/field (az/field workspace trial) ptr)) 0)
                    (scatter! workspace (az/field workspace trial))
                    (let [trial (objective! workspace effective-h clearance pressure false)
                          energy-error (* 1.4210854715202004e-14
                                          (+ (ak/abs (az/field current energy)) (ak/abs (az/field trial energy))))]
                      (when (and (az/field trial valid)
                                 (<= (az/field trial energy) (+ (az/field current energy) (* 1.0e-4 alpha slope))))
                        (set! accepted true)
                        (ak/break))
                      ;; Near convergence the energy decrement can disappear in
                      ;; floating-point summation. Require a smaller residual
                      ;; before accepting an energy-indistinguishable candidate.
                      (when (and (az/field trial valid)
                                 (<= (az/field trial energy) (+ (az/field current energy) energy-error)))
                        (let [checked (objective! workspace effective-h clearance pressure true)]
                          (when (and (az/field checked valid)
                                     (< (az/field checked residual) (az/field current residual)))
                            (set! accepted true)
                            (ak/break))))))
                  (az/set-many! alpha (* 0.5 alpha)
                                (az/field report backtracks) (+ (az/field report backtracks) 1))))
              (when (ak/! accepted) (set! (az/field report status) 4) (ak/break)))))
        (when (ak/! converged)
          (coupled/checkpoint! assembly true)
          (set! _ (coupled/observe! assembly))
          (when (and (> maximum-iterations 1) (>= (az/field report status) 3)
                     (> h 2.0e-9) (< (az/field report rejected) 32))
            (az/set-many!
              step-cap (* 0.5 h)
              (az/field report rejected) (+ (az/field report rejected) 1))
            (ak/continue))
          (ak/return report))
        ;; Count only accepted steps. Backward Euler uses the endpoint force;
        ;; Newmark uses the trapezoidal average of initial and endpoint forces.
        ;; BDF2 integrates I'=contact-force with the same time coefficients;
        ;; the previous impulse comes from force evaluations, never momentum.
        (let [impulse (p/add (p/scale (p/add initial-contact-force (net-contact-force workspace)) effective-h)
                             (if history-ready
                               (p/scale (az/field workspace previous-contact-impulse) history-weight)
                               (p/v 0.0 0.0 0.0)))]
          (az/set-many!
            (az/field report contact-impulse) (p/add (az/field report contact-impulse) impulse)
            (az/field report ground-impulse) (+ (az/field report ground-impulse) (az/field impulse y))
            (az/field workspace previous-contact-impulse) impulse))
        (capture! workspace)
        (dotimes [body (az/field bodies len)]
          (let [state (az/field (az/index bodies body) state)
                offset (az/index (az/field workspace offsets) body)]
            (dotimes [node (az/field (az/field state masses) len)]
              (let [index (* 3 (+ offset node))
                    displacement (p/v (- (az/index (az/field workspace positions) index)
                                          (az/index (az/field workspace base) index))
                                      (- (az/index (az/field workspace positions) (+ index 1))
                                          (az/index (az/field workspace base) (+ index 1)))
                                      (- (az/index (az/field workspace positions) (+ index 2))
                                          (az/index (az/field workspace base) (+ index 2))))
                    previous (if history-ready
                               (p/v (az/index (az/field workspace previous-displacement) index)
                                    (az/index (az/field workspace previous-displacement) (+ index 1))
                                    (az/index (az/field workspace previous-displacement) (+ index 2)))
                               (p/v 0.0 0.0 0.0))
                    rate (p/scale (p/add displacement (p/scale previous (- history-weight))) (/ 1.0 effective-h))
                    old-velocity (dynamics/particle-velocity state node)
                    next-velocity (if newmark (p/add rate (p/scale old-velocity -1.0)) rate)]
                (when (ak/== integration 2)
                  (dotimes [axis 3]
                    (az/set-many!
                      (az/index (az/field workspace previous-displacement) (+ index axis))
                      (fem/component displacement axis)
                      (az/index (az/field workspace previous-velocity-change) (+ index axis))
                      (- (fem/component next-velocity axis) (fem/component old-velocity axis)))))
                (set! (az/index (az/field state velocities) node) next-velocity)))
            (coupled/synchronize! (az/index bodies body))))
        (az/set-many!
          (az/field assembly time) (if (ak/== h remaining) target (+ (az/field assembly time) h))
          (az/field workspace history-time) (az/field assembly time)
          (az/field workspace previous-step) h
          (az/field workspace history-ready) (ak/== integration 2)
          (az/field report time) (az/field assembly time)
          (az/field report minimum-step) (ak/min (az/field report minimum-step) h)
          (az/field report substeps) (+ (az/field report substeps) 1))
        (dotimes [body (az/field bodies len)]
          (set! (az/field (az/field (az/index bodies body) state) time) (az/field assembly time)))))
    (az/set-many! (az/field report completed) true (az/field report status) 0
                  (az/field report time) (az/field assembly time))
    report))

(az/defn advance-native!
  "Uninterrupted native entry point; hosts needing cancellation use advance-batch!."
  :- Report
  [[workspace [:* Workspace]] [duration :f64] [maximum-step :f64]
   [clearance :f64] [pressure :f64] [tolerance :f64] [maximum-iterations :u32]]
  (let [^:var task (advance-task workspace duration maximum-step)]
    (advance-batch! workspace (ak/& task) clearance pressure tolerance maximum-iterations 4294967295)))

(az/defn pitoco_implicit_snapshot_batch
  "Private same-build boundary for a frozen native implicit solve."
  {:attrs #{:export}}
  :- :void
  [[workspace [:* Workspace]] [task [:* AdvanceTask]]
   [clearance :f64] [pressure :f64] [tolerance :f64]
   [iterations :u32] [attempts :u32] [report [:* Report]]]
  (set! (az/deref report)
        (advance-batch! workspace task clearance pressure tolerance iterations attempts)))

(def ^:dynamic *advance-batch* nil)

(defn advance!
  "Yield to the host after at most eight attempted steps, keeping one task's
  target, adaptive cap and cumulative report across batches. Optional callbacks
  run on the calling worker, outside the native solve."
  ([workspace seconds maximum-step clearance pressure]
   (advance! workspace seconds maximum-step clearance pressure {}))
  ([workspace seconds maximum-step clearance pressure
    {:keys [maximum-attempts cancelled? on-progress velocity-tolerance maximum-newton-iterations integration]
     :or {maximum-attempts 8 cancelled? (constantly false)
          velocity-tolerance 1.0e-7 maximum-newton-iterations 100 integration :backward-euler}}]
   (when-not (and (#{:backward-euler :newmark :bdf2} integration) (integer? maximum-attempts) (<= 1 maximum-attempts 64)
                  (number? velocity-tolerance) (Double/isFinite (double velocity-tolerance))
                  (pos? velocity-tolerance)
                  (integer? maximum-newton-iterations) (<= 1 maximum-newton-iterations 10000)
                  (ifn? cancelled?) (or (nil? on-progress) (ifn? on-progress)))
     (throw (ex-info "Invalid variational work budget, nonlinear tolerance, or callbacks" {})))
   (let [check-cancellation!
         (fn [accepted]
           (when (or (.isInterrupted (Thread/currentThread)) (cancelled?))
             (throw (ex-info "Variational FEM bake cancelled at its last accepted state"
                             {:cancelled? true :report accepted}))))]
     ;; A pre-interrupted worker must not enter compilation/native allocation.
     (check-cancellation! nil)
     (let [task (create-task! workspace seconds maximum-step)]
       (try
         (set-integration! task ({:backward-euler 0 :newmark 1 :bdf2 2} integration))
         (loop [accepted nil]
           (check-cancellation! accepted)
           (let [arguments [workspace task clearance pressure (double velocity-tolerance)
                            maximum-newton-iterations maximum-attempts]
                 report (if *advance-batch*
                          (apply *advance-batch* arguments)
                          (az/value (apply advance-batch! arguments)))]
             (when on-progress (on-progress report))
             (cond
               (:completed report) report
               (= 6 (:status report)) (recur report)
               :else (throw (ex-info "Variational FEM stopped at its last accepted state"
                                     (assoc report :native-error (native-error)))))))
         (finally
           ;; Native cleanup may await a hot-reloaded declaration. Preserve the
           ;; interrupt for the caller, but let cleanup finish before restoring it.
           (let [interrupted? (Thread/interrupted)]
             (try
               (destroy-task! task)
               (finally
                 (when interrupted? (.interrupt (Thread/currentThread))))))))))))


(defn solver-version []
  (let [prefix (str (io/file (build/root) "build/variational-libraries") "/")
        library (last (filter #(and (string? %) (.startsWith ^String % prefix))
                              (:zig-args (az/configuration))))
        _ (when-not library (throw (ex-info "No variational backend configured" {})))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (java.nio.file.Files/readAllBytes (.toPath (io/file library))))
        info (az/module-info 'field-lab.variational)]
    {'field-lab.variational/platform-linear-algebra
     {:provider :apple-accelerate :routine :dsyev :abi :lp64-new-lapack
      :projection :aguafria-zig-psd-clamp :minimum-macos "13.3"
      :os-version (System/getProperty "os.version") :arch (System/getProperty "os.arch")}
     'field-lab.variational/native-library
     {:dependency build/ipc-dependency
      :library-sha256 (apply str (map #(format "%02x" (bit-and % 255)) digest))
      :friction-smoothing-m-s 1.0e-5 :integrations [:backward-euler :newmark :bdf2]
      :mass-models [:lumped :consistent]
      :consistent-mass {:basis :linear-tetrahedral :inverse :jacobi-pcg
                        :relative-residual-tolerance 1e-12 :maximum-iterations 64
                        :rhs-scaling :independent-body-and-axis
                        :residual-check :per-body-axis-mass-weighted-norm
                        :weighted-relative-solution-error-bound 5e-12
                        :nonlinear-residual :inverse-mass-scaled-velocity}
      :bdf2 {:startup :backward-euler :maximum-step-ratio 2.0
             :contact-impulse :bdf2-integral-of-contact-force}
      :output-boundary :split-final-two-steps-within-cap
      :contact :area-weighted-improved-max-physical-barrier}
     'field-lab.variational
     (mapv #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint])
           (sort-by (comp pr-str :logical-id) (:definitions info)))}))
