(ns learn.example.test-invalid-defer
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- deferInvalidExample [:error-union :void] []
  ;; Returning from deferred cleanup is forbidden, regardless of the error.
  (defer (k/return (az/error-value :DeferError)))
  (az/error-value :DeferError))

(comment
  (deferInvalidExample))
