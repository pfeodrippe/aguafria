(ns aguafria.zig.namespace-admission-extern-demand-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defextern unavailable :i32
  {:zig/prefix "extern \"__aguafria_unavailable_test_library__\""}
  [])

(a/defn demand :i32 []
  (unavailable))
