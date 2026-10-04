(ns aguafria.zig.precompile-eager-other-dependency-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.builtin :as builtin]
            [aguafria.zig.precompile-eager-test-state-fixture :as test-state]
            [aguafria.zig.precompile-eager-other-constants-fixture :as constants]))

(a/defcomptime verify-other-constants
  (debug/assert (k/== constants/required-c 13)))

(a/defcomptime inactive-test-state
  (when builtin/is_test
    (k/= :_ (:count test-state/TestOnly))))
