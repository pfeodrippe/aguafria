(ns learn.example.test-fibonacci-comptime-unreachable
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- fibonacci :i32 [[index :i32]]
  (if (< index 2)
    index
    (+ (fibonacci (- index 1))
       (fibonacci (- index 2)))))

(az/deftest fibonacci-unreachable-test
  (try (ak/comptime (debug/assert (ak/== (fibonacci 7) 99999)))))

(comment
  (fibonacci-unreachable-test))
