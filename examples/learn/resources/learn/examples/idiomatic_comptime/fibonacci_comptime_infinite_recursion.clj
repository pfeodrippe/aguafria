(ns learn.examples.idiomatic-comptime.fibonacci-comptime-infinite-recursion
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- fibonacci :i32 [[index :i32]]
  (+ (fibonacci (- index 1))
     (fibonacci (- index 2))))

(az/deftest fibonacci-infinite-recursion-test
  (try (ak/comptime (debug/assert (== (fibonacci 7) 13)))))
