(ns aguafria.zig.precompile-member-leaf-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst selected-value :u32 31)

(a/defconst unrelated :u32 99)
