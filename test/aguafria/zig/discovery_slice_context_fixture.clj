(ns aguafria.zig.discovery-slice-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn trim [:slice-const :u8]
  [[input [:slice-const :u8]] [start :i32] [end :i32]]
  (a/slice input (k/intCast start) (k/intCast end)))

(a/deftest never-run
  (let [array (a/array [1 2 3 4] :u8)
        end (k/var 3 :i32)]
    (k/= :_ (k/& end))
    (k/= :_ (a/slice array 0 (k/intCast end))))
  (k/unreachable))
