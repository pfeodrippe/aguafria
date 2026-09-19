(ns learn.example.test-print-too-many-args
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst number :i32 1234)
(az/defconst text "foobar")

(az/deftest unused-print-argument-test
  (debug/print "here is a string: '{s}' here is a number: {}\n"
    [text number number]))
