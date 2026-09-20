(ns learn.example.test-void-ignored
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest void-ignored-test
  (returns-void))

(az/deftest explicit-discard-test
  (ak/= :_ (foo)))

(az/defn- returns-void :void [])

(az/defn- foo :i32
  []
  1234)

(comment
  (void-ignored-test)
  (explicit-discard-test))
