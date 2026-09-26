(ns learn.example.test-expression-ignored
  (:require [aguafria.zig :as az]))

(az/defn- foo :i32
  []
  1234)

(az/deftest ignoring-expression-value
  (foo))

(comment
  (ignoring-expression-value))
