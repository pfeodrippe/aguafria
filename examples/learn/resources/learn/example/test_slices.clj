(ns learn.example.test-slices
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.fmt :as fmt]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest string-slices-test
  ;; String literals coerce to UTF-8 byte slices without decoding characters.
  (let [^{:zig/type [:slice-const :u8]} hello "hello"
        ^{:zig/type [:slice-const :u8]} world "世界"
        ^{:var [:array 100 :u8]} buffer ak/undefined
        ^{:var :usize} start 0]
    (set! _ (& start))
    (let [slice (az/slice buffer start)
          greeting (try (fmt/bufPrint slice "{s} {s}" [hello world]))]
      (try (testing/expectEqualStrings "hello 世界" greeting)))))

(az/deftest slice-pointer-test
  (let [^{:var [:array 10 :u8]} bytes ak/undefined
        pointer (& bytes)]
    (try (testing/expectEqual (az/type [:* [:array 10 :u8]])
                              (ak/TypeOf pointer)))
    (let [^{:var :usize} start 0
          ^{:var :usize} end 5]
      (set! _ [(& start) (& end)])
      ;; Slicing a mutable array pointer yields a mutable slice.
      (let [slice (az/slice pointer start end)]
        (try (testing/expectEqual (az/type [:slice :u8]) (ak/TypeOf slice)))
        (set! (az/index slice 2) 3)
        (try (testing/expectEqual 3 (az/index bytes 2)))
        ;; Compile-time bounds instead produce a pointer to a fixed-size array.
        (let [element-pointer (az/slice slice 2 3)]
          (try (testing/expectEqual 1 (az/field element-pointer :len)))
          (try (testing/expectEqual 3 (az/index element-pointer 0)))
          (try (testing/expectEqual (az/type [:* [:array 1 :u8]])
                                    (ak/TypeOf element-pointer))))))))
