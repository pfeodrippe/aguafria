(ns learn.examples.idiomatic-safety.comptime-unreachable
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/deftest unreachable-type-test
  (ak/comptime
    (debug/assert (== (ak/TypeOf (ak/unreachable)) :noreturn))))
