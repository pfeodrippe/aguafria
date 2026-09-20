(ns learn.example.test-vector
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest basic-vector-test
  ;; Vectors have a compile-time-known length and base type.
  (let [a (az/array-init (ak/Vector 4 :i32) [1 2 3 4])
        b (az/array-init (ak/Vector 4 :i32) [5 6 7 8])
        ;; Math operations take place element-wise.
        sum (+ a b)]
    ;; Individual vector elements use the same indexing syntax as arrays.
    (try (testing/expectEqual 6 (az/index sum 0)))
    (try (testing/expectEqual 8 (az/index sum 1)))
    (try (testing/expectEqual 10 (az/index sum 2)))
    (try (testing/expectEqual 12 (az/index sum 3)))))

(az/deftest vector-array-slice-conversion-test
  ;; Vectors can be coerced to arrays, and vice versa.
  (let [original (ak/as (az/array-init [:array _ :f32] [1.1 3.2 4.5 5.6]) [:array 4 :f32])
        vector (ak/as original (ak/Vector 4 :f32))
        roundtrip (ak/as vector [:array 4 :f32])]
    (try (testing/expectEqual original roundtrip))

    ;; Dereference a slice with compile-time-known length to assign a vector.
    (let [fixed-vector (ak/as @(az/slice original 1 3) (ak/Vector 2 :f32))
          slice (ak/as (& original) [:slice-const :f32])
          ^:var offset (ak/u32 1)] ; mutable to make it runtime-known
      (set! _ (& offset)) ; suppress the never-mutated error
      ;; Starting at a runtime-known offset, first take a new slice, then an
      ;; array of compile-time-known length.
      (let [offset-vector (ak/as @(az/slice (az/slice slice offset) 0 2) (ak/Vector 2 :f32))]
        (try (testing/expectEqual (az/index slice offset) (az/index fixed-vector 0)))
        (try (testing/expectEqual (az/index slice (+ offset 1)) (az/index fixed-vector 1)))
        (try (testing/expectEqual fixed-vector offset-vector))))))

(comment
  (basic-vector-test)
  (vector-array-slice-conversion-test))
