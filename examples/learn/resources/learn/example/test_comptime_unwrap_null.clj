(ns learn.example.test-comptime-unwrap-null
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-absent-number
  (let [optional-number (k/as nil [:optional :i32])
        number (az/unwrap optional-number)]
    (k/= :_ number)))
