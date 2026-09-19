(ns learn.examples.idiomatic-safety.comptime-shrExact-overflow
  "Converted from test_comptime_shrExact_overflow.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-low-bits
  (let [^{:zig/type :u8} alternating-bits 2r10101010
        shifted-bits (ak/shrExact alternating-bits 2)]
    (set! _ shifted-bits)))
