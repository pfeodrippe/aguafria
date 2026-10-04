(ns aguafria.zig.precompile-eager-dependency-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-constants-fixture :as constants]))

(a/defcomptime verify-constants
  (debug/assert (k/== constants/required-a 3))
  (debug/assert (k/== constants/required-b 7)))

(a/defn unused :void
  []
  (k/unreachable))
