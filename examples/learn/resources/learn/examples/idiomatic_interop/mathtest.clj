(ns learn.examples.idiomatic-interop.mathtest
  "Converted from mathtest.zig"
  (:require [aguafria.zig :as az]))

(az/defn add :i32
  {:attrs #{:export}}
  [[a :i32] [b :i32]]
  (+ a b))
