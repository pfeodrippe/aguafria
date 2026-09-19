(ns lightpanda-agua.live
  "Small native state built from a real Lightpanda comptime type factory.

  The state capsule survives compatible reevaluations and gives the walkthrough
  a stable native identity that can be inspected from Clojure."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [lightpanda.src.cdp.id :as lightpanda-id]))

(az/defconst LiveCounter (lightpanda-id/Incrementing u32 "LIVE"))

(az/defvar live-counter LiveCounter (az/object []))

(az/defn display-offset :u32
  "A compatible leaf intended for quick body-only REPL edits."
  []
  100)

(az/defn session-address :usize
  "Return the stable address of the live native Lightpanda counter."
  []
  (ak/intFromPtr (ak/& live-counter)))

(az/defn counter-value :u32
  "Return the current value without changing native state."
  []
  (az/field live-counter counter))

(az/defn counter-next! :u32
  "Advance state through Lightpanda's generated Incrementing method."
  []
  ((az/field live-counter incr)))

(az/defn reset-counter! :void
  "Reset the live counter while retaining its native allocation."
  []
  (set! (az/field live-counter counter) 0))

(az/defn displayed-value :u32
  "Cross-function caller used to prove leaf publication reaches callers."
  []
  (+ (counter-value) (display-offset)))

