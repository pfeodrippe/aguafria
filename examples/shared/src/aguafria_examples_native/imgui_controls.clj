(ns aguafria-examples-native.imgui-controls
  "Optional generated bindings for controls in the existing native ImGui frame."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [aguafria-examples-native.build :as build]
            [aguafria-examples-native.imgui-bindings]
            [clojure.java.io :as io]))

(defonce loaded? (atom false))

(defn ensure-loaded!
  []
  (let [library (str (build/imgui-controls-path :shared))]
    (when-not (= library @loaded?)
      (let [root (:root (build/paths))
          header (io/file root "resources/aguafria_examples_native/imgui_controls.h")
          output (io/file root "generated/aguafria_examples_native/bindings/imgui_controls.clj")]
      (build/prepare-imgui-controls! :shared)
      (when-not (and (.isFile output) (>= (.lastModified output) (.lastModified header)))
        (ac/translate-header! header output
          {:namespace 'aguafria-examples-native.bindings.imgui-controls
           :cache-dir (str (io/file root ".aguafria/c-bindings")) :overwrite? true}))
      (ac/load-bindings! output)
      (az/configure!
        {:zig-args (conj (vec (remove #(and (string? %)
                                        (re-find #"[/\\](?:lib)?aguafria_imgui_controls\.(?:dylib|so|dll)$" %))
                              (or (:zig-args (az/configuration)) []))) library)})
      (reset! loaded? library)))))

(ensure-loaded!)
