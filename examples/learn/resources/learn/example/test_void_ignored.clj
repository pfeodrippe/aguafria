(ns learn.example.test-void-ignored
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- returns-void :void [])

(az/defn- foo :i32
  []
  1234)

(az/deftest void-ignored-test
  (returns-void))

(az/deftest explicit-discard-test
  (ak/= :_ (foo)))

(comment
  (void-ignored-test)
  (explicit-discard-test))
