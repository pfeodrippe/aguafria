(ns aguafria.zig.discovery-entry-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn main :c_int
  {:attrs #{k/export}}
  []
  0)

(az/deftest never-run
  (let [x (k/i32 1)]
    (k/= :_ (k/+ x 2)))
  (k/unreachable))
