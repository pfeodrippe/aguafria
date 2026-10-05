(ns aguafria.zig.jvm-native-parameter-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn allocator-address :usize [[allocator mem/Allocator]]
  (k/intFromPtr (:ptr allocator)))

(a/deftest inferred-method-result-parameter
  (let [buffer (k/var k/undefined [:array 32 :u8])
        fba (k/var ((:init heap/FixedBufferAllocator) (k/& buffer)))
        allocator ((:allocator fba))]
    (try (testing/expectEqual (k/intFromPtr (k/& fba))
                              (allocator-address allocator)))))
