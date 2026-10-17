(ns learn.example.testing-failure
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest expect-this-to-fail
  (k/try (testing/expect false)))

(a/deftest expect-this-to-succeed
  (k/try (testing/expect true)))

(comment
  (expect-this-to-fail)
  (expect-this-to-succeed))
