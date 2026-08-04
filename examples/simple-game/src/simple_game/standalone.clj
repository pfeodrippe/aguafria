(ns simple-game.standalone
  "ReleaseFast entry point emitted as a normal Zig executable."
  (:require [aguafria.zig :as az]
            [simple-game.desktop :as desktop]))

(az/defn main
  "Run the same Flecs/Vulkan game without the JVM or Aguafria runtime."
  {:attrs #{:public}}
  :-
  :void
  []
  (set! _ (desktop/run!)))
