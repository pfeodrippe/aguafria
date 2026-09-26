(ns learn.example.test-pointer-arithmetic
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest pointer-arithmetic-with-many-item-pointer
  (let [array (az/array [1 2 3 4] :i32)
        ptr (k/var (k/& array) [:many-const :i32])]
    (try (testing/expectEqual 1 (az/get ptr 0)))
    (k/+= ptr 1)
    (try (testing/expectEqual 2 (az/get ptr 0)))
    ;; slicing a many-item pointer without an end is equivalent to
    ;; pointer arithmetic: `ptr[start..] == ptr + start`
    (try (testing/expectEqual (az/slice ptr 1) (k/+ ptr 1)))
    ;; subtraction between any two pointers except slices based on element size is supported
    (try (testing/expectEqual 1 (k/- (k/& (az/get ptr 1))
                                     (k/& (az/get ptr 0)))))))

(az/deftest pointer-arithmetic-with-slices
  (let [array (k/var (az/array [1 2 3 4] :i32))
        length (k/var 0 :usize)] ; var to make it runtime-known
    (k/= :_ (k/& length)) ; suppress 'var is never mutated' error
    (let [slice (k/var (az/slice array length (:len array)))]
      (try (testing/expectEqual 1 (az/get slice 0)))
      (try (testing/expectEqual 4 (:len slice)))
      (k/+= (:ptr slice) 1)
      ;; now the slice is in an bad state since len has not been updated
      (try (testing/expectEqual 2 (az/get slice 0)))
      (try (testing/expectEqual 4 (:len slice))))))

(comment
  (pointer-arithmetic-with-many-item-pointer)
  (pointer-arithmetic-with-slices))
