(ns learn.example.test-comptime-unwrap-error
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- getNumberOrFail [:error-union :i32] []
  (a/error-value :UnableToReturnNumber))

(a/defcomptime reject-unexpected-error
  (let [number (k/catch (getNumberOrFail) (unreachable))]
    (= :_ number)))
