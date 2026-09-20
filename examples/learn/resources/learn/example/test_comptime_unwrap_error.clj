(ns learn.example.test-comptime-unwrap-error
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- number-or-error [:error-union :i32] []
  (ak/return (az/error-value :UnableToReturnNumber)))

(az/defcomptime reject-unexpected-error
  (let [number (catch (number-or-error) (ak/unreachable))]
    (set! _ number)))
