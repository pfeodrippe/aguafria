(ns aguafria.zig.discovery-slice-context-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn trim [:slice-const :u8]
  [[input [:slice-const :u8]] [start :i32] [end :i32]]
  (a/slice input (intCast start) (intCast end)))

(a/deftest never-run
  (let [array (a/array [1 2 3 4] :u8)
        end (k/var 3 :i32)]
    (= :_ (k/& end))
    (= :_ (a/slice array 0 (intCast end))))
  (unreachable))
