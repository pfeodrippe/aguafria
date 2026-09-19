(ns learn.examples.idiomatic-values.coerce-tuples-arrays
  "Converted from test_coerce_tuples_arrays.zig"
  (:require [aguafria.zig :as az]))

(az/defconst Tuple
  (az/container {:kind :struct}
    (az/tuple-field-decl :u8)
    (az/tuple-field-decl :u8)))

(az/deftest homogeneous-tuple-to-array-test
  (let [^{:zig/type Tuple} tuple [5 6]
        ^{:zig/type [:array 2 :u8]} array tuple]
    (set! _ array)))
