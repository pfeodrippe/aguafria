(ns learn.example.test-comptime-reaching-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- assert :void [[condition :bool]]
  (when (k/! condition)
    (k/unreachable)))

;; A false assertion reaches unreachable during compile-time evaluation.
(az/defcomptime reject-false-condition
  (assert false))
