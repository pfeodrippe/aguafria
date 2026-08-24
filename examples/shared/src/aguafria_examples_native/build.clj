(ns aguafria-examples-native.build
  "Build pinned Flecs/GLFW sources with Zig for development and release."
  (:require [aguafria-examples-native.vendor :as vendor]
            [clojure.java.io :as io]))

(defn paths
  []
  (let [root (vendor/project-root)
        vendor-root (io/file root "build/vendor")]
    {:root root
     :flecs-root (io/file vendor-root "flecs")
     :glfw-root (io/file vendor-root "glfw")
     :imgui-root (io/file vendor-root "imgui")
     :vulkan-root (io/file vendor-root "vulkan-headers")
     :flecs-shared (io/file root "build/native" (System/mapLibraryName "aguafria_flecs"))
     :flecs-static (io/file root "build/native/libaguafria_flecs.a")
     :glfw-shared (io/file root "build/native" (System/mapLibraryName "aguafria_glfw"))
     :glfw-static (io/file root "build/native/libaguafria_glfw.a")
     :imgui-shared (io/file root "build/native" (System/mapLibraryName "aguafria_imgui"))
     :imgui-static (io/file root "build/native/libaguafria_imgui.a")
     :imgui-header (io/file root "resources/aguafria_examples_native/imgui_monitor.h")
     :imgui-wrapper (io/file root "resources/aguafria_examples_native/imgui_monitor.cpp")
     :runtime-header (io/file root "resources/aguafria_examples_native/runtime.h")}))

(defn run-command!
  [command]
  (vendor/run-command! command (:root (paths))))

(defn vulkan-loader
  []
  (let [sdk (System/getenv "VULKAN_SDK")
        candidates (remove nil?
                           [(when sdk (io/file sdk "lib/libvulkan.dylib"))
                            (io/file "/opt/homebrew/lib/libvulkan.dylib")
                            (io/file "/usr/local/lib/libvulkan.dylib")])]
    (or (some #(when (.isFile ^java.io.File %)
                 (.getAbsolutePath ^java.io.File %))
              candidates)
        (throw (ex-info "Could not find the Vulkan loader; set VULKAN_SDK"
                        {:candidates (mapv str candidates)})))))

