(ns learn.example.testing-skip
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest this-will-be-skipped
  (return (a/error-value :SkipZigTest)))

(comment
  (this-will-be-skipped))
