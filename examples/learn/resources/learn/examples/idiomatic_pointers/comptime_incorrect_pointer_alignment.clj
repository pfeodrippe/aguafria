(ns learn.examples.idiomatic-pointers.comptime-incorrect-pointer-alignment
  "Converted from test_comptime_incorrect_pointer_alignment.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-unaligned-address
  (let [^{:zig/type [:pointer {:size :one :align 1} :i32]}
        byte-aligned (ak/ptrFromInt 0x1)
        ;; Intentionally invalid: address 1 does not satisfy four-byte alignment.
        ^{:zig/type [:pointer {:size :one :align 4} :i32]}
        word-aligned (ak/alignCast byte-aligned)]
    (set! _ word-aligned)))
