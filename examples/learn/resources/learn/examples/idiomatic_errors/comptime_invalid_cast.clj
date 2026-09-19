(ns learn.examples.idiomatic-errors.comptime-invalid-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-negative-unsigned-value
  (let [^{:zig/type :i32} signed-value -1
        ^{:zig/type :u32} unsigned-value (ak/intCast signed-value)]
    (set! _ unsigned-value)))
