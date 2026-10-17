(ns aguafria.zig.discovery-aggregate-address-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defenum Instruction [:mul :add :end])
(a/defstruct Pair [[:left :i32] [:right :i32]])

(a/defn evaluate :i32
  [[stack [:slice-const :i32]] [code [:slice-const Instruction]]]
  (switch (a/get code 0)
    :mul
    (switch (a/get code 1)
      :add (+ (a/get stack 0) (* (a/get stack 1) (a/get stack 2)))
      -999)
    -999))

(a/defn length :usize [[values [:slice-const :u16]]]
  (:len values))

(a/defn retain-slice [:slice-const :i32] [[values [:slice-const :i32]]]
  values)

(a/defn array-first :i32 [[values [:*const [:array 3 :i32]]]]
  (a/get (a/deref values) 0))

(a/defn pair-sum :i32 [[pair [:*const Pair]]]
  (+ (:left (a/deref pair)) (:right (a/deref pair))))

(a/deftest observe-addresses
  (= :_ (evaluate (k/& [7 2 -3]) (k/& [:mul :add :end])))
  (= :_ (evaluate (k/& [(i32 7) 2 -3]) (k/& [:mul :add :end])))
  (= :_ (length (k/& [])))
  (= :_ (retain-slice (k/& [4 5])))
  (= :_ (array-first (k/& [9 8 7])))
  (= :_ (pair-sum (k/& {:left 6 :right 5})))
  (unreachable))
