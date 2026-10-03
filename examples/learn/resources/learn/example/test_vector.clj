(ns learn.example.test-vector
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest basic-vector-usage
  ;; Vectors have a compile-time known length and base type.
  (let [a (a/vector [1 2 3 4] :i32)
        b (a/vector [5 6 7 8] :i32)
        ;; Math operations take place element-wise.
        c (k/+ a b)]
    ;; Individual vector elements can be accessed using array indexing syntax.
    (try (testing/expectEqual 6 (a/get c 0)))
    (try (testing/expectEqual 8 (a/get c 1)))
    (try (testing/expectEqual 10 (a/get c 2)))
    (try (testing/expectEqual 12 (a/get c 3)))))

(a/deftest conversion-between-vectors-arrays-and-slices
  ;; Vectors can be coerced to arrays, and vice versa.
  (let [arr1 (a/array [1.1 3.2 4.5 5.6] :f32)
        vec (k/as arr1 (k/Vector 4 :f32))
        arr2 (k/as vec [:array 4 :f32])]
    (try (testing/expectEqual arr1 arr2))

    ;; You can also assign from a slice with comptime-known length to a vector using .*
    (let [vec2 (k/as @(a/slice arr1 1 3) (k/Vector 2 :f32))
          slice (k/as (k/& arr1) [:slice-const :f32])
          offset (k/var 1 :u32)] ; var to make it runtime-known
      (k/= :_ (k/& offset)) ; suppress 'var is never mutated' error
      ;; To extract a comptime-known length from a runtime-known offset,
      ;; first extract a new slice from the starting offset, then an array of
      ;; comptime-known length
      (let [vec3 (-> (a/slice slice offset)
                     (a/slice 0 2)
                     deref
                     (k/as (k/Vector 2 :f32)))]
        (try (testing/expectEqual (a/get slice offset) (a/get vec2 0)))
        (try (testing/expectEqual (a/get slice (k/+ offset 1)) (a/get vec2 1)))
        (try (testing/expectEqual vec2 vec3))))))

(comment
  (basic-vector-usage)
  (conversion-between-vectors-arrays-and-slices))
