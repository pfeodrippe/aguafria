(ns learn.example.testing-detect-test
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.builtin :as builtin]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- isATest :bool
  []
  builtin/is_test)

(a/deftest builtin-is-test
  (k/try (testing/expect (isATest))))

(comment
  (builtin-is-test))
