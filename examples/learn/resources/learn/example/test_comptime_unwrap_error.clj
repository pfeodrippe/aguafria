(ns learn.example.test-comptime-unwrap-error
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- getNumberOrFail [:error-union :i32] []
  (ak/return (az/error-value :UnableToReturnNumber)))

(az/defcomptime reject-unexpected-error
  (let [number (catch (getNumberOrFail) (ak/unreachable))]
    (ak/= :_ number)))
