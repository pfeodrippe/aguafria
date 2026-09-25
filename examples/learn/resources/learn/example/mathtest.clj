(ns learn.example.mathtest
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn add :i32
  {:attrs #{k/export}}
  [[a :i32] [b :i32]]
  (k/+ a b))

(comment
  (add 12 34))