(defn- current-output?
  [^java.io.File output sources]
  (and (.isFile output)
       (pos? (.length output))
       (>= (.lastModified output)
           (reduce max 0 (map #(.lastModified ^java.io.File %) sources)))))

(defn- build-flecs!
  [mode]
  (vendor/checkout! :flecs)
  (let [{:keys [flecs-root flecs-shared flecs-static]} (paths)
        source (io/file flecs-root "distr/flecs.c")
        include (io/file flecs-root "distr")
        output (case mode :shared flecs-shared :static flecs-static)]
    (if (current-output? output [source])
      {:status :cached :mode mode :output output}
      (do
        (io/make-parents output)
        (run-command!
         ["zig" "build-lib"
          (case mode :shared "-dynamic" :static "-static")
          "-OReleaseFast" "-fPIC" "-DFLECS_NO_CPP"
          (str "-I" (.getAbsolutePath include))
          (str "-femit-bin=" (.getAbsolutePath output))
          (.getAbsolutePath source) "-lc"])
        {:status :built :mode mode :output output}))))

(def ^:private glfw-common-sources
  ["context.c" "init.c" "input.c" "monitor.c" "platform.c" "vulkan.c"
   "window.c" "egl_context.c" "osmesa_context.c" "null_init.c"
   "null_joystick.c" "null_monitor.c" "null_window.c"])

(def ^:private glfw-macos-sources
  ["cocoa_init.m" "cocoa_joystick.m" "cocoa_monitor.m" "cocoa_window.m"
   "nsgl_context.m" "posix_module.c" "posix_poll.c" "posix_thread.c"
   "macos_time.c"])

(defn- build-glfw!
  [mode]
  (vendor/checkout! :glfw)
  (let [{:keys [glfw-root glfw-shared glfw-static]} (paths)
        source-root (io/file glfw-root "src")
        include (io/file glfw-root "include")
        names (concat glfw-common-sources glfw-macos-sources)
        sources (mapv #(io/file source-root %) names)
        output (case mode :shared glfw-shared :static glfw-static)]
    (when-not (= "Mac OS X" (System/getProperty "os.name"))
      (throw (ex-info "The first shared GLFW recipe targets macOS"
                      {:os (System/getProperty "os.name")})))
    (if (current-output? output sources)
      {:status :cached :mode mode :output output}
      (do
        (io/make-parents output)
        (run-command!
         (vec
          (concat
           ["zig" "build-lib"
            (case mode :shared "-dynamic" :static "-static")
            "-OReleaseFast" "-fPIC" "-D_GLFW_COCOA"
            (str "-I" (.getAbsolutePath include))
            (str "-I" (.getAbsolutePath source-root))
            (str "-femit-bin=" (.getAbsolutePath output))]
           (map #(.getAbsolutePath ^java.io.File %) sources)
           ["-framework" "Cocoa" "-framework" "IOKit"
            "-framework" "CoreFoundation" "-framework" "QuartzCore" "-lc"])))
        {:status :built :mode mode :output output}))))

(defn prepare-imgui-shared!
  "Build the reusable development-only Dear ImGui GLFW/Vulkan monitor."
  []
  (vendor/checkout! :imgui)
  (vendor/checkout! :vulkan-headers)
  (build-glfw! :shared)
  (let [{:keys [imgui-root glfw-root vulkan-root imgui-shared
                imgui-header imgui-wrapper]} (paths)
        imgui-sources
        (mapv #(io/file imgui-root %)
              ["imgui.cpp" "imgui_draw.cpp" "imgui_tables.cpp" "imgui_widgets.cpp"
               "backends/imgui_impl_glfw.cpp" "backends/imgui_impl_vulkan.cpp"])
        sources (into [imgui-header imgui-wrapper] imgui-sources)]
    (if (current-output? imgui-shared sources)
      {:status :cached :mode :shared :output imgui-shared}
      (do
        (io/make-parents imgui-shared)
        (run-command!
         (vec
          (concat
           ["zig" "build-lib" "-dynamic" "-OReleaseFast" "-fPIC"
            (str "-I" (.getAbsolutePath imgui-root))
            (str "-I" (.getAbsolutePath (io/file imgui-root "backends")))
            (str "-I" (.getAbsolutePath (io/file glfw-root "include")))
            (str "-I" (.getAbsolutePath (io/file vulkan-root "include")))
            (str "-I" (.getAbsolutePath (.getParentFile imgui-header)))
            (str "-femit-bin=" (.getAbsolutePath imgui-shared))
            (.getAbsolutePath imgui-wrapper)]
           (map #(.getAbsolutePath ^java.io.File %) imgui-sources)
           [(.getAbsolutePath (:glfw-shared (paths)))
            (vulkan-loader)
            "-framework" "Cocoa" "-framework" "IOKit"
            "-framework" "CoreFoundation" "-framework" "QuartzCore"
            "-lc" "-lc++"])))
        {:status :built :mode :shared :output imgui-shared}))))

(defn prepare-imgui-static!
  "Build the native cognition UI into a release executable without a runtime
  shared-library dependency."
  []
  (vendor/checkout! :imgui)
  (vendor/checkout! :vulkan-headers)
  (let [{:keys [imgui-root glfw-root vulkan-root imgui-static
                imgui-header imgui-wrapper]} (paths)
        imgui-sources
        (mapv #(io/file imgui-root %)
              ["imgui.cpp" "imgui_draw.cpp" "imgui_tables.cpp" "imgui_widgets.cpp"
               "backends/imgui_impl_glfw.cpp" "backends/imgui_impl_vulkan.cpp"])
        sources (into [imgui-header imgui-wrapper] imgui-sources)]
    (if (current-output? imgui-static sources)
      {:status :cached :mode :static :output imgui-static}
      (do
        (io/make-parents imgui-static)
        (run-command!
         (vec
          (concat
           ["zig" "build-lib" "-static" "-OReleaseFast" "-fPIC"
            (str "-I" (.getAbsolutePath imgui-root))
            (str "-I" (.getAbsolutePath (io/file imgui-root "backends")))
            (str "-I" (.getAbsolutePath (io/file glfw-root "include")))
            (str "-I" (.getAbsolutePath (io/file vulkan-root "include")))
            (str "-I" (.getAbsolutePath (.getParentFile imgui-header)))
            (str "-femit-bin=" (.getAbsolutePath imgui-static))
            (.getAbsolutePath imgui-wrapper)]
           (map #(.getAbsolutePath ^java.io.File %) imgui-sources)
           ["-lc++"])))
        {:status :built :mode :static :output imgui-static}))))

(defn prepare-shared!
  []
  {:flecs (build-flecs! :shared)
   :glfw (build-glfw! :shared)
   :vulkan-loader (vulkan-loader)})

(defn prepare-static!
  []
  {:flecs (build-flecs! :static)
   :glfw (build-glfw! :static)
   :vulkan-loader (vulkan-loader)})

(defn development-link-arguments
  []
  (let [{:keys [flecs-shared glfw-shared]} (paths)]
    [(.getAbsolutePath flecs-shared)
     (.getAbsolutePath glfw-shared)
     (vulkan-loader)
     "-lc"]))

(defn imgui-development-link-arguments
  "Extra development links activated only by a monitor namespace."
  []
  (let [{:keys [imgui-shared glfw-shared]} (paths)]
    [(.getAbsolutePath imgui-shared)
     (.getAbsolutePath glfw-shared)
     (vulkan-loader)
     "-lc" "-lc++"]))

(defn standalone-link-arguments
  []
  (let [{:keys [flecs-static glfw-static]} (paths)]
    [(.getAbsolutePath flecs-static)
     (.getAbsolutePath glfw-static)
     (vulkan-loader)
     "-framework" "Cocoa"
     "-framework" "IOKit"
     "-framework" "CoreFoundation"
     "-framework" "QuartzCore"
     "-lc"]))

(defn imgui-standalone-link-arguments
  "Static release links for applications that intentionally ship the native
  cognition UI."
  []
  (let [{:keys [flecs-static glfw-static imgui-static]} (paths)]
    [(.getAbsolutePath flecs-static)
     (.getAbsolutePath imgui-static)
     (.getAbsolutePath glfw-static)
     (vulkan-loader)
     "-framework" "Cocoa"
     "-framework" "IOKit"
     "-framework" "CoreFoundation"
     "-framework" "QuartzCore"
     "-lc" "-lc++"]))
