(ns learn.example.test-fibonacci-comptime-unreachable
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- fibonacci :i32 [[index :i32]]
  (if (< index 2)
    index
    (+ (fibonacci (- index 1))
       (fibonacci (- index 2)))))

(a/deftest fibonacci-test
  (k/try (comptime (debug/assert (== (fibonacci 7) 99999)))))

(comment
  (fibonacci-test))
