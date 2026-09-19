(ns learn.examples.idiomatic-error-flow.invalid-defer
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- invalid-defer [:error-union :void] []
  ;; Returning from deferred cleanup is forbidden, regardless of the error.
  (defer (ak/return (az/error-value :DeferError)))
  (ak/return (az/error-value :DeferError)))
