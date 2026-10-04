(ns aguafria.zig.discovery-recursive-link-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn Link :type
  [[T {:attrs #{k/comptime}} :type]]
  (a/struct [[:next [:optional [:* T]]]]))
