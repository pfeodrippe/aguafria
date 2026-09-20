(ns learn.example.test-coerce-tuples-arrays
  (:require [aguafria.zig :as az]))

(az/defconst Tuple
  (az/container {:kind :struct}
    (az/tuple-field-decl :u8)
    (az/tuple-field-decl :u8)))

(az/deftest homogeneous-tuple-to-array-test
  (let [^{:zig/type Tuple} tuple [5 6]
        ^{:zig/type [:array 2 :u8]} array tuple]
    (set! _ array)))

(comment
  (homogeneous-tuple-to-array-test))
