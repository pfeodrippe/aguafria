(ns learn.example.test-invalid-defer
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- deferInvalidExample [:error-union :void] []
  (defer (k/return (a/error-value :DeferError)))
  (a/error-value :DeferError))

(comment
  (deferInvalidExample))
