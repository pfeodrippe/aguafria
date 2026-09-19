(ns learn.example.test-compileLog-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst number
  (let [^{:var :i32} value 99]
    (ak/compileLog "comptime val1 = " value)
    (set! value (+ value 1))
    value))

(az/deftest compile-log-test
  (ak/compileLog "comptime in main")
  (debug/print "Runtime in main, num1 = {}.\n" [number]))
