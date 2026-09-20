(ns learn.example.test-expression-ignored
  (:require [aguafria.zig :as az]))

(az/deftest ignored-value-test
  (foo))

(az/defn- foo :i32
  []
  1234)

(comment
  (ignored-value-test))
