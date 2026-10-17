(ns aguafria.zig.discovery-comptime-index-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Pair [[:first :u32] [:second [:optional :u32]]])

(a/defn optional-field :bool [[index :usize]]
  (let [types (:field_types (:struct (typeInfo Pair)))]
    (switch index
      (0 1)
      (inline (a/with-block :_ [selected] (== (typeInfo (a/get types selected)) :optional)))
      false)))
