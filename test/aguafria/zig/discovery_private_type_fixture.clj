(ns aguafria.zig.discovery-private-type-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- Box :type
  [[T {:attrs #{k/comptime}} :type]]
  (a/struct [[:value T]]))

(a/deftest never-run
  (let [box (a/init {:value 7} (Box :i32))]
    (k/= :_ (:value box)))
  (k/unreachable))
