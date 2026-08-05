(ns simple-game.cart
  "Native coconut-cart economy, customer queue, and upgrade progression."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.atomic :as std-atomic]
            [aguafria.zig :as az]))

(az/defstruct CartSnapshot
  "Inspectable authoritative state for the Recife coconut cart."
  {:layout :extern}
  [[:initialized :bool]
   [:coconuts :u32]
   [:ice :u32]
   [:cash :u32]
   [:reputation :u32]
   [:served :u32]
   [:missed :u32]
   [:waiting :u32]
   [:customer :u8]
   [:dialogue :u8]
   [:cart_level :u8]
   [:cooler_level :u8]
   [:service_level :u8]
   [:customer_seconds :f32]])

(az/defvar initialized false)

(az/defvar state-lock :u8 0)

(az/defvar coconuts :u32 12)

(az/defvar ice :u32 12)

(az/defvar cash :u32 24)

(az/defvar reputation :u32 1)

(az/defvar served :u32 0)

(az/defvar missed :u32 0)

(az/defvar waiting :u32 1)

(az/defvar customer :u8 0)

(az/defvar dialogue :u8 1)

(az/defvar cart-level :u8 0)

(az/defvar cooler-level :u8 0)

(az/defvar service-level :u8 0)

(az/defvar customer-seconds :f32 0.0)

(az/defn lock-state!
  {:export false}
  :-
  :void
  []
  (ak/while
   (ak/!= (ak/atomicRmw :u8 (ak/& state-lock) :.Xchg 1 :.acquire) 0)
   (std-atomic/spinLoopHint)))

(az/defn unlock-state!
  {:export false}
  :-
  :void
  []
  (ak/atomicStore :u8 (ak/& state-lock) 0 :.release))

(az/defn initialize!
  "Initialize once so compatible hot reloads retain the live street business."
  :-
  :bool
  []
  (when (ak/! initialized)
    (set! coconuts 12)
    (set! ice 12)
    (set! cash 24)
    (set! reputation 1)
    (set! served 0)
    (set! missed 0)
    (set! waiting 1)
    (set! customer 0)
    (set! dialogue 1)
    (set! cart-level 0)
    (set! cooler-level 0)
    (set! service-level 0)
    (set! customer-seconds 0.0)
    (set! initialized true))
  initialized)

(az/defn step!
  "Advance deterministic customer arrivals and patience without allocation."
  :-
  :void
  [[delta-seconds :f32]]
  (set! _ (initialize!))
  (lock-state!)
  (set! customer-seconds (+ customer-seconds delta-seconds))
  (cond
    (and (ak/== waiting 0) (>= customer-seconds 2.5))
    (do
      (set! customer (ak/intCast (mod (+ customer 1) 3)))
      (set! waiting 1)
      (set! dialogue 1)
      (set! customer-seconds 0.0))

    (and (> waiting 0)
         (>= customer-seconds (+ 14.0
                                  (ak/as :f32
                                         (ak/floatFromInt service-level)))))
    (do
      (set! missed (+ missed 1))
      (set! waiting 0)
      (set! dialogue 4)
      (set! customer-seconds 0.0)))
  (unlock-state!))

(az/defn serve!
  "Serve the front customer and return whether a chilled coconut was sold."
  :-
  :bool
  []
  (set! _ (initialize!))
  (lock-state!)
  (let [sold (and (> waiting 0) (> coconuts 0) (> ice 0))]
    (when sold
      (set! coconuts (- coconuts 1))
      (set! ice (- ice 1))
      (set! cash (+ cash 6 (ak/as :u32 (ak/intCast cart-level))))
      (set! served (+ served 1))
      (when (ak/== (mod served 3) 0)
        (set! reputation (+ reputation 1)))
      (set! waiting (- waiting 1))
      (set! dialogue (+ 2 (ak/as :u8 (ak/intCast (mod served 2)))))
      (set! customer-seconds 0.0))
    (unlock-state!)
    sold))

(az/defn restock!
  "Buy a small manual restock while the automated supply line is being built."
  :-
  :bool
  []
  (set! _ (initialize!))
  (lock-state!)
  (let [bought (>= cash 8)]
    (when bought
      (set! cash (- cash 8))
      (set! coconuts (+ coconuts 8))
      (set! ice (+ ice 8)))
    (unlock-state!)
    bought))

(az/defn upgrade-cart!
  "Purchase the next cart level; upgrades raise the price per coconut."
  :-
  :bool
  []
  (set! _ (initialize!))
  (lock-state!)
  (let [cost (+ 20 (* (ak/as :u32 (ak/intCast cart-level)) 15))
        upgraded (and (< cart-level 5) (>= cash cost))]
    (when upgraded
      (set! cash (- cash cost))
      (set! cart-level (+ cart-level 1)))
    (unlock-state!)
    upgraded))

(az/defn snapshot
  "Return the complete cart state as an ordinary inspectable native value."
  :-
  CartSnapshot
  []
  (set! _ (initialize!))
  (lock-state!)
  (let [result
        (CartSnapshot
         {:initialized initialized
          :coconuts coconuts
          :ice ice
          :cash cash
          :reputation reputation
          :served served
          :missed missed
          :waiting waiting
          :customer customer
          :dialogue dialogue
          :cart_level cart-level
          :cooler_level cooler-level
          :service_level service-level
          :customer_seconds customer-seconds})]
    (unlock-state!)
    result))

(az/defn reset-cart!
  "Reset the cart explicitly; ordinary hot reload never calls this function."
  :-
  :void
  []
  (lock-state!)
  (set! initialized false)
  (unlock-state!)
  (set! _ (initialize!)))
