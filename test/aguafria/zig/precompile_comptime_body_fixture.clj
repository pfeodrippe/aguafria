(ns aguafria.zig.precompile-comptime-body-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn size-is-four :bool
  [[T {:attrs #{comptime}} :type]]
  (comptime
   (return (== (sizeOf T) 4))))

(a/deftest never-run
  (= :_ (comptime (size-is-four :u32)))
  (= :_ (comptime (size-is-four :u16)))
  (unreachable))
