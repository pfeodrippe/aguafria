(ns aguafria.zig.precompile-backing-integer-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Code {:type :u8}
  [[:zero 0]
   [:eight 8]
   [:thirteen 13]
   [:_]])

(a/defn from-number Code
  [[number :u8]]
  (k/as (k/fromBackingInt (k/intCast number)) Code))

(a/deftest never-run
  (let [number (k/u8 8)
        code (k/as (k/fromBackingInt (k/intCast number)) Code)]
    (k/= :_ (k/intFromEnum code))
    (k/= :_ (k/== code :.eight))
    (k/= :_ (k/!= code :.zero)))
  (k/unreachable))
