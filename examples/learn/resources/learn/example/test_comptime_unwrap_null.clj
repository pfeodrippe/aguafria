(ns learn.example.test-comptime-unwrap-null
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-absent-number
  (let [optional-number (as nil [:optional :i32])
        number (a/unwrap optional-number)]
    (= :_ number)))
