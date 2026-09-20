(ns learn.example.float-mode-obj
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst big (ak/as :f64 (az/op "<<" 1 40)))

(az/defn foo_strict :f64
  {:attrs #{:export}}
  [[x :f64]]
  (- (+ x big) big))

(az/defn foo_optimized :f64
  {:attrs #{:export}}
  [[x :f64]]
  (ak/setFloatMode :.optimized)
  (- (+ x big) big))

(comment
  (foo_strict 0.001)
  (foo_optimized 0.001))
