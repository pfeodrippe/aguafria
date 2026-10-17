(ns aguafria.zig.precompile-fallible-import-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [number (u32 3)]
    (= :_ (try (math/shlExact :u32 number 2))))
  (unreachable))
