(ns aguafria.zig.precompile-comptime-body-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn size-is-four :bool
  [[T {:attrs #{k/comptime}} :type]]
  (k/comptime
   (k/return (k/== (k/sizeOf T) 4))))

(a/deftest never-run
  (k/= :_ (k/comptime (size-is-four :u32)))
  (k/= :_ (k/comptime (size-is-four :u16)))
  (k/unreachable))
