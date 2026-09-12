(ns field-lab.ball-fem-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.soft-body :as soft]
            [field-lab.soft-mesh :as mesh]
            [field-lab.ball-fem :as fem]))

(az/defstruct Report {:layout :extern}
  [[:completed :bool] [:minimum-jacobian :f64] [:minimum-height :f64]
   [:minimum-clearance :f64] [:peak-elastic :f64] [:initial-energy :f64]
   [:maximum-energy :f64] [:momentum-error :f64] [:final-vx :f64]
   [:substeps :u32]])

(az/defn experiment
  :- Report
  [[active :u32] [steps :u32]]
  (let [^:var config (p/defaults)]
    (az/set-many!
      (az/field config radius) 0.1
      (az/field config mass) (* 1100.0 mesh/unit-volume 0.001)
      (az/field config height) 0.5
      (az/field config gravity) (if (ak/== active 1) 9.81 0.0)
      (az/field config vx) 0.0
      (az/field config vz) 0.0
      (az/field config spin) 0.0)
    (let [^:var state (p/initial config)
          ^{:var soft/Sample} sample ak/undefined
          ^:var report (Report {:completed true :minimum-jacobian 1.0 :minimum-height 1.0
                                :minimum-clearance 1.0 :peak-elastic 0.0 :initial-energy 0.0
                                :maximum-energy 0.0 :momentum-error 0.0 :final-vx 0.0 :substeps 0})]
      (dotimes [body 3]
        (az/set-many!
          (az/field state position)
          (p/v (if (ak/== body 0) -0.6 (if (ak/== body 1) 0.0 0.6)) 0.6 0.0)
          (az/field state velocity)
          (p/v (if (ak/== active 1) 0.0 (if (ak/== body 0) 1.0 (if (ak/== body 2) -1.0 0.0))) 0.0 0.0)
          (az/index (az/field sample bodies) body) (soft/initial state config)))
      (dotimes [body active]
        (ak/+= (az/field report initial-energy)
               (fem/energy (az/index (az/field sample bodies) body) config 100000.0)))
      (set! (az/field report maximum-energy) (az/field report initial-energy))
      (dotimes [_ steps]
        (let [next (fem/advance sample config active 100000.0 (/ 1.0 240.0))
              ^{:var :f64} energy 0.0
              ^:var momentum (p/v 0.0 0.0 0.0)]
          (when (ak/! (az/field next completed))
            (set! (az/field report completed) false)
            (ak/return report))
          (az/set-many!
            sample (az/field next sample)
            (az/field report substeps) (+ (az/field report substeps) (az/field next substeps))
            (az/field report minimum-jacobian)
            (ak/min (az/field report minimum-jacobian) (az/field next minimum-jacobian)))
          (dotimes [body active]
            (let [current (az/index (az/field sample bodies) body)]
              (az/set-many!
                energy (+ energy (fem/energy current config 100000.0))
                momentum (p/add momentum (p/scale (soft/velocity current) (az/field config mass)))
                (az/field report minimum-height) (ak/min (az/field report minimum-height) (soft/height current))
                (az/field report minimum-clearance) (ak/min (az/field report minimum-clearance) (soft/clearance current))
                (az/field report peak-elastic) (ak/max (az/field report peak-elastic)
                                                    (fem/elastic-energy current config 100000.0)))))
          (az/set-many!
            (az/field report maximum-energy) (ak/max (az/field report maximum-energy) energy)
            (az/field report momentum-error) (ak/max (az/field report momentum-error) (p/length momentum)))))
      (set! (az/field report final-vx) (az/field (soft/velocity (az/index (az/field sample bodies) 0)) x))
      report)))

(deftest continuum-ball-drop-compresses-and-rebounds
  (let [report (az/value (experiment 1 240))]
    (is (:completed report) (pr-str report))
    (is (< (:minimum-height report) 0.18))
    (is (zero? (:minimum-clearance report)))
    (is (> (:peak-elastic report) 0.5))
    (is (> (:minimum-jacobian report) 0.5))
    (is (< (:maximum-energy report) (* 1.005 (:initial-energy report))))))

(deftest three-continuum-balls-transfer-momentum
  (let [report (az/value (experiment 3 180))]
    (is (:completed report) (pr-str report))
    (is (< (:final-vx report) -0.1) (pr-str report))
    (is (< (:momentum-error report) 1.0e-7) (pr-str report))
    (is (> (:peak-elastic report) 0.1))
    (is (> (:minimum-jacobian report) 0.6))
    (is (< (:maximum-energy report) (* 1.01 (:initial-energy report))) (pr-str report))))
