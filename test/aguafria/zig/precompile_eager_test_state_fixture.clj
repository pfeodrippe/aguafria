(ns aguafria.zig.precompile-eager-test-state-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.builtin :as builtin]
            [aguafria.std.debug :as debug]
            [aguafria.keyword :as k :refer :all]))

(a/defstruct TestOnly
  [(a/comptime-decl require-test (debug/assert builtin/is_test))
   [:count {:var 0} :u32]])
