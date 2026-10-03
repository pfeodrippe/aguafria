(ns learn.example.test-basic-slices
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest basic-slices
  (let [array (k/var (a/array [1 2 3 4] :i32))
        known-at-runtime-zero (k/var 0 :usize)]
    (k/= :_ (k/& known-at-runtime-zero))
    (let [slice (a/slice array known-at-runtime-zero (:len array))
          ;; alternative initialization using result location
          alt-slice (k/as (k/& [1 2 3 4]) [:slice-const :i32])]
      (try (testing/expectEqualSlices :i32 slice alt-slice))
      (try (testing/expectEqual (a/type [:slice :i32]) (k/TypeOf slice)))
      (try (testing/expectEqual (k/& (a/get array 0)) (k/& (a/get slice 0))))
      (try (testing/expectEqual (:len array) (:len slice)))

      ;; If you slice with comptime-known start and end positions, the result is
      ;; a pointer to an array, rather than a slice.
      (let [array-ptr (a/slice array 0 (:len array))]
        (try (testing/expectEqual (a/type [:* [:array (:len array) :i32]])
                                  (k/TypeOf array-ptr))))

      ;; Using the address-of operator on a slice gives a single-item pointer.
      (try (testing/expectEqual (a/type [:* :i32])
                                (k/TypeOf (k/& (a/get slice 0)))))
      ;; Using the `ptr` field gives a many-item pointer.
      (try (testing/expectEqual (a/type [:many :i32])
                                (k/TypeOf (:ptr slice))))
      (try (testing/expectEqual (k/intFromPtr (:ptr slice))
                                (k/intFromPtr (k/& (a/get slice 0)))))

      ;; Slices have array bounds checking. If you try to access something out
      ;; of bounds, you'll get a safety check failure:
      (k/+= (a/get slice 10) 1)

      ;; Note that `slice.ptr` does not invoke safety checking, while `&slice[0]`
      ;; asserts that the slice has len > 0.

      ;; Empty slices can be created like this:
      (let [empty1 (k/& (a/init (a/object []) [:array 0 :u8]))
            ;; If the type is known you can use this short hand:
            empty2 (k/as (k/& (a/object [])) [:slice :u8])]
        (try (testing/expectEqual 0 (:len empty1)))
        (try (testing/expectEqual 0 (:len empty2))))
      ;; A zero-length initialization can always be used to create an empty slice, even if the slice is mutable.
      ;; This is because the pointed-to data is zero bits long, so its immutability is irrelevant.
      )))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (basic-slices))
