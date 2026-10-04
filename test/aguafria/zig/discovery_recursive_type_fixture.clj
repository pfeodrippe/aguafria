(ns aguafria.zig.discovery-recursive-type-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.discovery-recursive-link-fixture :as link]))

(a/defstruct Item
  [[:link (link/Link Item)]
   [:value :u32]])

(a/defn item-size :usize
  []
  (k/sizeOf Item))
