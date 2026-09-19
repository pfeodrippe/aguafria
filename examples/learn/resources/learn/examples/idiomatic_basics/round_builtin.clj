(ns learn.examples.idiomatic-basics.round-builtin
  "Converted from test_round_builtin.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest round-test
  (try (testing/expectEqual 1 (ak/round 1.4)))
  (try (testing/expectEqual 2 (ak/round 1.5)))
  (try (testing/expectEqual -1 (ak/round -1.4)))
  (try (testing/expectEqual -3 (ak/round -2.5))))
