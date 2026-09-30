(ns aguafria.zig.discovery-generic-type-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn Box :type
  [[T {:attrs #{k/comptime}} :type]]
  (az/struct [[:value T]]))

(az/defn make-box (Box :i32)
  [[n :i32]]
  (az/init {:value n} (Box :i32)))

(az/deftest do-not-execute
  (let [box-type (Box :i32)
        box (az/init {:value 42} box-type)]
    (k/= :_ (:value box)))
  (let [box-type (Box :u16)
        box (az/init {:value 7} box-type)]
    (k/= :_ (:value box)))
  (let [box-type (Box :i32)
        box (az/init {:value 15} box-type)]
    (k/= :_ (:value box)))
  (k/unreachable))
