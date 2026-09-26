(ns learn.example.test-round-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest round
  (try (testing/expectEqual 1 (k/round 1.4)))
  (try (testing/expectEqual 2 (k/round 1.5)))
  (try (testing/expectEqual -1 (k/round -1.4)))
  (try (testing/expectEqual -3 (k/round -2.5))))

(comment
  (round))
