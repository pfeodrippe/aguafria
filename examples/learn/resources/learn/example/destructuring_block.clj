(ns learn.example.destructuring-block
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [digits (az/array [3 8 9 0 7 4 1] :i8)
        [min max]
        (az/with-block :blk
          (let [min (k/var 127 :i8)
                max (k/var -128 :i8)]
            (k/for [digit digits]
              (when (k/< digit min)
                (k/= min digit))
              (when (k/> digit max)
                (k/= max digit)))
            (k/break :blk [min max])))]
    (debug/print "min = {}\n" [min])
    (debug/print "max = {}\n" [max])))

(comment
  (main))
