(ns learn.example.test-comptime-shlExact-overflow
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-lost-high-bits
  (let [x (shlExact (u8 2r01010101) 2)]
    (= :_ x)))
