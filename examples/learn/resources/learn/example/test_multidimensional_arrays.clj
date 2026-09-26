(ns learn.example.test-multidimensional-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst mat4x5
  (-> [(az/array [1.0 0.0 0.0 0.0 0.0] :f32)
       (az/array [0.0 1.0 0.0 1.0 0.0] :f32)
       (az/array [0.0 0.0 1.0 0.0 0.0] :f32)
       (az/array [0.0 0.0 0.0 1.0 9.9] :f32)]
      (az/array [:array 5 :f32])))

(az/deftest multidimensional-arrays
  ;; mat4x5 itself is a one-dimensional array of arrays.
  (try (testing/expectEqual (az/get mat4x5 1)
                            (az/array [0.0 1.0 0.0 1.0 0.0] :f32)))

  ;; Access the 2D array by indexing the outer array, and then the inner array.
  (try (testing/expectEqual 9.9 (az/get-in mat4x5 [3 4])))

  ;; Here we iterate with for loops.
  (k/for [row mat4x5 row-index (az/range 0)]
    (k/for [cell row column-index (az/range 0)]
      (when (k/== row-index column-index)
        (try (testing/expectEqual 1.0 cell)))))

  ;; Initialize a multidimensional array to zeros.
  (let [all-zero (-> [(k/** [0] 5)]
                     (k/** 4)
                     (k/as [:array 4 [:array 5 :f32]]))]
    (try (testing/expectEqual 0 (az/get-in all-zero [0 0])))))

(comment
  (multidimensional-arrays))
