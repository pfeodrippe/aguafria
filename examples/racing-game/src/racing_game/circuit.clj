(ns racing-game.circuit
  "Metre-based, arc-length-parameterized circuit exported from Blender."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(let [{:keys [length-metres samples]} (edn/read-string
                                     (slurp (io/resource "geometry/circuit.edn")))]
  (when-not (and (= 4309.0 length-metres) (> (count samples) 100)
                 (every? #(and (= 4 (count %))
                               (every? (fn [n] (and (number? n) (Double/isFinite (double n)))) %)) samples)
                 (apply < (map last samples)))
    (throw (ex-info "Invalid metre-based Blender circuit export" {})))
  (eval `(az/defconst ~'centerline [:array ~(count samples) [:array 4 :f32]] ~samples))
  (eval `(az/defconst ~'sample-count :usize ~(count samples)))
  (eval `(az/defconst ~'minimum-elevation :f32 ~(apply min (map #(nth % 2) samples)))))

(az/defconst length-metres :f32 4309.0)

(az/defconst road-width-metres :f32 13.0)

(az/defstruct Sample {:layout :extern}
  [[:x :f32] [:y :f32] [:z :f32] [:heading :f32]])

(az/defn tangent
  "Arc-distance derivative at an authored knot, including the closed seam."
  :- :f32 [[index :usize] [axis :usize]]
  (let [i (if (ak/== index (- sample-count 1)) (ak/as :usize 0) index)
        previous (az/index centerline (if (ak/== i 0) (- sample-count 2) (- i 1)))
        following (az/index centerline (+ i 1))
        span (- (az/index following 3)
                (- (az/index previous 3) (if (ak/== i 0) length-metres 0.0)))]
    (/ (- (az/index following axis) (az/index previous axis)) span)))

(az/defn hermite
  :- :f32 [[a :f32] [b :f32] [ma :f32] [mb :f32] [t :f32]]
  (let [t2 (* t t) t3 (* t2 t)]
    (+ (* (+ (- (* 2.0 t3) (* 3.0 t2)) 1.0) a)
       (* (+ (- t3 (* 2.0 t2)) t) ma)
       (* (+ (* -2.0 t3) (* 3.0 t2)) b)
       (* (- t3 t2) mb))))

(az/defn hermite-derivative
  :- :f32 [[a :f32] [b :f32] [ma :f32] [mb :f32] [t :f32]]
  (+ (* (- (* 6.0 t t) (* 6.0 t)) a)
     (* (+ (- (* 3.0 t t) (* 4.0 t)) 1.0) ma)
     (* (+ (* -6.0 t t) (* 6.0 t)) b)
     (* (- (* 3.0 t t) (* 2.0 t)) mb)))

(az/defn at-distance
  "C1-continuous interpolation of Blender's authored knots. Position and
  heading share the same curve derivative; lane offsets no longer jump at
  each polyline boundary. Distance remains the authored arc-distance coordinate."
  :- Sample [[distance :f32] [lane :f32]]
  (let [d (mod distance length-metres)
        ^{:var :usize} low 0
        ^{:var :usize} high (- sample-count 1)]
    (while (> (- high low) 1)
      (let [middle (ak/divTrunc (+ low high) 2)]
        (if (> (az/index (az/index centerline middle) 3) d)
          (set! high middle)
          (set! low middle))))
    (let [a (az/index centerline low) b (az/index centerline high)
          span (- (az/index b 3) (az/index a 3))
          t (/ (- d (az/index a 3)) span)
          ax (* span (tangent low 0)) bx (* span (tangent high 0))
          ay (* span (tangent low 1)) by (* span (tangent high 1))
          dx (hermite-derivative (az/index a 0) (az/index b 0) ax bx t)
          dy (hermite-derivative (az/index a 1) (az/index b 1) ay by t)
          heading (math/atan2 dy dx)]
      (Sample {:x (- (hermite (az/index a 0) (az/index b 0) ax bx t) (* (math/sin heading) lane))
               :y (+ (hermite (az/index a 1) (az/index b 1) ay by t) (* (math/cos heading) lane))
               :z (hermite (az/index a 2) (az/index b 2)
                           (* span (tangent low 2)) (* span (tangent high 2)) t)
               :heading heading}))))

(az/defn progress-after
  "Convert physical travel into lap progress; 300 km/h is 83.333 m/s."
  :- :f32 [[progress :f32] [speed-metres-per-second :f32] [seconds :f32]]
  (+ progress (/ (* speed-metres-per-second seconds) length-metres)))

(az/defn kilometres-per-hour :- :f32 [[metres-per-second :f32]]
  (* metres-per-second 3.6))
