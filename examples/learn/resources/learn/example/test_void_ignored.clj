(ns learn.example.test-void-ignored
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- returns-void :void [])

(a/defn- foo :i32
  []
  1234)

(a/deftest void-is-ignored
  (returns-void))

(a/deftest explicitly-ignoring-expression-value
  (= :_ (foo)))

(comment
  (void-is-ignored)
  (explicitly-ignoring-expression-value))
