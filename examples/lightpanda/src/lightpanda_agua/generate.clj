(ns lightpanda-agua.generate
  "Lightpanda conversion and standalone-project tooling.

  This namespace intentionally does not require generated output, so a clean
  checkout can generate the example before any Lightpanda namespace exists."
  (:require [aguafria.zig.convert :as convert]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def v8-version "14.9.207.35")

(def zig-v8-tag "v0.5.2")

(defn- checked-git-output
  [input-root & arguments]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-C" input-root arguments)]
    (when-not (zero? exit)
      (throw (ex-info "Unable to resolve the vendored Lightpanda revision"
                      {:input-root input-root
                       :arguments arguments
                       :exit exit
                       :error err})))
    (str/trim out)))

(defn resolved-upstream-version
  "Resolve the exact version Lightpanda's own build reports for this revision."
  [input-root]
  (let [zon (slurp (io/file input-root "build.zig.zon"))
        base (second (re-find #"(?m)^\s*\.version\s*=\s*\"([^\"]+)\"" zon))]
    (when-not base
      (throw (ex-info "Lightpanda build.zig.zon has no version"
                      {:input-root input-root})))
    (if (or (not (str/includes? base "-"))
            (str/includes? base "+"))
      base
      (str base "."
           (checked-git-output input-root "rev-list" "--count" "HEAD")
           "+"
           (checked-git-output input-root "rev-parse" "--short" "HEAD")))))

(defn- write-report!
  [path report]
  (spit path (with-out-str (pprint/pprint report)))
  report)

(defn repository-root
  "Find this Aguafria checkout from the current working directory."
  []
  (or
   (some
    (fn [^java.io.File directory]
      (when (and (.isFile (io/file directory "src/aguafria/zig.clj"))
                 (.isDirectory
                  (io/file directory
                           "examples/lightpanda/vendor/lightpanda")))
        (.getCanonicalFile directory)))
    (take-while some?
                (iterate #(.getParentFile ^java.io.File %)
                         (.getCanonicalFile
                          (io/file (System/getProperty "user.dir"))))))
   (throw
    (ex-info
     (str "Could not find the Aguafria checkout or "
          "examples/lightpanda/vendor/lightpanda submodule")
     {:user-dir (System/getProperty "user.dir")}))))

(defn project-paths
  "Return Lightpanda input, generated, report, and standalone paths."
  []
  (let [root (repository-root)
        input (io/file root "examples/lightpanda/vendor/lightpanda")]
    {:repository-root (.getAbsolutePath root)
     :input-root (.getAbsolutePath input)
     :output-root (.getAbsolutePath
                   (io/file root "examples/lightpanda/generated"))
     :report-output (.getAbsolutePath
                     (io/file root
                              "examples/lightpanda/generated/lightpanda-report.edn"))
     :standalone-root (.getAbsolutePath
                       (io/file root "examples/lightpanda/build/standalone"))
     :prebuilt-v8 (.getAbsolutePath
                   (io/file input
                            ".lp-cache/prebuilt-v8"
                            zig-v8-tag
                            (str "libc_v8_" v8-version
                                 "_macos_aarch64.a")))}))

(defn generated?
  "True when Lightpanda's generated main namespace exists."
  ([] (generated? (:output-root (project-paths))))
  ([output-root]
   (.isFile (io/file output-root "lightpanda/src/main.clj"))))

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
                :source-module-count
                :asset-file-count
                :lightpanda/version
                :build-profiles
                :elapsed-ms]))

