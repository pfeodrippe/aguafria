(ns field-lab.coupled-fem
  "Joint explicit FEM stepping with discrete vertex/surface contact.
  Private owned jobs only. This is not continuous collision detection."
  (:require [aguafria.zig :as az]
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
    (dynamics/set-particle! state node point velocity)
    (when move (contact/move-point! (az/field body surface) node point))))

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
              normal-impulse (/ (ak/max 0.0 (- normal-speed)) inverse)
              friction (ak/sqrt (* (az/field (az/field a state) friction)
                                  (az/field (az/field b state) friction)))
              tangent-impulse (ak/min (* friction normal-impulse) (/ tangent-speed inverse))
              impulse (p/add (p/scale normal normal-impulse)
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
            (az/field result normal-impulse) normal-impulse
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

(az/defn advance!
  "A shared Verlet clock, contact sweeps and all-body rollback on rejected steps.
  Residual bounds cover sampled vertex/face contact, not edge crossings or CCD."
  :- Report
  [[assembly [:* Assembly]] [duration :f64] [maximum-step :f64]]
  (let [bodies (az/field assembly bodies)
        target (+ (az/field assembly time) duration)
        ^:var observation (observe! assembly)
        ^:var report (Report {:completed false :substeps 0 :rejected 0 :time (az/field assembly time)
                              :minimum-jacobian (az/field observation minimum-jacobian)
                              :maximum-penetration 0.0 :ground-impulse 0.0 :pair-impulse 0.0})]
    (while (< (az/field assembly time) target)
      (when (ak/! (valid? observation)) (ak/return report))
      (let [remaining (- target (az/field assembly time))
            ^:var h (ak/min remaining (ak/min maximum-step
                                             (/ 0.35 (ak/sqrt (ak/max 1.0 (az/field observation frequency-squared-bound))))))
            ^{:var :bool} accepted false
            ^{:var :f64} ground-impulse 0.0
            ^{:var :f64} pair-impulse 0.0
            ^:var contact-residual (Residual {:penetration 0.0 :closing-speed 0.0})]
        (when (<= remaining (* 3.552713678800501e-15 (ak/max 1.0 (ak/abs target))))
          (set! (az/field assembly time) target)
          (ak/break))
        (checkpoint! assembly false)
        (while (ak/! accepted)
          (when (or (<= h 1.0e-12) (ak/== (+ (az/field assembly time) h) (az/field assembly time)))
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
      (az/field report completed) true
      (az/field report time) (az/field assembly time))
    report))
