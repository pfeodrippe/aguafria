(ns learn.example.testing-skip
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest this-will-be-skipped
  (k/return (az/error-value :SkipZigTest)))

(comment
  (this-will-be-skipped))
