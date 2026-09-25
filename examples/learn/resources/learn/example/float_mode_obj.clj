(ns learn.example.float-mode-obj
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst big (k/as (az/op "<<" 1 40) :f64))

(az/defn foo_strict :f64
  {:attrs #{k/export}}
  [[x :f64]]
  (k/- (k/+ x big) big))

(az/defn foo_optimized :f64
  {:attrs #{k/export}}
  [[x :f64]]
  (k/setFloatMode :.optimized)
  (k/- (k/+ x big) big))

(comment
  (foo_strict 0.001)
  (foo_optimized 0.001))
