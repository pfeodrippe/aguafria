(ns aguafria.zig.discovery-recursive-type-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.zig.discovery-recursive-link-fixture :as link]))

(a/defstruct Item
  [[:link (link/Link Item)]
   [:value :u32]])

(a/defn item-size :usize
  []
  (sizeOf Item))
