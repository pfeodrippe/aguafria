(ns aguafria-examples-native.generate
  "Generate ordinary Aguafria namespaces from shared native C headers."
  (:require [aguafria.c :as ac]
            [aguafria-examples-native.build :as build]
            [aguafria-examples-native.vendor :as vendor]
            [clojure.java.io :as io]))

(defn binding-specs
  []
  (vendor/ensure!)
  (let [{:keys [root flecs-root glfw-root vulkan-root runtime-header]} (build/paths)]
    [{:name :flecs
      :header (io/file flecs-root "distr/flecs.h")
      :output (io/file root "generated/aguafria_examples_native/bindings/flecs.clj")
      :namespace 'aguafria-examples-native.bindings.flecs
      :include-dirs [(io/file flecs-root "distr")]}
     {:name :glfw
      :header (io/file glfw-root "include/GLFW/glfw3.h")
      :output (io/file root "generated/aguafria_examples_native/bindings/glfw.clj")
      :namespace 'aguafria-examples-native.bindings.glfw
      :include-dirs [(io/file glfw-root "include")
                     (io/file vulkan-root "include")]
      :defines {:GLFW_INCLUDE_VULKAN true}}
     {:name :runtime
      :header runtime-header
      :output (io/file root "generated/aguafria_examples_native/bindings/runtime.clj")
      :namespace 'aguafria-examples-native.bindings.runtime
      :include-dirs [(.getParentFile runtime-header)]}]))

(defn generate!
  []
  (let [root (:root (build/paths))]
    (mapv
     (fn [{:keys [name header output namespace include-dirs defines]}]
       (assoc
        (ac/translate-header!
         header output
         {:namespace namespace
          :include-dirs include-dirs
          :defines defines
          :cache-dir (str (io/file root ".aguafria/c-bindings"))
          :overwrite? true})
        :binding name))
     (binding-specs))))

