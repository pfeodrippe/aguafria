(ns learn.example.test-print-too-many-args
  (:refer-clojure :only [])
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst a-number :i32 1234)
(a/defconst a-string "foobar")

(a/deftest print-too-many-arguments
  (debug/print "here is a string: '{s}' here is a number: {}\n"
               [a-string a-number a-number]))

(comment
  (print-too-many-arguments))
