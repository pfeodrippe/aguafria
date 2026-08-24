(ns racing-game.build
  "Prepare shaders and produce the JVM-free ReleaseFast racing executable."
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as zig-build]
            [aguafria-examples-native.build :as native-build]
            [aguafria-examples-native.vendor :as native-vendor]
            [clojure.java.io :as io]
            [racing-game.model :as model])
  (:import [java.nio.file Files StandardCopyOption]))

(defn project-root
  []
  (or
   (some (fn [^java.io.File directory]
           (when (and (.isFile (io/file directory "deps.edn"))
                      (.isDirectory (io/file directory "src/racing_game")))
             (.getCanonicalFile directory)))
         (take-while some?
                     (iterate #(.getParentFile ^java.io.File %)
                              (.getCanonicalFile
                               (io/file (System/getProperty "user.dir"))))))
   (throw (ex-info "Could not locate examples/racing-game"
                   {:user-dir (System/getProperty "user.dir")}))))

(defn run-command!
  [command]
  (native-vendor/run-command! command (project-root)))

(defn prepare-shaders!
  []
  (into {}
        (map
         (fn [name]
           (let [source (io/file (project-root) "resources/shaders" name)
                 output (io/file (str (.getAbsolutePath source) ".spv"))]
             (if (and (.isFile output)
                      (>= (.lastModified output) (.lastModified source)))
               [name :cached]
               (do
                 (run-command! ["glslc" (.getAbsolutePath source)
                                "-o" (.getAbsolutePath output)])
                 [name :built]))))
        ["mesh.vert" "mesh.frag"])))

(defn prepare!
  []
  {:native (native-build/prepare-shared!)
   :shaders (prepare-shaders!)})

