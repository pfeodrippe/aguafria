(ns learn.examples.idiomatic-basics.assertion-failure
  (:refer-clojure :exclude [assert])
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; This is how std.debug.assert is implemented.
(az/defn- assert :void
  [[ok :bool]]
  (when (ak/! ok)
    (ak/unreachable))) ; assertion failure

;; This test will fail because we hit unreachable.
(az/deftest assertion-failure-test
  (assert false))
