(ns racing-game.standalone
  "JVM-free fast entry point generated from the same Aguafria graph."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [aguafria.zig :as a]
            [racing-game.monitor :as monitor]))

(a/defconst aguafria-development-overlays
  "Keep the native human-readable cognition UI in this demonstrator release."
  true)

(a/defn main :void
  []
  (= :_ (monitor/run!)))
