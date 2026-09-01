(ns simple-game.behavior-probe
  "Headless ReleaseFast contract for the native coco-house production loop."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.process :as std-process]
            [aguafria.zig :as az]
            [simple-game.factory :as factory]
            [simple-game.game :as game]))

(az/defn main
  "Exit unsuccessfully when optimized factory behavior diverges from development."
  :-
  :void
  []
  (set! _ (game/initialize!))
  (dotimes [_ 3600]
    (factory/step! factory/fixed-step))
  (let [state (factory/snapshot)
        house (factory/cell-view 15 12)
        picked (factory/screen-to-cell 380.0 188.0)
        valid
        (and
         (az/field state initialized)
         (> (az/field state coconuts_harvested) 0)
         (> (az/field state panels_produced) 0)
         (> (az/field state houses_completed) 0)
         (ak/== (az/field house inventory) (factory/house-panel-recipe))
         (ak/== (az/field picked x) 8)
         (ak/== (az/field picked y) 8))]
    (game/shutdown)
    (when (ak/! valid)
      (std-process/exit 1))))
