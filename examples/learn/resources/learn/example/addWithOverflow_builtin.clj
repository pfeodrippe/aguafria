(ns learn.example.addWithOverflow-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [byte (u8 255)
        ov (addWithOverflow byte 10)]
    (if (!= (a/get ov 1) 0)
      (debug/print "overflowed result: {}\n" [(a/get ov 0)])
      (debug/print "result: {}\n" [(a/get ov 0)]))))

(comment
  (main))
