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
   [:positions [:slice :f64]] [:base [:slice :f64]] [:trial [:slice :f64]] [:iterate [:slice :f64]]
   [:predicted [:slice :f64]] [:gradient [:slice :f64]] [:direction [:slice :f64]]
   [:contact-gradient [:slice :f64]]])

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
                        :masses (fem/allocate :f64 nodes) :friction (fem/allocate :f64 nodes) :has-friction false :positions (fem/allocate :f64 (* 3 nodes))
                        :base (fem/allocate :f64 (* 3 nodes)) :trial (fem/allocate :f64 (* 3 nodes))
                        :iterate (fem/allocate :f64 (* 3 nodes))
                        :predicted (fem/allocate :f64 (* 3 nodes)) :gradient (fem/allocate :f64 (* 3 nodes))
                        :direction (fem/allocate :f64 (* 3 nodes)) :contact-gradient (fem/allocate :f64 (* 3 nodes))}))
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
  ((az/field heap/page_allocator destroy) workspace))

(az/defn valid-workspace?
  :- :bool [[workspace [:* Workspace]]]
  (ak/!= (az/field workspace handle) null))

(az/defn error-byte
  :- :u8 [[index :usize]]
  (az/index (pitoco_aguafria_variational_error) index))

(defn native-error []
  (apply str (map char (take-while pos? (map error-byte (range 4096))))))

(defn with-context! [assembly f]
  (let [workspace (create! assembly)]
    (try
      (when-not (valid-workspace? workspace)
        (throw (ex-info "Unable to initialize IPC contact" {:native-error (native-error)})))
      (f workspace)
      (finally (destroy! workspace)))))

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
                (ak/+= (az/field result energy) (* 0.5 mass delta delta))
                (when derivatives
                  (let [gradient (+ (* mass delta)
                                    (* scale (- (az/index (az/field workspace contact-gradient) index)
                                                (fem/component force axis))))]
                    (set! (az/index (az/field workspace gradient) index) gradient)
                    (set! (az/field result residual)
                          (ak/max (az/field result residual) (/ (ak/abs gradient) (* mass duration))))))))))))
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
            (when (ak/!= (pitoco_aguafria_variational_add_projected_element (az/field workspace handle)
                                                        (ak/& (az/index nodes 0)) (ak/& (az/index tangent 0))) 0)
              (ak/return false)))))))
  (ak/== (pitoco_aguafria_variational_solve (az/field workspace handle)
                                  (az/field (az/field workspace gradient) ptr)
                                  (az/field (az/field workspace direction) ptr)) 0))

(az/defstruct Report {:layout :extern}
  [[:completed :bool] [:status :u32] [:substeps :u32] [:iterations :u32] [:backtracks :u32]
   [:rejected :u32] [:minimum-step :f64]
   [:time :f64] [:minimum-jacobian :f64] [:contact-energy :f64] [:friction-energy :f64] [:residual :f64]])

(az/defstruct AdvanceTask
  [[:duration :f64] [:target :f64] [:step-cap :f64] [:report Report]])

