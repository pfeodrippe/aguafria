(ns learn.example.test-comptime-unreachable
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/deftest unreachable-type-test
  (k/comptime
   (debug/assert (k/== (k/TypeOf (k/unreachable)) :noreturn))))

(comment
  (unreachable-type-test))
