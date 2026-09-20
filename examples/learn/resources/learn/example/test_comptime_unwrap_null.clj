(ns learn.example.test-comptime-unwrap-null
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-absent-number
  (let [optional-number (ak/as nil [:optional :i32])
        number (az/unwrap optional-number)]
    (ak/= :_ number)))
