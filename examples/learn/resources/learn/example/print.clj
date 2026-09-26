(ns learn.example.print
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst a-number :i32 1234)
(az/defconst a-string "foobar")

(az/defn main :void []
  (debug/print "here is a string: '{s}' here is a number: {}\n" [a-string a-number]))

(comment
  (main))
