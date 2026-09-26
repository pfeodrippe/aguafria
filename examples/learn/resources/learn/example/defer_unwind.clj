(ns learn.example.defer-unwind
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (debug/print "\n" [])
  (defer (debug/print "1 " []))
  (defer (debug/print "2 " []))
  (when false
    ;; defers are not run if they are never executed.
    (defer (debug/print "3 " []))))

(comment
  (main))
