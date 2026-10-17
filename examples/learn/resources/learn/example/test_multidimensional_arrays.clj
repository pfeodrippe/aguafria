(ns learn.example.test-multidimensional-arrays
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
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
  (k/try (testing/expectEqual (a/get mat4x5 1)
                              (a/array [0.0 1.0 0.0 1.0 0.0] :f32)))

  ;; Access the 2D array by indexing the outer array, and then the inner array.
  (k/try (testing/expectEqual 9.9 (a/get-in mat4x5 [3 4])))

  ;; Here we iterate with for loops.
  (for [row mat4x5 row-index (a/range 0)]
    (for [cell row column-index (a/range 0)]
      (when (== row-index column-index)
        (k/try (testing/expectEqual 1.0 cell)))))

  ;; Initialize a multidimensional array to zeros.
  (let [all-zero (as (splat (splat 0)) [:array 4 [:array 5 :f32]])]
    (k/try (testing/expectEqual 0 (a/get-in all-zero [0 0])))))

(comment
  (multidimensional-arrays))
