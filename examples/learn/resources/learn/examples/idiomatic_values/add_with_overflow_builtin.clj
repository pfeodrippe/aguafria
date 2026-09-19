(ns learn.examples.idiomatic-values.add-with-overflow-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^{:zig/type :u8} byte 255
        result (ak/addWithOverflow byte 10)]
    (if (!= (az/index result 1) 0)
      (debug/print "overflowed result: {}\n" [(az/index result 0)])
      (debug/print "result: {}\n" [(az/index result 0)]))))
