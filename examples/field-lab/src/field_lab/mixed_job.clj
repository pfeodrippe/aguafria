(ns field-lab.mixed-job
  "Owned native mixed-FEM contexts and Clojure scene-data adapter. Experimental:
  backward Euler/SDIRK2 and axis-aligned plane constraints; no body/body collision."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.hyperelastic :as elastic]
            [field-lab.mixed-tetra :as mixed]
            [field-lab.mixed-solver :as solver]))

(az/defstruct Context
  [[:problem solver/Problem] [:workspace solver/Workspace]
   [:storage [:slice p/Vec3]] [:tangent-storage [:slice mixed/QuadraticResponse]]
   [:rest [:slice p/Vec3]] [:velocity [:slice p/Vec3]]
   [:time :f64] [:steps :u64] [:contact-force p/Vec3] [:contact-impulse p/Vec3] [:ready :bool]])

(az/defstruct Observables {:layout :extern}
  [[:valid :bool] [:time :f64] [:steps :u64] [:mass :f64]
   [:kinetic :f64] [:elastic :f64] [:potential :f64]
   [:center p/Vec3] [:momentum p/Vec3] [:angular-momentum p/Vec3]
   [:contact-force p/Vec3] [:contact-impulse p/Vec3]])

(az/defn segment [:slice p/Vec3] [[storage [:slice p/Vec3]] [count :usize] [slot :usize]]
  (az/slice storage (* slot count) (* (+ slot 1) count)))

(az/defn create! [:optional [:* Context]]
  "Allocate the entire ownership scope, or return null after freeing partial
  allocations. Configuration must finish before advance! can publish a step." [[vertices :usize] [cells :usize]]
  (when (or (ak/== vertices 0) (ak/== cells 0) (> vertices 10000000) (> cells 10000000)) (ak/return null))
  (let [count (+ vertices (* 4 cells))
        context (catch ((az/field heap/page_allocator create) Context) (ak/return null))
        ^:var owned (ak/bool false)]
    (defer (when (ak/! owned) ((az/field heap/page_allocator destroy) context)))
    (let [storage (catch ((az/field heap/page_allocator alloc) p/Vec3 (* 19 count)) (ak/return null))]
      (defer (when (ak/! owned) ((az/field heap/page_allocator free) storage)))
      (let [elements (catch ((az/field heap/page_allocator alloc) mixed/Element cells) (ak/return null))]
        (defer (when (ak/! owned) ((az/field heap/page_allocator free) elements)))
        (let [tangents (catch ((az/field heap/page_allocator alloc) mixed/QuadraticResponse (* 2 cells)) (ak/return null))]
          (defer (when (ak/! owned) ((az/field heap/page_allocator free) tangents)))
          (dotimes [index (az/field storage len)] (set! (az/index storage index) (p/v 0.0 0.0 0.0)))
          (dotimes [index cells] (set! (az/index elements index) (mem/zeroes (az/type mixed/Element))))
          (set! (az/deref context)
                (Context
                  {:problem (solver/Problem
                              {:vertex-count vertices :elements elements
                               :initial (segment storage count 0) :prediction (segment storage count 1)
                               :loads (segment storage count 2) :lower (segment storage count 3) :upper (segment storage count 4)
                               :gravity (p/v 0.0 -9.81 0.0) :duration 0.01 :quadrature (mixed/quadrature 6)
                               :force-tolerance 1e-7 :iteration-limit 100 :cg-limit 200})
                   :workspace (solver/Workspace
                                {:x (segment storage count 7) :trial (segment storage count 8)
                                 :gradient (segment storage count 9) :trial-gradient (segment storage count 10)
                                 :direction (segment storage count 11) :residual (segment storage count 12)
                                 :search (segment storage count 13) :product (segment storage count 14)
                                 :diagonal (segment storage count 15) :free (segment storage count 16)
                                 :tangents (az/slice tangents 0 cells) :trial-tangents (az/slice tangents cells (* 2 cells))})
                   :storage storage :tangent-storage tangents :rest (segment storage count 6)
                   :velocity (segment storage count 5) :time 0.0 :steps 0
                   :contact-force (p/v 0.0 0.0 0.0) :contact-impulse (p/v 0.0 0.0 0.0) :ready false}))
          (dotimes [index count]
            (az/set-many!
              (az/index (az/field (az/field context problem) lower) index)
              (p/v (- (math/inf :f64)) (- (math/inf :f64)) (- (math/inf :f64)))
              (az/index (az/field (az/field context problem) upper) index)
              (p/v (math/inf :f64) (math/inf :f64) (math/inf :f64))))
          (dotimes [index vertices]
            (set! (az/index (az/field context rest) index) (p/v (math/nan :f64) (math/nan :f64) (math/nan :f64))))
          (set! owned true)
          context)))))

