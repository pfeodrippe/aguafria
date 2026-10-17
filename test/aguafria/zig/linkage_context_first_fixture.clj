(ns aguafria.zig.linkage-context-first-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn foo :i32 {:attrs #{export}} [] 11)
