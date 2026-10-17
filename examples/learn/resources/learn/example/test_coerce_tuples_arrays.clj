(ns learn.example.test-coerce-tuples-arrays
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst Tuple
  (a/struct
   [(a/tuple-field-decl :u8)
    (a/tuple-field-decl :u8)]))

(a/deftest coercion-from-homogeneous-tuple-to-array
  (let [tuple (as [5 6] Tuple)
        array (as tuple [:array 2 :u8])]
    (= :_ array)))

(comment
  (coercion-from-homogeneous-tuple-to-array))