(az/defn destroy! :void [[context [:* Context]]]
  ((az/field heap/page_allocator free) (az/field (az/field context problem) elements))
  ((az/field heap/page_allocator free) (az/field context tangent-storage))
  ((az/field heap/page_allocator free) (az/field context storage))
  ((az/field heap/page_allocator destroy) context))

(az/defn set-vertex! :bool [[context [:* Context]] [index :usize] [rest p/Vec3] [position p/Vec3] [velocity p/Vec3]]
  (when (or (az/field context ready) (>= index (az/field (az/field context problem) vertex-count))) (ak/return false))
  (dotimes [cell (az/field (az/field (az/field context problem) elements) len)]
    (when (> (az/field (az/index (az/field (az/field context problem) elements) cell) volume) 0.0)
      (ak/return false)))
  (dotimes [axis 3]
    (when (or (ak/! (math/isFinite (fem/component rest axis)))
              (ak/! (math/isFinite (fem/component position axis)))
              (ak/! (math/isFinite (fem/component velocity axis)))) (ak/return false)))
  (az/set-many! (az/index (az/field context rest) index) rest
                (az/index (az/field (az/field context problem) initial) index) (p/add position (p/scale rest -1.0))
                (az/index (az/field context velocity) index) velocity)
  true)

(az/defn set-element! :bool
  [[context [:* Context]] [cell :usize] [vertices [:array 4 :u32]] [young :f64] [poisson :f64] [density :f64]]
  (let [problem (ak/& (az/field context problem))]
    (when (or (az/field context ready) (>= cell (az/field (az/field problem elements) len))
              (<= young 0.0) (<= density 0.0) (<= poisson -1.0) (>= poisson 0.5)
              (ak/! (math/isFinite young)) (ak/! (math/isFinite density)) (ak/! (math/isFinite poisson)))
      (ak/return false))
    (dotimes [node 4]
      (when (>= (az/index vertices node) (az/field problem vertex-count)) (ak/return false))
      (dotimes [other node]
        (when (ak/== (az/index vertices node) (az/index vertices other)) (ak/return false))))
    (let [a (az/index (az/field context rest) (az/index vertices 0))
          ab (p/add (az/index (az/field context rest) (az/index vertices 1)) (p/scale a -1.0))
          ac (p/add (az/index (az/field context rest) (az/index vertices 2)) (p/scale a -1.0))
          ad (p/add (az/index (az/field context rest) (az/index vertices 3)) (p/scale a -1.0))
          determinant (p/dot ab (p/cross ac ad))
          scale (* (p/length ab) (p/length ac) (p/length ad))]
      (when (or (ak/! (math/isFinite determinant)) (ak/! (math/isFinite scale)) (<= scale 0.0)
                (<= (ak/abs determinant) (* 1e-12 scale))
                (<= (* density (/ (ak/abs determinant) 6.0)) 0.0)
                (ak/! (math/isFinite (* density (/ (ak/abs determinant) 6.0))))) (ak/return false))
      (let [b (p/scale (p/cross ac ad) (/ 1.0 determinant))
            c (p/scale (p/cross ad ab) (/ 1.0 determinant))
            d (p/scale (p/cross ab ac) (/ 1.0 determinant))]
        (set! (az/index (az/field problem elements) cell)
              (mixed/Element {:vertices vertices :edges (mem/zeroes (az/type [:array 6 :u32])) :quadratic? false
                              :gradients (az/init [(p/scale (p/add (p/add b c) d) -1.0) b c d] [:array 4 p/Vec3])
                              :volume (/ (ak/abs determinant) 6.0) :density density :material (elastic/material young poisson)}))))
    (dotimes [node 4]
      (let [vertex (az/index vertices node)
            private (+ (az/field problem vertex-count) (* 4 cell) node)]
        (az/set-many! (az/index (az/field context rest) private) (az/index (az/field context rest) vertex)
                      (az/index (az/field problem initial) private) (az/index (az/field problem initial) vertex)
                      (az/index (az/field context velocity) private) (az/index (az/field context velocity) vertex))))
    true))

