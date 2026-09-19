(ns learn.examples.idiomatic-errors.runtime-out-of-bounds-float-to-integer-cast
  "Converted from runtime_out_of_bounds_float_to_integer_cast.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :f32} float-value 4294967296]
    (set! _ (ak/& float-value))
    (let [^{:zig/type :i32} integer-value (ak/intFromFloat float-value)]
      (set! _ integer-value))))
