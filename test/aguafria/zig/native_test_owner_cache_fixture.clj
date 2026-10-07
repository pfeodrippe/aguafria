(ns aguafria.zig.native-test-owner-cache-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- increment T
  [[T {:attrs #{k/comptime}} :type] [x T]]
  (k/+ x 1))

(a/deftest increment-test
  (try (testing/expectEqual 42 (increment :u32 41))))

(a/defn- later T
  [[T {:attrs #{k/comptime}} :type] [x T]]
  x)
