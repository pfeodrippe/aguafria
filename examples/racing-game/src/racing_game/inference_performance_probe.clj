(ns racing-game.inference-performance-probe
  "Bounded fast proof for the exact native model graph."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as a]
            [racing-game.assets :as assets]
            [racing-game.inference :as inference]))

(a/defn main :void
  []
  (std-debug/assert (assets/load-and-verify!))
  (std-debug/assert (inference/initialize-sequences!))
  (dotimes [token 100]
    (let [report (inference/forward-token! 0 token)]
      (std-debug/assert (a/field report valid))
      (std-debug/assert (ak/== (a/field report position) token))))
  (inference/unload-model!))
