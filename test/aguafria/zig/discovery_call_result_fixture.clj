(ns aguafria.zig.discovery-call-result-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]))

(a/deftest do-not-execute
  (let [input (k/as "one,two" [:slice-const :u8])
        iterator (k/var (mem/splitScalar :u8 input \,))]
    (k/= :_ ((:next iterator)))
    (k/= :_ ((:next iterator))))
  (k/unreachable))
