(ns learn.example.test-basic-slices
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest basic-slices-test
  (let [numbers (k/var (az/array [1 2 3 4] :i32))
        start (k/var 0 :usize)]
    (k/= :_ (k/& start))
    (let [slice (az/slice numbers start (:len numbers))
          expected (k/as (k/& [1 2 3 4]) [:slice-const :i32])]
      (try (testing/expectEqualSlices (az/type :i32) slice expected))
      (try (testing/expectEqual (az/type [:slice :i32]) (k/TypeOf slice)))
      (try (testing/expectEqual (k/& (az/get numbers 0)) (k/& (az/get slice 0))))
      (try (testing/expectEqual (:len numbers) (:len slice)))

      ;; Compile-time bounds yield an array pointer instead of a slice.
      (let [array-pointer (az/slice numbers 0 (:len numbers))]
        (try (testing/expectEqual (az/type [:* [:array (:len numbers) :i32]])
                                  (k/TypeOf array-pointer))))

      ;; Slicing twice retains a compile-time length with a runtime start.
      (let [runtime-start (k/var 1 :usize)
            length 2]
        (k/= :_ (k/& runtime-start))
        (let [pair-pointer (az/slice (az/slice numbers runtime-start) 0 length)]
          (try (testing/expectEqual (az/type [:* [:array length :i32]])
                                    (k/TypeOf pair-pointer)))))

      (try (testing/expectEqual (az/type [:* :i32])
                                (k/TypeOf (k/& (az/get slice 0)))))
      (try (testing/expectEqual (az/type [:many :i32])
                                (k/TypeOf (:ptr slice))))
      (try (testing/expectEqual (k/intFromPtr (:ptr slice))
                                (k/intFromPtr (k/& (az/get slice 0)))))

      ;; Intentionally trips the same bounds-check panic as the reference.
      (k/+= (az/get slice 10) 1)

      ;; Zero-length storage permits empty mutable slices, too.
      (let [empty-array (k/& (az/init (az/object []) [:array 0 :u8]))
            empty-slice (k/as (k/& (az/object [])) [:slice :u8])]
        (try (testing/expectEqual 0 (:len empty-array)))
        (try (testing/expectEqual 0 (:len empty-slice)))))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (basic-slices-test))
