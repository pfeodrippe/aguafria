(ns simple-game.bindings
  "Load only the C APIs used by the active coco-factory native graph."
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
      (build/prepare-stb-truetype!)
      (build/prepare-font-assets!)
      (doseq [name [:flecs :stb-truetype :stdio]
              :let [spec (some #(when (= name (:name %)) %) specs)]]
        (ac/load-bindings! (:output spec)))
      (az/configure! {:zig-args (build/link-arguments)
                      :reloadable? true})
      (reset! loaded? true)))
  {:loaded? @loaded?
   :flecs-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.flecs))
   :stb-truetype-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.stb-truetype))
   :stdio-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.stdio))})

(ensure-loaded!)
