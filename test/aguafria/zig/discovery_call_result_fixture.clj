(ns aguafria.zig.discovery-call-result-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]))

(a/deftest do-not-execute
  (let [input (as "one,two" [:slice-const :u8])
        iterator (k/var (mem/splitScalar :u8 input \,))]
    (= :_ ((:next iterator)))
    (= :_ ((:next iterator))))
  (unreachable))
