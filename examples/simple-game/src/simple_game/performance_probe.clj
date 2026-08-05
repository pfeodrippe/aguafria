(ns simple-game.performance-probe
  "Bounded ReleaseFast renderer measurement without changing the shipped HUD."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [simple-game.bindings.stdio :as stdio]
            [simple-game.desktop :as desktop]))

(az/defn main
  {:attrs #{:public}}
  :-
  :void
  []
  (let [timing (desktop/run-for-frames! 60 360)]
    (set! _
          (stdio/printf
           "frames=%llu average_ms=%.6f work_ms=%.6f simulation_ms=%.6f presentation_ms=%.6f\n"
           (ak/as :c_ulonglong (az/field timing frames))
           (ak/as :f64 (az/field timing average_ms))
           (ak/as :f64 (az/field timing work_average_ms))
           (ak/as :f64 (az/field timing simulation_average_ms))
           (ak/as :f64 (az/field timing presentation_average_ms))))))
