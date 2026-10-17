(ns learn.example.test-comptime-divExact-remainder
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-inexact-division
  (let [a (u32 10)
        b (u32 3)
        c (divExact a b)]
    (= :_ c)))
