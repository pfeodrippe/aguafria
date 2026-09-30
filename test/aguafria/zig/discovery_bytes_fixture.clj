(ns aguafria.zig.discovery-bytes-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/deftest never-run
  (debug/print (az/string-literal "\"\\xff\"") [])
  (k/unreachable))
