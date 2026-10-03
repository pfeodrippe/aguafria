(ns learn.example.test-print-too-many-args
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defconst a-number :i32 1234)
(a/defconst a-string "foobar")

(a/deftest print-too-many-arguments
  (debug/print "here is a string: '{s}' here is a number: {}\n"
               [a-string a-number a-number]))

(comment
  (print-too-many-arguments))
