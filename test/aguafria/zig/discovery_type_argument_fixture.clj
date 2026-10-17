(ns aguafria.zig.discovery-type-argument-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest pointer-size
  (k/try (testing/expectEqual (sizeOf [:optional [:* :i32]])
                              (sizeOf [:* :i32]))))
