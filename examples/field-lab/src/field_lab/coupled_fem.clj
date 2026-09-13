(ns field-lab.coupled-fem
  "Joint explicit FEM stepping with discrete vertex/surface contact.
  Private owned jobs only. This is not continuous collision detection."
  (:require [aguafria.std]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.nonlinear-fem :as dynamics]
            [field-lab.contact-mesh :as contact]))

(az/defconst position-tolerance :f64 1.0e-9)

(az/defconst velocity-tolerance :f64 1.0e-7)

(az/defstruct Body
  [[:state [:* dynamics/Dynamics]] [:surface [:* contact/Surface]]])

(az/defstruct Assembly
  [[:bodies [:slice Body]] [:time :f64]])

(az/defstruct ContactResult {:layout :extern}
  [[:penetration :f64] [:normal-impulse :f64] [:tangent-impulse :f64]])

(az/defstruct Residual {:layout :extern}
  [[:penetration :f64] [:closing-speed :f64]])

(az/defstruct Report {:layout :extern}
  [[:completed :bool] [:substeps :u32] [:rejected :u32] [:time :f64]
   [:minimum-jacobian :f64] [:maximum-penetration :f64]
   [:ground-impulse :f64] [:pair-impulse :f64]])

(az/defn create!
  :- [:* Assembly]
  [[count :usize]]
  (debug/assert (and (> count 0) (<= count 3)))
  (let [assembly (catch ((az/field heap/page_allocator create) Assembly)
                   (debug/panic "Unable to allocate coupled FEM assembly" []))]
    (set! (az/deref assembly)
          (Assembly {:bodies (fem/allocate Body count) :time 0.0}))
    assembly))

(az/defn destroy!
  "Body states and surfaces belong to the enclosing Clojure resource scope."
  :- :void
  [[assembly [:* Assembly]]]
  ((az/field heap/page_allocator free) (az/field assembly bodies))
  ((az/field heap/page_allocator destroy) assembly))

(az/defn attach!
  :- :void
  [[assembly [:* Assembly]] [index :usize]
   [state [:* dynamics/Dynamics]] [surface [:* contact/Surface]]]
  (debug/assert (ak/== (az/field (az/field state masses) len)
                       (az/field (az/field surface points) len)))
  (set! (az/index (az/field assembly bodies) index) (Body {:state state :surface surface})))

(az/defn synchronize!
  :- :void
  [[body Body]]
  (let [state (az/field body state)
        surface (az/field body surface)]
    (dotimes [node (az/field (az/field state masses) len)]
      (contact/set-point! surface node (dynamics/position state node)))
    (contact/refit! surface)))

(az/defn candidate?
  :- :bool
  [[surface [:* contact/Surface]] [point p/Vec3]]
  (<= (contact/box-distance (az/field (az/index (az/field surface tree) 0) bounds) point)
      (* position-tolerance position-tolerance)))

(az/defn x-gap
  "Positive separation of ordered bodies' current boundary boxes along x."
  :- :f64
  [[assembly [:* Assembly]] [left :usize] [right :usize]]
  (let [a (az/field (az/index (az/field assembly bodies) left) surface)
        b (az/field (az/index (az/field assembly bodies) right) surface)
        bounds-a (az/field (az/index (az/field a tree) 0) bounds)
        bounds-b (az/field (az/index (az/field b tree) 0) bounds)]
    (- (az/field (az/field bounds-b lower) x) (az/field (az/field bounds-a upper) x))))

(az/defn relative-velocity
  :- p/Vec3
  [[a Body] [b Body] [node :usize] [closest contact/Closest]]
  (let [face (az/index (az/field (az/field b surface) faces) (az/field closest face))
        ^:var velocity (dynamics/particle-velocity (az/field a state) node)]
    (dotimes [local 3]
      (set! velocity
            (p/add velocity
                   (p/scale (dynamics/particle-velocity (az/field b state) (az/index face local))
                            (- (fem/component (az/field closest weights) local))))))
    velocity))

(az/defn apply-correction!
  :- :void
  [[body Body] [node :usize] [position-change p/Vec3] [impulse p/Vec3] [move :bool]]
  (let [state (az/field body state)
        point (p/add (dynamics/position state node) position-change)
        velocity (p/add (dynamics/particle-velocity state node)
                        (p/scale impulse (/ 1.0 (az/index (az/field state masses) node))))]
    (if move
      (do
        (dynamics/set-particle! state node point velocity)
        (contact/move-point! (az/field body surface) node point))
      (set! (az/index (az/field state velocities) node) velocity))))

(az/defn project-vertex!
  "Mass-weighted position repair and inelastic Coulomb impulses on both bodies.
  Barycentric reaction weights preserve equal and opposite linear impulses."
  :- ContactResult
  [[a Body] [b Body] [node :usize] [move :bool]]
  (let [surface (az/field b surface)
        point (dynamics/position (az/field a state) node)
        ^:var result (ContactResult {:penetration 0.0 :normal-impulse 0.0 :tangent-impulse 0.0})]
    (when (ak/! (candidate? surface point)) (ak/return result))
    (let [closest (contact/closest-point surface point)
          distance (az/field closest signed-distance)]
      (when (> distance position-tolerance) (ak/return result))
      (let [normal (az/field closest normal)
            face (az/index (az/field surface faces) (az/field closest face))
            inverse-a (/ 1.0 (az/index (az/field (az/field a state) masses) node))
            ^:var inverse inverse-a]
        (dotimes [local 3]
          (let [weight (fem/component (az/field closest weights) local)
                mass (az/index (az/field (az/field b state) masses) (az/index face local))]
            (ak/+= inverse (/ (* weight weight) mass))))
        (let [velocity (relative-velocity a b node closest)
              normal-speed (p/dot velocity normal)
              tangent (p/add velocity (p/scale normal (- normal-speed)))
              tangent-speed (p/length tangent)
              normal-magnitude (/ (ak/max 0.0 (- normal-speed)) inverse)
              friction (ak/sqrt (* (az/field (az/field a state) friction)
                                  (az/field (az/field b state) friction)))
              tangent-impulse (ak/min (* friction normal-magnitude) (/ tangent-speed inverse))
              impulse (p/add (p/scale normal normal-magnitude)
                             (p/scale tangent (/ (- tangent-impulse) (ak/max 1.0e-30 tangent-speed))))
              penetration (ak/max 0.0 (- distance))
              correction (p/scale normal (if move (/ penetration inverse) 0.0))]
          (apply-correction! a node (p/scale correction inverse-a) impulse move)
          (dotimes [local 3]
            (let [vertex (az/index face local)
                  weight (fem/component (az/field closest weights) local)
                  mass (az/index (az/field (az/field b state) masses) vertex)]
              (apply-correction! b vertex (p/scale correction (/ (- weight) mass))
                                 (p/scale impulse (- weight)) move)))
          (az/set-many!
            (az/field result penetration) penetration
            (az/field result normal-impulse) normal-magnitude
            (az/field result tangent-impulse) tangent-impulse))))
    result))

(az/defn pair-pass!
  :- :f64
  [[assembly [:* Assembly]] [move :bool]]
  (let [bodies (az/field assembly bodies)
        ^{:var :f64} impulse 0.0]
    (dotimes [a (az/field bodies len)]
      (dotimes [b (az/field bodies len)]
        (when (ak/!= a b)
          (let [source (az/index bodies a)
                surface (az/field source surface)]
            (dotimes [node (az/field (az/field surface points) len)]
              ;; Only boundary nodes participate; interior FEM nodes have no faces.
              (when (< (az/index (az/field surface offsets) node)
                       (az/index (az/field surface offsets) (+ node 1)))
                (ak/+= impulse (az/field (project-vertex! source (az/index bodies b) node move) normal-impulse))))))))
    impulse))

(az/defn residual
  :- Residual
  [[assembly [:* Assembly]]]
  (let [bodies (az/field assembly bodies)
        ^:var result (Residual {:penetration 0.0 :closing-speed 0.0})]
    (dotimes [a (az/field bodies len)]
      (dotimes [b (az/field bodies len)]
        (when (ak/!= a b)
          (let [source (az/index bodies a)
                target (az/index bodies b)
                surface (az/field source surface)]
            (dotimes [node (az/field (az/field surface points) len)]
              (when (< (az/index (az/field surface offsets) node)
                       (az/index (az/field surface offsets) (+ node 1)))
                (let [point (dynamics/position (az/field source state) node)]
                  (when (candidate? (az/field target surface) point)
                    (let [closest (contact/closest-point (az/field target surface) point)
                          distance (az/field closest signed-distance)]
                      (set! (az/field result penetration)
                            (ak/max (az/field result penetration) (- distance)))
                      (when (<= distance position-tolerance)
                        (set! (az/field result closing-speed)
                              (ak/max (az/field result closing-speed)
                                      (- (p/dot (relative-velocity source target node closest)
                                                (az/field closest normal)))))))))))))))
    result))

(az/defn ground-pass!
  :- :f64
  [[assembly [:* Assembly]] [move :bool]]
  (let [bodies (az/field assembly bodies)
        ^{:var :f64} impulse 0.0]
    (dotimes [index (az/field bodies len)]
      (let [body (az/index bodies index)]
        (ak/+= impulse (dynamics/ground-contact! (az/field body state)))
        (when move (synchronize! body))))
    impulse))

