(ns aguafria.zig.discovery-slice-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn trim [:slice-const :u8]
  [[input [:slice-const :u8]] [start :i32] [end :i32]]
  (az/slice input (k/intCast start) (k/intCast end)))

(az/deftest never-run
  (let [array (az/array [1 2 3 4] :u8)
        end (k/var 3 :i32)]
    (k/= :_ (k/& end))
    (k/= :_ (az/slice array 0 (k/intCast end))))
  (k/unreachable))
