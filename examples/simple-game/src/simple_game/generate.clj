(ns simple-game.generate
  "Generate inspectable Aguafria bindings from upstream C headers."
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]
            [simple-game.build :as build]))

(defn binding-specs
  []
  (let [root (:root (build/paths))]
    [{:name :flecs
      :header (io/file root "vendor/flecs/distr/flecs.h")
      :output (io/file root "generated/simple_game/bindings/flecs.clj")
      :namespace 'simple-game.bindings.flecs
      :include-dirs [(io/file root "vendor/flecs/distr")]}
     {:name :box3d
      :header (io/file root "vendor/box3d/include/box3d/box3d.h")
      :output (io/file root "generated/simple_game/bindings/box3d.clj")
      :namespace 'simple-game.bindings.box3d
      :include-dirs [(io/file root "vendor/box3d/include")]}
     {:name :miniaudio
      :header (io/file root "vendor/miniaudio/miniaudio.h")
      :output (io/file root "generated/simple_game/bindings/miniaudio.clj")
      :namespace 'simple-game.bindings.miniaudio
      :include-dirs [(io/file root "vendor/miniaudio")]}
     {:name :vulkan
      :header (io/file root "vendor/vulkan-headers/include/vulkan/vulkan.h")
      :output (io/file root "generated/simple_game/bindings/vulkan.clj")
      :namespace 'simple-game.bindings.vulkan
      :include-dirs [(io/file root "vendor/vulkan-headers/include")]}
     {:name :glfw
      :header (io/file root "vendor/glfw/include/GLFW/glfw3.h")
      :output (io/file root "generated/simple_game/bindings/glfw.clj")
      :namespace 'simple-game.bindings.glfw
      :include-dirs [(io/file root "vendor/glfw/include")
                     (io/file root "vendor/vulkan-headers/include")]
      :defines {:GLFW_INCLUDE_VULKAN true}}]))

(defn web-binding-specs
  []
  (let [root (:root (build/paths))
        sysroot (build/emscripten-sysroot)
        include (io/file sysroot "include")
        glfw (some #(when (= :glfw (:name %)) %) (binding-specs))]
    [glfw
     {:name :webgl
      :header (io/file include "GLES3/gl3.h")
      :output (io/file root "generated/simple_game/bindings/webgl.clj")
      :namespace 'simple-game.bindings.webgl
      :include-dirs [include]
      :target "wasm32-emscripten"}
     {:name :emscripten
      :header (io/file include "emscripten/emscripten.h")
      :output (io/file root "generated/simple_game/bindings/emscripten.clj")
      :namespace 'simple-game.bindings.emscripten
      :include-dirs [include]
      ;; Zig 0.16's bundled Clang accepts one deprecated message while the
      ;; current Emscripten header supplies an optional replacement message.
      ;; Attributes are documentation/optimizer hints, not ABI, so stripping
      ;; them only for structural translation preserves the declarations.
      :defines {"__attribute__(x)" ""}
      :target "wasm32-emscripten"}]))

(defn- generate-specs!
  [specs]
  (let [root (:root (build/paths))]
    (mapv
     (fn [{:keys [name header output namespace include-dirs defines target args]}]
       (assoc
        (ac/translate-header!
         header output
         {:namespace namespace
          :include-dirs include-dirs
          :defines defines
          :target target
          :args args
          :cache-dir (str (io/file root ".aguafria/c-bindings"))
          :overwrite? true})
        :binding name))
     specs)))

(defn generate!
  []
  (generate-specs! (binding-specs)))

(defn generate-web!
  []
  (generate-specs! (web-binding-specs)))

(defn summary
  [reports]
  (mapv #(select-keys % [:binding :namespace :declaration-count
                          :fallback-count :cache-hit? :elapsed-ms])
        reports))

(defn -main
  [& [target]]
  (prn (summary
        (case (or target "desktop")
          "desktop" (generate!)
          "web" (generate-web!)
          "all" (into (generate!) (generate-web!))
          (throw (ex-info "Unknown binding target"
                          {:target target
                           :supported ["desktop" "web" "all"]})))))
  (shutdown-agents))
