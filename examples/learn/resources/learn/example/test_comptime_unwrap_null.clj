(ns learn.example.test-comptime-unwrap-null
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defcomptime reject-absent-number
  (let [optional-number (k/as nil [:optional :i32])
        number (a/unwrap optional-number)]
    (k/= :_ number)))