(az/defn set-quadratic-edges! :bool
  "Configure shared Bernstein edge controls before configure!. Rest geometry
  stays affine: every edge control must be the exact authored midpoint. Initial
  displacement and velocity are projected into the four private coefficients." [[context [:* Context]] [cell :usize] [edges [:array 6 :u32]]]
  (let [problem (ak/& (az/field context problem))]
    (when (or (az/field context ready) (>= cell (az/field (az/field problem elements) len))) (ak/return false))
    (let [element (ak/& (az/index (az/field problem elements) cell))
          ^:var edge (ak/usize 0)]
      (when (<= (az/field element volume) 0.0) (ak/return false))
      (dotimes [index 6]
        (let [control (az/index edges index)]
          (when (>= control (az/field problem vertex-count)) (ak/return false))
          (dotimes [other index]
            (when (ak/== control (az/index edges other)) (ak/return false)))
          (dotimes [vertex 4]
            (when (ak/== control (az/index (az/field element vertices) vertex)) (ak/return false)))))
      (dotimes [left 4]
        (dotimes [right 4]
          (when (> right left)
            (let [a (az/index (az/field context rest) (az/index (az/field element vertices) left))
                  b (az/index (az/field context rest) (az/index (az/field element vertices) right))
                  expected (p/scale (p/add a b) 0.5)
                  actual (az/index (az/field context rest) (az/index edges edge))]
              (dotimes [axis 3]
                (when (ak/!= (fem/component actual axis) (fem/component expected axis)) (ak/return false))))
            (ak/+= edge 1))))
      (az/set-many! (az/field element edges) edges (az/field element quadratic?) true)
      (dotimes [node 4]
        (let [private (+ (az/field problem vertex-count) (* 4 cell) node)
              ^:var displacement (p/v 0.0 0.0 0.0)
              ^:var velocity (p/v 0.0 0.0 0.0)]
          (dotimes [column 10]
            (let [index (mixed/coefficient-index (az/field problem vertex-count) cell (az/deref element) column)
                  coefficient (mixed/quadratic-projection (ak/intCast node) (ak/intCast column))]
              (az/set-many!
                displacement (p/add displacement (p/scale (az/index (az/field problem initial) index) coefficient))
                velocity (p/add velocity (p/scale (az/index (az/field context velocity) index) coefficient)))))
          (az/set-many! (az/index (az/field problem initial) private) displacement
                        (az/index (az/field context velocity) private) velocity))))
    true))

(az/defn configure! :bool [[context [:* Context]] [gravity p/Vec3] [floor :bool] [height :f64]]
  (when (or (az/field context ready) (and floor (ak/! (math/isFinite height)))) (ak/return false))
  (let [problem (ak/& (az/field context problem))]
    (dotimes [vertex (az/field problem vertex-count)]
      (let [^:var used (ak/bool false)]
        (dotimes [cell (az/field (az/field problem elements) len)]
          (dotimes [node (mixed/trace-count (az/index (az/field problem elements) cell))]
            (when (ak/== vertex (mixed/coefficient-index (az/field problem vertex-count) cell
                                 (az/index (az/field problem elements) cell) node))
              (set! used true))))
        (when (ak/! used) (ak/return false)))
      (set! (az/field (az/index (az/field problem lower) vertex) y)
            (if floor (- height (az/field (az/index (az/field context rest) vertex) y)) (- (math/inf :f64)))))
    (set! (az/field problem gravity) gravity)
    (mem/copyForwards (az/type p/Vec3) (az/field problem prediction) (az/field problem initial))
    ;; A zero-iteration evaluation validates topology/material/geometry without
    ;; solving or moving the authored initial state.
    (let [response (solver/evaluate! problem (az/field problem initial)
                     (az/field (az/field context workspace) gradient) (az/field (az/field context workspace) tangents))]
      (when (ak/! (az/field response valid)) (ak/return false)))
    (when (<= (solver/path-bound problem (az/field problem initial) (az/field problem initial)) 0.0) (ak/return false))
    (dotimes [index (az/field (az/field problem initial) len)]
      (when (< (az/field (az/index (az/field problem initial) index) y)
               (az/field (az/index (az/field problem lower) index) y)) (ak/return false)))
    (set! (az/field context ready) true)
    true))

