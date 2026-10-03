(ns aguafria.zig.discovery-generic-type-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn Box :type
  [[T {:attrs #{k/comptime}} :type]]
  (a/struct [[:value T]]))

(a/defn make-box (Box :i32)
  [[n :i32]]
  (a/init {:value n} (Box :i32)))

(a/deftest do-not-execute
  (let [box-type (Box :i32)
        box (a/init {:value 42} box-type)]
    (k/= :_ (:value box)))
  (let [box-type (Box :u16)
        box (a/init {:value 7} box-type)]
    (k/= :_ (:value box)))
  (let [box-type (Box :i32)
        box (a/init {:value 15} box-type)]
    (k/= :_ (:value box)))
  (k/unreachable))
