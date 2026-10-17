(ns aguafria.zig.discovery-methods-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Counter
  [[:value :i32]
   (a/fn twice :i32 [[x :i32]] (* x 2))
   (a/fn plus :i32 [[self Counter] [x :i32]] (+ (:value self) x))
   (a/fn increment :void [[self [:* Counter]]]
     (+= (:value self) 1))])

(a/deftest never-run
  (let [constant (Counter {:value 10})
        mutable (k/var (Counter {:value 20}))
        x (i32 3)]
    (= :_ ((:twice Counter) x))
    (= :_ ((:plus constant) x))
    ((:increment mutable))
    (= :_ ((:plus mutable) x)))
  (unreachable))