(az/defn plane-reaction p/Vec3
  "Read the current successful solve's plane reaction before reusing workspace." [[context [:* Context]]]
  (let [problem (ak/& (az/field context problem))
        ^:var force (ak/f64 0.0)]
    (dotimes [vertex (az/field problem vertex-count)]
      (let [position (az/index (az/field (az/field context workspace) x) vertex)
            lower (az/index (az/field problem lower) vertex)
            gradient (az/index (az/field (az/field context workspace) gradient) vertex)]
        (when (and (ak/== (az/field position y) (az/field lower y)) (> (az/field gradient y) 0.0))
          (ak/+= force (az/field gradient y)))))
    (p/v 0.0 force 0.0)))

(az/defn advance! solver/Report
  "Commit displacement, velocity and clock together only after solver success." [[context [:* Context]] [duration :f64]]
  (let [^:var report (mem/zeroes (az/type solver/Report))
        problem (ak/& (az/field context problem))
        next-time (+ (az/field context time) duration)]
    (set! (az/field report status) 1)
    (when (or (ak/! (az/field context ready)) (<= duration 0.0) (ak/! (math/isFinite next-time))
              (<= next-time (az/field context time))) (ak/return report))
    (set! (az/field problem duration) duration)
    (dotimes [index (az/field (az/field problem initial) len)]
      (set! (az/index (az/field problem prediction) index)
            (p/add (az/index (az/field problem initial) index) (p/scale (az/index (az/field context velocity) index) duration))))
    (set! report (solver/solve! problem (ak/& (az/field context workspace))))
    (when (ak/== (az/field report status) 0)
      ;; Retain the accepted KKT reaction with the accepted state. Failed trials
      ;; overwrite workspace gradients, but must not alter published forces.
      (az/set-many! (az/field context contact-force) (plane-reaction context)
                    (az/field context contact-impulse) (p/scale (az/field context contact-force) duration))
      (dotimes [index (az/field (az/field problem initial) len)]
        (set! (az/index (az/field context velocity) index)
              (p/scale (p/add (az/index (az/field (az/field context workspace) x) index)
                             (p/scale (az/index (az/field problem initial) index) -1.0)) (/ 1.0 duration))))
      (mem/copyForwards (az/type p/Vec3) (az/field problem initial) (az/field (az/field context workspace) x))
      (set! (az/field context time) next-time)
      (ak/+= (az/field context steps) 1))
    report))