(az/defn observe!
  :- dynamics/Observables
  [[assembly [:* Assembly]]]
  (let [bodies (az/field assembly bodies)
        ^:var result (dynamics/Observables
                      {:elastic-energy 0.0 :kinetic-energy 0.0 :potential-energy 0.0
                       :minimum-jacobian 1.0e30 :frequency-squared-bound 0.0
                       :minimum-height 1.0e30 :mass 0.0
                       :center (p/v 0.0 0.0 0.0) :momentum (p/v 0.0 0.0 0.0)})]
    (dotimes [index (az/field bodies len)]
      (let [value (dynamics/evaluate! (az/field (az/index bodies index) state))]
        (az/set-many!
          (az/field result elastic-energy) (+ (az/field result elastic-energy) (az/field value elastic-energy))
          (az/field result kinetic-energy) (+ (az/field result kinetic-energy) (az/field value kinetic-energy))
          (az/field result potential-energy) (+ (az/field result potential-energy) (az/field value potential-energy))
          (az/field result minimum-jacobian) (ak/min (az/field result minimum-jacobian) (az/field value minimum-jacobian))
          (az/field result minimum-height) (ak/min (az/field result minimum-height) (az/field value minimum-height))
          (az/field result frequency-squared-bound)
          (ak/max (az/field result frequency-squared-bound) (az/field value frequency-squared-bound))
          (az/field result mass) (+ (az/field result mass) (az/field value mass))
          (az/field result momentum) (p/add (az/field result momentum) (az/field value momentum))
          (az/field result center) (p/add (az/field result center)
                                         (p/scale (az/field value center) (az/field value mass))))))
    (set! (az/field result center) (p/scale (az/field result center) (/ 1.0 (az/field result mass))))
    result))

(az/defn valid?
  :- :bool
  [[value dynamics/Observables]]
  (and (> (az/field value minimum-jacobian) 0.05)
       (math/isFinite (az/field value elastic-energy))
       (math/isFinite (az/field value kinetic-energy))
       (math/isFinite (az/field value frequency-squared-bound))))

(az/defn checkpoint!
  :- :void
  [[assembly [:* Assembly]] [restore :bool]]
  (let [bodies (az/field assembly bodies)]
    (dotimes [index (az/field bodies len)]
      (let [body (az/index bodies index)]
        (dynamics/checkpoint! (az/field body state) restore)
        (when restore (synchronize! body))))))

(az/defstruct NormalSystem
  "Owned dense normal-contact system. Input is A = J M^-1 J^T and b = J v."
  [[:matrix [:slice :f64]] [:scaled [:slice :f64]] [:factor [:slice :f64]]
   [:rhs [:slice :f64]] [:scales [:slice :f64]] [:impulses [:slice :f64]]
   [:velocities [:slice :f64]] [:direction [:slice :f64]] [:change [:slice :f64]]
   [:solution [:slice :f64]] [:indices [:slice :usize]] [:active [:slice :bool]]])

(az/defstruct NormalReport
  "Status: 0 success, 1 singular active system, 2 no finite pivot,
  3 pivot limit, 4 failed complementarity check, 5 invalid input."
  {:layout :extern}
  [[:status :u32] [:pivots :u32] [:minimum-velocity :f64]
   [:minimum-impulse :f64] [:maximum-complementarity :f64]])

(az/defn create-normal-system!
  :- [:* NormalSystem]
  [[count :usize]]
  (debug/assert (and (> count 0) (<= count 128)))
  (let [system (catch ((az/field heap/page_allocator create) NormalSystem)
                 (debug/panic "Unable to allocate normal-contact system" []))]
    (set! (az/deref system)
          (NormalSystem {:matrix (fem/allocate :f64 (* count count))
                         :scaled (fem/allocate :f64 (* count count))
                         :factor (fem/allocate :f64 (* count count))
                         :rhs (fem/allocate :f64 count) :scales (fem/allocate :f64 count)
                         :impulses (fem/allocate :f64 count) :velocities (fem/allocate :f64 count)
                         :direction (fem/allocate :f64 count) :change (fem/allocate :f64 count)
                         :solution (fem/allocate :f64 count) :indices (fem/allocate :usize count)
                         :active (fem/allocate :bool count)}))
    (dotimes [i (* count count)] (set! (az/index (az/field system matrix) i) 0.0))
    (dotimes [i count]
      (az/set-many!
        (az/index (az/field system rhs) i) 0.0
        (az/index (az/field system impulses) i) 0.0))
    system))

(az/defn destroy-normal-system!
  :- :void
  [[system [:* NormalSystem]]]
  ((az/field heap/page_allocator free) (az/field system matrix))
  ((az/field heap/page_allocator free) (az/field system scaled))
  ((az/field heap/page_allocator free) (az/field system factor))
  ((az/field heap/page_allocator free) (az/field system rhs))
  ((az/field heap/page_allocator free) (az/field system scales))
  ((az/field heap/page_allocator free) (az/field system impulses))
  ((az/field heap/page_allocator free) (az/field system velocities))
  ((az/field heap/page_allocator free) (az/field system direction))
  ((az/field heap/page_allocator free) (az/field system change))
  ((az/field heap/page_allocator free) (az/field system solution))
  ((az/field heap/page_allocator free) (az/field system indices))
  ((az/field heap/page_allocator free) (az/field system active))
  ((az/field heap/page_allocator destroy) system))

(az/defn set-normal-entry!
  :- :void
  [[system [:* NormalSystem]] [row :usize] [column :usize] [value :f64]]
  (set! (az/index (az/field system matrix) (+ (* row (az/field (az/field system rhs) len)) column)) value))

(az/defn set-normal-rhs!
  :- :void
  [[system [:* NormalSystem]] [row :usize] [value :f64]]
  (set! (az/index (az/field system rhs) row) value))

(az/defn normal-impulse
  :- :f64
  [[system [:* NormalSystem]] [row :usize]]
  (az/index (az/field system impulses) row))

(az/defn normal-direction!
  "Solve the active principal system with partial pivoting; never regularize it."
  :- :bool
  [[system [:* NormalSystem]] [driven :usize]]
  (let [count (az/field (az/field system rhs) len)
        ^{:var :usize} active-count 0]
    (dotimes [i count]
      (set! (az/index (az/field system direction) i) 0.0)
      (when (az/index (az/field system active) i)
        (set! (az/index (az/field system indices) active-count) i)
        (ak/+= active-count 1)))
    (set! (az/index (az/field system direction) driven) 1.0)
    (dotimes [i active-count]
      (let [row (az/index (az/field system indices) i)]
        (set! (az/index (az/field system solution) i)
              (- (az/index (az/field system scaled) (+ (* row count) driven))))
        (dotimes [j active-count]
          (set! (az/index (az/field system factor) (+ (* i count) j))
                (az/index (az/field system scaled)
                          (+ (* row count) (az/index (az/field system indices) j)))))))
    (dotimes [column active-count]
      (let [^{:var :usize} pivot column]
        (dotimes [offset (- active-count column)]
          (let [row (+ column offset)]
            (when (> (ak/abs (az/index (az/field system factor) (+ (* row count) column)))
                     (ak/abs (az/index (az/field system factor) (+ (* pivot count) column))))
              (set! pivot row))))
        (when (<= (ak/abs (az/index (az/field system factor) (+ (* pivot count) column))) 1.0e-14)
          (ak/return false))
        (when (ak/!= pivot column)
          (dotimes [j active-count]
            (let [value (az/index (az/field system factor) (+ (* column count) j))]
              (az/set-many!
                (az/index (az/field system factor) (+ (* column count) j))
                (az/index (az/field system factor) (+ (* pivot count) j))
                (az/index (az/field system factor) (+ (* pivot count) j)) value)))
          (let [value (az/index (az/field system solution) column)]
            (az/set-many!
              (az/index (az/field system solution) column) (az/index (az/field system solution) pivot)
              (az/index (az/field system solution) pivot) value)))
        (dotimes [offset (- active-count (+ column 1))]
          (let [row (+ column 1 offset)
                multiplier (/ (az/index (az/field system factor) (+ (* row count) column))
                              (az/index (az/field system factor) (+ (* column count) column)))]
            (dotimes [j (- active-count column)]
              (ak/-= (az/index (az/field system factor) (+ (* row count) column j))
                      (* multiplier (az/index (az/field system factor) (+ (* column count) column j)))))
            (ak/-= (az/index (az/field system solution) row)
                    (* multiplier (az/index (az/field system solution) column)))))))
    (dotimes [offset active-count]
      (let [row (- active-count 1 offset)
            ^:var value (az/index (az/field system solution) row)]
        (dotimes [j (- active-count (+ row 1))]
          (let [column (+ row 1 j)]
            (ak/-= value (* (az/index (az/field system factor) (+ (* row count) column))
                            (az/index (az/field system solution) column)))))
        (set! (az/index (az/field system solution) row)
              (/ value (az/index (az/field system factor) (+ (* row count) row))))))
    (dotimes [i active-count]
      (set! (az/index (az/field system direction) (az/index (az/field system indices) i))
            (az/index (az/field system solution) i)))
    true))

