(ns field-lab.mixed-tetra
  "Experimental mixed tetrahedron: linear boundary trace, enriched displacement,
  and independent discontinuous P1 velocity. Used by the experimental mixed solver.
  The moment-compatible basis below is derived in RESEARCH_AND_DESIGN.md."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.hyperelastic :as elastic]
            [field-lab.geometry :as interval]))

(az/defstruct Quadrature {:layout :extern}
  [[:count :u32] [:nodes [:array 12 :f64]] [:weights [:array 12 :f64]]])

(az/defstruct Basis {:layout :extern}
  [[:values [:array 8 :f64]] [:gradients [:array 8 p/Vec3]]])

(az/defstruct Response {:layout :extern}
  [[:valid :bool] [:energy :f64] [:minimum-jacobian :f64]
   [:gradient [:array 24 :f64]] [:hessian [:array 576 :f64]]])

(az/defstruct QuadraticBasis {:layout :extern}
  [[:values [:array 14 :f64]] [:gradients [:array 14 p/Vec3]]])

(az/defstruct QuadraticResponse {:layout :extern}
  [[:valid :bool] [:energy :f64] [:minimum-jacobian :f64]
   [:gradient [:array 42 :f64]] [:hessian [:array 1764 :f64]]])

(az/defn legendre p/Vec3
  "P_n(x) and its derivative for quadrature roots strictly inside (-1,1)." [[order :u32] [x :f64]]
  (let [^{:var :f64} previous 1.0
        ^{:var :f64} current x
        ^{:var :u32} degree 2]
    (while (<= degree order)
      (let [n (ak/as :f64 (ak/floatFromInt degree))
            next (/ (- (* (- (* 2.0 n) 1.0) x current) (* (- n 1.0) previous)) n)]
        (az/set-many! previous current current next)
        (ak/+= degree 1)))
    (p/v current (/ (* (ak/as :f64 (ak/floatFromInt order)) (- (* x current) previous))
                     (- (* x x) 1.0)) 0.0)))

(az/defn quadrature Quadrature
  "Gauss-Legendre on [0,1], 2..12 points. Count zero signals invalid input or
  root failure. Generated natively, including in standalone execution." [[order :u32]]
  (let [^:var result (mem/zeroes (az/type Quadrature))]
    (when (or (< order 2) (> order 12)) (ak/return result))
    (dotimes [index (ak/divTrunc (+ order 1) 2)]
      (let [^{:var :f64} root (ak/cos (/ (* 3.141592653589793
                                            (+ (ak/as :f64 (ak/floatFromInt index)) 0.75))
                                         (+ (ak/as :f64 (ak/floatFromInt order)) 0.5)))
            ^{:var :bool} converged false]
        (dotimes [_ 32]
          (let [polynomial (legendre order root)
                correction (/ (az/field polynomial x) (az/field polynomial y))]
            (ak/-= root correction)
            (when (<= (ak/abs correction) 2e-15)
              (set! converged true)
              (ak/break))))
        (when (ak/! converged) (ak/return result))
        (let [derivative (az/field (legendre order root) y)
              weight (/ 1.0 (* (- 1.0 (* root root)) derivative derivative))
              opposite (- (- order 1) index)]
          (when (or (ak/! (math/isFinite weight)) (<= weight 0.0)) (ak/return result))
          (az/set-many!
            (az/index (az/field result nodes) index) (* 0.5 (- 1.0 root))
            (az/index (az/field result nodes) opposite) (* 0.5 (+ 1.0 root))
            (az/index (az/field result weights) index) weight
            (az/index (az/field result weights) opposite) weight))))
    (set! (az/field result count) order)
    result))

