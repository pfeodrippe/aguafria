(ns aguafria.zig.discovery-const-container-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst imported-namespace (import "std"))

(a/defenum Code {:type :u8}
  [[:zero 0]
   [:one 1]
   (a/fn increment :u32
     [[value :u32]]
     (+ value 1))])

(a/defunion Cell
  [[:integer :u32]
   [:float :f32]
   (a/fn twice :u32
     [[value :u32]]
     (* value 2))])
