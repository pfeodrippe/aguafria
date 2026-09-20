(ns learn.example.test-comptime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-unaligned-address
  (let [^{:zig/type [:pointer {:size :one :align 1} :i32]}
        byte-aligned (ak/ptrFromInt 0x1)
        ;; Intentionally invalid: address 1 does not satisfy four-byte alignment.
        ^{:zig/type [:pointer {:size :one :align 4} :i32]}
        word-aligned (ak/alignCast byte-aligned)]
    (set! _ word-aligned)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
