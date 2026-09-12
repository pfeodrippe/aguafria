(ns field-lab.ball-fem
  "Continuum FEM bridge for the existing 43-node Flecs ball/cache format.
  Hyperelastic material forces plus nodal ground and convex pair constraints."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.soft-body :as soft]
            [field-lab.soft-mesh :as mesh]
            [field-lab.fem :as fem]
            [field-lab.hyperelastic :as elastic]
            [field-lab.nonlinear-fem :as dynamics]))

(az/defconst poisson :f64 0.4)

(az/defstruct Result {:layout :extern}
  [[:sample soft/Sample] [:completed :bool] [:substeps :u32]
   [:minimum-jacobian :f64]])

(az/defn reference-element
  :- fem/Element
  [[index :usize] [radius :f64]]
  (let [face (az/index mesh/faces index)
        b (az/index face 0)
        c (az/index face 1)
        d (az/index face 2)
        pb (p/scale (az/index mesh/points b) radius)
        pc (p/scale (az/index mesh/points c) radius)
        pd (p/scale (az/index mesh/points d) radius)
        volume (* radius radius radius (az/index mesh/rest-volumes index))
        inverse (/ 1.0 (* 6.0 volume))
        gb (p/scale (p/cross pc pd) inverse)
        gc (p/scale (p/cross pd pb) inverse)
        gd (p/scale (p/cross pb pc) inverse)]
    (fem/Element {:nodes [0 (ak/intCast b) (ak/intCast c) (ak/intCast d)]
                  :gradients [(p/scale (p/add (p/add gb gc) gd) -1.0) gb gc gd]
                  :volume volume})))

(az/defn elastic-energy
  :- :f64
  [[body soft/Body] [config p/Config] [young :f64]]
  (let [parameters (elastic/material young poisson)
        origin (az/index (az/field body positions) 0)
        ^{:var :f64} total 0.0]
    (dotimes [index soft/face-count]
      (let [element (reference-element index (az/field config radius))
            ^:var gradient (elastic/zero)]
        (dotimes [j 3]
          (let [local (+ j 1)
                node (az/index (az/field element nodes) local)
                position (az/index (az/field body positions) node)
                basis (az/index (az/field element gradients) local)]
            (set! gradient (elastic/add gradient (elastic/outer (p/add position (p/scale origin -1.0)) basis)))))
        (ak/+= total (* (az/field element volume)
                         (az/field (elastic/evaluate gradient parameters) energy-density)))))
    total))

(az/defn energy
  :- :f64
  [[body soft/Body] [config p/Config] [young :f64]]
  (+ (elastic-energy body config young) (soft/kinetic body config)
     (* (az/field config mass) (az/field config gravity) (az/field (soft/center body) y))))

(az/defn read-body
  :- soft/Body
  [[state [:* dynamics/Dynamics]]]
  (let [^{:var soft/Body} body ak/undefined]
    (dotimes [node soft/particle-count]
      (az/set-many!
        (az/index (az/field body positions) node) (dynamics/position state node)
        (az/index (az/field body velocities) node) (dynamics/particle-velocity state node)))
    body))

(az/defn correct-contact!
  :- :void
  [[state [:* dynamics/Dynamics]] [before soft/Body] [after soft/Body] [dt :f64]]
  (dotimes [node soft/particle-count]
    (let [old (az/index (az/field before positions) node)
          point (az/index (az/field after positions) node)
          correction (p/scale (p/add point (p/scale old -1.0)) (/ 1.0 dt))
          velocity (p/add (az/index (az/field before velocities) node) correction)]
      (dynamics/set-particle! state node point velocity))))

