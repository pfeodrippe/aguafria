(ns simple-game.build
  "Zig-only dependency and standalone preparation for simple-game."
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as zig-build]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn project-root
  []
  (or
   (some (fn [^java.io.File directory]
           (when (and (.isFile (io/file directory "deps.edn"))
                      (.isFile (io/file directory "vendor-lock.edn"))
                      (.isDirectory (io/file directory "vendor/flecs")))
             (.getCanonicalFile directory)))
         (take-while some?
                     (iterate #(.getParentFile ^java.io.File %)
                              (.getCanonicalFile
                               (io/file (System/getProperty "user.dir"))))))
   (throw (ex-info "Could not find the simple-game project root"
                   {:user-dir (System/getProperty "user.dir")}))))

(defn paths
  []
  (let [root (project-root)]
    {:root root
     :flecs-source (io/file root "vendor/flecs/distr/flecs.c")
     :flecs-include (io/file root "vendor/flecs/distr")
     :flecs-static-library (io/file root "build/native/libflecs.a")
     :flecs-web-object (io/file root "build/web/flecs-game.o")
     :flecs-shared-library (io/file root "build/native"
                                    (System/mapLibraryName "flecs"))
     :box3d-source-root (io/file root "vendor/box3d/src")
     :box3d-include (io/file root "vendor/box3d/include")
     :box3d-static-library (io/file root "build/native/libbox3d.a")
     :box3d-shared-library (io/file root "build/native"
                                    (System/mapLibraryName "box3d"))
     :miniaudio-source (io/file root "vendor/miniaudio/miniaudio.c")
     :miniaudio-include (io/file root "vendor/miniaudio")
     :miniaudio-static-library (io/file root "build/native/libminiaudio.a")
     :miniaudio-shared-library (io/file root "build/native"
                                        (System/mapLibraryName "miniaudio"))
     :glfw-include (io/file root "vendor/glfw/include")
     :glfw-source-root (io/file root "vendor/glfw/src")
     :glfw-static-library (io/file root "build/native/libglfw.a")
     :glfw-shared-library (io/file root "build/native"
                                   (System/mapLibraryName "glfw"))
     :web-build-root (io/file root "build/web")}))

(declare run-command!)

(defn emscripten-root
  "Resolve the Emscripten SDK used only for web headers and final packaging."
  []
  (let [configured (or (System/getenv "EMSCRIPTEN")
                       (System/getenv "EMSDK"))
        configured (when configured (io/file configured))
        command-result (when-not (and configured (.isDirectory configured))
                         (run-command! ["em-config" "EMSCRIPTEN_ROOT"]
                                       (:root (paths))))
        resolved (if (and configured (.isDirectory configured))
                   configured
                   (io/file (str/trim (:output command-result))))]
    (when-not (.isDirectory resolved)
      (throw (ex-info
              "Could not find Emscripten; set EMSCRIPTEN or put em-config in PATH"
              {:resolved (.getAbsolutePath resolved)})))
    (.getCanonicalFile resolved)))

(defn emscripten-sysroot
  []
  (let [root (emscripten-root)
        sysroot (io/file root "cache/sysroot")]
    (when-not (.isDirectory sysroot)
      (throw (ex-info "Emscripten sysroot is missing"
                      {:root (.getAbsolutePath root)
                       :sysroot (.getAbsolutePath sysroot)})))
    (.getCanonicalFile sysroot)))

(defn- run-command!
  [command directory]
  (let [builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory directory)
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit (.waitFor process)
        result {:command (vec command)
                :directory (.getAbsolutePath ^java.io.File directory)
                :exit exit
                :output output}]
    (when-not (zero? exit)
      (throw (ex-info
              (str "error[simple-game::zig-build]: Zig command failed\n"
                   "  = command: " (str/join " " command) "\n\n" output)
              result)))
    result))