(az/defn solve-normal-system!
  "Dantzig normal complementarity pivots, adapted to impulses and J*v.
  Requires a symmetric PSD Delassus matrix. Dense capacity is 128 constraints.
  Only status 0 permits applying the returned impulses. No Coulomb solve or CCD."
  :- NormalReport
  [[system [:* NormalSystem]]]
  (let [count (az/field (az/field system rhs) len)
        ^:var report (NormalReport {:status 0 :pivots 0 :minimum-velocity 1.0e300
                                    :minimum-impulse 1.0e300 :maximum-complementarity 0.0})]
    (dotimes [i count]
      (let [diagonal (az/index (az/field system matrix) (+ (* i count) i))]
        (when (or (ak/! (math/isFinite diagonal)) (<= diagonal 0.0)
                  (ak/! (math/isFinite (az/index (az/field system rhs) i))))
          (set! (az/field report status) 5)
          (ak/return report))
        (az/set-many!
          (az/index (az/field system scales) i) (ak/sqrt diagonal)
          (az/index (az/field system impulses) i) 0.0
          (az/index (az/field system active) i) false)))
    (dotimes [i count]
      (set! (az/index (az/field system velocities) i)
            (/ (az/index (az/field system rhs) i) (az/index (az/field system scales) i)))
      (dotimes [j count]
        (let [value (/ (az/index (az/field system matrix) (+ (* i count) j))
                       (* (az/index (az/field system scales) i) (az/index (az/field system scales) j)))
              transposed (/ (az/index (az/field system matrix) (+ (* j count) i))
                            (* (az/index (az/field system scales) i) (az/index (az/field system scales) j)))]
          (when (or (ak/! (math/isFinite value)) (ak/! (math/isFinite transposed))
                    (> (ak/abs (- value transposed)) 1.0e-12))
            (set! (az/field report status) 5)
            (ak/return report))
          (set! (az/index (az/field system scaled) (+ (* i count) j)) value))))
    (dotimes [driven count]
      (while (< (az/index (az/field system velocities) driven) -1.0e-12)
        (when (>= (az/field report pivots) (+ 64 (* 64 count count)))
          (set! (az/field report status) 3)
          (ak/return report))
        (when (ak/! (normal-direction! system driven))
          (set! (az/field report status) 1)
          (ak/return report))
        (dotimes [i count]
          (let [^{:var :f64} value 0.0]
            (dotimes [j count]
              (ak/+= value (* (az/index (az/field system scaled) (+ (* i count) j))
                              (az/index (az/field system direction) j))))
            (set! (az/index (az/field system change) i) value)))
        (let [^{:var :f64} step 1.0e300
              ^{:var :usize} limiting driven
              derivative (az/index (az/field system change) driven)]
          (when (> derivative 1.0e-15)
            (set! step (/ (- (az/index (az/field system velocities) driven)) derivative)))
          (dotimes [i driven]
            (let [active (az/index (az/field system active) i)
                  slope (if active (az/index (az/field system direction) i)
                                   (az/index (az/field system change) i))
                  value (if active (az/index (az/field system impulses) i)
                                   (az/index (az/field system velocities) i))]
              (when (< slope -1.0e-15)
                (let [candidate (ak/max 0.0 (/ (- value) slope))]
                  (when (< candidate step)
                    (az/set-many! step candidate limiting i))))))
          (when (or (ak/! (math/isFinite step)) (>= step 1.0e300))
            (set! (az/field report status) 2)
            (ak/return report))
          (dotimes [i count]
            (ak/+= (az/index (az/field system impulses) i)
                    (* step (az/index (az/field system direction) i))))
          (dotimes [i count]
            (let [^:var value (/ (az/index (az/field system rhs) i) (az/index (az/field system scales) i))]
              (dotimes [j count]
                (ak/+= value (* (az/index (az/field system scaled) (+ (* i count) j))
                                (az/index (az/field system impulses) j))))
              (set! (az/index (az/field system velocities) i) value)))
          (ak/+= (az/field report pivots) 1)
          (if (ak/== limiting driven)
            (do (set! (az/index (az/field system active) driven) true) (ak/break))
            (set! (az/index (az/field system active) limiting)
                  (ak/! (az/index (az/field system active) limiting)))))))
    (dotimes [i count]
      (set! (az/index (az/field system impulses) i)
            (/ (az/index (az/field system impulses) i) (az/index (az/field system scales) i))))
    (let [^{:var :f64} maximum-impulse 0.0]
      (dotimes [i count]
        (let [^:var velocity (az/index (az/field system rhs) i)
              impulse (az/index (az/field system impulses) i)]
          (dotimes [j count]
            (ak/+= velocity (* (az/index (az/field system matrix) (+ (* i count) j))
                               (az/index (az/field system impulses) j))))
          (when (or (ak/! (math/isFinite velocity)) (ak/! (math/isFinite impulse)))
            (set! (az/field report status) 4)
            (ak/return report))
          (az/set-many!
            (az/field report minimum-velocity) (ak/min (az/field report minimum-velocity) velocity)
            (az/field report minimum-impulse) (ak/min (az/field report minimum-impulse) impulse)
            (az/field report maximum-complementarity)
            (ak/max (az/field report maximum-complementarity) (ak/abs (* velocity impulse)))
            maximum-impulse (ak/max maximum-impulse (ak/abs impulse)))))
      (when (or (< (az/field report minimum-velocity) -1.0e-8)
                (< (az/field report minimum-impulse) -1.0e-10)
                (> (az/field report maximum-complementarity) (* 1.0e-8 (ak/max 1.0 maximum-impulse))))
        (set! (az/field report status) 4)))
    report))

(az/defstruct ContactWeight
  [[:body :usize] [:node :usize] [:weight :f64]])

(az/defstruct VelocityContact
  [[:entries [:array 4 ContactWeight]] [:normal p/Vec3] [:friction :f64] [:ground :bool]])

(az/defstruct BlockReport {:layout :extern}
  [[:completed :bool] [:status :u32] [:contacts :usize] [:pivots :u32]
   [:ground-impulse :f64] [:pair-impulse :f64] [:closing-speed :f64]])

(az/defn contact-row-velocity
  :- p/Vec3
  [[assembly [:* Assembly]] [row VelocityContact]]
  (let [^:var velocity (p/v 0.0 0.0 0.0)]
    (dotimes [i 4]
      (let [entry (az/index (az/field row entries) i)]
        (when (ak/!= (az/field entry weight) 0.0)
          (set! velocity
                (p/add velocity
                       (p/scale (dynamics/particle-velocity
                                 (az/field (az/index (az/field assembly bodies) (az/field entry body)) state)
                                 (az/field entry node))
                                (az/field entry weight)))))))
    velocity))

(az/defn contact-row-inner
  :- :f64
  [[assembly [:* Assembly]] [left VelocityContact] [right VelocityContact]]
  (let [^{:var :f64} value 0.0]
    (dotimes [i 4]
      (let [a (az/index (az/field left entries) i)]
        (when (ak/!= (az/field a weight) 0.0)
          (dotimes [j 4]
            (let [b (az/index (az/field right entries) j)]
              (when (and (ak/!= (az/field b weight) 0.0)
                         (ak/== (az/field a body) (az/field b body))
                         (ak/== (az/field a node) (az/field b node)))
                (let [state (az/field (az/index (az/field assembly bodies) (az/field a body)) state)
                      mass (az/index (az/field state masses) (az/field a node))]
                  (ak/+= value (/ (* (az/field a weight) (az/field b weight)) mass)))))))))
    (* value (p/dot (az/field left normal) (az/field right normal)))))

(az/defn apply-row-impulse!
  :- :void
  [[assembly [:* Assembly]] [row VelocityContact] [impulse p/Vec3]]
  (dotimes [i 4]
    (let [entry (az/index (az/field row entries) i)]
      (when (ak/!= (az/field entry weight) 0.0)
        (apply-correction! (az/index (az/field assembly bodies) (az/field entry body))
                           (az/field entry node) (p/v 0.0 0.0 0.0)
                           (p/scale impulse (az/field entry weight)) false)))))

(az/defn rows-closing-speed
  :- :f64
  [[assembly [:* Assembly]] [rows [:c-pointer VelocityContact]] [count :usize]]
  (let [^{:var :f64} speed 0.0]
    (dotimes [i count]
      (let [row (az/index rows i)]
        (set! speed (ak/max speed (- (p/dot (az/field row normal) (contact-row-velocity assembly row)))))))
    speed))

