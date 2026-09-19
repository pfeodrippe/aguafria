(ns learn.examples.idiomatic-library.print-comptime-known-format
  "Converted from print_comptime-known_format.zig"
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst number :i32 1234)
(az/defconst text "foobar")
(az/defconst format "here is a string: '{s}' here is a number: {}\n")

(az/defn main :void []
  (debug/print format [text number]))
