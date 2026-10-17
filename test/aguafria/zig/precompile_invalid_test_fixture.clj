(ns aguafria.zig.precompile-invalid-test-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn increment :u8
  [[number :u8]]
  (+ number 1))

(a/deftest invalid-literal
  (= :_ (as 256 :u8)))
