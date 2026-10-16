(ns learn.example.test-round-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest round
  (k/try (testing/expectEqual 1 (k/round 1.4)))
  (k/try (testing/expectEqual 2 (k/round 1.5)))
  (k/try (testing/expectEqual -1 (k/round -1.4)))
  (k/try (testing/expectEqual -3 (k/round -2.5))))

(comment
  (round))
