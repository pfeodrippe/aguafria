(ns learn.example.test-expression-ignored
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn- foo :i32
  []
  1234)

(a/deftest ignoring-expression-value
  (foo))

(comment
  (ignoring-expression-value))
