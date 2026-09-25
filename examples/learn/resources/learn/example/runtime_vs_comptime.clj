(ns learn.example.runtime-vs-comptime
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- divide :i32
  [[a :i32] [b :i32]]
  (k// a b))

(comment
  (divide 12 3))
