(ns simple-game.hot-reload-target
  "Native caller used to measure cross-namespace hot publication."
  (:require [aguafria.zig :as az]
            [simple-game.factory :as factory]))

(az/defn press-duration-caller :f32
  "Call the live factory tuning Var from an already-compiled namespace."
  []
  (factory/press-duration))
