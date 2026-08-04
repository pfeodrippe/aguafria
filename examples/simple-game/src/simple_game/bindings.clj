(ns simple-game.bindings
  "Load generated external APIs source-only, then configure their Zig link."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [clojure.java.io :as io]
            [simple-game.build :as build]
            [simple-game.generate :as generate]))

(defonce loaded? (atom false))

(defn ensure-loaded!
  []
  (when-not @loaded?
    (let [specs (generate/binding-specs)
          missing (remove #(.isFile ^java.io.File (:output %)) specs)]
      (when (seq missing)
        (generate/generate!))
      (build/prepare-flecs!)
      ;; Flecs participates in game compilation. Vulkan/GLFW are loaded by the
      ;; desktop namespace only, keeping headless game-logic edits small.
      (let [flecs (first specs)]
        (ac/load-bindings! (:output flecs)))
      (az/configure! {:zig-args (build/link-arguments)
                      :reloadable? true})
      (reset! loaded? true)))
  {:loaded? @loaded?
   :flecs-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.flecs))})

(ensure-loaded!)
