(ns learn.example.test-multidimensional-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst matrix
  (-> [(az/array [1.0 0.0 0.0 0.0 0.0] :f32)
       (az/array [0.0 1.0 0.0 1.0 0.0] :f32)
       (az/array [0.0 0.0 1.0 0.0 0.0] :f32)
       (az/array [0.0 0.0 0.0 1.0 9.9] :f32)]
      (az/array [:array 5 :f32])))

(az/deftest multidimensional-arrays-test
  ;; matrix itself is a one-dimensional array of arrays.
  (try (testing/expectEqual (az/get matrix 1)
                            (az/array [0.0 1.0 0.0 1.0 0.0] :f32)))

  ;; Access the 2D array by indexing the outer array, and then the inner array.
  (try (testing/expectEqual 9.9 (az/get-in matrix [3 4])))

  ;; Here we iterate with for loops.
  (k/for [row matrix row-index (az/range 0)]
    (k/for [cell row column-index (az/range 0)]
      (when (k/== row-index column-index)
        (try (testing/expectEqual 1.0 cell)))))

  ;; Initialize a multidimensional array to zeros.
  (let [zeroes (-> [(k/** [0] 5)]
                   (k/** 4)
                   (k/as [:array 4 [:array 5 :f32]]))]
    (try (testing/expectEqual 0 (az/get-in zeroes [0 0])))))

(comment
  (multidimensional-arrays-test))
