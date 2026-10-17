(ns learn.example.test-comptime-unreachable
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest type-of-unreachable
  (comptime
   ;; The type of unreachable is noreturn.

   ;; However this assertion will still fail to compile because
   ;; unreachable expressions are compile errors.

   (debug/assert (== (TypeOf (unreachable)) :noreturn))))

(comment
  (type-of-unreachable))
