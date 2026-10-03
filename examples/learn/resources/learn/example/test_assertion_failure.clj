(ns learn.example.test-assertion-failure
  (:refer-clojure :exclude [assert])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

;; This is how std.debug.assert is implemented
(a/defn- assert :void
  [[ok :bool]]
  (when (k/! ok)
    (k/unreachable))) ; assertion failure

;; This test will fail because we hit unreachable.
(a/deftest this-will-fail
  (assert false))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (this-will-fail))
