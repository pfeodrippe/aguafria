(ns learn.example.comments
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  ;; Zig line comments start with //; Clojure line comments start with ;.
  ;; Both end at the next line feed. The line below is not executed.

  ;; (debug/print "Hello?" [])

  (debug/print "Hello, world!\n" [])) ; another comment

(comment
  (main))
