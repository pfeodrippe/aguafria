(ns learn.example.destructuring-block
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [digits (a/array [3 8 9 0 7 4 1] :i8)
        [min max]
        (a/with-block :blk
          (let [min (k/var 127 :i8)
                max (k/var -128 :i8)]
            (for [digit digits]
              (when (< digit min)
                (= min digit))
              (when (> digit max)
                (= max digit)))
            (break :blk [min max])))]
    (debug/print "min = {}\n" [min])
    (debug/print "max = {}\n" [max])))

(comment
  (main))
