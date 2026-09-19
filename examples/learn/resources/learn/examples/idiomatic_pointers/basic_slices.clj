(ns learn.examples.idiomatic-pointers.basic-slices
  "Converted from test_basic_slices.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest basic-slices-test
  (let [^:var numbers (az/array-init [:array _ :i32] [1 2 3 4])
        ^{:var :usize} start 0]
    (set! _ (& start))
    (let [slice (az/slice numbers start (az/field numbers :len))
          ^{:zig/type [:slice-const :i32]} expected (& [1 2 3 4])]
      (try (testing/expectEqualSlices (az/type :i32) slice expected))
      (try (testing/expectEqual (az/type [:slice :i32]) (ak/TypeOf slice)))
      (try (testing/expectEqual (& (az/index numbers 0)) (& (az/index slice 0))))
      (try (testing/expectEqual (az/field numbers :len) (az/field slice :len)))

      ;; Compile-time bounds yield an array pointer instead of a slice.
      (let [array-pointer (az/slice numbers 0 (az/field numbers :len))]
        (try (testing/expectEqual (az/type [:* [:array (az/field numbers :len) :i32]])
                                  (ak/TypeOf array-pointer))))

      ;; Slicing twice retains a compile-time length with a runtime start.
      (let [^{:var :usize} runtime-start 1
            length 2]
        (set! _ (& runtime-start))
        (let [pair-pointer (az/slice (az/slice numbers runtime-start) 0 length)]
          (try (testing/expectEqual (az/type [:* [:array length :i32]])
                                    (ak/TypeOf pair-pointer)))))

      (try (testing/expectEqual (az/type [:* :i32])
                                (ak/TypeOf (& (az/index slice 0)))))
      (try (testing/expectEqual (az/type [:many :i32])
                                (ak/TypeOf (az/field slice :ptr))))
      (try (testing/expectEqual (ak/intFromPtr (az/field slice :ptr))
                                (ak/intFromPtr (& (az/index slice 0)))))

      ;; Intentionally trips the same bounds-check panic as the reference.
      (ak/+= (az/index slice 10) 1)

      ;; Zero-length storage permits empty mutable slices, too.
      (let [empty-array (& (az/init [:array 0 :u8] (az/object [])))
            ^{:zig/type [:slice :u8]} empty-slice (& (az/object []))]
        (try (testing/expectEqual 0 (az/field empty-array :len)))
        (try (testing/expectEqual 0 (az/field empty-slice :len)))))))
