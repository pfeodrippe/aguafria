(ns learn.example.print-comptime-known-format
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst a-number :i32 1234)
(az/defconst a-string "foobar")
(az/defconst fmt "here is a string: '{s}' here is a number: {}\n")

(az/defn main :void []
  (debug/print fmt [a-string a-number]))

(comment
  (main))
