(ns learn.example.test-for
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest for-basics-test
  (let [items (az/array [4 5 3 4 0] :i32)
        sum (k/var 0 :i32)]
    ;; For loops iterate over slices and arrays.
    (k/for [value items]
      ;; Break and continue are supported.
      (when (k/== value 0)
        (k/continue))
      (k/+= sum value))
    (try (testing/expectEqual 16 sum))

    ;; To iterate over a portion of a slice, reslice.
    (k/for [value (az/slice items 0 1)]
      (k/+= sum value))
    (try (testing/expectEqual 20 sum))

    ;; To access the index of iteration, specify a second condition as well
    ;; as a second capture value.
    (let [index-sum (k/var 0 :i32)]
      (k/for [_ items index (az/range 0)]
        (try (testing/expectEqual :usize (k/TypeOf index)))
        (k/+= index-sum (k/as (k/intCast index) :i32)))
      (try (testing/expectEqual 10 index-sum)))

    ;; To iterate over consecutive integers, use the range syntax.
    ;; Unbounded range is always a compile error.
    (let [range-sum (k/var 0 :usize)]
      (k/for [index (az/range 0 5)]
        (k/+= range-sum index))
      (try (testing/expectEqual 10 range-sum)))))

(az/deftest multi-object-for-test
  (let [items (az/array [1 2 3] :usize)
        other-items (az/array [4 5 6] :usize)
        count (k/var 0 :usize)]
    ;; Iterate over multiple objects.
    ;; All lengths must be equal at the start of the loop, otherwise detectable
    ;; illegal behavior occurs.
    (k/for [left items right other-items]
      (k/+= count (k/+ left right)))
    (try (testing/expectEqual 21 count))))

(az/deftest for-reference-test
  (let [items (k/var (az/array [3 4 2] :i32))]
    ;; Iterate over the slice by reference by
    ;; specifying that the capture value is a pointer.
    (k/for [(k/* value) (k/& items)]
      (k/+= @value 1))
    (try (testing/expectEqual 4 (az/get items 0)))
    (try (testing/expectEqual 5 (az/get items 1)))
    (try (testing/expectEqual 3 (az/get items 2)))))

(az/deftest for-else-test
  ;; For allows an else attached to it, the same as a while loop.
  (let [items (az/array [3 4 nil 5] [:optional :i32])
        sum (k/var 0 :i32)]
    ;; For loops can also be used as expressions.
    ;; Similar to while loops, when you break from a for loop,
    ;; the else branch is not evaluated.
    (let [result (k/for [value items]
                   (when (k/!= value nil)
                     (k/+= sum (az/unwrap value)))
                   (az/else-expression
                    (az/with-block :blk
                      (try (testing/expectEqual 12 sum))
                      (k/break :blk sum))))]
      (try (testing/expectEqual 12 result)))))

(comment
  (for-basics-test)
  (multi-object-for-test)
  (for-reference-test)
  (for-else-test))