(az/defn solve-contact-rows!
  "Coupled normal solve and bounded Coulomb increments for explicit four-node rows.
  Requires valid unit normals and free positive-mass nodes. Failure may partially
  change velocities; caller must own rollback. Capacity is 128 constraints."
  :- BlockReport
  [[assembly [:* Assembly]] [rows [:c-pointer VelocityContact]] [count :usize]]
  (let [^:var report (BlockReport {:completed false :status 0 :contacts count :pivots 0
                                   :ground-impulse 0.0 :pair-impulse 0.0 :closing-speed 0.0})]
    (when (> count 128)
      (set! (az/field report status) 1)
      (ak/return report))
    (set! (az/field report closing-speed) (rows-closing-speed assembly rows count))
    (when (<= (az/field report closing-speed) velocity-tolerance)
      (set! (az/field report completed) true)
      (ak/return report))
    (let [system (create-normal-system! count)]
      (defer (destroy-normal-system! system))
      (dotimes [i count]
        (dotimes [j count]
          (set-normal-entry! system i j (contact-row-inner assembly (az/index rows i) (az/index rows j)))))
      (dotimes [_ 64]
        (dotimes [i count]
          (set-normal-rhs! system i (p/dot (az/field (az/index rows i) normal)
                                          (contact-row-velocity assembly (az/index rows i)))))
        (let [solved (solve-normal-system! system)]
          (ak/+= (az/field report pivots) (az/field solved pivots))
          (when (ak/!= (az/field solved status) 0)
            (set! (az/field report status) (+ 10 (az/field solved status)))
            (ak/return report)))
        (dotimes [i count]
          (let [row (az/index rows i)
                magnitude (ak/max 0.0 (normal-impulse system i))]
            (apply-row-impulse! assembly row (p/scale (az/field row normal) magnitude))
            (if (az/field row ground)
              (ak/+= (az/field report ground-impulse) magnitude)
              (ak/+= (az/field report pair-impulse) magnitude))))
        ;; Each tangential update uses the current velocity, so its kinetic
        ;; energy cannot increase even when contact rows share mass points.
        (dotimes [i count]
          (let [row (az/index rows i)
                velocity (contact-row-velocity assembly row)
                normal (az/field row normal)
                tangent (p/add velocity (p/scale normal (- (p/dot velocity normal))))
                speed (p/length tangent)
                inverse (contact-row-inner assembly row row)
                budget (* (az/field row friction) (ak/max 0.0 (normal-impulse system i)))
                magnitude (ak/min budget (/ speed inverse))]
            (when (> speed 0.0)
              (apply-row-impulse! assembly row (p/scale tangent (/ (- magnitude) speed))))))
        (set! (az/field report closing-speed) (rows-closing-speed assembly rows count))
        (when (<= (az/field report closing-speed) velocity-tolerance)
          (set! (az/field report completed) true)
          (ak/return report))))
    (set! (az/field report status) 3)
    report))

(az/defstruct FeatureContact
  [[:row VelocityContact] [:distance :f64] [:valid :bool]])

(az/defn feature-contact
  "Current-geometry contact row for a CCD-reported feature. A zero/ambiguous gap
  is an explicit invalid result; do not invent a direction at intersection."
  :- FeatureContact
  [[assembly [:* Assembly]] [a :usize] [b :usize]
   [face-a :u32] [face-b :u32] [index :usize]]
  (let [left (az/index (az/field assembly bodies) a)
        right (az/index (az/field assembly bodies) b)
        feature (contact/triangle-feature
                 (az/index (az/field (az/field left surface) faces) face-a)
                 (az/index (az/field (az/field right surface) faces) face-b) index)
        empty (ContactWeight {:body 0 :node 0 :weight 0.0})
        ^:var result (FeatureContact
                      {:valid false :distance 0.0
                       :row (VelocityContact {:entries [empty empty empty empty]
                                              :normal (p/v 0.0 0.0 0.0) :ground false
                                              :friction (ak/sqrt (* (az/field (az/field left state) friction)
                                                                   (az/field (az/field right state) friction)))})})
        ^{:var [:array 4 p/Vec3]} points ak/undefined
        ^{:var [:array 4 :f64]} weights ak/undefined
        ^:var delta (p/v 0.0 0.0 0.0)]
    (dotimes [i 4]
      (let [body (if (az/index (az/field feature left) i) a b)
            node (az/index (az/field feature nodes) i)]
        (set! (az/index points i)
              (dynamics/position (az/field (az/index (az/field assembly bodies) body) state) node))))
    (if (ak/== (az/field feature kind) 0)
      (let [u (p/add (az/index points 2) (p/scale (az/index points 1) -1.0))
            v (p/add (az/index points 3) (p/scale (az/index points 1) -1.0))
            normal (p/cross u v)]
        (when (<= (p/dot normal normal) 1.0e-30) (ak/return result))
        (let [closest (contact/triangle-closest (az/index points 0) (az/index points 1)
                                               (az/index points 2) (az/index points 3))]
          (az/set-many!
            delta (p/add (az/index points 0) (p/scale (az/field closest point) -1.0))
            (az/index weights 0) 1.0)
          (let [barycentric (az/field closest weights)]
            ;; The interior distance gradient is the geometric face normal.
            ;; Subtracting two nearly equal closest points amplifies tangential
            ;; roundoff by 1/gap and can spuriously rotate resting contact rows.
            (when (and (> (az/field barycentric x) 1.0e-10)
                       (> (az/field barycentric y) 1.0e-10)
                       (> (az/field barycentric z) 1.0e-10))
              (set! delta (p/scale normal (/ (p/dot delta normal) (p/dot normal normal))))))
          (dotimes [i 3]
            (set! (az/index weights (+ i 1)) (- (fem/component (az/field closest weights) i))))))
      (let [closest (contact/segment-closest (az/index points 0) (az/index points 1)
                                             (az/index points 2) (az/index points 3))]
        (az/set-many!
          delta (p/add (az/field closest a) (p/scale (az/field closest b) -1.0))
          (az/index weights 0) (- 1.0 (az/field closest s))
          (az/index weights 1) (az/field closest s)
          (az/index weights 2) (- (az/field closest t) 1.0)
          (az/index weights 3) (- (az/field closest t)))
        (when (and (> (az/field closest s) 1.0e-10) (< (az/field closest s) (- 1.0 1.0e-10))
                   (> (az/field closest t) 1.0e-10) (< (az/field closest t) (- 1.0 1.0e-10)))
          (let [u (p/add (az/index points 1) (p/scale (az/index points 0) -1.0))
                v (p/add (az/index points 3) (p/scale (az/index points 2) -1.0))
                normal (p/cross u v)
                squared (p/dot normal normal)]
            (when (> squared 1.0e-30)
              (set! delta (p/scale normal (/ (p/dot delta normal) squared))))))))
    (let [distance (p/length delta)]
      (set! (az/field result distance) distance)
      (when (or (ak/! (math/isFinite distance)) (<= distance 1.0e-12)) (ak/return result))
      (az/set-many!
        (az/field result valid) true
        (az/field (az/field result row) normal) (p/scale delta (/ 1.0 distance)))
      (dotimes [i 4]
        (set! (az/index (az/field (az/field result row) entries) i)
              (ContactWeight {:body (if (az/index (az/field feature left) i) a b)
                              :node (az/index (az/field feature nodes) i)
                              :weight (az/index weights i)}))))
    result))

(az/defn resolve-velocity-contact!
  "Inelastic normal response and a dissipative Coulomb increment on four nodes.
  The row must have a unit normal and valid positive masses. Positions stay fixed."
  :- ContactResult
  [[assembly [:* Assembly]] [row VelocityContact]]
  (let [velocity (contact-row-velocity assembly row)
        normal (az/field row normal)
        normal-speed (p/dot velocity normal)
        inverse (contact-row-inner assembly row row)
        tangent (p/add velocity (p/scale normal (- normal-speed)))
        tangent-speed (p/length tangent)
        magnitude (/ (ak/max 0.0 (- normal-speed)) inverse)
        friction (ak/min (* (az/field row friction) magnitude) (/ tangent-speed inverse))]
    (when (> magnitude 0.0)
      (apply-row-impulse! assembly row
                        (p/add (p/scale normal magnitude)
                               (p/scale tangent (/ (- friction) (ak/max 1.0e-30 tangent-speed))))))
    (ContactResult {:penetration 0.0 :normal-impulse magnitude :tangent-impulse friction})))

(az/defstruct DriftBody
  [[:motion [:* contact/MotionSurface]] [:proposed [:slice :f64]] [:velocity [:slice p/Vec3]]])

(az/defstruct DriftWorkspace
  [[:bodies [:slice DriftBody]] [:pairs [:slice [:array 2 :u32]]]
   [:rows [:slice VelocityContact]]])

(az/defstruct DriftReport {:layout :extern}
  [[:completed :bool] [:status :u32] [:passes :u32] [:queries :u64]
   [:safe-fraction :f64] [:ground-impulse :f64] [:pair-impulse :f64]
   [:distance :f64] [:normal-speed :f64] [:toi :f64] [:achieved-tolerance :f64]])

(az/defn create-drift-workspace!
  :- [:* DriftWorkspace]
  [[assembly [:* Assembly]]]
  (let [count (az/field (az/field assembly bodies) len)
        workspace (catch ((az/field heap/page_allocator create) DriftWorkspace)
                    (debug/panic "Unable to allocate continuous drift workspace" []))]
    (set! (az/deref workspace) (DriftWorkspace {:bodies (fem/allocate DriftBody count)
                                                      :pairs (fem/allocate (az/type [:array 2 :u32]) 8192)
                                                      :rows (fem/allocate VelocityContact 128)}))
    (dotimes [i count]
      (let [body (az/index (az/field assembly bodies) i)
            nodes (az/field (az/field (az/field body state) masses) len)]
        (set! (az/index (az/field workspace bodies) i)
              (DriftBody {:motion (contact/create-motion! (az/field body surface))
                          :proposed (fem/allocate :f64 (* 3 nodes))
                          :velocity (fem/allocate p/Vec3 nodes)}))))
    workspace))

