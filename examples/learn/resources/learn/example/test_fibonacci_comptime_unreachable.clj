(ns learn.example.test-fibonacci-comptime-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- fibonacci :i32 [[index :i32]]
  (if (k/< index 2)
    index
    (k/+ (fibonacci (k/- index 1))
       (fibonacci (k/- index 2)))))

(az/deftest fibonacci-unreachable-test
  (try (k/comptime (debug/assert (k/== (fibonacci 7) 99999)))))

(comment
  (fibonacci-unreachable-test))
