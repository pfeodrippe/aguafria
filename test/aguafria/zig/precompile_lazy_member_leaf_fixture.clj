(ns aguafria.zig.precompile-lazy-member-leaf-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-test-state-fixture :as tests]))

(a/defconst selected :u32 {:attrs #{pub}}  31)

(a/defconst TestOnly {:attrs #{pub}} tests/TestOnly)
