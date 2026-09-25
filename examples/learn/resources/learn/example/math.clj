(ns learn.example.math
  (:refer-clojure :exclude [print])
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defextern print :void
  [[value :i32]])

(az/defn add :void
  {:attrs #{ak/export}}
  [[a :i32] [b :i32]]
  (print (+ a b)))

(comment
  (add 12 34))
