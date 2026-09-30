(ns aguafria.zig.discovery-methods-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Counter
  [[:value :i32]
   (az/fn twice :i32 [[x :i32]] (k/* x 2))
   (az/fn plus :i32 [[self Counter] [x :i32]] (k/+ (:value self) x))
   (az/fn increment :void [[self [:* Counter]]]
     (k/+= (:value self) 1))])

(az/deftest never-run
  (let [constant (Counter {:value 10})
        mutable (k/var (Counter {:value 20}))
        x (k/i32 3)]
    (k/= :_ ((:twice Counter) x))
    (k/= :_ ((:plus constant) x))
    ((:increment mutable))
    (k/= :_ ((:plus mutable) x)))
  (k/unreachable))
