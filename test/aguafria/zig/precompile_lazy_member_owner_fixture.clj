(ns aguafria.zig.precompile-lazy-member-owner-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-lazy-member-leaf-fixture :as leaf]))

(a/defconst api {:attrs #{k/pub}} leaf)
