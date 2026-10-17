(ns aguafria.zig.discovery-private-type-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- Box :type
  [[T {:attrs #{comptime}} :type]]
  (a/struct [[:value T]]))

(a/deftest never-run
  (let [box (a/init {:value 7} (Box :i32))]
    (= :_ (:value box)))
  (unreachable))
