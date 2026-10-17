(ns aguafria.zig.precompile-eager-other-dependency-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.builtin :as builtin]
            [aguafria.zig.precompile-eager-test-state-fixture :as test-state]
            [aguafria.zig.precompile-eager-other-constants-fixture :as constants]))

(a/defcomptime verify-other-constants
  (debug/assert (== constants/required-c 13)))

(a/defcomptime inactive-test-state
  (when builtin/is_test
    (= :_ (:count test-state/TestOnly))))
