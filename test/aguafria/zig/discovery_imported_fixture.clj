(ns aguafria.zig.discovery-imported-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std :as std]
            [aguafria.std.SemanticVersion :as version]
            [aguafria.zig :as a]))

(a/defn make-version std/SemanticVersion []
  (a/init {:major 1 :minor 2 :patch 3} std/SemanticVersion))

(a/deftest never-run
  (let [v (make-version)]
    (= :_ (version/-major v))
    (= :_ (k/& v)))
  (unreachable))
