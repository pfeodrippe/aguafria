(ns learn.example.testing-failure
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest failing-expectation-test
  (try (testing/expect false)))

(az/deftest passing-expectation-test
  (try (testing/expect true)))
