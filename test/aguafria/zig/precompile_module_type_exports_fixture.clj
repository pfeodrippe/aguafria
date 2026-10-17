(ns aguafria.zig.precompile-module-type-exports-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-module-type-leaf-fixture :as leaf]
            [aguafria.keyword :as k :refer :all]))

(a/defconst Container leaf)
