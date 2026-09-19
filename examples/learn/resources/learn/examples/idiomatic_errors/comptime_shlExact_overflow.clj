(ns learn.examples.idiomatic-errors.comptime-shlExact-overflow
  "Converted from test_comptime_shlExact_overflow.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-high-bits
  (let [^{:zig/type :u8} alternating-bits 2r01010101
        shifted-bits (ak/shlExact alternating-bits 2)]
    (set! _ shifted-bits)))
