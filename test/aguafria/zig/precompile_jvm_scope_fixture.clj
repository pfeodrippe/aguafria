(ns aguafria.zig.precompile-jvm-scope-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn increment :i32 [[x :i32]]
  (+ x 1))

(a/deftest native-success
  (try (testing/expectEqual 42 (increment 41))))
