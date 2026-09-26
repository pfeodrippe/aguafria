(ns learn.example.test-slices
  (:require [aguafria.keyword :as k]
            [aguafria.std.fmt :as fmt]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest using-slices-for-strings
  ;; Zig has no concept of strings. String literals are const pointers
  ;; to null-terminated arrays of u8, and by convention parameters
  ;; that are "strings" are expected to be UTF-8 encoded slices of u8.
  ;; Here we coerce *const [5:0]u8 and *const [6:0]u8 to []const u8
  (let [hello (k/as "hello" [:slice-const :u8])
        world (k/as "世界" [:slice-const :u8])
        all-together (k/var k/undefined [:array 100 :u8])
        ;; You can use slice syntax with at least one runtime-known index on an
        ;; array to convert an array into a slice.
        start (k/var 0 :usize)]
    (k/= :_ (k/& start))
    (let [all-together-slice (az/slice all-together start)
          ;; String concatenation example.
          hello-world (try (fmt/bufPrint all-together-slice "{s} {s}" [hello world]))]
      ;; Generally, you can use UTF-8 and not worry about whether something is a
      ;; string. If you don't need to deal with individual characters, no need
      ;; to decode.
      (try (testing/expectEqualStrings "hello 世界" hello-world)))))

(az/deftest slice-pointer
  (let [array (k/var k/undefined [:array 10 :u8])
        ptr (k/& array)]
    (try (testing/expectEqual (az/type [:* [:array 10 :u8]])
                              (k/TypeOf ptr)))
    ;; A pointer to an array can be sliced just like an array:
    (let [start (k/var 0 :usize)
          end (k/var 5 :usize)]
      (k/= :_ [(k/& start) (k/& end)])
      (let [slice (az/slice ptr start end)]
        ;; The slice is mutable because we sliced a mutable pointer.
        (try (testing/expectEqual (az/type [:slice :u8]) (k/TypeOf slice)))
        (k/= (az/get slice 2) 3)
        (try (testing/expectEqual 3 (az/get array 2)))
        ;; Again, slicing with comptime-known indexes will produce another pointer
        ;; to an array:
        (let [ptr2 (az/slice slice 2 3)]
          (try (testing/expectEqual 1 (:len ptr2)))
          (try (testing/expectEqual 3 (az/get ptr2 0)))
          (try (testing/expectEqual (az/type [:* [:array 1 :u8]])
                                    (k/TypeOf ptr2))))))))

(comment
  (using-slices-for-strings)
  (slice-pointer))
