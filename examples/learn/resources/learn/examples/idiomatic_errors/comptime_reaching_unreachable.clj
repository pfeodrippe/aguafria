(ns learn.examples.idiomatic-errors.comptime-reaching-unreachable
  "Converted from test_comptime_reaching_unreachable.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- require-condition :void [[condition :bool]]
  (when (ak/! condition)
    (ak/unreachable)))

;; A false assertion reaches unreachable during compile-time evaluation.
(az/defcomptime reject-false-condition
  (require-condition false))
