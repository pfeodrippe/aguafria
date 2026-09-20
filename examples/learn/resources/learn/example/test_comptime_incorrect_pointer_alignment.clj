(ns learn.example.test-comptime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-unaligned-address
  (let [byte-aligned (ak/as (ak/ptrFromInt 0x1) [:pointer {:align 1, :size :one} :i32])
        ;; Intentionally invalid: address 1 does not satisfy four-byte alignment.
        word-aligned (ak/as (ak/alignCast byte-aligned) [:pointer {:align 4, :size :one} :i32])]
    (ak/= :_ word-aligned)))
