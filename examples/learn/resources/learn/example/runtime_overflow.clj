(ns learn.example.runtime-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [byte (k/var 255 :u8)]
    ;; Runtime safety traps instead of silently wrapping to zero.
    (k/+= byte 1)
    (debug/print "value: {}\n" [byte])))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
