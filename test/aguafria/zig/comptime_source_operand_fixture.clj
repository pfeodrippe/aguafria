(ns aguafria.zig.comptime-source-operand-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest optional-child
  (try (testing/expectEqual
        (a/type :u64)
        (-> (typeInfo [:optional :u64]) :optional :child))))

(a/deftest array-layout
  (try (testing/expectEqual 4 (sizeOf [:array 4 :u8]))))
