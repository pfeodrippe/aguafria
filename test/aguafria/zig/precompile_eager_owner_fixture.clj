(ns aguafria.zig.precompile-eager-owner-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-constants-fixture :as constants]
            [aguafria.zig.precompile-eager-dependency-fixture :as dependency]
            [aguafria.zig.precompile-eager-other-dependency-fixture :as other-dependency]
            [aguafria.zig.precompile-eager-this-fixture :as file-container]))

(a/defn selected-value :u32
  []
  (k/= :_ file-container/Self)
  (k/= :_ dependency)
  (k/= :_ other-dependency)
  constants/selected)
