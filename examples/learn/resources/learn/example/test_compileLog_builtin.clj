(ns learn.example.test-compileLog-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst number
  (let [value (k/var 99 :i32)]
    (k/compileLog "comptime val1 = " value)
    (k/= value (k/+ value 1))
    value))

(az/deftest compile-log-test
  (k/compileLog "comptime in main")
  (debug/print "Runtime in main, num1 = {}.\n" [number]))

(comment
  (compile-log-test))
