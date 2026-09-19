(ns learn.examples.idiomatic-comptime.fibonacci-comptime-unreachable
  "Converted from test_fibonacci_comptime_unreachable.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- fibonacci :i32 [[index :i32]]
  (if (< index 2)
    index
    (+ (fibonacci (- index 1))
       (fibonacci (- index 2)))))

(az/deftest fibonacci-unreachable-test
  (try (ak/comptime (debug/assert (== (fibonacci 7) 99999)))))
