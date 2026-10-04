(ns aguafria.zig.precompile-invalid-test-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn increment :u8
  [[number :u8]]
  (k/+ number 1))

(a/deftest invalid-literal
  (k/= :_ (k/as 256 :u8)))
