(ns racing-game.standalone
  "JVM-free ReleaseFast entry point generated from the same Aguafria graph."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [racing-game.monitor :as monitor]))

(az/defconst aguafria-development-overlays
  "Keep the native human-readable cognition UI in this demonstrator release."
  true)

(az/defn main :void
  []
  (ak/= :_ (monitor/run!)))
