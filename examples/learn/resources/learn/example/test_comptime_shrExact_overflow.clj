(ns learn.example.test-comptime-shrExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-low-bits
  (let [x (k/shrExact (k/u8 2r10101010) 2)]
    (k/= :_ x)))
