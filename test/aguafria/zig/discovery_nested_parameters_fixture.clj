(ns aguafria.zig.discovery-nested-parameters-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Calculator
  [[:bias :i32]
   (a/fn- negate :i32
          [[input (a/struct [[:value :i32]])]]
          (k/- (:value input)))
   (a/fn add :i32
     [[self Calculator]
      [input (a/struct [[:amount :i32] [:other :i32]])]]
     (k/+ (:amount input) (:bias self)
          (negate (a/object [[:value (:other input)]]))))])
