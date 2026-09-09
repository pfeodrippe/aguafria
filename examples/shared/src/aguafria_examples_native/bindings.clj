(ns aguafria-examples-native.bindings
  "Load generated Flecs/GLFW/Vulkan bindings and native shared libraries."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [aguafria-examples-native.build :as build]
            [aguafria-examples-native.generate :as generate]))

(defonce loaded? (atom false))

(defn ensure-loaded!
  []
  (when-not @loaded?
    (let [specs (generate/binding-specs)
          missing (remove #(.isFile ^java.io.File (:output %)) specs)]
      (when (seq missing)
        (generate/generate!))
      (build/prepare-shared!)
      (doseq [{:keys [output]} specs]
        (ac/load-bindings! output))
      ;; Optional libraries (e.g. Box3D) can be required first. Loading the
      ;; graphics bindings must not discard their linker arguments. Preserve
      ;; argument groups verbatim: deduplicating individual tokens can corrupt
      ;; repeated options such as -framework followed by different names.
      (az/configure! {:zig-args (into (vec (:zig-args (az/configuration)))
                                     (build/development-link-arguments))
                      :reloadable? true})
      (reset! loaded? true)))
  {:loaded? @loaded?
   :flecs (when @loaded?
            (select-keys (ac/namespace-info
                          'aguafria-examples-native.bindings.flecs)
                         [:namespace :count]))
   :glfw (when @loaded?
           (select-keys (ac/namespace-info
                         'aguafria-examples-native.bindings.glfw)
                        [:namespace :count]))})

(ensure-loaded!)
