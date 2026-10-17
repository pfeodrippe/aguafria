(ns learn.example.fibonacci-comptime-infinite-recursion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- fibonacci :i32 [[index :i32]]
  ;;if (index < 2) return index;
  (+ (fibonacci (- index 1))
     (fibonacci (- index 2))))

(a/deftest fibonacci-test
  (k/try (comptime (debug/assert (== (fibonacci 7) 13)))))

(comment
  (fibonacci-test))
