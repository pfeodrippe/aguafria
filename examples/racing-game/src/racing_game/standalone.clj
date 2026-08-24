(ns racing-game.standalone
  "JVM-free ReleaseFast entry point generated from the same Aguafria graph."
  (:require [aguafria.zig :as az]
            [racing-game.desktop :as desktop]))

(az/defn main
  {:attrs #{:public}}
  :-
  :void
  []
  (set! _ (desktop/run!)))

