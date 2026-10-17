(ns learn.example.test-comptime-shrExact-overflow
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-lost-low-bits
  (let [x (shrExact (u8 2r10101010) 2)]
    (= :_ x)))
