(ns aguafria.zig.discovery-object-initializer-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn Record :type
  [[T {:attrs #{comptime}} :type]]
  (a/struct [[:value {:default 0} T]]))

(a/deftest do-not-execute
  (let [T (Record :u32)
        empty-record (a/init (a/object []) T)
        record (a/init (a/object [[:value 31]]) T)]
    (= :_ (:value empty-record))
    (= :_ (:value record)))
  (unreachable))
