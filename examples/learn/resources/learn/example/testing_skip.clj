(ns learn.example.testing-skip
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest skipped-test
  (k/return (az/error-value :SkipZigTest)))

(comment
  (skipped-test))
