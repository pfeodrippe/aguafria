(ns learn.example.test-illegal-behavior
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest safety-check
  (k/unreachable))

(comment
  (safety-check))
