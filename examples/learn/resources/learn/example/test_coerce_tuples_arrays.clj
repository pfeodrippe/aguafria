(ns learn.example.test-coerce-tuples-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst Tuple
  (a/struct
   [(a/tuple-field-decl :u8)
    (a/tuple-field-decl :u8)]))

(a/deftest coercion-from-homogeneous-tuple-to-array
  (let [tuple (k/as [5 6] Tuple)
        array (k/as tuple [:array 2 :u8])]
    (k/= :_ array)))

(comment
  (coercion-from-homogeneous-tuple-to-array))
