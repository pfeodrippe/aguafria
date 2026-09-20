(ns learn.example.test-multidimensional-arrays
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst matrix
  (az/array-init [:array 4 [:array 5 :f32]]
    [(az/array-init [:array _ :f32] [1.0 0.0 0.0 0.0 0.0])
     (az/array-init [:array _ :f32] [0.0 1.0 0.0 1.0 0.0])
     (az/array-init [:array _ :f32] [0.0 0.0 1.0 0.0 0.0])
     (az/array-init [:array _ :f32] [0.0 0.0 0.0 1.0 9.9])]))

(az/deftest multidimensional-arrays-test
  ;; A matrix is an array whose elements are themselves arrays.
  (try (testing/expectEqual (az/index matrix 1)
                            (az/array-init [:array _ :f32] [0.0 1.0 0.0 1.0 0.0])))
  (try (testing/expectEqual 9.9 (az/index (az/index matrix 3) 4)))
  (for [[row matrix] [row-index (az/op ".." 0)]]
    (for [[cell row] [column-index (az/op ".." 0)]]
      (when (== row-index column-index)
        (try (testing/expectEqual 1.0 cell)))))
  (let [^{:zig/type [:array 4 [:array 5 :f32]]}
        zeroes (az/op "**" [(az/op "**" [0] 5)] 4)]
    (try (testing/expectEqual 0 (az/index (az/index zeroes 0) 0)))))

(comment
  (multidimensional-arrays-test))
