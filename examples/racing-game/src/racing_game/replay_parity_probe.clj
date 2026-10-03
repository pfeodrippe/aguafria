(ns racing-game.replay-parity-probe
  "JVM-free golden replay check over the same native simulation graph."
  (:require [aguafria.keyword :as ak]
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
     (ak/== (a/field loaded intent_count)
            protocol/replay-golden-intent-count))
    (std-debug/assert (simulation/start-replay!))
    (simulation/step-many! protocol/replay-golden-ticks)
    (std-debug/assert
     (ak/== (simulation/state-fingerprint)
            protocol/replay-golden-fingerprint)))
  (simulation/shutdown!))
