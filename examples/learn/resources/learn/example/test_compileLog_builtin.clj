(ns learn.example.test-compileLog-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst num1
  (az/with-block :blk
    (let [val1 (k/var 99 :i32)]
      (k/compileLog "comptime val1 = " val1)
      (k/= val1 (k/+ val1 1))
      (k/break :blk val1))))

(az/deftest main
  (k/compileLog "comptime in main")
  (debug/print "Runtime in main, num1 = {}.\n" [num1]))

(comment
  (main))
