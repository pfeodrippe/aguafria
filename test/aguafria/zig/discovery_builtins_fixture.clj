(ns aguafria.zig.discovery-builtins-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [number (u8 16)
        shift (u3 1)]
    (= :_ (shlExact number 2))
    (= :_ (shrExact number 2))
    (= :_ (shlExact number shift))
    (= :_ (shrExact number shift)))
  (unreachable))
