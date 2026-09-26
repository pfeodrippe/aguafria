(ns learn.example.testing-failure
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest expect-this-to-fail
  (try (testing/expect false)))

(az/deftest expect-this-to-succeed
  (try (testing/expect true)))

(comment
  (expect-this-to-fail)
  (expect-this-to-succeed))
