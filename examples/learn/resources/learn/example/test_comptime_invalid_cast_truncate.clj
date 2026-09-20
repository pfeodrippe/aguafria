(ns learn.example.test-comptime-invalid-cast-truncate
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; intCast checks representability; unlike truncate, it does not discard bits.
(az/defcomptime reject-too-large-byte
  (let [spartan-count (ak/u16 300)
        byte (ak/u8 (ak/intCast spartan-count))]
    (ak/= :_ byte)))
