(ns learn.example.testing-skip
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest skipped-test
  (ak/return (az/error-value :SkipZigTest)))
