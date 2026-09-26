(ns learn.example.test-allocator
  (:require [aguafria.keyword :as k]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- concat [:error-union [:slice :u8]]
  [[allocator mem/Allocator] [a [:slice-const :u8]] [b [:slice-const :u8]]]
  (let [result (try ((:alloc allocator) :u8 (k/+ (:len a) (:len b))))]
    (k/memcpy (az/slice result 0 (:len a)) a)
    (k/memcpy (az/slice result (:len a)) b)
    result))

(az/deftest using-an-allocator
  (let [buffer (k/var k/undefined [:array 100 :u8])
        fba (k/var ((:init heap/FixedBufferAllocator) (k/& buffer)))
        allocator ((:allocator fba))
        result (try (concat allocator "foo" "bar"))]
    (try (testing/expectEqualStrings "foobar" result))))

(comment
  (using-an-allocator))
