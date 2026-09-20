(ns learn.example.testing-detect-test
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst builtin (ak/import "builtin"))

(az/deftest detect-test-build-test
  (try (testing/expect (isATest))))

(az/defn- isATest :bool
  []
  (az/field builtin :is_test))

(comment
  (detect-test-build-test))
