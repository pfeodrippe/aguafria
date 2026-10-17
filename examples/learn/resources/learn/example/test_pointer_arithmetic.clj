(ns learn.example.test-pointer-arithmetic
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest pointer-arithmetic-with-many-item-pointer
  (let [array (a/array [1 2 3 4] :i32)
        ptr (k/var (k/& array) [:many-const :i32])]
    (k/try (testing/expectEqual 1 (a/get ptr 0)))
    (+= ptr 1)
    (k/try (testing/expectEqual 2 (a/get ptr 0)))
    ;; slicing a many-item pointer without an end is equivalent to
    ;; pointer arithmetic: `ptr[start..] == ptr + start`
    (k/try (testing/expectEqual (a/slice ptr 1) (+ ptr 1)))
    ;; subtraction between any two pointers except slices based on element size is supported
    (k/try (testing/expectEqual 1 (- (k/& (a/get ptr 1))
                                     (k/& (a/get ptr 0)))))))

(a/deftest pointer-arithmetic-with-slices
  (let [array (k/var (a/array [1 2 3 4] :i32))
        length (k/var 0 :usize)] ; var to make it runtime-known
    (= :_ (k/& length)) ; suppress 'var is never mutated' error
    (let [slice (k/var (a/slice array length (:len array)))]
      (k/try (testing/expectEqual 1 (a/get slice 0)))
      (k/try (testing/expectEqual 4 (:len slice)))
      (+= (:ptr slice) 1)
      ;; now the slice is in an bad state since len has not been updated
      (k/try (testing/expectEqual 2 (a/get slice 0)))
      (k/try (testing/expectEqual 4 (:len slice))))))

(comment
  (pointer-arithmetic-with-many-item-pointer)
  (pointer-arithmetic-with-slices))
