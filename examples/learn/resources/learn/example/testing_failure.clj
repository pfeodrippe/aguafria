(ns learn.example.testing-failure
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest expect-this-to-fail
  (try (testing/expect false)))

(a/deftest expect-this-to-succeed
  (try (testing/expect true)))

(comment
  (expect-this-to-fail)
  (expect-this-to-succeed))
