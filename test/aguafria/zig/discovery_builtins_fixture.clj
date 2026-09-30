(ns aguafria.zig.discovery-builtins-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest never-run
  (let [number (k/u8 16)
        shift (k/u3 1)]
    (k/= :_ (k/shlExact number 2))
    (k/= :_ (k/shrExact number 2))
    (k/= :_ (k/shlExact number shift))
    (k/= :_ (k/shrExact number shift)))
  (k/unreachable))
