(ns aguafria.zig.precompile-eager-this-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-eager-test-state-fixture :as test-state]))

(a/defconst Self {:attrs #{pub}} (This))

(a/deffield value :u32)

(a/defn unused-test-method :u32 []
  (:count test-state/TestOnly))
