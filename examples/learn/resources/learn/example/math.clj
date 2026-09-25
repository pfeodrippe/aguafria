(ns learn.example.math
  (:refer-clojure :exclude [print])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defextern print :void
  [[value :i32]])

(az/defn add :void
  {:attrs #{k/export}}
  [[a :i32] [b :i32]]
  (print (k/+ a b)))

(comment
  (add 12 34))
