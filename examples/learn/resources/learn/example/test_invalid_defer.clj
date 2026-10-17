(ns learn.example.test-invalid-defer
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- deferInvalidExample [:error-union :void] []
  (defer (return (a/error-value :DeferError)))
  (a/error-value :DeferError))

(comment
  (deferInvalidExample))
