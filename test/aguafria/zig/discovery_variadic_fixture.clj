(ns aguafria.zig.discovery-variadic-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.zig :as az]))

(az/deftest never-run
  (k/= :_ (c/printf ""))
  (k/= :_ (c/printf "%d\n" (k/i32 12)))
  (k/= :_ (c/printf "%s=%d\n" "value" (k/i32 42)))
  (k/unreachable))
