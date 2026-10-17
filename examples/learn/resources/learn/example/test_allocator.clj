(ns learn.example.test-allocator
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- concat [:error-union [:slice :u8]]
  [[allocator mem/Allocator] [a [:slice-const :u8]] [b [:slice-const :u8]]]
  (let [result (k/try ((:alloc allocator) :u8 (+ (:len a) (:len b))))]
    (memcpy (a/slice result 0 (:len a)) a)
    (memcpy (a/slice result (:len a)) b)
    result))

(a/deftest using-an-allocator
  (let [buffer (k/var undefined [:array 100 :u8])
        fba (k/var ((:init heap/FixedBufferAllocator) (k/& buffer)))
        allocator ((:allocator fba))
        result (k/try (concat allocator "foo" "bar"))]
    (k/try (testing/expectEqualStrings "foobar" result))))

(comment
  (using-an-allocator))
