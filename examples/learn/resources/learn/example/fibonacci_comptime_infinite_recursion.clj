(ns learn.example.fibonacci-comptime-infinite-recursion
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- fibonacci :i32 [[index :i32]]
  (k/+ (fibonacci (k/- index 1))
     (fibonacci (k/- index 2))))

(az/deftest fibonacci-infinite-recursion-test
  (try (k/comptime (debug/assert (k/== (fibonacci 7) 13)))))

(comment
  (fibonacci-infinite-recursion-test))
