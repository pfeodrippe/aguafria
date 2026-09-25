(ns learn.example.test-multidimensional-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst matrix
  (az/array-init [(az/array-init [1.0 0.0 0.0 0.0 0.0] [:array :_ :f32])
                  (az/array-init [0.0 1.0 0.0 1.0 0.0] [:array :_ :f32])
                  (az/array-init [0.0 0.0 1.0 0.0 0.0] [:array :_ :f32])
                  (az/array-init [0.0 0.0 0.0 1.0 9.9] [:array :_ :f32])] [:array 4 [:array 5 :f32]]))

(az/deftest multidimensional-arrays-test
  ;; A matrix is an array whose elements are themselves arrays.
  (try (testing/expectEqual (az/index matrix 1)
                            (az/array-init [0.0 1.0 0.0 1.0 0.0] [:array :_ :f32])))
  (try (testing/expectEqual 9.9 (az/index (az/index matrix 3) 4)))
  (k/for [[row matrix] [row-index (az/op ".." 0)]]
    (k/for [[cell row] [column-index (az/op ".." 0)]]
      (when (k/== row-index column-index)
        (try (testing/expectEqual 1.0 cell)))))
  (let [zeroes (k/as (az/op "**" [(az/op "**" [0] 5)] 4) [:array 4 [:array 5 :f32]])]
    (try (testing/expectEqual 0 (az/index (az/index zeroes 0) 0)))))

(comment
  (multidimensional-arrays-test))
