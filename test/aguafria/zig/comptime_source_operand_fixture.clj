(ns aguafria.zig.comptime-source-operand-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest optional-child
  (try (testing/expectEqual
        (a/type :u64)
        (-> (k/typeInfo [:optional :u64]) :optional :child))))

(a/deftest array-layout
  (try (testing/expectEqual 4 (k/sizeOf [:array 4 :u8]))))
