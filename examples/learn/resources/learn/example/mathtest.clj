(ns learn.example.mathtest
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn add :i32
  {:attrs #{ak/export}}
  [[a :i32] [b :i32]]
  (+ a b))

(comment
  (add 12 34))
