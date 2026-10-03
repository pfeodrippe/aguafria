(ns learn.example.test-comptime-shrExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defcomptime reject-lost-low-bits
  (let [x (k/shrExact (k/u8 2r10101010) 2)]
    (k/= :_ x)))
