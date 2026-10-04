(ns aguafria.zig.discovery-const-container-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst imported-namespace (k/import "std"))

(a/defenum Code {:type :u8}
  [[:zero 0]
   [:one 1]
   (a/fn increment :u32
     [[value :u32]]
     (k/+ value 1))])

(a/defunion Cell
  [[:integer :u32]
   [:float :f32]
   (a/fn twice :u32
     [[value :u32]]
     (k/* value 2))])
