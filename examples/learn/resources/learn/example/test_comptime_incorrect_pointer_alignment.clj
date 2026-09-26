(ns learn.example.test-comptime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-unaligned-address
  (let [ptr (k/as (k/ptrFromInt 0x1) [:pointer {:align 1, :size :one} :i32])
        aligned (k/as (k/alignCast ptr) [:pointer {:align 4, :size :one} :i32])]
    (k/= :_ aligned)))