(defn- prepare-flecs-library!
  [mode output]
  (let [{:keys [root flecs-source flecs-include]} (paths)
        current? (and (.isFile output)
                      (>= (.lastModified output)
                          (.lastModified flecs-source)))]
    (if current?
      {:status :cached
       :mode mode
       :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [result
              (run-command!
               ["zig" "build-lib"
                (case mode :shared "-dynamic" :static "-static")
                "-OReleaseFast" "-fPIC"
                (str "-femit-bin=" (.getAbsolutePath output))
                (str "-I" (.getAbsolutePath flecs-include))
                (.getAbsolutePath flecs-source)
                "-lc"]
               root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-flecs!
  "Compile the unmodified vendored Flecs C amalgamation with Zig.
  Development uses one shared library so every hot-reload generation observes
  the same Flecs process globals. Standalone links retain a static archive."
  []
  (let [{:keys [flecs-static-library flecs-shared-library]} (paths)]
    {:shared (prepare-flecs-library! :shared flecs-shared-library)
     :static (prepare-flecs-library! :static flecs-static-library)}))

(defn- box3d-sources
  []
  (->> (.listFiles ^java.io.File (:box3d-source-root (paths)))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".c")))
       (sort-by #(.getName ^java.io.File %))
       vec))

(defn- prepare-box3d-library!
  [mode output]
  (let [{:keys [root box3d-source-root box3d-include]} (paths)
        sources (box3d-sources)
        newest-input (reduce max (.lastModified (io/file box3d-include
                                                         "box3d/box3d.h"))
                             (map #(.lastModified ^java.io.File %) sources))
        current? (and (.isFile output)
                      (>= (.lastModified output) newest-input))]
    (if current?
      {:status :cached :mode mode :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 (case mode :shared "-dynamic" :static "-static")
                 "-OReleaseFast" "-fPIC"
                 (str "-I" (.getAbsolutePath box3d-include))
                 (str "-I" (.getAbsolutePath box3d-source-root))
                 (str "-femit-bin=" (.getAbsolutePath output))]
                (when (= :shared mode) ["-Dbox3d_EXPORTS"])
                ["-cflags" "-std=c17" "--"]
                (map #(.getAbsolutePath ^java.io.File %) sources)
                ["-lc"]))
              result (run-command! command root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-box3d!
  "Compile unmodified Box3D C sources with Zig for dev and standalone use."
  []
  (let [{:keys [box3d-static-library box3d-shared-library]} (paths)]
    {:shared (prepare-box3d-library! :shared box3d-shared-library)
     :static (prepare-box3d-library! :static box3d-static-library)}))

(defn- prepare-miniaudio-library!
  [mode output]
  (let [{:keys [root miniaudio-source miniaudio-include]} (paths)
        newest-input (max (.lastModified miniaudio-source)
                          (.lastModified (io/file miniaudio-include
                                                  "miniaudio.h")))
        current? (and (.isFile output)
                      (>= (.lastModified output) newest-input))]
    (if current?
      {:status :cached :mode mode :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 (case mode :shared "-dynamic" :static "-static")
                 "-OReleaseFast" "-fPIC"
                 (str "-I" (.getAbsolutePath miniaudio-include))
                 (str "-femit-bin=" (.getAbsolutePath output))
                 (.getAbsolutePath miniaudio-source)
                 "-lc"]
                (when (= "Mac OS X" (System/getProperty "os.name"))
                  ["-framework" "CoreAudio"
                   "-framework" "AudioToolbox"
                   "-framework" "CoreFoundation"])))
              result (run-command! command root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-miniaudio!
  "Compile unmodified miniaudio C implementation with Zig."
  []
  (let [{:keys [miniaudio-static-library miniaudio-shared-library]} (paths)]
    {:shared (prepare-miniaudio-library! :shared miniaudio-shared-library)
     :static (prepare-miniaudio-library! :static miniaudio-static-library)}))

(defn prepare-web-flecs!
  "Compile unmodified Flecs for the browser with only the addons the game uses."
  []
  (let [{:keys [root flecs-source flecs-include flecs-web-object]} (paths)
        include (io/file (emscripten-sysroot) "include")
        newest-input (max (.lastModified flecs-source)
                          (.lastModified (io/file flecs-include "flecs.h")))
        current? (and (.isFile flecs-web-object)
                      (>= (.lastModified flecs-web-object) newest-input))]
    (if current?
      {:status :cached
       :output-path (.getAbsolutePath flecs-web-object)}
      (do
        (io/make-parents flecs-web-object)
        (let [command ["zig" "build-obj"
                       "-target" "wasm32-emscripten"
                       "-OReleaseFast"
                       (str "-femit-bin=" (.getAbsolutePath flecs-web-object))
                       (str "-I" (.getAbsolutePath flecs-include))
                       "-isystem" (.getAbsolutePath include)
                       "-DFLECS_CUSTOM_BUILD"
                       "-DFLECS_PIPELINE"
                       "-DFLECS_QUERY_DSL"
                       (.getAbsolutePath flecs-source)
                       "-lc"]
              result (run-command! command root)]
          {:status :built
           :output-path (.getAbsolutePath flecs-web-object)
           :command (:command result)})))))

(def ^:private glfw-common-sources
  ["context.c" "init.c" "input.c" "monitor.c" "platform.c" "vulkan.c"
   "window.c" "egl_context.c" "osmesa_context.c" "null_init.c"
   "null_joystick.c" "null_monitor.c" "null_window.c"])

(def ^:private glfw-macos-sources
  ["cocoa_init.m" "cocoa_joystick.m" "cocoa_monitor.m" "cocoa_window.m"
   "nsgl_context.m" "posix_module.c" "posix_poll.c" "posix_thread.c"
   "macos_time.c"])

(defn- prepare-glfw-library!
  [mode output]
  (let [{:keys [root glfw-include glfw-source-root]} (paths)
        macos? (= "Mac OS X" (System/getProperty "os.name"))
        source-names (if macos?
                       (concat glfw-common-sources glfw-macos-sources)
                       (throw (ex-info
                               "The first GLFW build recipe currently targets macOS"
                               {:os (System/getProperty "os.name")})))
        sources (mapv #(io/file glfw-source-root %) source-names)
        newest-source (reduce max 0 (map #(.lastModified ^java.io.File %) sources))
        current? (and (.isFile output)
                      (>= (.lastModified output) newest-source))]
    (if current?
      {:status :cached
       :mode mode
       :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 (case mode :shared "-dynamic" :static "-static")
                 "-OReleaseFast" "-fPIC"
                 "-D_GLFW_COCOA"
                 (str "-I" (.getAbsolutePath glfw-include))
                 (str "-I" (.getAbsolutePath glfw-source-root))
                 (str "-femit-bin=" (.getAbsolutePath output))]
                (map #(.getAbsolutePath ^java.io.File %) sources)
                ["-framework" "Cocoa" "-framework" "IOKit"
                 "-framework" "CoreFoundation" "-framework" "QuartzCore"
                 "-lc"]))
              result (run-command! command root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-glfw!
  "Compile vendored GLFW master directly with Zig for hot-reload development."
  []
  (prepare-glfw-library! :shared (:glfw-shared-library (paths))))

(defn prepare-static-glfw!
  "Compile vendored GLFW master directly with Zig for standalone linking."
  []
  (prepare-glfw-library! :static (:glfw-static-library (paths))))

(defn vulkan-loader
  "Resolve the desktop Vulkan loader without baking it into generated code."
  []
  (let [sdk (System/getenv "VULKAN_SDK")
        candidates (remove nil?
                           [(when sdk (io/file sdk "lib/libvulkan.dylib"))
                            (io/file "/opt/homebrew/lib/libvulkan.dylib")
                            (io/file "/usr/local/lib/libvulkan.dylib")])]
    (or (some #(when (.isFile ^java.io.File %) (.getAbsolutePath ^java.io.File %))
              candidates)
        (throw (ex-info
                "Could not find a Vulkan loader; set VULKAN_SDK"
                {:candidates (mapv str candidates)})))))

(defn link-arguments
  "Development links. Each upstream dylib owns its long-lived native globals."
  []
  (let [{:keys [flecs-shared-library box3d-shared-library
                miniaudio-shared-library]} (paths)]
    [(.getAbsolutePath flecs-shared-library)
     (.getAbsolutePath box3d-shared-library)
     (.getAbsolutePath miniaudio-shared-library)
     "-lc"]))

(defn standalone-link-arguments
  "Release/standalone link arguments; no development shared library required."
  []
  (let [{:keys [flecs-static-library glfw-static-library box3d-static-library
                miniaudio-static-library]} (paths)]
    [(.getAbsolutePath flecs-static-library)
     (.getAbsolutePath glfw-static-library)
     (.getAbsolutePath box3d-static-library)
     (.getAbsolutePath miniaudio-static-library)
     (vulkan-loader)
     "-framework" "Cocoa"
     "-framework" "IOKit"
     "-framework" "CoreFoundation"
     "-framework" "QuartzCore"
     "-framework" "CoreAudio"
     "-framework" "AudioToolbox"
     "-lc"]))

(defn desktop-link-arguments
  "Development links for game logic, GLFW, and the system Vulkan loader."
  []
  (let [{:keys [flecs-shared-library glfw-shared-library box3d-shared-library
                miniaudio-shared-library]} (paths)]
    [(.getAbsolutePath flecs-shared-library)
     (.getAbsolutePath glfw-shared-library)
     (.getAbsolutePath box3d-shared-library)
     (.getAbsolutePath miniaudio-shared-library)
     (vulkan-loader)
     "-lc"]))

(defn build-standalone!
  "Build the complete game as a ReleaseFast native executable.
  Clojure is only the build frontend; the artifact has no JVM dependency."
  []
  (prepare-flecs!)
  (prepare-box3d!)
  (prepare-miniaudio!)
  (prepare-static-glfw!)
  (zig-build/load-source-only! 'simple-game.standalone)
  (let [output (io/file (:root (paths)) "build/standalone/simple-game")]
    (az/build!
     'simple-game.standalone
     {:kind :exe
      :name "simple-game"
      :output output
      :optimize "ReleaseFast"
      :reloadable? false
      :async? false
      :zig-args (standalone-link-arguments)})))

(defn- copy-web-assets!
  [output-directory]
  (doseq [name ["index.html" "app.js"]]
    (let [source (io/file (:root (paths)) "resources/web" name)
          output (io/file output-directory name)]
      (when-not (.isFile source)
        (throw (ex-info "Web asset is missing" {:asset (.getAbsolutePath source)})))
      (io/make-parents output)
      (with-open [input (io/input-stream source)
                  stream (io/output-stream output)]
        (io/copy input stream))))
  output-directory)

(defn build-web!
  "Build the Aguafria game and unmodified Flecs C source to browser WebAssembly."
  []
  (let [{:keys [root web-build-root flecs-web-object]} (paths)
        generated-directory (io/file root "generated/simple_game/bindings")]
    (when-not (every? #(.isFile ^java.io.File %)
                      [(io/file generated-directory "webgl.clj")
                       (io/file generated-directory "emscripten.clj")])
      ((requiring-resolve 'simple-game.generate/generate-web!)))
    (let [flecs (prepare-web-flecs!)]
      (zig-build/load-source-only! 'simple-game.web)
      (let [game-object (io/file web-build-root "simple-game.o")
            native-object
            (az/build! 'simple-game.web
                       {:kind :object
                        :name "simple-game-web"
                        :output game-object
                        :target "wasm32-emscripten"
                        :optimize "ReleaseFast"
                        :reloadable? false
                        :async? false
                        :zig-args []})
            javascript (io/file web-build-root "simple-game.js")
            emcc-command
            ["emcc"
             (.getAbsolutePath game-object)
             (.getAbsolutePath flecs-web-object)
             "-O3"
             "-o" (.getAbsolutePath javascript)
             "-sMODULARIZE=1"
             "-sEXPORT_NAME=createAguafriaGame"
             "-sENVIRONMENT=web"
             "-sFILESYSTEM=0"
             "-sALLOW_MEMORY_GROWTH=1"
             "-sMIN_WEBGL_VERSION=2"
             "-sMAX_WEBGL_VERSION=2"
             "-sFULL_ES3=1"
             "-sUSE_GLFW=3"
             "-sNO_EXIT_RUNTIME=1"
             "--no-entry"
             "-sEXPORTED_FUNCTIONS=['_web_start','_web_stop']"]
            package (run-command! emcc-command root)]
        (copy-web-assets! web-build-root)
        {:flecs flecs
         :native-object native-object
         :package {:command (:command package)
                   :javascript-path (.getAbsolutePath javascript)
                   :wasm-path (.getAbsolutePath
                               (io/file web-build-root "simple-game.wasm"))
                   :html-path (.getAbsolutePath
                               (io/file web-build-root "index.html"))}}))))

(defn -main
  [& [command]]
  (case (or command "prepare")
    "prepare" (prn {:flecs (prepare-flecs!)
                    :box3d (prepare-box3d!)
                    :miniaudio (prepare-miniaudio!)
                    :glfw (prepare-glfw!)
                    :vulkan-loader (vulkan-loader)})
    "desktop" (prn {:flecs (prepare-flecs!)
                     :box3d (prepare-box3d!)
                     :miniaudio (prepare-miniaudio!)
                     :glfw (prepare-glfw!)
                     :vulkan-loader (vulkan-loader)})
    "standalone" (prn {:flecs (prepare-flecs!)
                        :box3d (prepare-box3d!)
                        :miniaudio (prepare-miniaudio!)
                        :glfw (prepare-static-glfw!)
                        :vulkan-loader (vulkan-loader)
                        :artifact (build-standalone!)})
    "web" (prn (build-web!))
    (throw (ex-info "Unknown simple-game build command"
                    {:command command
                     :supported ["prepare" "desktop" "standalone" "web"]})))
  (shutdown-agents))
