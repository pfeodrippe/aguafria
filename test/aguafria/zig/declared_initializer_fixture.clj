(ns aguafria.zig.declared-initializer-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- remainder :i32
  [[denominator {:attrs #{k/comptime}} :i32] [numerator :i32]]
  (k/% numerator denominator))

(a/defconst first-result (remainder 4 11))
(a/defconst second-result (remainder 5 17))
(a/defconst nested-result (k/+ (remainder 4 11) 8))
(a/defconst inferred-array (a/array [3 2 11] :i32))
(a/defconst typed-array [:array 3 :i32] (a/array [11 2 3] :i32))
(a/defconst element-type (a/type :i32))
(a/defconst alias-pointer (k/& inferred-array))
(a/defconst typed-number :i32 (k/i32 1060))
(a/defconst maybe-number [:optional :i32] (k/as 7 [:optional :i32]))
(a/defconst no-number [:optional :i32] (k/as nil [:optional :i32]))

(a/defstruct Pair [[:x :i32] [:y :i32]])
(a/defconst pair Pair (Pair {:x 3 :y 6}))

(a/defstruct Container
  [[:number :i32]
   [:inner-result {:const (remainder 4 11)} :_]])
