(ns simple-game.hot-reload-target
  "Native caller used to measure cross-namespace hot publication."
  (:require [aguafria.zig :as az]
            [simple-game.factory :as factory]))

(az/defn press-duration-caller
  "Call the live factory tuning Var from an already-compiled namespace."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  []
  (factory/press-duration))
