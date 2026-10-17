(ns aguafria.zig.precompile-member-owner-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-member-exports-fixture :as exports]
            [aguafria.keyword :as k :refer :all]))

(a/defn selected-value :u32
  []
  (a/field (a/field exports "api") "selected_value"))
