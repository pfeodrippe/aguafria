(ns learn.example.test-comptime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-unaligned-address
  (let [byte-aligned (k/as (k/ptrFromInt 0x1) [:pointer {:align 1, :size :one} :i32])
        ;; Intentionally invalid: address 1 does not satisfy four-byte alignment.
        word-aligned (k/as (k/alignCast byte-aligned) [:pointer {:align 4, :size :one} :i32])]
    (k/= :_ word-aligned)))
