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

(az/defn material
  :- Material
  [[young :f64] [poisson :f64]]
  (let [shear (/ young (* 2.0 (+ 1.0 poisson)))
        lame (/ (* young poisson) (* (+ 1.0 poisson) (- 1.0 (* 2.0 poisson))))
        mu (* (/ 4.0 3.0) shear)
        lambda (+ lame (* (/ 5.0 6.0) shear))]
    (Material {:mu mu :lambda lambda :alpha (+ 1.0 (/ (* 0.75 mu) lambda))})))

(az/defn identity
  :- Matrix
  []
  (Matrix {:c0 (p/v 1.0 0.0 0.0) :c1 (p/v 0.0 1.0 0.0) :c2 (p/v 0.0 0.0 1.0)}))

(az/defn zero
  :- Matrix
  []
  (Matrix {:c0 (p/v 0.0 0.0 0.0) :c1 (p/v 0.0 0.0 0.0) :c2 (p/v 0.0 0.0 0.0)}))

(az/defn scale
  :- Matrix
  [[matrix Matrix] [factor :f64]]
  (Matrix {:c0 (p/scale (az/field matrix c0) factor)
           :c1 (p/scale (az/field matrix c1) factor)
           :c2 (p/scale (az/field matrix c2) factor)}))

(az/defn add
  :- Matrix
  [[a Matrix] [b Matrix]]
  (Matrix {:c0 (p/add (az/field a c0) (az/field b c0))
           :c1 (p/add (az/field a c1) (az/field b c1))
           :c2 (p/add (az/field a c2) (az/field b c2))}))

(az/defn inner
  :- :f64
  [[a Matrix] [b Matrix]]
  (+ (p/dot (az/field a c0) (az/field b c0))
     (p/dot (az/field a c1) (az/field b c1))
     (p/dot (az/field a c2) (az/field b c2))))

(az/defn apply-vector
  :- p/Vec3
  [[matrix Matrix] [vector p/Vec3]]
  (p/add (p/add (p/scale (az/field matrix c0) (az/field vector x))
                (p/scale (az/field matrix c1) (az/field vector y)))
         (p/scale (az/field matrix c2) (az/field vector z))))

(az/defn outer
  :- Matrix
  [[a p/Vec3] [b p/Vec3]]
  (Matrix {:c0 (p/scale a (az/field b x))
           :c1 (p/scale a (az/field b y))
           :c2 (p/scale a (az/field b z))}))

(az/defn cofactor
  :- Matrix
  [[deformation Matrix]]
  (Matrix {:c0 (p/cross (az/field deformation c1) (az/field deformation c2))
           :c1 (p/cross (az/field deformation c2) (az/field deformation c0))
           :c2 (p/cross (az/field deformation c0) (az/field deformation c1))}))

(az/defn determinant
  :- :f64
  [[deformation Matrix]]
  (p/dot (az/field deformation c0)
         (p/cross (az/field deformation c1) (az/field deformation c2))))

(az/defn evaluate
  :- Response
  [[deformation Matrix] [parameters Material]]
  (let [invariant (inner deformation deformation)
        jacobian (determinant deformation)
        mu (az/field parameters mu)
        lambda (az/field parameters lambda)
        rest-shift (- 1.0 (az/field parameters alpha))
        volume-shift (- jacobian (az/field parameters alpha))
        shear (* mu (- 1.0 (/ 1.0 (+ invariant 1.0))))
        pressure (* lambda volume-shift)]
    (Response {:energy-density
               (- (+ (* 0.5 mu (- invariant 3.0))
                     (* 0.5 lambda (- (* volume-shift volume-shift) (* rest-shift rest-shift))))
                  (* 0.5 mu (ak/log (/ (+ invariant 1.0) 4.0))))
               :jacobian jacobian
               :pk1 (add (scale deformation shear) (scale (cofactor deformation) pressure))})))

(az/defn cofactor-differential
  :- Matrix
  [[f Matrix] [h Matrix]]
  (Matrix {:c0 (p/add (p/cross (az/field h c1) (az/field f c2))
                      (p/cross (az/field f c1) (az/field h c2)))
           :c1 (p/add (p/cross (az/field h c2) (az/field f c0))
                      (p/cross (az/field f c2) (az/field h c0)))
           :c2 (p/add (p/cross (az/field h c0) (az/field f c1))
                      (p/cross (az/field f c0) (az/field h c1)))}))

(az/defn tangent
  "Directional derivative dP(F)[H]; no finite differencing in the solver."
  :- Matrix
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
