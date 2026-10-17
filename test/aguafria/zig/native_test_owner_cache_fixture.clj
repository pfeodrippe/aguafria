(ns aguafria.zig.native-test-owner-cache-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- increment T
  [[T {:attrs #{comptime}} :type] [x T]]
  (+ x 1))

(a/deftest increment-test
  (try (testing/expectEqual 42 (increment :u32 41))))

(a/defn- later T
  [[T {:attrs #{comptime}} :type] [x T]]
  x)