(az/defn advance-task
  :- AdvanceTask
  [[workspace [:* Workspace]] [duration :f64] [maximum-step :f64]]
  (let [time (az/field (az/field workspace assembly) time)]
    (AdvanceTask
     {:duration duration :target (+ time duration) :step-cap maximum-step
      :report (Report {:completed false :status 1 :substeps 0 :iterations 0 :backtracks 0
                        :rejected 0 :minimum-step 1.0e300 :time time :minimum-jacobian 1.0e300
                        :contact-energy 0.0 :friction-energy 0.0 :residual 0.0})})))

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
        target (az/field task target)
        ^:var step-cap (az/field task step-cap)
        ^:var report (az/field task report)
        ^{:var :u32} attempts 0]
    (defer
      (az/set-many! (az/field task step-cap) step-cap
                    (az/field task report) report))
    (when (or (ak/! (valid-workspace? workspace)) (ak/! (math/isFinite target))
              (ak/! (math/isFinite duration)) (< duration 0.0)
              (ak/! (math/isFinite step-cap)) (<= step-cap 0.0)
              (ak/! (math/isFinite clearance)) (<= clearance 0.0)
              (ak/! (math/isFinite pressure)) (<= pressure 0.0)
              (ak/! (math/isFinite tolerance)) (<= tolerance 0.0)
              (ak/== maximum-iterations 0) (ak/== maximum-attempts 0))
      (az/set-many! (az/field report completed) false (az/field report status) 1)
      (ak/return report))
    (while (< (az/field assembly time) target)
      (let [remaining (- target (az/field assembly time))
            h (ak/min remaining step-cap)
            ^{:var :bool} converged false]
        (when (<= remaining (* 3.552713678800501e-15 (ak/max 1.0 (ak/abs target))))
          (set! (az/field assembly time) target)
          (dotimes [body (az/field bodies len)]
            (set! (az/field (az/field (az/index bodies body) state) time) target))
          (ak/break))
        (when (>= attempts maximum-attempts)
          (set! (az/field report status) 6)
          (ak/return report))
        (ak/+= attempts 1)
        (coupled/checkpoint! assembly false)
        (capture! workspace)
        (dotimes [body (az/field bodies len)]
          (let [state (az/field (az/index bodies body) state)
                offset (az/index (az/field workspace offsets) body)]
            (dotimes [node (az/field (az/field state masses) len)]
              (dotimes [axis 3]
                (let [index (+ (* 3 (+ offset node)) axis)
                      point (az/index (az/field workspace positions) index)]
                  (az/set-many!
                    (az/index (az/field workspace base) index) point
                    (az/index (az/field workspace predicted) index)
                    (+ point (* h (fem/component (dynamics/particle-velocity state node) axis))
                       (* h h (fem/component (az/field state gravity) axis)))))))))
        (when (ak/!= (pitoco_aguafria_variational_begin_step (az/field workspace handle)
                                                    (az/field (az/field workspace base) ptr)
                                                    (az/field (az/field workspace friction) ptr)
                                                    h clearance pressure) 0)
          (set! (az/field report status) 2)
          (ak/return report))
        (set! (az/field report status) 5)
        (dotimes [_ maximum-iterations]
          (let [^:var current (objective! workspace h clearance pressure true)]
            (ak/+= (az/field report iterations) 1)
            (when (and (az/field current valid) (<= (az/field current residual) tolerance)
                       (az/field workspace has-friction))
              (when (ak/!= (pitoco_aguafria_variational_lag_friction (az/field workspace handle)
                                                          (az/field (az/field workspace positions) ptr)
                                                          clearance pressure) 0)
                (set! (az/field report status) 2)
                (ak/break))
              (set! current (objective! workspace h clearance pressure true)))
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
            (when (ak/! (newton-direction! workspace h)) (set! (az/field report status) 3) (ak/break))
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
                    (let [trial (objective! workspace h clearance pressure false)
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
                        (let [checked (objective! workspace h clearance pressure true)]
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
        (capture! workspace)
        (dotimes [body (az/field bodies len)]
          (let [state (az/field (az/index bodies body) state)
                offset (az/index (az/field workspace offsets) body)]
            (dotimes [node (az/field (az/field state masses) len)]
              (let [index (* 3 (+ offset node))]
                (set! (az/index (az/field state velocities) node)
                      (p/v (/ (- (az/index (az/field workspace positions) index) (az/index (az/field workspace base) index)) h)
                           (/ (- (az/index (az/field workspace positions) (+ index 1)) (az/index (az/field workspace base) (+ index 1))) h)
                           (/ (- (az/index (az/field workspace positions) (+ index 2)) (az/index (az/field workspace base) (+ index 2))) h)))))
            (coupled/synchronize! (az/index bodies body))))
        (az/set-many!
          (az/field assembly time) (if (ak/== h remaining) target (+ (az/field assembly time) h))
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

(defn advance!
  "Yield to the host after at most eight attempted steps, keeping one task's
  target, adaptive cap and cumulative report across batches. Optional callbacks
  run on the calling worker, outside the native solve."
  ([workspace seconds maximum-step clearance pressure]
   (advance! workspace seconds maximum-step clearance pressure {}))
  ([workspace seconds maximum-step clearance pressure
    {:keys [maximum-attempts cancelled? on-progress]
     :or {maximum-attempts 8 cancelled? (constantly false)}}]
   (when-not (and (integer? maximum-attempts) (<= 1 maximum-attempts 64)
                  (ifn? cancelled?) (or (nil? on-progress) (ifn? on-progress)))
     (throw (ex-info "Invalid variational work budget or callbacks" {})))
   (let [check-cancellation!
         (fn [accepted]
           (when (or (.isInterrupted (Thread/currentThread)) (cancelled?))
             (throw (ex-info "Variational FEM bake cancelled at its last accepted state"
                             {:cancelled? true :report accepted}))))]
     ;; A pre-interrupted worker must not enter compilation/native allocation.
     (check-cancellation! nil)
     (let [task (create-task! workspace seconds maximum-step)]
       (try
         (loop [accepted nil]
           (check-cancellation! accepted)
           (let [report (az/value (advance-batch! workspace task clearance pressure
                                                 1.0e-7 100 maximum-attempts))]
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
      :friction-smoothing-m-s 1.0e-5 :integration :backward-euler
      :contact :area-weighted-improved-max-physical-barrier}
     'field-lab.variational
     (mapv #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint])
           (sort-by (comp pr-str :logical-id) (:definitions info)))}))
