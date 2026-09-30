(ns aguafria.zig.discovery-private-methods-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defenum Suit
  [:clubs
   :spades
   (az/fn- is-clubs :bool [[self Suit]]
           (k/== self :.clubs))])

(az/defconst Variant
  (az/union {:attrs #{k/enum}}
            [[:int :i32]
             [:none :void]
             (az/fn- truthy :bool [[self Variant]]
                     (k/switch self
                               (case [:.int] [n] (k/!= n 0))
                               (case [:.none] false)))]))

(az/defstruct Counter
  [[:value :i32]
   (az/fn- increment :void [[self [:* Counter]]]
           (k/+= (:value self) 1))
   (az/fn- plus :i32 [[self Counter] [amount :i32]]
           (k/+ (:value self) amount))])

(az/deftest never-run
  (let [suit (k/as :.clubs Suit)
        variant (Variant {:int 1})
        counter (k/var (Counter {:value 20}))
        amount (k/i32 3)]
    (k/= :_ ((:is-clubs suit)))
    (k/= :_ ((:truthy variant)))
    ((:increment counter))
    (k/= :_ ((:plus counter) amount)))
  (k/unreachable))
