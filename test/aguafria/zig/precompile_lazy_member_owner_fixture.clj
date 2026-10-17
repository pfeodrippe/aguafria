(ns aguafria.zig.precompile-lazy-member-owner-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-lazy-member-leaf-fixture :as leaf]))

(a/defconst api {:attrs #{pub}} leaf)
