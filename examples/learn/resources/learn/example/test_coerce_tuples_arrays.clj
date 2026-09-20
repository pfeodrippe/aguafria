(ns learn.example.test-coerce-tuples-arrays
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Tuple
  (az/struct
    [(az/tuple-field-decl :u8)
     (az/tuple-field-decl :u8)]))

(az/deftest homogeneous-tuple-to-array-test
  (let [tuple (ak/as [5 6] Tuple)
        array (ak/as tuple [:array 2 :u8])]
    (set! _ array)))

(comment
  (homogeneous-tuple-to-array-test))
