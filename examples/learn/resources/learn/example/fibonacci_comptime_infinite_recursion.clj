(ns learn.example.fibonacci-comptime-infinite-recursion
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- fibonacci :i32 [[index :i32]]
  ;;if (index < 2) return index;
  (k/+ (fibonacci (k/- index 1))
       (fibonacci (k/- index 2))))

(az/deftest fibonacci-test
  (try (k/comptime (debug/assert (k/== (fibonacci 7) 13)))))

(comment
  (fibonacci-test))
