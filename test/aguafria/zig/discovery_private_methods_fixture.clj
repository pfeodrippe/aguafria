(ns aguafria.zig.discovery-private-methods-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Suit
  [:clubs
   :spades
   (a/fn- is-clubs :bool [[self Suit]]
          (k/== self :.clubs))])

(a/defconst Variant
  (a/union {:attrs #{k/enum}}
           [[:int :i32]
            [:none :void]
            (a/fn- truthy :bool [[self Variant]]
                   (k/switch self
                             (case [:.int] [n] (k/!= n 0))
                             (case [:.none] false)))]))

(a/defstruct Counter
  [[:value :i32]
   (a/fn- initial-value :i32 [] 20)
   (a/fn- increment :void [[self [:* Counter]]]
          (k/+= (:value self) 1))
   (a/fn- plus :i32 [[self Counter] [amount :i32]]
          (k/+ (:value self) amount))])

(a/deftest never-run
  (let [suit (k/as :.clubs Suit)
        variant (Variant {:int 1})
        counter (k/var (Counter {:value 20}))
        amount (k/i32 3)]
    (k/= :_ ((:is-clubs suit)))
    (k/= :_ ((:truthy variant)))
    ((:increment counter))
    (k/= :_ ((:plus counter) amount))
    (k/= :_ ((:initial-value Counter))))
  (k/unreachable))
