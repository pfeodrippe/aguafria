(ns learn.example.test-comptime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-unaligned-address
  (let [ptr (k/as (k/ptrFromInt 0x1) [:* {:align 1} :i32])
        aligned (k/as (k/alignCast ptr) [:* {:align 4} :i32])]
    (k/= :_ aligned)))
