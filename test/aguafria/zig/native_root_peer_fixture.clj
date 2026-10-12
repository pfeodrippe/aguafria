(ns aguafria.zig.native-root-peer-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest ordinary-panic
  (k/panic "ordinary peer panic"))
