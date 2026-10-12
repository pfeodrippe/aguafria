(ns aguafria.zig.comptime-owner-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- comptime-only :i32 [[n {:attrs #{k/comptime}} :i32]]
  (let [number (k/var n :i32)]
    (k/+= number 1)
    (k/% number (k/as 3 :i32))))

(a/defn- mixed :i32 [[n {:attrs #{k/comptime}} :i32]]
  (let [number (k/var n :i32)]
    (k/+= number 1)
    number))

(a/defconst folded (comptime-only 8))
(a/defconst folded-mixed (mixed 7))

(a/deftest mixed-runtime-call
  (k/try (testing/expectEqual (k/as 8 :i32) (mixed 7))))
