(ns aguafria.zig.discovery-runtime-tuple-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [result (k/var (mulWithOverflow (u64 12) (u64 10)))]
    (= result (addWithOverflow (a/get result 0) (u64 3)))
    (= result [(u64 99) (u1 0)])
    (debug/print "{} {}" result)
    (= :_ (k/& result))
    (= :_ (a/get result 0))
    (= :_ (a/get result 1)))
  (unreachable))

(a/defn show-name :void
  [[input [:slice-const :u8]]]
  (let [text (as input [:slice-const :u8])
        tuple (++ [text] [])]
    (debug/print "{s}" tuple)))

(a/defn nested-flags :void
  [[input :bool]]
  (let [flag (bool input)
        inner (++ [flag] [])
        outer (++ [inner] [false])]
    (= :_ outer)))
