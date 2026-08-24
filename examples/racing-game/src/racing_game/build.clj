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

(defn build-standalone!
  []
  (native-build/prepare-static!)
  (prepare-shaders!)
  (package-model!)
  (package-action-head!)
  (package-shaders!)
  (package-manifest!)
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
      :zig-args (native-build/standalone-link-arguments)})))

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

(defn -main
  [& [command]]
  (case (or command "prepare")
    "prepare" (prn (prepare!))
    "standalone" (prn {:prepare (prepare!)
                        :artifact (build-standalone!)})
    "inference-probe" (prn {:prepare (prepare!)
                             :artifact (build-inference-probe!)})
    (throw (ex-info "Unknown racing-game build command"
                    {:command command
                     :supported ["prepare" "standalone"
                                 "inference-probe"]})))
  (shutdown-agents))
