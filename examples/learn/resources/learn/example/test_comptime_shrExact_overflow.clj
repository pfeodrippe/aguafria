(ns learn.example.test-comptime-shrExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-low-bits
  (let [alternating-bits (k/u8 2r10101010)
        shifted-bits (k/shrExact alternating-bits 2)]
    (k/= :_ shifted-bits)))
