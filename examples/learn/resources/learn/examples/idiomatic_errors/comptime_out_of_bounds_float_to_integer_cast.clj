(ns learn.examples.idiomatic-errors.comptime-out-of-bounds-float-to-integer-cast
  "Converted from test_comptime_out_of_bounds_float_to_integer_cast.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-out-of-range-float
  (let [^{:zig/type :f32} float-value 4294967296
        ^{:zig/type :i32} integer-value (ak/intFromFloat float-value)]
    (set! _ integer-value)))
