(ns simple-game.behavior-probe
  "Headless ReleaseFast check for behavior also exercised through the nREPL."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.process :as std-process]
            [aguafria.zig :as az]
            [simple-game.game :as game]))

(az/defn main
  "Exit unsuccessfully if optimized gameplay differs from the Debug contract."
  {:attrs #{:public}}
  :-
  :void
  []
  (when (ak/!
         (and
          (game/circle-contains? 360.0 270.0 360.0 270.0 90.0)
          (ak/! (game/circle-contains? 500.0 270.0 360.0 270.0 90.0))
          (ak/== (game/animated-phase 2.0 0.25 true) 3.0)
          (ak/== (game/animated-phase 2.0 0.25 false) 2.0)
          (ak/== (game/shader-for-count 0) 0)
          (ak/== (game/shader-for-count 1) 1)
          (ak/== (game/shader-for-count 2) 2)
          (ak/== (game/shader-for-count 3) 3)
          (ak/== (game/shader-for-count 4) 4)
          (ak/== (game/shader-for-count 5) 0)
          (ak/== (game/shader-for-count 6) 1)))
    (std-process/exit 1)))
