(ns aguafria.zig.discovery-bytes-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest never-run
  (debug/print (a/string-literal "\"\\xff\"") [])
  (unreachable))
