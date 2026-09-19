(ns learn.examples.idiomatic-error-flow.defer-unwind
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (debug/print "\n" [])
  (defer (debug/print "1 " []))
  (defer (debug/print "2 " []))
  ;; Only executed defer statements participate in reverse-order cleanup.
  (when false
    (defer (debug/print "3 " []))))
