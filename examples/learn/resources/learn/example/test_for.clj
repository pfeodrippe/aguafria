(ns learn.example.test-for
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest for-basics-test
  (let [items (az/array-init [:array _ :i32] [4 5 3 4 0])
        ^:var sum (ak/i32 0)]
    ;; For loops iterate over slices and arrays.
    (for [value items]
      ;; Break and continue are supported.
      (when (== value 0)
        (ak/continue))
      (ak/+= sum value))
    (try (testing/expectEqual 16 sum))

    ;; To iterate over a portion of a slice, reslice.
    (for [value (az/slice items 0 1)]
      (ak/+= sum value))
    (try (testing/expectEqual 20 sum))

    ;; To access the index of iteration, specify a second condition as well
    ;; as a second capture value.
    (let [^:var index-sum (ak/i32 0)]
      (for [[_ items] [index (az/op ".." 0)]]
        (try (testing/expectEqual :usize (ak/TypeOf index)))
        (ak/+= index-sum (ak/as (ak/intCast index) :i32)))
      (try (testing/expectEqual 10 index-sum)))

    ;; To iterate over consecutive integers, use the range syntax.
    ;; Unbounded range is always a compile error.
    (let [^:var range-sum (ak/usize 0)]
      (for [index (az/op ".." 0 5)]
        (ak/+= range-sum index))
      (try (testing/expectEqual 10 range-sum)))))

(az/deftest multi-object-for-test
  (let [items (az/array-init [:array _ :usize] [1 2 3])
        other-items (az/array-init [:array _ :usize] [4 5 6])
        ^:var count (ak/usize 0)]
    ;; Iterate over multiple objects.
    ;; All lengths must be equal at the start of the loop, otherwise detectable
    ;; illegal behavior occurs.
    (for [[left items] [right other-items]]
      (ak/+= count (+ left right)))
    (try (testing/expectEqual 21 count))))

(az/deftest for-reference-test
  (let [^:var items (az/array-init [:array _ :i32] [3 4 2])]
    ;; Iterate over the slice by reference by
    ;; specifying that the capture value is a pointer.
    (for [(az/pointer-capture value) (& items)]
      (ak/+= @value 1))
    (try (testing/expectEqual 4 (az/index items 0)))
    (try (testing/expectEqual 5 (az/index items 1)))
    (try (testing/expectEqual 3 (az/index items 2)))))

(az/deftest for-else-test
  ;; For allows an else attached to it, the same as a while loop.
  (let [items (az/array-init [:array _ [:optional :i32]] [3 4 nil 5])
        ^:var sum (ak/i32 0)]
    ;; For loops can also be used as expressions.
    ;; Similar to while loops, when you break from a for loop,
    ;; the else branch is not evaluated.
    (let [result (for [value items]
                   (when (ak/!= value nil)
                     (ak/+= sum (az/unwrap value)))
                   (az/else-expression
                    (az/labeled-block blk
                      (try (testing/expectEqual 12 sum))
                      (ak/break blk sum))))]
      (try (testing/expectEqual 12 result)))))

(comment
  (for-basics-test)
  (multi-object-for-test)
  (for-reference-test)
  (for-else-test))