(az/defn basis Basis
  "For barycentric l_i, Psi_i=168*product(l)*(9*l_i-1), Phi_i=l_i-Psi_i.
  The first four coefficients describe the boundary trace; the last four are
  internal projection coefficients, not the positions of interior nodes.
  Gradients supplied by the caller are with respect to rest-world coordinates." [[barycentric [:array 4 :f64]] [gradients [:array 4 p/Vec3]]]
  (let [^:var result (mem/zeroes (az/type Basis))
        ^{:var :f64} product 1.0
        ^:var product-gradient (p/v 0.0 0.0 0.0)]
    (dotimes [index 4]
      (ak/*= product (az/index barycentric index)))
    ;; Products excluding one factor work at faces and vertices, without 0/0.
    (dotimes [index 4]
      (let [^{:var :f64} remaining 1.0]
        (dotimes [other 4]
          (when (ak/!= index other) (ak/*= remaining (az/index barycentric other))))
        (set! product-gradient (p/add product-gradient (p/scale (az/index gradients index) remaining)))))
    (dotimes [index 4]
      (let [linear (az/index barycentric index)
            factor (- (* 9.0 linear) 1.0)
            interior (* 168.0 product factor)
            interior-gradient (p/scale (p/add (p/scale product-gradient factor)
                                              (p/scale (az/index gradients index) (* 9.0 product))) 168.0)]
        (az/set-many!
          (az/index (az/field result values) index) (- linear interior)
          (az/index (az/field result values) (+ 4 index)) interior
          (az/index (az/field result gradients) index)
          (p/add (az/index gradients index) (p/scale interior-gradient -1.0))
          (az/index (az/field result gradients) (+ 4 index)) interior-gradient)))
    result))

(az/defn quadratic-projection :f64
  "Exact L2 projection of the ten quadratic Bernstein traces onto P1.
  Columns: l_i^2 followed by 2*l_i*l_j for edges 01,02,03,12,13,23." [[row :u32] [column :u32]]
  (if (< column 4)
    (if (ak/== row column) (/ 3.0 5.0) (/ -1.0 15.0))
    (let [incident (or (and (ak/== column 4) (or (ak/== row 0) (ak/== row 1)))
                       (and (ak/== column 5) (or (ak/== row 0) (ak/== row 2)))
                       (and (ak/== column 6) (or (ak/== row 0) (ak/== row 3)))
                       (and (ak/== column 7) (or (ak/== row 1) (ak/== row 2)))
                       (and (ak/== column 8) (or (ak/== row 1) (ak/== row 3)))
                       (and (ak/== column 9) (or (ak/== row 2) (ak/== row 3))))]
      (if incident (/ 4.0 15.0) (/ -1.0 15.0)))))

(az/defn quadratic-basis QuadraticBasis
  "Ten shared Bernstein trace controls and four private P1 projections.
  Phi_a=N_a-sum_i(Psi_i*C_ia). On faces Psi=0 and Phi=N>=0, so
  nonnegative position controls suffice for complete quadratic face clearance.
  Edge coefficients are Bernstein controls, not interpolating midpoint values." [[barycentric [:array 4 :f64]] [gradients [:array 4 p/Vec3]]]
  (let [^:var result (mem/zeroes (az/type QuadraticBasis))
        enriched (basis barycentric gradients)
        ^{:var :usize} edge 4]
    (dotimes [node 4]
      (let [coordinate (az/index barycentric node)]
        (az/set-many!
          (az/index (az/field result values) node) (* coordinate coordinate)
          (az/index (az/field result gradients) node) (p/scale (az/index gradients node) (* 2.0 coordinate))
          (az/index (az/field result values) (+ 10 node)) (az/index (az/field enriched values) (+ 4 node))
          (az/index (az/field result gradients) (+ 10 node)) (az/index (az/field enriched gradients) (+ 4 node)))))
    (dotimes [left 4]
      (dotimes [right 4]
        (when (> right left)
          (az/set-many!
            (az/index (az/field result values) edge) (* 2.0 (az/index barycentric left) (az/index barycentric right))
            (az/index (az/field result gradients) edge)
            (p/scale (p/add (p/scale (az/index gradients left) (az/index barycentric right))
                            (p/scale (az/index gradients right) (az/index barycentric left))) 2.0))
          (ak/+= edge 1))))
    (dotimes [column 10]
      (dotimes [row 4]
        (let [coefficient (quadratic-projection (ak/intCast row) (ak/intCast column))]
          (ak/-= (az/index (az/field result values) column)
                 (* coefficient (az/index (az/field enriched values) (+ 4 row))))
          (set! (az/index (az/field result gradients) column)
                (p/add (az/index (az/field result gradients) column)
                       (p/scale (az/index (az/field enriched gradients) (+ 4 row)) (- coefficient)))))))
    result))

(az/defn displacement-gradient elastic/Matrix
  "H=sum(u_a outer grad N_a); all coefficients are displacements in metres." [[sample Basis] [displacements [:array 8 p/Vec3]]]
  (let [^:var result (elastic/zero)]
    (dotimes [index 8]
      (set! result (elastic/add result
                    (elastic/outer (az/index displacements index)
                                   (az/index (az/field sample gradients) index)))))
    result))

(az/defn deformation elastic/Matrix [[sample Basis] [displacements [:array 8 p/Vec3]]]
  (elastic/add (elastic/identity) (displacement-gradient sample displacements)))

(az/defn mass-entry :f64
  "Scalar mixed mass matrix. The four boundary coefficients have no inertia;
  the internal P1 velocity coefficients have the exact consistent tetra mass." [[density :f64] [volume :f64] [row :u32] [column :u32]]
  (if (or (< row 4) (< column 4) (>= row 8) (>= column 8))
    0.0
    (* density volume 0.05 (ak/as :f64 (if (ak/== row column) 2.0 1.0)))))

(az/defn kinetic-energy :f64 [[density :f64] [volume :f64] [velocities [:array 8 p/Vec3]]]
  (let [^:var sum (p/v 0.0 0.0 0.0)
        ^{:var :f64} squared 0.0]
    (dotimes [index 4]
      (let [velocity (az/index velocities (+ index 4))]
        (set! sum (p/add sum velocity))
        (ak/+= squared (p/dot velocity velocity))))
    (* 0.025 density volume (+ squared (p/dot sum sum)))))

(az/defn accumulate-hessian! :void
  "Contract the exact material Hessian with basis gradients in 3x3 blocks.
  Invariant derivatives and F/cofactor products are shared across node pairs.
  The determinant term is polynomial; no inverse F or Hessian clipping is used."
  [[result :anytype] [sample :anytype] [f elastic/Matrix]
   [parameters elastic/Material] [weight :f64]]
  (let [mu (az/field parameters mu)
        lambda (az/field parameters lambda)
        denominator (+ 1.0 (elastic/inner f f))
        shear (* mu (- 1.0 (/ 1.0 denominator)))
        invariant-curvature (/ (* 2.0 mu) (* denominator denominator))
        pressure (* lambda (- (elastic/determinant f) (az/field parameters alpha)))
        cofactor (elastic/cofactor f)
        count (az/field (az/field sample gradients) len)
        size (* 3 count)
        ^:var transformed (mem/zeroes (ak/TypeOf (az/field sample gradients)))
        ^:var cofactor-gradients (mem/zeroes (ak/TypeOf (az/field sample gradients)))]
    (dotimes [node count]
      (let [gradient (az/index (az/field sample gradients) node)]
        (az/set-many!
          (az/index transformed node) (elastic/apply-vector f gradient)
          (az/index cofactor-gradients node) (elastic/apply-vector cofactor gradient))))
    (dotimes [left count]
      (dotimes [right count]
        (when (>= right left)
          (let [a (az/index (az/field sample gradients) left)
                b (az/index (az/field sample gradients) right)
                cross (elastic/apply-vector f (p/cross a b))
                ;; -[F*(a cross b)]_x contracts the determinant Hessian.
                determinant-block
                (elastic/Matrix
                  {:c0 (p/v 0.0 (- (az/field cross z)) (az/field cross y))
                   :c1 (p/v (az/field cross z) 0.0 (- (az/field cross x)))
                   :c2 (p/v (- (az/field cross y)) (az/field cross x) 0.0)})
                block
                (elastic/add
                  (elastic/add (elastic/scale (elastic/identity) (* shear (p/dot a b)))
                               (elastic/scale (elastic/outer (az/index transformed left)
                                                             (az/index transformed right)) invariant-curvature))
                  (elastic/add (elastic/scale (elastic/outer (az/index cofactor-gradients left)
                                                             (az/index cofactor-gradients right)) lambda)
                               (elastic/scale determinant-block pressure)))
                columns (az/array-init [:array 3 p/Vec3]
                           [(az/field block c0) (az/field block c1) (az/field block c2)])]
            (dotimes [column-axis 3]
              (dotimes [row-axis 3]
                (let [row (+ (* 3 left) row-axis)
                      column (+ (* 3 right) column-axis)
                      value (* weight (fem/component (az/index columns column-axis) row-axis))]
                  (ak/+= (az/index (az/field result hessian) (+ (* size row) column)) value)
                  (when (ak/!= left right)
                    (ak/+= (az/index (az/field result hessian) (+ (* size column) row)) value)))))))))))

(az/defn integrate ResultType
  "Shared quadrature and constitutive evaluation for both trace spaces.
  The result type, basis function and coefficient array specialize at compile time."
  [[ResultType {:zig/prefix "comptime"} :type] [sample-function :anytype]
   [gradients [:array 4 p/Vec3]] [volume :f64] [parameters elastic/Material]
   [displacements :anytype] [rule Quadrature] [with-hessian :bool]]
  (let [^:var result (mem/zeroes ResultType)
        nodes (az/field displacements len)
        count (az/field rule count)]
    (when (or (< count 2) (> count 12) (<= volume 0.0) (ak/! (math/isFinite volume)))
      (ak/return result))
    (set! (az/field result minimum-jacobian) 1e300)
    (dotimes [i count]
      (dotimes [j count]
        (dotimes [k count]
          (let [u (az/index (az/field rule nodes) i)
                v (az/index (az/field rule nodes) j)
                w (az/index (az/field rule nodes) k)
                a (- 1.0 u)
                b (- 1.0 v)
                barycentric (az/array-init [:array 4 :f64] [(* a b (- 1.0 w)) u (* a v) (* a b w)])
                weight (* 6.0 volume a a b (az/index (az/field rule weights) i)
                          (az/index (az/field rule weights) j) (az/index (az/field rule weights) k))
                sample (sample-function barycentric gradients)
                ^:var displacement-derivative (elastic/zero)]
            (dotimes [node nodes]
              (set! displacement-derivative
                    (elastic/add displacement-derivative
                      (elastic/outer (az/index displacements node)
                                     (az/index (az/field sample gradients) node)))))
            (let [f (elastic/add (elastic/identity) displacement-derivative)
                  response (elastic/evaluate-gradient displacement-derivative parameters)]
              (when (or (<= weight 0.0) (ak/! (math/isFinite weight))
                        (<= (az/field response jacobian) 0.0)
                        (ak/! (math/isFinite (az/field response energy-density))))
                (ak/return result))
              (ak/+= (az/field result energy) (* weight (az/field response energy-density)))
              (set! (az/field result minimum-jacobian)
                    (ak/min (az/field result minimum-jacobian) (az/field response jacobian)))
              (dotimes [node nodes]
                (let [force (elastic/apply-vector (az/field response pk1)
                                                 (az/index (az/field sample gradients) node))]
                  (dotimes [axis 3]
                    (ak/+= (az/index (az/field result gradient) (+ (* 3 node) axis))
                           (* weight (fem/component force axis))))))
              (when with-hessian
                (accumulate-hessian! (ak/& result) sample f parameters weight)))))))
    (when (ak/! (math/isFinite (az/field result energy))) (ak/return result))
    (dotimes [index (az/field (az/field result gradient) len)]
      (when (ak/! (math/isFinite (az/index (az/field result gradient) index))) (ak/return result)))
    (when with-hessian
      (dotimes [index (az/field (az/field result hessian) len)]
        (when (ak/! (math/isFinite (az/index (az/field result hessian) index))) (ak/return result))))
    (set! (az/field result valid) true)
    result))

(az/defn evaluate Response
  "Linear trace / P1 projection element. Six points per axis integrate its rest
  stiffness exactly. Sampled positive J is not a whole-element certificate."
  [[gradients [:array 4 p/Vec3]] [volume :f64] [parameters elastic/Material]
   [displacements [:array 8 p/Vec3]] [rule Quadrature] [with-hessian :bool]]
  (integrate Response basis gradients volume parameters displacements rule with-hessian))

(az/defn evaluate-quadratic QuadraticResponse
  "Experimental quadratic trace / P1 projection element: 10 trace controls and
  4 private coefficients. Energy, gradient and exact Hessian share the existing
  constitutive kernel. Whole-element validity is checked separately by the
  Bernstein interval path certificate."
  [[gradients [:array 4 p/Vec3]] [volume :f64] [parameters elastic/Material]
   [displacements [:array 14 p/Vec3]] [rule Quadrature] [with-hessian :bool]]
  (integrate QuadraticResponse quadratic-basis gradients volume parameters displacements rule with-hessian))

(az/defn path-jacobian-bound interval/Interval
  "Outward interval enclosure of det F over the entire tetrahedron and straight
  coefficient path. Uses the convex hull of the degree-four Bernstein controls
  of F at both endpoints. A positive lower bound certifies the path; a bound
  crossing zero is inconclusive, not proof of inversion. No sampling is used."
  [[gradients [:array 4 p/Vec3]] [before [:array 8 p/Vec3]] [after [:array 8 p/Vec3]]]
  (let [unknown (interval/Interval {:lower (- (math/inf :f64)) :upper (math/inf :f64)})
        ^:var envelope (mem/zeroes (az/type [:array 9 interval/Interval]))]
    (when (ak/!= (interval/fegetround) 0) (ak/return unknown))
    (dotimes [index 9]
      (set! (az/index envelope index)
            (interval/Interval {:lower (math/inf :f64) :upper (- (math/inf :f64))})))
    (dotimes [endpoint 2]
      (let [displacements (if (ak/== endpoint 0) before after)
            ^:var base (mem/zeroes (az/type [:array 9 interval/Interval]))
            ^:var delta (mem/zeroes (az/type [:array 12 interval/Interval]))
            ^:var sum-delta (mem/zeroes (az/type [:array 3 interval/Interval]))
            ^:var sum-gradient (mem/zeroes (az/type [:array 3 interval/Interval]))]
        (dotimes [node 4]
          (dotimes [axis 3]
            (let [q (fem/component (az/index displacements node) axis)
                  r (fem/component (az/index displacements (+ node 4)) axis)
                  g (fem/component (az/index gradients node) axis)]
              (when (or (ak/! (math/isFinite q)) (ak/! (math/isFinite r)) (ak/! (math/isFinite g)))
                (ak/return unknown))
              (let [difference (interval/subtract (interval/point r) (interval/point q))]
                (az/set-many!
                  (az/index delta (+ (* 3 node) axis)) difference
                  (az/index sum-delta axis) (interval/add (az/index sum-delta axis) difference)
                  (az/index sum-gradient axis) (interval/add (az/index sum-gradient axis) (interval/point g)))))))
        (dotimes [row 3]
          (dotimes [column 3]
            (let [^{:var interval/Interval} entry (interval/point (if (ak/== row column) 1.0 0.0))]
              (dotimes [node 4]
                (set! entry (interval/add entry
                              (interval/multiply
                                (interval/point (fem/component (az/index displacements node) row))
                                (interval/point (fem/component (az/index gradients node) column))))))
              (set! (az/index base (+ (* 3 row) column)) entry))))
        ;; There are 35 degree-four controls. Twenty-two equal the affine base,
        ;; twelve have exponents (0,2,1,1) under permutation, and one is (1,1,1,1).
        (dotimes [control 14]
          (let [^{:var :u32} missing 0
                ^{:var :u32} doubled 0]
            (when (>= control 2)
              (az/set-many! missing (ak/intCast (ak/divTrunc (- control 2) 3))
                            doubled (ak/intCast (mod (- control 2) 3)))
              (when (>= doubled missing) (ak/+= doubled 1)))
            (dotimes [row 3]
              (dotimes [column 3]
                (let [index (+ (* 3 row) column)
                      ^:var entry (az/index base index)]
                  (if (ak/== control 1)
                    (do
                      (dotimes [node 4]
                        (set! entry (interval/add entry
                                      (interval/multiply (interval/point 126.0)
                                        (interval/multiply (az/index delta (+ (* 3 node) row))
                                          (interval/point (fem/component (az/index gradients node) column)))))))
                      (set! entry (interval/subtract entry
                                    (interval/multiply (interval/point 7.0)
                                      (interval/multiply (az/index sum-delta row) (az/index sum-gradient column))))))
                    (when (>= control 2)
                      (let [factor (interval/multiply (interval/point 14.0)
                                     (interval/subtract
                                       (interval/multiply (interval/point 9.0) (az/index delta (+ (* 3 doubled) row)))
                                       (az/index sum-delta row)))]
                        (set! entry (interval/add entry
                                      (interval/multiply factor
                                        (interval/point (fem/component (az/index gradients missing) column))))))))
                  (when (or (ak/! (math/isFinite (az/field entry lower)))
                            (ak/! (math/isFinite (az/field entry upper))))
                    (ak/return unknown))
                  (az/set-many!
                    (az/field (az/index envelope index) lower)
                    (ak/min (az/field (az/index envelope index) lower) (az/field entry lower))
                    (az/field (az/index envelope index) upper)
                    (ak/max (az/field (az/index envelope index) upper) (az/field entry upper))))))))))
    (let [bound (interval/determinant
                  (az/array-init [:array 3 interval/Interval] [(az/index envelope 0) (az/index envelope 3) (az/index envelope 6)])
                  (az/array-init [:array 3 interval/Interval] [(az/index envelope 1) (az/index envelope 4) (az/index envelope 7)])
                  (az/array-init [:array 3 interval/Interval] [(az/index envelope 2) (az/index envelope 5) (az/index envelope 8)]))]
      (if (and (math/isFinite (az/field bound lower)) (math/isFinite (az/field bound upper))) bound unknown))))

(az/defn quadratic-path-bound interval/Interval
  "Outward enclosure of det F for quadratic traces, throughout the tetrahedron
  and the straight coefficient path. Enumerates all 35 degree-four Bernstein
  controls. Bounds crossing zero are inconclusive and must reject the step."
  [[gradients [:array 4 p/Vec3]] [before [:array 14 p/Vec3]] [after [:array 14 p/Vec3]]]
  (let [unknown (interval/Interval {:lower (- (math/inf :f64)) :upper (math/inf :f64)})
        ^:var envelope (mem/zeroes (az/type [:array 9 interval/Interval]))]
    (when (ak/!= (interval/fegetround) 0) (ak/return unknown))
    (dotimes [node 4]
      (dotimes [axis 3]
        (when (ak/! (math/isFinite (fem/component (az/index gradients node) axis))) (ak/return unknown))))
    (dotimes [index 9]
      (set! (az/index envelope index)
            (interval/Interval {:lower (math/inf :f64) :upper (- (math/inf :f64))})))
    (dotimes [endpoint 2]
      (let [values (if (ak/== endpoint 0) before after)
            ^:var delta (mem/zeroes (az/type [:array 12 interval/Interval]))]
        (dotimes [node 14]
          (dotimes [axis 3]
            (when (ak/! (math/isFinite (fem/component (az/index values node) axis))) (ak/return unknown))))
        ;; u=sum(N_a*q_a)+sum(Psi_i*(r_i-C_ia*q_a)). Enclose the
        ;; rational projection constants and every operation; do not transform
        ;; coefficients in binary64 and then treat the result as exact.
        (dotimes [node 4]
          (dotimes [axis 3]
            (let [^:var entry (interval/point (fem/component (az/index values (+ 10 node)) axis))]
              (dotimes [column 10]
                (let [coefficient (quadratic-projection (ak/intCast node) (ak/intCast column))
                      bound (interval/Interval {:lower (interval/down coefficient) :upper (interval/up coefficient)})]
                  (set! entry (interval/subtract entry
                                (interval/multiply bound (interval/point (fem/component (az/index values column) axis)))))))
              (set! (az/index delta (+ (* 3 node) axis)) entry))))
        (dotimes [a 5]
          (dotimes [b (- 5 a)]
            (dotimes [c (- 5 a b)]
              (let [alpha (az/array-init [:array 4 :usize] [a b c (- 4 a b c)])
                    ^{:var :usize} missing 4
                    ^{:var :usize} doubled 4
                    ^{:var :usize} ones 0]
                (dotimes [node 4]
                  (let [power (az/index alpha node)]
                    (when (ak/== power 0) (set! missing node))
                    (when (ak/== power 1) (ak/+= ones 1))
                    (when (ak/== power 2) (set! doubled node))))
                (dotimes [row 3]
                  (dotimes [column 3]
                    (let [^:var entry (interval/point (if (ak/== row column) 1.0 0.0))
                          ^{:var :usize} edge 4]
                      ;; Degree elevation of a linear trace gradient to degree
                      ;; four evaluates it at alpha/4 (exact binary fractions).
                      (dotimes [left 4]
                        (let [factor (* 0.5 (ak/as :f64 (ak/floatFromInt (az/index alpha left))))]
                          (set! entry (interval/add entry
                                        (interval/multiply
                                          (interval/point (fem/component (az/index values left) row))
                                          (interval/multiply (interval/point factor)
                                            (interval/point (fem/component (az/index gradients left) column)))))))
                        (dotimes [right 4]
                          (when (> right left)
                            (let [left-weight (* 0.5 (ak/as :f64 (ak/floatFromInt (az/index alpha right))))
                                  right-weight (* 0.5 (ak/as :f64 (ak/floatFromInt (az/index alpha left))))
                                  gradient (interval/add
                                             (interval/multiply (interval/point left-weight)
                                               (interval/point (fem/component (az/index gradients left) column)))
                                             (interval/multiply (interval/point right-weight)
                                               (interval/point (fem/component (az/index gradients right) column))))]
                              (set! entry (interval/add entry
                                            (interval/multiply gradient
                                              (interval/point (fem/component (az/index values edge) row))))))
                            (ak/+= edge 1))))
                      (dotimes [node 4]
                        (let [^:var gradient (interval/point 0.0)]
                          (if (ak/== ones 4)
                            (do
                              (set! gradient (interval/multiply (interval/point 126.0)
                                               (interval/point (fem/component (az/index gradients node) column))))
                              (dotimes [other 4]
                                (set! gradient (interval/subtract gradient
                                                 (interval/multiply (interval/point 7.0)
                                                   (interval/point (fem/component (az/index gradients other) column)))))))
                            (when (and (ak/== ones 2) (< missing 4) (< doubled 4))
                              (set! gradient (interval/multiply
                                               (interval/point (if (ak/== node doubled) 112.0 -14.0))
                                               (interval/point (fem/component (az/index gradients missing) column))))))
                          (set! entry (interval/add entry
                                        (interval/multiply gradient (az/index delta (+ (* 3 node) row)))))))
                      (when (or (ak/! (math/isFinite (az/field entry lower)))
                                (ak/! (math/isFinite (az/field entry upper)))) (ak/return unknown))
                      (let [index (+ (* 3 row) column)]
                        (az/set-many!
                          (az/field (az/index envelope index) lower)
                          (ak/min (az/field (az/index envelope index) lower) (az/field entry lower))
                          (az/field (az/index envelope index) upper)
                          (ak/max (az/field (az/index envelope index) upper) (az/field entry upper)))))))))))))
    (let [bound (interval/determinant
                  (az/array-init [:array 3 interval/Interval] [(az/index envelope 0) (az/index envelope 3) (az/index envelope 6)])
                  (az/array-init [:array 3 interval/Interval] [(az/index envelope 1) (az/index envelope 4) (az/index envelope 7)])
                  (az/array-init [:array 3 interval/Interval] [(az/index envelope 2) (az/index envelope 5) (az/index envelope 8)]))]
      (if (and (math/isFinite (az/field bound lower)) (math/isFinite (az/field bound upper))) bound unknown))))

(az/defstruct Element {:layout :extern}
  [[:vertices [:array 4 :u32]] [:edges [:array 6 :u32]] [:quadratic? :bool] [:gradients [:array 4 p/Vec3]]
   [:volume :f64] [:density :f64] [:material elastic/Material]])

(az/defstruct StepResponse {:layout :extern}
  [[:valid :bool] [:energy :f64] [:elastic-energy :f64]
   [:inertial-energy :f64] [:load-potential :f64]])

(az/defn trace-count :usize [[element Element]]
  (if (az/field element quadratic?) 10 4))

(az/defn coefficient-count :usize [[element Element]]
  (+ (trace-count element) 4))

(az/defn coefficient-index :usize
  "Shared trace controls first, then four private coefficients per cell." [[vertex-count :usize] [cell :usize] [element Element] [local :usize]]
  (if (< local 4)
    (az/index (az/field element vertices) local)
    (if (< local (trace-count element))
      (az/index (az/field element edges) (- local 4))
      (+ vertex-count (* 4 cell) (- local (trace-count element))))))

(az/defn evaluate-element QuadraticResponse
  "Assembly uses a 42-scalar stride for both spaces. The original eight-control
  element keeps its specialized quadrature and is expanded only after evaluation."
  [[element Element] [local [:array 14 p/Vec3]] [rule Quadrature] [with-hessian :bool]]
  (when (az/field element quadratic?)
    (ak/return (evaluate-quadratic (az/field element gradients) (az/field element volume)
                 (az/field element material) local rule with-hessian)))
  (let [^:var linear (mem/zeroes (az/type [:array 8 p/Vec3]))
        ^:var result (mem/zeroes (az/type QuadraticResponse))]
    (dotimes [index 8] (set! (az/index linear index) (az/index local index)))
    (let [response (evaluate (az/field element gradients) (az/field element volume)
                     (az/field element material) linear rule with-hessian)]
      (az/set-many! (az/field result valid) (az/field response valid)
                    (az/field result energy) (az/field response energy)
                    (az/field result minimum-jacobian) (az/field response minimum-jacobian))
      (dotimes [row 24]
        (set! (az/index (az/field result gradient) row) (az/index (az/field response gradient) row))
        (when with-hessian
          (dotimes [column 24]
            (set! (az/index (az/field result hessian) (+ (* 42 row) column))
                  (az/index (az/field response hessian) (+ (* 24 row) column)))))))
    result))

(az/defn element-path-bound interval/Interval
  [[element Element] [before [:array 14 p/Vec3]] [after [:array 14 p/Vec3]]]
  (when (az/field element quadratic?)
    (ak/return (quadratic-path-bound (az/field element gradients) before after)))
  (let [^:var start (mem/zeroes (az/type [:array 8 p/Vec3]))
        ^:var end (mem/zeroes (az/type [:array 8 p/Vec3]))]
    (dotimes [index 8]
      (az/set-many! (az/index start index) (az/index before index)
                    (az/index end index) (az/index after index)))
    (path-jacobian-bound (az/field element gradients) start end)))

(az/defn assemble-step! StepResponse
  "Backward-Euler incremental potential in joules, gradient in newtons, and
  cached element Hessians in N/m. Prediction is u_old + dt*v_old for the private
  coefficients; boundary prediction is ignored. Gravity is uniform acceleration;
  loads are already integrated generalized forces (e.g. face traction on q).
  Output buffers must not alias inputs. On failure all scratch outputs must be
  discarded. Valid means numerical evaluation only, NOT a collision or inversion
  certificate. A nonlinear driver must separately certify its accepted path."
  [[vertex-count :usize] [elements [:slice Element]]
   [coefficients [:slice p/Vec3]] [prediction [:slice p/Vec3]]
   [gravity p/Vec3] [loads [:slice p/Vec3]] [duration :f64] [rule Quadrature]
   [gradient [:slice p/Vec3]] [tangents [:slice QuadraticResponse]]]
  (let [^:var result (mem/zeroes (az/type StepResponse))
        count (+ vertex-count (* 4 (az/field elements len)))
        inverse-time-squared (/ 1.0 (* duration duration))]
    (when (or (ak/== vertex-count 0) (ak/== (az/field elements len) 0)
              (ak/!= (az/field coefficients len) count) (ak/!= (az/field prediction len) count)
              (ak/!= (az/field loads len) count) (ak/!= (az/field gradient len) count)
              (ak/!= (az/field tangents len) (az/field elements len))
              (<= duration 0.0) (ak/! (math/isFinite duration))
              (<= inverse-time-squared 0.0) (ak/! (math/isFinite inverse-time-squared)))
      (ak/return result))
    (dotimes [axis 3]
      (when (ak/! (math/isFinite (fem/component gravity axis))) (ak/return result)))
    (dotimes [index count]
      (dotimes [axis 3]
        (when (or (ak/! (math/isFinite (fem/component (az/index coefficients index) axis)))
                  (ak/! (math/isFinite (fem/component (az/index loads index) axis)))
                  (and (>= index vertex-count)
                       (ak/! (math/isFinite (fem/component (az/index prediction index) axis)))))
          (ak/return result)))
      (set! (az/index gradient index) (p/scale (az/index loads index) -1.0))
      (ak/-= (az/field result load-potential) (p/dot (az/index loads index) (az/index coefficients index))))
    (dotimes [cell (az/field elements len)]
      (let [element (az/index elements cell)
            density (az/field element density)
            volume (az/field element volume)
            ^:var local (mem/zeroes (az/type [:array 14 p/Vec3]))
            ^:var delta (mem/zeroes (az/type [:array 4 p/Vec3]))
            ^:var sum-delta (p/v 0.0 0.0 0.0)]
        (when (or (<= density 0.0) (ak/! (math/isFinite density))) (ak/return result))
        (dotimes [node (trace-count element)]
          (when (>= (coefficient-index vertex-count cell element node) vertex-count) (ak/return result))
          (dotimes [other node]
            (when (ak/== (coefficient-index vertex-count cell element node)
                         (coefficient-index vertex-count cell element other)) (ak/return result))))
        (dotimes [node (coefficient-count element)]
          (set! (az/index local node) (az/index coefficients (coefficient-index vertex-count cell element node))))
        (dotimes [node 4]
          (let [index (+ vertex-count (* 4 cell) node)
                difference (p/add (az/index coefficients index) (p/scale (az/index prediction index) -1.0))]
            (az/set-many! (az/index delta node) difference
                          sum-delta (p/add sum-delta difference))))
        (let [^:var response (evaluate-element element local rule true)
              mass-factor (* density volume 0.05)
              inertia-factor (* mass-factor inverse-time-squared)
              gravity-force (p/scale gravity (* density volume 0.25))]
          (when (ak/! (az/field response valid)) (ak/return result))
          (ak/+= (az/field result elastic-energy) (az/field response energy))
          (dotimes [node 4]
            (let [difference (az/index delta node)
                  inertia-force (p/scale (p/add difference sum-delta) inertia-factor)
                  force (p/add inertia-force (p/scale gravity-force -1.0))
                  inertial-energy (* 0.5 (p/dot difference inertia-force))
                  gravity-work (p/dot gravity-force (az/index local (+ (trace-count element) node)))]
              (ak/+= (az/field result inertial-energy) inertial-energy)
              (ak/-= (az/field result load-potential) gravity-work)
              (ak/+= (az/field response energy) (- inertial-energy gravity-work))
              (dotimes [axis 3]
                (let [row (+ (* 3 (trace-count element)) (* 3 node) axis)]
                  (ak/+= (az/index (az/field response gradient) row) (fem/component force axis))
                  (dotimes [other 4]
                    (let [column (+ (* 3 (trace-count element)) (* 3 other) axis)]
                      (ak/+= (az/index (az/field response hessian) (+ (* 42 row) column))
                             (* inertia-factor (ak/as :f64 (if (ak/== node other) 2.0 1.0))))))))))
          (dotimes [node (coefficient-count element)]
            (let [index (coefficient-index vertex-count cell element node)
                  offset (* 3 node)]
              (set! (az/index gradient index)
                    (p/add (az/index gradient index)
                      (p/v (az/index (az/field response gradient) offset)
                           (az/index (az/field response gradient) (+ offset 1))
                           (az/index (az/field response gradient) (+ offset 2)))))))
          (set! (az/index tangents cell) response))))
    (set! (az/field result energy)
          (+ (az/field result elastic-energy) (az/field result inertial-energy) (az/field result load-potential)))
    (when (ak/! (math/isFinite (az/field result energy))) (ak/return result))
    (dotimes [index count]
      (dotimes [axis 3]
        (when (ak/! (math/isFinite (fem/component (az/index gradient index) axis))) (ak/return result))))
    (dotimes [cell (az/field elements len)]
      (dotimes [index 1764]
        (when (ak/! (math/isFinite (az/index (az/field (az/index tangents cell) hessian) index)))
          (ak/return result))))
    (set! (az/field result valid) true)
    result))

(az/defn tangent-product! :void
  "Apply the assembled tangent without a dense global matrix. Reuse only the
  element responses from a successful assemble-step! with unchanged topology.
  Input and output must not alias; slice sizes and connectivity are caller-owned."
  [[vertex-count :usize] [elements [:slice Element]] [tangents [:slice QuadraticResponse]]
   [direction [:slice p/Vec3]] [output [:slice p/Vec3]]]
  (dotimes [index (az/field output len)] (set! (az/index output index) (p/v 0.0 0.0 0.0)))
  (dotimes [cell (az/field elements len)]
    (let [element (az/index elements cell)
          ^:var local (mem/zeroes (az/type [:array 42 :f64]))]
      (dotimes [node (coefficient-count element)]
        (let [value (az/index direction (coefficient-index vertex-count cell element node))]
          (dotimes [axis 3] (set! (az/index local (+ (* 3 node) axis)) (fem/component value axis)))))
      (dotimes [node (coefficient-count element)]
        (let [index (coefficient-index vertex-count cell element node)
              ^:var product (mem/zeroes (az/type [:array 3 :f64]))]
          (dotimes [axis 3]
            (dotimes [column (* 3 (coefficient-count element))]
              (ak/+= (az/index product axis)
                     (* (az/index (az/field (az/index tangents cell) hessian) (+ (* 42 (+ (* 3 node) axis)) column))
                        (az/index local column)))))
          (set! (az/index output index)
                (p/add (az/index output index) (p/v (az/index product 0) (az/index product 1) (az/index product 2)))))))))
