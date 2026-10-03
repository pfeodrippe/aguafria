(ns learn.example.hello-again
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (debug/print "Hello, {s}!\n" ["World"]))

(comment
  (main))
