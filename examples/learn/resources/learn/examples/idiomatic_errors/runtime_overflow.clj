(ns learn.examples.idiomatic-errors.runtime-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :u8} byte 255]
    ;; Runtime safety traps instead of silently wrapping to zero.
    (ak/+= byte 1)
    (debug/print "value: {}\n" [byte])))
