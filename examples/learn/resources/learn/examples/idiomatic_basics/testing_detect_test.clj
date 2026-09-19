(ns learn.examples.idiomatic-basics.testing-detect-test
  "Converted from testing_detect_test.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst builtin (ak/import "builtin"))

(az/deftest detect-test-build-test
  (try (testing/expect (test-build?))))

(az/defn- test-build? :bool
  []
  (az/field builtin :is_test))
