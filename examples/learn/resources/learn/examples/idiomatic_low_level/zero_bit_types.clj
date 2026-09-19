(ns learn.examples.idiomatic-low-level.zero-bit-types
  "Converted from zero_bit_types.zig"
  (:require [aguafria.zig :as az]))

(az/defn entry :void {:attrs #{:export}} []
  (let [^{:var :void} first-value (az/block)
        ^{:var :void} second-value (az/block)]
    (set! first-value second-value)
    (set! second-value first-value)))
