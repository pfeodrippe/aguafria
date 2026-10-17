(ns aguafria.zig.discovery-private-methods-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defenum Suit
  [:clubs
   :spades
   (a/fn- is-clubs :bool [[self Suit]]
          (== self :clubs))])

(a/defconst Variant
  (a/union {:attrs #{enum}}
           [[:int :i32]
            [:none :void]
            (a/fn- truthy :bool [[self Variant]]
                   (switch self
                     :int (a/with-block :_ [n] (!= n 0))
                     :none false))]))

(a/defstruct Counter
  [[:value :i32]
   [:Category {:const (a/enum [:small :large])} :type]
   (a/fn- initial-value :i32 [] 20)
   (a/fn- increment :void [[self [:* Counter]]]
          (+= (:value self) 1))
   (a/fn- plus :i32 [[self Counter] [amount :i32]]
          (+ (:value self) amount))
   (a/fn- category Category [[self Counter]]
          (if (< (:value self) 20) :small :large))])

(a/deftest never-run
  (let [suit (as :clubs Suit)
        variant (Variant {:int 1})
        counter (k/var (Counter {:value 20}))
        amount (i32 3)]
    (= :_ ((:is-clubs suit)))
    (= :_ ((:truthy variant)))
    ((:increment counter))
    (= :_ ((:plus counter) amount))
    (= :_ ((:category counter)))
    (= :_ ((:initial-value Counter))))
  (unreachable))
