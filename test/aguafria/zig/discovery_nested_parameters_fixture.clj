(ns aguafria.zig.discovery-nested-parameters-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Calculator
  [[:bias :i32]
   (a/fn- negate :i32
          [[input (a/struct [[:value :i32]])]]
          (- (:value input)))
   (a/fn add :i32
     [[self Calculator]
      [input (a/struct [[:amount :i32] [:other :i32]])]]
     (+ (:amount input) (:bias self)
        (negate (a/object [[:value (:other input)]]))))])
