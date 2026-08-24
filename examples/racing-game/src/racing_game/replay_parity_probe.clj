(ns racing-game.replay-parity-probe
  "JVM-free golden replay check over the same native simulation graph."
  (:require [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation]))

(az/defn main
  {:attrs #{:public}}
  :-
  :void
  []
  (let [loaded
        (simulation/load-replay-file! "resources/replay/golden-r3.bin")]
    (std-debug/assert (az/field loaded valid))
    (std-debug/assert
     (ak/== (az/field loaded intent_count)
            protocol/replay-golden-intent-count))
    (std-debug/assert (simulation/start-replay!))
    (simulation/step-many! protocol/replay-golden-ticks)
    (std-debug/assert
     (ak/== (simulation/state-fingerprint)
            protocol/replay-golden-fingerprint)))
  (simulation/shutdown!))