(az/defn advance
  :- Result
  [[input soft/Sample] [config p/Config] [active :u32] [young :f64] [dt :f64]]
  (let [^{:var [:array 3 [:* fem/Model]]} models ak/undefined
        ^{:var [:array 3 [:* dynamics/Dynamics]]} states ak/undefined
        ^:var result (Result {:sample input :completed false :substeps 0 :minimum-jacobian 1.0})
        radius (az/field config radius)
        density (/ (az/field config mass) (* mesh/unit-volume radius radius radius))
        ^{:var :f64} time 0.0]
    (dotimes [body active]
      (let [model (fem/create! soft/particle-count soft/face-count young poisson)]
        (set! (az/index models body) model)
        (dotimes [node soft/particle-count]
          (set! (az/index (az/field model positions) node) (p/scale (az/index mesh/points node) radius)))
        (dotimes [index soft/face-count]
          (set! (az/index (az/field model elements) index) (reference-element index radius)))
        (let [state (dynamics/create! model density young poisson)
              initial (az/index (az/field input bodies) body)]
          (set! (az/index states body) state)
          (dynamics/configure! state (p/v 0.0 (- (az/field config gravity)) 0.0) true (az/field config friction))
          (dotimes [node soft/particle-count]
            (dynamics/set-particle! state node (az/index (az/field initial positions) node)
                                     (az/index (az/field initial velocities) node))))))
    (defer
      (dotimes [body active]
        (dynamics/destroy! (az/index states body))
        (fem/destroy! (az/index models body))))
    (while (< time dt)
      (let [^{:var :f64} h (ak/min (- dt time) 0.0001)
            ^{:var :bool} accepted false]
        (dotimes [body active]
          (let [state (az/index states body)
                observation (dynamics/evaluate! state)]
            (when (or (<= (az/field observation minimum-jacobian) 0.05)
                      (ak/! (math/isFinite (az/field observation elastic-energy))))
              (ak/return result))
            (set! h (ak/min h (/ 0.35 (ak/sqrt (ak/max 1.0 (az/field observation frequency-squared-bound))))))
            (dynamics/checkpoint! state false)))
        (while (ak/! accepted)
          (when (< h 1.0e-12) (ak/return result))
          (let [^:var predicted (az/field result sample)]
            (dotimes [body active]
              (let [state (az/index states body)]
                (dynamics/kick! state (* 0.5 h))
                (dynamics/drift! state h)
                (set! _ (dynamics/ground-contact! state))
                (set! (az/index (az/field predicted bodies) body) (read-body state))))
            (let [unconstrained predicted]
              ;; Pair constraints act on the actual deformed convex surfaces.
              ;; This coarse bridge still lacks edge-edge CCD and self-contact.
              (dotimes [_ 6]
                (dotimes [a active]
                  (dotimes [b active]
                    (when (< a b)
                      (let [pa (ak/& (az/index (az/field predicted bodies) a))
                            pb (ak/& (az/index (az/field predicted bodies) b))]
                        (when (soft/bounds-overlap? (az/deref pa) (az/deref pb))
                          (dotimes [node 42]
                            (soft/project-vertex! pa pb (ak/intCast (+ node 1)) config)
                            (soft/project-vertex! pb pa (ak/intCast (+ node 1)) config))))))))
              (set! accepted true)
              (dotimes [body active]
                (let [state (az/index states body)]
                  (correct-contact! state (az/index (az/field unconstrained bodies) body)
                                    (az/index (az/field predicted bodies) body) h)
                  (set! _ (dynamics/ground-contact! state))
                  (let [observation (dynamics/evaluate! state)]
                    (when (or (<= (az/field observation minimum-jacobian) 0.05)
                              (ak/! (math/isFinite (az/field observation elastic-energy)))
                              (ak/! (math/isFinite (az/field observation frequency-squared-bound)))
                              (> (* h h (az/field observation frequency-squared-bound)) 0.25))
                      (set! accepted false)))))))
          (if accepted
            (dotimes [body active]
              (let [state (az/index states body)]
                (dynamics/kick! state (* 0.5 h))
                (set! _ (dynamics/ground-contact! state))
                (set! (az/index (az/field (az/field result sample) bodies) body) (read-body state))
                (let [observation (dynamics/evaluate! state)]
                  (set! (az/field result minimum-jacobian)
                        (ak/min (az/field result minimum-jacobian) (az/field observation minimum-jacobian))))))
            (do
              (dotimes [body active]
                (let [state (az/index states body)]
                  (dynamics/checkpoint! state true)
                  (set! _ (dynamics/evaluate! state))))
              (set! h (* 0.5 h)))))
        (az/set-many!
          time (+ time h)
          (az/field result substeps) (+ (az/field result substeps) 1))
        (when (<= (- dt time) 3.552713678800501e-15)
          (set! time dt))))
    (set! (az/field result completed) true)
    result))
