(ns learn.example.test-comptime-shlExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-high-bits
  (let [alternating-bits (k/u8 2r01010101)
        shifted-bits (k/shlExact alternating-bits 2)]
    (k/= :_ shifted-bits)))
