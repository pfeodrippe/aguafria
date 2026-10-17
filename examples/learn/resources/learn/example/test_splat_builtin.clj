(ns learn.example.test-splat-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest vector-splat
  (let [scalar (u32 5)
        result (as (splat scalar) [:vector 4 :u32])]
    (k/try (testing/expectEqualSlices
            :u32
            (k/& (a/array [5 5 5 5] :u32))
            (k/& (as result [:array 4 :u32]))))))

(a/deftest array-splat
  (let [scalar (u32 5)
        result (as (splat scalar) [:array 4 :u32])]
    (k/try (testing/expectEqualSlices
            :u32
            (k/& (a/array [5 5 5 5] :u32))
            (k/& (as result [:array 4 :u32]))))))

(comment
  (vector-splat)
  (array-splat))
