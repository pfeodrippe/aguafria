(ns learn.example.print-comptime-known-format
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defconst a-number :i32 1234)
(a/defconst a-string "foobar")
(a/defconst fmt "here is a string: '{s}' here is a number: {}\n")

(a/defn main :void []
  (debug/print fmt [a-string a-number]))

(comment
  (main))
