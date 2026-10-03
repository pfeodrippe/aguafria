(ns learn.example.float-mode-obj
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst big (k/as (k/<< 1 40) :f64))

(a/defn foo_strict :f64
  {:attrs #{k/export}}
  [[x :f64]]
  (k/- (k/+ x big) big))

(a/defn foo_optimized :f64
  {:attrs #{k/export}}
  [[x :f64]]
  (k/setFloatMode :.optimized)
  (k/- (k/+ x big) big))

(comment
  (foo_strict 0.001)
  (foo_optimized 0.001))
