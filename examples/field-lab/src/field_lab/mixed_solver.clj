(ns field-lab.mixed-solver
  "Experimental bound-constrained backward Euler for the mixed tetrahedra.
  Native projected Newton/CG with a projected-gradient fallback. Bounds support
  fixed supports and axis-aligned frictionless planes, not general body contact."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.mixed-tetra :as mixed]))

(az/defstruct Problem
  [[:vertex-count :usize] [:elements [:slice mixed/Element]]
   [:initial [:slice p/Vec3]] [:prediction [:slice p/Vec3]] [:loads [:slice p/Vec3]]
   [:lower [:slice p/Vec3]] [:upper [:slice p/Vec3]]
   [:gravity p/Vec3] [:duration :f64] [:quadrature mixed/Quadrature]
   [:force-tolerance :f64] [:iteration-limit :u32] [:cg-limit :u32]])

(az/defstruct Workspace
  [[:x [:slice p/Vec3]] [:trial [:slice p/Vec3]]
   [:gradient [:slice p/Vec3]] [:trial-gradient [:slice p/Vec3]]
   [:direction [:slice p/Vec3]] [:residual [:slice p/Vec3]] [:search [:slice p/Vec3]]
   [:product [:slice p/Vec3]] [:diagonal [:slice p/Vec3]] [:free [:slice p/Vec3]]
   [:tangents [:slice mixed/QuadraticResponse]] [:trial-tangents [:slice mixed/QuadraticResponse]]])

(az/defstruct Report {:layout :extern}
  [[:status :u32] [:iterations :u32] [:cg-iterations :u32] [:line-trials :u32]
   [:gradient-fallbacks :u32] [:force-residual :f64] [:energy :f64]
   [:path-lower-bound :f64]])

(az/defn with-component p/Vec3 [[value p/Vec3] [axis :usize] [component :f64]]
  (p/v (if (ak/== axis 0) component (az/field value x))
       (if (ak/== axis 1) component (az/field value y))
       (if (ak/== axis 2) component (az/field value z))))

(az/defn inner :f64 [[left [:slice p/Vec3]] [right [:slice p/Vec3]]]
  (let [^:var result (ak/f64 0.0)]
    (dotimes [index (az/field left len)] (ak/+= result (p/dot (az/index left index) (az/index right index))))
    result))

(az/defn path-bound :f64
  "Lower det F bound over every element and straight coefficient interpolation.
  Nonpositive/nonfinite results are inconclusive. Connectivity must be validated
  by successful assembly before calling. No general collision detection here." [[problem [:* Problem]] [before [:slice p/Vec3]] [after [:slice p/Vec3]]]
  (let [^:var lower (ak/f64 (math/inf :f64))]
    (dotimes [cell (az/field (az/field problem elements) len)]
      (let [element (az/index (az/field problem elements) cell)
            ^:var start (mem/zeroes (az/type [:array 14 p/Vec3]))
            ^:var end (mem/zeroes (az/type [:array 14 p/Vec3]))]
        (dotimes [node (mixed/coefficient-count element)]
          (let [index (mixed/coefficient-index (az/field problem vertex-count) cell element node)]
            (az/set-many! (az/index start node) (az/index before index)
                          (az/index end node) (az/index after index))))
        (let [bound (mixed/element-path-bound element start end)]
          (when (or (ak/! (math/isFinite (az/field bound lower))) (<= (az/field bound lower) 0.0))
            (ak/return (- (math/inf :f64))))
          (set! lower (ak/min lower (az/field bound lower))))))
    lower))

(az/defn evaluate! mixed/StepResponse
  [[problem [:* Problem]] [x [:slice p/Vec3]] [gradient [:slice p/Vec3]] [tangents [:slice mixed/QuadraticResponse]]]
  (mixed/assemble-step! (az/field problem vertex-count) (az/field problem elements) x
    (az/field problem prediction) (az/field problem gravity) (az/field problem loads)
    (az/field problem duration) (az/field problem quadrature) gradient tangents))

(az/defn prepare-direction! :f64
  "Exact projected KKT residual in N. Equality supports have zero residual;
  a binding lower/upper bound suppresses only the correctly signed reaction." [[problem [:* Problem]] [workspace [:* Workspace]]]
  (let [^:var norm (ak/f64 0.0)
        diagonal (az/field workspace diagonal)]
    (dotimes [index (az/field diagonal len)] (set! (az/index diagonal index) (p/v 0.0 0.0 0.0)))
    (dotimes [cell (az/field (az/field problem elements) len)]
      (let [element (az/index (az/field problem elements) cell)
            response (az/index (az/field workspace tangents) cell)]
        (dotimes [node (mixed/coefficient-count element)]
          (let [index (mixed/coefficient-index (az/field problem vertex-count) cell element node)]
            (dotimes [axis 3]
              (let [row (+ (* 3 node) axis)
                    value (+ (fem/component (az/index diagonal index) axis)
                             (az/index (az/field response hessian) (+ (* 42 row) row)))]
                (set! (az/index diagonal index) (with-component (az/index diagonal index) axis value))))))))
    (dotimes [index (az/field diagonal len)]
      (dotimes [axis 3]
        (let [x (fem/component (az/index (az/field workspace x) index) axis)
              gradient (fem/component (az/index (az/field workspace gradient) index) axis)
              lower (fem/component (az/index (az/field problem lower) index) axis)
              upper (fem/component (az/index (az/field problem upper) index) axis)
              active (or (ak/== lower upper) (and (ak/== x lower) (> gradient 0.0))
                         (and (ak/== x upper) (< gradient 0.0)))
              force (if active 0.0 gradient)
              scale (ak/max (ak/abs (fem/component (az/index diagonal index) axis)) 1e-30)]
          (az/set-many!
            norm (ak/max norm (ak/abs force))
            (az/index diagonal index) (with-component (az/index diagonal index) axis scale)
            (az/index (az/field workspace free) index)
            (with-component (az/index (az/field workspace free) index) axis (if active 0.0 1.0))
            (az/index (az/field workspace residual) index)
            (with-component (az/index (az/field workspace residual) index) axis (- force))
            (az/index (az/field workspace search) index)
            (with-component (az/index (az/field workspace search) index) axis (/ (- force) scale)))))
      (set! (az/index (az/field workspace direction) index) (p/v 0.0 0.0 0.0)))
    norm))

(az/defn gradient-direction! :void [[workspace [:* Workspace]]]
  (dotimes [index (az/field (az/field workspace x) len)]
    (dotimes [axis 3]
      (let [gradient (fem/component (az/index (az/field workspace gradient) index) axis)
            free (fem/component (az/index (az/field workspace free) index) axis)
            diagonal (fem/component (az/index (az/field workspace diagonal) index) axis)]
        (set! (az/index (az/field workspace direction) index)
              (with-component (az/index (az/field workspace direction) index) axis (/ (* (- gradient) free) diagonal)))))))

(az/defn newton-direction! :bool
  "Jacobi-preconditioned CG on free variables. Nonpositive curvature or a
  nonfinite recurrence rejects Newton and asks the caller for gradient descent." [[problem [:* Problem]] [workspace [:* Workspace]] [report [:* Report]]]
  (let [residual (az/field workspace residual)
        search (az/field workspace search)
        product (az/field workspace product)
        direction (az/field workspace direction)
        ^:var rz (ak/f64 (inner residual search))
        initial rz]
    (when (or (<= rz 0.0) (ak/! (math/isFinite rz))) (ak/return false))
    (dotimes [_ (az/field problem cg-limit)]
      (mixed/tangent-product! (az/field problem vertex-count) (az/field problem elements)
        (az/field workspace tangents) search product)
      (dotimes [index (az/field product len)]
        (dotimes [axis 3]
          (set! (az/index product index)
                (with-component (az/index product index) axis
                  (* (fem/component (az/index product index) axis)
                     (fem/component (az/index (az/field workspace free) index) axis))))))
      (let [curvature (inner search product)]
        (ak/+= (az/field report cg-iterations) 1)
        (when (or (<= curvature 0.0) (ak/! (math/isFinite curvature))) (ak/return false))
        (let [alpha (/ rz curvature)
              ^:var next-rz (ak/f64 0.0)]
          (dotimes [index (az/field direction len)]
            (az/set-many!
              (az/index direction index) (p/add (az/index direction index) (p/scale (az/index search index) alpha))
              (az/index residual index) (p/add (az/index residual index) (p/scale (az/index product index) (- alpha))))
            (dotimes [axis 3]
              (let [r (fem/component (az/index residual index) axis)]
                (ak/+= next-rz (/ (* r r) (fem/component (az/index (az/field workspace diagonal) index) axis))))))
          (when (ak/! (math/isFinite next-rz)) (ak/return false))
          (when (<= next-rz (* 1e-12 initial)) (ak/return true))
          (let [beta (/ next-rz rz)]
            (dotimes [index (az/field search len)]
              (dotimes [axis 3]
                (let [r (fem/component (az/index residual index) axis)
                      diagonal (fem/component (az/index (az/field workspace diagonal) index) axis)
                      old (fem/component (az/index search index) axis)]
                  (set! (az/index search index) (with-component (az/index search index) axis (+ (/ r diagonal) (* beta old))))))))
          (set! rz next-rz))))
    ;; An inexact direction still needs a finite, strictly descending projected
    ;; line search. The outer KKT test alone decides nonlinear convergence.
    true))

(az/defn line-search! :bool [[problem [:* Problem]] [workspace [:* Workspace]] [report [:* Report]]]
  (let [^:var alpha (ak/f64 1.0)
        x (az/field workspace x)
        trial (az/field workspace trial)]
    (dotimes [_ 40]
      (let [^:var slope (ak/f64 0.0)]
        (ak/+= (az/field report line-trials) 1)
        (dotimes [index (az/field x len)]
          (dotimes [axis 3]
            (let [current (fem/component (az/index x index) axis)
                  delta (* alpha (fem/component (az/index (az/field workspace direction) index) axis))
                  value (ak/min (fem/component (az/index (az/field problem upper) index) axis)
                                (ak/max (fem/component (az/index (az/field problem lower) index) axis) (+ current delta)))]
              (ak/+= slope (* (- value current) (fem/component (az/index (az/field workspace gradient) index) axis)))
              (set! (az/index trial index) (with-component (az/index trial index) axis value)))))
        (when (and (math/isFinite slope) (< slope 0.0))
          (let [iteration-bound (path-bound problem x trial)
                time-bound (path-bound problem (az/field problem initial) trial)]
            (when (and (> iteration-bound 0.0) (> time-bound 0.0))
              (let [response (evaluate! problem trial (az/field workspace trial-gradient) (az/field workspace trial-tangents))]
                (when (and (az/field response valid)
                           (<= (az/field response energy) (+ (az/field report energy) (* 1e-4 slope))))
                  (mem/copyForwards (az/type p/Vec3) x trial)
                  (mem/copyForwards (az/type p/Vec3) (az/field workspace gradient) (az/field workspace trial-gradient))
                  (mem/copyForwards (az/type mixed/QuadraticResponse) (az/field workspace tangents) (az/field workspace trial-tangents))
                  (az/set-many! (az/field report energy) (az/field response energy)
                                (az/field report path-lower-bound) time-bound)
                  (ak/return true)))))))
      (ak/*= alpha 0.5))
    false))

(az/defn solve! Report
  "Caller owns nonaliasing workspace buffers. Initial state is never mutated;
  x is publishable ONLY with status 0. Status: 0 KKT tolerance reached, 1 invalid
  input, 2 uncertified initial geometry, 3 invalid initial energy, 4 iteration
  limit, 5 line-search failure. Success is first-order stationarity, not a proof
  of a global/local minimum, physical accuracy, or general collision safety." [[problem [:* Problem]] [workspace [:* Workspace]]]
  (let [^:var report (mem/zeroes (az/type Report))
        count (az/field (az/field problem initial) len)
        cells (az/field (az/field problem elements) len)]
    (az/set-many! (az/field report status) 1
                  (az/field report force-residual) (math/inf :f64))
    (when (or (<= (az/field problem force-tolerance) 0.0)
              (ak/! (math/isFinite (az/field problem force-tolerance)))
              (ak/== (az/field problem iteration-limit) 0) (ak/== (az/field problem cg-limit) 0)
              (ak/!= (az/field (az/field problem lower) len) count)
              (ak/!= (az/field (az/field problem upper) len) count)
              (ak/!= (az/field (az/field workspace x) len) count)
              (ak/!= (az/field (az/field workspace trial) len) count)
              (ak/!= (az/field (az/field workspace gradient) len) count)
              (ak/!= (az/field (az/field workspace trial-gradient) len) count)
              (ak/!= (az/field (az/field workspace direction) len) count)
              (ak/!= (az/field (az/field workspace residual) len) count)
              (ak/!= (az/field (az/field workspace search) len) count)
              (ak/!= (az/field (az/field workspace product) len) count)
              (ak/!= (az/field (az/field workspace diagonal) len) count)
              (ak/!= (az/field (az/field workspace free) len) count)
              (ak/!= (az/field (az/field workspace tangents) len) cells)
              (ak/!= (az/field (az/field workspace trial-tangents) len) cells))
      (ak/return report))
    (dotimes [index count]
      (dotimes [axis 3]
        (let [x (fem/component (az/index (az/field problem initial) index) axis)
              lower (fem/component (az/index (az/field problem lower) index) axis)
              upper (fem/component (az/index (az/field problem upper) index) axis)]
          (when (or (ak/! (math/isFinite x)) (math/isNan lower) (math/isNan upper)
                    (> lower upper) (< x lower) (> x upper)) (ak/return report)))))
    (mem/copyForwards (az/type p/Vec3) (az/field workspace x) (az/field problem initial))
    (let [response (evaluate! problem (az/field workspace x) (az/field workspace gradient) (az/field workspace tangents))]
      (when (ak/! (az/field response valid))
        (set! (az/field report status) 3)
        (ak/return report))
      (set! (az/field report energy) (az/field response energy)))
    (set! (az/field report path-lower-bound) (path-bound problem (az/field workspace x) (az/field workspace x)))
    (when (<= (az/field report path-lower-bound) 0.0)
      (set! (az/field report status) 2)
      (ak/return report))
    (dotimes [iteration (+ (ak/as (az/field problem iteration-limit) :usize) 1)]
      (set! (az/field report force-residual) (prepare-direction! problem workspace))
      (when (<= (az/field report force-residual) (az/field problem force-tolerance))
        (set! (az/field report status) 0)
        (ak/return report))
      (when (ak/== iteration (az/field problem iteration-limit)) (ak/break))
      (let [newton (newton-direction! problem workspace (ak/& report))]
        (when (ak/! newton)
          (gradient-direction! workspace)
          (ak/+= (az/field report gradient-fallbacks) 1))
        (when (ak/! (line-search! problem workspace (ak/& report)))
          (when newton
            (gradient-direction! workspace)
            (ak/+= (az/field report gradient-fallbacks) 1))
          (when (or (ak/! newton) (ak/! (line-search! problem workspace (ak/& report))))
            (set! (az/field report status) 5)
            (ak/return report))))
      (ak/+= (az/field report iterations) 1))
    (set! (az/field report status) 4)
    report))
