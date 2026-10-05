(ns aguafria.zig.discovery-aggregate-address-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Instruction [:mul :add :end])
(a/defstruct Pair [[:left :i32] [:right :i32]])

(a/defn evaluate :i32
  [[stack [:slice-const :i32]] [code [:slice-const Instruction]]]
  (k/switch (a/get code 0)
            (case [:.mul]
              (k/switch (a/get code 1)
                        (case [:.add] (k/+ (a/get stack 0) (k/* (a/get stack 1) (a/get stack 2))))
                        (a/case-else -999)))
            (a/case-else -999)))

(a/defn length :usize [[values [:slice-const :u16]]]
  (:len values))

(a/defn retain-slice [:slice-const :i32] [[values [:slice-const :i32]]]
  values)

(a/defn array-first :i32 [[values [:*const [:array 3 :i32]]]]
  (a/get (a/deref values) 0))

(a/defn pair-sum :i32 [[pair [:*const Pair]]]
  (k/+ (:left (a/deref pair)) (:right (a/deref pair))))

(a/deftest observe-addresses
  (k/= :_ (evaluate (k/& [7 2 -3]) (k/& [:.mul :.add :.end])))
  (k/= :_ (evaluate (k/& [(k/i32 7) 2 -3]) (k/& [:.mul :.add :.end])))
  (k/= :_ (length (k/& [])))
  (k/= :_ (retain-slice (k/& [4 5])))
  (k/= :_ (array-first (k/& [9 8 7])))
  (k/= :_ (pair-sum (k/& {:left 6 :right 5})))
  (k/unreachable))
