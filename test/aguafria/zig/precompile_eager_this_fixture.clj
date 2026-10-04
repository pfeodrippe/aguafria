(ns aguafria.zig.precompile-eager-this-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-test-state-fixture :as test-state]))

(a/defconst Self {:attrs #{k/pub}} (k/This))

(a/deffield value :u32)

(a/defn unused-test-method :u32 []
  (:count test-state/TestOnly))
