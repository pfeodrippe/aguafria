(ns learn.example.test-assertion-failure
  (:refer-clojure :exclude [assert])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; This is how std.debug.assert is implemented.
(az/defn- assert :void
  [[ok :bool]]
  (when (k/! ok)
    (k/unreachable))) ; assertion failure

;; This test will fail because we hit unreachable.
(az/deftest assertion-failure-test
  (assert false))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (assertion-failure-test))
