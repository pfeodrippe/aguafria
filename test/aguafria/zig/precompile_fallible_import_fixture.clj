(ns aguafria.zig.precompile-fallible-import-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [number (k/u32 3)]
    (k/= :_ (try (math/shlExact :u32 number 2))))
  (k/unreachable))