(az/defn destroy-drift-workspace!
  :- :void
  [[workspace [:* DriftWorkspace]]]
  (dotimes [i (az/field (az/field workspace bodies) len)]
    (let [body (az/index (az/field workspace bodies) i)]
      (contact/destroy-motion! (az/field body motion))
      ((az/field heap/page_allocator free) (az/field body proposed))
      ((az/field heap/page_allocator free) (az/field body velocity))))
  ((az/field heap/page_allocator free) (az/field workspace bodies))
  ((az/field heap/page_allocator free) (az/field workspace pairs))
  ((az/field heap/page_allocator free) (az/field workspace rows))
  ((az/field heap/page_allocator destroy) workspace))

(az/defn stage-drift!
  "Store the actual representable displacement endpoints without committing them.
  Plane response limits the average velocity to land on, or above, the plane."
  :- :f64
  [[assembly [:* Assembly]] [workspace [:* DriftWorkspace]] [duration :f64]]
  (let [^{:var :f64} ground-impulse 0.0]
    (dotimes [i (az/field (az/field assembly bodies) len)]
      (let [body (az/index (az/field assembly bodies) i)
            state (az/field body state)
            mesh (az/field state mesh)
            scratch (az/index (az/field workspace bodies) i)]
        (dotimes [node (az/field (az/field state masses) len)]
          (let [start (dynamics/position state node)
                ^:var velocity (dynamics/particle-velocity state node)
                ^:var end (p/v 0.0 0.0 0.0)]
            (when (az/field state floor)
              (let [normal-change (ak/max 0.0 (- (/ (- (az/field start y)) duration) (az/field velocity y)))
                    speed (p/length (p/v (az/field velocity x) 0.0 (az/field velocity z)))
                    factor (if (> speed 0.0)
                             (ak/max 0.0 (- 1.0 (/ (* (az/field state friction) normal-change) speed))) 1.0)]
                (az/set-many!
                  velocity (p/v (* factor (az/field velocity x)) (+ (az/field velocity y) normal-change)
                                (* factor (az/field velocity z)))
                  (az/index (az/field state velocities) node) velocity
                  ground-impulse (+ ground-impulse (* normal-change (az/index (az/field state masses) node))))))
            (dotimes [axis 3]
              (let [index (+ (* node 3) axis)
                    reference (fem/component (az/index (az/field mesh positions) node) axis)
                    proposed (+ (az/index (az/field mesh displacement) index)
                                (* duration (fem/component velocity axis)))
                    value (if (and (az/field state floor) (ak/== axis 1) (< (+ reference proposed) 0.0))
                            (- reference) proposed)]
                (set! (az/index (az/field scratch proposed) index) value)
                (if (ak/== axis 0) (set! (az/field end x) (+ reference value))
                    (if (ak/== axis 1) (set! (az/field end y) (+ reference value))
                        (set! (az/field end z) (+ reference value))))))
            (contact/set-motion-point! (az/field scratch motion) node start end)))))
    ground-impulse))

(az/defstruct ProximityReport {:layout :extern}
  [[:status :u32] [:contacts :u32] [:closing-speed :f64] [:pair-impulse :f64]])

(az/defn proximity-pass!
  "Dissipative impulses on currently near features, before trajectory testing.
  A pass with closing-speed <= tolerance changes no velocity. Repeat otherwise,
  since later impulses can reactivate earlier constraints. No position edits."
  :- ProximityReport
  [[assembly [:* Assembly]] [workspace [:* DriftWorkspace]] [clearance :f64]]
  (let [bodies (az/field assembly bodies)
        ^:var report (ProximityReport {:status 0 :contacts 0 :closing-speed 0.0 :pair-impulse 0.0})]
    (dotimes [a (az/field bodies len)]
      (dotimes [b (az/field bodies len)]
        (when (< a b)
          (let [pairs (contact/near-face-pairs! (az/field (az/index bodies a) surface)
                                               (az/field (az/index bodies b) surface)
                                               clearance (az/field workspace pairs))]
            (when (ak/!= (az/field pairs status) 0)
              (set! (az/field report status) 1)
              (ak/return report))
            (dotimes [pair (az/field pairs count)]
              (let [indices (az/index (az/field workspace pairs) pair)]
                (dotimes [feature 15]
                  (let [constraint (feature-contact assembly a b (az/index indices 0) (az/index indices 1) feature)]
                    (when (ak/! (az/field constraint valid))
                      (set! (az/field report status) 2)
                      (ak/return report))
                    (when (<= (az/field constraint distance) clearance)
                      (ak/+= (az/field report contacts) 1)
                      (let [row (az/field constraint row)
                            closing (- (p/dot (az/field row normal) (contact-row-velocity assembly row)))]
                        (set! (az/field report closing-speed) (ak/max (az/field report closing-speed) closing))
                        (when (> closing velocity-tolerance)
                          (ak/+= (az/field report pair-impulse)
                                  (az/field (resolve-velocity-contact! assembly row) normal-impulse)))))))))))))
    report))

(az/defn row-coefficient
  :- p/Vec3
  [[row VelocityContact] [body :usize] [node :usize]]
  (let [^{:var :f64} weight 0.0]
    (dotimes [i 4]
      (let [entry (az/index (az/field row entries) i)]
        (when (and (ak/== (az/field entry body) body) (ak/== (az/field entry node) node))
          (ak/+= weight (az/field entry weight)))))
    (p/scale (az/field row normal) weight)))

(az/defn equivalent-rows?
  "Merge only stencils whose vector coefficients agree within 1e-12.
  All feature constraints and the full CCD path are rechecked after solving."
  :- :bool
  [[left VelocityContact] [right VelocityContact]]
  (dotimes [side 2]
    (let [row (if (ak/== side 0) left right)]
      (dotimes [i 4]
        (let [entry (az/index (az/field row entries) i)
              a (row-coefficient left (az/field entry body) (az/field entry node))
              b (row-coefficient right (az/field entry body) (az/field entry node))
              difference (p/add a (p/scale b -1.0))]
          (when (> (p/dot difference difference) 1.0e-24) (ak/return false))))))
  true)

(az/defn append-contact-row!
  "Return the new count, or 129 on capacity exhaustion."
  :- :usize
  [[workspace [:* DriftWorkspace]] [count :usize] [row VelocityContact]]
  (dotimes [i count]
    (when (equivalent-rows? (az/index (az/field workspace rows) i) row) (ak/return count)))
  (when (ak/== count 128) (ak/return 129))
  (set! (az/index (az/field workspace rows) count) row)
  (+ count 1))

(az/defn resolve-proximity-block!
  :- BlockReport
  [[assembly [:* Assembly]] [workspace [:* DriftWorkspace]] [clearance :f64]]
  (let [bodies (az/field assembly bodies)
        ^{:var :usize} count 0
        ^:var report (BlockReport {:completed false :status 1 :contacts 0 :pivots 0
                                   :ground-impulse 0.0 :pair-impulse 0.0 :closing-speed 0.0})
        empty (ContactWeight {:body 0 :node 0 :weight 0.0})]
    (dotimes [a (az/field bodies len)]
      (dotimes [b (az/field bodies len)]
        (when (< a b)
          (let [pairs (contact/near-face-pairs! (az/field (az/index bodies a) surface)
                                               (az/field (az/index bodies b) surface)
                                               clearance (az/field workspace pairs))]
            (when (ak/!= (az/field pairs status) 0) (ak/return report))
            (dotimes [pair (az/field pairs count)]
              (let [indices (az/index (az/field workspace pairs) pair)]
                (dotimes [feature 15]
                  (let [constraint (feature-contact assembly a b (az/index indices 0) (az/index indices 1) feature)]
                    (when (ak/! (az/field constraint valid))
                      (set! (az/field report status) 2)
                      (ak/return report))
                    (when (<= (az/field constraint distance) clearance)
                      (set! count (append-contact-row! workspace count (az/field constraint row)))
                      (when (> count 128) (ak/return report)))))))))))
    (dotimes [body (az/field bodies len)]
      (let [state (az/field (az/index bodies body) state)]
        (when (az/field state floor)
          (dotimes [node (az/field (az/field state masses) len)]
            (when (<= (az/field (dynamics/position state node) y) 0.0)
              (set! count (append-contact-row! workspace count
                                               (VelocityContact {:entries [(ContactWeight {:body body :node node :weight 1.0})
                                                                            empty empty empty]
                                                                  :normal (p/v 0.0 1.0 0.0)
                                                                  :friction (az/field state friction) :ground true})))
              (when (> count 128) (ak/return report)))))))
    (solve-contact-rows! assembly (az/field (az/field workspace rows) ptr) count)))

