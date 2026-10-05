(ns aguafria.zig.precompile-member-owner-fixture
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-member-exports-fixture :as exports]))

(a/defn selected-value :u32
  []
  (a/field (a/field exports "api") "selected_value"))
