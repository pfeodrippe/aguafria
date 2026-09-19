(ns learn.examples.idiomatic-basics.expression-ignored
  (:require [aguafria.zig :as az]))

(az/deftest ignored-value-test
  (returns-integer))

(az/defn- returns-integer :i32
  []
  1234)
