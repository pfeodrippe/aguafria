(ns learn.example.print
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst number :i32 1234)
(az/defconst text "foobar")

(az/defn main :void []
  (debug/print "here is a string: '{s}' here is a number: {}\n" [text number]))

(comment
  (main))
