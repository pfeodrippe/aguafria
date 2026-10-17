(ns learn.example.test-comptime-division-by-zero
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-zero-divisor
  (let [a (i32 1)
        b (i32 0)
        c (/ a b)]
    (= :_ c)))
