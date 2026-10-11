(ns aguafria.zig.namespace-admission-extern-demand-fixture
  (:require [aguafria.zig :as a]))

(a/defextern unavailable :i32
  {:zig/prefix "extern \"__aguafria_unavailable_test_library__\""}
  [])

(a/defn demand :i32 []
  (unavailable))
