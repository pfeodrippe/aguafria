(ns aguafria.zig.discovery-sized-type-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn Buffer :type
  [[T {:attrs #{k/comptime}} :type]
   [capacity {:attrs #{k/comptime}} :usize]]
  (a/struct [[:items [:array capacity T]]]))

(a/deftest do-not-execute
  (let [buffer (a/init {:items [1 2 3 4]} (Buffer :u64 4))]
    (k/= :_ (:items buffer)))
  (k/unreachable))
