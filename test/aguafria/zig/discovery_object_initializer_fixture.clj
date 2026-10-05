(ns aguafria.zig.discovery-object-initializer-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn Record :type
  [[T {:attrs #{k/comptime}} :type]]
  (a/struct [[:value {:default 0} T]]))

(a/deftest do-not-execute
  (let [T (Record :u32)
        empty-record (a/init (a/object []) T)
        record (a/init (a/object [[:value 31]]) T)]
    (k/= :_ (:value empty-record))
    (k/= :_ (:value record)))
  (k/unreachable))