(def ^:private development-external-module-names
  #{"default_exports" "v8" "zenai"})

(def ^:private build-control-options
  #{"--cache-dir" "--global-cache-dir" "--name" "--zig-lib-dir"})

(declare conversion-profile)

(defn- build-command-line
  [input-root]
  (let [arguments (concat ["zig" "build" "--verbose"]
                          (conversion-profile)
                          ["-Doptimize=Debug"])
        {:keys [exit out err]}
        (apply shell/sh (concat arguments [:dir input-root]))
        output (str out "\n" err)
        line (->> (str/split-lines output)
                  (filter #(and (str/includes? % "zig build-exe ")
                                (str/includes? % "src/main.zig")))
                  last)]
    (when-not (and (zero? exit) line)
      (throw
       (ex-info
        "Unable to obtain Lightpanda's native development link command"
        {:input-root input-root
         :command (vec arguments)
         :exit exit
         :output-tail (subs output (max 0 (- (count output) 4000)))})))
    (str/split (str/trim line) #"\s+")))

(defn- absolute-build-path
  [input-root value]
  (let [value-file (io/file value)
        candidate (if (.isAbsolute value-file)
                    value-file
                    (io/file input-root value))]
    (if (and (not (str/starts-with? value "-")) (.exists candidate))
      (.getAbsolutePath (.getCanonicalFile candidate))
      value)))

(defn development-configuration!
  "Build native dependencies through Lightpanda's own Zig graph and return
  the exact modules/link inputs needed by Aguafria's in-JVM development host.

  No Lightpanda source is changed. A warm call is served by Zig's build cache;
  the first call also builds C/C++/Rust dependencies selected by upstream."
  []
  (let [{:keys [input-root]} (project-paths)
        tokens (build-command-line input-root)
        start (inc (.indexOf ^java.util.List tokens "build-exe"))]
    (loop [tokens (subvec (vec tokens) start)
           modules {}
           link-arguments []]
      (if-let [token (first tokens)]
        (cond
          (= token "--dep")
          (recur (subvec tokens 2) modules link-arguments)

          (str/starts-with? token "-M")
          (let [[module path] (str/split (subs token 2) #"=" 2)]
            (recur (subvec tokens 1)
                   (if (contains? development-external-module-names module)
                     (assoc modules module (absolute-build-path input-root path))
                     modules)
                   link-arguments))

          (contains? build-control-options token)
          (recur (subvec tokens 2) modules link-arguments)

          (or (= token "--listen=-")
              (str/starts-with? token "-O"))
          (recur (subvec tokens 1) modules link-arguments)

          :else
          (recur (subvec tokens 1)
                 modules
                 (conj link-arguments (absolute-build-path input-root token))))
        (do
          (when-not (= development-external-module-names (set (keys modules)))
            (throw
             (ex-info "Lightpanda build omitted required external Zig modules"
                      {:required development-external-module-names
                       :found (set (keys modules))})))
          {:modules modules
           ;; Lightpanda's Zig package/cache paths are content-addressed. The
           ;; path therefore serves as the immutable identity Aguafria needs
           ;; to reuse an already-built complex native slice safely.
           :module-cache-tokens modules
           :module-dependencies {"v8" ["default_exports"]}
           :module-zig-args
           {"v8" ["-ODebug"
                  "-I" (.getAbsolutePath
                         (.getParentFile (io/file (get modules "v8"))))
                  "-iframework" "/System/Library/Frameworks"]}
           ;; A leaf hot edit that never imports V8 must not relink V8, curl,
           ;; TLS, SQLite, and libc++. The full browser graph imports `v8`, so
           ;; it activates the exact upstream link line automatically.
           :zig-args-by-module {"v8" link-arguments}})))))

(defn- conversion-profile
  []
  (let [prebuilt-v8 (:prebuilt-v8 (project-paths))]
    (when-not (.isFile (io/file prebuilt-v8))
      (throw
       (ex-info
        "Lightpanda's prebuilt V8 archive is absent; run `make download-v8`"
        {:prebuilt-v8 prebuilt-v8
         :directory (:input-root (project-paths))})))
    [(str "-Dprebuilt_v8_path=" prebuilt-v8)]))

(defn generate!
  "Convert the complete vendored Lightpanda project to Aguafria namespaces."
  ([] (generate! {}))
  ([options]
   (let [paths (project-paths)
         input-root (or (:input-root options) (:input-root paths))
         output-root (or (:output-root options) (:output-root paths))
         report-output (or (:report-output options) (:report-output paths))
         report
         (convert/convert-tree!
          input-root
          output-root
          (merge {:namespace-prefix 'lightpanda
                  :overwrite? true
                  :bundle-assets? true
                  :exclude-directories #{".lp-cache"
                                         ".zig-cache"
                                         "zig-pkg"
                                         "zig-out"
                                         "target"}
                  :build-profiles [(conversion-profile)]
                  :report-output report-output}
                 (dissoc options :input-root :output-root :report-output)))
         report (assoc report :lightpanda/version
                       (resolved-upstream-version input-root))]
     (write-report! report-output report))))

(defn ensure-generated!
  "Generate Lightpanda only when its generated main namespace is absent."
  []
  (if (generated?)
    {:status :present :output-root (:output-root (project-paths))}
    {:status :generated :report (generation-summary (generate!))}))

(defn materialize!
  "Regenerate ordinary Lightpanda sources without reading vendored Zig."
  ([] (materialize! {}))
  ([options]
   (let [{:keys [report-output standalone-root]} (project-paths)]
     (convert/materialize-project!
      report-output
      (or (:output-root options) standalone-root)
      (merge {:overwrite? true} (dissoc options :output-root))))))

(defn materialization-summary
  "Return counts and paths without printing the complete source graph."
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
  (let [started (System/nanoTime)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory (io/file directory))
                          (.inheritIO)))
        exit (.waitFor process)
        report {:command command
                :directory (str directory)
                :exit exit
                :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}]
    (when-not (zero? exit)
      (throw (ex-info "Lightpanda command failed" report)))
    report))

(defn build-standalone!
  "Materialize and build a ReleaseFast browser with no JVM dependency."
  []
  (let [{:keys [standalone-root report-output]} (project-paths)
        materialized (materialize!)
        version (:lightpanda/version (edn/read-string (slurp report-output)))
        _ (when-not (string? version)
            (throw (ex-info
                    "Generated report has no resolved Lightpanda version; regenerate it"
                    {:report-output report-output})))
        v8 (run-command! ["make" "download-v8"] standalone-root)
        zig-flags (str "-Dprebuilt_v8_path=.lp-cache/prebuilt-v8/"
                       zig-v8-tag "/libc_v8_" v8-version
                       "_macos_aarch64.a -Dversion=" version)
        build (run-command! ["make" "build" (str "ZIGFLAGS=" zig-flags)]
                            standalone-root)
        executable (io/file standalone-root "zig-out/bin/lightpanda")]
    (when-not (.isFile executable)
      (throw (ex-info "Standalone build produced no Lightpanda executable"
                      {:standalone-root standalone-root})))
    {:materialized (materialization-summary materialized)
     :v8 v8
     :build build
     :executable (.getAbsolutePath executable)
     :bytes (.length executable)}))

(defn -main
  [& [command]]
  (try
    (case command
      "generate" (pprint/pprint (generation-summary (generate!)))
      "materialize" (pprint/pprint
                     (materialization-summary (materialize!)))
      "standalone" (pprint/pprint (build-standalone!))
      (println "Use generate, materialize, or standalone."))
    (finally
      (shutdown-agents))))
