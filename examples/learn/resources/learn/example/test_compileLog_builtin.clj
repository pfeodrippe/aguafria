(ns learn.example.test-compileLog-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defconst num1
  (a/with-block :blk
    (let [val1 (k/var 99 :i32)]
      (compileLog "comptime val1 = " val1)
      (= val1 (+ val1 1))
      (break :blk val1))))

(a/deftest main
  (compileLog "comptime in main")
  (debug/print "Runtime in main, num1 = {}.\n" [num1]))

(comment
  (main))
