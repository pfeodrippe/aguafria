(ns racing-game.standalone
  "JVM-free fast entry point generated from the same Aguafria graph."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [racing-game.monitor :as monitor]))

(a/defconst aguafria-development-overlays
  "Keep the native human-readable cognition UI in this demonstrator release."
  true)

(a/defn main :void
  []
  (ak/= :_ (monitor/run!)))
