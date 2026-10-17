(ns learn.example.test-comptime-remainder-division-by-zero
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-zero-remainder-divisor
  (let [a (i32 10)
        b (i32 0)
        c (% a b)]
    (= :_ c)))
