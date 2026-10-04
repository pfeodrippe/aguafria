(ns aguafria.zig.precompile-dependent-fixture
  (:require [aguafria.zig.precompile-dependency-fixture :as dependency]
            [aguafria.zig :as a]))

(a/defn increment-twice :i32
  [[x :i32]]
  (dependency/increment (dependency/increment x)))
