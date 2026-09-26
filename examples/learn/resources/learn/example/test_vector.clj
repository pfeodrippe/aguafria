(ns learn.example.test-vector
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest basic-vector-test
  ;; Vectors have a compile-time-known length and base type.
  (let [a (az/init [1 2 3 4] (k/Vector 4 :i32))
        b (az/init [5 6 7 8] (k/Vector 4 :i32))
        ;; Math operations take place element-wise.
        sum (k/+ a b)]
    ;; Individual vector elements use the same indexing syntax as arrays.
    (try (testing/expectEqual 6 (az/get sum 0)))
    (try (testing/expectEqual 8 (az/get sum 1)))
    (try (testing/expectEqual 10 (az/get sum 2)))
    (try (testing/expectEqual 12 (az/get sum 3)))))

(az/deftest vector-array-slice-conversion-test
  ;; Vectors can be coerced to arrays, and vice versa.
  (let [original (k/as (az/array [1.1 3.2 4.5 5.6] :f32) [:array 4 :f32])
        vector (k/as original (k/Vector 4 :f32))
        roundtrip (k/as vector [:array 4 :f32])]
    (try (testing/expectEqual original roundtrip))

    ;; Dereference a slice with compile-time-known length to assign a vector.
    (let [fixed-vector (k/as @(az/slice original 1 3) (k/Vector 2 :f32))
          slice (k/as (k/& original) [:slice-const :f32])
          offset (k/var 1 :u32)] ; mutable to make it runtime-known
      (k/= :_ (k/& offset)) ; suppress the never-mutated error
      ;; Starting at a runtime-known offset, first take a new slice, then an
      ;; array of compile-time-known length.
      (let [offset-vector (k/as @(az/slice (az/slice slice offset) 0 2) (k/Vector 2 :f32))]
        (try (testing/expectEqual (az/get slice offset) (az/get fixed-vector 0)))
        (try (testing/expectEqual (az/get slice (k/+ offset 1)) (az/get fixed-vector 1)))
        (try (testing/expectEqual fixed-vector offset-vector))))))

(comment
  (basic-vector-test)
  (vector-array-slice-conversion-test))
