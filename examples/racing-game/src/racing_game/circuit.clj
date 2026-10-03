(ns racing-game.circuit
  "Metre-based, arc-length-parameterized circuit exported from Blender."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(let [{:keys [length-metres samples]} (edn/read-string
                                     (slurp (io/resource "geometry/circuit.edn")))]
  (when-not (and (= 4309.0 length-metres) (> (count samples) 100)
                 (every? #(and (= 4 (count %))
                               (every? (fn [n] (and (number? n) (Double/isFinite (double n)))) %)) samples)
                 (apply < (map last samples)))
    (throw (ex-info "Invalid metre-based Blender circuit export" {})))
  (eval `(a/defconst ~'centerline [:array ~(count samples) [:array 4 :f32]] ~samples))
  (eval `(a/defconst ~'sample-count :usize ~(count samples)))
  (eval `(a/defconst ~'minimum-elevation :f32 ~(apply min (map #(nth % 2) samples)))))

(a/defconst length-metres :f32 4309.0)

(a/defconst road-width-metres :f32 13.0)

(a/defstruct Sample {:layout :extern}
  [[:x :f32] [:y :f32] [:z :f32] [:heading :f32]])

(a/defn tangent :f32
  "Arc-distance derivative at an authored knot, including the closed seam." [[index :usize] [axis :usize]]
  (let [i (if (ak/== index (- sample-count 1)) (ak/as 0 :usize) index)
        previous (a/index centerline (if (ak/== i 0) (- sample-count 2) (- i 1)))
        following (a/index centerline (+ i 1))
        span (- (a/index following 3)
                (- (a/index previous 3) (if (ak/== i 0) length-metres 0.0)))]
    (/ (- (a/index following axis) (a/index previous axis)) span)))

(a/defn hermite :f32 [[a :f32] [b :f32] [ma :f32] [mb :f32] [t :f32]]
  (let [t2 (* t t) t3 (* t2 t)]
    (+ (* (+ (- (* 2.0 t3) (* 3.0 t2)) 1.0) a)
       (* (+ (- t3 (* 2.0 t2)) t) ma)
       (* (+ (* -2.0 t3) (* 3.0 t2)) b)
       (* (- t3 t2) mb))))

(a/defn hermite-derivative :f32 [[a :f32] [b :f32] [ma :f32] [mb :f32] [t :f32]]
  (+ (* (- (* 6.0 t t) (* 6.0 t)) a)
     (* (+ (- (* 3.0 t t) (* 4.0 t)) 1.0) ma)
     (* (+ (* -6.0 t t) (* 6.0 t)) b)
     (* (- (* 3.0 t t) (* 2.0 t)) mb)))

(a/defn at-distance Sample
  "C1-continuous interpolation of Blender's authored knots. Position and
  heading share the same curve derivative; lane offsets no longer jump at
  each polyline boundary. Distance remains the authored arc-distance coordinate." [[distance :f32] [lane :f32]]
  (let [d (mod distance length-metres)
        ^:var low (ak/usize 0)
        ^:var high (ak/usize (- sample-count 1))]
    (while (> (- high low) 1)
      (let [middle (ak/divTrunc (+ low high) 2)]
        (if (> (a/index (a/index centerline middle) 3) d)
          (ak/= high middle)
          (ak/= low middle))))
    (let [a (a/index centerline low) b (a/index centerline high)
          span (- (a/index b 3) (a/index a 3))
          t (/ (- d (a/index a 3)) span)
          ax (* span (tangent low 0)) bx (* span (tangent high 0))
          ay (* span (tangent low 1)) by (* span (tangent high 1))
          dx (hermite-derivative (a/index a 0) (a/index b 0) ax bx t)
          dy (hermite-derivative (a/index a 1) (a/index b 1) ay by t)
          heading (math/atan2 dy dx)]
      (Sample {:x (- (hermite (a/index a 0) (a/index b 0) ax bx t) (* (math/sin heading) lane))
               :y (+ (hermite (a/index a 1) (a/index b 1) ay by t) (* (math/cos heading) lane))
               :z (hermite (a/index a 2) (a/index b 2)
                           (* span (tangent low 2)) (* span (tangent high 2)) t)
               :heading heading}))))

(a/defn progress-after :f32
  "Convert physical travel into lap progress; 300 km/h is 83.333 m/s." [[progress :f32] [speed-metres-per-second :f32] [seconds :f32]]
  (+ progress (/ (* speed-metres-per-second seconds) length-metres)))

(a/defn kilometres-per-hour :f32 [[metres-per-second :f32]]
  (* metres-per-second 3.6))
