(ns aguafria.zig.precompile-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn increment :i32
  [[x :i32]]
  (k/+ x 1))

(az/defn subtract :i32
  [[x :i32] [y :i32]]
  (k/- x y))

(az/defn do-not-call :void
  []
  (k/unreachable))

(az/defn generic-identity T
  [[T {:attrs #{k/comptime}} :type] [x T]]
  x)
