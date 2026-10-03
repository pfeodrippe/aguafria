(ns aguafria.zig.precompile-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn increment :i32
  [[x :i32]]
  (k/+ x 1))

(a/defn subtract :i32
  [[x :i32] [y :i32]]
  (k/- x y))

(a/defn do-not-call :void
  []
  (k/unreachable))

(a/defn generic-identity T
  [[T {:attrs #{k/comptime}} :type] [x T]]
  x)
