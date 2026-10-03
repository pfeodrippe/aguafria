(ns aguafria.zig.discovery-bytes-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest never-run
  (debug/print (a/string-literal "\"\\xff\"") [])
  (k/unreachable))
