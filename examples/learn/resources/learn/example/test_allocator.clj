(ns learn.example.test-allocator
  (:require [aguafria.keyword :as k]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- concat [:error-union [:slice :u8]]
  [[allocator mem/Allocator] [left [:slice-const :u8]] [right [:slice-const :u8]]]
  (let [left-length (:len left)
        result (try ((:alloc allocator) :u8 (k/+ left-length (:len right))))]
    (k/memcpy (az/slice result 0 left-length) left)
    (k/memcpy (az/slice result left-length) right)
    result))

(az/deftest fixed-buffer-allocation-test
  (let [buffer (k/var k/undefined [:array 100 :u8])
        fixed-buffer (k/var ((:init heap/FixedBufferAllocator) (k/& buffer)))
        allocator ((:allocator fixed-buffer))
        result (try (concat allocator "foo" "bar"))]
    (try (testing/expectEqualStrings "foobar" result))))

(comment
  (fixed-buffer-allocation-test))
