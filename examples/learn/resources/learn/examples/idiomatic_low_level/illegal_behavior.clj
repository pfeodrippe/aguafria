(ns learn.examples.idiomatic-low-level.illegal-behavior
  "Converted from test_illegal_behavior.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Intentional safety failure: this path reaches unreachable code.
(az/deftest safety-check-test
  (ak/unreachable))