(az/defn continuous-drift!
  "Resolve CCD-reported imminent features with current-geometry impulses, and
  recheck every proposed path. Commit only a clear path; failures restore velocity
  exactly and never change positions. Requires initially disjoint free solids.
  Status 1 requests a shorter step, 2 ambiguous geometry, 3 pass limit, 4 CCD
  failure, 5 invalid input, 6 no dissipative response available at this step size."
  :- DriftReport
  [[assembly [:* Assembly]] [workspace [:* DriftWorkspace]] [duration :f64]
   [clearance :f64] [maximum-passes :u32] [maximum-work :u32] [maximum-iterations :u32]]
  (let [bodies (az/field assembly bodies)
        ^{:var :f64} query-tolerance 1.0e-8
        ^:var report (DriftReport {:completed false :status 5 :passes 0 :queries 0
                                   :safe-fraction 0.5 :ground-impulse 0.0 :pair-impulse 0.0
                                      :distance 0.0 :normal-speed 0.0 :toi 0.0 :achieved-tolerance 0.0})]
    (when (or (ak/! (math/isFinite duration)) (<= duration 0.0)
              (ak/! (math/isFinite clearance)) (<= clearance 1.0e-12) (> clearance 0.01)
              (ak/== maximum-passes 0) (> maximum-passes 128)
              (ak/== maximum-work 0) (> maximum-work 10000000)
              (ak/== maximum-iterations 0) (> maximum-iterations 1000000)
              (ak/!= (az/field bodies len) (az/field (az/field workspace bodies) len)))
      (ak/return report))
    (dotimes [i (az/field bodies len)]
      (let [state (az/field (az/index bodies i) state)
            scratch (az/index (az/field workspace bodies) i)]
        (dotimes [node (az/field (az/field state masses) len)]
          (when (and (az/field state floor) (< (az/field (dynamics/position state node) y) 0.0))
            (ak/return report))
          (set! (az/index (az/field scratch velocity) node) (dynamics/particle-velocity state node)))))
    (set! (az/field report status) 0)
    (dotimes [pass maximum-passes]
      (az/set-many!
        (az/field report passes) (ak/intCast (+ pass 1))
        (az/field report ground-impulse)
        (+ (az/field report ground-impulse) (stage-drift! assembly workspace duration)))
      (let [proximity (proximity-pass! assembly workspace clearance)]
        (when (ak/!= (az/field proximity status) 0)
          (set! (az/field report status) (if (ak/== (az/field proximity status) 1) 4 2))
          (ak/break))
        (ak/+= (az/field report pair-impulse) (az/field proximity pair-impulse))
        (when (> (az/field proximity closing-speed) velocity-tolerance)
          (when (>= pass 4)
            (let [block (resolve-proximity-block! assembly workspace clearance)]
              (when (ak/! (az/field block completed))
                (set! (az/field report status) 3)
                (ak/break))
              (ak/+= (az/field report pair-impulse) (az/field block pair-impulse))
              (ak/+= (az/field report ground-impulse) (az/field block ground-impulse))))
          (ak/continue)))
      (dotimes [i (az/field bodies len)]
        (when (ak/! (contact/refit-motion! (az/field (az/index (az/field workspace bodies) i) motion)))
          (set! (az/field report status) 4)))
      (when (ak/!= (az/field report status) 0) (ak/break))
      (let [^{:var :f64} earliest 1.0e300
            ^{:var :usize} left 0
            ^{:var :usize} right 0
            ^:var selected (contact/MeshSweepResult {:status 0 :visits 0 :queries 0 :candidates 0
                                                    :face-a 0 :face-b 0 :feature 0 :reserved 0
                                                    :time 1.0e300 :achieved-tolerance 0.0})]
        (dotimes [a (az/field bodies len)]
          (dotimes [b (az/field bodies len)]
            (when (and (< a b) (ak/== (az/field report status) 0))
              (let [result (contact/sweep-surfaces (az/field (az/index (az/field workspace bodies) a) motion)
                                                  (az/field (az/index (az/field workspace bodies) b) motion)
                                                  0.0 query-tolerance maximum-work maximum-iterations)]
                (ak/+= (az/field report queries) (az/field result queries))
                (when (> (az/field result status) 1) (set! (az/field report status) 4))
                (when (and (ak/== (az/field result status) 1) (< (az/field result time) earliest))
                  (az/set-many! earliest (az/field result time) left a right b selected result))))))
        (when (ak/!= (az/field report status) 0) (ak/break))
        (when (> earliest 1.0)
          (dotimes [i (az/field bodies len)]
            (let [body (az/index bodies i)
                  mesh (az/field (az/field body state) mesh)
                  scratch (az/index (az/field workspace bodies) i)]
              (dotimes [index (az/field (az/field mesh displacement) len)]
                (set! (az/index (az/field mesh displacement) index) (az/index (az/field scratch proposed) index)))
              (synchronize! body)))
          (set! (az/field report completed) true)
          (ak/return report))
        (let [constraint (feature-contact assembly left right (az/field selected face-a)
                                           (az/field selected face-b) (az/field selected feature))]
          (az/set-many!
            (az/field report distance) (az/field constraint distance)
            (az/field report normal-speed)
            (p/dot (az/field (az/field constraint row) normal)
                   (contact-row-velocity assembly (az/field constraint row)))
            (az/field report toi) earliest
            (az/field report achieved-tolerance) (az/field selected achieved-tolerance))
          (when (ak/! (az/field constraint valid))
            (set! (az/field report status) 2)
            (ak/break))
          (when (> (az/field constraint distance) clearance)
            (az/set-many!
              (az/field report status) 1
              (az/field report safe-fraction) (ak/min 0.5 (ak/max 0.1 (* 0.8 earliest))))
            (ak/break))
          ;; A conservative spatial tolerance can report a stationary positive
          ;; gap as time zero. Refine the entire query before applying another
          ;; roundoff-sized impulse; a possible result never authorizes a drift.
          (when (and (>= (az/field report normal-speed) (- velocity-tolerance))
                     (> query-tolerance 1.0e-14))
            (set! query-tolerance
                  (ak/max 1.0e-14 (ak/min (* 0.01 query-tolerance)
                                           (* 0.01 (az/field constraint distance)))))
            (ak/continue))
          (let [impulse (resolve-velocity-contact! assembly (az/field constraint row))]
            (when (<= (az/field impulse normal-impulse) 0.0)
              (set! (az/field report status) 6)
              (ak/break))
            (ak/+= (az/field report pair-impulse) (az/field impulse normal-impulse))))))
    (when (ak/== (az/field report status) 0) (set! (az/field report status) 3))
    (dotimes [i (az/field bodies len)]
      (let [state (az/field (az/index bodies i) state)
            scratch (az/index (az/field workspace bodies) i)]
        (dotimes [node (az/field (az/field state masses) len)]
          (set! (az/index (az/field state velocities) node) (az/index (az/field scratch velocity) node)))))
    (az/set-many! (az/field report ground-impulse) 0.0 (az/field report pair-impulse) 0.0)
    report))

(az/defn contact-closing-speed
  :- :f64
  [[assembly [:* Assembly]]]
  (let [^:var speed (az/field (residual assembly) closing-speed)]
    (dotimes [body (az/field (az/field assembly bodies) len)]
      (let [state (az/field (az/index (az/field assembly bodies) body) state)]
        (when (az/field state floor)
          (dotimes [node (az/field (az/field state masses) len)]
            (when (<= (az/field (dynamics/position state node) y) 0.0)
              (set! speed (ak/max speed (- (az/field (dynamics/particle-velocity state node) y)))))))))
    speed))

