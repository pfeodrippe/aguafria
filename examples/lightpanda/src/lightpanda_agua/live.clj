(ns lightpanda-agua.live
  "Small native state built from a real Lightpanda comptime type factory.

  The state capsule survives compatible reevaluations and gives the walkthrough
  a stable native identity that can be inspected from Clojure."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [lightpanda.src.cdp.id :as lightpanda-id]))

(az/defconst LiveCounter (lightpanda-id/Incrementing u32 "LIVE"))

(az/defvar live-counter LiveCounter (az/object []))

(az/defn display-offset
  "A compatible leaf intended for quick body-only REPL edits."
  :-
  :u32
  []
  100)

(az/defn session-address
  "Return the stable address of the live native Lightpanda counter."
  :-
  :usize
  []
  (ak/intFromPtr (ak/& live-counter)))

(az/defn counter-value
  "Return the current value without changing native state."
  :-
  :u32
  []
  (az/field live-counter counter))

(az/defn counter-next!
  "Advance state through Lightpanda's generated Incrementing method."
  :-
  :u32
  []
  ((az/field live-counter incr)))

(az/defn reset-counter!
  "Reset the live counter while retaining its native allocation."
  :-
  :void
  []
  (set! (az/field live-counter counter) 0))

(az/defn displayed-value
  "Cross-function caller used to prove leaf publication reaches callers."
  :-
  :u32
  []
  (+ (counter-value) (display-offset)))

