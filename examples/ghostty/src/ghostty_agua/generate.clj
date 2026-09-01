(ns ghostty-agua.generate
  "Ghostty conversion and standalone-project tooling.

  This namespace deliberately has no dependency on generated output, so it can
  be required from a clean checkout before `examples/ghostty/generated` exists."
  (:require [aguafria.zig :as az]
            [aguafria.zig.convert :as convert]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(def build-profile
  "The C-ABI libghostty-vt profile used for nREPL calls and standalone builds.

  Ghostty's `test-lib-vt` step deliberately builds the same Zig root once with
  the Zig ABI and once with the C ABI, which gives that root two incompatible
  `terminal_options` modules. The installed library profile is unambiguous and
  captures the C-ABI options used by the callable native artifact."
  ["-Demit-lib-vt=true"
   "-Demit-xcframework=false"
   "-Demit-macos-app=false"])

(def macos-source-module-profile
  "The full app profile used only to discover optional named Zig modules.

  Its generated build options intentionally do not replace the libghostty-vt
  options used by nREPL development. It contributes source-module edges such
  as Ghostty's optional `sentry` package so the independently materialized app
  retains the same imports as the original Zig build."
  ["-Demit-macos-app=true"
   "-Demit-lib-vt=false"])

(defn repository-root
  "Find this Aguafria checkout from the current working directory."
  []
  (or
   (some
    (fn [^java.io.File directory]
      (when (and (.isFile (io/file directory "src/aguafria/zig.clj"))
                 (.isDirectory
                  (io/file directory "examples/ghostty/vendor/ghostty")))
        (.getCanonicalFile directory)))
    (take-while some?
                (iterate #(.getParentFile ^java.io.File %)
                         (.getCanonicalFile
                          (io/file (System/getProperty "user.dir"))))))
   (throw
    (ex-info
     (str "Could not find the Aguafria checkout or "
          "examples/ghostty/vendor/ghostty submodule")
     {:user-dir (System/getProperty "user.dir")}))))

(defn project-paths
  "Return Ghostty input, generated, report, and standalone paths."
  []
  (let [root (repository-root)]
    {:repository-root (.getAbsolutePath root)
     :input-root (.getAbsolutePath
                  (io/file root "examples/ghostty/vendor/ghostty"))
     :output-root (.getAbsolutePath
                   (io/file root "examples/ghostty/generated"))
     :report-output (.getAbsolutePath
                     (io/file root
                              "examples/ghostty/generated/ghostty-report.edn"))
     :standalone-root (.getAbsolutePath
                       (io/file root "examples/ghostty/build/standalone"))}))

(defn generated?
  "True when Ghostty's generated terminal root namespace exists."
  ([] (generated? (:output-root (project-paths))))
  ([output-root]
   (.isFile (io/file output-root "ghostty/src/terminal/main.clj"))))

(defn generation-summary
  "Return the bounded, useful portion of a conversion report."
  [report]
  (select-keys report
               [:input-root
                :output-root
                :file-count
                :declaration-count
                :structural-declaration-count
                :raw-declaration-count
                :fallback-count
                :unresolved-syntax-count
                :generated-module-count
                :asset-file-count
                :build-profiles
                :elapsed-ms]))

(defn generate!
  "Convert the complete vendored Ghostty project into Aguafria namespaces."
  ([] (generate! {}))
  ([options]
   (let [paths (project-paths)
         input-root (or (:input-root options) (:input-root paths))
         output-root (or (:output-root options) (:output-root paths))
         report-output (or (:report-output options) (:report-output paths))]
     (convert/convert-tree!
      input-root
      output-root
      (merge {:namespace-prefix 'ghostty
              :overwrite? true
              :bundle-assets? true
              ;; Ghostty's ignored dependency cache is not Ghostty source.
              ;; Standalone builds resolve these ordinary dependencies through
              ;; build.zig.zon, so they remain native and need no conversion.
              :exclude-directories #{"zig-pkg"}
              :build-profiles [build-profile]
              :source-module-build-profiles
              [macos-source-module-profile]
              :report-output report-output}
             (dissoc options :input-root :output-root :report-output))))))

(defn ensure-generated!
  "Generate Ghostty only when its generated terminal namespace is absent."
  []
  (if (generated?)
    {:status :present :output-root (:output-root (project-paths))}
    {:status :generated :report (generation-summary (generate!))}))

(defn materialize!
  "Regenerate an ordinary Ghostty source tree without reading vendored Zig."
  ([] (materialize! {}))
  ([options]
   (let [{:keys [report-output standalone-root]} (project-paths)]
     (convert/materialize-project!
      report-output
      (or (:output-root options) standalone-root)
      (merge {:overwrite? true} (dissoc options :output-root))))))

(defn materialization-summary
  "Return counts and paths without printing the complete materialized graph."
  [report]
  (select-keys report
               [:output-root
                :file-count
                :zig-file-count
                :asset-file-count
                :written-count
                :elapsed-ms]))

(defn- run-command!
  [command directory]
  (let [zig (.getAbsoluteFile (io/file (az/zig-executable)))
        command (mapv #(if (= "zig" %) (.getAbsolutePath zig) %) command)
        started (System/nanoTime)
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (io/file directory))
                  (.inheritIO))
        environment (.environment builder)
        _ (.put environment "ZIG" (.getAbsolutePath zig))
        _ (.put environment "PATH"
                (str (.getAbsolutePath (.getParentFile zig))
                     java.io.File/pathSeparator
                     (or (.get environment "PATH") "")))
        process (.start builder)
        exit (.waitFor process)
        report {:command command
                :directory (str directory)
                :exit exit
                :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}]
    (when-not (zero? exit)
      (throw (ex-info "Ghostty command failed" report)))
    report))

(defn build-standalone!
  "Materialize then build libghostty-vt ReleaseFast with no JVM dependency."
  []
  (let [{:keys [standalone-root]} (project-paths)
        materialized (materialize!)
        build (run-command!
               [(az/zig-executable) "build"
                "-Demit-lib-vt=true"
                "-Demit-xcframework=false"
                "-Demit-macos-app=false"
                "-Doptimize=ReleaseFast"]
               standalone-root)
        libraries (->> (file-seq (io/file standalone-root "zig-out/lib"))
                       (filter #(.isFile ^java.io.File %))
                       (filter #(re-matches #"libghostty-vt(?:\..+)?\.dylib"
                                            (.getName ^java.io.File %)))
                       (sort-by #(.getName ^java.io.File %))
                       vec)
        library (or (first libraries)
                    (throw (ex-info "Standalone build produced no Ghostty VT dylib"
                                    {:standalone-root standalone-root})))]
    {:materialized (materialization-summary materialized)
     :build build
     :library (.getAbsolutePath ^java.io.File library)}))

(defn build-macos-app!
  "Materialize then build Ghostty.app without a JVM/Clojure runtime."
  []
  (let [{:keys [standalone-root]} (project-paths)
        materialized (materialize!)
        build (run-command!
               [(az/zig-executable) "build"
                "-Demit-macos-app=true"
                "-Demit-lib-vt=false"]
               standalone-root)
        app (io/file standalone-root "zig-out/Ghostty.app")]
    (when-not (.isDirectory app)
      (throw (ex-info "Standalone build produced no Ghostty.app"
                      {:standalone-root standalone-root})))
    {:materialized (materialization-summary materialized)
     :build build
     :app (.getAbsolutePath app)}))

(defn -main
  [& [command]]
  (try
    (case command
      "generate" (pprint/pprint (generation-summary (generate!)))
      "materialize" (pprint/pprint
                     (materialization-summary (materialize!)))
      "standalone" (pprint/pprint (build-standalone!))
      "macos-app" (pprint/pprint (build-macos-app!))
      (println "Use generate, materialize, standalone, or macos-app."))
    (finally
      (shutdown-agents))))
