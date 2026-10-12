(ns aguafria.zig.discovery-comptime-index-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Pair [[:first :u32] [:second [:optional :u32]]])

(a/defn optional-field :bool [[index :usize]]
  (let [types (:field_types (:struct (k/typeInfo Pair)))]
    (k/switch index
      (0 1)
      (k/inline (a/with-block :_ [selected] (k/== (k/typeInfo (a/get types selected)) :optional)))
      false)))
