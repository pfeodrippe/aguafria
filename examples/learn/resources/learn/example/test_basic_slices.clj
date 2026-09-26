(ns learn.example.test-basic-slices
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest basic-slices
  (let [array (k/var (az/array [1 2 3 4] :i32))
        known-at-runtime-zero (k/var 0 :usize)]
    (k/= :_ (k/& known-at-runtime-zero))
    (let [slice (az/slice array known-at-runtime-zero (:len array))
          ;; alternative initialization using result location
          alt-slice (k/as (k/& [1 2 3 4]) [:slice-const :i32])]
      (try (testing/expectEqualSlices (az/type :i32) slice alt-slice))
      (try (testing/expectEqual (az/type [:slice :i32]) (k/TypeOf slice)))
      (try (testing/expectEqual (k/& (az/get array 0)) (k/& (az/get slice 0))))
      (try (testing/expectEqual (:len array) (:len slice)))

      ;; If you slice with comptime-known start and end positions, the result is
      ;; a pointer to an array, rather than a slice.
      (let [array-ptr (az/slice array 0 (:len array))]
        (try (testing/expectEqual (az/type [:* [:array (:len array) :i32]])
                                  (k/TypeOf array-ptr))))

      ;; You can perform a slice-by-length by slicing twice. This allows the compiler
      ;; to perform some optimisations like recognising a comptime-known length when
      ;; the start position is only known at runtime.
      (let [runtime-start (k/var 1 :usize)
            length 2]
        (k/= :_ (k/& runtime-start))
        (let [array-ptr-len (az/slice (az/slice array runtime-start) 0 length)]
          (try (testing/expectEqual (az/type [:* [:array length :i32]])
                                    (k/TypeOf array-ptr-len)))))

      ;; Using the address-of operator on a slice gives a single-item pointer.
      (try (testing/expectEqual (az/type [:* :i32])
                                (k/TypeOf (k/& (az/get slice 0)))))
      ;; Using the `ptr` field gives a many-item pointer.
      (try (testing/expectEqual (az/type [:many :i32])
                                (k/TypeOf (:ptr slice))))
      (try (testing/expectEqual (k/intFromPtr (:ptr slice))
                                (k/intFromPtr (k/& (az/get slice 0)))))

      ;; Slices have array bounds checking. If you try to access something out
      ;; of bounds, you'll get a safety check failure:
      (k/+= (az/get slice 10) 1)

      ;; Note that `slice.ptr` does not invoke safety checking, while `&slice[0]`
      ;; asserts that the slice has len > 0.

      ;; Empty slices can be created like this:
      (let [empty1 (k/& (az/init (az/object []) [:array 0 :u8]))
            ;; If the type is known you can use this short hand:
            empty2 (k/as (k/& (az/object [])) [:slice :u8])]
        (try (testing/expectEqual 0 (:len empty1)))
        (try (testing/expectEqual 0 (:len empty2))))
      ;; A zero-length initialization can always be used to create an empty slice, even if the slice is mutable.
      ;; This is because the pointed-to data is zero bits long, so its immutability is irrelevant.
      )))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (basic-slices))
