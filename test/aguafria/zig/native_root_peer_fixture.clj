(ns aguafria.zig.native-root-peer-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest ordinary-panic
  (panic "ordinary peer panic"))