(az/defn advance-sdirk! solver/Report
  "Alexander SDIRK2 on projected displacement/velocity. Both stages enforce
  plane constraints; nothing commits until both succeed and their connecting
  path is certified. Status 6 denotes an uncertified inter-stage path." [[context [:* Context]] [duration :f64]]
  (let [^:var report (mem/zeroes (az/type solver/Report))
        problem (ak/& (az/field context problem))
        workspace (ak/& (az/field context workspace))
        count (az/field (az/field problem initial) len)
        stage-position (segment (az/field context storage) count 17)
        stage-velocity (segment (az/field context storage) count 18)
        gamma (- 1.0 (/ 1.0 (ak/sqrt (ak/as 2.0 :f64))))
        stage-dt (* gamma duration)
        next-time (+ (az/field context time) duration)]
    (set! (az/field report status) 1)
    (when (or (ak/! (az/field context ready)) (<= stage-dt 0.0)
              (ak/! (math/isFinite next-time)) (<= next-time (az/field context time)))
      (ak/return report))
    (set! (az/field problem duration) stage-dt)
    (dotimes [index count]
      (set! (az/index (az/field problem prediction) index)
            (p/add (az/index (az/field problem initial) index)
                   (p/scale (az/index (az/field context velocity) index) stage-dt))))
    (let [first-report (solver/solve! problem workspace)]
      (when (ak/!= (az/field first-report status) 0) (ak/return first-report))
      (let [first-force (plane-reaction context)]
        (mem/copyForwards (az/type p/Vec3) stage-position (az/field workspace x))
        (dotimes [index count]
          (let [initial (az/index (az/field problem initial) index)
                velocity (az/index (az/field context velocity) index)
                first-velocity (p/scale (p/add (az/index stage-position index) (p/scale initial -1.0)) (/ 1.0 stage-dt))]
            (set! (az/index stage-velocity index) first-velocity)
            ;; u_base = u0 + (1-gamma)*h*v1; v_base = v0 + (1-gamma)*h*a1.
            ;; Eliminating a1 gives this second-stage displacement predictor.
            (set! (az/index (az/field problem prediction) index)
                  (p/add initial
                    (p/add (p/scale first-velocity (* 2.0 (- 1.0 gamma) duration))
                           (p/scale velocity (* (- (* 2.0 gamma) 1.0) duration)))))))
        (set! report (solver/solve! problem workspace))
        (az/set-many!
          (az/field report iterations) (+ (az/field report iterations) (az/field first-report iterations))
          (az/field report cg-iterations) (+ (az/field report cg-iterations) (az/field first-report cg-iterations))
          (az/field report line-trials) (+ (az/field report line-trials) (az/field first-report line-trials))
          (az/field report gradient-fallbacks) (+ (az/field report gradient-fallbacks) (az/field first-report gradient-fallbacks)))
        (when (ak/!= (az/field report status) 0) (ak/return report))
        (set! (az/field report path-lower-bound)
              (ak/min (az/field first-report path-lower-bound)
                (ak/min (az/field report path-lower-bound)
                        (solver/path-bound problem stage-position (az/field workspace x)))))
        (when (<= (az/field report path-lower-bound) 0.0)
          (set! (az/field report status) 6)
          (ak/return report))
        ;; The stiffly accurate last stage is the accepted endpoint. The
        ;; weighted stage reactions give impulse; endpoint force alone does not.
        (az/set-many!
          (az/field context contact-force) (plane-reaction context)
          (az/field context contact-impulse)
          (p/scale (p/add (p/scale first-force (- 1.0 gamma))
                         (p/scale (az/field context contact-force) gamma)) duration))
        (dotimes [index count]
          (let [base (p/add (az/index (az/field problem initial) index)
                            (p/scale (az/index stage-velocity index) (* (- 1.0 gamma) duration)))]
            (set! (az/index (az/field context velocity) index)
                  (p/scale (p/add (az/index (az/field workspace x) index) (p/scale base -1.0)) (/ 1.0 stage-dt)))))
        (mem/copyForwards (az/type p/Vec3) (az/field problem initial) (az/field workspace x))
        (az/set-many! (az/field context time) next-time
                      (az/field context steps) (+ (az/field context steps) 1))))
    report))

