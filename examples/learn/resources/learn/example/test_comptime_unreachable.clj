(ns learn.example.test-comptime-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/deftest type-of-unreachable
  (k/comptime
   ;; The type of unreachable is noreturn.

   ;; However this assertion will still fail to compile because
   ;; unreachable expressions are compile errors.

   (debug/assert (k/== (k/TypeOf (k/unreachable)) :noreturn))))

(comment
  (type-of-unreachable))
