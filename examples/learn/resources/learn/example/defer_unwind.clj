(ns learn.example.defer-unwind
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (debug/print "\n" [])
  (k/defer (debug/print "1 " []))
  (k/defer (debug/print "2 " []))
  (when false
    ;; defers are not run if they are never executed.
    (k/defer (debug/print "3 " []))))

(comment
  (main))
