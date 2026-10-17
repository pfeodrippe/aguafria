(ns racing-game.replay-parity-probe
  "JVM-free golden replay check over the same native simulation graph."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [aguafria.std]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as a]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation]))

(a/defn main :void
  []
  (let [loaded
        (simulation/load-replay-file! "resources/replay/golden-r4.bin")]
    (std-debug/assert (a/field loaded valid))
    (std-debug/assert
     (== (a/field loaded intent_count)
         protocol/replay-golden-intent-count))
    (std-debug/assert (simulation/start-replay!))
    (simulation/step-many! protocol/replay-golden-ticks)
    (std-debug/assert
     (== (simulation/state-fingerprint)
         protocol/replay-golden-fingerprint)))
  (simulation/shutdown!))
