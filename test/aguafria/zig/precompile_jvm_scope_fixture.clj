(ns aguafria.zig.precompile-jvm-scope-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn increment :i32 [[x :i32]]
  (k/+ x 1))

(a/deftest native-success
  (try (testing/expectEqual 42 (increment 41))))
