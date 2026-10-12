(ns aguafria.zig.linkage-context-first-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn foo :i32 {:attrs #{k/export}} [] 11)
