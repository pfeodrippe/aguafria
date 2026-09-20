(ns learn.example.test-allocator
  (:require [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- concat [:error-union [:slice :u8]]
  [[allocator mem/Allocator] [left [:slice-const :u8]] [right [:slice-const :u8]]]
  (let [left-length (az/field left :len)
        result (try ((az/field allocator :alloc) :u8 (+ left-length (az/field right :len))))]
    (ak/memcpy (az/slice result 0 left-length) left)
    (ak/memcpy (az/slice result left-length) right)
    result))

(az/deftest fixed-buffer-allocation-test
  (let [buffer (ak/var ak/undefined [:array 100 :u8])
        fixed-buffer (ak/var ((az/field heap/FixedBufferAllocator :init) (& buffer)))
        allocator ((az/field fixed-buffer :allocator))
        result (try (concat allocator "foo" "bar"))]
    (try (testing/expectEqualStrings "foobar" result))))

(comment
  (fixed-buffer-allocation-test))
