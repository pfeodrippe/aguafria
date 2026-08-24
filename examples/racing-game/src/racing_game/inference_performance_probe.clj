(ns racing-game.inference-performance-probe
  "Bounded ReleaseFast proof for the exact native model graph."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [racing-game.assets :as assets]
            [racing-game.inference :as inference]))

(az/defn main
  {:attrs #{:public}}
  :-
  :void
  []
  (std-debug/assert (assets/load-and-verify!))
  (std-debug/assert (inference/initialize-sequences!))
  (dotimes [token 100]
    (let [report (inference/forward-token! 0 token)]
      (std-debug/assert (az/field report valid))
      (std-debug/assert (ak/== (az/field report position) token))))
  (inference/unload-model!))
