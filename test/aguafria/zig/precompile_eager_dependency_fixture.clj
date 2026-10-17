(ns aguafria.zig.precompile-eager-dependency-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-constants-fixture :as constants]))

(a/defcomptime verify-constants
  (debug/assert (== constants/required-a 3))
  (debug/assert (== constants/required-b 7)))

(a/defn unused :void
  []
  (unreachable))
