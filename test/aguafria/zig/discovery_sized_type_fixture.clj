(ns aguafria.zig.discovery-sized-type-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn Buffer :type
  [[T {:attrs #{comptime}} :type]
   [capacity {:attrs #{comptime}} :usize]]
  (a/struct [[:items [:array capacity T]]]))

(a/deftest do-not-execute
  (let [buffer (a/init {:items [1 2 3 4]} (Buffer :u64 4))]
    (= :_ (:items buffer)))
  (unreachable))
