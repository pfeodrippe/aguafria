(ns learn.example.test-for
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest for-basics
  (let [items (a/array [4 5 3 4 0] :i32)
        sum (k/var 0 :i32)]
    ;; For loops iterate over slices and arrays.
    (for [value items]
      ;; Break and continue are supported.
      (when (== value 0)
        (continue))
      (+= sum value))
    (k/try (testing/expectEqual 16 sum))

    ;; To iterate over a portion of a slice, reslice.
    (for [value (a/slice items 0 1)]
      (+= sum value))
    (k/try (testing/expectEqual 20 sum))

    ;; To access the index of iteration, specify a second condition as well
    ;; as a second capture value.
    (let [sum2 (k/var 0 :i32)]
      (for [_ items i (a/range 0)]
        (k/try (testing/expectEqual :usize (TypeOf i)))
        (+= sum2 (as (intCast i) :i32)))
      (k/try (testing/expectEqual 10 sum2)))

    ;; To iterate over consecutive integers, use the range syntax.
    ;; Unbounded range is always a compile error.
    (let [sum3 (k/var 0 :usize)]
      (for [i (a/range 0 5)]
        (+= sum3 i))
      (k/try (testing/expectEqual 10 sum3)))))

(a/deftest multi-object-for
  (let [items (a/array [1 2 3] :usize)
        items2 (a/array [4 5 6] :usize)
        count (k/var 0 :usize)]
    ;; Iterate over multiple objects.
    ;; All lengths must be equal at the start of the loop, otherwise detectable
    ;; illegal behavior occurs.
    (for [i items j items2]
      (+= count (+ i j)))
    (k/try (testing/expectEqual 21 count))))

(a/deftest for-reference
  (let [items (k/var (a/array [3 4 2] :i32))]
    ;; Iterate over the slice by reference by
    ;; specifying that the capture value is a pointer.
    (for [(* value) (k/& items)]
      (+= @value 1))
    (k/try (testing/expectEqual 4 (a/get items 0)))
    (k/try (testing/expectEqual 5 (a/get items 1)))
    (k/try (testing/expectEqual 3 (a/get items 2)))))

(a/deftest for-else
  ;; For allows an else attached to it, the same as a while loop.
  (let [items (a/array [3 4 nil 5] [:optional :i32])
        sum (k/var 0 :i32)]
    ;; For loops can also be used as expressions.
    ;; Similar to while loops, when you break from a for loop, the else branch is not evaluated.
    (let [result (for [value items]
                   (when (!= value nil)
                     (+= sum (a/unwrap value)))
                   (a/else-expression
                    (a/with-block :blk
                      (k/try (testing/expectEqual 12 sum))
                      (break :blk sum))))]
      (k/try (testing/expectEqual 12 result)))))

(comment
  (for-basics)
  (multi-object-for)
  (for-reference)
  (for-else))
