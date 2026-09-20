(ns learn.example.test-comptime-shrExact-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-low-bits
  (let [alternating-bits (ak/u8 2r10101010)
        shifted-bits (ak/shrExact alternating-bits 2)]
    (ak/= :_ shifted-bits)))
