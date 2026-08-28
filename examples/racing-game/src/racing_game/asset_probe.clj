(ns racing-game.asset-probe
  "JVM-free offline release-asset validation executable."
  (:require [aguafria.std]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.process :as std-process]
            [aguafria.zig :as az]
            [racing-game.assets :as assets]
            [racing-game.inference :as inference]))

(az/defn main
  {:attrs #{:public}}
  :-
  :void
  []
  (if (assets/load-and-verify!)
    (do
      (std-debug/print
       "Racing assets OK: Granite GGUF, driver head, and team head match models.edn.\n"
       {})
      (inference/unload-model!))
    (std-process/exit 1)))
