(ns field-lab.soft-body-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.soft-body :as soft]
            [field-lab.soft-mesh :as mesh]
            [field-lab.experiments :as experiments]))

(az/defstruct Report
  {:layout :extern}
  [[:minimum-height :f64] [:minimum-volume :f64] [:maximum-energy :f64] [:initial-energy :f64]
   [:final-height :f64] [:final-volume :f64] [:floor :f64] [:time :f64]])

(az/defn drop-config
  :- p/Config
  []
  (let [^:var config (p/defaults)]
    (az/set-many!
      (az/field config vx) 0.0
      (az/field config vz) 0.0
      (az/field config spin) 0.0)
    config))

(az/defn drop-report
  :- Report
  [[steps :u32] [active :u32] [modulus :f64]]
  (let [config (drop-config)
        rigid (experiments/three-balls config)
        ^:var batch (soft/Sample
                     {:bodies [(soft/initial (az/index (az/field rigid bodies) 0) config)
                               (soft/initial (az/index (az/field rigid bodies) 1) config)
                               (soft/initial (az/index (az/field rigid bodies) 2) config)]})
        first (az/index (az/field batch bodies) 0)
        start-energy (soft/energy first config modulus)
        start-volume (soft/volume first)
        ^:var result (Report {:minimum-height (soft/height first)
                              :minimum-volume 1.0
                              :initial-energy start-energy
                              :maximum-energy start-energy
                              :floor 100.0
                              :final-height 0.9
                              :final-volume 1.0
                              :time 0.0})]
    (dotimes [_ steps]
      (set! batch (soft/advance batch config active modulus (/ 1.0 240.0)))
      (let [body (az/index (az/field batch bodies) 0)]
        (az/set-many!
          (az/field result minimum-height)
          (ak/min (az/field result minimum-height)
                  (soft/height body))
          (az/field result minimum-volume)
          (ak/min (az/field result minimum-volume)
                  (/ (soft/volume body)
                     start-volume))
          (az/field result maximum-energy)
          (ak/max
           (az/field result maximum-energy)
           (soft/energy body config modulus))
          (az/field result final-height) (soft/height body)
          (az/field result final-volume) (/ (soft/volume body) start-volume))
        (dotimes [i soft/particle-count]
          (set! (az/field result floor)
                (ak/min (az/field result floor)
                        (az/field (az/index (az/field body positions) i) y))))))
    result))

(deftest mesh-invariants
  (is (= [43 80 162] (mapv #(count (get mesh/mesh %)) [:points :triangles :edges])))
  (is (every? pos? (:volumes mesh/mesh)))
  (is (< (abs (- 1.0 (reduce + (:mass-fractions mesh/mesh)))) 1.0e-12)))

(deftest elastic-impact-and-recovery
  (let [report (az/value (drop-report 480 1 10000.0))]
    (is (< (:minimum-height report) 0.82) (pr-str report))
    (is (> (:minimum-volume report) 0.92) (pr-str report))
    (is (> (:final-height report) (:minimum-height report)))
    (is (< (abs (- 1.0 (:final-volume report))) 0.03))
    (is (>= (:floor report) -1.0e-9))
    (is (< (:maximum-energy report) (* 1.02 (:initial-energy report))) (pr-str report))))

(az/defstruct CollisionReport
  {:layout :extern}
  [[:momentum-error :f64] [:peak-elastic :f64] [:minimum-volume :f64] [:initial-energy :f64]
   [:maximum-energy :f64]])

(az/defn collision-report
  :- CollisionReport
  []
  (let [^:var config (drop-config)]
    (az/set-many!
      (az/field config gravity) 0.0
      (az/field config vx) 1.0)
    (let [rigid (experiments/three-balls config)
          ^:var batch (soft/Sample
                       {:bodies [(soft/initial (az/index (az/field rigid bodies) 0) config)
                                 (soft/initial (az/index (az/field rigid bodies) 1) config)
                                 (soft/initial (az/index (az/field rigid bodies) 2) config)]})
          ^:var result (CollisionReport {:momentum-error 0.0
                                         :peak-elastic 0.0
                                         :minimum-volume 1.0
                                         :initial-energy 0.0
                                         :maximum-energy 0.0})
          rest (soft/volume (az/index (az/field batch bodies) 0))]
      (dotimes [i 3]
        (set! (az/field result initial-energy)
              (+ (az/field result initial-energy)
                 (soft/energy (az/index (az/field batch bodies) i) config 10000.0))))
      (dotimes [_ 360]
        (set! batch (soft/advance batch config 3 10000.0 (/ 1.0 240.0)))
        (let [^:var momentum (p/v 0.0 0.0 0.0)
              ^{:var :f64} total-energy 0.0]
          (dotimes [i 3]
            (let [body (az/index (az/field batch bodies) i)]
              (az/set-many!
                momentum (p/add momentum (p/scale (soft/velocity body) (az/field config mass)))
                total-energy (+ total-energy (soft/energy body config 10000.0))
                (az/field result peak-elastic)
                (ak/max
                 (az/field result peak-elastic)
                 (soft/elastic-energy body config 10000.0))
                (az/field result minimum-volume)
                (ak/min (az/field result minimum-volume)
                        (/ (soft/volume body) rest)))))
          (az/set-many!
            (az/field result momentum-error)
            (ak/max (az/field result momentum-error) (p/length momentum))
            (az/field result maximum-energy)
            (ak/max (az/field result maximum-energy) total-energy))))
      result)))

(deftest three-deformable-balls-exchange-momentum
  (let [report (az/value (collision-report))]
    (is (< (:momentum-error report) 1.0e-7) (pr-str report))
    (is (> (:peak-elastic report) 0.01) (pr-str report))
    (is (> (:minimum-volume report) 0.95) (pr-str report))
    (is (< (:maximum-energy report) (* 1.02 (:initial-energy report))) (pr-str report))))
