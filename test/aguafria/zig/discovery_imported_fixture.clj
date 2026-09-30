(ns aguafria.zig.discovery-imported-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.SemanticVersion :as version]
            [aguafria.zig :as az]))

(az/defn make-version std/SemanticVersion []
  (az/init {:major 1 :minor 2 :patch 3} std/SemanticVersion))

(az/deftest never-run
  (let [v (make-version)]
    (k/= :_ (version/-major v))
    (k/= :_ (k/& v)))
  (k/unreachable))
