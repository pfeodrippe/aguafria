(ns learn.examples.idiomatic-basics.testing-skip
  "Converted from testing_skip.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest skipped-test
  (ak/return (az/error-value :SkipZigTest)))
