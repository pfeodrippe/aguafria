(ns learn.examples.idiomatic-basics.expression-ignored
  "Converted from test_expression_ignored.zig"
  (:require [aguafria.zig :as az]))

(az/deftest ignored-value-test
  (returns-integer))

(az/defn- returns-integer :i32
  []
  1234)
