(ns aguafria.zig.precompile-member-exports-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-member-leaf-fixture :as leaf]
            [aguafria.keyword :as k :refer :all]))

(a/defconst api leaf)
