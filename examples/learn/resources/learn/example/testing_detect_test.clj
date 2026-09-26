(ns learn.example.testing-detect-test
  (:require [aguafria.builtin :as builtin]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- isATest :bool
  []
  builtin/is_test)

(az/deftest builtin-is-test
  (try (testing/expect (isATest))))

(comment
  (builtin-is-test))
