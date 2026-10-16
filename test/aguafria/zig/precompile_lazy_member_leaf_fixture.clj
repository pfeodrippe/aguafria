(ns aguafria.zig.precompile-lazy-member-leaf-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-test-state-fixture :as tests]))

(a/defconst selected :u32 {:attrs #{k/pub}}  31)

(a/defconst TestOnly {:attrs #{k/pub}} tests/TestOnly)
