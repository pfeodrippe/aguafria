(ns aguafria-examples-native.imgui-bindings
  "Generate and activate the optional development-only Dear ImGui C boundary."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [aguafria-examples-native.build :as build]
            [clojure.java.io :as io]))

(defonce loaded? (atom false))

(defn binding-spec
  []
  (let [{:keys [root imgui-header]} (build/paths)]
    {:header imgui-header
     :output (io/file root
                      "generated/aguafria_examples_native/bindings/imgui.clj")
     :namespace 'aguafria-examples-native.bindings.imgui
     :include-dirs [(.getParentFile ^java.io.File imgui-header)]}))

(defn- current-binding?
  [{:keys [^java.io.File header ^java.io.File output]}]
  (and (.isFile output)
       (>= (.lastModified output) (.lastModified header))))

(defn ensure-loaded!
  []
  (when-not @loaded?
    (let [{:keys [header output namespace include-dirs] :as spec}
          (binding-spec)]
      (when-not (current-binding? spec)
        (ac/translate-header!
         header output
         {:namespace namespace
          :include-dirs include-dirs
          :cache-dir (str (io/file (:root (build/paths))
                                   ".aguafria/c-bindings"))
          :overwrite? true}))
      (build/prepare-imgui-shared!)
      (ac/load-bindings! output)
      (let [current (or (:zig-args (az/configuration)) [])
            monitor (build/imgui-development-link-arguments)]
        (az/configure! {:zig-args (vec (distinct (concat current monitor)))
                        :reloadable? true}))
      (reset! loaded? true)))
  {:loaded? @loaded?
   :bindings
   (when @loaded?
     (select-keys
      (ac/namespace-info 'aguafria-examples-native.bindings.imgui)
      [:namespace :count]))})

(ensure-loaded!)
