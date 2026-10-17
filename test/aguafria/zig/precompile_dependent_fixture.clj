(ns aguafria.zig.precompile-dependent-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig.precompile-dependency-fixture :as dependency]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn increment-twice :i32
  [[x :i32]]
  (dependency/increment (dependency/increment x)))
