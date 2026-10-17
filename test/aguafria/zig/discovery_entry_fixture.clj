(ns aguafria.zig.discovery-entry-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn main :c_int
  {:attrs #{export}}
  []
  0)

(a/deftest never-run
  (let [x (i32 1)]
    (= :_ (+ x 2)))
  (unreachable))
