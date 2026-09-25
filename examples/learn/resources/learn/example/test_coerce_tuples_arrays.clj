(ns learn.example.test-coerce-tuples-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Tuple
  (az/struct
    [(az/tuple-field-decl :u8)
     (az/tuple-field-decl :u8)]))

(az/deftest homogeneous-tuple-to-array-test
  (let [tuple (k/as [5 6] Tuple)
        array (k/as tuple [:array 2 :u8])]
    (k/= :_ array)))

(comment
  (homogeneous-tuple-to-array-test))
