(ns learn.example.testing-null-with-if
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [optional-number (k/as nil [:optional :i32])]
    (a/if-capture-stmt {:payload [number]} optional-number
                       (debug/print "got number: {}\n" [number])
                       (debug/print "it's null\n" []))))

(comment
  (main))
