(ns learn.example.addWithOverflow-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [byte (ak/u8 255)
        result (ak/addWithOverflow byte 10)]
    (if (!= (az/index result 1) 0)
      (debug/print "overflowed result: {}\n" [(az/index result 0)])
      (debug/print "result: {}\n" [(az/index result 0)]))))

(comment
  (main))
