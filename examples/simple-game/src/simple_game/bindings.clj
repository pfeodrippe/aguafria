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
      (build/prepare-box3d!)
      (build/prepare-miniaudio!)
      ;; Game/Flecs, particle physics, and click audio are all ordinary,
      ;; inspectable Aguafria dependencies. Vulkan/GLFW stay desktop-only.
      (doseq [name [:flecs :box3d :miniaudio]
              :let [spec (some #(when (= name (:name %)) %) specs)]]
        (ac/load-bindings! (:output spec)))
      (az/configure! {:zig-args (build/link-arguments)
                      :reloadable? true})
      (reset! loaded? true)))
  {:loaded? @loaded?
   :flecs-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.flecs))
   :box3d-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.box3d))
   :miniaudio-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.miniaudio))})

(ensure-loaded!)
