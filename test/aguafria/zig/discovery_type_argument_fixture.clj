(ns aguafria.zig.discovery-type-argument-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest pointer-size
  (k/try (testing/expectEqual (k/sizeOf [:optional [:* :i32]])
                              (k/sizeOf [:* :i32]))))
