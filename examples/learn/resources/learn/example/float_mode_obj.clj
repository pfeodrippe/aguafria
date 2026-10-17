(ns learn.example.float-mode-obj
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst big (as (<< 1 40) :f64))

(a/defn foo_strict :f64
  {:attrs #{export}}
  [[x :f64]]
  (- (+ x big) big))

(a/defn foo_optimized :f64
  {:attrs #{export}}
  [[x :f64]]
  (setFloatMode :optimized)
  (- (+ x big) big))

(comment
  (foo_strict 0.001)
  (foo_optimized 0.001))
