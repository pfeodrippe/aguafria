(ns aguafria.zig.precompile-eager-unreachable-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime do-not-import
  (compileError "This namespace is not reachable from the selected callable."))
