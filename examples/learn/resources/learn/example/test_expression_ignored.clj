(ns learn.example.test-expression-ignored
  (:require [aguafria.zig :as az]))

(az/deftest ignored-value-test
  (returns-integer))

(az/defn- returns-integer :i32
  []
  1234)

(comment
  (ignored-value-test))
