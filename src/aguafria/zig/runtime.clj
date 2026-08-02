(ns aguafria.zig.runtime
  "Compilation, loading, and invocation for generated Zig modules."
  (:require [aguafria.zig.emitter :as emit]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]
           [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path
            StandardCopyOption StandardOpenOption]
           [java.security MessageDigest]
           [java.util ArrayList HexFormat]))

(defonce ^:private registry (atom {}))
(defonce ^:private build-registry (atom {}))
(defonce ^:private program-build-sequence (atom 0))
(defonce ^:private compile-lock (Object.))
(defonce ^:private artifact-locks (atom {}))

(def ^:dynamic *registration-batch*
  "Declaration collector used by tooling that bulk-loads generated source."
  nil)

(defn- env-true?
  [value]
  (contains? #{"1" "true" "yes" "on"}
             (some-> value str/lower-case str/trim)))

(defonce ^:private config
  (atom {:zig (or (System/getenv "AGUAFRIA_ZIG") "zig")
         :cache-dir (or (System/getProperty "aguafria.cache-dir")
                        ".aguafria/zig")
         :optimize (or (System/getProperty "aguafria.optimize") "Debug")
         :target nil
         :cpu nil
         :zig-args []
         :modules {}
         :build-history-limit 100
         :async? (env-true? (or (System/getProperty "aguafria.async-compile")
                                (System/getenv "AGUAFRIA_ASYNC_COMPILE")))}))

(def ^:private scalar-layouts
  {:bool ValueLayout/JAVA_BYTE
   :i8 ValueLayout/JAVA_BYTE
   :u8 ValueLayout/JAVA_BYTE
   :i16 ValueLayout/JAVA_SHORT
   :u16 ValueLayout/JAVA_SHORT
   :i32 ValueLayout/JAVA_INT
   :u32 ValueLayout/JAVA_INT
   :i64 ValueLayout/JAVA_LONG
   :u64 ValueLayout/JAVA_LONG
   :isize ValueLayout/JAVA_LONG
   :usize ValueLayout/JAVA_LONG
   :f32 ValueLayout/JAVA_FLOAT
   :f64 ValueLayout/JAVA_DOUBLE})

(defn configure!
  "Merge compiler configuration. Important keys are `:zig`, `:cache-dir`,
  `:optimize`, `:target`, `:cpu`, `:zig-args`, `:modules`, and `:async?`.
  `:modules` maps Zig import names to root source paths. Returns the resulting
  configuration."
  [options]
  (when-not (map? options)
    (throw (ex-info "Aguafria configuration must be a map" {:value options})))
  (when-let [optimize (:optimize options)]
    (when-not (contains? #{"Debug" "ReleaseFast" "ReleaseSafe" "ReleaseSmall"}
                         optimize)
      (throw (ex-info "Unsupported Zig optimization mode"
                      {:optimize optimize
                       :supported ["Debug" "ReleaseFast" "ReleaseSafe"
                                   "ReleaseSmall"]}))))
  (when (and (contains? options :async?)
             (not (instance? Boolean (:async? options))))
    (throw (ex-info ":async? must be true or false" {:value (:async? options)})))
  (when-let [zig-args (:zig-args options)]
    (when-not (and (sequential? zig-args) (every? string? zig-args))
      (throw (ex-info ":zig-args must be a sequence of strings"
                      {:value zig-args}))))
  (when-let [modules (:modules options)]
    (when-not (and (map? modules)
                   (every? #(or (string? %) (symbol? %) (keyword? %))
                           (keys modules))
                   (every? #(or (string? %) (instance? File %)) (vals modules)))
      (throw (ex-info ":modules must map import names to Zig source paths"
                      {:value modules}))))
  (when-let [history-limit (:build-history-limit options)]
    (when-not (and (integer? history-limit) (pos? history-limit))
      (throw (ex-info ":build-history-limit must be a positive integer"
                      {:value history-limit}))))
  (swap! config merge options))

(defn configuration [] @config)

(defn read-declaration
  "Reconstitute declaration data serialized into JVM-safe UTF-8 chunks.
  Public because macro expansions call it; not part of the user-facing API."
  [chunks]
  (when-not (and (vector? chunks) (every? string? chunks))
    (throw (ex-info "Serialized Aguafria declaration must be string chunks"
                    {:chunks (type chunks)})))
  (edn/read-string (apply str chunks)))

(defn clear!
  "Forget loaded modules. Generated, content-addressed files are retained."
  []
  (reset! registry {})
  (reset! build-registry {})
  (reset! program-build-sequence 0)
  nil)

(defn- declaration-summary
  [{:keys [declaration-key kind name qualified-name export? source]}]
  {:key declaration-key
   :kind kind
   :name (str name)
   :qualified-name (some-> qualified-name str)
   :export? (boolean export?)
   :source source})

(defn- trim-build-history
  [builds]
  (let [limit (max 1 (long (:build-history-limit @config)))
        grouped (group-by (comp :module val) builds)]
    (->> grouped
         (mapcat (fn [[_ entries]]
                   (let [{active true completed false}
                         (group-by (fn [[_ build]]
                                     (boolean (#{:queued :compiling}
                                                (:status build))))
                                   entries)]
                     (concat active
                             (->> completed
                                  (sort-by (comp :requested-at-ms val) >)
                                  (take limit))))))
         (into {}))))

(defn- record-build!
  [{:keys [module generation declarations]} async?]
  (let [now (System/currentTimeMillis)
        record {:module module
                :generation generation
                :purpose :repl-shared-library
                :status :queued
                :async? async?
                :requested-at-ms now
                :declarations (mapv declaration-summary declarations)}]
    (swap! build-registry
           (fn [builds]
             (-> builds
                 (assoc [module :repl generation] record)
                 trim-build-history)))
    record))

(defn- mark-build-started!
  [module generation]
  (swap! build-registry update [module :repl generation]
         merge
         {:status :compiling
          :started-at-ms (System/currentTimeMillis)
          :thread (.getName (Thread/currentThread))}))

(defn- mark-build-finished!
  [module generation status compiled]
  (let [finished-at (System/currentTimeMillis)]
    (swap! build-registry update [module :repl generation]
           (fn [build]
             (merge build
                    {:status status
                     :finished-at-ms finished-at
                     :duration-ms (when-let [started-at (:started-at-ms build)]
                                    (- finished-at started-at))}
                    (select-keys compiled
                                 [:hash :cached? :zig-version :source-path
                                  :library-path]))))))

(defn- mark-build-failed!
  [module generation error]
  (let [finished-at (System/currentTimeMillis)]
    (swap! build-registry update [module :repl generation]
           (fn [build]
             (merge build
                    {:status :failed
                     :finished-at-ms finished-at
                     :duration-ms (when-let [started-at (:started-at-ms build)]
                                    (- finished-at started-at))
                     :error (ex-message error)
                     :phase (:aguafria/phase (ex-data error))
                     :diagnostic-count (count (:diagnostics (ex-data error)))})))))

(defn- sha256
  [s]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes (str s) StandardCharsets/UTF_8)))]
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- safe-path-component
  [s]
  (-> (str s)
      (str/replace #"[^A-Za-z0-9_.-]" "_")
      (str/replace "." "_")))

