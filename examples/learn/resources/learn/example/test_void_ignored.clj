(ns learn.example.test-void-ignored
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- returns-void :void [])

(az/defn- foo :i32
  []
  1234)

(az/deftest void-is-ignored
  (returns-void))

(az/deftest explicitly-ignoring-expression-value
  (k/= :_ (foo)))

(comment
  (void-is-ignored)
  (explicitly-ignoring-expression-value))
