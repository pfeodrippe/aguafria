(ns learn.examples.idiomatic-errors.comptime-invalid-cast-truncate
  "Converted from test_comptime_invalid_cast_truncate.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; intCast checks representability; unlike truncate, it does not discard bits.
(az/defcomptime reject-too-large-byte
  (let [^{:zig/type :u16} spartan-count 300
        ^{:zig/type :u8} byte (ak/intCast spartan-count)]
    (set! _ byte)))
