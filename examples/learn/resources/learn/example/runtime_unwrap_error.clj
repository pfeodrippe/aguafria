(ns learn.example.runtime-unwrap-error
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- number-or-error [:error-union :i32] []
  (ak/return (az/error-value :UnableToReturnNumber)))

(az/defn main :void []
  (let [number (catch (number-or-error) (ak/unreachable))]
    (debug/print "value: {}\n" [number])))
