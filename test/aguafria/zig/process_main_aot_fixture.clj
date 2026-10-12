(ns aguafria.zig.process-main-aot-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.process :as process]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar counter :u32 0)

(a/defn main :!void [[init process/Init]]
  (k/= :_ init)
  (k/+= counter 1)
  (k/try (testing/expectEqual (k/u32 1) counter)))
