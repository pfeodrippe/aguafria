(ns aguafria.zig.discovery-variadic-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.c :as libc]
            [aguafria.zig :as a]))

(a/deftest never-run
  (= :_ (libc/printf ""))
  (= :_ (libc/printf "%d\n" (i32 12)))
  (= :_ (libc/printf "%s=%d\n" "value" (i32 42)))
  (unreachable))
