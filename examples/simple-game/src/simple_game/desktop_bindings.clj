(ns simple-game.desktop-bindings
  "Load generated GLFW/Vulkan bindings and configure desktop native links."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [simple-game.bindings]
            [simple-game.build :as build]
            [simple-game.generate :as generate]))

(defonce loaded? (atom false))

(defn ensure-loaded!
  []
  (when-not @loaded?
    (let [specs (generate/binding-specs)
          glfw (some #(when (= 'simple-game.bindings.glfw (:namespace %)) %) specs)]
      (when-not glfw
        (throw (ex-info "The GLFW binding specification is missing" {})))
      (when-not (.isFile ^java.io.File (:output glfw))
        (generate/generate!))
      (build/prepare-glfw!)
      (ac/load-bindings! (:output glfw))
      (az/configure! {:zig-args (build/desktop-link-arguments)
                      :reloadable? true})
      (reset! loaded? true)))
  {:loaded? @loaded?
   :glfw-bindings
   (when @loaded? (ac/namespace-info 'simple-game.bindings.glfw))})

(ensure-loaded!)