(az/defn resolve-normal-block!
  "Resolve difficult velocity contacts together, then spend each new normal
  impulse's Coulomb budget in dissipative tangential corrections. Geometry stays
  fixed. Caller owns rollback: failure may leave partial velocity corrections.
  Status 1 is capacity, 3 is block limit, and 10+ is a normal-system failure."
  :- BlockReport
  [[assembly [:* Assembly]]]
  (let [^{:var [:array 128 VelocityContact]} rows ak/undefined
        ^{:var :usize} count 0
        ^:var report (BlockReport {:completed false :status 0 :contacts 0 :pivots 0
                                   :ground-impulse 0.0 :pair-impulse 0.0
                                   :closing-speed (contact-closing-speed assembly)})
        empty (ContactWeight {:body 0 :node 0 :weight 0.0})
        bodies (az/field assembly bodies)]
    (when (<= (az/field report closing-speed) velocity-tolerance)
      (set! (az/field report completed) true)
      (ak/return report))
    (dotimes [a (az/field bodies len)]
      (dotimes [b (az/field bodies len)]
        (when (ak/!= a b)
          (let [source (az/index bodies a)
                target (az/index bodies b)
                source-surface (az/field source surface)
                target-surface (az/field target surface)]
            (dotimes [node (az/field (az/field source-surface points) len)]
              (when (< (az/index (az/field source-surface offsets) node)
                       (az/index (az/field source-surface offsets) (+ node 1)))
                (let [point (dynamics/position (az/field source state) node)]
                  (when (candidate? target-surface point)
                    (let [closest (contact/closest-point target-surface point)]
                      (when (<= (az/field closest signed-distance) position-tolerance)
                        (when (ak/== count 128)
                          (set! (az/field report status) 1)
                          (ak/return report))
                        (let [face (az/index (az/field target-surface faces) (az/field closest face))
                              ^:var row (VelocityContact
                                         {:entries [(ContactWeight {:body a :node node :weight 1.0}) empty empty empty]
                                          :normal (az/field closest normal)
                                          :friction (ak/sqrt (* (az/field (az/field source state) friction)
                                                               (az/field (az/field target state) friction)))
                                          :ground false})]
                          (dotimes [local 3]
                            (set! (az/index (az/field row entries) (+ local 1))
                                  (ContactWeight {:body b :node (az/index face local)
                                                  :weight (- (fem/component (az/field closest weights) local))})))
                          (set! (az/index rows count) row)
                          (ak/+= count 1))))))))))))
    ;; Independent ground nodes were already handled by ground-pass!. Only
    ;; nodes touched by these coupled impulses need additional matrix rows.
    (let [pair-count count]
      (dotimes [i pair-count]
        (dotimes [local 4]
          (let [entry (az/index (az/field (az/index rows i) entries) local)
                state (az/field (az/index bodies (az/field entry body)) state)
                ^{:var :bool} present false]
            (when (and (ak/!= (az/field entry weight) 0.0) (az/field state floor)
                       (<= (az/field (dynamics/position state (az/field entry node)) y) 0.0))
              (dotimes [offset (- count pair-count)]
                (let [other (az/index (az/field (az/index rows (+ pair-count offset)) entries) 0)]
                  (when (and (ak/== (az/field other body) (az/field entry body))
                             (ak/== (az/field other node) (az/field entry node)))
                    (set! present true))))
              (when (ak/! present)
                (when (ak/== count 128)
                  (set! (az/field report status) 1)
                  (ak/return report))
                (set! (az/index rows count)
                      (VelocityContact {:entries [(ContactWeight {:body (az/field entry body)
                                                                  :node (az/field entry node) :weight 1.0})
                                                   empty empty empty]
                                        :normal (p/v 0.0 1.0 0.0)
                                        :friction (az/field state friction) :ground true}))
                (ak/+= count 1)))))))
    (set! (az/field report contacts) count)
    (when (ak/== count 0)
      (set! (az/field report status) 1)
      (ak/return report))
    (solve-contact-rows! assembly (ak/& (az/index rows 0)) count)))

(az/defstruct ExplicitTask
  [[:assembly [:* Assembly]] [:target :f64] [:maximum-step :f64]
   [:observation dynamics/Observables] [:report Report]
   [:next-step :f64] [:minimum-step :f64] [:attempts :u64] [:status :u32]])

(az/defstruct ExplicitProgress {:layout :extern}
  [[:status :u32] [:attempts :u64] [:next-step :f64] [:minimum-step :f64]])

(az/defn explicit-task
  :- ExplicitTask
  [[assembly [:* Assembly]] [duration :f64] [maximum-step :f64]]
  (let [observation (observe! assembly)
        time (az/field assembly time)
        valid (and (math/isFinite duration) (>= duration 0.0)
                   (math/isFinite maximum-step) (> maximum-step 0.0)
                   (math/isFinite (+ time duration)))]
    (ExplicitTask
     {:assembly assembly :target (+ time duration) :maximum-step maximum-step
      :observation observation :next-step 0.0 :minimum-step 1.0e300 :attempts 0
      :status (if valid 0 2)
      :report (Report {:completed false :substeps 0 :rejected 0 :time time
                       :minimum-jacobian (az/field observation minimum-jacobian)
                       :maximum-penetration 0.0 :ground-impulse 0.0 :pair-impulse 0.0})})))

(az/defn create-explicit-task!
  :- [:* ExplicitTask]
  [[assembly [:* Assembly]] [duration :f64] [maximum-step :f64]]
  (let [task (catch ((az/field heap/page_allocator create) ExplicitTask)
               (debug/panic "Unable to allocate explicit FEM advance task" []))]
    (set! (az/deref task) (explicit-task assembly duration maximum-step))
    task))

(az/defn destroy-explicit-task!
  :- :void [[task [:* ExplicitTask]]]
  ((az/field heap/page_allocator destroy) task))

(az/defn explicit-progress
  :- ExplicitProgress [[task [:* ExplicitTask]]]
  (ExplicitProgress {:status (az/field task status) :attempts (az/field task attempts)
                     :next-step (az/field task next-step) :minimum-step (az/field task minimum-step)}))

(az/defn advance-explicit-batch!
  "Bound native Verlet work by attempted steps, including retries. Status 0 yields
  at the last accepted state; 1 completes; 2 input, 3 state, 4 step underflow,
  and 5 contact solve indicate failure. A task retains retries across batches."
  :- Report
  [[task [:* ExplicitTask]] [maximum-attempts :u32]]
  (let [assembly (az/field task assembly)
        bodies (az/field assembly bodies)
        target (az/field task target)
        maximum-step (az/field task maximum-step)
        ^:var observation (az/field task observation)
        ^:var report (az/field task report)
        ^{:var :u32} attempted 0]
    (defer (az/set-many!
             (az/field task observation) observation
             (az/field task report) report))
    (when (ak/!= (az/field task status) 0) (ak/return report))
    (while (< (az/field assembly time) target)
      (when (ak/! (valid? observation))
        (set! (az/field task status) 3)
        (ak/return report))
      (let [remaining (- target (az/field assembly time))
            ^:var h (if (> (az/field task next-step) 0.0)
                      (az/field task next-step)
                      (ak/min remaining (ak/min maximum-step
                        (/ 0.35 (ak/sqrt (ak/max 1.0 (az/field observation frequency-squared-bound)))))))
            ^{:var :bool} accepted false
            ^{:var :f64} ground-impulse 0.0
            ^{:var :f64} pair-impulse 0.0
            ^:var contact-residual (Residual {:penetration 0.0 :closing-speed 0.0})]
        (when (<= remaining (* 3.552713678800501e-15 (ak/max 1.0 (ak/abs target))))
          (set! (az/field assembly time) target)
          (ak/break))
        (checkpoint! assembly false)
        (while (ak/! accepted)
          ;; Every yield is at an accepted state: rejected trials were restored.
          ;; Keep the reduced h so a new host batch does not repeat failed trials.
          (set! (az/field task next-step) h)
          (when (>= attempted maximum-attempts) (ak/return report))
          (ak/+= attempted 1)
          (ak/+= (az/field task attempts) 1)
          (when (or (<= h 1.0e-12) (ak/== (+ (az/field assembly time) h) (az/field assembly time)))
            (set! (az/field task status) 4)
            (ak/return report))
          (dotimes [index (az/field bodies len)]
            (let [body (az/index bodies index)]
              (dynamics/kick! (az/field body state) (* 0.5 h))
              (dynamics/drift! (az/field body state) h)))
          (az/set-many!
            ground-impulse (ground-pass! assembly true)
            pair-impulse 0.0)
          (dotimes [_ 48]
            (ak/+= pair-impulse (pair-pass! assembly true))
            (ak/+= ground-impulse (ground-pass! assembly true))
            (set! contact-residual (residual assembly))
            (when (and (<= (az/field contact-residual penetration) position-tolerance)
                       (<= (az/field contact-residual closing-speed) velocity-tolerance))
              (ak/break)))
          (set! observation (observe! assembly))
          (set! accepted (and (valid? observation)
                              (<= (* h h (az/field observation frequency-squared-bound)) 0.25)
                              (<= (az/field contact-residual penetration) position-tolerance)))
          (when accepted
            (dotimes [index (az/field bodies len)]
              (dynamics/kick! (az/field (az/index bodies index) state) (* 0.5 h)))
            (dotimes [_ 48]
              (ak/+= pair-impulse (pair-pass! assembly false))
              (ak/+= ground-impulse (ground-pass! assembly false))
              (set! contact-residual (residual assembly))
              (when (<= (az/field contact-residual closing-speed) velocity-tolerance) (ak/break)))
            (when (> (az/field contact-residual closing-speed) velocity-tolerance)
              (let [block (resolve-normal-block! assembly)]
                (when (ak/! (az/field block completed))
                  (checkpoint! assembly true)
                  (set! (az/field task status) 5)
                  (ak/return report))
                (ak/+= pair-impulse (az/field block pair-impulse))
                (ak/+= ground-impulse (az/field block ground-impulse))
                (set! contact-residual (residual assembly))))
            (set! observation (observe! assembly))
            (set! accepted (and (valid? observation)
                                (<= (az/field contact-residual closing-speed) velocity-tolerance))))
          (when (ak/! accepted)
            (checkpoint! assembly true)
            (az/set-many!
              observation (observe! assembly)
              h (* 0.5 h)
              (az/field report rejected) (+ (az/field report rejected) 1))))
        (az/set-many!
          (az/field task next-step) 0.0
          (az/field task minimum-step) (ak/min (az/field task minimum-step) h)
          (az/field assembly time) (if (ak/== h remaining) target (+ (az/field assembly time) h))
          (az/field report substeps) (+ (az/field report substeps) 1)
          (az/field report minimum-jacobian)
          (ak/min (az/field report minimum-jacobian) (az/field observation minimum-jacobian))
          (az/field report maximum-penetration)
          (ak/max (az/field report maximum-penetration) (az/field contact-residual penetration))
          (az/field report ground-impulse) (+ (az/field report ground-impulse) ground-impulse)
          (az/field report pair-impulse) (+ (az/field report pair-impulse) pair-impulse)
          (az/field report time) (az/field assembly time))
        (dotimes [index (az/field bodies len)]
          (set! (az/field (az/field (az/index bodies index) state) time) (az/field assembly time)))))
    (dotimes [index (az/field bodies len)]
      (set! (az/field (az/field (az/index bodies index) state) time) (az/field assembly time)))
    (az/set-many!
      (az/field task status) 1
      (az/field report completed) true
      (az/field report time) (az/field assembly time))
    report))

