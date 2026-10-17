(ns aguafria.zig.discovery-generic-type-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn Box :type
  [[T {:attrs #{comptime}} :type]]
  (a/struct [[:value T]]))

(a/defn make-box (Box :i32)
  [[n :i32]]
  (a/init {:value n} (Box :i32)))

(a/deftest do-not-execute
  (let [box-type (Box :i32)
        box (a/init {:value 42} box-type)]
    (= :_ (:value box)))
  (let [box-type (Box :u16)
        box (a/init {:value 7} box-type)]
    (= :_ (:value box)))
  (let [box-type (Box :i32)
        box (a/init {:value 15} box-type)]
    (= :_ (:value box)))
  (unreachable))
