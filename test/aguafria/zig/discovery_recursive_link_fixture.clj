(ns aguafria.zig.discovery-recursive-link-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn Link :type
  [[T {:attrs #{comptime}} :type]]
  (a/struct [[:next [:optional [:* T]]]]))
