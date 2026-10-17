(ns aguafria.zig.process-main-aot-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.process :as process]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar counter :u32 0)

(a/defn main :!void [[init process/Init]]
  (= :_ init)
  (+= counter 1)
  (k/try (testing/expectEqual (u32 1) counter)))
