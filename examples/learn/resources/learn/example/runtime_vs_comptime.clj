(ns learn.example.runtime-vs-comptime
  (:require [aguafria.zig :as az]))

(az/defn- divide :i32
  [[a :i32] [b :i32]]
  (/ a b))

(comment
  (divide 12 3))