(defn- run-command
  [command directory]
  (let [result (apply shell/sh (concat command [:dir directory]))]
    (assoc result :command command :directory directory)))

(defn- remove-ansi
  [text]
  (str/replace (or text "") #"\u001b\[[0-9;]*m" ""))

(defn- line-at
  [text line]
  (when (and line (pos? line))
    (nth (str/split-lines (or text "")) (dec line) nil)))

(defn- existing-source-line
  [file line]
  (when (and file line)
    (let [source-file (io/file file)]
      (when (.isFile source-file)
        (line-at (slurp source-file) line)))))

(defn- code-frame
  [line column source-line label]
  (when (and line source-line)
    (let [line-label (str line)
          gutter (apply str (repeat (count line-label) " "))
          column (max 1 (or column 1))]
      (str " " gutter " |\n"
           " " line-label " | " source-line "\n"
           " " gutter " | " (apply str (repeat (dec column) " "))
           "^ " label "\n"))))

(defn- source-location-at
  [source generated-line]
  (reduce
   (fn [location line]
     (if-let [[_ file source-line source-column]
              (re-matches #"^// Clojure source: (.*):(\d+):(\d+)$" line)]
       {:file file
        :line (parse-long source-line)
        :column (parse-long source-column)}
       (if-let [[_ form-line form-column]
                (re-matches #"^\s*// Clojure form: (\d+)(?::(\d+))?$" line)]
         (assoc location
                :line (parse-long form-line)
                :column (some-> form-column parse-long))
         location)))
   nil
   (take (or generated-line 0) (str/split-lines source))))

(defn- parse-zig-diagnostics
  [stderr]
  (->> (str/split-lines (remove-ansi stderr))
       (keep (fn [line]
               (when-let [[_ file line-number column severity message]
                          (re-matches #"^(.*):(\d+):(\d+): (error|warning|note): (.*)$"
                                      line)]
                 {:file file
                  :line (parse-long line-number)
                  :column (parse-long column)
                  :severity (keyword severity)
                  :message message})))
       vec))

(defn- format-zig-diagnostic
  [source source-path diagnostic]
  (let [{generated-file :file generated-line :line generated-column :column
         :keys [severity message]} diagnostic
        root-module? (= (.getName (io/file source-path))
                        (.getName (io/file generated-file)))
        {:keys [file line column]} (when root-module?
                                     (source-location-at source generated-line))
        clojure-line (existing-source-line file line)
        generated-source-line (if root-module?
                                (line-at source generated-line)
                                (existing-source-line generated-file generated-line))]
    (str (name severity) "[aguafria::zig]: " message "\n"
         (when file
           (str "  --> " file
                (when line (str ":" line))
                (when column (str ":" column)) "\n"
                (code-frame line column clojure-line
                            "this Clojure form generated the failing Zig")))
         "  ::: " generated-file ":" generated-line ":" generated-column "\n"
         (code-frame generated-line generated-column generated-source-line
                     "Zig reported the error here"))))

(defn- pretty-zig-error
  [module source source-path command stderr]
  (let [diagnostics (parse-zig-diagnostics stderr)
        rendered (if (seq diagnostics)
                   (str/join "\n" (map #(format-zig-diagnostic source source-path %)
                                          diagnostics))
                   (str "error[aguafria::zig]: Zig compilation failed without a location\n"))]
    {:diagnostics diagnostics
     :message
     (str "Zig compilation failed for " module "\n\n"
          rendered
          "\n  = generated module: " source-path
          "\n  = compiler command: " (pr-str command)
          (when (str/blank? stderr)
            "\n  = Zig produced no stderr output")
          (when-not (str/blank? stderr)
            (str "\n\nRaw Zig diagnostics:\n" (remove-ansi stderr))))}))

(defn- pretty-emission-error
  [declaration error]
  (let [{:keys [file line column]} (:source declaration)
        form (or (:form (ex-data error)) (:clojure-form declaration))
        form-location (meta form)
        line (or (:line form-location) line)
        column (or (:column form-location) column)
        source-line (existing-source-line file line)]
    (str "Aguafria could not emit Zig for " (:module declaration) "/"
         (:name declaration) "\n\n"
         "error[aguafria::emit]: " (ex-message error) "\n"
         "  --> " (or file "<repl>")
         (when line (str ":" line))
         (when column (str ":" column)) "\n"
         (code-frame line column source-line "this form could not be emitted")
         (when form (str "  = form: " (pr-str form) "\n")))))

(defn- emit-source!
  [module declarations]
  (try
    (emit/emit-module module declarations)
    (catch clojure.lang.ExceptionInfo error
      (let [[declaration cause]
            (or (some (fn [declaration]
                        (try
                          (emit/emit-declaration declaration)
                          nil
                          (catch clojure.lang.ExceptionInfo cause
                            [declaration cause])))
                      declarations)
                [(last declarations) error])
            message (pretty-emission-error declaration cause)]
        (throw (ex-info message
                        (merge (ex-data cause)
                               {:aguafria/phase :emit
                                :module module
                                :declaration declaration})
                        cause))))))

(defn- zig-version
  []
  (let [{:keys [exit out err command] :as result}
        (run-command [(:zig @config) "version"] (System/getProperty "user.dir"))]
    (if (zero? exit)
      (str/trim out)
      (throw (ex-info "Unable to run the Zig compiler"
                      {:command command :exit exit :stdout out :stderr err
                       :result result})))))

(defn- absolute-path
  [path]
  (.getAbsolutePath (io/file path)))

(defn- root-module-arguments
  [source-file {:keys [optimize target cpu zig-args modules]}]
  (let [modules (sort-by (comp str key) modules)]
    (vec
     (concat
      [(str "-O" optimize)]
      (when target ["-target" (str target)])
      (when cpu ["-mcpu" (str cpu)])
      (mapcat (fn [[module-name _]]
                ["--dep" (if (instance? clojure.lang.Named module-name)
                           (name module-name)
                           (str module-name))])
              modules)
      zig-args
      [(str "-Mroot=" (absolute-path source-file))]
      (map (fn [[module-name module-path]]
             (str "-M"
                  (if (instance? clojure.lang.Named module-name)
                    (name module-name)
                    (str module-name))
                  "=" (absolute-path module-path)))
           modules)))))

(defn- usable-artifact?
  [^File file]
  (and (.isFile file) (pos? (.length file))))

(defn- move-replacing!
  [^File from ^File to]
  (try
    (Files/move (.toPath from) (.toPath to)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                        StandardCopyOption/ATOMIC_MOVE]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath from) (.toPath to)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn- materialize-registered-module-source!
  [module]
  (let [{:keys [source] :as module-state} (get @registry (str module))]
    (when-not (and module-state (string? source))
      (throw
       (ex-info
        (str "Required Aguafria namespace `" module
             "` has no registered Zig source. Require/load that namespace "
             "before compiling its caller.")
        {:aguafria/phase :zig-dependency
         :module (str module)
         :known-modules (sort (keys @registry))})))
    (let [source-hash (subs (sha256 source) 0 24)
          directory (io/file (:cache-dir @config) "dependencies"
                             (safe-path-component module) source-hash)
          source-file (io/file directory "module.zig")]
      (.mkdirs directory)
      (when-not (= source (when (.isFile source-file) (slurp source-file)))
        (Files/writeString (.toPath source-file) source StandardCharsets/UTF_8
                           (into-array StandardOpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/TRUNCATE_EXISTING
                                        StandardOpenOption/WRITE])))
      (.getAbsolutePath source-file))))

(defn- compiler-options-for-declarations
  [compiler-options declarations]
  (let [automatic
        (into {}
              (keep (fn [[_ {:keys [import-name namespace]}]]
                      (when namespace
                        [import-name
                         (materialize-registered-module-source! namespace)])))
              (emit/declaration-imports declarations))
        configured (:modules compiler-options)
        conflicts (->> (keys automatic)
                       (filter #(contains? configured %))
                       sort
                       vec)]
    (when (seq conflicts)
      (throw (ex-info
              "Configured Zig modules conflict with required Aguafria namespaces"
              {:aguafria/phase :zig-dependency
               :module-names conflicts
               :configured configured
               :automatic automatic})))
    (assoc compiler-options :modules (merge configured automatic))))

(defn- compile-source!
  [module-name source declarations]
  (let [{:keys [cache-dir optimize zig] :as compiler-options}
        (compiler-options-for-declarations @config declarations)
        compiler-version (zig-version)
        hash-input [source compiler-version
                    (select-keys compiler-options
                                 [:optimize :target :cpu :zig-args :modules])
                    (System/getProperty "os.name") (System/getProperty "os.arch")]
        source-hash (subs (sha256 (pr-str hash-input)) 0 24)
        module-dir (io/file cache-dir (safe-path-component module-name) source-hash)
        source-file (io/file module-dir "module.zig")
        library-name (System/mapLibraryName
                      (str "aguafria_" (safe-path-component module-name) "_" source-hash))
        library-file (io/file module-dir library-name)
        command (vec (concat
                      [zig "build-lib" "-dynamic"
                       (str "-femit-bin=" (.getAbsolutePath library-file))]
                      (root-module-arguments source-file compiler-options)))]
    (.mkdirs ^File module-dir)
    (when-not (= source (when (.isFile source-file) (slurp source-file)))
      (spit source-file source))
    (let [artifact-lock (get (swap! artifact-locks
                                    #(if (contains? % (.getAbsolutePath library-file))
                                       %
                                       (assoc % (.getAbsolutePath library-file) (Object.))))
                             (.getAbsolutePath library-file))]
      (locking artifact-lock
        (let [cache-safe? (and (empty? (:modules compiler-options))
                               (empty? (:zig-args compiler-options)))
              cached? (and cache-safe? (usable-artifact? library-file))
              result
              (when-not cached?
                (let [temporary-file
                      (io/file module-dir
                               (str "." (java.util.UUID/randomUUID) "-" library-name))
                      temporary-command
                      (assoc command 3 (str "-femit-bin="
                                            (.getAbsolutePath temporary-file)))]
                  (try
                    (let [result (run-command temporary-command
                                              (.getAbsolutePath module-dir))]
                      (when (and (zero? (:exit result))
                                 (not (usable-artifact? temporary-file)))
                        (throw (ex-info "Zig exited successfully but produced no usable library"
                                        {:aguafria/phase :zig-compile
                                         :module module-name
                                         :source-path (.getAbsolutePath source-file)
                                         :library-path (.getAbsolutePath library-file)
                                         :command temporary-command})))
                      (when (zero? (:exit result))
                        (move-replacing! temporary-file library-file))
                      result)
                    (finally
                      (Files/deleteIfExists (.toPath temporary-file))))))]
          (when (and result (not (zero? (:exit result))))
            (let [{:keys [message diagnostics]}
                  (pretty-zig-error module-name source
                                    (.getAbsolutePath source-file)
                                    command (:err result))]
              (throw (ex-info message
                              {:aguafria/phase :zig-compile
                               :module module-name
                               :source-path (.getAbsolutePath source-file)
                               :library-path (.getAbsolutePath library-file)
                               :command command
                               :exit (:exit result)
                               :stdout (:out result)
                               :stderr (:err result)
                               :diagnostics diagnostics}))))
          {:hash source-hash
           :cached? cached?
           :zig-version compiler-version
           :source source
           :source-path (.getAbsolutePath source-file)
           :library-path (.getAbsolutePath library-file)
           :command command
           :compiler-output result})))))

(defn- scalar-key
  [type]
  (when (keyword? type) type))

(defn- supported-signature?
  [{:keys [args return]}]
  (and (or (= :void return) (contains? scalar-layouts (scalar-key return)))
       (every? #(contains? scalar-layouts (scalar-key (:type %))) args)))

(defn- function-descriptor
  [{:keys [args return]}]
  (let [arg-layouts (into-array MemoryLayout
                                (map #(get scalar-layouts (scalar-key (:type %))) args))]
    (if (= :void return)
      (FunctionDescriptor/ofVoid arg-layouts)
      (FunctionDescriptor/of ^MemoryLayout (get scalar-layouts (scalar-key return))
                             arg-layouts))))

(defn- bind-function
  [^Linker linker ^SymbolLookup lookup declaration]
  (if-not (supported-signature? declaration)
    {:declaration declaration
     :unsupported? true}
    (let [symbol-name (emit/identifier (or (:zig-name declaration)
                                           (:name declaration)))
          address (-> (.find lookup symbol-name)
                      (.orElseThrow
                       (reify java.util.function.Supplier
                         (get [_]
                           (ex-info "Exported Zig symbol was not found"
                                    {:symbol symbol-name
                                     :declaration declaration})))))
          descriptor (function-descriptor declaration)
          options (into-array Linker$Option [])
          handle (.downcallHandle linker address descriptor options)]
      {:declaration declaration
       :descriptor descriptor
       :handle handle})))

(defn- load-module
  [compiled declarations]
  (try
    (let [arena (Arena/ofAuto)
          lookup (SymbolLookup/libraryLookup
                  ^Path (.toPath (io/file (:library-path compiled))) arena)
          linker (Linker/nativeLinker)
          functions (->> declarations
                         (filter #(and (= :fn (:kind %)) (:export? %)))
                         (map (fn [declaration]
                                [(:qualified-name declaration)
                                 (bind-function linker lookup declaration)]))
                         (into {}))]
      (merge compiled {:arena arena :lookup lookup :linker linker
                       :functions functions}))
    (catch Throwable error
      (throw
       (ex-info
        (str "Aguafria could not load the compiled Zig library\n\n"
             "error[aguafria::load]: " (ex-message error) "\n"
             "  --> " (:library-path compiled) "\n"
             "  = generated source: " (:source-path compiled) "\n"
             "  = hint: run the JVM with --enable-native-access=ALL-UNNAMED")
        {:aguafria/phase :native-load
         :source-path (:source-path compiled)
         :library-path (:library-path compiled)}
        error)))))

(defn- registration-result
  [module declaration-key generation compiled published?]
  {:module module
   :declaration-key declaration-key
   :generation generation
   :published? published?
   :hash (:hash compiled)
   :cached? (:cached? compiled)
   :source-path (:source-path compiled)
   :library-path (:library-path compiled)})

(defn- compile-and-publish-async!
  [{:keys [module declaration-key generation declarations source completion]}]
  (mark-build-started! module generation)
  (try
    (let [compiled (compile-source! module source declarations)
          ;; Stale snapshots are useful compiler work/history, but loading each
          ;; one would waste native-library arenas during a large REPL reload.
          loaded (when (= generation
                          (get-in @registry [module :requested-generation]))
                   (load-module compiled declarations))
          published? (atom false)]
      (swap! registry update module
             (fn [current]
               (if (and loaded (= generation (:requested-generation current)))
                 (do
                   (reset! published? true)
                   (merge current loaded
                          {:generation generation
                           :published-generation generation
                           :pending nil
                           :last-error nil
                           :failed-generation nil}))
                 current)))
      (let [status (if @published? :finished :stale)]
        (mark-build-finished! module generation status compiled)
        (deliver completion
                 {:status :success
                  :result (registration-result module declaration-key generation
                                               compiled @published?)})))
    (catch Throwable error
      (swap! registry update module
             (fn [current]
               (if (= generation (:requested-generation current))
                 (assoc current
                        :pending nil
                        :last-error error
                        :failed-generation generation)
                 current)))
      (mark-build-failed! module generation error)
      (deliver completion {:status :error :error error}))))

(defn- register-async!
  [{:keys [module declaration-key] :as declaration}]
  (let [job
        (locking compile-lock
          (let [old-module (get @registry module)
                definitions (assoc (or (:definitions old-module) {})
                                   declaration-key declaration)
                declarations (vec (vals definitions))
                source (emit-source! module declarations)
                generation (inc (or (:requested-generation old-module)
                                    (:generation old-module) 0))
                completion (promise)
                job {:module module
                     :declaration-key declaration-key
                     :generation generation
                     :definitions definitions
                     :declarations declarations
                     :source source
                     :completion completion}]
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :source source
                           :requested-generation generation
                           :pending completion
                           :last-error nil
                           :failed-generation nil}))
            job))]
    (record-build! job true)
    (future (compile-and-publish-async! job))
    {:module module
     :declaration-key declaration-key
     :generation (:generation job)
     :async? true
     :pending? true}))

(defn- register-sync!
  [{:keys [module declaration-key] :as declaration}]
  (locking compile-lock
    (let [old-module (get @registry module)
          definitions (assoc (or (:definitions old-module) {})
                             declaration-key declaration)
          declarations (vec (vals definitions))
          source (emit-source! module declarations)
          generation (inc (or (:requested-generation old-module)
                              (:generation old-module) 0))
          job {:module module :generation generation :declarations declarations}]
      (record-build! job false)
      (mark-build-started! module generation)
      (try
        (let [compiled (compile-source! module source declarations)
              loaded (load-module compiled declarations)
              new-module (merge loaded
                                {:module module
                                 :generation generation
                                 :published-generation generation
                                 :requested-generation generation
                                 :pending nil
                                 :last-error nil
                                 :failed-generation nil
                                 :definitions definitions})]
          (swap! registry assoc module new-module)
          (mark-build-finished! module generation :finished compiled)
          (registration-result module declaration-key generation compiled true))
        (catch Throwable error
          (mark-build-failed! module generation error)
          (throw error))))))

(declare recompile!)

(defn register-declaration!
  "Add or replace a declaration and rebuild its namespace module.

  With `:async?` configuration enabled, returns immediately after scheduling
  an immutable module snapshot. Builds may run concurrently, but only the
  newest requested generation is published."
  [{:keys [module declaration-key] :as declaration}]
  (when-not (and module declaration-key)
    (throw (ex-info "Declaration requires :module and :declaration-key"
                    {:declaration declaration})))
  (if *registration-batch*
    (do
      (swap! *registration-batch* conj declaration)
      {:module module :declaration-key declaration-key :batched? true})
    (if (:async? @config)
      (register-async! declaration)
      (register-sync! declaration))))

(defn register-batch!
  "Register a complete declaration batch without intermediate compilations.

  With `:compile? false` (the converter default), the source and declarations
  become immediately inspectable and a later `recompile!` builds the complete
  module once. `:replace? true` removes declarations absent from the batch."
  [declarations {:keys [compile? replace? module]
                 :or {compile? false replace? true}}]
  (let [declarations (vec declarations)
        modules (cond-> (set (map :module declarations))
                  module (conj (str module)))]
    (when-not (= 1 (count modules))
      (throw (ex-info "A registration batch must contain exactly one module"
                      {:modules modules :declaration-count (count declarations)})))
    (let [module (first modules)
          definitions (into {} (map (juxt :declaration-key identity)) declarations)]
      (if compile?
        ;; Seed the whole immutable snapshot, then ask the existing single-module
        ;; compiler to compile that complete definition map exactly once.
        (do
          (locking compile-lock
            (swap! registry update module
                   (fn [current]
                     (assoc (or current {})
                            :module module
                            :definitions (if replace?
                                           definitions
                                           (merge (:definitions current) definitions))))))
          (recompile! module))
        (locking compile-lock
          (let [old-module (get @registry module)
                definitions (if replace?
                              definitions
                              (merge (:definitions old-module) definitions))
                declarations (vec (vals definitions))
                source (emit-source! module declarations)]
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :declarations declarations
                           :source source
                           :source-only? true
                           :pending nil
                           :last-error nil
                           :failed-generation nil}))
            {:module module
             :declaration-count (count declarations)
             :source-only? true
             :compiled? false}))))))

(defn recompile!
  "Recompile one existing module using the current compiler configuration.
  With no argument recompiles every loaded module. Honors `:async?`."
  ([]
   (mapv recompile! (sort (keys @registry))))
  ([module]
   (let [module (str module)
         declaration (some-> (get @registry module) :definitions vals first)]
     (when-not declaration
       (throw (ex-info "Cannot recompile a module with no declarations"
                       {:module module :known-modules (sort (keys @registry))})))
     (register-declaration! declaration))))

(defn await!
  "Wait for the newest asynchronous compilation of one module. With no
  argument, waits for all currently registered modules. Throws the compiler
  error from the newest failed generation."
  ([]
   (doseq [module (keys @registry)]
     (await! module))
   nil)
  ([module]
   (let [module (str module)]
     (loop []
       (let [{:keys [pending last-error failed-generation requested-generation]
              :as current} (get @registry module)]
         (cond
           pending
           (let [{:keys [status error]} @pending]
             (when (= :error status)
               (throw error))
             ;; Another generation may have been requested while we waited.
             (recur))

           (and last-error (= failed-generation requested-generation))
           (throw last-error)

           :else
           current))))))

(defn- default-program-filename
  [kind name]
  (case kind
    :exe (str name (when (str/includes? (str/lower-case (System/getProperty "os.name"))
                                        "windows")
                     ".exe"))
    :dynamic-lib (System/mapLibraryName name)
    :static-lib (if (str/includes? (str/lower-case (System/getProperty "os.name"))
                                   "windows")
                  (str name ".lib")
                  (str "lib" name ".a"))
    :object (str name (if (str/includes? (str/lower-case (System/getProperty "os.name"))
                                         "windows")
                        ".obj"
                        ".o"))
    (throw (ex-info "Build :kind must be :exe, :dynamic-lib, :static-lib, or :object"
                    {:kind kind}))))

(defn build!
  "Build the current generated module as a standalone Zig artifact.

  Options include `:kind` (`:exe`, `:dynamic-lib`, `:static-lib`, or `:object`),
  `:name`, `:output`, `:optimize`, `:target`, `:cpu`, `:zig-args`, and
  `:modules`. The latter maps names used by `@import` to Zig root files. This
  invokes Zig directly; the output has no Aguafria runtime dependency."
  ([module] (build! module {}))
  ([module options]
   (let [module (str module)
         _ (await! module)
         module-state (get @registry module)]
     (when-not module-state
       (throw (ex-info "Cannot build an unknown Aguafria module"
                       {:module module :known-modules (sort (keys @registry))})))
     (let [declarations (vec (vals (:definitions module-state)))
           compiler-options (compiler-options-for-declarations
                             (merge @config options) declarations)
           kind (or (:kind options) :exe)
           name (safe-path-component (or (:name options) module))
           command-name ({:exe "build-exe"
                          :dynamic-lib "build-lib"
                          :static-lib "build-lib"
                          :object "build-obj"} kind)
           _ (when-not command-name
               (default-program-filename kind name))
           source-file (io/file (:source-path module-state))
           output-file (.getAbsoluteFile
                        (if-let [output (:output options)]
                          (io/file output)
                          (io/file (.getParentFile source-file)
                                   (default-program-filename kind name))))
           _ (when-let [parent (.getParentFile output-file)] (.mkdirs parent))
           command (vec
                    (concat [(:zig compiler-options) command-name]
                            (when (= kind :dynamic-lib) ["-dynamic"])
                            (when (= kind :static-lib) ["-static"])
                            [(str "-femit-bin=" (.getAbsolutePath output-file))]
                            (root-module-arguments source-file compiler-options)))
           build-id (swap! program-build-sequence inc)
           build-key [module :program build-id]
           started-at (System/currentTimeMillis)
           build-record {:module module
                         :generation (:published-generation module-state)
                         :build-id build-id
                         :purpose :standalone-program
                         :artifact-kind kind
                         :status :compiling
                         :async? false
                         :requested-at-ms started-at
                         :started-at-ms started-at
                         :thread (.getName (Thread/currentThread))
                         :declarations (mapv declaration-summary declarations)}]
       (swap! build-registry
              (fn [builds]
                (-> builds (assoc build-key build-record) trim-build-history)))
       (let [result (run-command command (.getAbsolutePath (.getParentFile output-file)))
             finished-at (System/currentTimeMillis)]
         (if (zero? (:exit result))
           (let [artifact {:module module
                           :kind kind
                           :name name
                           :build-id build-id
                           :source-path (.getAbsolutePath source-file)
                           :output-path (.getAbsolutePath output-file)
                           :optimize (:optimize compiler-options)
                           :target (:target compiler-options)
                           :cpu (:cpu compiler-options)
                           :command command
                           :stdout (:out result)
                           :stderr (:err result)
                           :duration-ms (- finished-at started-at)}]
             (swap! build-registry update build-key merge
                    {:status :finished
                     :finished-at-ms finished-at
                     :duration-ms (- finished-at started-at)
                     :source-path (:source-path artifact)
                     :output-path (:output-path artifact)})
             artifact)
           (let [{:keys [message diagnostics]}
                 (pretty-zig-error module (:source module-state)
                                   (.getAbsolutePath source-file)
                                   command (:err result))
                 error (ex-info message
                                {:aguafria/phase :zig-program-compile
                                 :module module
                                 :kind kind
                                 :source-path (.getAbsolutePath source-file)
                                 :output-path (.getAbsolutePath output-file)
                                 :command command
                                 :exit (:exit result)
                                 :stdout (:out result)
                                 :stderr (:err result)
                                 :diagnostics diagnostics})]
             (swap! build-registry update build-key merge
                    {:status :failed
                     :finished-at-ms finished-at
                     :duration-ms (- finished-at started-at)
                     :error (ex-message error)
                     :phase :zig-program-compile
                     :diagnostic-count (count diagnostics)})
             (throw error))))))))

(defn module-info
  "Return inspectable information for a module/namespace, excluding native
  loader objects and method handles."
  [module]
  (when-let [m (get @registry (str module))]
    (-> m
        (dissoc :arena :lookup :linker :functions :pending :last-error)
        (assoc :pending? (boolean (:pending m))
               :error (some-> (:last-error m) ex-message))
        (update :definitions vals))))

(defn- build-view
  [now build]
  (cond-> build
    (and (#{:queued :compiling} (:status build)) (:started-at-ms build))
    (assoc :elapsed-ms (- now (:started-at-ms build)))))

(defn- module-stats
  [module now builds]
  (let [module-state (get @registry module)
        module-builds (->> builds
                           vals
                           (filter #(= module (:module %)))
                           (sort-by (juxt :requested-at-ms :generation)
                                    #(compare %2 %1))
                           (mapv #(build-view now %)))
        latest-build (first module-builds)
        latest-repl-build (first (filter #(= :repl-shared-library (:purpose %))
                                         module-builds))
        declaration-state (case (:status latest-repl-build)
                            (:queued :compiling :failed) (:status latest-repl-build)
                            :finished)
        declarations (->> (:definitions module-state)
                          vals
                          (map #(assoc (declaration-summary %)
                                       :state declaration-state))
                          (sort-by (juxt :kind :name))
                          vec)]
    {:module module
     :status (or (:status latest-repl-build) :idle)
     :requested-generation (or (:requested-generation module-state)
                               (:generation latest-build))
     :published-generation (:published-generation module-state)
     :pending? (boolean (:pending module-state))
     :declaration-count (count declarations)
     :function-count (count (filter #(= :fn (:kind %)) declarations))
     :declarations declarations
     :active-builds (->> module-builds
                         (filter #(#{:queued :compiling} (:status %)))
                         vec)
     :last-build latest-build
     :source-path (:source-path module-state)
     :library-path (:library-path module-state)
     :hash (:hash module-state)}))

(defn stats
  "Return immutable, serializable compilation statistics suitable for a
  future monitor UI.

  With no argument returns aggregate summary, per-module state, and bounded
  build history. With a module/namespace returns that module's detail and build
  history."
  ([]
   (let [now (System/currentTimeMillis)
         builds @build-registry
         module-names (->> (concat (keys @registry) (map :module (vals builds)))
                           set sort)
         modules (into (sorted-map)
                       (map (fn [module]
                              [module (module-stats module now builds)]))
                       module-names)
         build-views (->> builds vals
                          (sort-by (juxt :requested-at-ms :generation) #(compare %2 %1))
                          (mapv #(build-view now %)))
         statuses (frequencies (map :status build-views))]
     {:generated-at-ms now
      :summary {:module-count (count modules)
                :declaration-count (reduce + 0 (map :declaration-count (vals modules)))
                :function-count (reduce + 0 (map :function-count (vals modules)))
                :active-build-count (+ (get statuses :queued 0)
                                       (get statuses :compiling 0))
                :queued-build-count (get statuses :queued 0)
                :compiling-build-count (get statuses :compiling 0)
                :finished-build-count (get statuses :finished 0)
                :stale-build-count (get statuses :stale 0)
                :failed-build-count (get statuses :failed 0)
                :cache-hit-count (count (filter :cached? build-views))}
      :modules modules
      :builds build-views}))
  ([module]
   (let [module (str module)
         all (stats)]
     (when-let [details (get-in all [:modules module])]
       (assoc details
              :generated-at-ms (:generated-at-ms all)
              :builds (->> (:builds all)
                           (filter #(= module (:module %)))
                           vec))))))

(defn source
  "Return the current generated Zig source for a module/namespace."
  [module]
  (:source (get @registry (str module))))

(defn- coerce-argument
  [type value]
  (case type
    :bool (byte (if (if (instance? Boolean value) value (not (zero? value))) 1 0))
    (:i8 :u8) (unchecked-byte value)
    (:i16 :u16) (unchecked-short value)
    (:i32 :u32) (unchecked-int value)
    (:i64 :u64 :isize :usize) (unchecked-long value)
    :f32 (float value)
    :f64 (double value)
    value))

(defn- coerce-result
  [type value]
  (case type
    :void nil
    :bool (not (zero? (long value)))
    :u8 (bit-and 0xff (long value))
    :u16 (bit-and 0xffff (long value))
    :u32 (Integer/toUnsignedLong (int value))
    value))

(defn invoke!
  "Invoke the latest loaded generation of an exported scalar Zig function."
  [qualified-name arguments]
  (let [module (namespace qualified-name)
        _ (await! module)
        binding (get-in @registry [module :functions qualified-name])
        declaration (:declaration binding)]
    (when-not binding
      (throw (ex-info "Zig function is not loaded"
                      {:function qualified-name :module module})))
    (when (:unsupported? binding)
      (throw (ex-info "Zig function uses an ABI type not callable from Clojure yet"
                      {:function qualified-name
                       :supported-types (conj (set (keys scalar-layouts)) :void)
                       :arguments (mapv :type (:args declaration))
                       :return (:return declaration)})))
    (when-not (= (count arguments) (count (:args declaration)))
      (throw (ex-info "Wrong number of arguments for Zig function"
                      {:function qualified-name
                       :expected (count (:args declaration))
                       :actual (count arguments)})))
    (let [coerced (mapv (fn [{:keys [type]} value]
                          (coerce-argument type value))
                        (:args declaration) arguments)
          values (ArrayList. ^java.util.Collection coerced)
          result (.invokeWithArguments ^MethodHandle (:handle binding) values)]
      (coerce-result (:return declaration) result))))
