(ns aguafria.zig.discovery-methods-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Counter
  [[:value :i32]
   (a/fn twice :i32 [[x :i32]] (k/* x 2))
   (a/fn plus :i32 [[self Counter] [x :i32]] (k/+ (:value self) x))
   (a/fn increment :void [[self [:* Counter]]]
     (k/+= (:value self) 1))])

(a/deftest never-run
  (let [constant (Counter {:value 10})
        mutable (k/var (Counter {:value 20}))
        x (k/i32 3)]
    (k/= :_ ((:twice Counter) x))
    (k/= :_ ((:plus constant) x))
    ((:increment mutable))
    (k/= :_ ((:plus mutable) x)))
  (k/unreachable))
