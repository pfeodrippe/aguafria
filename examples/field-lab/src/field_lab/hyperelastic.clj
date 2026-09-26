(ns field-lab.hyperelastic
  "Stable Neo-Hookean energy, PK1 stress and exact directional tangent.
  Smith, de Goes & Kim (2018), equations 14, 18 and section 3.4."
  (:refer-clojure :exclude [identity])
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]))

(az/defstruct Matrix {:layout :extern}
  [[:c0 p/Vec3] [:c1 p/Vec3] [:c2 p/Vec3]])

(az/defstruct Material {:layout :extern}
  [[:mu :f64] [:lambda :f64] [:alpha :f64]])

(az/defstruct Response {:layout :extern}
  [[:energy-density :f64] [:jacobian :f64] [:pk1 Matrix]])

(az/defn material Material
  [[young :f64] [poisson :f64]]
  (let [shear (/ young (* 2.0 (+ 1.0 poisson)))
        lame (/ (* young poisson) (* (+ 1.0 poisson) (- 1.0 (* 2.0 poisson))))
        mu (* (/ 4.0 3.0) shear)
        lambda (+ lame (* (/ 5.0 6.0) shear))]
    (Material {:mu mu :lambda lambda :alpha (+ 1.0 (/ (* 0.75 mu) lambda))})))

(az/defn identity Matrix
  []
  (Matrix {:c0 (p/v 1.0 0.0 0.0) :c1 (p/v 0.0 1.0 0.0) :c2 (p/v 0.0 0.0 1.0)}))

(az/defn zero Matrix
  []
  (Matrix {:c0 (p/v 0.0 0.0 0.0) :c1 (p/v 0.0 0.0 0.0) :c2 (p/v 0.0 0.0 0.0)}))

(az/defn scale Matrix
  [[matrix Matrix] [factor :f64]]
  (Matrix {:c0 (p/scale (az/field matrix c0) factor)
           :c1 (p/scale (az/field matrix c1) factor)
           :c2 (p/scale (az/field matrix c2) factor)}))

(az/defn add Matrix
  [[a Matrix] [b Matrix]]
  (Matrix {:c0 (p/add (az/field a c0) (az/field b c0))
           :c1 (p/add (az/field a c1) (az/field b c1))
           :c2 (p/add (az/field a c2) (az/field b c2))}))

(az/defn inner :f64
  [[a Matrix] [b Matrix]]
  (+ (p/dot (az/field a c0) (az/field b c0))
     (p/dot (az/field a c1) (az/field b c1))
     (p/dot (az/field a c2) (az/field b c2))))

(az/defn apply-vector p/Vec3
  [[matrix Matrix] [vector p/Vec3]]
  (p/add (p/add (p/scale (az/field matrix c0) (az/field vector x))
                (p/scale (az/field matrix c1) (az/field vector y)))
         (p/scale (az/field matrix c2) (az/field vector z))))

(az/defn outer Matrix
  [[a p/Vec3] [b p/Vec3]]
  (Matrix {:c0 (p/scale a (az/field b x))
           :c1 (p/scale a (az/field b y))
           :c2 (p/scale a (az/field b z))}))

(az/defn cofactor Matrix
  [[deformation Matrix]]
  (Matrix {:c0 (p/cross (az/field deformation c1) (az/field deformation c2))
           :c1 (p/cross (az/field deformation c2) (az/field deformation c0))
           :c2 (p/cross (az/field deformation c0) (az/field deformation c1))}))

(az/defn determinant :f64
  [[deformation Matrix]]
  (p/dot (az/field deformation c0)
         (p/cross (az/field deformation c1) (az/field deformation c2))))

