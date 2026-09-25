(ns learn.example.test-slices
  (:require [aguafria.keyword :as k]
            [aguafria.std.fmt :as fmt]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest string-slices-test
  ;; String literals coerce to UTF-8 byte slices without decoding characters.
  (let [hello (k/as "hello" [:slice-const :u8])
        world (k/as "世界" [:slice-const :u8])
        buffer (k/var k/undefined [:array 100 :u8])
        start (k/var 0 :usize)]
    (k/= :_ (k/& start))
    (let [slice (az/slice buffer start)
          greeting (try (fmt/bufPrint slice "{s} {s}" [hello world]))]
      (try (testing/expectEqualStrings "hello 世界" greeting)))))

(az/deftest slice-pointer-test
  (let [bytes (k/var k/undefined [:array 10 :u8])
        pointer (k/& bytes)]
    (try (testing/expectEqual (az/type [:* [:array 10 :u8]])
                              (k/TypeOf pointer)))
    (let [start (k/var 0 :usize)
          end (k/var 5 :usize)]
      (k/= :_ [(k/& start) (k/& end)])
      ;; Slicing a mutable array pointer yields a mutable slice.
      (let [slice (az/slice pointer start end)]
        (try (testing/expectEqual (az/type [:slice :u8]) (k/TypeOf slice)))
        (k/= (az/index slice 2) 3)
        (try (testing/expectEqual 3 (az/index bytes 2)))
        ;; Compile-time bounds instead produce a pointer to a fixed-size array.
        (let [element-pointer (az/slice slice 2 3)]
          (try (testing/expectEqual 1 (az/field element-pointer :len)))
          (try (testing/expectEqual 3 (az/index element-pointer 0)))
          (try (testing/expectEqual (az/type [:* [:array 1 :u8]])
                                    (k/TypeOf element-pointer))))))))

(comment
  (string-slices-test)
  (slice-pointer-test))
