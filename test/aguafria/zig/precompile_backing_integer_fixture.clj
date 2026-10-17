(ns aguafria.zig.precompile-backing-integer-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defenum Code {:type :u8}
  [[:zero 0]
   [:eight 8]
   [:thirteen 13]
   [:_]])

(a/defn from-number Code
  [[number :u8]]
  (as (fromBackingInt (intCast number)) Code))

(a/deftest never-run
  (let [number (u8 8)
        code (as (fromBackingInt (intCast number)) Code)]
    (= :_ (intFromEnum code))
    (= :_ (== code :eight))
    (= :_ (!= code :zero)))
  (unreachable))
