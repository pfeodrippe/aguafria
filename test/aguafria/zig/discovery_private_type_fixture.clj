(ns aguafria.zig.discovery-private-type-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- Box :type
  [[T {:attrs #{k/comptime}} :type]]
  (az/struct [[:value T]]))

(az/deftest never-run
  (let [box (az/init {:value 7} (Box :i32))]
    (k/= :_ (:value box)))
  (k/unreachable))
