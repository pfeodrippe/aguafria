(ns learn.example.test-print-too-many-args
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst a-number :i32 1234)
(az/defconst a-string "foobar")

(az/deftest print-too-many-arguments
  (debug/print "here is a string: '{s}' here is a number: {}\n"
               [a-string a-number a-number]))

(comment
  (print-too-many-arguments))
