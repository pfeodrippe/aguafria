(ns learn.example.zero-bit-types
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn entry :void {:attrs #{export}} []
  (let [x (k/var (a/block) :void)
        y (k/var (a/block) :void)]
    (= x y)
    (= y x)))

(comment
  (entry))
