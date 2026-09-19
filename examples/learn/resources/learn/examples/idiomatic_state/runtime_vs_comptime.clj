(ns learn.examples.idiomatic-state.runtime-vs-comptime
  "Converted from runtime_vs_comptime.zig"
  (:require [aguafria.zig :as az]))

(az/defn- divide :i32
  [[a :i32] [b :i32]]
  (/ a b))
