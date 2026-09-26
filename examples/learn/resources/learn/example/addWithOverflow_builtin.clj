(ns learn.example.addWithOverflow-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [byte (k/u8 255)
        ov (k/addWithOverflow byte 10)]
    (if (k/!= (az/get ov 1) 0)
      (debug/print "overflowed result: {}\n" [(az/get ov 0)])
      (debug/print "result: {}\n" [(az/get ov 0)]))))

(comment
  (main))
