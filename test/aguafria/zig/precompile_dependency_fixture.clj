(ns aguafria.zig.precompile-dependency-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn increment :i32
  [[x :i32]]
  (k/+ x 1))