(az/defn compensated-dot :f64
  "Recover product and summation roundoff when a dot product nearly cancels offset."
  [[a p/Vec3] [b p/Vec3] [offset :f64]]
  (let [x (* (az/field a x) (az/field b x))
        y (* (az/field a y) (az/field b y))
        z (* (az/field a z) (az/field b z))
        values (az/init [x y z offset
                  (ak/mulAdd :f64 (az/field a x) (az/field b x) (- x))
                  (ak/mulAdd :f64 (az/field a y) (az/field b y) (- y))
                  (ak/mulAdd :f64 (az/field a z) (az/field b z) (- z))] [:array 7 :f64])
        ^:var sum (ak/f64 0.0)
        ^:var correction (ak/f64 0.0)]
    (dotimes [index 7]
      (let [value (az/index values index)
            next (+ sum value)]
        (ak/+= correction (if (>= (ak/abs sum) (ak/abs value))
                           (+ (- sum next) value)
                           (+ (- value next) sum)))
        (set! sum next)))
    (+ sum correction)))

(az/defn metric-strain Matrix
  "FᵀF-I, with compensated dot products to retain small strains after rotation." [[deformation Matrix]]
  (let [a (az/field deformation c0)
        b (az/field deformation c1)
        c (az/field deformation c2)
        xx (compensated-dot a a -1.0)
        yy (compensated-dot b b -1.0)
        zz (compensated-dot c c -1.0)
        xy (compensated-dot a b 0.0)
        xz (compensated-dot a c 0.0)
        yz (compensated-dot b c 0.0)]
    (Matrix {:c0 (p/v xx xy xz) :c1 (p/v xy yy yz) :c2 (p/v xz yz zz)})))

(az/defn logarithm-remainder :f64
  "z-log(1+z) without subtracting first-order terms; caller keeps |z| below 0.125." [[z :f64]]
  (let [^:var result (ak/f64 (/ 1.0 24.0))
        ^:var index (ak/u32 23)]
    (while (>= index 2)
      (set! result (- (/ 1.0 (ak/as (ak/floatFromInt index) :f64)) (* z result)))
      (ak/-= index 1))
    (* z z result)))

(az/defn strain-energy-from-metric :f64
  "Equivalent energy near any proper rotation, expressed in second-order strains.
  The original signed-J formula remains valid for large strains and inversion."
  [[strain Matrix] [parameters Material] [invariant :f64] [jacobian :f64]]
  (let [mu (az/field parameters mu)
        lambda (az/field parameters lambda)
        rest-shift (- 1.0 (az/field parameters alpha))
        volume-shift (- jacobian (az/field parameters alpha))]
    (when (and (> jacobian 0.0) (< (inner strain strain) 0.0625))
      (let [a (az/field strain c0)
            b (az/field strain c1)
            c (az/field strain c2)
            q (+ (az/field a x) (az/field b y) (az/field c z))
            second (- (+ (* (az/field a x) (az/field b y))
                         (* (az/field b y) (az/field c z))
                         (* (az/field c z) (az/field a x)))
                      (+ (* (az/field a y) (az/field a y))
                         (* (az/field a z) (az/field a z))
                         (* (az/field b z) (az/field b z))))
            higher (+ second (determinant strain))
            determinant-change (+ q higher)
            denominator (+ 1.0 (ak/sqrt (+ 1.0 determinant-change)))
            s (/ determinant-change denominator)
            q-minus-two-s (/ (- (* q s) (* 2.0 higher)) denominator)
            linear-correction (compensated-dot (p/v mu lambda 0.0)
                                                (p/v 0.75 rest-shift 0.0) 0.0)]
        (ak/return (+ (* 0.375 mu q-minus-two-s)
                      (* 0.5 mu (logarithm-remainder (* 0.25 q)))
                      (* 0.5 lambda s s)
                      (* linear-correction s)))))
    (- (+ (* 0.5 mu (- invariant 3.0))
          (* 0.5 lambda (- (* volume-shift volume-shift) (* rest-shift rest-shift))))
       (* 0.5 mu (ak/log (/ (+ invariant 1.0) 4.0))))))

(az/defn strain-energy-density :f64
  [[deformation Matrix] [parameters Material] [invariant :f64] [jacobian :f64]]
  (strain-energy-from-metric (metric-strain deformation) parameters invariant jacobian))

