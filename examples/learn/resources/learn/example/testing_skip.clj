(ns learn.example.testing-skip
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest this-will-be-skipped
  (k/return (a/error-value :SkipZigTest)))

(comment
  (this-will-be-skipped))
