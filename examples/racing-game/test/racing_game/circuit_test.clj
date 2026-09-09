(ns racing-game.circuit-test
  (:require [aguafria.zig :as az]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [racing-game.circuit :as circuit]))

(deftest metre-scale-blender-circuit-test
  (let [{:keys [samples length-metres]} (edn/read-string
                                        (slurp (io/resource "geometry/circuit.edn")))
        segment-length (fn [[a b]]
                         (Math/sqrt (reduce + (map #(Math/pow (- %1 %2) 2)
                                                  (take 3 a) (take 3 b)))))
        measured (reduce + (map segment-length (partition 2 1 samples)))]
    (is (< (abs (- length-metres measured)) 0.01))
    (is (= (take 3 (first samples)) (take 3 (last samples))))
    (is (= 13.0 circuit/road-width-metres))
    (is (< (abs (- 300.0 (circuit/kilometres-per-hour (/ 300.0 3.6)))) 0.001))
    (is (< (abs (- 1.0 (circuit/progress-after 0.0 83.333333 51.708))) 0.00001))
    (doseq [distance [0.0 100.0 1234.5 4308.0]]
      (let [a (az/value (circuit/at-distance distance 0.0))
            b (az/value (circuit/at-distance distance 6.5))
            wrapped (az/value (circuit/at-distance (+ distance 4309.0) 0.0))]
        (is (< (abs (- 6.5 (Math/hypot (- (:x b) (:x a)) (- (:y b) (:y a))))) 0.001))
        (is (< (Math/hypot (- (:x a) (:x wrapped)) (- (:y a) (:y wrapped))) 0.001))))))

(deftest continuous-knot-heading-and-offset-test
  (let [samples (:samples (edn/read-string (slurp (io/resource "geometry/circuit.edn"))))
        angle-gap (fn [a b] (abs (Math/atan2 (Math/sin (- a b)) (Math/cos (- a b)))))]
    ;; Include the tight reported bend, every authored knot, and the lap seam.
    (doseq [distance (map last (butlast samples))]
      (let [a (az/value (circuit/at-distance (- distance 0.002) 5.0))
            b (az/value (circuit/at-distance (+ distance 0.002) 5.0))]
        (is (< (angle-gap (:heading a) (:heading b)) 0.002)
            (str "Heading jumps at authored distance " distance))
        (is (< (Math/hypot (- (:x b) (:x a)) (- (:y b) (:y a))) 0.02)
            (str "Offset route jumps at authored distance " distance))))))