(az/defn evaluate Response
  [[deformation Matrix] [parameters Material]]
  (let [invariant (inner deformation deformation)
        jacobian (determinant deformation)
        mu (az/field parameters mu)
        lambda (az/field parameters lambda)
        volume-shift (- jacobian (az/field parameters alpha))
        shear (* mu (- 1.0 (/ 1.0 (+ invariant 1.0))))
        pressure (* lambda volume-shift)]
    (Response {:energy-density (strain-energy-density deformation parameters invariant jacobian)
               :jacobian jacobian
               :pk1 (add (scale deformation shear) (scale (cofactor deformation) pressure))})))

(az/defn evaluate-gradient Response
  "Evaluate F=I+H without discarding small H in energy/stress near identity.
  Algebraically identical to evaluate; large gradients use the general path." [[h Matrix] [parameters Material]]
  (let [f (add (identity) h)]
    (when (>= (inner h h) 0.0625) (ak/return (evaluate f parameters)))
    (let [a (az/field h c0)
          b (az/field h c1)
          c (az/field h c2)
          transpose (Matrix {:c0 (p/v (az/field a x) (az/field b x) (az/field c x))
                             :c1 (p/v (az/field a y) (az/field b y) (az/field c y))
                             :c2 (p/v (az/field a z) (az/field b z) (az/field c z))})
          metric (add (add h transpose)
                      (Matrix {:c0 (p/v (p/dot a a) (p/dot a b) (p/dot a c))
                               :c1 (p/v (p/dot b a) (p/dot b b) (p/dot b c))
                               :c2 (p/v (p/dot c a) (p/dot c b) (p/dot c c))}))
          trace (+ (az/field a x) (az/field b y) (az/field c z))
          cof (cofactor h)
          determinant-change (+ trace (az/field (az/field cof c0) x)
                                (az/field (az/field cof c1) y) (az/field (az/field cof c2) z)
                                (determinant h))
          invariant-change (+ (* 2.0 trace) (inner h h))
          cofactor-change (add (add (scale (identity) trace) (scale transpose -1.0)) cof)
          mu (az/field parameters mu)
          lambda (az/field parameters lambda)
          rest-shift (- 1.0 (az/field parameters alpha))
          rest-stress (compensated-dot (p/v mu lambda 0.0) (p/v 0.75 rest-shift 0.0) 0.0)
          shear-change (/ (* mu invariant-change) (* 4.0 (+ 4.0 invariant-change)))
          shear (+ (* 0.75 mu) shear-change)
          pressure (* lambda (+ rest-shift determinant-change))]
      (Response
        {:jacobian (+ 1.0 determinant-change)
         :energy-density (strain-energy-from-metric metric parameters (+ 3.0 invariant-change) (+ 1.0 determinant-change))
         :pk1 (add (scale (identity) (+ rest-stress shear-change (* lambda determinant-change)))
                   (add (scale h shear) (scale cofactor-change pressure)))}))))

(az/defn cofactor-differential Matrix
  [[f Matrix] [h Matrix]]
  (Matrix {:c0 (p/add (p/cross (az/field h c1) (az/field f c2))
                      (p/cross (az/field f c1) (az/field h c2)))
           :c1 (p/add (p/cross (az/field h c2) (az/field f c0))
                      (p/cross (az/field f c2) (az/field h c0)))
           :c2 (p/add (p/cross (az/field h c0) (az/field f c1))
                      (p/cross (az/field f c0) (az/field h c1)))}))

(az/defn tangent Matrix
  "Directional derivative dP(F)[H]; no finite differencing in the solver."
  [[deformation Matrix] [direction Matrix] [parameters Material]]
  (let [denominator (+ 1.0 (inner deformation deformation))
        cofactors (cofactor deformation)
        mu (az/field parameters mu)
        lambda (az/field parameters lambda)
        shear (* mu (- 1.0 (/ 1.0 denominator)))
        shear-change (/ (* 2.0 mu (inner deformation direction)) (* denominator denominator))
        pressure (* lambda (- (determinant deformation) (az/field parameters alpha)))
        pressure-change (* lambda (inner cofactors direction))]
    (add (add (scale direction shear) (scale deformation shear-change))
         (add (scale cofactors pressure-change)
              (scale (cofactor-differential deformation direction) pressure)))))