(az/defn pitoco_mixed_snapshot_step :void
  "Private same-build boundary for a frozen native mixed solve."
  {:attrs #{:export}}
  [[context [:* Context]] [duration :f64] [integration :u32] [report [:* solver/Report]]]
  (set! (az/deref report)
        (if (ak/== integration 1) (advance-sdirk! context duration) (advance! context duration))))

(def ^:dynamic *advance-step* nil)

(defn step!
  "Advance through an optional frozen kernel; direct native calls remain available
  for development. Both paths preserve the same accepted-state transaction."
  [context duration integration]
  (when-not (#{:backward-euler :sdirk2} integration)
    (throw (ex-info "Unknown mixed integrator" {:integration integration})))
  (if *advance-step*
    (*advance-step* context duration (if (= integration :sdirk2) 1 0))
    (az/value ((if (= integration :sdirk2) advance-sdirk! advance!) context duration))))

(defn solver-version []
  (into (sorted-map)
        (for [module '[field-lab.mixed-job field-lab.mixed-solver field-lab.mixed-tetra
                       field-lab.hyperelastic field-lab.fem field-lab.physics field-lab.geometry]]
          [module (mapv #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint])
                        (sort-by (comp pr-str :logical-id) (:definitions (az/module-info module))))])))

(az/defn vertex-count :usize [[context [:* Context]]]
  (az/field (az/field context problem) vertex-count))

(az/defn vertex-position p/Vec3 [[context [:* Context]] [vertex :usize]]
  (p/add (az/index (az/field context rest) vertex) (az/index (az/field (az/field context problem) initial) vertex)))

(az/defn observables Observables
  "Exact P1 projected-velocity inertia and momenta; quadrature elastic energy.
  Angular momentum uses integral (X+u) cross rho*v, with the moment-compatible
  projection u -> r. Boundary coefficient velocities carry no inertia." [[context [:* Context]]]
  (let [^:var result (mem/zeroes (az/type Observables))
        problem (ak/& (az/field context problem))]
    (when (ak/! (az/field context ready)) (ak/return result))
    (az/set-many! (az/field result time) (az/field context time)
                  (az/field result steps) (az/field context steps)
                  (az/field result contact-force) (az/field context contact-force)
                  (az/field result contact-impulse) (az/field context contact-impulse))
    (dotimes [cell (az/field (az/field problem elements) len)]
      (let [element (az/index (az/field problem elements) cell)
            mass (* (az/field element density) (az/field element volume))
            ^:var local (mem/zeroes (az/type [:array 14 p/Vec3]))
            ^:var velocity (mem/zeroes (az/type [:array 8 p/Vec3]))]
        (dotimes [node (mixed/coefficient-count element)]
          (let [index (mixed/coefficient-index (az/field problem vertex-count) cell element node)]
            (set! (az/index local node) (az/index (az/field problem initial) index))))
        (dotimes [node 4]
          (set! (az/index velocity (+ 4 node))
                (az/index (az/field context velocity) (+ (az/field problem vertex-count) (* 4 cell) node))))
        (let [response (mixed/evaluate-element element local (az/field problem quadrature) false)]
          (when (ak/! (az/field response valid)) (ak/return result))
          (ak/+= (az/field result elastic) (az/field response energy)))
        (ak/+= (az/field result mass) mass)
        (ak/+= (az/field result kinetic) (mixed/kinetic-energy (az/field element density) (az/field element volume) velocity))
        (dotimes [node 4]
          (let [index (+ (az/field problem vertex-count) (* 4 cell) node)
                position (p/add (az/index (az/field context rest) index) (az/index local (+ (mixed/trace-count element) node)))
                momentum (p/scale (az/index velocity (+ 4 node)) (* mass 0.25))]
            (az/set-many! (az/field result center) (p/add (az/field result center) (p/scale position (* mass 0.25)))
                          (az/field result momentum) (p/add (az/field result momentum) momentum))
            (ak/-= (az/field result potential) (* mass 0.25 (p/dot (az/field problem gravity) position)))
            (dotimes [other 4]
              (let [entry (mixed/mass-entry (az/field element density) (az/field element volume) (ak/intCast (+ 4 node)) (ak/intCast (+ 4 other)))]
                (set! (az/field result angular-momentum)
                      (p/add (az/field result angular-momentum)
                        (p/scale (p/cross position (az/index velocity (+ 4 other))) entry)))))))))
    (set! (az/field result center) (p/scale (az/field result center) (/ 1.0 (az/field result mass))))
    (when (or (ak/! (math/isFinite (az/field result mass))) (<= (az/field result mass) 0.0)
              (ak/! (math/isFinite (az/field result kinetic))) (ak/! (math/isFinite (az/field result elastic)))
              (ak/! (math/isFinite (az/field result potential)))) (ak/return result))
    (dotimes [axis 3]
      (when (or (ak/! (math/isFinite (fem/component (az/field result center) axis)))
                (ak/! (math/isFinite (fem/component (az/field result momentum) axis)))
                (ak/! (math/isFinite (fem/component (az/field result angular-momentum) axis)))) (ak/return result)))
    (set! (az/field result valid) true)
    result))

(defn- native-vector [value]
  (when-not (and (sequential? value) (= 3 (count value))
                (every? #(and (number? %) (Double/isFinite (double %))) value))
    (throw (ex-info "Expected three finite SI coordinates" {:value value})))
  (zipmap [:x :y :z] (map double value)))

(defn quadratic-controls
  "Elevate an affine tetrahedral mesh/initial fields to shared Bernstein edge
  controls. Cell corner connectivity stays unchanged. This is exact P1-to-P2
  elevation; edge coefficients are not nodal samples of a curved initial field."
  [{:keys [mesh initial-positions initial-velocities] :as description}]
  (let [{:keys [points cells]} mesh
        edges (vec (distinct (for [cell cells left (range 4) right (range (inc left) 4)]
                               (vec (sort [(cell left) (cell right)])))))
        indices (zipmap edges (range (count points) (+ (count points) (count edges))))
        extend-field (fn [values]
                       (into (vec values)
                             (map (fn [[a b]] (mapv #(* 0.5 (+ %1 %2)) (nth values a) (nth values b))) edges)))]
    (assoc description
           :mesh (assoc mesh :points (extend-field points))
           :initial-positions (extend-field (or initial-positions points))
           :initial-velocities (extend-field (or initial-velocities (repeat (count points) [0.0 0.0 0.0])))
           :cell-edges (mapv (fn [cell]
                               (mapv (fn [[left right]] (indices (vec (sort [(cell left) (cell right)]))))
                                     [[0 1] [0 2] [0 3] [1 2] [1 3] [2 3]])) cells))))

(defn with-context!
  "Own a complete native job for the callback. Mesh is {:points [...] :cells
  [[a b c d] ...]}, with optional initial positions/velocities. Cell material
  entries may override the common material and density. Never retain the pointer
  beyond the callback or reload native layouts while this scope is live."
  [{:keys [mesh initial-positions initial-velocities material density-kg-m3 gravity floor? floor-height cell-materials trace-degree]
    :or {material {:young-Pa 10000.0 :poisson-ratio 0.3} density-kg-m3 1000.0
         gravity [0.0 -9.81 0.0] floor? false floor-height 0.0 trace-degree 1}
    :as description} f]
  (let [{:keys [points cells]} mesh
        positions (or initial-positions points)
        velocities (or initial-velocities (vec (repeat (count points) [0.0 0.0 0.0])))]
    (when-not (and (#{1 2} trace-degree) (vector? points) (seq points) (vector? cells) (seq cells)
                  (= (count points) (count positions) (count velocities))
                  (or (nil? cell-materials) (= (count cells) (count cell-materials)))
                  (every? #(and (vector? %) (= 4 (count %))
                                (every? (fn [v] (and (integer? v) (<= 0 v) (< v (count points)))) %)) cells))
      (throw (ex-info "Invalid mixed tetrahedral mesh or initial fields" {})))
    (let [prepared (if (= 2 trace-degree) (quadratic-controls description) description)
          points (get-in prepared [:mesh :points])
          positions (or (:initial-positions prepared) points)
          velocities (or (:initial-velocities prepared) (repeat (count points) [0.0 0.0 0.0]))
          context (az/value (create! (count points) (count cells)))]
      (when-not context (throw (ex-info "Unable to allocate mixed FEM job" {})))
      (try
        (doseq [index (range (count points))]
          (when-not (set-vertex! context index (native-vector (points index))
                                (native-vector (nth positions index)) (native-vector (nth velocities index)))
            (throw (ex-info "Invalid mixed FEM vertex" {:vertex index}))))
        (doseq [cell (range (count cells))]
          (let [parameters (merge material {:density-kg-m3 density-kg-m3} (get cell-materials cell))]
            (when-not (set-element! context cell (cells cell) (double (:young-Pa parameters))
                                   (double (:poisson-ratio parameters)) (double (:density-kg-m3 parameters)))
              (throw (ex-info "Invalid mixed FEM element" {:cell cell})))
            (when (= 2 trace-degree)
              (when-not (set-quadratic-edges! context cell (get-in prepared [:cell-edges cell]))
                (throw (ex-info "Invalid mixed FEM quadratic controls" {:cell cell}))))))
        (when-not (configure! context (native-vector gravity) (boolean floor?) (double floor-height))
          (throw (ex-info "Mixed FEM initial mesh is invalid, infeasible or not geometrically certified" {})))
        (f context)
        (finally (destroy! context))))))

(defn snapshot
  "Copy trace controls and SI observables out of native memory. For quadratic
  elements these are Bernstein control positions, not interpolating mesh nodes."
  [context]
  {:observables (az/value (observables context))
   :positions (mapv (fn [index] (let [v (az/value (vertex-position context index))] (mapv v [:x :y :z])))
                    (range (vertex-count context)))})
