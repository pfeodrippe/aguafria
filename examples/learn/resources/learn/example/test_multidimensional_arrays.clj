(ns learn.example.test-multidimensional-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst mat4x5
  (-> [(a/array [1.0 0.0 0.0 0.0 0.0] :f32)
       (a/array [0.0 1.0 0.0 1.0 0.0] :f32)
       (a/array [0.0 0.0 1.0 0.0 0.0] :f32)
       (a/array [0.0 0.0 0.0 1.0 9.9] :f32)]
      (a/array [:array 5 :f32])))

(a/deftest multidimensional-arrays
  ;; mat4x5 itself is a one-dimensional array of arrays.
  (try (testing/expectEqual (a/get mat4x5 1)
                            (a/array [0.0 1.0 0.0 1.0 0.0] :f32)))

  ;; Access the 2D array by indexing the outer array, and then the inner array.
  (try (testing/expectEqual 9.9 (a/get-in mat4x5 [3 4])))

  ;; Here we iterate with for loops.
  (k/for [row mat4x5 row-index (a/range 0)]
    (k/for [cell row column-index (a/range 0)]
      (when (k/== row-index column-index)
        (try (testing/expectEqual 1.0 cell)))))

  ;; Initialize a multidimensional array to zeros.
  (let [all-zero (k/as (k/splat (k/splat 0)) [:array 4 [:array 5 :f32]])]
    (try (testing/expectEqual 0 (a/get-in all-zero [0 0])))))

(comment
  (multidimensional-arrays))