(az/defn advance!
  "Uninterrupted native compatibility entry point. Hosts needing cancellation
  own an ExplicitTask and use advance-explicit-batch!."
  :- Report
  [[assembly [:* Assembly]] [duration :f64] [maximum-step :f64]]
  (let [^:var task (explicit-task assembly duration maximum-step)]
    (while (ak/== (az/field task status) 0)
      (set! _ (advance-explicit-batch! (ak/& task) 4096)))
    (az/field task report)))

(az/defstruct ContinuousReport {:layout :extern}
  [[:completed :bool] [:substeps :u32] [:rejected :u32] [:time :f64]
   [:budget-exhausted :bool] [:attempts :u32] [:next-step :f64]
   [:minimum-jacobian :f64] [:maximum-penetration :f64]
   [:ground-impulse :f64] [:pair-impulse :f64]
   [:ccd-queries :u64] [:ccd-passes :u64] [:last-contact-status :u32] [:minimum-step :f64]
   [:last-distance :f64] [:last-normal-speed :f64] [:last-toi :f64] [:last-achieved-tolerance :f64]])

(az/defn ground-velocity!
  "Post-kick inelastic plane response. It never edits positions."
  :- :f64
  [[state [:* dynamics/Dynamics]]]
  (let [^{:var :f64} impulse 0.0]
    (when (az/field state floor)
      (dotimes [node (az/field (az/field state masses) len)]
        (when (<= (az/field (dynamics/position state node) y) 0.0)
          (let [velocity (dynamics/particle-velocity state node)
                change (ak/max 0.0 (- (az/field velocity y)))
                speed (p/length (p/v (az/field velocity x) 0.0 (az/field velocity z)))
                factor (if (> speed 0.0)
                         (ak/max 0.0 (- 1.0 (/ (* (az/field state friction) change) speed))) 1.0)]
            (set! (az/index (az/field state velocities) node)
                  (p/v (* factor (az/field velocity x)) (ak/max 0.0 (az/field velocity y))
                       (* factor (az/field velocity z))))
            (ak/+= impulse (* change (az/index (az/field state masses) node)))))))
    impulse))

(az/defn advance-continuous-bounded!
  "FEM Verlet with CCD-checked drifts and rollback. No pair position repair.
  Clearance activates imminent-contact impulses; it is a numerical parameter,
  not a calibrated material thickness. Initial disjointness is a precondition.
  Attempt-budget exhaustion returns the last accepted state for inspection/resume."
  :- ContinuousReport
  [[assembly [:* Assembly]] [duration :f64] [maximum-step :f64] [clearance :f64] [maximum-attempts :u32]]
  (let [bodies (az/field assembly bodies)
        target (+ (az/field assembly time) duration)
        ^:var observation (observe! assembly)
        ^:var report (ContinuousReport
                      {:completed false :substeps 0 :rejected 0 :time (az/field assembly time)
                       :budget-exhausted false :attempts 0 :next-step maximum-step
                       :minimum-jacobian (az/field observation minimum-jacobian)
                       :maximum-penetration 0.0 :ground-impulse 0.0 :pair-impulse 0.0
                       :ccd-queries 0 :ccd-passes 0 :last-contact-status 0 :minimum-step 1.0e300
                       :last-distance 0.0 :last-normal-speed 0.0 :last-toi 0.0 :last-achieved-tolerance 0.0})
        workspace (create-drift-workspace! assembly)]
    (defer (destroy-drift-workspace! workspace))
    (when (or (ak/! (math/isFinite duration)) (< duration 0.0)
              (ak/! (math/isFinite target)) (ak/! (math/isFinite maximum-step)) (<= maximum-step 0.0)
              (ak/! (math/isFinite clearance)) (<= clearance 1.0e-12) (> clearance 0.01))
      (set! (az/field report last-contact-status) 5)
      (ak/return report))
    (while (< (az/field assembly time) target)
      (when (ak/! (valid? observation)) (ak/return report))
      (let [remaining (- target (az/field assembly time))
            ^:var h (ak/min remaining (ak/min maximum-step
                                             (/ 0.35 (ak/sqrt (ak/max 1.0 (az/field observation frequency-squared-bound))))))
            ^{:var :bool} accepted false
            ^{:var :f64} ground-impulse 0.0
            ^:var drift (DriftReport {:completed false :status 0 :passes 0 :queries 0
                                      :safe-fraction 0.5 :ground-impulse 0.0 :pair-impulse 0.0
                                      :distance 0.0 :normal-speed 0.0 :toi 0.0 :achieved-tolerance 0.0})
            ^{:var :f64} penetration 0.0]
        (when (<= remaining (* 3.552713678800501e-15 (ak/max 1.0 (ak/abs target))))
          (set! (az/field assembly time) target)
          (ak/break))
        (checkpoint! assembly false)
        (while (ak/! accepted)
          (when (>= (az/field report attempts) maximum-attempts)
            (az/set-many! (az/field report budget-exhausted) true
                          (az/field report next-step) h)
            (ak/return report))
          (when (or (<= h 1.0e-12) (ak/== (+ (az/field assembly time) h) (az/field assembly time)))
            (ak/return report))
          (ak/+= (az/field report attempts) 1)
          (dotimes [i (az/field bodies len)]
            (dynamics/kick! (az/field (az/index bodies i) state) (* 0.5 h)))
          (set! drift (continuous-drift! assembly workspace h clearance 128 100000 1000000))
          (az/set-many!
            (az/field report ccd-queries) (+ (az/field report ccd-queries) (az/field drift queries))
            (az/field report ccd-passes) (+ (az/field report ccd-passes) (az/field drift passes))
            (az/field report last-contact-status) (az/field drift status)
            (az/field report last-distance) (az/field drift distance)
            (az/field report last-normal-speed) (az/field drift normal-speed)
            (az/field report last-toi) (az/field drift toi)
            (az/field report last-achieved-tolerance) (az/field drift achieved-tolerance)
            ground-impulse (az/field drift ground-impulse))
          (when (az/field drift completed)
            (set! observation (observe! assembly))
            (set! accepted (and (valid? observation)
                                (<= (* h h (az/field observation frequency-squared-bound)) 0.25)))
            (when accepted
              (dotimes [i (az/field bodies len)]
                (let [state (az/field (az/index bodies i) state)]
                  (dynamics/kick! state (* 0.5 h))
                  (ak/+= ground-impulse (ground-velocity! state))))
              (az/set-many!
                observation (observe! assembly)
                penetration (az/field (residual assembly) penetration)
                accepted (and (valid? observation) (<= penetration position-tolerance)))))
          (when (ak/! accepted)
            (checkpoint! assembly true)
            (az/set-many!
              observation (observe! assembly)
              h (* h (if (az/field drift completed) 0.5 (az/field drift safe-fraction)))
              (az/field report rejected) (+ (az/field report rejected) 1))))
        (az/set-many!
          (az/field assembly time) (if (ak/== h remaining) target (+ (az/field assembly time) h))
          (az/field report substeps) (+ (az/field report substeps) 1)
          (az/field report minimum-jacobian)
          (ak/min (az/field report minimum-jacobian) (az/field observation minimum-jacobian))
          (az/field report maximum-penetration) (ak/max (az/field report maximum-penetration) penetration)
          (az/field report ground-impulse) (+ (az/field report ground-impulse) ground-impulse)
          (az/field report pair-impulse) (+ (az/field report pair-impulse) (az/field drift pair-impulse))
          (az/field report minimum-step) (ak/min (az/field report minimum-step) h)
          (az/field report time) (az/field assembly time))
        (dotimes [i (az/field bodies len)]
          (set! (az/field (az/field (az/index bodies i) state) time) (az/field assembly time)))))
    (dotimes [i (az/field bodies len)]
      (set! (az/field (az/field (az/index bodies i) state) time) (az/field assembly time)))
    (az/set-many! (az/field report completed) true (az/field report time) (az/field assembly time))
    report))


(az/defn advance-continuous!
  "Unbudgeted convenience entry point. Use the bounded form for job scheduling."
  :- ContinuousReport
  [[assembly [:* Assembly]] [duration :f64] [maximum-step :f64] [clearance :f64]]
  (advance-continuous-bounded! assembly duration maximum-step clearance 4294967295))