(defn package-model!
  []
  (let [{:keys [file filename bytes sha256]} (model/verify!)
        output (io/file (project-root) "build/standalone/resources/models" filename)]
    (io/make-parents output)
    (if (and (.isFile output)
             (= bytes (.length output))
             (= sha256 (model/sha256 output)))
      {:status :cached :output output :bytes bytes :sha256 sha256}
      (do
        (Files/copy (.toPath ^java.io.File file) (.toPath output)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
        {:status :packaged :output output :bytes bytes :sha256 sha256}))))

(defn package-action-head!
  []
  (let [{:keys [file filename bytes sha256]} (model/verify-action-head!)
        output (io/file (project-root) "build/standalone/resources/models"
                        filename)]
    (io/make-parents output)
    (if (and (.isFile output)
             (= bytes (.length output))
             (= sha256 (model/sha256 output)))
      {:status :cached :output output :bytes bytes :sha256 sha256}
      (do
        (Files/copy (.toPath ^java.io.File file) (.toPath output)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
        {:status :packaged :output output :bytes bytes :sha256 sha256}))))

(defn package-shaders!
  []
  (into {}
        (map
         (fn [name]
           (let [source (io/file (project-root) "resources/shaders" (str name ".spv"))
                 output (io/file (project-root) "build/standalone/resources/shaders"
                                 (str name ".spv"))]
             (io/make-parents output)
             (if (and (.isFile output)
                      (= (.length source) (.length output))
                      (>= (.lastModified output) (.lastModified source)))
               [name {:status :cached :output output}]
               (do
                 (Files/copy (.toPath source) (.toPath output)
                             (into-array StandardCopyOption
                                         [StandardCopyOption/REPLACE_EXISTING]))
                 [name {:status :packaged :output output}]))))
        ["mesh.vert" "mesh.frag"])))

(defn package-manifest!
  []
  (let [source (io/file (project-root) "resources/models.edn")
        output (io/file (project-root) "build/standalone/resources/models.edn")]
    (io/make-parents output)
    (Files/copy (.toPath source) (.toPath output)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    {:status :packaged :output output}))

(defn- copy-release-file!
  [source target-name]
  (when-not (.isFile ^java.io.File source)
    (throw (ex-info "A required release notice or license is missing"
                    {:source (str source)
                     :target-name target-name})))
  (let [output (io/file (project-root) "build/standalone/licenses" target-name)
        source-sha256 (model/sha256 source)]
    (io/make-parents output)
    (if (and (.isFile output)
             (= (.length ^java.io.File source) (.length output))
             (= source-sha256 (model/sha256 output)))
      {:status :cached
       :output output
       :bytes (.length output)
       :sha256 source-sha256}
      (do
        (Files/copy (.toPath ^java.io.File source) (.toPath output)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
        {:status :packaged
         :output output
         :bytes (.length output)
         :sha256 source-sha256}))))

(defn package-licenses!
  "Package the model notice and exact licenses from every pinned native source."
  []
  (let [shared-root (native-vendor/project-root)
        racing-licenses (io/file (project-root) "resources/licenses")
        shared-vendor (io/file shared-root "build/vendor")]
    {:third-party-notices
     (copy-release-file!
      (io/file racing-licenses "THIRD_PARTY_NOTICES.md")
      "THIRD_PARTY_NOTICES.md")
     :apache-2.0
     (copy-release-file!
      (io/file racing-licenses "Apache-2.0.txt")
      "Apache-2.0.txt")
     :flecs
     (copy-release-file!
      (io/file shared-vendor "flecs/LICENSE")
      "Flecs-MIT.txt")
     :glfw
     (copy-release-file!
      (io/file shared-vendor "glfw/LICENSE.md")
      "GLFW-zlib.txt")
     :vulkan-headers
     (copy-release-file!
      (io/file shared-vendor "vulkan-headers/LICENSE.md")
      "Vulkan-Headers.txt")}))

(defn package-replay-fixture!
  []
  (let [source (io/file (project-root) "resources/replay/golden-r3.bin")
        output (io/file (project-root)
                        "build/standalone/resources/replay/golden-r3.bin")]
    (when-not (.isFile source)
      (throw (ex-info "Missing golden replay fixture; run `clojure -M:generate-replay-fixture`"
                      {:source source})))
    (io/make-parents output)
    (Files/copy (.toPath source) (.toPath output)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    {:status :packaged :output output :bytes (.length output)}))

(defn build-standalone!
  []
  (native-build/prepare-static!)
  (native-build/prepare-imgui-static!)
  (prepare-shaders!)
  (package-model!)
  (package-action-head!)
  (package-replay-fixture!)
  (package-shaders!)
  (package-manifest!)
  (package-licenses!)
  (zig-build/load-source-only! 'racing-game.standalone)
  (let [output (io/file (project-root) "build/standalone/racing-game")]
    (az/build!
     'racing-game.standalone
     {:kind :exe
      :name "racing-game"
      :output output
      :optimize "ReleaseFast"
      :reloadable? false
      :async? false
      :zig-args (native-build/imgui-standalone-link-arguments)})))

(defn build-inference-probe!
  "Build the same native graph without a window for repeatable timing."
  []
  (native-build/prepare-static!)
  (package-model!)
  (package-action-head!)
  (zig-build/load-source-only! 'racing-game.inference-performance-probe)
  (let [output (io/file (project-root) "build/standalone/inference-probe")]
    (az/build!
     'racing-game.inference-performance-probe
     {:kind :exe
      :name "inference-probe"
      :output output
      :optimize "ReleaseFast"
      :reloadable? false
      :async? false
      :zig-args (native-build/standalone-link-arguments)})))

(defn build-asset-probe!
  "Build the JVM-free offline checksum and compatibility gate."
  []
  (native-build/prepare-static!)
  (package-model!)
  (package-action-head!)
  (package-manifest!)
  (package-licenses!)
  (zig-build/load-source-only! 'racing-game.asset-probe)
  (let [output (io/file (project-root) "build/standalone/asset-probe")]
    (az/build!
     'racing-game.asset-probe
     {:kind :exe
      :name "asset-probe"
      :output output
      :optimize "ReleaseFast"
      :reloadable? false
      :async? false
      :zig-args (native-build/standalone-link-arguments)})))

(defn build-replay-parity-probe!
  "Build the JVM-free golden intent-capture/replay parity executable."
  []
  (native-build/prepare-static!)
  (package-replay-fixture!)
  (zig-build/load-source-only! 'racing-game.replay-parity-probe)
  (let [output (io/file (project-root) "build/standalone/replay-parity-probe")]
    (az/build!
     'racing-game.replay-parity-probe
     {:kind :exe
      :name "replay-parity-probe"
      :output output
      :optimize "ReleaseFast"
      :reloadable? false
      :async? false
      :zig-args (native-build/standalone-link-arguments)})))

(defn -main
  [& [command]]
  (case (or command "prepare")
    "prepare" (prn (prepare!))
    "standalone" (prn {:prepare (prepare!)
                        :artifact (build-standalone!)})
    "inference-probe" (prn {:prepare (prepare!)
                             :artifact (build-inference-probe!)})
    "asset-probe" (prn {:prepare (prepare!)
                         :artifact (build-asset-probe!)})
    "replay-parity-probe"
    (prn {:prepare (prepare!)
          :artifact (build-replay-parity-probe!)})
    (throw (ex-info "Unknown racing-game build command"
                    {:command command
                     :supported ["prepare" "standalone"
                                 "inference-probe"
                                 "asset-probe"
                                 "replay-parity-probe"]})))
  (shutdown-agents))
