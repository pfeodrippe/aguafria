(ns learn.example.test-comptime-unwrap-error
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- getNumberOrFail [:error-union :i32] []
  (az/error-value :UnableToReturnNumber))

(az/defcomptime reject-unexpected-error
  (let [number (catch (getNumberOrFail) (k/unreachable))]
    (k/= :_ number)))
