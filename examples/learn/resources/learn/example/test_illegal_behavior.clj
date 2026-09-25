(ns learn.example.test-illegal-behavior
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; Intentional safety failure: this path reaches unreachable code.
(az/deftest safety-check-test
  (k/unreachable))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (safety-check-test))
