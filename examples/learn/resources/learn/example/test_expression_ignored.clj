(ns learn.example.test-expression-ignored
  (:require [aguafria.zig :as a]))

(a/defn- foo :i32
  []
  1234)

(a/deftest ignoring-expression-value
  (foo))

(comment
  (ignoring-expression-value))
