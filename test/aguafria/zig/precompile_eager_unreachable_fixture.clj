(ns aguafria.zig.precompile-eager-unreachable-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defcomptime do-not-import
  (k/compileError "This namespace is not reachable from the selected callable."))
