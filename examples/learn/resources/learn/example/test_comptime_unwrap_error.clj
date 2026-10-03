(ns learn.example.test-comptime-unwrap-error
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- getNumberOrFail [:error-union :i32] []
  (a/error-value :UnableToReturnNumber))

(a/defcomptime reject-unexpected-error
  (let [number (catch (getNumberOrFail) (k/unreachable))]
    (k/= :_ number)))
