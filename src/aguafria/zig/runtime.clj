(ns aguafria.zig.runtime
  "Compilation, loading, and invocation for generated Zig modules."
  (:require [aguafria.zig.emitter :as emit]
            [aguafria.zig.project :as project]
            [aguafria.zig.value :as zig-value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io File]
           [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle VarHandle]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path
            StandardCopyOption StandardOpenOption]
           [java.security MessageDigest]
           [java.util ArrayList HexFormat]
           [java.util.concurrent ExecutionException Executors
            ScheduledExecutorService ScheduledFuture ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(defonce ^:private registry (atom {}))
(defonce ^:private build-registry (atom {}))
(defonce ^:private program-build-sequence (atom 0))
(defonce ^:private component-publication-sequence (atom 0))
(defonce ^:private live-host-sequence (AtomicLong. 0))
(defonce ^:private live-hosts (atom {}))
(def ^:private default-native-host-stack-size-bytes
  ;; Match Zig's std.Thread.SpawnConfig.default_stack_size. A JVM-created
  ;; thread is commonly only ~1-2 MiB, which is too small for ordinary Zig
  ;; programs with substantial stack frames (TigerBeetle is one real case).
  (* 16 1024 1024))
(defonce ^:private state-migrations (atom {}))
(defonce ^:private dispatch-publication-arena (Arena/ofShared))
(defonce ^:private dispatch-publication-epoch
  (.allocate ^Arena dispatch-publication-arena ValueLayout/JAVA_LONG))
(defonce ^:private dispatch-publication-epoch-handle
  (.varHandle ValueLayout/JAVA_LONG))
(defonce ^:private compile-lock (Object.))
(defonce ^:private artifact-locks (atom {}))
(defonce ^:private retirement-pending-modules (atom #{}))
(defonce ^:private converted-load-lock (Object.))
(defonce ^:private converted-thread-sequence (AtomicLong. 0))
(defonce ^ScheduledExecutorService converted-compiler
  (Executors/newScheduledThreadPool
   (max 2 (min 4 (.availableProcessors (Runtime/getRuntime))))
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable
                      (str "aguafria-converted-compiler-"
                           (.incrementAndGet converted-thread-sequence)))
         (.setDaemon true))))))

(def ^:dynamic *registration-batch*
  "Declaration collector used by tooling that bulk-loads generated source."
  nil)

(def ^:dynamic *converted-dependency-loading* #{})

(def ^:dynamic *propagate-dependent-changes?*
  "Internal publication policy. Ordinary declaration edits propagate; a
  development-only JVM-call trampoline does not change Zig behavior and must
  not rebuild otherwise-unchanged dependents."
  true)

(declare register-batch!)

(declare declaration-info declaration-type-value
         materialize-constant! materialize-state!
         materialize-type!
         native-error-union-field-schema native-optional-field-schema
         native-slice-field-schema native-storage-binding-schema
         native-type-schema
         scalar-key scalar-layouts)

(defn- container-type-description
  [declaration]
  (when (= :const (:kind declaration))
    (emit/container-description
     (or (some-> (:module declaration) symbol find-ns) *ns*)
     (:value declaration))))

(defn declaration-root-value
  "Return the public Clojure root for a Zig constant. Literal values retain
  their exact JVM representation. Other declarations receive a typed, lazy
  Zig value handle; the emitter continues to use Var metadata, never this
  public root."
  [declaration]
  (let [declaration (declaration-info declaration)
        declaration-value (:value declaration)
        declaration-type (:type declaration)
        exact-jvm-literal?
        (or (nil? declaration-value)
            (and (or (number? declaration-value)
                     (char? declaration-value)
                     (boolean? declaration-value))
                 (or (nil? declaration-type)
                     (contains? scalar-layouts
                                (scalar-key declaration-type))))
            ;; Zig enum literals are semantically symbolic until context gives
            ;; them a concrete storage type, matching a Clojure keyword.
            (and (keyword? declaration-value) (nil? declaration-type)))]
    (cond
      (container-type-description declaration)
      (declaration-type-value declaration)

      exact-jvm-literal?
      declaration-value

      :else
      (zig-value/native-value
       (select-keys declaration [:module :name :kind :type :logical-id])
       #(materialize-constant! declaration)))))

(defn declaration-type-value
  "Return the ordinary callable Clojure value for a Zig type declaration."
  [declaration]
  (let [declaration (declaration-info declaration)]
    (zig-value/zig-type
     (select-keys declaration
                  [:module :name :kind :layout :logical-id
                   :schema-fingerprint])
     #(materialize-type! declaration %))))

(defn declaration-state-value
  "Return a live, inspectable Clojure view of an `az/defvar`. Dereferencing or
  printing it reads the actual native state bytes, never a declaration map."
  [declaration]
  (let [declaration (declaration-info declaration)]
    (zig-value/native-value
     (select-keys declaration [:module :name :kind :type :logical-id])
     #(materialize-state! declaration))))

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
         :converted-compile-debounce-ms 25
         :reloadable? true
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
  (when (and (contains? options :reloadable?)
             (not (instance? Boolean (:reloadable? options))))
    (throw (ex-info ":reloadable? must be true or false"
                    {:value (:reloadable? options)})))
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
  (when-let [debounce-ms (:converted-compile-debounce-ms options)]
    (when-not (and (integer? debounce-ms) (not (neg? debounce-ms)))
      (throw (ex-info ":converted-compile-debounce-ms must be a non-negative integer"
                      {:value debounce-ms}))))
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
  "Forget loaded modules and close their quiescent native libraries.
  Generated, content-addressed files are retained. Refuses to clear while a
  JVM or native invocation is active."
  []
  (locking compile-lock
    (let [active-hosts
          (->> @live-hosts
               vals
               (filter #(contains? #{:starting :running} (:status %)))
               (mapv #(select-keys % [:id :function :status :started-at-ms])))
          _ (when (seq active-hosts)
              (throw (ex-info "Cannot clear Aguafria while native hosts are active"
                              {:aguafria/phase :native-clear
                               :active-hosts active-hosts})))
          active
          (->> @registry
               (mapcat (fn [[module module-state]]
                         (keep
                          (fn [{:keys [generation active-call-handle
                                      jvm-active-calls]}]
                            (let [native-active
                                  (if active-call-handle
                                    (long (.invokeWithArguments
                                           ^MethodHandle active-call-handle
                                           (ArrayList.)))
                                    0)
                                  jvm-active (if jvm-active-calls
                                               (.get ^AtomicLong jvm-active-calls)
                                               0)]
                              (when (or (pos? native-active) (pos? jvm-active))
                                {:module module
                                 :generation generation
                                 :native-active-call-count native-active
                                 :jvm-active-call-count jvm-active})))
                          (:native-generations module-state))))
               vec)]
      (when (seq active)
        (throw (ex-info "Cannot clear Aguafria while native calls are active"
                        {:aguafria/phase :native-clear
                         :active-generations active})))
      (doseq [{:keys [scheduled]} (vals @registry)]
        (when scheduled
          (.cancel ^ScheduledFuture scheduled false)))
      (doseq [module-state (vals @registry)
              ^Arena arena (vals (:jvm-state-backing-arenas module-state))]
        (.close arena))
      (doseq [module-state (vals @registry)
              generation (:native-generations module-state)]
        (.close ^Arena (:arena generation)))
      (reset! registry {})
      (reset! build-registry {})
      (reset! program-build-sequence 0)
      (reset! component-publication-sequence 0)
      (reset! live-hosts {})
      (reset! state-migrations {})
      (reset! retirement-pending-modules #{})
      nil)))

(declare declaration-info host-view)

(defn- declaration-summary
  [{:keys [declaration-key kind name qualified-name export? source logical-id
           abi-fingerprint schema-fingerprint]}]
  {:key declaration-key
   :kind kind
   :name (str name)
   :qualified-name (some-> qualified-name str)
   :logical-id logical-id
   :abi-fingerprint abi-fingerprint
   :schema-fingerprint schema-fingerprint
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
                                  :library-path :partial-publication?
                                  :full-compile-error]))))))

(defn- mark-build-failed!
  [module generation error]
  (let [finished-at (System/currentTimeMillis)
        phase (:aguafria/phase (ex-data error))
        status (if (= :zig-state-migration-required phase)
                 :migration-required
                 :failed)]
    (swap! build-registry update [module :repl generation]
           (fn [build]
             (merge build
                    {:status status
                     :finished-at-ms finished-at
                     :duration-ms (when-let [started-at (:started-at-ms build)]
                                    (- finished-at started-at))
                     :error (ex-message error)
                     :phase phase
                     :diagnostic-count (count (:diagnostics (ex-data error)))})))))

(defn- sha256
  [s]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes (str s) StandardCharsets/UTF_8)))]
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- canonical-fingerprint-value
  [value]
  (cond
    (symbol? value)
    (let [reference (:aguafria/zig-reference (meta value))]
      (cond-> [:symbol (str value)]
        (:zig/name (meta value))
        (conj [:zig/name (:zig/name (meta value))])

        reference
        (conj [:zig/reference
               (select-keys reference
                            [:kind :module :zig-name :import-name
                             :import-alias :schema-fingerprint])])))

    (keyword? value) [:keyword (namespace value) (name value)]

    (map? value)
    [:map
     (->> value
          (map (fn [[key nested]]
                 [(canonical-fingerprint-value key)
                  (canonical-fingerprint-value nested)]))
          (sort-by (comp pr-str first))
          vec)]

    (set? value)
    [:set (->> value (map canonical-fingerprint-value) (sort-by pr-str) vec)]

    (vector? value)
    [:vector (mapv canonical-fingerprint-value value)]

    (seq? value)
    [:list (mapv canonical-fingerprint-value value)]

    :else value))

(defn- data-fingerprint
  [value]
  (sha256 (pr-str (canonical-fingerprint-value value))))

(defn- declaration-zig-name
  [{:keys [name zig-name]}]
  (emit/identifier (or zig-name name)))

(defn- callable-abi
  [{:keys [args return export? zig-prefix zig-qualifiers] :as declaration}]
  {:kind :callable
   :symbol (declaration-zig-name declaration)
   :export? (boolean export?)
   :calling-convention (cond
                         (seq zig-qualifiers) zig-qualifiers
                         (and export? (nil? zig-prefix)) :c
                         :else :zig)
   :prefix zig-prefix
   :arguments
   (mapv (fn [{:keys [type properties]}]
           {:type type
            :prefix (:zig/prefix properties)
            :variadic? (boolean (:zig/variadic properties))})
         args)
   :return return
   ;; A caller compiled against a breaking type schema is a distinct live
   ;; implementation lineage even when its surface C signature is scalar.
   ;; Keeping this identity in the dispatch key prevents a running old host
   ;; from being redirected to code that interprets its stack/heap instances
   ;; with the new layout. Compatible method-only edits retain the same schema
   ;; key and therefore continue to hot-swap in place.
   :type-dependencies
   (mapv (fn [[logical-id schema-fingerprint _implementation-fingerprint]]
           [logical-id schema-fingerprint])
         (:type-dependency-fingerprints declaration))})

(defn- struct-schema
  [{:keys [layout fields] :as declaration}]
  {:kind :struct-schema
   :symbol (declaration-zig-name declaration)
   :layout (or layout :extern)
   :fields
   (mapv (fn [{:keys [name type properties]}]
           {:name (emit/identifier name)
            :type type
            ;; Documentation does not alter memory layout. Other field
            ;; properties are retained because users may attach alignment or
            ;; future schema-affecting options through the Malli-style map.
            :properties (dissoc properties :doc :comments)})
         fields)})

(defn- state-schema
  [{:keys [type]}]
  {:kind :state-schema
   ;; Inferred Zig globals are verified again from exported @sizeOf/@alignOf
   ;; data when loaded. Their initializer is implementation, not layout,
   ;; otherwise changing `0` to `1` would spuriously demand a migration.
   :type (or type :zig-inferred)})

(declare container-value-schema type-factory-schema-value)

(defn- schema-type
  [type]
  (if (and (seq? type)
           (symbol? (first type))
           (= "container" (name (first type))))
    (container-value-schema type)
    type))

(defn- nested-field-schema
  [form]
  (let [kind (keyword (name (first form)))
        arguments (rest form)
        [field-name arguments]
        (if (= :tuple-field-decl kind)
          [nil arguments]
          [(first arguments) (next arguments)])
        arguments (if (string? (first arguments)) (next arguments) arguments)
        [attributes arguments] (if (map? (first arguments))
                                 [(first arguments) (next arguments)]
                                 [{} arguments])
        type-or-value (first arguments)]
    (cond->
     {:kind kind
      :attributes (select-keys attributes
                               [:attrs :align :zig/align :zig/prefix])}
      field-name (assoc :name (emit/identifier field-name))
      (= :enum-field-decl kind) (assoc :value type-or-value)
      (contains? #{:field-decl :tuple-field-decl} kind)
      (assoc :type (schema-type type-or-value)))))

(defn- container-value-schema
  [form]
  (let [[_ options & members] form]
    {:kind :container-schema
     :container (select-keys options [:kind :layout :enum? :argument])
     :fields
     (->> members
          (filter #(and (seq? %)
                        (symbol? (first %))
                        (contains? #{"field-decl" "enum-field-decl"
                                     "tuple-field-decl"}
                                   (name (first %)))))
          (mapv nested-field-schema))
     ;; Nested type aliases participate in the effective layout even though
     ;; they are not fields themselves. For example, TigerBeetle's
     ;; `WorkloadType` declares `const Options = OptionsType(...)` and then
     ;; stores an `Options` field. Keep alias types/values in the schema while
     ;; continuing to exclude method bodies, so logic-only edits remain
     ;; compatible.
     :type-aliases
     (->> members
          (filter #(and (seq? %)
                        (symbol? (first %))
                        (= "const-decl" (name (first %)))))
          (mapv
           (fn [[_ alias-name & arguments]]
             (let [arguments (if (string? (first arguments))
                               (next arguments)
                               arguments)
                   arguments (if (map? (first arguments))
                               (next arguments)
                               arguments)]
               {:name (emit/identifier alias-name)
                :definition
                (mapv type-factory-schema-value arguments)}))))}))

(defn- container-type-declaration?
  [{:keys [kind value]}]
  (and (= :const kind)
       (seq? value)
       (symbol? (first value))
       (= "container" (name (first value)))))

(defn- type-factory-schema-value
  [value]
  (cond
    (and (seq? value)
         (symbol? (first value))
         (= "container" (name (first value))))
    (container-value-schema value)

    (map? value)
    (into (empty value)
          (map (fn [[key nested]]
                 [(type-factory-schema-value key)
                  (type-factory-schema-value nested)]))
          value)

    (vector? value) (mapv type-factory-schema-value value)
    (set? value) (into #{} (map type-factory-schema-value) value)
    (seq? value) (apply list (map type-factory-schema-value value))
    :else value))

(defn- type-factory-declaration?
  [{:keys [kind return]}]
  (and (= :fn kind) (= :type return)))

(defn declaration-info
  "Return a declaration with a stable logical identity and deterministic
  callable ABI or struct-schema fingerprint. Body-only function changes do
  not change the ABI fingerprint; signature/layout changes do. The returned
  map is plain serializable data and is also stored in Var metadata/stats."
  [{:keys [module kind declaration-key] :as declaration}]
  (let [logical-id [(str module) kind (declaration-zig-name declaration)]]
    (cond-> (assoc declaration :logical-id logical-id)
      (contains? #{:fn :fn-proto} kind)
      (assoc :abi-fingerprint (data-fingerprint (callable-abi declaration))
             :implementation-fingerprint
             (data-fingerprint
              (select-keys declaration
                           [:kind :name :zig-name :args :return :body :export?
                            :public? :zig-prefix :zig-qualifiers
                            :implicit-return?
                            :type-dependency-fingerprints
                            :callable-dependency-fingerprints])))

      (= :struct kind)
      (assoc :schema-fingerprint (data-fingerprint
                                  (struct-schema declaration)))

      (= :var kind)
      (assoc :schema-fingerprint
             (data-fingerprint (state-schema declaration)))

      (container-type-declaration? declaration)
      (assoc :schema-fingerprint
             (data-fingerprint
              {:symbol (declaration-zig-name declaration)
               :schema (container-value-schema (:value declaration))})
             ;; The schema deliberately excludes nested method bodies, but a
             ;; compatible method edit still changes every monomorphization
             ;; that copied that method at comptime.
             :implementation-fingerprint
             (data-fingerprint
              {:kind :container-type
               :symbol (declaration-zig-name declaration)
               :value (:value declaration)
               :type-dependency-fingerprints
               (:type-dependency-fingerprints declaration)}))

      (type-factory-declaration? declaration)
      (assoc :type-factory? true
             :schema-fingerprint
             (data-fingerprint
              {:kind :type-factory
               :symbol (declaration-zig-name declaration)
               :arguments (mapv #(select-keys % [:type :properties])
                                (:args declaration))
               :shape (type-factory-schema-value (:body declaration))}))

      declaration-key
      (assoc :logical-key declaration-key))))

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

(declare scalar-key)

(defn- generic-function-argument?
  [{:keys [type properties]}]
  (or (:zig/variadic properties)
      (= "comptime" (:zig/prefix properties))
      (contains? #{:anytype 'anytype :type 'type} type)))

(defn- contains-syntax-operator?
  [form operator-name]
  (boolean
   (some (fn [value]
           (and (seq? value)
                (symbol? (first value))
                (= operator-name (name (first value)))))
         (tree-seq coll? seq form))))

(defn- anonymous-type?
  [type]
  (contains-syntax-operator? type "container"))

(defn- dispatchable-declaration?
  [{:keys [kind args return zig-prefix]}]
  (and (:reloadable? @config)
       (= :fn kind)
       ;; Forced-inline wrappers can still inline the cell load and indirect
       ;; call into each concrete caller. Their implementation must be emitted
       ;; as an addressable non-inline helper (see emit-reloadable-function).
       (contains? #{nil "inline" "pub inline"} zig-prefix)
       (not (contains? #{:type 'type :anytype 'anytype} return))
       (not (anonymous-type? return))
       (not-any? #(anonymous-type? (:type %)) args)
       (not-any? generic-function-argument? args)))

(defn- declaration-dispatch-spec
  [{:keys [logical-id abi-fingerprint implementation-fingerprint]
    :as declaration}]
  (let [version-key [logical-id abi-fingerprint]
        token (subs (sha256 (pr-str version-key)) 0 24)
        module-token (subs (sha256 (str (:module declaration))) 0 16)
        prefix (str "__aguafria_" token)]
    {:declaration-key (:declaration-key declaration)
     :version-key version-key
     :logical-id logical-id
     :abi-fingerprint abi-fingerprint
     :implementation-fingerprint implementation-fingerprint
     :implementation (str prefix "_implementation")
     :dispatch-type (str prefix "_function_type")
     :dispatch (str prefix "_dispatch")
     :getter (str prefix "_implementation_address")
     :setter (str prefix "_set_dispatch")
     :active-counter (str "__aguafria_" module-token "_active_calls")
     :active-depth (str "__aguafria_" module-token "_active_depth")
     :active-tracking (str "__aguafria_" module-token "_track_active_calls")
     :active-tracking-setter
     (str "__aguafria_" module-token "_set_active_call_tracking")
     :active-getter (str "__aguafria_" module-token "_active_call_count")
     :publication-epoch (str "__aguafria_" module-token
                             "_publication_epoch")
     :publication-epoch-setter (str "__aguafria_" module-token
                                    "_set_publication_epoch")}))

(defn- reloadable-dispatch-specs
  [declarations]
  (into {}
        (comp (filter dispatchable-declaration?)
              (map (fn [declaration]
                     [(:declaration-key declaration)
                      (declaration-dispatch-spec declaration)])))
        declarations))

(defn- declaration-state-spec
  [{:keys [logical-id schema-fingerprint] :as declaration}]
  (let [version-key [logical-id schema-fingerprint]
        token (subs (sha256 (pr-str version-key)) 0 24)
        prefix (str "__aguafria_state_" token)]
    {:declaration-key (:declaration-key declaration)
     :version-key version-key
     :logical-id logical-id
     :schema-fingerprint schema-fingerprint
     :accessor (str prefix "_reference")
     :getter (str prefix "_address")
     :setter (str prefix "_set_address")
     :size-getter (str prefix "_size")
     :align-getter (str prefix "_alignment")}))

(defn state-reference
  "Return deterministic development state-reference metadata for a defvar."
  [declaration]
  (let [declaration (declaration-info declaration)]
    (when (= :var (:kind declaration))
      (select-keys (declaration-state-spec declaration)
                   [:version-key :logical-id :schema-fingerprint :accessor]))))

(defn- reloadable-state-specs
  [declarations]
  (into {}
        (comp
         (filter #(and (:reloadable? @config) (= :var (:kind %))))
         (map (juxt :declaration-key declaration-state-spec)))
        declarations))

(defn- emit-reload-source!
  [module declarations dispatch-specs state-specs]
  (if (:reloadable? @config)
    (try
      (emit/emit-reloadable-module module declarations dispatch-specs
                                   state-specs)
      (catch clojure.lang.ExceptionInfo error
        (let [declaration (or (some #(when (contains? dispatch-specs
                                                     (:declaration-key %))
                                      %)
                                    declarations)
                              (last declarations))]
          (throw (ex-info (pretty-emission-error declaration error)
                          (merge (ex-data error)
                                 {:aguafria/phase :emit
                                  :module module
                                  :declaration declaration})
                          error)))))
    (emit-source! module declarations)))

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

(defn- dependency-arguments
  [dependencies]
  (mapcat (fn [dependency]
            ["--dep" (if (instance? clojure.lang.Named dependency)
                       (name dependency)
                       (str dependency))])
          (sort-by str dependencies)))

(defn- root-module-arguments
  [source-file {:keys [optimize target cpu zig-args modules
                       module-dependencies]}]
  (let [modules (sort-by (comp str key) modules)]
    (vec
     (concat
      [(str "-O" optimize)]
      (when target ["-target" (str target)])
      (when cpu ["-mcpu" (str cpu)])
      (dependency-arguments
       (or (get module-dependencies :root) (map key modules)))
      zig-args
      [(str "-Mroot=" (absolute-path source-file))]
      (mapcat
       (fn [[module-name module-path]]
         (concat
          (dependency-arguments (get module-dependencies (str module-name)))
          [(str "-M"
                (if (instance? clojure.lang.Named module-name)
                  (name module-name)
                  (str module-name))
                "=" (absolute-path module-path))]))
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

(defn- materialize-module-source!
  [module source]
  (when-not (string? source)
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
    (.getAbsolutePath source-file)))

(defn- materialize-registered-module-source!
  [module development-dependencies?]
  (let [{:keys [source reload-source] :as module-state}
        (get @registry (str module))
        source (if development-dependencies?
                 (or reload-source source)
                 source)]
    (when-not module-state
      (throw
       (ex-info
        (str "Required Aguafria namespace `" module
             "` has no registered Zig source. Require/load that namespace "
             "before compiling its caller.")
        {:aguafria/phase :zig-dependency
         :module (str module)
         :known-modules (sort (keys @registry))})))
    (materialize-module-source! module source)))

(defn- declaration-named-module-imports
  [declarations]
  (->> (tree-seq coll? seq declarations)
       (keep (fn [value]
               (cond
                 (map? value)
                 (or (get-in value [:attributes :zig/import-name])
                     (when (= :import (:kind value))
                       (:import-name value)))

                 (and (sequential? value)
                      (symbol? (first value))
                      (= "import" (name (first value)))
                      (string? (second value)))
                 (second value))))
       (filter string?)
       distinct
       sort
       vec))

(defn- development-dependency-snapshot
  ([declarations]
   (development-dependency-snapshot declarations @registry))
  ([declarations module-states]
  (let [root-module (some-> declarations first :module str)
        direct-dependencies
        (fn [module-declarations]
          (->> (emit/declaration-imports module-declarations)
               vals
               (keep :namespace)
               (map str)
               distinct
               sort
               vec))]
    (loop [pending (direct-dependencies declarations)
           ;; A cyclic graph can reach the compilation root again. Its cells
           ;; are owned by the root library and must not be reintroduced as
           ;; dependency entries, where they would overwrite the owned entry.
           seen (cond-> #{} root-module (conj root-module))
           snapshot (sorted-map)]
      (if-let [module (first pending)]
        (if (contains? seen module)
          (recur (next pending) seen snapshot)
          (let [module-state (get module-states module)
                source (or (:reload-source module-state)
                           (:source module-state))
                dependencies
                (direct-dependencies (vals (:definitions module-state)))
                specs (or (:reload-source-dispatch-specs module-state)
                          (:dispatch-specs module-state))
                entries
                (->> specs
                     (keep (fn [[declaration-key spec]]
                             (when-let [declaration
                                        (get-in module-state
                                                [:definitions declaration-key])]
                               {:declaration declaration
                                :spec spec
                                :owned? false})))
                     vec)
                state-entries
                (->> (reloadable-state-specs
                      (vals (:definitions module-state)))
                     (keep (fn [[declaration-key spec]]
                             (when-let [declaration
                                        (get-in module-state
                                                [:definitions declaration-key])]
                               {:declaration declaration
                                :spec spec
                                :owned? false})))
                     vec)
                snapshot
                ;; A generated namespace may register before the cycle-safe
                ;; dependency loader has populated its graph. Omit that module
                ;; for now; converted jobs rebuild this immutable transitive
                ;; snapshot after loading their reachable namespaces.
                (cond-> snapshot
                  (string? source)
                  (assoc module {:module module
                                 :source source
                                 :dependencies dependencies
                                 :named-module-imports
                                 (declaration-named-module-imports
                                  (vals (:definitions module-state)))
                                 :dispatch-entries entries
                                 :state-entries state-entries}))]
            (recur (concat (next pending) dependencies)
                   (conj seen module)
                   snapshot)))
        snapshot)))))

(defn- project-generated-module-sources
  [modules overridden-names]
  (reduce
   (fn [captured module]
     (reduce-kv
      (fn [captured module-name source]
        (let [module-name (str module-name)]
          (if (contains? overridden-names module-name)
            captured
            (if-let [{existing-source :source existing-owners :owners}
                     (get captured module-name)]
              (if (= existing-source source)
                (update-in captured [module-name :owners] conj (str module))
                (throw
                 (ex-info
                  (str "Zig build profile supplies conflicting generated module `"
                       module-name "`")
                  {:aguafria/phase :zig-build-generated-module
                   :module-name module-name
                   :first-owners (sort existing-owners)
                   :second-owner (str module)
                   :hint (str "Convert/select one build profile whose reachable "
                              "modules agree, or explicitly override the module "
                              "through az/configure!.")})))
              (assoc captured module-name
                     {:source source :owners #{(str module)}})))))
      captured
      (project/generated-modules module)))
   (sorted-map)
   (distinct (remove nil? modules))))

(defn- development-profile-module
  [declarations]
  (or (some-> declarations first :attributes
              :zig/build-profile-owner str)
      (some-> declarations first :module str)))

(defn- compiler-options-for-declarations
  [compiler-options declarations]
  (let [development-dependencies?
        (boolean (:development-dependencies? compiler-options))
        dependency-snapshot (:dependency-snapshot compiler-options)
        development-root-source (:development-root-source compiler-options)
        development-root-module (some-> declarations first :module str)
        development-profile-module (development-profile-module declarations)
        compiler-options (dissoc compiler-options :development-dependencies?
                                 :dependency-snapshot
                                 :development-root-source)
        root-dependencies
        (->> (emit/declaration-imports declarations)
             vals
             (keep :namespace)
             (map str)
             distinct
             sort
             vec)
        root-named-module-imports
        (declaration-named-module-imports declarations)
        automatic
        (if development-dependencies?
          (into {}
                (map (fn [[module {:keys [source]}]]
                       [module (materialize-module-source! module source)]))
                dependency-snapshot)
          (into {}
                (keep (fn [[_ {:keys [import-name namespace]}]]
                        (when namespace
                          [import-name
                           (materialize-registered-module-source!
                            namespace false)])))
                (emit/declaration-imports declarations)))
        automatic
        (if development-dependencies?
          (reduce
           (fn [modules module]
             (if (contains? modules module)
               modules
               (assoc modules module
                      (materialize-registered-module-source! module true))))
           automatic
           root-dependencies)
          automatic)
        automatic
        (if (and development-dependencies?
                 development-root-module
                 (string? development-root-source))
          (assoc automatic development-root-module
                 (materialize-module-source! development-root-module
                                             development-root-source))
          automatic)
        configured
        (into {}
              (map (fn [[module path]]
                     [(if (instance? clojure.lang.Named module)
                        (name module)
                        (str module))
                      path]))
              (:modules compiler-options))
        ;; A root-specific build profile (for example TigerBeetle's VOPR)
        ;; overrides fallback options captured on dependencies. Direct imports
        ;; are still dependencies here: putting them at the root's rank would
        ;; make a VOPR-owned `vsr_options` conflict with the fallback captured
        ;; on `vsr.zig`. Conflicts among equally ranked transitive owners remain
        ;; errors.
        preferred-generated
        (project-generated-module-sources
         [development-profile-module]
         (set (keys configured)))
        captured-generated
        (merge
         (project-generated-module-sources
          (keys dependency-snapshot)
          (into (set (keys configured)) (keys preferred-generated)))
         preferred-generated)
        generated
        (into {}
              (keep (fn [[module-name {:keys [source]}]]
                      (when-not (contains? configured module-name)
                        [module-name
                         (materialize-module-source!
                          (str "build-generated-" module-name) source)])))
              captured-generated)
        external-candidates (merge generated configured)
        required-external-names
        (->> (concat root-named-module-imports
                     (mapcat :named-module-imports
                             (vals dependency-snapshot)))
             (filter (set (keys external-candidates)))
             set)
        ;; Zig 0.16 rejects even an otherwise valid `-Mname=...` module when
        ;; no module in this compilation graph declares it through `--dep`.
        ;; A declaration live-slice therefore carries only the named modules
        ;; it (or its reachable namespace modules) actually imports.
        external (select-keys external-candidates required-external-names)
        external-names (set (keys external))
        module-dependencies
        (when development-dependencies?
          (into {:root (->> (concat [development-root-module
                                     development-profile-module])
                            distinct
                            (remove nil?)
                            (sort-by str)
                            vec)
                 development-root-module
                 (->> (concat root-dependencies
                              (filter external-names
                                      root-named-module-imports))
                      distinct
                      (sort-by str)
                      vec)}
                (concat
                 (map (fn [[module {:keys [dependencies
                                            named-module-imports]}]]
                        [module (->> (concat dependencies
                                             (filter external-names
                                                     named-module-imports))
                                    distinct
                                    (sort-by str)
                                    vec)])
                      dependency-snapshot))))
        conflicts (->> (keys automatic)
                       (filter #(contains? external %))
                       sort
                       vec)]
    (when (seq conflicts)
      (throw (ex-info
              "Configured Zig modules conflict with required Aguafria namespaces"
              {:aguafria/phase :zig-dependency
               :module-names conflicts
               :configured configured
               :generated (keys generated)
               :automatic automatic})))
    (cond-> (assoc compiler-options
                   :modules (merge external automatic)
                   ;; Automatic and build-generated modules are materialized
                   ;; beneath content-addressed paths, so they cannot make an
                   ;; otherwise identical artifact stale. User-configured
                   ;; module paths are mutable and remain deliberately
                   ;; non-cacheable until their contents are fingerprinted.
                   :cache-safe? (empty? configured))
      module-dependencies
      (assoc :module-dependencies module-dependencies))))

(defn- namespace-source-file
  [^File root module]
  (io/file root
           (str (-> (str module)
                    (str/replace "." File/separator)
                    (str/replace "-" "_"))
                ".clj")))

(defn- converted-source-root
  [module declarations]
  (when-let [source-file (some #(get-in % [:source :file]) declarations)]
    (let [direct (io/file source-file)
          resource (when-not (.isFile direct) (io/resource source-file))
          file (.getCanonicalFile
                (if (and resource (= "file" (.getProtocol resource)))
                  (io/file resource)
                  direct))
          segment-count (count (str/split (str module) #"\."))
          root (nth (iterate #(.getParentFile ^File %) file) segment-count nil)]
      (when (and root
                 (= file (.getCanonicalFile (namespace-source-file root module))))
        root))))

(defn- converted-project-dependencies
  [module]
  (->> (:imports (project/module-data module))
       vals
       (keep :namespace)
       distinct
       sort))

(defn- load-converted-source-only!
  [^File root module]
  (let [module (str module)]
    (when-not (or (string? (:source (get @registry module)))
                  (contains? *converted-dependency-loading* module))
      (binding [*converted-dependency-loading*
                (conj *converted-dependency-loading* module)]
        ;; Load dependencies first. Eager `:as` edges then find normal loaded
        ;; Clojure namespaces, while `:as-alias` cycle edges are made concrete
        ;; by this cycle-aware traversal without recursive `require` failure.
        (doseq [dependency (converted-project-dependencies module)]
          (load-converted-source-only! root dependency))
        (when-not (string? (:source (get @registry module)))
          (let [file (.getCanonicalFile (namespace-source-file root module))]
            (when-not (.isFile file)
              (throw
               (ex-info
                (str "Converted Aguafria dependency `" module
                     "` is not available below the generated source root.")
                {:aguafria/phase :zig-dependency
                 :module module
                 :generated-root (.getAbsolutePath root)
                 :expected-source (.getAbsolutePath file)})))
            (let [collector (atom [])]
              (binding [*registration-batch* collector]
                (load-file (.getAbsolutePath file)))
              (doseq [[loaded-module declarations]
                      (group-by :module @collector)]
                (register-batch! declarations
                                 {:module loaded-module
                                  :compile? false
                                  :replace? true})))))))))

(defn- ensure-converted-dependency-sources!
  [module declarations]
  (when (project/converted-module? module)
    (when-let [root (converted-source-root module declarations)]
      (locking converted-load-lock
        (binding [*converted-dependency-loading* #{(str module)}]
          (doseq [dependency (converted-project-dependencies module)]
            (load-converted-source-only! root dependency)))))))

(defn- compile-source!
  ([module-name source declarations]
   (compile-source! module-name source declarations nil))
  ([module-name source declarations dependency-snapshot]
   (let [development-dependencies? true
         profile-module (development-profile-module declarations)
         {:keys [cache-dir optimize zig] :as compiler-options}
         (compiler-options-for-declarations
          (cond-> (assoc @config
                         :development-dependencies? development-dependencies?
                         :development-root-source source)
            dependency-snapshot (assoc :dependency-snapshot dependency-snapshot))
          declarations)
        compiler-version (zig-version)
        hash-input [source compiler-version
                    (select-keys compiler-options
                                 [:optimize :target :cpu :zig-args :modules
                                  :module-dependencies])
                    (System/getProperty "os.name") (System/getProperty "os.arch")]
        source-hash (subs (sha256 (pr-str hash-input)) 0 24)
        module-dir (io/file cache-dir (safe-path-component module-name) source-hash)
        source-file (io/file module-dir "module.zig")
        profile-root-declarations
        (if (= (str module-name) profile-module)
          ;; A breaking declaration may compile as an intentionally small
          ;; live slice of the profile root. Re-exporting every declaration
          ;; registered in the full module would reference names that are not
          ;; present in that slice (for example VOPR's `std_options`).
          declarations
          (vals (get-in @registry [profile-module :definitions])))
        compiler-source
        (if development-dependencies?
          (str "// Aguafria development loader.\n"
               "const aguafria_module = @import("
               (emit/emit-expr (str module-name)) ");\n"
               (when (and profile-module
                          (not= (str module-name) profile-module))
                 (str "const aguafria_profile_root = @import("
                      (emit/emit-expr profile-module) ");\n"))
               (->> profile-root-declarations
                    (filter :public?)
                    (filter #(contains? #{:const :struct :fn :fn-proto}
                                        (:kind %)))
                    (map (fn [declaration]
                           (let [declaration-name
                                 (emit/identifier
                                  (or (:zig-name declaration)
                                      (:name declaration)))
                                 source-module
                                 (if (= (str module-name) profile-module)
                                   "aguafria_module"
                                   "aguafria_profile_root")]
                             (str "pub const " declaration-name " = "
                                  source-module "." declaration-name ";\n"))))
                    distinct
                    sort
                    (apply str))
               "comptime { _ = aguafria_module; }\n")
          source)
        library-name (System/mapLibraryName
                      (str "aguafria_" (safe-path-component module-name) "_" source-hash))
        library-file (io/file module-dir library-name)
        command (vec (concat
                      [zig "build-lib" "-dynamic"
                       (str "-femit-bin=" (.getAbsolutePath library-file))]
                      (root-module-arguments source-file compiler-options)))]
    (.mkdirs ^File module-dir)
    (when-not (= compiler-source
                 (when (.isFile source-file) (slurp source-file)))
      (spit source-file compiler-source))
    (let [artifact-lock (get (swap! artifact-locks
                                    #(if (contains? % (.getAbsolutePath library-file))
                                       %
                                       (assoc % (.getAbsolutePath library-file) (Object.))))
                             (.getAbsolutePath library-file))]
      (locking artifact-lock
        (let [cache-safe? (and (:cache-safe? compiler-options)
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
            :compiled-source source
            :source-path (.getAbsolutePath source-file)
            :library-path (.getAbsolutePath library-file)
            :command command
            :compiler-output result}))))))

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
  ([^Linker linker ^SymbolLookup lookup declaration]
   (bind-function linker lookup declaration
                  (emit/identifier (or (:zig-name declaration)
                                       (:name declaration)))))
  ([^Linker linker ^SymbolLookup lookup declaration symbol-name]
  (if-not (supported-signature? declaration)
    {:declaration declaration
     :unsupported? true}
    (let [address (-> (.find lookup symbol-name)
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
       :handle handle}))))

(defn- bind-nested-storage-spec
  [spec {:keys [bind-long bind-present bind-address bind-optional-set
                bind-slice-set bind-error-set]}]
  (when spec
    (let [bound-child (bind-nested-storage-spec (:child spec)
                                                {:bind-long bind-long
                                                 :bind-present bind-present
                                                 :bind-address bind-address
                                                 :bind-optional-set
                                                 bind-optional-set
                                                 :bind-slice-set bind-slice-set
                                                 :bind-error-set bind-error-set})]
      (cond-> (assoc spec :child-storage-binding bound-child)
        (= :optional (:storage-kind spec))
        (assoc
         :optional-set-handle (bind-optional-set (:optional-set spec))
         :optional-present-handle (bind-present (:optional-present spec))
         :optional-payload-address-handle
         (bind-address (:optional-payload-address spec))
         :optional-payload-size-handle
         (bind-long (:optional-payload-size spec)))

        (= :slice (:storage-kind spec))
        (assoc
         :slice-set-handle (bind-slice-set (:slice-set spec))
         :slice-pointer-handle (bind-address (:slice-pointer spec))
         :slice-length-handle (bind-address (:slice-length spec))
         :slice-element-size-handle (bind-long (:slice-element-size spec))
         :slice-element-align-handle (bind-long (:slice-element-align spec)))

        (= :error-union (:storage-kind spec))
        (assoc
         :error-set-ok-handle (bind-error-set (:error-set-ok spec))
         :error-set-error-handle (bind-error-set (:error-set-error spec))
         :error-present-handle (bind-present (:error-present spec))
         :error-code-handle (bind-address (:error-code spec))
         :error-name-pointer-handle (bind-address (:error-name-pointer spec))
         :error-name-length-handle (bind-address (:error-name-length spec))
         :error-payload-address-handle
         (bind-address (:error-payload-address spec))
         :error-payload-size-handle
         (bind-long (:error-payload-size spec)))))))

(defn- bind-jvm-callable
  [^Linker linker ^SymbolLookup lookup qualified-name
   {:keys [declaration symbol mode argument-modes return-mode
           native-argument-specs result-size-getter
           result-align-getter] :as spec}]
  (if (= :direct mode)
    (assoc (bind-function linker lookup declaration symbol)
           :bridge-spec spec)
    (let [find-required
          (fn [symbol-name]
            (-> (.find lookup symbol-name)
                (.orElseThrow
                 (reify java.util.function.Supplier
                   (get [_]
                     (ex-info "JVM Zig callable bridge symbol was not found"
                              {:function qualified-name
                               :symbol symbol-name
                               :declaration declaration}))))))
          argument-layouts
          (mapv (fn [{:keys [type]} argument-mode]
                  (if (= :scalar argument-mode)
                    (get scalar-layouts (scalar-key type))
                    ValueLayout/JAVA_LONG))
                (:args declaration) argument-modes)
          argument-layouts (cond-> argument-layouts
                             (= :native return-mode)
                             (conj ValueLayout/JAVA_LONG))
          argument-layouts-array (into-array MemoryLayout argument-layouts)
          descriptor
          (if (= :scalar return-mode)
            (FunctionDescriptor/of
             ^MemoryLayout (get scalar-layouts
                                (scalar-key (:return declaration)))
             argument-layouts-array)
            (FunctionDescriptor/ofVoid argument-layouts-array))
          options (into-array Linker$Option [])
          handle (.downcallHandle linker (find-required symbol)
                                  descriptor options)
          bind-long-getter
          (fn [symbol-name]
            (.downcallHandle
             linker
             (find-required symbol-name)
             (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                                    (make-array MemoryLayout 0))
             options))
          bind-present
          (fn [symbol-name]
            (.downcallHandle
             linker (find-required symbol-name)
             (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_BYTE
                                    (into-array MemoryLayout
                                                [ValueLayout/JAVA_LONG]))
             options))
          bind-address
          (fn [symbol-name]
            (.downcallHandle
             linker (find-required symbol-name)
             (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                                    (into-array MemoryLayout
                                                [ValueLayout/JAVA_LONG]))
             options))
          bind-optional-set
          (fn [symbol-name]
            (.downcallHandle
             linker (find-required symbol-name)
             (FunctionDescriptor/ofVoid
              (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                        ValueLayout/JAVA_BYTE
                                        ValueLayout/JAVA_LONG]))
             options))
          bind-slice-set
          (fn [symbol-name]
            (.downcallHandle
             linker (find-required symbol-name)
             (FunctionDescriptor/ofVoid
              (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                        ValueLayout/JAVA_LONG
                                        ValueLayout/JAVA_LONG]))
             options))
          bind-error-set
          (fn [symbol-name]
            (.downcallHandle
             linker (find-required symbol-name)
             (FunctionDescriptor/ofVoid
              (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                        ValueLayout/JAVA_LONG]))
             options))
          bind-optional-spec
          (fn [{:keys [optional? optional-set optional-present
                       optional-payload-address optional-payload-size
                       slice? slice-set slice-pointer slice-length
                       slice-element-size slice-element-align
                       error-union? error-set-ok error-set-error
                       error-present error-code error-name-pointer
                       error-name-length error-payload-address
                       error-payload-size]
                :as optional-spec}]
            (let [bound
                  (cond-> optional-spec
                    optional?
                    (assoc
                     :optional-set-handle (bind-optional-set optional-set)
                     :optional-present-handle (bind-present optional-present)
                     :optional-payload-address-handle
                     (bind-address optional-payload-address)
                     :optional-payload-size-handle
                     (bind-long-getter optional-payload-size))
                    slice?
                    (assoc
                     :slice-set-handle (bind-slice-set slice-set)
                     :slice-pointer-handle (bind-address slice-pointer)
                     :slice-length-handle (bind-address slice-length)
                     :slice-element-size-handle
                     (bind-long-getter slice-element-size)
                     :slice-element-align-handle
                     (bind-long-getter slice-element-align))
                    error-union?
                    (assoc
                     :error-set-ok-handle (bind-error-set error-set-ok)
                     :error-set-error-handle (bind-error-set error-set-error)
                     :error-present-handle (bind-present error-present)
                     :error-code-handle (bind-address error-code)
                     :error-name-pointer-handle (bind-address error-name-pointer)
                     :error-name-length-handle (bind-address error-name-length)
                     :error-payload-address-handle
                     (bind-address error-payload-address)
                     :error-payload-size-handle
                     (bind-long-getter error-payload-size)))]
              (assoc bound :nested-storage-binding
                     (bind-nested-storage-spec
                      (:nested-storage-spec optional-spec)
                      {:bind-long bind-long-getter
                       :bind-present bind-present
                       :bind-address bind-address
                       :bind-optional-set bind-optional-set
                       :bind-slice-set bind-slice-set
                       :bind-error-set bind-error-set}))))]
      (cond-> {:declaration declaration
               :descriptor descriptor
               :handle handle
               :bridge-spec spec
               :native-argument-bindings
               (mapv (fn [argument-spec]
                       (when argument-spec
                         (bind-optional-spec
                          (assoc argument-spec
                                 :size-getter-handle
                                 (bind-long-getter (:size-getter argument-spec))
                                 :align-getter-handle
                                 (bind-long-getter (:align-getter argument-spec))))))
                     native-argument-specs)}
        (= :native return-mode)
        (merge
         (bind-optional-spec
          {:optional? (:result-optional? spec)
           :optional-child-type (:result-optional-child-type spec)
           :optional-set (:result-optional-set spec)
           :optional-present (:result-optional-present spec)
           :optional-payload-address (:result-optional-payload-address spec)
           :optional-payload-size (:result-optional-payload-size spec)
           :slice? (:result-slice? spec)
           :slice-element-type (:result-slice-element-type spec)
           :slice-set (:result-slice-set spec)
           :slice-pointer (:result-slice-pointer spec)
           :slice-length (:result-slice-length spec)
           :slice-element-size (:result-slice-element-size spec)
           :slice-element-align (:result-slice-element-align spec)
           :error-union? (:result-error-union? spec)
           :error-payload-type (:result-error-payload-type spec)
           :error-set (:result-error-set spec)
           :error-set-ok (:result-error-set-ok spec)
           :error-set-error (:result-error-set-error spec)
           :error-present (:result-error-present spec)
           :error-code (:result-error-code spec)
           :error-name-pointer (:result-error-name-pointer spec)
           :error-name-length (:result-error-name-length spec)
           :error-payload-address (:result-error-payload-address spec)
           :error-payload-size (:result-error-payload-size spec)
           :nested-storage-spec (:result-nested-storage-spec spec)})
         {:result-size-getter-handle (bind-long-getter result-size-getter)
          :result-align-getter-handle
          (bind-long-getter result-align-getter)})))))

(defn- bind-dispatch
  [^Linker linker ^SymbolLookup lookup declaration spec required?]
  (let [find-symbol (fn [symbol-name]
                      (.orElse (.find lookup symbol-name) nil))
        getter-address (find-symbol (:getter spec))
        setter-address (find-symbol (:setter spec))
        _ (when (and required? (nil? setter-address))
            (throw (ex-info "Reload dispatch symbol was not found"
                            {:symbol (:setter spec)
                             :declaration declaration
                             :dispatch-spec spec})))
        _ (when (and getter-address (nil? setter-address))
            (throw (ex-info "Reload dispatch symbols were only partially linked"
                            {:getter (:getter spec)
                             :getter-found? (boolean getter-address)
                             :setter (:setter spec)
                             :setter-found? (boolean setter-address)
                             :declaration declaration
                             :dispatch-spec spec})))
        options (into-array Linker$Option [])
        getter-descriptor
        (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                               (make-array MemoryLayout 0))
        setter-descriptor
        (FunctionDescriptor/ofVoid
         (into-array MemoryLayout [ValueLayout/JAVA_LONG]))]
    (when setter-address
      (let [setter (.downcallHandle linker setter-address
                                    setter-descriptor options)
            binding (assoc spec
                           :declaration declaration
                           :setter-handle setter)]
        (if getter-address
          (let [getter (.downcallHandle linker getter-address
                                        getter-descriptor options)]
            (assoc binding
                   :implementation-address
                   (long (.invokeWithArguments ^MethodHandle getter
                                               (ArrayList.)))))
          binding)))))

(defn- bind-active-call-counter
  [^Linker linker ^SymbolLookup lookup dispatch-specs]
  (when-let [symbol-name (some-> dispatch-specs first val :active-getter)]
    (let [address
          (-> (.find lookup symbol-name)
              (.orElseThrow
               (reify java.util.function.Supplier
                 (get [_]
                   (ex-info "Reload active-call symbol was not found"
                            {:symbol symbol-name})))))
          descriptor
          (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                                 (make-array MemoryLayout 0))]
      (.downcallHandle linker address descriptor
                       (into-array Linker$Option [])))))

(defn- native-host-active?
  []
  (boolean
   (some #(contains? #{:starting :running} (:status %))
         (vals @live-hosts))))

(defn- freeze-active-host-dispatch!
  [declaration]
  (let [frozen-at (System/currentTimeMillis)
        reason {:logical-id (:logical-id declaration)
                :schema-fingerprint (:schema-fingerprint declaration)
                :frozen-at-ms frozen-at}]
    (swap! live-hosts
           (fn [hosts]
             (into {}
                   (map (fn [[id host]]
                          [id
                           (if (contains? #{:starting :running}
                                          (:status host))
                             (assoc host
                                    :dispatch-frozen? true
                                    :dispatch-frozen-reason reason)
                             host)]))
                   hosts))))
  nil)

(defn- bind-active-call-tracking
  [^Linker linker ^SymbolLookup lookup dispatch-specs]
  (when-let [symbol-name
             (some-> dispatch-specs first val :active-tracking-setter)]
    (let [address
          (-> (.find lookup symbol-name)
              (.orElseThrow
               (reify java.util.function.Supplier
                 (get [_]
                   (ex-info "Reload active-call tracking symbol was not found"
                            {:symbol symbol-name})))))
          descriptor
          (FunctionDescriptor/ofVoid
           (into-array MemoryLayout [ValueLayout/JAVA_BYTE]))]
      (.downcallHandle linker address descriptor
                       (into-array Linker$Option [])))))

(defn- set-active-call-tracking-handle!
  [handle enabled?]
  (when handle
    (.invokeWithArguments
     ^MethodHandle handle
     (ArrayList. ^java.util.Collection
                 [(byte (if enabled? 1 0))])))
  nil)

(defn- bind-publication-epoch!
  [^Linker linker ^SymbolLookup lookup specs]
  (let [descriptor
        (FunctionDescriptor/ofVoid
         (into-array MemoryLayout [ValueLayout/JAVA_LONG]))
        options (into-array Linker$Option [])
        epoch-address (.address ^MemorySegment dispatch-publication-epoch)]
    (mapv
     (fn [symbol-name]
       (let [address
             (-> (.find lookup symbol-name)
                 (.orElseThrow
                  (reify java.util.function.Supplier
                    (get [_]
                      (ex-info "Reload publication-epoch symbol was not found"
                               {:symbol symbol-name})))))
             setter (.downcallHandle linker address descriptor options)]
         (.invokeWithArguments ^MethodHandle setter
                               (ArrayList. ^java.util.Collection
                                           [(long epoch-address)]))
         setter))
     (->> specs
          (keep :publication-epoch-setter)
          distinct
          sort))))

(defn- bind-state
  [^Linker linker ^SymbolLookup lookup declaration spec]
  (let [find-required
        (fn [symbol-name]
          (-> (.find lookup symbol-name)
              (.orElseThrow
               (reify java.util.function.Supplier
                 (get [_]
                   (ex-info "Reload state symbol was not found"
                            {:symbol symbol-name
                             :declaration declaration
                             :state-spec spec}))))))
        options (into-array Linker$Option [])
        getter-descriptor
        (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                               (make-array MemoryLayout 0))
        setter-descriptor
        (FunctionDescriptor/ofVoid
         (into-array MemoryLayout [ValueLayout/JAVA_LONG]))
        bind-getter
        (fn [symbol-name]
          (.downcallHandle linker (find-required symbol-name)
                           getter-descriptor options))
        address-handle (bind-getter (:getter spec))
        size-handle (bind-getter (:size-getter spec))
        align-handle (bind-getter (:align-getter spec))
        setter-handle
        (.downcallHandle linker (find-required (:setter spec))
                         setter-descriptor options)
        call-long
        (fn [handle]
          (long (.invokeWithArguments ^MethodHandle handle (ArrayList.))))]
    (assoc spec
           :declaration declaration
           :address (call-long address-handle)
           :size (call-long size-handle)
           :alignment (call-long align-handle)
           :setter-handle setter-handle)))

(defn- dependency-dispatch-entries
  [dependency-snapshot]
  (->> dependency-snapshot vals (mapcat :dispatch-entries) vec))

(defn- dependency-state-entries
  [dependency-snapshot]
  (->> dependency-snapshot vals (mapcat :state-entries) vec))

(defn- bind-jvm-value
  [^Linker linker ^SymbolLookup lookup
   qualified-name {:keys [declaration mode getter address-getter size-getter
                          align-getter] :as spec}]
  (let [options (into-array Linker$Option [])
        find-required
        (fn [symbol-name]
          (-> (.find lookup symbol-name)
              (.orElseThrow
               (reify java.util.function.Supplier
                 (get [_]
                   (ex-info "JVM Zig value accessor was not found"
                            {:value qualified-name
                             :symbol symbol-name
                             :declaration declaration}))))))
        bind-zero
        (fn [symbol-name layout]
          (.downcallHandle linker
                           (find-required symbol-name)
                           (FunctionDescriptor/of
                            ^MemoryLayout layout
                            (make-array MemoryLayout 0))
                           options))
        bind-one
        (fn [symbol-name layout]
          (.downcallHandle linker
                           (find-required symbol-name)
                           (FunctionDescriptor/of
                            ^MemoryLayout layout
                            (into-array MemoryLayout [ValueLayout/JAVA_LONG]))
                           options))
        bind-optional-set
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/ofVoid
            (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_BYTE
                                      ValueLayout/JAVA_LONG]))
           options))
        bind-slice-set
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/ofVoid
            (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_LONG]))
           options))
        bind-error-set
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/ofVoid
            (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_LONG]))
           options))]
    (merge
     spec
     {:qualified-name qualified-name}
     (if (= :scalar mode)
       {:getter-handle
        (bind-zero getter (get scalar-layouts (scalar-key (:type declaration))))}
       (let [bound
             (cond->
              {:address-getter-handle
               (bind-zero address-getter ValueLayout/JAVA_LONG)
               :size-getter-handle
               (bind-zero size-getter ValueLayout/JAVA_LONG)
               :align-getter-handle
               (bind-zero align-getter ValueLayout/JAVA_LONG)}
         (:optional? spec)
         (assoc
          :optional-set-handle (bind-optional-set (:optional-set spec))
          :optional-present-handle
          (bind-one (:optional-present spec) ValueLayout/JAVA_BYTE)
          :optional-payload-address-handle
          (bind-one (:optional-payload-address spec) ValueLayout/JAVA_LONG)
          :optional-payload-size-handle
          (bind-zero (:optional-payload-size spec)
                     ValueLayout/JAVA_LONG))
         (:slice? spec)
         (assoc
          :slice-set-handle (bind-slice-set (:slice-set spec))
          :slice-pointer-handle
          (bind-one (:slice-pointer spec) ValueLayout/JAVA_LONG)
          :slice-length-handle
          (bind-one (:slice-length spec) ValueLayout/JAVA_LONG)
          :slice-element-size-handle
          (bind-zero (:slice-element-size spec) ValueLayout/JAVA_LONG)
          :slice-element-align-handle
          (bind-zero (:slice-element-align spec) ValueLayout/JAVA_LONG))
         (:error-union? spec)
         (assoc
          :error-set-ok-handle (bind-error-set (:error-set-ok spec))
          :error-set-error-handle (bind-error-set (:error-set-error spec))
          :error-present-handle
          (bind-one (:error-present spec) ValueLayout/JAVA_BYTE)
          :error-code-handle
          (bind-one (:error-code spec) ValueLayout/JAVA_LONG)
          :error-name-pointer-handle
          (bind-one (:error-name-pointer spec) ValueLayout/JAVA_LONG)
          :error-name-length-handle
          (bind-one (:error-name-length spec) ValueLayout/JAVA_LONG)
          :error-payload-address-handle
          (bind-one (:error-payload-address spec) ValueLayout/JAVA_LONG)
          :error-payload-size-handle
          (bind-zero (:error-payload-size spec) ValueLayout/JAVA_LONG)))]
         (assoc bound :nested-storage-binding
                (bind-nested-storage-spec
                 (:nested-storage-spec spec)
                 {:bind-long #(bind-zero % ValueLayout/JAVA_LONG)
                  :bind-present #(bind-one % ValueLayout/JAVA_BYTE)
                  :bind-address #(bind-one % ValueLayout/JAVA_LONG)
                  :bind-optional-set bind-optional-set
                  :bind-slice-set bind-slice-set
                  :bind-error-set bind-error-set})))))))

(defn- bind-jvm-type
  [^Linker linker ^SymbolLookup lookup qualified-name
   {:keys [declaration size-getter align-getter field-specs
           enum-member-specs] :as spec}]
  (let [options (into-array Linker$Option [])
        find-required
        (fn [symbol-name]
          (-> (.find lookup symbol-name)
              (.orElseThrow
               (reify java.util.function.Supplier
                 (get [_]
                   (ex-info "JVM Zig type accessor was not found"
                            {:type qualified-name
                             :symbol symbol-name
                             :declaration declaration}))))) )
        bind-long
        (fn [symbol-name]
          (let [address (find-required symbol-name)]
            (.downcallHandle
             linker address
             (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                                    (make-array MemoryLayout 0))
             options)))
        bind-union-init
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/ofVoid
            (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_LONG]))
           options))
        bind-union-active
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_BYTE
                                  (into-array MemoryLayout
                                              [ValueLayout/JAVA_LONG]))
           options))
        bind-optional-set
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/ofVoid
            (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_BYTE
                                      ValueLayout/JAVA_LONG]))
           options))
        bind-slice-set
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/ofVoid
            (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_LONG
                                      ValueLayout/JAVA_LONG]))
           options))
        bind-address-from-address
        (fn [symbol-name]
          (.downcallHandle
           linker (find-required symbol-name)
           (FunctionDescriptor/of ^MemoryLayout ValueLayout/JAVA_LONG
                                  (into-array MemoryLayout
                                              [ValueLayout/JAVA_LONG]))
           options))]
    (assoc spec
           :qualified-name qualified-name
           :size-getter-handle (bind-long size-getter)
           :align-getter-handle (bind-long align-getter)
           :field-bindings
           (mapv (fn [{:keys [offset-getter size-getter union?
                              union-init union-active
                              union-payload-address optional?
                              optional-set optional-present
                              optional-payload-address optional-payload-size
                              slice? slice-set slice-pointer slice-length
                              slice-element-size slice-element-align
                              error-union? error-set-ok error-set-error
                              error-present error-code error-name-pointer
                              error-name-length error-payload-address
                              error-payload-size nested-storage-spec]
                       :as field-spec}]
                   (let [bound
                         (cond->
                          (assoc field-spec
                                 :offset-getter-handle (bind-long offset-getter)
                                 :size-getter-handle (bind-long size-getter))
                     union?
                     (assoc :union-init-handle (bind-union-init union-init))
                     (and union? (:tagged-union? spec))
                     (assoc
                      :union-active-handle
                      (bind-union-active union-active)
                      :union-payload-address-handle
                      (bind-address-from-address union-payload-address))
                     optional?
                     (assoc
                      :optional-set-handle
                      (bind-optional-set optional-set)
                      :optional-present-handle
                      (bind-union-active optional-present)
                      :optional-payload-address-handle
                      (bind-address-from-address optional-payload-address)
                      :optional-payload-size-handle
                      (bind-long optional-payload-size))
                     slice?
                     (assoc
                      :slice-set-handle (bind-slice-set slice-set)
                      :slice-pointer-handle
                      (bind-address-from-address slice-pointer)
                      :slice-length-handle
                      (bind-address-from-address slice-length)
                      :slice-element-size-handle
                      (bind-long slice-element-size)
                      :slice-element-align-handle
                      (bind-long slice-element-align))
                     error-union?
                     (assoc
                      :error-set-ok-handle (bind-union-init error-set-ok)
                      :error-set-error-handle (bind-union-init error-set-error)
                      :error-present-handle (bind-union-active error-present)
                      :error-code-handle
                      (bind-address-from-address error-code)
                      :error-name-pointer-handle
                      (bind-address-from-address error-name-pointer)
                      :error-name-length-handle
                      (bind-address-from-address error-name-length)
                      :error-payload-address-handle
                      (bind-address-from-address error-payload-address)
                      :error-payload-size-handle
                      (bind-long error-payload-size)))]
                     (assoc bound :nested-storage-binding
                            (bind-nested-storage-spec
                             nested-storage-spec
                             {:bind-long bind-long
                              :bind-present bind-union-active
                              :bind-address bind-address-from-address
                              :bind-optional-set bind-optional-set
                              :bind-slice-set bind-slice-set
                              :bind-error-set bind-union-init}))))
                 field-specs)
           :enum-member-bindings
           (mapv (fn [{:keys [address-getter] :as member-spec}]
                   (assoc member-spec
                          :address-getter-handle
                          (bind-long address-getter)))
                 enum-member-specs))))

(defn- load-module
  ([compiled declarations dispatch-specs dependency-entries
    dependency-state-entries]
   (load-module compiled declarations dispatch-specs dependency-entries
                dependency-state-entries {} {} {}))
  ([compiled declarations dispatch-specs dependency-entries
    dependency-state-entries jvm-callable-specs jvm-value-specs
    jvm-type-specs]
  (let [arena (Arena/ofShared)]
    (try
      (let [lookup (SymbolLookup/libraryLookup
                  ^Path (.toPath (io/file (:library-path compiled))) arena)
          linker (Linker/nativeLinker)
          functions (->> declarations
                         (filter #(and (= :fn (:kind %)) (:export? %)))
                         (map (fn [declaration]
                                [(:qualified-name declaration)
                                 (bind-function linker lookup declaration)]))
                         (into {}))
          functions
          (reduce-kv
           (fn [functions qualified-name spec]
             (assoc functions qualified-name
                    (bind-jvm-callable linker lookup qualified-name spec)))
           functions
           jvm-callable-specs)
          values
          (reduce-kv
           (fn [values qualified-name spec]
             (assoc values qualified-name
                    (bind-jvm-value linker lookup qualified-name spec)))
           {}
           jvm-value-specs)
          types
          (reduce-kv
           (fn [types qualified-name spec]
             (assoc types qualified-name
                    (bind-jvm-type linker lookup qualified-name spec)))
           {}
           jvm-type-specs)
          declarations-by-key (into {} (map (juxt :declaration-key identity))
                                    declarations)
          dispatch-entries
          (concat
           (keep (fn [[declaration-key spec]]
                   (when-let [declaration (get declarations-by-key declaration-key)]
                     {:declaration declaration :spec spec :owned? true}))
                 dispatch-specs)
           dependency-entries)
          dispatch-bindings
          (into {}
                (keep (fn [{:keys [declaration spec owned?]}]
                        (when-let [binding (bind-dispatch linker lookup declaration
                                                         spec owned?)]
                          [(:version-key spec)
                           (assoc binding :owned? owned?)])))
                dispatch-entries)
          state-specs (reloadable-state-specs declarations)
          state-entries
          (concat
           (map (fn [[declaration-key spec]]
                  {:declaration (get declarations-by-key declaration-key)
                   :spec spec
                   :owned? true})
                state-specs)
           dependency-state-entries)
          state-bindings
          (into {}
                (keep (fn [{:keys [declaration spec owned?]}]
                        ;; Platform-lazy dependency modules may omit their
                        ;; state helpers exactly as they can omit dispatch
                        ;; symbols. Owned state is always required.
                        (try
                          [(:version-key spec)
                           (assoc (bind-state linker lookup declaration spec)
                                  :owned? owned?)]
                          (catch Throwable error
                            (when owned? (throw error))))))
                state-entries)
          active-call-handle
          (bind-active-call-counter linker lookup dispatch-specs)
          active-call-tracking-handle
          (bind-active-call-tracking linker lookup dispatch-specs)
          _ (when (native-host-active?)
              (set-active-call-tracking-handle!
               active-call-tracking-handle false))
          publication-epoch-setters
          (bind-publication-epoch!
           linker lookup (vals dispatch-bindings))]
        (merge compiled {:arena arena :lookup lookup :linker linker
                         :loaded-declarations declarations
                         :functions functions
                         :values values
                         :types types
                         :dispatch-bindings dispatch-bindings
                         :state-bindings state-bindings
                         :active-call-handle active-call-handle
                         :active-call-tracking-handle
                         active-call-tracking-handle
                         :publication-epoch-setters publication-epoch-setters
                         :jvm-active-calls (AtomicLong. 0)
                         :native-value-refs (AtomicLong. 0)}))
      (catch Throwable error
        (try (.close ^Arena arena) (catch Throwable _))
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
          error)))))))

(defn- invoke-dispatch-setter!
  [^MethodHandle setter address]
  (.invokeWithArguments setter
                        (ArrayList. ^java.util.Collection [(long address)]))
  nil)

(defn- native-generation
  [generation loaded]
  {:generation generation
   :arena (:arena loaded)
   :lookup (:lookup loaded)
   :linker (:linker loaded)
   :functions (:functions loaded)
   :values (:values loaded)
   :types (:types loaded)
   :dispatch-bindings (:dispatch-bindings loaded)
   :state-bindings (:state-bindings loaded)
   :active-call-handle (:active-call-handle loaded)
   :active-call-tracking-handle (:active-call-tracking-handle loaded)
   :jvm-active-calls (:jvm-active-calls loaded)
   :native-value-refs (:native-value-refs loaded)
   :hash (:hash loaded)
   :library-path (:library-path loaded)})

(defn- set-all-active-call-tracking!
  [enabled?]
  (doseq [handle
          (->> @registry
               vals
               (mapcat :native-generations)
               (keep :active-call-tracking-handle)
               distinct)]
    (set-active-call-tracking-handle! handle enabled?))
  nil)

(defn- prepare-loaded-generation
  [loaded generation]
  (-> loaded
      (update :functions
              (fn [functions]
                (into {}
                      (map (fn [[qualified-name binding]]
                             [qualified-name
                              (assoc binding
                                     :wrapper-generation generation
                                     :jvm-active-calls
                                     (:jvm-active-calls loaded)
                                     :native-value-refs
                                     (:native-value-refs loaded))]))
                      functions)))
      (update :values
              (fn [values]
                (into {}
                      (map (fn [[qualified-name binding]]
                             [qualified-name
                              (assoc binding
                                     :wrapper-generation generation
                                     :native-value-refs
                                     (:native-value-refs loaded))]))
                      values)))
      (update :types
              (fn [types]
                (into {}
                      (map (fn [[qualified-name binding]]
                             [qualified-name
                              (assoc binding
                                     :wrapper-generation generation
                                     :native-value-refs
                                     (:native-value-refs loaded))]))
                      types)))))

(defn- invoke-state-setter!
  [binding address]
  (.invokeWithArguments ^MethodHandle (:setter-handle binding)
                        (ArrayList. ^java.util.Collection
                                    [(long address)]))
  nil)

(defn- migration-function-binding
  [loaded migration]
  (let [qualified-name (symbol (:function migration))]
    (or (get (:functions loaded) qualified-name)
        (get-in @registry [(namespace qualified-name)
                           :functions qualified-name]))))

(defn- invoke-state-migration!
  [loaded migration previous-address next-address]
  (let [function-binding (migration-function-binding loaded migration)]
    (when-not function-binding
      (throw
       (ex-info "The registered Zig state migration function is not loaded"
                {:aguafria/phase :zig-state-migration
                 :migration migration
                 :available-functions
                 (->> (concat (keys (:functions loaded))
                              (mapcat (comp keys :functions val) @registry))
                      (map str) distinct sort vec)})))
    (let [declaration (:declaration function-binding)]
      (when-not (and (= :void (:return declaration))
                     (= [:usize :usize] (mapv :type (:args declaration))))
        (throw
         (ex-info
          "A Zig state migration must accept old/new usize addresses and return void"
          {:aguafria/phase :zig-state-migration
           :migration (:function migration)
           :arguments (mapv :type (:args declaration))
           :return (:return declaration)}))))
    (.invokeWithArguments ^MethodHandle (:handle function-binding)
                          (ArrayList. ^java.util.Collection
                                      [(long previous-address)
                                       (long next-address)]))
    nil))

(defn- reconcile-state
  [current loaded generation]
  (let [previous-versions (vec (:state-versions current))
        active-by-logical
        (into {} (keep (fn [version]
                         (when (:active? version)
                           [(:logical-id version) version])))
              previous-versions)]
    (reduce-kv
     (fn [{:keys [state-versions] :as result} _ binding]
       (let [{:keys [logical-id schema-fingerprint address size alignment]}
             binding
             previous (get active-by-logical logical-id)
             compatible? (or (nil? previous)
                             (= schema-fingerprint
                                (:schema-fingerprint previous)))
             migration-key [logical-id (:schema-fingerprint previous)
                            schema-fingerprint]
             migration (when (and previous (not compatible?))
                         (get @state-migrations migration-key))]
         (when (and previous compatible?
                    (not= [size alignment]
                          [(:size previous) (:alignment previous)]))
           (throw
            (ex-info
             "A defvar kept its schema fingerprint but Zig changed its native layout"
             {:aguafria/phase :zig-state-layout
              :logical-id logical-id
              :schema-fingerprint schema-fingerprint
              :previous {:size (:size previous)
                         :alignment (:alignment previous)}
              :next {:size size :alignment alignment}})))
         (cond
           (nil? previous)
           (do
             (invoke-state-setter! binding address)
             (update result :state-versions conj
                     (assoc (select-keys binding
                                         [:version-key :logical-id
                                          :schema-fingerprint :address :size
                                          :alignment])
                            :generation generation
                            :active? true
                            :status :initialized)))

           compatible?
           (do
             (invoke-state-setter! binding (:address previous))
             (update result :state-versions
                     (fn [versions]
                       (mapv #(if (and (= (:logical-id %) logical-id)
                                       (:active? %))
                                (assoc % :active? true
                                       :status (if (= :migrated (:status %))
                                                 :migrated
                                                 :preserved)
                                       :last-bound-generation generation)
                                %)
                             versions))))

           migration
           (do
             (invoke-state-migration! loaded migration (:address previous)
                                      address)
             (invoke-state-setter! binding address)
             (-> result
                 (update :state-versions
                         (fn [versions]
                           (mapv #(if (= (:logical-id %) logical-id)
                                    (assoc % :active? false :status :retained)
                                    %)
                                 versions)))
                 (update :state-versions conj
                         (assoc (select-keys binding
                                             [:version-key :logical-id
                                              :schema-fingerprint :address
                                              :size :alignment])
                                :generation generation
                                :active? true
                                :status :migrated
                                :migrated-from-schema
                                (:schema-fingerprint previous)
                                :migration-function (:function migration)
                                :migrated-at-ms (System/currentTimeMillis)))))

           :else
           (throw
            (ex-info
             "A defvar layout changed and requires an explicit Zig migration"
             {:aguafria/phase :zig-state-migration-required
              :logical-id logical-id
              :from-schema (:schema-fingerprint previous)
              :to-schema schema-fingerprint
              :previous-generation (:generation previous)
              :requested-generation generation
              :hint (str "Define an exported `(usize old, usize new) void` "
                         "Aguafria function, then call `az/migrate-state!` "
                         "with the state Var and migration Var.")})))))
     {:state-versions previous-versions}
     (into {} (filter (comp :owned? val)) (:state-bindings loaded)))))

(defn- type-producing-declaration?
  [{:keys [kind schema-fingerprint]}]
  (and schema-fingerprint (not= :var kind)))

(defn- declaration-reference-view
  [{:keys [kind module name zig-name logical-id abi-fingerprint
           schema-fingerprint implementation-fingerprint value]
    :as declaration}]
  (cond-> {:kind :declaration
           :declaration-kind kind
           :module module
           :zig-name (emit/identifier (or zig-name name))
           :symbol (symbol module (str name))}
    logical-id (assoc :logical-id logical-id)
    abi-fingerprint (assoc :abi-fingerprint abi-fingerprint)
    implementation-fingerprint
    (assoc :implementation-fingerprint implementation-fingerprint)
    schema-fingerprint (assoc :schema-fingerprint schema-fingerprint)
    (= :var kind)
    (assoc :state-accessor (:accessor (declaration-state-spec declaration)))
    ;; `:type-reference?` means the Var itself is usable with Zig's
    ;; `Type{...}` constructor syntax. A function returning `type` is invoked
    ;; normally and must not be rewritten to `function{...}`.
    (or (= :struct kind)
        (and (= :const kind)
             (seq? value)
             (symbol? (first value))
             (= "container" (name (first value)))))
    (assoc :type-reference? true)))

(defn- publish-clojure-declaration-metadata!
  "Keep ordinary Clojure Vars aligned with declarations refreshed internally
  by automatic dependency propagation. A later REPL form must resolve the
  current ABI/schema reference exactly as if the affected Var had itself been
  reevaluated."
  [declarations]
  (doseq [{:keys [module name] :as declaration} declarations
          :let [namespace (find-ns (symbol module))]
          :when namespace
          :let [v (ns-resolve namespace (symbol (str name)))]
          :when (var? v)]
    (alter-meta! v
                 (fn [metadata]
                   (cond-> (assoc metadata :aguafria/declaration declaration)
                     (contains? #{:fn :fn-proto :const :var :struct :import
                                  :raw :field}
                                (:kind declaration))
                     (assoc :aguafria/zig-reference
                            (declaration-reference-view declaration)))))
  nil))

(defn refresh-declaration-var!
  "Synchronize one just-defined Clojure Var with the descriptor currently
  registered for it. Macros call this after `def`, because synchronous native
  preparation can enrich type/callable dependency identities before the Var
  itself exists."
  [declaration]
  (let [{:keys [module declaration-key] :as declaration}
        (declaration-info declaration)
        current (or (get-in @registry [module :definitions declaration-key])
                    declaration)]
    (publish-clojure-declaration-metadata! [current])
    current))

(defn- reconcile-type-versions
  [current loaded generation]
  (let [previous-versions (vec (:type-versions current))]
    {:type-versions
     (reduce
      (fn [versions declaration]
        (let [{:keys [logical-id schema-fingerprint]} declaration
              previous (some #(when (and (= logical-id (:logical-id %))
                                          (:active? %))
                                 %)
                             versions)
              compatible? (or (nil? previous)
                              (= schema-fingerprint
                                 (:schema-fingerprint previous)))
              next-version
              {:logical-id logical-id
               :schema-fingerprint schema-fingerprint
               :generation generation
               :kind (:kind declaration)
               :name (str (:name declaration))
               :active? true
               :status (cond
                         (nil? previous) :initialized
                         (= :breaking (:status previous)) :breaking
                         compatible? :compatible
                         :else :breaking)
               :previous-schema
               (if (and compatible? (= :breaking (:status previous)))
                 (:previous-schema previous)
                 (:schema-fingerprint previous))}]
          (if (and previous compatible?)
            (mapv #(if (and (= logical-id (:logical-id %))
                            (:active? %))
                     next-version
                     %)
                  versions)
            (conj
             (mapv #(if (= logical-id (:logical-id %))
                      (assoc % :active? false :status :retained)
                      %)
                   versions)
             next-version))))
      previous-versions
      (filter type-producing-declaration?
              (:loaded-declarations loaded)))}))

(defn- reconcile-dispatch
  [current loaded generation]
  (let [previous-state (or (:dispatch-state current) {})
        previous-types
        (into {}
              (comp (filter type-producing-declaration?)
                    (map (juxt :logical-id identity)))
              (:loaded-declarations current))
        compatible-type-logic-change?
        (boolean
         (some
          (fn [declaration]
            (when-let [previous (get previous-types (:logical-id declaration))]
              (and (= (:schema-fingerprint previous)
                      (:schema-fingerprint declaration))
                   (not= (:implementation-fingerprint previous)
                         (:implementation-fingerprint declaration)))))
          (filter type-producing-declaration?
                  (:loaded-declarations loaded))))
        candidates (:dispatch-bindings loaded)
        owned-candidates (into {}
                               (filter (comp :owned? val))
                               candidates)
        dispatch-state
        (reduce-kv
         (fn [state version-key candidate]
           (let [active (get state version-key)
                 implementation-address (:implementation-address candidate)
                 candidate-state
                 (-> candidate
                     (select-keys [:version-key :logical-id
                                   :abi-fingerprint
                                   :implementation-fingerprint
                                   :implementation-address])
                     (assoc :implementation-generation generation))]
             (assoc state version-key
                    (cond
                      ;; A setter-only generation preserves Zig laziness. Keep
                      ;; the prior active implementation, or publish an
                      ;; inspectable placeholder until the first real edit
                      ;; demands an implementation address.
                      (nil? implementation-address)
                      (or active candidate-state)

                      (and active
                           (empty? (:state-bindings loaded))
                           (not compatible-type-logic-change?)
                           (= (:implementation-fingerprint active)
                              (:implementation-fingerprint candidate)))
                      active

                      :else candidate-state))))
         previous-state
         owned-candidates)
        generations (conj (vec (:native-generations current))
                          (native-generation generation loaded))]
    (merge {:dispatch-state dispatch-state
            :native-generations generations}
           (reconcile-state current loaded generation)
           (reconcile-type-versions current loaded generation))))

(defn- reconcile-dispatch!
  [current loaded generation]
  (reconcile-dispatch current loaded generation))

(defn- set-dispatch-publication-epoch!
  [value]
  ;; The shared segment is naturally aligned. Full fences make the epoch
  ;; transition visible to the native acquire loads surrounding every
  ;; dispatch-cell read.
  (VarHandle/fullFence)
  (.set ^MemorySegment dispatch-publication-epoch ValueLayout/JAVA_LONG
        0 (long value))
  (VarHandle/fullFence)
  value)

(defn- begin-dispatch-publication!
  []
  (let [current (.get ^MemorySegment dispatch-publication-epoch
                      ValueLayout/JAVA_LONG 0)
        publishing (if (even? current) (inc current) (+ current 2))]
    (set-dispatch-publication-epoch! publishing)))

(defn- end-dispatch-publication!
  [publishing]
  (set-dispatch-publication-epoch! (inc publishing)))

(defn- refresh-project-dispatch-unguarded!
  []
  (let [active-versions
        (into {}
              (mapcat (comp seq :dispatch-state val))
              @registry)
        active-states
        (into {}
              (keep (fn [version]
                      (when (:active? version)
                        [(:version-key version) version])))
              (mapcat :state-versions (vals @registry)))]
    (doseq [[_ module-state] @registry
            {:keys [dispatch-bindings]} (:native-generations module-state)
            [version-key {:keys [setter-handle]}] dispatch-bindings
            :let [active (get active-versions version-key)]
            :when (some? (:implementation-address active))]
      (invoke-dispatch-setter! setter-handle
                               (:implementation-address active)))
    (doseq [[_ module-state] @registry
            {:keys [state-bindings]} (:native-generations module-state)
            [version-key binding] state-bindings
            :let [active (get active-states version-key)]
            :when active]
      (invoke-state-setter! binding (:address active)))
    ;; A development host is another loaded copy of the logical module graph.
    ;; Keep its dependency cells in the same atomic publication epoch as the
    ;; JVM-owned generations so an already-running program observes each Var
    ;; replacement without being restarted.
    (doseq [{:keys [loaded status dispatch-frozen?]} (vals @live-hosts)
            :when (and loaded
                       (not dispatch-frozen?)
                       (contains? #{:starting :running} status))
            [version-key {:keys [setter-handle]}] (:dispatch-bindings loaded)
            :let [active (get active-versions version-key)]
            :when (some? (:implementation-address active))]
      (invoke-dispatch-setter! setter-handle
                               (:implementation-address active)))
    (doseq [{:keys [loaded status share-state?]} (vals @live-hosts)
            :when (and loaded share-state?
                       (contains? #{:starting :running} status))
            [version-key binding] (:state-bindings loaded)
            :let [active (get active-states version-key)]
            :when active]
      (invoke-state-setter! binding (:address active))))
  nil)

(defn- refresh-project-dispatch!
  []
  (let [publishing (begin-dispatch-publication!)]
    (try
      (refresh-project-dispatch-unguarded!)
      (finally
        (end-dispatch-publication! publishing)))))

(defn- native-active-call-count
  [{:keys [active-call-handle]}]
  (if active-call-handle
    (long (.invokeWithArguments ^MethodHandle active-call-handle (ArrayList.)))
    0))

(defn- jvm-active-call-count
  [{:keys [jvm-active-calls]}]
  (if jvm-active-calls
    (.get ^AtomicLong jvm-active-calls)
    0))

(defn- native-value-ref-count
  [{:keys [native-value-refs]}]
  (if native-value-refs
    (.get ^AtomicLong native-value-refs)
    0))

(defn- retired-generation-view
  [generation native-active jvm-active]
  {:generation (:generation generation)
   :hash (:hash generation)
   :library-path (:library-path generation)
   :native-active-call-count native-active
   :jvm-active-call-count jvm-active
   :retired-at-ms (System/currentTimeMillis)})

(defn- retire-quiescent-generations!
  [module-state]
  (let [host-active? (native-host-active?)
        current-generation (:published-generation module-state)
        referenced-generations
        (into (into (into (into (into (into #{current-generation}
                                (keep :generation)
                                (remove #(= :superseded (:status %))
                                        (:state-versions module-state)))
                          (keep :generation)
                          (remove #(= :superseded (:status %))
                                  (:type-versions module-state)))
                    ;; `:functions` contains JVM downcall handles into the
                    ;; wrapper generation. During a partial breaking
                    ;; publication an unchanged callable can intentionally
                    ;; remain here even when its native dispatch cell points
                    ;; at an older implementation generation. Its arena is
                    ;; therefore independently live until a later complete
                    ;; publication replaces the binding.
                    (keep :wrapper-generation)
                    (vals (:functions module-state)))
              (keep :wrapper-generation)
              (vals (:values module-state)))
              (keep :wrapper-generation)
              (vals (:types module-state)))
              (keep :implementation-generation)
              (vals (:dispatch-state module-state)))
        [retained newly-retired]
        (reduce
         (fn [[retained retired] generation]
           (let [native-active (native-active-call-count generation)
                 jvm-active (jvm-active-call-count generation)
                 native-value-refs (native-value-ref-count generation)
                 retire? (and (not (contains? referenced-generations
                                               (:generation generation)))
                              (not host-active?)
                              (zero? native-active)
                              (zero? jvm-active)
                              (zero? native-value-refs))]
             (if retire?
               (do
                 (.close ^Arena (:arena generation))
                 [retained
                  (conj retired
                        (retired-generation-view generation
                                                 native-active jvm-active))])
               [(conj retained generation) retired])))
         [[] []]
         (:native-generations module-state))
        limit (max 1 (long (:build-history-limit @config)))
        retired (->> (concat (:retired-generations module-state) newly-retired)
                     (take-last limit)
                     vec)
        retired-generation-ids (set (map :generation newly-retired))
        retire-version
        (fn [version]
          (if (contains? retired-generation-ids (:generation version))
            (-> version
                (assoc :active? false :status :retired)
                (dissoc :address))
            version))]
    (let [module (:module module-state)
          pending? (some #(not (contains? referenced-generations
                                          (:generation %)))
                         retained)]
      (when module
        (swap! retirement-pending-modules
               (if pending? conj disj) module))
      (assoc module-state
             :native-generations retained
             :retired-generations retired
             :state-versions (mapv retire-version (:state-versions module-state))
             :type-versions (mapv retire-version (:type-versions module-state))))))

(defn- retire-module-quiescent-generations!
  [module]
  (when-let [module-state (get @registry module)]
    (swap! registry assoc module
           (retire-quiescent-generations! module-state)))
  nil)

(defn- retire-pending-generations!
  []
  (doseq [module @retirement-pending-modules]
    (retire-module-quiescent-generations! module))
  nil)

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

(defn- next-source-order
  [definitions]
  (inc (reduce max -1 (keep :source-order (vals definitions)))))

(defn- stable-source-order
  [definitions declaration]
  (if (some? (:source-order declaration))
    declaration
    (if-let [order (get-in definitions [(:declaration-key declaration)
                                        :source-order])]
      (assoc declaration :source-order order)
      (assoc declaration :source-order (next-source-order definitions)))))

(defn- ordered-batch
  [declarations]
  (mapv (fn [index declaration]
          (cond-> declaration
            (nil? (:source-order declaration)) (assoc :source-order index)))
        (range)
        declarations))

(defn- nested-storage-wrapper-spec
  [type prefix]
  (when (vector? type)
    (let [operator (some-> type first name)]
      (case operator
        "optional"
        (let [child-type (second type)]
          {:storage-kind :optional
           :type type
           :child-type child-type
           :optional-set (str prefix "_optional_set")
           :optional-present (str prefix "_optional_present")
           :optional-payload-address (str prefix "_optional_payload_address")
           :optional-payload-size (str prefix "_optional_payload_size")
           :child (nested-storage-wrapper-spec child-type
                                               (str prefix "_child"))})

        ("slice" "slice-const")
        (let [element-type (second type)]
          {:storage-kind :slice
           :type type
           :element-type element-type
           :slice-set (str prefix "_slice_set")
           :slice-pointer (str prefix "_slice_pointer")
           :slice-length (str prefix "_slice_length")
           :slice-element-size (str prefix "_slice_element_size")
           :slice-element-align (str prefix "_slice_element_align")
           :child (nested-storage-wrapper-spec element-type
                                               (str prefix "_element"))})

        "error-union"
        (let [payload-type (last type)]
          {:storage-kind :error-union
           :type type
           :payload-type payload-type
           :error-set (when (= 3 (count type)) (second type))
           :error-set-ok (str prefix "_error_set_ok")
           :error-set-error (str prefix "_error_set_error")
           :error-present (str prefix "_error_present")
           :error-code (str prefix "_error_code")
           :error-name-pointer (str prefix "_error_name_pointer")
           :error-name-length (str prefix "_error_name_length")
           :error-payload-address (str prefix "_error_payload_address")
           :error-payload-size (str prefix "_error_payload_size")
           :child (nested-storage-wrapper-spec payload-type
                                               (str prefix "_payload"))})

        ("array" "array-sentinel" "vector")
        (let [element-type (if (= "array-sentinel" operator)
                             (nth type 3)
                             (nth type 2))
              child (nested-storage-wrapper-spec element-type
                                                  (str prefix "_element"))]
          (when child
            {:storage-kind :collection
             :type type
             :element-type element-type
             :child child}))

        nil))))

(defn- nested-child-storage-wrapper-spec
  [type prefix]
  (when (vector? type)
    (let [operator (some-> type first name)]
      (case operator
        "optional" (nested-storage-wrapper-spec (second type)
                                                (str prefix "_child"))
        ("slice" "slice-const")
        (nested-storage-wrapper-spec (second type) (str prefix "_element"))
        "error-union"
        (nested-storage-wrapper-spec (last type) (str prefix "_payload"))
        ("array" "array-sentinel" "vector")
        (nested-storage-wrapper-spec type prefix)
        nil))))

(defn- jvm-callable-wrapper-specs
  [module declarations]
  (let [requested (get-in @registry [module :jvm-callable-declaration-keys] #{})]
    (into {}
          (keep
           (fn [declaration]
             (when (contains? requested (:declaration-key declaration))
               (let [qualified-name (:qualified-name declaration)
                     token (subs (sha256 (pr-str [qualified-name
                                                  (:abi-fingerprint declaration)]))
                                 0 24)
                     argument-modes
                     (mapv (fn [{:keys [type]}]
                             (if (contains? scalar-layouts (scalar-key type))
                               :scalar
                               :native))
                           (:args declaration))
                     return-mode
                     (cond
                       (= :void (:return declaration)) :void
                       (contains? scalar-layouts
                                  (scalar-key (:return declaration))) :scalar
                       :else :native)
                     mode (if (and (every? #{:scalar} argument-modes)
                                   (not= :native return-mode))
                            :direct
                            :indirect)
                     prefix (str "__aguafria_jvm_call_" token)
                     native-argument-specs
                     (mapv (fn [index argument-mode argument]
                             (when (= :native argument-mode)
                               (let [type (:type argument)
                                     optional?
                                     (and (vector? type)
                                          (= "optional"
                                             (some-> type first name)))
                                     slice?
                                     (and (vector? type)
                                          (contains? #{"slice" "slice-const"}
                                                     (some-> type first name)))
                                     error-union?
                                     (and (vector? type)
                                          (= "error-union"
                                             (some-> type first name)))
                                     helper-prefix
                                     (str prefix "_argument_" index)]
                                 {:index index
                                  :optional? optional?
                                  :optional-child-type
                                  (when optional? (second type))
                                  :slice? slice?
                                  :slice-element-type
                                  (when slice? (second type))
                                  :size-getter (str helper-prefix "_size")
                                  :align-getter (str helper-prefix "_align")
                                  :optional-set
                                  (str helper-prefix "_optional_set")
                                  :optional-present
                                  (str helper-prefix "_optional_present")
                                  :optional-payload-address
                                  (str helper-prefix "_optional_payload_address")
                                  :optional-payload-size
                                  (str helper-prefix "_optional_payload_size")
                                  :slice-set (str helper-prefix "_slice_set")
                                  :slice-pointer (str helper-prefix "_slice_pointer")
                                  :slice-length (str helper-prefix "_slice_length")
                                  :slice-element-size
                                  (str helper-prefix "_slice_element_size")
                                  :slice-element-align
                                  (str helper-prefix "_slice_element_align")
                                  :error-union? error-union?
                                  :error-payload-type
                                  (when error-union? (last type))
                                  :error-set
                                  (when (and error-union? (= 3 (count type)))
                                    (second type))
                                  :error-set-ok (str helper-prefix "_error_set_ok")
                                  :error-set-error
                                  (str helper-prefix "_error_set_error")
                                  :error-present
                                  (str helper-prefix "_error_present")
                                  :error-code (str helper-prefix "_error_code")
                                  :error-name-pointer
                                  (str helper-prefix "_error_name_pointer")
                                  :error-name-length
                                  (str helper-prefix "_error_name_length")
                                  :error-payload-address
                                  (str helper-prefix "_error_payload_address")
                                  :error-payload-size
                                  (str helper-prefix "_error_payload_size")
                                  :nested-storage-spec
                                  (nested-child-storage-wrapper-spec
                                   type helper-prefix)})))
                           (range) argument-modes (:args declaration))
                     result-type (:return declaration)
                     result-optional?
                     (and (= :native return-mode)
                          (vector? result-type)
                          (= "optional" (some-> result-type first name)))
                     result-slice?
                     (and (= :native return-mode)
                          (vector? result-type)
                          (contains? #{"slice" "slice-const"}
                                     (some-> result-type first name)))
                     result-error-union?
                     (and (= :native return-mode)
                          (vector? result-type)
                          (= "error-union" (some-> result-type first name)))
                     result-helper-prefix (str prefix "_result")]
                 [qualified-name
                  {:declaration declaration
                   :mode mode
                   :argument-modes argument-modes
                   :native-argument-specs native-argument-specs
                   :return-mode return-mode
                   :symbol prefix
                   :result-size-getter (str prefix "_result_size")
                   :result-align-getter (str prefix "_result_align")
                   :result-optional? result-optional?
                   :result-optional-child-type
                   (when result-optional? (second result-type))
                   :result-optional-set
                   (str result-helper-prefix "_optional_set")
                   :result-optional-present
                   (str result-helper-prefix "_optional_present")
                   :result-optional-payload-address
                   (str result-helper-prefix "_optional_payload_address")
                   :result-optional-payload-size
                   (str result-helper-prefix "_optional_payload_size")
                   :result-slice? result-slice?
                   :result-slice-element-type
                   (when result-slice? (second result-type))
                   :result-slice-set (str result-helper-prefix "_slice_set")
                   :result-slice-pointer
                   (str result-helper-prefix "_slice_pointer")
                   :result-slice-length
                   (str result-helper-prefix "_slice_length")
                   :result-slice-element-size
                   (str result-helper-prefix "_slice_element_size")
                   :result-slice-element-align
                   (str result-helper-prefix "_slice_element_align")
                   :result-error-union? result-error-union?
                   :result-error-payload-type
                   (when result-error-union? (last result-type))
                   :result-error-set
                   (when (and result-error-union? (= 3 (count result-type)))
                     (second result-type))
                   :result-error-set-ok
                   (str result-helper-prefix "_error_set_ok")
                   :result-error-set-error
                   (str result-helper-prefix "_error_set_error")
                   :result-error-present
                   (str result-helper-prefix "_error_present")
                   :result-error-code
                   (str result-helper-prefix "_error_code")
                   :result-error-name-pointer
                   (str result-helper-prefix "_error_name_pointer")
                   :result-error-name-length
                   (str result-helper-prefix "_error_name_length")
                   :result-error-payload-address
                   (str result-helper-prefix "_error_payload_address")
                   :result-error-payload-size
                   (str result-helper-prefix "_error_payload_size")
                   :result-nested-storage-spec
                   (nested-child-storage-wrapper-spec
                    result-type result-helper-prefix)}]))))
          declarations)))

(defn- jvm-value-wrapper-specs
  [module declarations]
  (let [requested (get-in @registry [module :jvm-value-declaration-keys] #{})]
    (into {}
          (keep
           (fn [{:keys [kind type] :as declaration}]
             (when (and (contains? #{:const :var} kind)
                        (contains? requested (:declaration-key declaration)))
               (let [qualified-name
                     (symbol (:module declaration) (str (:name declaration)))
                     token (subs (sha256 (pr-str [qualified-name type
                                                  (:value declaration)]))
                                 0 24)
                     prefix (str "__aguafria_jvm_value_" token)
                     optional?
                     (and (vector? type)
                          (= "optional" (some-> type first name)))
                     slice?
                     (and (vector? type)
                          (contains? #{"slice" "slice-const"}
                                     (some-> type first name)))
                     error-union?
                     (and (vector? type)
                          (= "error-union" (some-> type first name)))]
                 [qualified-name
                  {:declaration declaration
                   :mode (if (contains? scalar-layouts (scalar-key type))
                           :scalar
                           :native)
                   :getter (str prefix "_get")
                   :address-getter (str prefix "_address")
                   :size-getter (str prefix "_size")
                   :align-getter (str prefix "_align")
                   :optional? optional?
                   :optional-child-type (when optional? (second type))
                   :optional-set (str prefix "_optional_set")
                   :optional-present (str prefix "_optional_present")
                   :optional-payload-address
                   (str prefix "_optional_payload_address")
                   :optional-payload-size
                   (str prefix "_optional_payload_size")
                   :slice? slice?
                   :slice-element-type (when slice? (second type))
                   :slice-set (str prefix "_slice_set")
                   :slice-pointer (str prefix "_slice_pointer")
                   :slice-length (str prefix "_slice_length")
                   :slice-element-size (str prefix "_slice_element_size")
                   :slice-element-align (str prefix "_slice_element_align")
                   :error-union? error-union?
                   :error-payload-type (when error-union? (last type))
                   :error-set
                   (when (and error-union? (= 3 (count type))) (second type))
                   :error-set-ok (str prefix "_error_set_ok")
                   :error-set-error (str prefix "_error_set_error")
                   :error-present (str prefix "_error_present")
                   :error-code (str prefix "_error_code")
                   :error-name-pointer (str prefix "_error_name_pointer")
                   :error-name-length (str prefix "_error_name_length")
                   :error-payload-address
                   (str prefix "_error_payload_address")
                   :error-payload-size (str prefix "_error_payload_size")
                   :nested-storage-spec
                   (nested-child-storage-wrapper-spec type prefix)}]))))
          declarations)))

(defn- jvm-type-wrapper-specs
  [module declarations]
  (let [requested (get-in @registry [module :jvm-type-declaration-keys] #{})]
    (into {}
          (keep
           (fn [{:keys [kind] :as declaration}]
             (let [container-description
                   (container-type-description declaration)
                   container-kind
                   (get-in container-description [:options :kind])
                   type-declaration?
                   (or (= :struct kind)
                       (contains? #{:struct :enum :union :opaque}
                                  container-kind))]
               (when (and type-declaration?
                          (contains? requested (:declaration-key declaration)))
                 (let [qualified-name
                     (symbol (:module declaration) (str (:name declaration)))
                     token (subs (sha256 (pr-str [qualified-name
                                                  (:schema-fingerprint
                                                   declaration)]))
                                 0 24)
                     prefix (str "__aguafria_jvm_type_" token)
                     tagged-union?
                     (and (= :union container-kind)
                          (or (true? (get-in container-description
                                             [:options :enum?]))
                              (some? (get-in container-description
                                             [:options :argument]))))
                     fields (if (= :struct kind)
                              (:fields declaration)
                              (->> (:members container-description)
                                   (filter #(= :field (:kind %)))
                                   vec))
                     enum-members
                     (->> (:members container-description)
                          (filter #(= :enum-field (:kind %)))
                          vec)]
                 [qualified-name
                  {:declaration declaration
                   :container-description container-description
                   :container-kind container-kind
                   :tagged-union? tagged-union?
                   :size-getter (str prefix "_size")
                   :align-getter (str prefix "_align")
                   :field-specs
                   (mapv (fn [index field]
                           (let [optional?
                                 (and (vector? (:type field))
                                      (= "optional"
                                         (some-> (:type field) first name)))
                                 slice?
                                 (and (vector? (:type field))
                                      (contains? #{"slice" "slice-const"}
                                                 (some-> (:type field)
                                                         first name)))
                                 error-union?
                                 (and (vector? (:type field))
                                      (= "error-union"
                                         (some-> (:type field) first name)))]
                             {:index index
                              :field field
                              :union? (= :union container-kind)
                              :optional? optional?
                              :optional-child-type
                              (when optional? (second (:type field)))
                              :slice? slice?
                              :slice-element-type
                              (when slice? (second (:type field)))
                              :offset-getter
                              (str prefix "_field_" index "_offset")
                              :size-getter
                              (str prefix "_field_" index "_size")
                              :union-init
                              (str prefix "_field_" index "_init")
                              :union-active
                              (str prefix "_field_" index "_active")
                              :union-payload-address
                              (str prefix "_field_" index "_payload_address")
                              :optional-set
                              (str prefix "_field_" index "_optional_set")
                              :optional-present
                              (str prefix "_field_" index "_optional_present")
                              :optional-payload-address
                              (str prefix "_field_" index
                                   "_optional_payload_address")
                              :optional-payload-size
                              (str prefix "_field_" index
                                   "_optional_payload_size")
                              :slice-set
                              (str prefix "_field_" index "_slice_set")
                              :slice-pointer
                              (str prefix "_field_" index "_slice_pointer")
                              :slice-length
                              (str prefix "_field_" index "_slice_length")
                              :slice-element-size
                              (str prefix "_field_" index "_slice_element_size")
                              :slice-element-align
                              (str prefix "_field_" index "_slice_element_align")
                              :error-union? error-union?
                              :error-payload-type
                              (when error-union? (last (:type field)))
                              :error-set
                              (when (and error-union?
                                         (= 3 (count (:type field))))
                                (second (:type field)))
                              :error-set-ok
                              (str prefix "_field_" index "_error_set_ok")
                              :error-set-error
                              (str prefix "_field_" index "_error_set_error")
                              :error-present
                              (str prefix "_field_" index "_error_present")
                              :error-code
                              (str prefix "_field_" index "_error_code")
                              :error-name-pointer
                              (str prefix "_field_" index "_error_name_pointer")
                              :error-name-length
                              (str prefix "_field_" index "_error_name_length")
                              :error-payload-address
                              (str prefix "_field_" index "_error_payload_address")
                              :error-payload-size
                              (str prefix "_field_" index "_error_payload_size")
                              :nested-storage-spec
                              (nested-child-storage-wrapper-spec
                               (:type field)
                               (str prefix "_field_" index))}))
                         (range) fields)
                   :enum-member-specs
                   (mapv (fn [index member]
                           {:index index
                            :member member
                            :address-getter
                            (str prefix "_enum_" index "_address")})
                         (range) enum-members)}])))))
          declarations)))

(defn- emit-jvm-optional-storage-wrapper
  [child-type {:keys [optional-set optional-present
                      optional-payload-address optional-payload-size]}]
  (let [child-type (emit/emit-type child-type)]
    (str
     "export fn " optional-set
     "(storage_address: usize, present: bool, value_address: usize) callconv(.c) void {\n"
     "    const storage: *?" child-type " = @ptrFromInt(storage_address);\n"
     "    storage.* = if (present) (@as(*const " child-type
     ", @ptrFromInt(value_address))).* else null;\n"
     "}\n"
     "export fn " optional-present
     "(storage_address: usize) callconv(.c) bool {\n"
     "    const storage: *const ?" child-type
     " = @ptrFromInt(storage_address);\n"
     "    return storage.* != null;\n"
     "}\n"
     "export fn " optional-payload-address
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const storage: *const ?" child-type
     " = @ptrFromInt(storage_address);\n"
     "    if (storage.*) |*payload| return @intFromPtr(payload);\n"
     "    return 0;\n"
     "}\n"
     "export fn " optional-payload-size
     "() callconv(.c) usize {\n"
     "    return @sizeOf(" child-type ");\n"
     "}\n")))

(defn- emit-jvm-slice-storage-wrapper
  [slice-type element-type
   {:keys [slice-set slice-pointer slice-length
           slice-element-size slice-element-align]}]
  (let [slice-type (emit/emit-type slice-type)
        element-type (emit/emit-type element-type)]
    (str
     "export fn " slice-set
     "(storage_address: usize, items_address: usize, length: usize) callconv(.c) void {\n"
     "    const storage: *" slice-type " = @ptrFromInt(storage_address);\n"
     "    const items: [*]" (when (str/includes? slice-type "const ") "const ")
     element-type " = @ptrFromInt(items_address);\n"
     "    storage.* = items[0..length];\n"
     "}\n"
     "export fn " slice-pointer
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const storage: *const " slice-type
     " = @ptrFromInt(storage_address);\n"
     "    return @intFromPtr(storage.*.ptr);\n"
     "}\n"
     "export fn " slice-length
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const storage: *const " slice-type
     " = @ptrFromInt(storage_address);\n"
     "    return storage.*.len;\n"
     "}\n"
     "export fn " slice-element-size "() callconv(.c) usize {\n"
     "    return @sizeOf(" element-type ");\n"
     "}\n"
     "export fn " slice-element-align "() callconv(.c) usize {\n"
     "    return @alignOf(" element-type ");\n"
     "}\n")))

(defn- emit-jvm-error-union-storage-wrapper
  [error-union-type payload-type
   {:keys [error-set-ok error-set-error error-present error-code
           error-name-pointer error-name-length error-payload-address
           error-payload-size]}]
  (let [void-payload? (= :void payload-type)
        error-union-type (emit/emit-type error-union-type)
        payload-type (emit/emit-type payload-type)]
    (str
     "export fn " error-set-ok
     "(storage_address: usize, payload_address: usize) callconv(.c) void {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *ErrorUnion = @ptrFromInt(storage_address);\n"
     (if void-payload?
       "    _ = payload_address;\n    storage.* = {};\n"
       (str "    storage.* = (@as(*const " payload-type
            ", @ptrFromInt(payload_address))).*;\n"))
     "}\n"
     "export fn " error-set-error
     "(storage_address: usize, code: usize) callconv(.c) void {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *ErrorUnion = @ptrFromInt(storage_address);\n"
     "    storage.* = @errorCast(@errorFromInt(@as(u16, @intCast(code))));\n"
     "}\n"
     "export fn " error-present
     "(storage_address: usize) callconv(.c) bool {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *const ErrorUnion = @ptrFromInt(storage_address);\n"
     "    if (storage.*) |_| return false else |_| return true;\n"
     "}\n"
     "export fn " error-code
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *const ErrorUnion = @ptrFromInt(storage_address);\n"
     "    if (storage.*) |_| return 0 else |err| return @intFromError(err);\n"
     "}\n"
     "export fn " error-name-pointer
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *const ErrorUnion = @ptrFromInt(storage_address);\n"
     "    if (storage.*) |_| return 0 else |err| return @intFromPtr(@errorName(err).ptr);\n"
     "}\n"
     "export fn " error-name-length
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *const ErrorUnion = @ptrFromInt(storage_address);\n"
     "    if (storage.*) |_| return 0 else |err| return @errorName(err).len;\n"
     "}\n"
     "export fn " error-payload-address
     "(storage_address: usize) callconv(.c) usize {\n"
     "    const ErrorUnion = " error-union-type ";\n"
     "    const storage: *ErrorUnion = @ptrFromInt(storage_address);\n"
     (if void-payload?
       "    _ = storage;\n    return 0;\n"
       "    if (storage.*) |*payload| return @intFromPtr(payload) else |_| return 0;\n")
     "}\n"
     "export fn " error-payload-size "() callconv(.c) usize {\n"
     "    return " (if void-payload? "0" (str "@sizeOf(" payload-type ")")) ";\n"
     "}\n")))

(defn- emit-jvm-nested-storage-wrappers
  [{:keys [storage-kind type child-type element-type payload-type child]
    :as spec}]
  (when spec
    (str
     (case storage-kind
       :optional (emit-jvm-optional-storage-wrapper child-type spec)
       :slice (emit-jvm-slice-storage-wrapper type element-type spec)
       :error-union (emit-jvm-error-union-storage-wrapper type payload-type spec)
       :collection ""
       "")
     (emit-jvm-nested-storage-wrappers child))))

(defn- emit-jvm-callable-wrapper
  [[_ {:keys [declaration symbol mode argument-modes return-mode
              native-argument-specs result-size-getter
              result-align-getter result-optional?
              result-optional-child-type
              result-optional-set result-optional-present
              result-optional-payload-address
              result-optional-payload-size result-slice?
              result-slice-element-type result-error-union?
              result-error-payload-type result-nested-storage-spec]
       :as entry}]]
  (let [argument-names (mapv #(str "argument_" %) (range (count (:args declaration))))
        bridge-arguments
        (mapv (fn [argument-name argument argument-mode]
                (if (= :scalar argument-mode)
                  (str argument-name ": " (emit/emit-type (:type argument)))
                  (str argument-name "_address: usize")))
              argument-names (:args declaration) argument-modes)
        bridge-arguments (cond-> bridge-arguments
                           (= :native return-mode)
                           (conj "result_address: usize"))
        call-arguments
        (mapv (fn [argument-name argument argument-mode]
                (if (= :scalar argument-mode)
                  argument-name
                  (str "(@as(*const " (emit/emit-type (:type argument))
                       ", @ptrFromInt(" argument-name "_address))).*")))
              argument-names (:args declaration) argument-modes)
        call (str (emit/identifier (or (:zig-name declaration)
                                       (:name declaration)))
                  "(" (str/join ", " call-arguments) ")")]
    (if (= :direct mode)
      (str "export fn " symbol "(" (str/join ", " bridge-arguments)
           ") callconv(.c) " (emit/emit-type (:return declaration)) " {\n"
           "    " (when-not (= :void return-mode) "return ") call ";\n"
           "}\n")
      (str "export fn " symbol "(" (str/join ", " bridge-arguments)
           ") callconv(.c) "
           (if (= :scalar return-mode)
             (emit/emit-type (:return declaration))
             "void")
           " {\n"
           "    "
           (case return-mode
             :void (str call ";")
             :scalar (str "return " call ";")
             :native
             (str "(@as(*" (emit/emit-type (:return declaration))
                  ", @ptrFromInt(result_address))).* = " call ";"))
           "\n}\n"
           (when (= :native return-mode)
             (str "export fn " result-size-getter "() callconv(.c) usize {\n"
                  "    return @sizeOf(" (emit/emit-type (:return declaration)) ");\n"
                  "}\n"
                  "export fn " result-align-getter "() callconv(.c) usize {\n"
                  "    return @alignOf(" (emit/emit-type (:return declaration)) ");\n"
                  "}\n"
                  (when result-optional?
                    (emit-jvm-optional-storage-wrapper
                     result-optional-child-type
                     {:optional-set result-optional-set
                      :optional-present result-optional-present
                      :optional-payload-address
                      result-optional-payload-address
                      :optional-payload-size result-optional-payload-size}))
                  (when result-slice?
                    (emit-jvm-slice-storage-wrapper
                     (:return declaration) result-slice-element-type
                     {:slice-set (:result-slice-set entry)
                      :slice-pointer (:result-slice-pointer entry)
                      :slice-length (:result-slice-length entry)
                      :slice-element-size (:result-slice-element-size entry)
                      :slice-element-align
                      (:result-slice-element-align entry)}))
                  (when result-error-union?
                    (emit-jvm-error-union-storage-wrapper
                     (:return declaration) result-error-payload-type
                     {:error-set-ok (:result-error-set-ok entry)
                      :error-set-error (:result-error-set-error entry)
                      :error-present (:result-error-present entry)
                      :error-code (:result-error-code entry)
                      :error-name-pointer (:result-error-name-pointer entry)
                      :error-name-length (:result-error-name-length entry)
                      :error-payload-address
                      (:result-error-payload-address entry)
                      :error-payload-size
                      (:result-error-payload-size entry)}))
                  (emit-jvm-nested-storage-wrappers
                   result-nested-storage-spec)))
           (apply str
                  (keep
                   (fn [{:keys [index size-getter align-getter optional?
                                optional-child-type slice?
                                slice-element-type error-union?
                                error-payload-type nested-storage-spec]
                         :as argument-spec}]
                     (when argument-spec
                       (let [argument-type
                             (emit/emit-type
                              (:type (nth (:args declaration) index)))]
                         (str "export fn " size-getter
                            "() callconv(.c) usize {\n"
                            "    return @sizeOf(" argument-type ");\n"
                            "}\n"
                            "export fn " align-getter
                            "() callconv(.c) usize {\n"
                            "    return @alignOf(" argument-type ");\n"
                            "}\n"
                            (when optional?
                              (emit-jvm-optional-storage-wrapper
                               optional-child-type argument-spec))
                            (when slice?
                              (emit-jvm-slice-storage-wrapper
                               (:type (nth (:args declaration) index))
                               slice-element-type argument-spec))
                            (when error-union?
                              (emit-jvm-error-union-storage-wrapper
                               (:type (nth (:args declaration) index))
                               error-payload-type argument-spec))
                            (emit-jvm-nested-storage-wrappers
                             nested-storage-spec)))))
                   native-argument-specs))))))

(defn- emit-jvm-callable-wrappers
  [specs]
  (when (seq specs)
    (str "\n// Development-only JVM invocation trampolines.\n"
         (->> specs
              (sort-by (comp str key))
              (map emit-jvm-callable-wrapper)
              (str/join "\n")))))

(defn- emit-jvm-value-wrapper
  [[_ {:keys [declaration mode getter address-getter size-getter
              align-getter optional? optional-child-type slice?
              slice-element-type error-union? error-payload-type
              nested-storage-spec] :as spec}]]
  (let [constant-name (emit/identifier (or (:zig-name declaration)
                                           (:name declaration)))]
    (if (= :scalar mode)
      (str "export fn " getter "() callconv(.c) "
           (emit/emit-type (:type declaration)) " {\n"
           "    return " constant-name ";\n"
           "}\n")
      (str "export fn " address-getter "() callconv(.c) usize {\n"
           "    return @intFromPtr(&" constant-name ");\n"
           "}\n"
           "export fn " size-getter "() callconv(.c) usize {\n"
           "    return @sizeOf(@TypeOf(" constant-name "));\n"
           "}\n"
           "export fn " align-getter "() callconv(.c) usize {\n"
           "    return @alignOf(@TypeOf(" constant-name "));\n"
           "}\n"
           (when optional?
             (emit-jvm-optional-storage-wrapper optional-child-type
                                                spec))
           (when slice?
             (emit-jvm-slice-storage-wrapper (:type declaration)
                                             slice-element-type spec))
           (when error-union?
             (emit-jvm-error-union-storage-wrapper
              (:type declaration) error-payload-type spec))
           (emit-jvm-nested-storage-wrappers nested-storage-spec)))))

(defn- emit-jvm-value-wrappers
  [specs]
  (when (seq specs)
    (str "\n// Development-only JVM value accessors.\n"
         (->> specs
              (sort-by (comp str key))
              (map emit-jvm-value-wrapper)
              (str/join "\n")))))

(defn- emit-jvm-type-wrapper
  [[_ {:keys [declaration size-getter align-getter field-specs
              enum-member-specs tagged-union?]}]]
  (let [type-name (emit/identifier (or (:zig-name declaration)
                                       (:name declaration)))]
    (str "export fn " size-getter "() callconv(.c) usize {\n"
         "    return @sizeOf(" type-name ");\n"
         "}\n"
         "export fn " align-getter "() callconv(.c) usize {\n"
         "    return @alignOf(" type-name ");\n"
         "}\n"
         (apply str
                (map
                 (fn [{:keys [field offset-getter size-getter union?
                              union-init union-active
                              union-payload-address optional?
                              optional-child-type optional-set
                              optional-present optional-payload-address
                              optional-payload-size slice?
                              slice-element-type error-union?
                              error-payload-type nested-storage-spec]
                       :as field-spec}]
                   (let [field-name (emit/identifier (:name field))
                         field-type (emit/emit-type (:type field))
                         optional-child-type
                         (when optional? (emit/emit-type optional-child-type))
                         void-field? (= :void (:type field))]
                     (str "export fn " offset-getter
                          "() callconv(.c) usize {\n"
                          "    return "
                          (if union?
                            "0"
                            (str "@offsetOf(" type-name ", \""
                                 field-name "\")"))
                          ";\n"
                          "}\n"
                          "export fn " size-getter
                          "() callconv(.c) usize {\n"
                          "    return @sizeOf(@FieldType(" type-name ", \""
                          field-name "\"));\n"
                          "}\n"
                          (when union?
                            (str
                             "export fn " union-init
                             "(destination_address: usize, value_address: usize) callconv(.c) void {\n"
                             "    const destination: *" type-name
                             " = @ptrFromInt(destination_address);\n"
                             (when void-field? "    _ = value_address;\n")
                             "    destination.* = .{ ." field-name " = "
                             (if void-field?
                               "{}"
                               (str "(@as(*const " field-type
                                    ", @ptrFromInt(value_address))).*"))
                             " };\n"
                             "}\n"
                             (when tagged-union?
                               (str
                                "export fn " union-active
                                "(value_address: usize) callconv(.c) bool {\n"
                                "    const value: *const " type-name
                                " = @ptrFromInt(value_address);\n"
                                "    return switch (value.*) { ." field-name
                                " => true, else => false };\n"
                                "}\n"
                                "export fn " union-payload-address
                                "(value_address: usize) callconv(.c) usize {\n"
                                (if void-field?
                                  "    _ = value_address;\n    return 0;\n"
                                  (str
                                   "    const value: *" type-name
                                   " = @ptrFromInt(value_address);\n"
                                   "    return @intFromPtr(&value." field-name ");\n"))
                                "}\n"))))
                          (when optional?
                            (str
                             "export fn " optional-set
                             "(storage_address: usize, present: bool, value_address: usize) callconv(.c) void {\n"
                             "    const storage: *?" optional-child-type
                             " = @ptrFromInt(storage_address);\n"
                             "    storage.* = if (present) "
                             "(@as(*const " optional-child-type
                             ", @ptrFromInt(value_address))).* else null;\n"
                             "}\n"
                             "export fn " optional-present
                             "(storage_address: usize) callconv(.c) bool {\n"
                             "    const storage: *const ?" optional-child-type
                             " = @ptrFromInt(storage_address);\n"
                             "    return storage.* != null;\n"
                             "}\n"
                             "export fn " optional-payload-address
                             "(storage_address: usize) callconv(.c) usize {\n"
                             "    const storage: *const ?" optional-child-type
                             " = @ptrFromInt(storage_address);\n"
                             "    if (storage.*) |*payload| return @intFromPtr(payload);\n"
                             "    return 0;\n"
                             "}\n"
                             "export fn " optional-payload-size
                             "() callconv(.c) usize {\n"
                             "    return @sizeOf(" optional-child-type ");\n"
                             "}\n"))
                          (when slice?
                            (emit-jvm-slice-storage-wrapper
                             (:type field) slice-element-type field-spec))
                          (when error-union?
                            (emit-jvm-error-union-storage-wrapper
                             (:type field) error-payload-type field-spec))
                          (emit-jvm-nested-storage-wrappers
                           nested-storage-spec))))
                 field-specs))
         (apply str
                (map
                 (fn [{:keys [member address-getter index]}]
                   (let [member-name
                         (emit/identifier (or (:zig-name member)
                                              (:name member)))
                         storage-name (str "EnumStorage" index)]
                     (str "export fn " address-getter
                          "() callconv(.c) usize {\n"
                          "    const " storage-name " = struct { var value: "
                          type-name " = ." member-name "; };\n"
                          "    return @intFromPtr(&" storage-name ".value);\n"
                          "}\n")))
                 enum-member-specs)))))

(defn- emit-jvm-type-wrappers
  [specs]
  (when (seq specs)
    (str "\n// Development-only JVM type constructors.\n"
         (->> specs
              (sort-by (comp str key))
              (map emit-jvm-type-wrapper)
              (str/join "\n")))))

(defn- module-sources
  ([module declarations]
   (module-sources module declarations #{}))
  ([module declarations getter-declaration-keys]
   (let [source (emit-source! module declarations)
         reload-source-dispatch-specs
         (reloadable-dispatch-specs declarations)
         dispatch-specs
         (into {}
               (map (fn [[declaration-key spec]]
                      [declaration-key
                       (cond-> spec
                         (contains? getter-declaration-keys declaration-key)
                         (assoc :emit-getter? true))]))
               reload-source-dispatch-specs)
         state-specs (reloadable-state-specs declarations)
         jvm-callable-specs (jvm-callable-wrapper-specs module declarations)
         jvm-callable-source (emit-jvm-callable-wrappers jvm-callable-specs)
         jvm-value-specs (jvm-value-wrapper-specs module declarations)
         jvm-value-source (emit-jvm-value-wrappers jvm-value-specs)
         jvm-type-specs (jvm-type-wrapper-specs module declarations)
         jvm-type-source (emit-jvm-type-wrappers jvm-type-specs)
         reload-source
         (emit-reload-source! module declarations reload-source-dispatch-specs
                              state-specs)]
     {:source source
      :dispatch-specs dispatch-specs
      :reload-source-dispatch-specs reload-source-dispatch-specs
      :state-specs state-specs
      ;; Dependency copies always contain setters and a local implementation,
      ;; but never an exported implementation-address getter. This preserves
      ;; Zig's lazy/platform analysis when another module imports this source.
      :reload-source reload-source
      :jvm-callable-specs jvm-callable-specs
      :jvm-value-specs jvm-value-specs
      :jvm-type-specs jvm-type-specs
      ;; Only declarations whose already-published implementation changed
      ;; expose a getter in the owning generation.
      :compile-source
      (str (if (= dispatch-specs reload-source-dispatch-specs)
             reload-source
             (emit-reload-source! module declarations dispatch-specs state-specs))
           jvm-callable-source
           jvm-value-source
           jvm-type-source)})))

(defn- changed-dispatch-declaration-keys
  [module-state declarations]
  (let [previous-by-version
        (into {}
              (comp
               (filter dispatchable-declaration?)
               (map (fn [declaration]
                      [[(:logical-id declaration)
                        (:abi-fingerprint declaration)]
                       declaration])))
              (:loaded-declarations module-state))
        embedded-dependency-implementations
        (reduce
         (fn [implementations [_ module-state]]
           (reduce
            (fn [implementations generation]
              (reduce-kv
               (fn [implementations version-key binding]
                 (if (:owned? binding)
                   implementations
                   (update implementations version-key (fnil conj #{})
                           (:implementation-fingerprint binding))))
               implementations
               (:dispatch-bindings generation)))
            implementations
            (:native-generations module-state)))
         {}
         @registry)]
    (into #{}
          (keep
           (fn [declaration]
             (when (dispatchable-declaration? declaration)
               (let [version-key
                     [(:logical-id declaration)
                      (:abi-fingerprint declaration)]
                     previous
                     (get previous-by-version
                          version-key)
                     embedded-implementations
                     (get embedded-dependency-implementations version-key)]
                 (when (or
                        ;; A C-exported wrapper is analyzed and directly
                        ;; callable already. Capturing its address adds no
                        ;; extra eagerness and lets JVM calls plus imported
                        ;; callers retain the exact initial generation.
                        (:export? declaration)
                        (and previous
                             (not= (:implementation-fingerprint previous)
                                   (:implementation-fingerprint declaration)))
                        ;; A dependent module may already be callable from an
                        ;; embedded source snapshot before this declaration's
                        ;; owner publishes its first native generation. If the
                        ;; owner has since changed, it must expose a getter so
                        ;; those live dependency cells can leave their local
                        ;; zero/default implementation.
                        (some #(not= (:implementation-fingerprint declaration)
                                     %)
                              embedded-implementations))
                   (:declaration-key declaration))))))
          declarations)))

(defn- breaking-callable-change?
  [old-declaration declaration]
  (and old-declaration
       (= :fn (:kind old-declaration) (:kind declaration))
       (dispatchable-declaration? old-declaration)
       (dispatchable-declaration? declaration)
       (not= (:abi-fingerprint old-declaration)
             (:abi-fingerprint declaration))))

(defn- breaking-state-change?
  [old-declaration declaration]
  (and old-declaration
       (= :var (:kind old-declaration) (:kind declaration))
       (not= (:schema-fingerprint old-declaration)
             (:schema-fingerprint declaration))))

(defn- breaking-type-change?
  [old-declaration declaration]
  (and old-declaration
       (type-producing-declaration? old-declaration)
       (type-producing-declaration? declaration)
       (not= (:schema-fingerprint old-declaration)
             (:schema-fingerprint declaration))))

(defn- type-producing-change?
  [old-declaration declaration]
  (and (:reloadable? @config)
       old-declaration
       (type-producing-declaration? old-declaration)
       (type-producing-declaration? declaration)
       (or (not= (:schema-fingerprint old-declaration)
                 (:schema-fingerprint declaration))
           (not= (:implementation-fingerprint old-declaration)
                 (:implementation-fingerprint declaration)))))

(defn- compatible-type-producing-change?
  [old-declaration declaration]
  (and (type-producing-change? old-declaration declaration)
       (= (:schema-fingerprint old-declaration)
          (:schema-fingerprint declaration))))

(defn- concrete-caller-recompile-change?
  "A compatible function that cannot own a stable dispatch cell is copied or
  statically bound into concrete Zig callers. Republishing those dependent
  components is the safe hot-reload mechanism for generic, anonymous-signature,
  and other non-addressable functions."
  [old-declaration declaration]
  (and (:reloadable? @config)
       old-declaration
       (= :fn (:kind old-declaration) (:kind declaration))
       (not (type-producing-declaration? old-declaration))
       (= (:abi-fingerprint old-declaration)
          (:abi-fingerprint declaration))
       (not= (:implementation-fingerprint old-declaration)
             (:implementation-fingerprint declaration))
       (not (and (dispatchable-declaration? old-declaration)
                 (dispatchable-declaration? declaration)))))

(defn- compatible-dependent-propagation?
  [old-declaration declaration]
  (or (compatible-type-producing-change? old-declaration declaration)
      (concrete-caller-recompile-change? old-declaration declaration)))

(defn- refresh-live-declaration-references
  [declarations]
  (letfn [(refresh-pass [declarations]
            (let [registered-by-logical
                  (into {}
                        (comp (mapcat (comp vals :definitions val))
                              (map (juxt :logical-id identity)))
                        @registry)
                  current-by-logical
                  (merge registered-by-logical
                         (into {} (map (juxt :logical-id identity))
                               declarations))
                  current-types-by-name
                  (into {}
                        (comp
                         (filter type-producing-declaration?)
                         (mapcat
                          (fn [declaration]
                            (let [names (->> [(:name declaration)
                                             (:zig-name declaration)
                                             (declaration-zig-name declaration)]
                                             (remove nil?)
                                             (map str)
                                             distinct)]
                              (map (fn [name]
                                     [[(:module declaration) name]
                                      declaration])
                                   names)))))
                        declarations)]
              (mapv
               (fn [declaration]
                 (let [type-producing? (type-producing-declaration? declaration)
                       refreshed
                       (walk/postwalk
                        (fn [value]
                          (if (symbol? value)
                            (let [reference
                                  (:aguafria/zig-reference (meta value))
                                  current
                                  (or (and reference
                                           (get current-by-logical
                                                (:logical-id reference)))
                                      ;; Converted Zig permits forward references,
                                      ;; so the target Var may not have existed
                                      ;; when this form was macroexpanded. Resolve
                                      ;; same-module type names from the complete
                                      ;; declaration snapshot at publication time.
                                      (and type-producing?
                                           (nil? (namespace value))
                                           (get current-types-by-name
                                                [(:module declaration)
                                                 (name value)])))]
                              (cond
                                (= :var (:kind current))
                                (let [reference
                                      (assoc (or reference
                                                 {:logical-id (:logical-id current)
                                                  :kind (:kind current)
                                                  :module (:module current)
                                                  :zig-name
                                                  (declaration-zig-name current)})
                                             :schema-fingerprint
                                             (:schema-fingerprint current)
                                             :state-accessor
                                             (:accessor
                                              (declaration-state-spec current)))]
                                  (with-meta value
                                    (assoc (meta value)
                                           :aguafria/zig-reference reference)))

                                (type-producing-declaration? current)
                                (let [reference
                                      (assoc (or reference
                                                 {:logical-id (:logical-id current)
                                                  :kind (:kind current)
                                                  :module (:module current)
                                                  :zig-name
                                                  (declaration-zig-name current)})
                                             :schema-fingerprint
                                             (:schema-fingerprint current))]
                                  (with-meta value
                                    (assoc (meta value)
                                           :aguafria/zig-reference reference)))

                                (contains? #{:fn :fn-proto} (:kind current))
                                (let [reference
                                      (assoc (or reference
                                                 {:logical-id (:logical-id current)
                                                  :kind (:kind current)
                                                  :module (:module current)
                                                  :zig-name
                                                  (declaration-zig-name current)})
                                             :abi-fingerprint
                                             (:abi-fingerprint current)
                                             :implementation-fingerprint
                                             (:implementation-fingerprint current))]
                                  (with-meta value
                                    (assoc (meta value)
                                           :aguafria/zig-reference reference)))

                                :else value))
                            value))
                        declaration)
                       type-dependency-fingerprints
                       (->> (tree-seq coll? seq refreshed)
                            (keep (fn [value]
                                    (when (symbol? value)
                                      (let [reference
                                            (:aguafria/zig-reference
                                             (meta value))
                                            current
                                            (and reference
                                                 (get current-by-logical
                                                      (:logical-id reference)))]
                                        (when (type-producing-declaration?
                                               current)
                                          [(:logical-id current)
                                           (:schema-fingerprint current)
                                           (:implementation-fingerprint
                                            current)])))))
                            distinct
                            (sort-by pr-str)
                            vec)
                       callable-dependency-fingerprints
                       (->> (tree-seq coll? seq refreshed)
                            (keep (fn [value]
                                    (when (symbol? value)
                                      (let [reference
                                            (:aguafria/zig-reference
                                             (meta value))
                                            current
                                            (and reference
                                                 (get current-by-logical
                                                      (:logical-id reference)))]
                                        (when (and (contains? #{:fn :fn-proto}
                                                               (:kind current))
                                                   (not (type-producing-declaration?
                                                         current)))
                                          (cond-> [(:logical-id current)
                                                   (:abi-fingerprint current)]
                                            (not (dispatchable-declaration?
                                                  current))
                                            (conj
                                             (:implementation-fingerprint
                                              current))))))))
                            distinct
                            (sort-by pr-str)
                            vec)]
                   (-> refreshed
                       (assoc :type-dependency-fingerprints
                              type-dependency-fingerprints
                              :callable-dependency-fingerprints
                              callable-dependency-fingerprints)
                       declaration-info)))
               declarations)))]
    ;; A defvar schema can itself depend on a just-refreshed defstruct schema.
    ;; The second pass makes that transitive identity visible to its users.
    (refresh-pass (refresh-pass declarations))))

(defn- declaration-live-slice
  [declarations root-declaration]
  (let [by-logical (into {} (map (juxt :logical-id identity)) declarations)
        by-name
        (into {}
              (mapcat
               (fn [declaration]
                 (->> [(:name declaration)
                       (:zig-name declaration)
                       (declaration-zig-name declaration)]
                      (remove nil?)
                      (map #(vector (str %) declaration)))))
              declarations)]
    (loop [pending [root-declaration]
           selected {}]
      (if-let [declaration (first pending)]
        (if (contains? selected (:declaration-key declaration))
          (recur (next pending) selected)
          (let [references
                (->> (tree-seq coll? seq declaration)
                     (keep (fn [value]
                             (when (symbol? value)
                               (let [logical-id
                                     (some-> value meta
                                             :aguafria/zig-reference
                                             :logical-id)]
                                 (or (get by-logical logical-id)
                                     ;; Converted Zig permits same-file
                                     ;; forward references whose stored
                                     ;; unqualified symbols predate the
                                     ;; target Clojure Var. A declaration-name
                                     ;; lookup closes that real dependency
                                     ;; without inventing a builtin or raw
                                     ;; source fragment.
                                     (when (nil? (namespace value))
                                       (get by-name (name value))))))))
                     distinct)]
            (recur (concat (next pending) references)
                   (assoc selected (:declaration-key declaration)
                          declaration))))
        (->> (vals selected)
             (sort-by (juxt :source-order (comp str :name)))
             vec)))))

(defn- compilation-plan
  [module module-state declarations old-declaration declaration]
  (let [declarations (refresh-live-declaration-references declarations)
        ;; The old descriptor is the immutable identity actually compiled into
        ;; its published native generation. Refreshing it against today's
        ;; registry would silently rewrite a stale caller's historical ABI/type
        ;; dependencies and could make an explicit adoption look unchanged.
        declaration
        (or (some #(when (= (:declaration-key declaration)
                            (:declaration-key %))
                    %)
                  declarations)
            declaration)
        getter-declaration-keys
        (changed-dispatch-declaration-keys module-state declarations)
        primary-dependencies (development-dependency-snapshot declarations)
        primary (assoc (module-sources module declarations
                                       getter-declaration-keys)
                       :declarations declarations
                       :dependency-snapshot primary-dependencies
                       :partial-publication? false)
        fallback
        (when (or (breaking-callable-change? old-declaration declaration)
                  (breaking-state-change? old-declaration declaration)
                  (breaking-type-change? old-declaration declaration))
          (let [refreshed-declaration
                (or (some #(when (= (:declaration-key declaration)
                                    (:declaration-key %))
                            %)
                          declarations)
                    declaration)
                fallback-declarations
                (declaration-live-slice declarations refreshed-declaration)
                fallback-dependencies
                (development-dependency-snapshot fallback-declarations)]
            (assoc (module-sources module fallback-declarations
                                   getter-declaration-keys)
                   :declarations fallback-declarations
                   :dependency-snapshot fallback-dependencies
                   :partial-publication? true)))]
    {:primary primary
     :fallback fallback
     :old-declaration old-declaration
     :declaration declaration
     ;; A breaking type is a new logical generation. Compiling the complete
     ;; namespace here would silently redirect untouched dependents to it.
     ;; Publish only the edited Var and its declaration dependencies; each
     ;; caller/state owner adopts the new schema when explicitly reevaluated.
     :prefer-fallback? (breaking-type-change? old-declaration declaration)}))

(defn- complete-compilation-plan
  [module module-state declarations]
  (let [declarations (refresh-live-declaration-references declarations)]
    {:primary
     (assoc (module-sources
             module declarations
             (changed-dispatch-declaration-keys module-state declarations))
            :declarations declarations
            :dependency-snapshot (development-dependency-snapshot declarations)
            :partial-publication? false)}))

(defn- compile-plan!
  [module {:keys [primary fallback prefer-fallback?]}]
  (if prefer-fallback?
    (assoc fallback :compiled
           (assoc (compile-source! module (:compile-source fallback)
                                   (:declarations fallback)
                                   (:dependency-snapshot fallback))
                  :partial-publication? true))
    (try
      (assoc primary :compiled
             (compile-source! module (:compile-source primary)
                              (:declarations primary)
                              (:dependency-snapshot primary)))
      (catch Throwable full-error
        (if-not fallback
          (throw full-error)
          (try
            (let [compiled (compile-source! module (:compile-source fallback)
                                            (:declarations fallback)
                                            (:dependency-snapshot fallback))]
              (assoc fallback :compiled
                     (assoc compiled
                            :partial-publication? true
                            :full-compile-error (ex-message full-error))))
            (catch Throwable fallback-error
              (throw
               (ex-info (ex-message fallback-error)
                        (assoc (ex-data fallback-error)
                               :aguafria/full-module-error (ex-message full-error)
                               :aguafria/partial-publication-attempted? true)
                        fallback-error)))))))))

(defn- refresh-plan-dependency-snapshots
  [plan]
  (reduce
   (fn [plan branch]
     (if-let [compilation (get plan branch)]
       (assoc plan branch
              (assoc compilation
                     :dependency-snapshot
                     (development-dependency-snapshot
                      (:declarations compilation))))
       plan))
   plan
   [:primary :fallback]))

(declare recompile-component! recompile-dependent-components!)

(defn- compile-and-publish-async!
  [{:keys [module declaration-key generation declarations source plan completion
           propagate-dependent-change?]}]
  (mark-build-started! module generation)
  (try
    (ensure-converted-dependency-sources! module declarations)
    (let [plan (refresh-plan-dependency-snapshots plan)
          {compiled-declarations :declarations
           :keys [compiled compile-source reload-source dispatch-specs
                  reload-source-dispatch-specs partial-publication?
                  dependency-snapshot jvm-callable-specs jvm-value-specs
                  jvm-type-specs]}
          (compile-plan! module plan)
          ;; Stale snapshots are useful compiler work/history, but loading each
          ;; one would waste native-library arenas during a large REPL reload.
          loaded (when (= generation
                          (get-in @registry [module :requested-generation]))
                   (-> (load-module compiled compiled-declarations dispatch-specs
                                    (dependency-dispatch-entries
                                     dependency-snapshot)
                                    (dependency-state-entries
                                     dependency-snapshot)
                                    jvm-callable-specs
                                    jvm-value-specs
                                    jvm-type-specs)
                       (prepare-loaded-generation generation)))
          published? (atom false)]
      (locking compile-lock
        (let [current (get @registry module)]
          (when (and loaded (= generation (:requested-generation current)))
            (let [dispatch (reconcile-dispatch! current loaded generation)
                  compiled-definitions
                  (into {}
                        (map (juxt :declaration-key identity))
                        compiled-declarations)
                  functions (if partial-publication?
                              (merge (:functions current) (:functions loaded))
                              (:functions loaded))
                  published-dispatch-specs dispatch-specs]
              (reset! published? true)
              (swap! registry assoc module
                     (merge current loaded dispatch
                            {:generation generation
                             :published-generation generation
                             ;; Keep the completion visible until dependent
                             ;; type propagation has also finished. Otherwise
                             ;; an `await!` started in this small window can
                             ;; return while an affected SCC is still being
                             ;; compiled (or miss its migration error).
                             :pending completion
                             :scheduled nil
                             :source source
                             :reload-source reload-source
                             :dispatch-specs published-dispatch-specs
                             :reload-source-dispatch-specs
                             reload-source-dispatch-specs
                             :definitions
                             (if partial-publication?
                               (merge (:definitions current)
                                      compiled-definitions)
                               compiled-definitions)
                             :functions functions
                             :partial-publication? partial-publication?
                             :full-compile-error (:full-compile-error compiled)
                             :source-only? false
                             :last-error nil
                             :failed-generation nil
                             :last-dependent-publication-failure nil
                             :last-dependent-publication-error nil}))
              (refresh-project-dispatch!)
              (publish-clojure-declaration-metadata!
               compiled-declarations)
              (retire-module-quiescent-generations! module)))))
      (when (and loaded (not @published?))
        (.close ^Arena (:arena loaded)))
      (let [propagation
            (when (and @published? propagate-dependent-change?)
              (try
                {:affected (recompile-dependent-components! module completion)}
                (catch Throwable error
                  {:error error})))
            propagation-error (:error propagation)
            affected (:affected propagation)
            status (if @published? :finished :stale)]
        (mark-build-finished! module generation status compiled)
        (if propagation-error
          (do
            (swap! registry update module assoc
                   :last-dependent-publication-failure
                   {:phase (:aguafria/phase (ex-data propagation-error))
                    :generation generation
                    :logical-id (:logical-id (ex-data propagation-error))
                    :failed-at-ms (System/currentTimeMillis)
                    :error (ex-message propagation-error)}
                   :last-dependent-publication-error propagation-error)
            (swap! registry update module
                   (fn [current]
                     (if (and (= generation (:requested-generation current))
                              (identical? completion (:pending current)))
                       (assoc current :pending nil :scheduled nil)
                       current)))
            (deliver completion {:status :error
                                 :error propagation-error
                                 :published? true}))
          (do
            (swap! registry update module
                   (fn [current]
                     (if (and (= generation (:requested-generation current))
                              (identical? completion (:pending current)))
                       (assoc current :pending nil :scheduled nil)
                       current)))
            (deliver completion
                     {:status :success
                      :result (cond->
                               (registration-result module declaration-key generation
                                                    compiled @published?)
                                affected (assoc :affected affected))})))))
    (catch Throwable error
      (locking compile-lock
        (swap! registry update module
               (fn [current]
                 (if (= generation (:requested-generation current))
                   (assoc current
                          :pending nil
                          :scheduled nil
                          :last-error error
                          :failed-generation generation)
                   current))))
      (mark-build-failed! module generation error)
      (deliver completion {:status :error :error error}))))

(defn- register-async!
  [{:keys [module declaration-key] :as declaration}]
  (let [job
        (locking compile-lock
          (let [old-module (get @registry module)
                old-definitions (or (:definitions old-module) {})
                old-declaration (get old-definitions declaration-key)
                declaration (stable-source-order old-definitions declaration)
                definitions (assoc old-definitions
                                   declaration-key declaration)
                declarations (vec (vals definitions))
                plan (compilation-plan module old-module declarations
                                       old-declaration declaration)
                plan-old-declaration (:old-declaration plan)
                plan-declaration (:declaration plan)
                _ (when (and (native-host-active?)
                             (breaking-type-change? plan-old-declaration
                                                    plan-declaration))
                    (freeze-active-host-dispatch! plan-declaration))
                {:keys [source compile-source reload-source dispatch-specs
                        reload-source-dispatch-specs]} (:primary plan)
                generation (inc (or (:requested-generation old-module)
                                    (:generation old-module) 0))
                completion (promise)
                job {:module module
                     :declaration-key declaration-key
                     :generation generation
                     :definitions definitions
                     :declarations declarations
                     :source source
                     :compile-source compile-source
                     :dispatch-specs dispatch-specs
                     :plan plan
                     :propagate-dependent-change?
                     (and *propagate-dependent-changes?*
                          (compatible-dependent-propagation?
                           plan-old-declaration plan-declaration))
                     :completion completion}]
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :source source
                           :reload-source reload-source
                           :dispatch-specs dispatch-specs
                           :reload-source-dispatch-specs
                           reload-source-dispatch-specs
                           :requested-generation generation
                           :pending completion
                           :last-error nil
                           :last-dependent-publication-error nil
                           :failed-generation nil}))
            job))]
    (record-build! job true)
    (future (compile-and-publish-async! job))
    {:module module
     :declaration-key declaration-key
     :generation (:generation job)
     :async? true
     :pending? true}))

(defn- register-converted-async!
  "Register generated project source immediately, then compile only the last
  snapshot after a short quiet period. Loading an entire generated namespace
  therefore never compiles half of a file, while reevaluating one declaration
  still schedules a hot generation automatically."
  [{:keys [module declaration-key] :as declaration}]
  ;; A host may start from a complete immutable source graph while individual
  ;; dependency modules are still finishing their first background builds.
  ;; Do not let a type edit supersede that baseline: the running host already
  ;; contains the old layout, so it must become an inspectable retained
  ;; generation before the new schema is queued.
  (let [{:keys [definitions pending]} (get @registry module)
        old-declaration (get definitions declaration-key)
        hosted-type-change?
        (and (native-host-active?)
             (type-producing-change? old-declaration declaration))]
    (when (and pending hosted-type-change?)
      @pending)
    (when hosted-type-change?
      (locking compile-lock
        (let [active-version
              (some #(when (and (= (:logical-id old-declaration)
                                  (:logical-id %))
                                (:active? %))
                       %)
                    (get-in @registry [module :type-versions]))]
          (when-not active-version
            ;; The old layout already exists in each active host's immutable
            ;; native graph. Represent that real ownership directly instead of
            ;; eagerly compiling the whole dependency SCC merely to obtain a
            ;; duplicate library generation (which would also defeat Zig's
            ;; normal lazy analysis of unreachable declarations).
            (let [host-ids (->> @live-hosts
                                vals
                                (filter #(contains? #{:starting :running}
                                                    (:status %)))
                                (mapv :id))
                  baseline
                  {:logical-id (:logical-id old-declaration)
                   :schema-fingerprint
                   (:schema-fingerprint old-declaration)
                   :generation nil
                   :kind (:kind old-declaration)
                   :name (str (:name old-declaration))
                   :active? true
                   :host-only? true
                   :host-ids host-ids
                   :status :hosted
                   :previous-schema nil}]
              (swap! registry update-in [module :type-versions]
                     (fnil conj []) baseline)))))))
  (let [[job superseded]
        (locking compile-lock
          (let [old-module (get @registry module)
                old-definitions (or (:definitions old-module) {})
                old-declaration (get old-definitions declaration-key)
                declaration (stable-source-order old-definitions declaration)
                definitions (assoc old-definitions declaration-key declaration)
                declarations (vec (vals definitions))
                plan (compilation-plan module old-module declarations
                                       old-declaration declaration)
                plan-old-declaration (:old-declaration plan)
                plan-declaration (:declaration plan)
                _ (when (and (native-host-active?)
                             (breaking-type-change? plan-old-declaration
                                                    plan-declaration))
                    (freeze-active-host-dispatch! plan-declaration))
                {:keys [source compile-source reload-source dispatch-specs
                        reload-source-dispatch-specs]} (:primary plan)
                generation (inc (or (:requested-generation old-module)
                                    (:generation old-module) 0))
                expected-declaration-count
                (project/expected-declaration-count module)
                ready? (or (nil? expected-declaration-count)
                           (>= (count definitions)
                               expected-declaration-count))
                completion (promise)
                job {:module module
                     :declaration-key declaration-key
                     :generation generation
                     :definitions definitions
                     :declarations declarations
                     :source source
                     :compile-source compile-source
                     :dispatch-specs dispatch-specs
                     :plan plan
                     :propagate-dependent-change?
                     (and *propagate-dependent-changes?*
                          (compatible-dependent-propagation?
                           plan-old-declaration plan-declaration))
                     :ready? ready?
                     :completion completion}]
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :declarations declarations
                           :source source
                           :reload-source reload-source
                           :dispatch-specs dispatch-specs
                           :reload-source-dispatch-specs
                           reload-source-dispatch-specs
                           :source-only? true
                           :requested-generation generation
                           :scheduled nil
                           :pending completion
                           :last-error nil
                           :last-dependent-publication-error nil
                           :failed-generation nil}))
            [job {:scheduled (:scheduled old-module)
                  :completion (:pending old-module)
                  :generation (:requested-generation old-module)}]))]
    (record-build! job true)
    (let [scheduled (:scheduled superseded)
          cancelled? (and scheduled
                          (.cancel ^ScheduledFuture scheduled false))]
      (when (and (:completion superseded)
                 (or (nil? scheduled) cancelled?))
        (when (:generation superseded)
          (mark-build-finished! module (:generation superseded) :stale {}))
        (deliver (:completion superseded)
                 {:status :success :stale? true})))
    (if-not (:ready? job)
      (do
        ;; The catalog tells us exactly when a generated namespace is
        ;; complete. Intermediate forms remain immediately inspectable but
        ;; are never sent to Zig as invalid half-modules.
        (mark-build-finished! module (:generation job) :stale {})
        (deliver (:completion job)
                 {:status :success :source-only? true})
        (swap! registry update module
               (fn [current]
                 (if (and (= (:generation job)
                             (:requested-generation current))
                          (identical? (:completion job) (:pending current)))
                   (assoc current :pending nil :scheduled nil)
                   current))))
      (let [task
            (.schedule
             converted-compiler
             ^Runnable
             (reify Runnable
               (run [_]
                 (if (= (:generation job)
                        (get-in @registry [module :requested-generation]))
                   (compile-and-publish-async! job)
                   (do
                     (mark-build-finished! module (:generation job) :stale {})
                     (deliver (:completion job)
                              {:status :success :stale? true})))))
             (long (:converted-compile-debounce-ms @config))
             TimeUnit/MILLISECONDS)]
        ;; A zero-delay job may already have finished. Never put its completed
        ;; scheduler handle back into a published module state.
        (swap! registry update module
               (fn [current]
                 (if (and (= (:generation job) (:requested-generation current))
                          (identical? (:completion job) (:pending current)))
                   (assoc current :scheduled task)
                   current)))))
    {:module module
     :declaration-key declaration-key
     :generation (:generation job)
     :async? true
     :converted? true
     :pending? (:ready? job)}))

(defn- register-sync!
  [{:keys [module declaration-key] :as declaration}]
  (locking compile-lock
    (let [old-module (get @registry module)
          old-definitions (or (:definitions old-module) {})
          old-declaration (get old-definitions declaration-key)
          declaration (stable-source-order old-definitions declaration)
          definitions (assoc old-definitions
                             declaration-key declaration)
          declarations (vec (vals definitions))
          plan (compilation-plan module old-module declarations
                                 old-declaration declaration)
          plan-old-declaration (:old-declaration plan)
          plan-declaration (:declaration plan)
          _ (when (and (native-host-active?)
                       (breaking-type-change? plan-old-declaration
                                              plan-declaration))
              (freeze-active-host-dispatch! plan-declaration))
          propagate-dependent-change?
          (and *propagate-dependent-changes?*
               (compatible-dependent-propagation?
                plan-old-declaration plan-declaration))
          {source :source} (:primary plan)
          generation (inc (or (:requested-generation old-module)
                              (:generation old-module) 0))
          job {:module module :generation generation :declarations declarations}
          prepared (atom nil)
          published? (atom false)]
      (record-build! job false)
      (mark-build-started! module generation)
      (try
        (let [{compiled-declarations :declarations
               :keys [compiled compile-source reload-source dispatch-specs
                      reload-source-dispatch-specs
                      partial-publication? dependency-snapshot
                      jvm-callable-specs jvm-value-specs jvm-type-specs]}
              (compile-plan! module plan)
              loaded (-> (load-module compiled compiled-declarations dispatch-specs
                                      (dependency-dispatch-entries
                                       dependency-snapshot)
                                      (dependency-state-entries
                                       dependency-snapshot)
                                      jvm-callable-specs
                                      jvm-value-specs
                                      jvm-type-specs)
                         (prepare-loaded-generation generation))
              _ (reset! prepared loaded)
              dispatch (reconcile-dispatch! old-module loaded generation)
              compiled-definitions
              (into {}
                    (map (juxt :declaration-key identity))
                    compiled-declarations)
              functions (if partial-publication?
                          (merge (:functions old-module) (:functions loaded))
                          (:functions loaded))
              published-dispatch-specs dispatch-specs
              new-module
              (merge old-module loaded dispatch
                     {:module module
                      :generation generation
                      :published-generation generation
                      :requested-generation generation
                      :source source
                      :reload-source reload-source
                      :dispatch-specs published-dispatch-specs
                      :reload-source-dispatch-specs
                      reload-source-dispatch-specs
                      :functions functions
                      :partial-publication? partial-publication?
                      :full-compile-error (:full-compile-error compiled)
                      :pending nil
                      :last-error nil
                      :last-dependent-publication-error nil
                      :failed-generation nil
                      :last-dependent-publication-failure nil
                      :definitions
                      (if partial-publication?
                        (merge (:definitions old-module)
                               compiled-definitions)
                        compiled-definitions)})]
          (swap! registry assoc module new-module)
          (reset! published? true)
          (refresh-project-dispatch!)
          (publish-clojure-declaration-metadata! compiled-declarations)
          (retire-module-quiescent-generations! module)
          (mark-build-finished! module generation :finished compiled)
          (cond-> (registration-result module declaration-key generation
                                       compiled true)
            propagate-dependent-change?
            (assoc :affected (recompile-dependent-components! module nil))))
        (catch Throwable error
          (when (and @prepared (not @published?))
            (try (.close ^Arena (:arena @prepared)) (catch Throwable _)))
          (when (and (not @published?)
                     (= :zig-state-migration-required
                        (:aguafria/phase (ex-data error))))
            ;; Keep the requested source/descriptor inspectable after a sync
            ;; migration stop, while the prior native generation remains the
            ;; published callable program.
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :source source
                           :requested-generation generation
                           :pending nil
                           :last-error error
                           :failed-generation generation})))
          (when @published?
            ;; The edited module is already a valid published generation. A
            ;; transitive dependent may still require an explicit state
            ;; migration; retain that failure for inspection without rolling
            ;; the root module back to its previous source/type identity.
            (swap! registry update module assoc
                   :last-dependent-publication-failure
                   {:phase (:aguafria/phase (ex-data error))
                    :generation generation
                    :logical-id (:logical-id (ex-data error))
                    :failed-at-ms (System/currentTimeMillis)
                    :error (ex-message error)}
                   :last-dependent-publication-error error))
          (when-not @published?
            (mark-build-failed! module generation error))
          (throw error))))))

(declare await! dependency-topology)

(defn- dependency-component-members
  [module module-states]
  (let [topology (dependency-topology module-states)
        component-id (get-in topology [:component-by-module module])
        component (some #(when (= component-id (:id %)) %)
                        (:components topology))]
    (->> (or (:modules component) [module])
         (filter #(contains? module-states %))
         sort
         vec)))

(defn- component-job
  [module module-state declarations]
  (let [declarations (vec declarations)
        definitions (into {}
                          (map (juxt :declaration-key identity))
                          declarations)
        generation (inc (or (:requested-generation module-state)
                            (:generation module-state) 0))
        sources
        (module-sources
         module declarations
         (changed-dispatch-declaration-keys module-state declarations))]
    (when-not (seq declarations)
      (throw (ex-info "Cannot compile an empty dependency-component module"
                      {:aguafria/phase :zig-component-prepare
                       :module module})))
    {:module module
     :generation generation
     :declaration-key (:declaration-key (first declarations))
     :definitions definitions
     :declarations declarations
     :sources sources
     ;; Dependency snapshots for every SCC member are created only after all
     ;; of these staged states exist. This prevents A@new from compiling
     ;; against B@old while an ostensibly atomic cyclic publication is being
     ;; prepared.
     :staged-state
     (merge module-state
            {:definitions definitions
             :source (:source sources)
             :reload-source (:reload-source sources)
             :dispatch-specs (:dispatch-specs sources)
             :reload-source-dispatch-specs
             (:reload-source-dispatch-specs sources)})}))

(defn- finalize-component-job
  [job staged-module-states]
  (let [sources (:sources job)]
    (assoc job :plan
           {:primary
            (assoc sources
                   :declarations (:declarations job)
                   :dependency-snapshot
                   (development-dependency-snapshot
                    (:declarations job) staged-module-states)
                   :partial-publication? false)})))

(defn- prepared-component-module-state
  [old-module {:keys [module generation definitions] :as job}
   {:keys [compiled compile-source reload-source dispatch-specs
           reload-source-dispatch-specs partial-publication?]
    :as compilation}
   loaded publication]
  (let [dispatch (reconcile-dispatch old-module loaded generation)
        functions (if partial-publication?
                    (merge (:functions old-module) (:functions loaded))
                    (:functions loaded))]
    (merge old-module loaded dispatch
           {:module module
            :generation generation
            :published-generation generation
            :requested-generation generation
            :source (get-in job [:plan :primary :source])
            :reload-source reload-source
            :dispatch-specs dispatch-specs
            :reload-source-dispatch-specs reload-source-dispatch-specs
            :functions functions
            :partial-publication? partial-publication?
            :full-compile-error (:full-compile-error compiled)
            :pending nil
            :scheduled nil
            :source-only? false
            :last-error nil
            :failed-generation nil
            :definitions definitions
            :last-component-publication publication
            :last-component-publication-failure nil})))

(defn- unwrap-component-compile-error
  [error]
  (if (instance? ExecutionException error)
    (or (.getCause ^ExecutionException error) error)
    error))

(defn- close-prepared-component!
  [prepared]
  (doseq [{:keys [loaded]} prepared]
    (when-let [arena (:arena loaded)]
      (try
        (.close ^Arena arena)
        (catch Throwable _))))
  nil)

(defn- compile-component-sync!
  ([requested-module]
   (compile-component-sync! requested-module nil))
  ([requested-module ignored-pending]
  ;; Resolve pending work before taking the component-wide publication lock.
  ;; A later request cannot enter while the lock is held.
  (doseq [module (dependency-component-members requested-module @registry)]
    (when-let [pending (get-in @registry [module :pending])]
      ;; Explicit component recompilation is also the recovery path after a
      ;; failed generation. Wait for in-flight work, but do not rethrow its
      ;; recorded error before attempting the new immutable snapshot. During
      ;; automatic type propagation, the cyclic root component may include
      ;; the declaration whose publication is performing this recompilation;
      ;; never wait on that one promise from inside itself.
      (when-not (identical? pending ignored-pending)
        @pending)))
  (locking compile-lock
    (let [module-states @registry
          members (dependency-component-members requested-module module-states)
          _ (when-not (seq members)
              (throw (ex-info "Cannot find an Aguafria dependency component"
                              {:module requested-module
                               :known-modules (sort (keys module-states))})))
          _ (doseq [module members]
              (let [declarations (vec (vals (get-in module-states
                                                     [module :definitions])))]
                (ensure-converted-dependency-sources! module declarations)))
          ;; Converted dependency loading above can populate the graph. Capture
          ;; one final immutable registry/component snapshot after it finishes.
          module-states @registry
          members (dependency-component-members requested-module module-states)
          component-declarations
          (->> members
               (mapcat #(vals (get-in module-states [% :definitions])))
               vec
               refresh-live-declaration-references
               (group-by :module))
          staged-jobs
          (mapv #(component-job % (get module-states %)
                                (get component-declarations %))
                members)
          staged-module-states
          (reduce (fn [states {:keys [module staged-state]}]
                    (assoc states module staged-state))
                  module-states
                  staged-jobs)
          jobs (mapv #(finalize-component-job % staged-module-states)
                     staged-jobs)
          publication {:id (swap! component-publication-sequence inc)
                       :modules members
                       :requested-at-ms (System/currentTimeMillis)}
          prepared (atom [])
          published? (atom false)]
      (doseq [job jobs]
        (record-build! job false)
        (mark-build-started! (:module job) (:generation job)))
      (try
        ;; Every member compiles from plans captured after the cycle-safe load.
        ;; Compiles are independent and Zig's cache remains responsible for
        ;; sharing unchanged work.
        (let [compile-futures
              (mapv (fn [{:keys [module plan]}]
                      (future
                        ;; `plan` already points at the coherent staged SCC
                        ;; graph assembled above. Refreshing from the live
                        ;; registry here would reintroduce old peer sources.
                        (compile-plan! module plan)))
                    jobs)
              compile-results
              (mapv (fn [future]
                      (try
                        {:compilation @future}
                        (catch Throwable error
                          {:error (unwrap-component-compile-error error)})))
                    compile-futures)
              compile-error (some :error compile-results)]
          (when compile-error (throw compile-error))
          (doseq [[job {:keys [compilation]}] (map vector jobs compile-results)]
            (let [{compiled-declarations :declarations
                   :keys [compiled dispatch-specs dependency-snapshot
                          jvm-callable-specs jvm-value-specs jvm-type-specs]}
                  compilation
                  loaded
                  (-> (load-module compiled compiled-declarations dispatch-specs
                                   (dependency-dispatch-entries
                                    dependency-snapshot)
                                   (dependency-state-entries
                                    dependency-snapshot)
                                   jvm-callable-specs
                                   jvm-value-specs
                                   jvm-type-specs)
                      (prepare-loaded-generation (:generation job)))]
              (swap! prepared conj {:job job
                                    :compilation compilation
                                    :loaded loaded})))
          (let [old-component-states (select-keys @registry members)
                published-at-ms (System/currentTimeMillis)
                duration-ms (- published-at-ms (:requested-at-ms publication))
                publication (assoc publication
                                   :published-at-ms published-at-ms
                                   :duration-ms duration-ms
                                   ;; Component members prepare in parallel;
                                   ;; the component wall time is therefore the
                                   ;; publication's observed critical path.
                                   :critical-path-ms duration-ms)
                new-component-states
                (into {}
                      (map (fn [{:keys [job compilation loaded]}]
                             (let [module (:module job)]
                               [module
                                (prepared-component-module-state
                                 (get old-component-states module)
                                 job compilation loaded publication)])))
                      @prepared)]
            ;; The registry replacement is one atom transition. Native dispatch
            ;; setters are updated behind one odd publication epoch, so native
            ;; wrappers cannot enter a half-published component.
            (swap! registry merge new-component-states)
            (let [publishing (begin-dispatch-publication!)]
              (try
                (refresh-project-dispatch-unguarded!)
                (catch Throwable publication-error
                  ;; Keep the epoch odd while restoring the complete component
                  ;; and all old targets; no native wrapper can cross it.
                  (swap! registry merge old-component-states)
                  (try
                    (refresh-project-dispatch-unguarded!)
                    (catch Throwable rollback-error
                      (.addSuppressed publication-error rollback-error)))
                  (throw
                   (ex-info "Atomic dependency-component publication failed"
                            {:aguafria/phase :zig-component-publication
                             :component (:id publication)
                             :modules members}
                            publication-error)))
                (finally
                  (end-dispatch-publication! publishing))))
            (reset! published? true)
            (doseq [module members]
              (publish-clojure-declaration-metadata!
               (vals (get-in new-component-states [module :definitions]))))
            (doseq [module members]
              (retire-module-quiescent-generations! module))
            (doseq [{:keys [job compilation]} @prepared]
              (mark-build-finished! (:module job) (:generation job)
                                    :finished (:compiled compilation)))
            {:status :finished
             :component (:id publication)
             :modules members
             :published-at-ms published-at-ms
             :duration-ms (:duration-ms publication)
             :critical-path-ms (:critical-path-ms publication)
             :generations
             (into (sorted-map)
                   (map (juxt :module :generation))
                   jobs)}))
        (catch Throwable error
          (when-not @published?
            (close-prepared-component! @prepared)
            (doseq [{:keys [module generation definitions plan]} jobs]
              (mark-build-failed! module generation error)
              (swap! registry update module
                     (fn [current]
                       (assoc current
                              :definitions definitions
                              :source (get-in plan [:primary :source])
                              :requested-generation generation
                              :pending nil
                              :scheduled nil
                              :last-error error
                              :failed-generation generation
                              :last-component-publication-failure
                              {:component (:id publication)
                               :modules members
                               :failed-at-ms (System/currentTimeMillis)
                               :error (ex-message error)})))))
          (throw error)))))))

(defn recompile-component!
  "Compile and publish every registered member of `module`'s dependency SCC.

  All members are compiled and loaded before one registry transition updates
  the component. A preparation or publication failure retains every previously
  published module generation and records the failure in `stats`."
  [module]
  (compile-component-sync! (str module)))

(defn- recompile-component-chain!
  [module include-root? ignored-pending]
  (let [module (str module)
        topology (dependency-topology @registry)
        component-by-module (:component-by-module topology)
        components (into {} (map (juxt :id identity)) (:components topology))
        root-component-id (get component-by-module module)
        root-component (get components root-component-id)
        dependent-component-ids
        (fn [component-id]
          (->> (:modules (get components component-id))
               (mapcat #(get-in topology [:reverse-graph %]))
               (keep component-by-module)
               (remove #{component-id})
               distinct
               sort))]
    (when-not (contains? component-by-module module)
      (throw (ex-info "Cannot find an Aguafria module to recompile"
                      {:module module
                       :known-modules (sort (keys @registry))})))
    ;; A type change inside a cycle can alter monomorphizations in every SCC
    ;; peer, so the root component must be republished as a unit. An acyclic
    ;; one-module root was already published by register-declaration! and can
    ;; start directly at its dependents.
    (loop [pending (if (or include-root? (:cyclic? root-component))
                     [root-component-id]
                     (dependent-component-ids root-component-id))
           seen #{}
           publications []]
      (if-let [component-id (first pending)]
        (if (contains? seen component-id)
          (recur (next pending) seen publications)
          (let [members (:modules (get components component-id))
                publication (compile-component-sync! (first members)
                                                       ignored-pending)]
            (recur (concat (next pending)
                           (dependent-component-ids component-id))
                   (conj seen component-id)
                   (conj publications publication))))
        {:status :finished
         :root module
         :root-component-recompiled?
         (boolean (some #(= (set (:modules %))
                            (set (:modules root-component)))
                        publications))
         :component-count (count publications)
         :module-count (reduce + 0 (map (comp count :modules) publications))
         :publications publications}))))

(defn- recompile-dependent-components!
  [module ignored-pending]
  (recompile-component-chain! module false ignored-pending))

(defn recompile-affected!
  "Recompile a module SCC and every transitively dependent SCC in dependency
  order. Each SCC prepares/publishes atomically; unchanged functions continue
  to use Zig's content-addressed cache and compatible dispatch identities."
  [module]
  (recompile-component-chain! module true nil))

(defn- declaration-from-reference-when
  [reference expected predicate]
  (let [metadata-declaration
        (when (instance? clojure.lang.Var reference)
          (:aguafria/declaration (meta reference)))
        reference-symbol
        (cond
          metadata-declaration
          (symbol (:module metadata-declaration)
                  (str (:name metadata-declaration)))

          (symbol? reference) reference

          :else nil)
        _ (when-not (and reference-symbol (namespace reference-symbol))
            (throw
             (ex-info "A live Zig declaration must be a Var or qualified symbol"
                      {:reference reference :expected expected})))
        module (namespace reference-symbol)
        declaration-name (name reference-symbol)
        registered
        (some (fn [declaration]
                (when (and (predicate declaration)
                           (= declaration-name (str (:name declaration))))
                  declaration))
              (vals (get-in @registry [module :definitions])))
        declaration (or registered metadata-declaration)]
    (when-not (predicate declaration)
      (throw
       (ex-info "The referenced Aguafria Var has the wrong Zig declaration kind"
                {:reference reference
                 :expected expected
                 :actual-kind (:kind declaration)
                 :known-modules (sort (keys @registry))})))
    declaration))

(defn- declaration-from-reference
  [reference expected-kind]
  (declaration-from-reference-when reference expected-kind
                                   #(= expected-kind (:kind %))))

(defn state-versions
  "Return serializable native state generations for an `az/defvar` Var."
  [state]
  (locking compile-lock
    (let [declaration (declaration-from-reference state :var)
          logical-id (:logical-id declaration)]
      (->> (get-in @registry [(:module declaration) :state-versions])
           (filter #(= logical-id (:logical-id %)))
           (mapv #(dissoc % :restore-handle))))))

(defn type-versions
  "Return schema generations for an `az/defstruct` or container type Var."
  [type]
  (locking compile-lock
    (let [declaration
          (declaration-from-reference-when
           type :type-producing type-producing-declaration?)
          logical-id (:logical-id declaration)]
      (->> (get-in @registry [(:module declaration) :type-versions])
           (filter #(= logical-id (:logical-id %)))
           vec))))

(defn migrate-state!
  "Authorize and apply one explicit breaking `az/defvar` migration.

  `migration` must name an exported Aguafria function with signature
  `[old-address :- :usize new-address :- :usize] -> :void`. The migration is
  registered for exactly the currently published and requested schema pair,
  then the complete dependency component is prepared and published again."
  [state migration]
  (let [{:keys [module logical-id schema-fingerprint] :as declaration}
        (locking compile-lock (declaration-from-reference state :var))
        migration-declaration
        (locking compile-lock (declaration-from-reference migration :fn))
        active
        (locking compile-lock
          (some #(when (and (= logical-id (:logical-id %)) (:active? %)) %)
                (get-in @registry [module :state-versions])))
        _ (when-not active
            (throw
             (ex-info "The Zig state Var has no published state to migrate"
                      {:aguafria/phase :zig-state-migration
                       :logical-id logical-id :module module})))
        _ (when (= (:schema-fingerprint active) schema-fingerprint)
            (throw
             (ex-info "The requested Zig state schema is already compatible"
                      {:aguafria/phase :zig-state-migration
                       :logical-id logical-id
                       :schema-fingerprint schema-fingerprint})))
        _ (when-not (and (:export? migration-declaration)
                         (= :void (:return migration-declaration))
                         (= [:usize :usize]
                            (mapv :type (:args migration-declaration))))
            (throw
             (ex-info
              "A Zig state migration must be exported, accept two usize addresses, and return void"
              {:aguafria/phase :zig-state-migration
               :migration (:qualified-name migration-declaration)
               :export? (:export? migration-declaration)
               :arguments (mapv :type (:args migration-declaration))
               :return (:return migration-declaration)})))
        migration-record
        {:logical-id logical-id
         :from-schema (:schema-fingerprint active)
         :to-schema schema-fingerprint
         :function (str (:qualified-name migration-declaration))
         :registered-at-ms (System/currentTimeMillis)}]
    (swap! state-migrations assoc
           [logical-id (:from-schema migration-record)
            (:to-schema migration-record)]
           migration-record)
    (let [publication (recompile-component! module)]
      ;; A root type publication is allowed to succeed while a transitive
      ;; state owner waits for explicit migration. Once that exact state has
      ;; migrated, its durable dependent-publication error is resolved too.
      (swap! registry
             (fn [modules]
               (into {}
                     (map
                      (fn [[module-name module-state]]
                        [module-name
                         (if (= logical-id
                                (get-in module-state
                                        [:last-dependent-publication-failure
                                         :logical-id]))
                           (-> module-state
                               (assoc :last-dependent-publication-failure nil)
                               (dissoc :last-dependent-publication-error))
                           module-state)]))
                     modules)))
      (assoc publication :migration migration-record))))

(declare recompile!)

(defn register-declaration!
  "Add or replace a declaration and rebuild its namespace module.

  With `:async?` configuration enabled, returns immediately after scheduling
  an immutable module snapshot. Builds may run concurrently, but only the
  newest requested generation is published."
  [declaration]
  (let [{:keys [module declaration-key] :as declaration}
        (declaration-info declaration)]
    (when-not (and module declaration-key)
      (throw (ex-info "Declaration requires :module and :declaration-key"
                      {:declaration declaration})))
    (if *registration-batch*
      (do
        (swap! *registration-batch* conj declaration)
        {:module module :declaration-key declaration-key :batched? true})
      (do
        (project/ensure-source-catalog! (get-in declaration [:source :file]))
        (cond
          (project/converted-module? module)
          (register-converted-async! declaration)

          (:async? @config)
          (register-async! declaration)

          :else
          (register-sync! declaration))))))

(defn register-batch!
  "Register a complete declaration batch without intermediate compilations.

  With `:compile? false` (the converter default), the source and declarations
  become immediately inspectable and a later `recompile!` builds the complete
  module once. `:replace? true` removes declarations absent from the batch."
  [declarations {:keys [compile? replace? module]
                 :or {compile? false replace? true}}]
  (let [declarations (mapv declaration-info (ordered-batch declarations))
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
       (let [{:keys [pending last-error failed-generation requested-generation
                     last-dependent-publication-error
                     last-dependent-publication-failure]
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

           (and last-dependent-publication-error
                (= (:generation last-dependent-publication-failure)
                   requested-generation))
           (throw last-dependent-publication-error)

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
           source (:source module-state)
           source-hash (subs (sha256 source) 0 24)
           source-directory (io/file (:cache-dir compiler-options)
                                     "standalone"
                                     (safe-path-component module)
                                     source-hash)
           source-file (io/file source-directory "module.zig")
           _ (.mkdirs ^File source-directory)
           _ (when-not (= source (when (.isFile source-file) (slurp source-file)))
               (Files/writeString
                (.toPath source-file) source StandardCharsets/UTF_8
                (into-array StandardOpenOption
                            [StandardOpenOption/CREATE
                             StandardOpenOption/TRUNCATE_EXISTING
                             StandardOpenOption/WRITE])))
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

(defn- dispatch-version-views
  [module-state]
  (->> (:dispatch-state module-state)
       vals
       (map #(select-keys % [:version-key :logical-id :abi-fingerprint
                             :implementation-fingerprint
                             :implementation-generation]))
       (sort-by (juxt (comp pr-str :logical-id) :abi-fingerprint))
       vec))

(defn- native-generation-views
  [module-state]
  (let [current (:published-generation module-state)
        referenced (into #{current}
                         (keep :implementation-generation)
                         (vals (:dispatch-state module-state)))]
    (->> (:native-generations module-state)
         (map (fn [generation]
                (let [native-active (native-active-call-count generation)
                      jvm-active (jvm-active-call-count generation)
                      native-value-refs (native-value-ref-count generation)
                      referenced? (contains? referenced (:generation generation))]
                  {:generation (:generation generation)
                   :hash (:hash generation)
                   :library-path (:library-path generation)
                   :current? (= current (:generation generation))
                   :referenced? referenced?
                   :native-active-call-count native-active
                   :jvm-active-call-count jvm-active
                   :native-value-reference-count native-value-refs
                   :retirement-pending? (and (not referenced?)
                                             (or (pos? native-active)
                                                 (pos? jvm-active)
                                                 (pos? native-value-refs)))})))
         (sort-by :generation)
         vec)))

(defn module-info
  "Return inspectable information for a module/namespace, excluding native
  loader objects and method handles."
  [module]
  (locking compile-lock
    (when-let [m (get @registry (str module))]
      (-> m
          (dissoc :arena :lookup :linker :functions :pending :scheduled :last-error
                  :last-dependent-publication-error
                  :dispatch-bindings :dispatch-state :state-bindings
                  :native-generations
                  :active-call-handle :active-call-tracking-handle
                  :publication-epoch-setters
                  :jvm-active-calls)
          (assoc :pending? (boolean (:pending m))
                 :error (some-> (:last-error m) ex-message)
                 :native-generation-count (count (:native-generations m))
                 :dispatch-versions (dispatch-version-views m)
                 :native-generations (native-generation-views m))
          (update :definitions vals)))))

(defn- build-view
  [now build]
  (cond-> build
    (and (#{:queued :compiling} (:status build)) (:started-at-ms build))
    (assoc :elapsed-ms (- now (:started-at-ms build)))))

(defn- percentile-value
  [sorted-values percentile]
  (when (seq sorted-values)
    (let [index (-> (* (double percentile) (count sorted-values))
                    Math/ceil
                    long
                    dec
                    (max 0)
                    (min (dec (count sorted-values))))]
      (nth sorted-values index))))

(defn- metric-summary
  [values]
  (let [values (->> values (remove nil?) sort vec)
        count-values (count values)]
    (if (zero? count-values)
      {:count 0}
      {:count count-values
       :min-ms (first values)
       :p50-ms (percentile-value values 0.50)
       :p95-ms (percentile-value values 0.95)
       :p99-ms (percentile-value values 0.99)
       :max-ms (peek values)
       :mean-ms (/ (double (reduce + values)) count-values)})))

(defn- build-timing-summary-base
  [builds]
  (let [completed
        (filter #(and (:requested-at-ms %)
                      (:started-at-ms %)
                      (:finished-at-ms %))
                builds)
        cache-observations (filter #(contains? % :cached?) completed)
        cache-hits (count (filter :cached? cache-observations))]
    {:queue-wait-ms
     (metric-summary (map #(- (:started-at-ms %) (:requested-at-ms %))
                          completed))
     :native-build-ms
     (metric-summary (map #(- (:finished-at-ms %) (:started-at-ms %))
                          completed))
     :end-to-end-ms
     (metric-summary (map #(- (:finished-at-ms %) (:requested-at-ms %))
                          completed))
     :cache {:observation-count (count cache-observations)
             :hit-count cache-hits
             :miss-count (- (count cache-observations) cache-hits)
             :hit-rate (when (seq cache-observations)
                         (/ (double cache-hits)
                            (count cache-observations)))}}))

(defn- build-timing-summary
  [builds]
  (assoc (build-timing-summary-base builds)
         :by-purpose
         (into (sorted-map)
               (map (fn [[purpose purpose-builds]]
                      [purpose (build-timing-summary-base purpose-builds)]))
               (group-by :purpose builds))))

(defn- module-dependency-graph
  [module-states]
  (let [direct
        (into {}
              (map (fn [[module module-state]]
                     [module
                      (->> (:definitions module-state)
                           vals
                           emit/declaration-imports
                           vals
                           (keep :namespace)
                           (map str)
                           distinct
                           sort
                           vec)]))
              module-states)
        nodes (->> (concat (keys direct) (mapcat val direct)) set sort)]
    (into (sorted-map)
          (map (fn [module] [module (get direct module [])]))
          nodes)))

(defn- dependency-topology
  [module-states]
  (let [graph (module-dependency-graph module-states)
        nodes (vec (keys graph))
        visited (atom #{})
        finish-order (atom [])]
    (letfn [(visit! [node]
              (when-not (contains? @visited node)
                (swap! visited conj node)
                (doseq [dependency (get graph node)]
                  (visit! dependency))
                (swap! finish-order conj node)))]
      (doseq [node nodes] (visit! node)))
    (let [reverse-graph
          (reduce-kv
           (fn [reversed module dependencies]
             (reduce (fn [result dependency]
                       (update result dependency conj module))
                     reversed dependencies))
           (into (sorted-map) (map (fn [node] [node []])) nodes)
           graph)
          reverse-graph (into (sorted-map)
                              (map (fn [[module dependents]]
                                     [module (vec (sort dependents))]))
                              reverse-graph)
          assigned (atom #{})
          components (atom [])]
      (letfn [(collect! [node component]
                (if (contains? @assigned node)
                  component
                  (do
                    (swap! assigned conj node)
                    (reduce (fn [result dependent]
                              (collect! dependent result))
                            (conj component node)
                            (get reverse-graph node)))))]
        (doseq [node (reverse @finish-order)]
          (when-not (contains? @assigned node)
            (swap! components conj (vec (sort (collect! node [])))))))
      (let [components (->> @components (sort-by first) vec)
            component-views
            (mapv (fn [id modules]
                    {:id id
                     :modules modules
                     :cyclic? (boolean
                               (or (> (count modules) 1)
                                   (some #(contains? (set (get graph %)) %)
                                         modules)))})
                  (range) components)
            component-by-module
            (into {}
                  (mapcat (fn [{:keys [id modules]}]
                            (map (fn [module] [module id]) modules)))
                  component-views)]
        {:graph graph
         :reverse-graph reverse-graph
         :components component-views
         :component-by-module component-by-module}))))

(defn- module-stats
  [module now builds topology]
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
        dispatch-by-version (:dispatch-state module-state)
        component-id (get-in topology [:component-by-module module])
        component (some #(when (= component-id (:id %)) %)
                        (:components topology))
        declarations (->> (:definitions module-state)
                          vals
                          (map (fn [declaration]
                                 (let [dispatch
                                       (get dispatch-by-version
                                            [(:logical-id declaration)
                                             (:abi-fingerprint declaration)])]
                                   (cond->
                                    (assoc (declaration-summary declaration)
                                           :state declaration-state)
                                     dispatch
                                     (assoc :implementation-generation
                                            (:implementation-generation dispatch)
                                            :implementation-fingerprint
                                            (:implementation-fingerprint dispatch))))))
                          (sort-by (juxt :kind :name))
                          vec)]
    {:module module
     :status (or (:status latest-repl-build) :idle)
     :requested-generation (or (:requested-generation module-state)
                               (:generation latest-build))
     :published-generation (:published-generation module-state)
     :pending? (boolean (:pending module-state))
     :dependencies (get-in topology [:graph module] [])
     :dependents (get-in topology [:reverse-graph module] [])
     :dependency-component component-id
     :dependency-component-modules (:modules component)
     :cyclic-dependency-component? (boolean (:cyclic? component))
     :last-component-publication (:last-component-publication module-state)
     :last-component-publication-failure
     (:last-component-publication-failure module-state)
     :last-dependent-publication-failure
     (:last-dependent-publication-failure module-state)
     :partial-publication? (boolean (:partial-publication? module-state))
     :full-compile-error (:full-compile-error module-state)
     :declaration-count (count declarations)
     :function-count (count (filter #(= :fn (:kind %)) declarations))
     :native-generation-count (count (:native-generations module-state))
     :retired-generation-count (count (:retired-generations module-state))
     :dispatch-version-count (count (:dispatch-state module-state))
     :dispatch-versions (dispatch-version-views module-state)
     :state-version-count (count (:state-versions module-state))
     :state-versions (vec (:state-versions module-state))
     :type-version-count (count (:type-versions module-state))
     :type-versions (vec (:type-versions module-state))
     :state-migrations
     (->> @state-migrations vals
          (filter #(some #{(:logical-id %)}
                         (map :logical-id (:state-versions module-state))))
          (sort-by (juxt :logical-id :registered-at-ms))
          vec)
     :native-generations (native-generation-views module-state)
     :retired-generations (:retired-generations module-state)
     :declarations declarations
     :active-builds (->> module-builds
                         (filter #(#{:queued :compiling} (:status %)))
                         vec)
     :timings (build-timing-summary module-builds)
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
   (locking compile-lock
     (let [now (System/currentTimeMillis)
           builds @build-registry
           topology (dependency-topology @registry)
           module-names (->> (concat (keys @registry) (map :module (vals builds)))
                             set sort)
           modules (into (sorted-map)
                         (map (fn [module]
                                [module (module-stats module now builds topology)]))
                         module-names)
           build-views (->> builds vals
                            (sort-by (juxt :requested-at-ms :generation) #(compare %2 %1))
                            (mapv #(build-view now %)))
           statuses (frequencies (map :status build-views))
           host-views (->> @live-hosts vals (sort-by :id >) (mapv host-view))
           host-statuses (frequencies (map :status host-views))]
       {:generated-at-ms now
        :summary {:module-count (count modules)
                  :declaration-count (reduce + 0 (map :declaration-count (vals modules)))
                  :function-count (reduce + 0 (map :function-count (vals modules)))
                  :state-version-count
                  (reduce + 0 (map :state-version-count (vals modules)))
                  :type-version-count
                  (reduce + 0 (map :type-version-count (vals modules)))
                  :dependency-component-count (count (:components topology))
                  :cyclic-dependency-component-count
                  (count (filter :cyclic? (:components topology)))
                  :active-build-count (+ (get statuses :queued 0)
                                         (get statuses :compiling 0))
                  :queued-build-count (get statuses :queued 0)
                  :compiling-build-count (get statuses :compiling 0)
                  :finished-build-count (get statuses :finished 0)
                  :stale-build-count (get statuses :stale 0)
                  :migration-required-build-count
                  (get statuses :migration-required 0)
                  :failed-build-count (get statuses :failed 0)
                  :cache-hit-count (count (filter :cached? build-views))
                  :native-host-count (count host-views)
                  :active-native-host-count
                  (+ (get host-statuses :starting 0)
                     (get host-statuses :running 0))
                  :finished-native-host-count (get host-statuses :finished 0)
                  :failed-native-host-count (get host-statuses :failed 0)}
        :modules modules
        :dependency-components (:components topology)
        :native-hosts host-views
        :timings (build-timing-summary build-views)
        :builds build-views})))
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

(defn- coerce-integer-argument
  [type value signed? bits]
  (when-not (integer? value)
    (throw (ex-info "Zig integer argument requires a Clojure integer"
                    {:zig-type type :value value
                     :clojure-type (clojure.core/type value)})))
  (let [integer (biginteger value)
        modulus (.shiftLeft java.math.BigInteger/ONE bits)
        minimum (if signed?
                  (.negate (.shiftRight modulus 1))
                  java.math.BigInteger/ZERO)
        maximum (if signed?
                  (.subtract (.shiftRight modulus 1) java.math.BigInteger/ONE)
                  (.subtract modulus java.math.BigInteger/ONE))]
    (when (or (neg? (.compareTo integer minimum))
              (pos? (.compareTo integer maximum)))
      (throw (ex-info "Zig integer argument is out of range"
                      {:zig-type type :value value
                       :minimum minimum :maximum maximum})))
    (.longValue integer)))

(defn- coerce-argument
  [type value]
  (case type
    :bool
    (if (instance? Boolean value)
      (byte (if value 1 0))
      (throw (ex-info "Zig bool argument requires true or false"
                      {:zig-type type :value value
                       :clojure-type (clojure.core/type value)})))
    :i8 (unchecked-byte (coerce-integer-argument type value true 8))
    :u8 (unchecked-byte (coerce-integer-argument type value false 8))
    :i16 (unchecked-short (coerce-integer-argument type value true 16))
    :u16 (unchecked-short (coerce-integer-argument type value false 16))
    :i32 (unchecked-int (coerce-integer-argument type value true 32))
    :u32 (unchecked-int (coerce-integer-argument type value false 32))
    :i64 (unchecked-long (coerce-integer-argument type value true 64))
    :u64 (unchecked-long (coerce-integer-argument type value false 64))
    :isize (unchecked-long (coerce-integer-argument type value true 64))
    :usize (unchecked-long (coerce-integer-argument type value false 64))
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
    (:u64 :usize)
    (let [signed-value (long value)]
      (if (neg? signed-value)
        (java.math.BigInteger. (Long/toUnsignedString signed-value))
        signed-value))
    value))

(defn- current-function-declaration
  [module-state qualified-name]
  (some #(when (= qualified-name (:qualified-name %)) %)
        (vals (:definitions module-state))))

(defn- qualified-function-name
  [function]
  (cond
    (var? function)
    (let [{:keys [ns name]} (meta function)]
      (symbol (str (ns-name ns)) (str name)))

    (and (symbol? function) (namespace function))
    function

    :else
    (throw (ex-info "Expected a qualified function symbol or Var"
                    {:function function :type (type function)}))))

(defn function-versions
  "Return every loaded ABI version for one exported Zig function as plain,
  serializable data. The current version is marked with `:current?`."
  [function]
  (let [qualified-name (qualified-function-name function)
        module (namespace qualified-name)
        _ (await! module)]
    (locking compile-lock
      (let [module-state (get @registry module)
            declaration (current-function-declaration module-state qualified-name)
            logical-id (:logical-id declaration)
            current-abi (:abi-fingerprint declaration)]
        (when-not declaration
          (throw (ex-info "Zig function is not registered"
                          {:function qualified-name :module module})))
        (->> (:dispatch-state module-state)
             vals
             (filter #(= logical-id (:logical-id %)))
             (map #(assoc (select-keys % [:logical-id :abi-fingerprint
                                           :implementation-fingerprint
                                           :implementation-generation])
                          :current? (= current-abi (:abi-fingerprint %))))
             (sort-by :abi-fingerprint)
             vec)))))

(defn- versioned-function-binding
  [module-state qualified-name abi-fingerprint]
  (let [declaration (current-function-declaration module-state qualified-name)
        logical-id (:logical-id declaration)
        dispatch (get-in module-state
                         [:dispatch-state [logical-id abi-fingerprint]])
        generation-id (:implementation-generation dispatch)
        generation (some #(when (= generation-id (:generation %)) %)
                         (:native-generations module-state))]
    (when dispatch
      (get-in generation [:functions qualified-name]))))

(defn- acquire-function-binding!
  [qualified-name arguments abi-fingerprint]
  (let [module (namespace qualified-name)]
    (locking compile-lock
      (let [module-state (get @registry module)
            function-binding
            (if abi-fingerprint
              (versioned-function-binding module-state qualified-name abi-fingerprint)
              (get-in module-state [:functions qualified-name]))
            declaration (:declaration function-binding)]
        (when-not function-binding
          (throw (ex-info (if abi-fingerprint
                            "Zig function ABI version is not loaded"
                            "Zig function is not loaded")
                          (cond-> {:function qualified-name :module module}
                            abi-fingerprint (assoc :abi-fingerprint abi-fingerprint
                                                   :available-versions
                                                   (mapv :abi-fingerprint
                                                         (function-versions
                                                          qualified-name)))))))
        (when (:unsupported? function-binding)
          (throw
           (ex-info "Zig function uses an ABI type not callable from Clojure yet"
                    {:function qualified-name
                     :abi-fingerprint (:abi-fingerprint declaration)
                     :supported-types (conj (set (keys scalar-layouts)) :void)
                     :arguments (mapv :type (:args declaration))
                     :return (:return declaration)})))
        (when-not (= (count arguments) (count (:args declaration)))
          (throw (ex-info "Wrong number of arguments for Zig function"
                          {:function qualified-name
                           :abi-fingerprint (:abi-fingerprint declaration)
                           :expected (count (:args declaration))
                           :actual (count arguments)})))
        (.incrementAndGet ^AtomicLong (:jvm-active-calls function-binding))
        function-binding))))

(defn- native-argument-address
  [module qualified-name expected-type argument argument-binding
   ^Arena call-arena]
  (if (zig-value/zig-value? argument)
    (let [actual-type (zig-value/type argument)
          actual-module (:module (zig-value/info argument))
          canonical
          (fn [type default-module]
            (if (and (symbol? type) (nil? (namespace type)))
              (symbol (str default-module) (name type))
              type))]
      (when-not (= (canonical expected-type module)
                   (canonical actual-type actual-module))
        (throw (ex-info "Native Zig argument has the wrong Zig type"
                        {:function qualified-name
                         :expected-zig-type expected-type
                         :actual-zig-type actual-type
                         :actual-module actual-module})))
      (.address ^MemorySegment (zig-value/segment argument)))
    (if-let [schema (or (native-optional-field-schema module #{}
                                                       argument-binding)
                        (native-slice-field-schema module #{}
                                                   argument-binding)
                        (native-error-union-field-schema module #{}
                                                         argument-binding)
                        (native-storage-binding-schema
                         module #{} expected-type
                         (:nested-storage-binding argument-binding))
                        (native-type-schema module expected-type))]
      (let [size (long (.invokeWithArguments
                        ^MethodHandle (:size-getter-handle argument-binding)
                        (ArrayList.)))
            alignment
            (long (.invokeWithArguments
                   ^MethodHandle (:align-getter-handle argument-binding)
                   (ArrayList.)))
            segment (.allocate call-arena size alignment)]
        (zig-value/write-value! segment expected-type schema argument call-arena)
        (.address segment))
      (throw (ex-info "Zig function argument requires a native Zig value or constructible Clojure value"
                      {:function qualified-name
                       :expected-zig-type expected-type
                       :argument argument
                       :argument-type (type argument)})))))

(defn- invoke-indirect-binding!
  [module function-binding arguments]
  (let [declaration (:declaration function-binding)
        qualified-name (:qualified-name declaration)
        {:keys [argument-modes return-mode]} (:bridge-spec function-binding)
        native-result? (= :native return-mode)
        result-arena (when native-result? (Arena/ofShared))
        call-arena (if native-result? (Arena/ofShared) (Arena/ofConfined))
        retained-call-arena? (atom false)]
    (try
      (let [coerced
            (mapv (fn [index {:keys [type]} argument argument-mode]
                    (if (= :scalar argument-mode)
                      (coerce-argument type argument)
                      (long
                       (native-argument-address
                        module qualified-name type argument
                        (nth (:native-argument-bindings function-binding) index)
                        call-arena))))
                  (range) (:args declaration) arguments argument-modes)
            result-size
            (when native-result?
              (long (.invokeWithArguments
                     ^MethodHandle (:result-size-getter-handle function-binding)
                     (ArrayList.))))
            result-alignment
            (when native-result?
              (long (.invokeWithArguments
                     ^MethodHandle (:result-align-getter-handle function-binding)
                     (ArrayList.))))
            result-segment
            (when native-result?
              (.allocate ^Arena result-arena result-size result-alignment))
            coerced (cond-> coerced
                      native-result? (conj (long (.address result-segment))))
            result (.invokeWithArguments
                    ^MethodHandle (:handle function-binding)
                    (ArrayList. ^java.util.Collection coerced))]
        (case return-mode
          :void nil
          :scalar (coerce-result (:return declaration) result)
          :native
          (let [native-value-refs (:native-value-refs function-binding)
                closed? (atom false)
                close!
                (fn []
                  (when (compare-and-set! closed? false true)
                    (try
                      (try
                        (.close ^Arena result-arena)
                        (finally
                          (.close ^Arena call-arena)))
                      (finally
                        (.decrementAndGet ^AtomicLong native-value-refs)
                        (locking compile-lock
                          (retire-module-quiescent-generations! module))))))
                native-value
                (zig-value/native-value
                 {:module module
                  :name (:name declaration)
                  :kind :return
                  :type (:return declaration)
                  :logical-id (:logical-id declaration)}
                 (constantly {:representation :native
                              :segment result-segment
                              :size result-size
                              :alignment result-alignment
                              :schema
                              (or (native-optional-field-schema module #{}
                                                                function-binding)
                                  (native-slice-field-schema module #{}
                                                             function-binding)
                                  (native-error-union-field-schema
                                   module #{} function-binding)
                                  (native-storage-binding-schema
                                   module #{} (:return declaration)
                                   (:nested-storage-binding function-binding))
                                  (native-type-schema module
                                                      (:return declaration)))
                              :generation (:wrapper-generation function-binding)
                              :close! close!}))]
            (.incrementAndGet ^AtomicLong native-value-refs)
            ;; The native result already exists. Force only the lightweight
            ;; wrapper state so Cleaner registration cannot be skipped when a
            ;; caller discards the return value without dereferencing it.
            (zig-value/value native-value)
            (reset! retained-call-arena? true)
            native-value)))
      (catch Throwable error
        (when result-arena
          (try (.close ^Arena result-arena) (catch Throwable _)))
        (throw error))
      (finally
        (when-not @retained-call-arena?
          (.close call-arena))))))

(defn- invoke-binding!
  [module function-binding arguments]
  (let [declaration (:declaration function-binding)]
    (try
      (if (= :indirect (get-in function-binding [:bridge-spec :mode]))
        (invoke-indirect-binding! module function-binding arguments)
        (let [coerced (mapv (fn [{:keys [type]} value]
                              (coerce-argument type value))
                            (:args declaration) arguments)
              values (ArrayList. ^java.util.Collection coerced)
              result (.invokeWithArguments ^MethodHandle
                                           (:handle function-binding) values)]
          (coerce-result (:return declaration) result)))
      (finally
        (.decrementAndGet ^AtomicLong (:jvm-active-calls function-binding))
        (locking compile-lock
          (when (seq @retirement-pending-modules)
            (retire-pending-generations!)))))))

(defn- await-callable-generation!
  [module]
  (try
    (await! module)
    (catch Throwable compilation-error
      ;; A failed hot-reload generation is inspectable through await!/stats,
      ;; but it must not take the last successfully published program offline.
      ;; Binding acquisition below still reports a precise error when the
      ;; requested function/ABI has never had a callable generation.
      (when-not (:published-generation (get @registry module))
        (throw compilation-error)))))

(defn- function-loaded?
  [qualified-name]
  (locking compile-lock
    (let [binding (get-in @registry [(namespace qualified-name)
                                     :functions qualified-name])]
      (boolean (and binding (not (:unsupported? binding)))))))

(defn- materialize-jvm-callable!
  "Compile a development-only C ABI trampoline for a registered Zig Var whose
  original declaration is intentionally not `export`. Final/static Zig source
  and the declaration's Zig visibility remain unchanged."
  [qualified-name]
  (let [module (namespace qualified-name)]
    (await-callable-generation! module)
    (when-not (function-loaded? qualified-name)
      (let [declaration
            (locking compile-lock
              (current-function-declaration (get @registry module)
                                            qualified-name))]
        (when-not declaration
          (throw (ex-info "Zig function is not registered"
                          {:function qualified-name :module module})))
        (locking compile-lock
          (swap! registry update-in [module :jvm-callable-declaration-keys]
                 (fnil conj #{}) (:declaration-key declaration)))
        (binding [*propagate-dependent-changes?* false]
          (recompile! module))
        (await-callable-generation! module)))))

(defn- current-value-binding
  [qualified-name]
  (locking compile-lock
    (get-in @registry [(namespace qualified-name) :values qualified-name])))

(defn- integer-bit-width
  [type]
  (when (keyword? type)
    (some-> (re-matches #"[iu](\d+)" (name type)) second Long/parseLong)))

(defn- storage-helper-type?
  [type]
  (boolean (nested-storage-wrapper-spec type "__aguafria_storage")))

(defn- packed-field-bit-width
  [module type seen]
  (cond
    (= :bool type) 1
    (integer-bit-width type) (integer-bit-width type)
    :else
    (some-> (native-type-schema module type seen) :bit-size)))

(defn- native-type-declaration
  [module type]
  (let [[target-module target-name]
        (cond
          (and (symbol? type) (namespace type))
          [(namespace type) (symbol (name type))]

          (symbol? type)
          [module type]

          :else [nil nil])]
    (when target-module
      (some (fn [declaration]
              (when (and (contains? #{:struct :const} (:kind declaration))
                         (= (str target-name) (str (:name declaration))))
                declaration))
            (vals (get-in @registry [target-module :definitions]))))))

(defn- native-enum-schema
  [type declaration]
  (let [qualified-name (symbol (:module declaration) (str (:name declaration)))
        {:keys [size-getter-handle enum-member-bindings] :as binding}
        (get-in @registry [(:module declaration) :types qualified-name])]
    (when binding
      (let [size (long (.invokeWithArguments ^MethodHandle size-getter-handle
                                              (ArrayList.)))]
        {:kind :enum
         :type type
         :size size
         :declaration
         (select-keys declaration [:module :name :logical-id
                                   :schema-fingerprint])
         :members
         (mapv
          (fn [{:keys [member address-getter-handle]}]
            (let [address
                  (long (.invokeWithArguments ^MethodHandle
                                              address-getter-handle
                                              (ArrayList.)))
                  segment (.reinterpret (MemorySegment/ofAddress address) size)]
              {:name (keyword (str (or (:zig-name member) (:name member))))
               :bytes (vec (.toArray segment ValueLayout/JAVA_BYTE))}))
          enum-member-bindings)}))))

(defn- native-optional-field-schema
  [module seen {:keys [optional? optional-child-type
                       optional-set-handle optional-present-handle
                       optional-payload-address-handle
                       optional-payload-size-handle
                       nested-storage-binding]}]
  (when optional?
    (let [payload-size
          (long (.invokeWithArguments ^MethodHandle optional-payload-size-handle
                                      (ArrayList.)))]
      {:kind :optional
       :child-type optional-child-type
       :child-schema
       (or (native-storage-binding-schema
            module seen optional-child-type nested-storage-binding)
           (native-type-schema module optional-child-type seen))
       :payload-size payload-size
       :present-fn
       (fn [^MemorySegment storage]
         (not
          (zero?
           (long
            (.invokeWithArguments
             ^MethodHandle optional-present-handle
             (ArrayList. ^java.util.Collection
                         [(long (.address storage))]))))))
       :payload-segment-fn
       (fn [^MemorySegment storage]
         (let [address
               (long
                (.invokeWithArguments
                 ^MethodHandle optional-payload-address-handle
                 (ArrayList. ^java.util.Collection
                             [(long (.address storage))])))]
           (when (pos? address)
             (.reinterpret (MemorySegment/ofAddress address) payload-size))))
       :set-fn
       (fn [^MemorySegment storage present? ^MemorySegment payload]
         (.invokeWithArguments
          ^MethodHandle optional-set-handle
          (ArrayList. ^java.util.Collection
                      [(long (.address storage))
                       (byte (if present? 1 0))
                       (long (if payload (.address payload) 0))]))
         storage)})))

(defn- native-slice-field-schema
  [module seen {:keys [slice? slice-element-type slice-set-handle
                       slice-pointer-handle slice-length-handle
                       slice-element-size-handle
                       slice-element-align-handle
                       nested-storage-binding]}]
  (when slice?
    (let [element-size
          (long (.invokeWithArguments ^MethodHandle slice-element-size-handle
                                      (ArrayList.)))
          element-alignment
          (long (.invokeWithArguments ^MethodHandle slice-element-align-handle
                                      (ArrayList.)))]
      {:kind :slice
       :element-type slice-element-type
       :element-schema
       (or (native-storage-binding-schema module seen slice-element-type
                                          nested-storage-binding)
           (native-type-schema module slice-element-type seen))
       :element-size element-size
       :element-alignment element-alignment
       :ownership :borrowed
       :read-fn
       (fn [^MemorySegment storage]
         {:address
          (long (.invokeWithArguments
                 ^MethodHandle slice-pointer-handle
                 (ArrayList. ^java.util.Collection
                             [(long (.address storage))])))
          :length
          (long (.invokeWithArguments
                 ^MethodHandle slice-length-handle
                 (ArrayList. ^java.util.Collection
                             [(long (.address storage))])))})
       :set-fn
       (fn [^MemorySegment storage ^MemorySegment backing length]
         (.invokeWithArguments
          ^MethodHandle slice-set-handle
          (ArrayList. ^java.util.Collection
                      [(long (.address storage))
                       (long (.address backing))
                       (long length)]))
         storage)})))

(defn- native-error-union-field-schema
  [module seen {:keys [error-union? error-payload-type error-set
                       error-set-ok-handle error-set-error-handle
                       error-present-handle error-code-handle
                       error-name-pointer-handle error-name-length-handle
                       error-payload-address-handle
                       error-payload-size-handle
                       nested-storage-binding]}]
  (when error-union?
    (let [payload-size
          (long (.invokeWithArguments ^MethodHandle error-payload-size-handle
                                      (ArrayList.)))]
      {:kind :error-union
       :error-set error-set
       :payload-type error-payload-type
       :payload-schema
       (or (native-storage-binding-schema module seen error-payload-type
                                          nested-storage-binding)
           (native-type-schema module error-payload-type seen))
       :payload-size payload-size
       :error-fn
       (fn [^MemorySegment storage]
         (when-not
          (zero?
           (long (.invokeWithArguments
                  ^MethodHandle error-present-handle
                  (ArrayList. ^java.util.Collection
                              [(long (.address storage))]))))
           (let [code
                 (long (.invokeWithArguments
                        ^MethodHandle error-code-handle
                        (ArrayList. ^java.util.Collection
                                    [(long (.address storage))])))
                 name-address
                 (long (.invokeWithArguments
                        ^MethodHandle error-name-pointer-handle
                        (ArrayList. ^java.util.Collection
                                    [(long (.address storage))])))
                 name-length
                 (long (.invokeWithArguments
                        ^MethodHandle error-name-length-handle
                        (ArrayList. ^java.util.Collection
                                    [(long (.address storage))])))
                 name (when (and (pos? name-address) (pos? name-length))
                        (String.
                         (.toArray
                          (.reinterpret (MemorySegment/ofAddress name-address)
                                        name-length)
                          ValueLayout/JAVA_BYTE)
                         StandardCharsets/UTF_8))]
             {:name (when name (keyword name)) :code code})))
       :payload-segment-fn
       (fn [^MemorySegment storage]
         (when (pos? payload-size)
           (let [address
                 (long (.invokeWithArguments
                        ^MethodHandle error-payload-address-handle
                        (ArrayList. ^java.util.Collection
                                    [(long (.address storage))])))]
             (when (pos? address)
               (.reinterpret (MemorySegment/ofAddress address) payload-size)))))
       :set-ok-fn
       (fn [^MemorySegment storage ^MemorySegment payload]
         (.invokeWithArguments
          ^MethodHandle error-set-ok-handle
          (ArrayList. ^java.util.Collection
                      [(long (.address storage))
                       (long (if payload (.address payload) 0))]))
         storage)
       :set-error-fn
       (fn [^MemorySegment storage code]
         (.invokeWithArguments
          ^MethodHandle error-set-error-handle
          (ArrayList. ^java.util.Collection
                      [(long (.address storage)) (long code)]))
         storage)})))

(defn- native-storage-binding-schema
  [module seen type binding]
  (when binding
    (case (:storage-kind binding)
      :optional
      (native-optional-field-schema
       module seen
       (assoc binding
              :optional? true
              :optional-child-type (:child-type binding)
              :nested-storage-binding (:child-storage-binding binding)))

      :slice
      (native-slice-field-schema
       module seen
       (assoc binding
              :slice? true
              :slice-element-type (:element-type binding)
              :nested-storage-binding (:child-storage-binding binding)))

      :error-union
      (native-error-union-field-schema
       module seen
       (assoc binding
              :error-union? true
              :error-payload-type (:payload-type binding)
              :nested-storage-binding (:child-storage-binding binding)))

      :collection
      (when-let [schema (native-type-schema module type seen)]
        (assoc schema
               :element-schema
               (or (native-storage-binding-schema
                    module (conj seen [module type])
                    (:element-type binding)
                    (:child-storage-binding binding))
                   (:element-schema schema))))

      nil)))

(defn- native-union-schema
  [module type seen declaration fields]
  (let [qualified-name (symbol (:module declaration) (str (:name declaration)))
        {:keys [field-bindings tagged-union?] :as binding}
        (get-in @registry [(:module declaration) :types qualified-name])]
    (when (and binding (= (count fields) (count field-bindings)))
      {:kind :union
       :type type
       :tagged? tagged-union?
       :declaration
       (select-keys declaration [:module :name :logical-id
                                 :schema-fingerprint])
       :fields
       (mapv
        (fn [field field-binding]
          (let [field-size
                (long (.invokeWithArguments
                       ^MethodHandle (:size-getter-handle field-binding)
                       (ArrayList.)))
                field-schema
                (or (native-optional-field-schema
                     module (conj seen [module type]) field-binding)
                    (native-slice-field-schema
                     module (conj seen [module type]) field-binding)
                    (native-error-union-field-schema
                     module (conj seen [module type]) field-binding)
                    (native-storage-binding-schema
                     module (conj seen [module type]) (:type field)
                     (:nested-storage-binding field-binding))
                    (native-type-schema module (:type field)
                                        (conj seen [module type])))]
            (cond->
             (assoc field
                    :byte-offset 0
                    :byte-size field-size
                    :init-fn
                    (fn [^MemorySegment destination ^MemorySegment payload]
                      (.invokeWithArguments
                       ^MethodHandle (:union-init-handle field-binding)
                       (ArrayList. ^java.util.Collection
                                   [(long (.address destination))
                                    (long (if payload (.address payload) 0))]))
                      destination))
              field-schema (assoc :schema field-schema)
              tagged-union?
              (assoc
               :active-fn
               (fn [^MemorySegment value]
                 (not
                  (zero?
                   (long
                    (.invokeWithArguments
                     ^MethodHandle (:union-active-handle field-binding)
                     (ArrayList. ^java.util.Collection
                                 [(long (.address value))]))))))
               :payload-segment-fn
               (fn [^MemorySegment value]
                 (when (pos? field-size)
                   (let [address
                         (long
                          (.invokeWithArguments
                           ^MethodHandle
                           (:union-payload-address-handle field-binding)
                           (ArrayList. ^java.util.Collection
                                       [(long (.address value))])))]
                     (when (pos? address)
                       (.reinterpret (MemorySegment/ofAddress address)
                                     field-size)))))))))
        fields field-bindings)})))

(defn- native-type-schema
  ([module type] (native-type-schema module type #{}))
  ([module type seen]
   (let [identity [module type]]
     (when-not (contains? seen identity)
       (cond
         (and (vector? type)
              (contains? #{"*" "*const" "many" "many-const"
                           "sentinel" "sentinel-const" "c-pointer"
                           "pointer"}
                         (some-> type first name)))
         (let [[operator & arguments] type
               operator (name operator)
               options (when (= "pointer" operator) (first arguments))
               child-type
               (case operator
                 "pointer" (second arguments)
                 "sentinel" (first arguments)
                 "sentinel-const" (first arguments)
                 (first arguments))]
           {:kind :pointer
            :type type
            :child-type child-type
            :nullable? (boolean (or (= "c-pointer" operator)
                                    (:allowzero? options)
                                    (:allowzero options)))
            :ownership :borrowed})

         (and (vector? type)
              (contains? #{:array :array-sentinel :vector} (first type)))
         (let [[kind & arguments] type
               [length sentinel element-type storage-length schema-kind]
               (case kind
                 :array [(first arguments) nil (second arguments)
                         (first arguments) :array]
                 :array-sentinel [(first arguments) (second arguments)
                                  (nth arguments 2)
                                  (when (integer? (first arguments))
                                    (inc (first arguments)))
                                  :array]
                 :vector [(first arguments) nil (second arguments)
                          (first arguments) :vector])]
           (when (and (integer? length) (not (neg? length)))
             {:kind schema-kind
              :type type
              :length length
              :storage-length storage-length
              :sentinel sentinel
              :element-type element-type
              :element-schema
              (native-type-schema module element-type (conj seen identity))}))

         :else
         (when-let [{:keys [kind layout fields] :as declaration}
                    (native-type-declaration module type)]
           (let [container-description
                 (container-type-description declaration)
                 effective-kind (or (get-in container-description
                                            [:options :kind])
                                    kind)
                 layout (or (get-in container-description [:options :layout])
                            layout)
                 fields (if container-description
                          (->> (:members container-description)
                               (filter #(= :field (:kind %)))
                               vec)
                          fields)]
             (cond
               (= :enum effective-kind)
               (native-enum-schema type declaration)

               (= :union effective-kind)
               (native-union-schema module type seen declaration fields)

               (= :struct effective-kind)
             (let [base
                   {:type type
                    :layout layout
                    :declaration
                    (select-keys declaration [:module :name :logical-id
                                              :schema-fingerprint])}]
               (if (= :packed layout)
                 (let [{:keys [fields bit-size]}
                       (reduce
                        (fn [{:keys [fields bit-size]} field]
                          (let [field-schema
                                (native-type-schema module (:type field)
                                                    (conj seen identity))
                                field-width
                                (or (:bit-size field-schema)
                                    (packed-field-bit-width
                                     module (:type field)
                                     (conj seen identity)))]
                            (when-not field-width
                              (throw
                               (ex-info "Packed Zig field type is not decodable yet"
                                        {:module module :type type
                                         :field (:name field)
                                         :field-type (:type field)})))
                            {:fields (conj fields
                                           (cond-> (assoc field
                                                          :bit-offset bit-size
                                                          :bit-size field-width)
                                             field-schema
                                             (assoc :schema field-schema)))
                             :bit-size (+ bit-size field-width)}))
                        {:fields [] :bit-size 0}
                        fields)]
                   (assoc base
                          :kind :packed-struct
                          :fields fields
                          :bit-size bit-size))
                 (let [qualified-name
                       (symbol (:module declaration) (str (:name declaration)))
                       field-bindings
                       (get-in @registry [(:module declaration) :types
                                          qualified-name :field-bindings])]
                   (when (and field-bindings
                              (= (count fields) (count field-bindings)))
                     (assoc
                      base
                      :kind :struct
                      :fields
                      (mapv
                       (fn [field field-binding]
                         (let [field-schema
                               (or (native-optional-field-schema
                                    module (conj seen identity) field-binding)
                                   (native-slice-field-schema
                                    module (conj seen identity) field-binding)
                                   (native-error-union-field-schema
                                    module (conj seen identity) field-binding)
                                   (native-storage-binding-schema
                                    module (conj seen identity) (:type field)
                                    (:nested-storage-binding field-binding))
                                   (native-type-schema module (:type field)
                                                       (conj seen identity)))]
                           (cond->
                            (assoc field
                                   :byte-offset
                                   (long
                                    (.invokeWithArguments
                                     ^MethodHandle
                                     (:offset-getter-handle field-binding)
                                     (ArrayList.)))
                                   :byte-size
                                   (long
                                    (.invokeWithArguments
                                     ^MethodHandle
                                     (:size-getter-handle field-binding)
                                     (ArrayList.))))
                             field-schema (assoc :schema field-schema))))
                       fields field-bindings))))))))))))))

(defn- ensure-native-type-binding!
  "Load Zig-authored size/alignment/field/tag accessors for a named native
  type. Returns true when a new wrapper generation was published."
  [module type]
  (when-let [declaration (native-type-declaration module type)]
    (let [container-description (container-type-description declaration)
          constructible?
          (or (= :struct (:kind declaration))
              (contains? #{:struct :enum :union}
                         (get-in container-description [:options :kind])))
          target-module (:module declaration)
          qualified-name (symbol target-module (str (:name declaration)))]
      (when (and constructible?
                 (not (get-in @registry [target-module :types qualified-name])))
        (locking compile-lock
          (swap! registry update-in [target-module :jvm-type-declaration-keys]
                 (fnil conj #{}) (:declaration-key declaration)))
        (binding [*propagate-dependent-changes?* false]
          (recompile! target-module))
        (await-callable-generation! target-module)
        true))))

(defn materialize-type!
  "Construct a persistent native value from an ordinary callable Zig type Var."
  [declaration clojure-value]
  (let [module (:module declaration)
        type-name (:name declaration)
        qualified-name (symbol module (str type-name))]
    (await-callable-generation! module)
    (ensure-native-type-binding! module type-name)
    (let [{:keys [size-getter-handle align-getter-handle native-value-refs
                  wrapper-generation] :as binding}
          (get-in @registry [module :types qualified-name])
          _ (when-not binding
              (throw (ex-info "Zig type constructor is not loaded"
                              {:type qualified-name :module module})))
          schema (native-type-schema module type-name)
          _ (when-not schema
              (throw (ex-info "Clojure construction is not implemented for this Zig type"
                              {:type qualified-name
                               :layout (:layout declaration)})))
          size (long (.invokeWithArguments ^MethodHandle size-getter-handle
                                           (ArrayList.)))
          alignment
          (long (.invokeWithArguments ^MethodHandle align-getter-handle
                                      (ArrayList.)))
          arena (Arena/ofShared)
          segment (.allocate arena size alignment)
          closed? (atom false)
          close!
          (fn []
            (when (compare-and-set! closed? false true)
              (try (.close arena)
                   (finally
                     (.decrementAndGet ^AtomicLong native-value-refs)
                     (locking compile-lock
                       (retire-module-quiescent-generations! module))))))]
      (try
        (zig-value/write-value! segment type-name schema clojure-value arena)
        (let [schema
              (if (and (= :union (:kind schema))
                       (not (:tagged? schema))
                       (map? clojure-value)
                       (= 1 (count clojure-value)))
                (assoc schema :active-field
                       (keyword (clojure.core/name (ffirst clojure-value))))
                schema)]
        (.incrementAndGet ^AtomicLong native-value-refs)
        (let [native-value
              (zig-value/native-value
               {:module module
                :name type-name
                :kind :value
                :type type-name
                :logical-id (:logical-id declaration)}
               (constantly {:representation :native
                            :segment segment
                            :size size
                            :alignment alignment
                            :schema schema
                            :generation wrapper-generation
                            :close! close!}))]
          ;; Construction allocated the bytes eagerly, so register its Cleaner
          ;; immediately even if user code never dereferences the handle.
          (zig-value/value native-value)
          native-value))
        (catch Throwable error
          (.close arena)
          (throw error))))))

(defn materialize-constant!
  "Materialize the latest value of a non-literal Zig constant for a ZigValue.
  Scalar accessors return an exact JVM value. Other values retain their exact
  native byte representation and pin the owning dylib generation."
  [declaration]
  (let [module (:module declaration)
        qualified-name (symbol module (str (:name declaration)))]
    (await-callable-generation! module)
    (when-not (current-value-binding qualified-name)
      (locking compile-lock
        (swap! registry update-in [module :jvm-value-declaration-keys]
               (fnil conj #{}) (:declaration-key declaration)))
      (binding [*propagate-dependent-changes?* false]
        (recompile! module))
      (await-callable-generation! module))
    (when (and (not (contains? scalar-layouts (scalar-key (:type declaration))))
               (nil? (native-type-schema module (:type declaration))))
      (ensure-native-type-binding! module (:type declaration)))
    (let [{:keys [mode getter-handle address-getter-handle size-getter-handle
                  align-getter-handle native-value-refs wrapper-generation]
           :as binding}
          (current-value-binding qualified-name)]
      (when-not binding
        (throw (ex-info "Zig constant value accessor is not loaded"
                        {:constant qualified-name
                         :module module
                         :type (:type declaration)})))
      (if (= :scalar mode)
        {:representation :scalar
         :value (coerce-result
                 (:type declaration)
                 (.invokeWithArguments ^MethodHandle getter-handle
                                       (ArrayList.)))
         :generation wrapper-generation}
        (let [address (long (.invokeWithArguments
                             ^MethodHandle address-getter-handle
                             (ArrayList.)))
              size (long (.invokeWithArguments ^MethodHandle size-getter-handle
                                                (ArrayList.)))
              alignment
              (long (.invokeWithArguments ^MethodHandle align-getter-handle
                                          (ArrayList.)))
              _ (when (or (zero? address) (neg? size) (not (pos? alignment)))
                  (throw (ex-info "Zig constant returned invalid native storage"
                                  {:constant qualified-name
                                   :address address
                                   :size size
                                   :alignment alignment})))
              segment (.reinterpret (MemorySegment/ofAddress address) size)
              schema (or (native-optional-field-schema module #{} binding)
                         (native-slice-field-schema module #{} binding)
                         (native-error-union-field-schema module #{} binding)
                         (native-storage-binding-schema
                          module #{} (:type declaration)
                          (:nested-storage-binding binding))
                         (native-type-schema module (:type declaration)))
              closed? (atom false)
              close!
              (fn []
                (when (compare-and-set! closed? false true)
                  (.decrementAndGet ^AtomicLong native-value-refs)
                  (locking compile-lock
                    (retire-module-quiescent-generations! module))))]
          (.incrementAndGet ^AtomicLong native-value-refs)
          {:representation :native
           :segment segment
           :size size
           :alignment alignment
           :schema schema
           :generation wrapper-generation
           :close! close!})))))

(defn materialize-state!
  "Materialize the active native storage for a Zig defvar as a live ZigValue."
  [declaration]
  (let [{:keys [module logical-id type name]} declaration]
    (await-callable-generation! module)
    (when (and (storage-helper-type? type)
               (nil? (current-value-binding
                      (symbol module (str name)))))
      (locking compile-lock
        (swap! registry update-in [module :jvm-value-declaration-keys]
               (fnil conj #{}) (:declaration-key declaration)))
      (binding [*propagate-dependent-changes?* false]
        (recompile! module))
      (await-callable-generation! module))
    (when (nil? (native-type-schema module type))
      (ensure-native-type-binding! module type))
    (let [qualified-name (symbol module (str name))
          value-binding (current-value-binding qualified-name)
          state-version
          (locking compile-lock
            (some #(when (and (= logical-id (:logical-id %)) (:active? %)) %)
                  (get-in @registry [module :state-versions])))]
      (when-not (and state-version (:address state-version))
        (throw (ex-info "Zig state has no active native storage"
                        {:state (symbol module (str name))
                         :module module
                         :logical-id logical-id
                         :versions (state-versions
                                    (symbol module (str name)))})))
      (let [{:keys [address size alignment generation version-key]}
            state-version
            allocation-arena
            (locking compile-lock
              (or (get-in @registry
                          [module :jvm-state-backing-arenas version-key])
                  (let [arena (Arena/ofShared)]
                    (swap! registry assoc-in
                           [module :jvm-state-backing-arenas version-key]
                           arena)
                    arena)))
            schema
            (or (native-optional-field-schema module #{} value-binding)
                (native-slice-field-schema module #{} value-binding)
                (native-error-union-field-schema module #{} value-binding)
                (native-storage-binding-schema
                 module #{} type (:nested-storage-binding value-binding))
                (native-type-schema module type))
            [type-module type-name]
            (when (symbol? type)
              [(or (namespace type) module)
               (symbol (clojure.core/name type))])
            type-binding
            (when type-module
              (get-in @registry
                      [type-module :types
                       (symbol type-module (str type-name))]))
            owner-binding (or value-binding type-binding)
            native-value-refs (:native-value-refs owner-binding)
            owner-module (or (get-in owner-binding [:declaration :module]) module)
            closed? (atom false)
            close!
            (when native-value-refs
              (fn []
                (when (compare-and-set! closed? false true)
                  (.decrementAndGet ^AtomicLong native-value-refs)
                  (locking compile-lock
                    (retire-module-quiescent-generations! owner-module)))))]
        (when native-value-refs
          (.incrementAndGet ^AtomicLong native-value-refs))
        {:representation :native
         :segment (.reinterpret (MemorySegment/ofAddress (long address))
                                (long size))
         :size size
         :alignment alignment
         :schema (assoc schema :allocation-arena allocation-arena)
         :generation (or (:wrapper-generation owner-binding) generation)
         :close! close!}))))

(defn invoke!
  "Invoke the latest loaded generation of a scalar Zig Var. Non-exported
  declarations receive a cached development-only trampoline on first call."
  [function arguments]
  (let [qualified-name (qualified-function-name function)
        module (namespace qualified-name)
        _ (await-callable-generation! module)
        declaration
        (locking compile-lock
          (current-function-declaration (get @registry module)
                                        qualified-name))
        _ (doseq [zig-type (concat (map :type (:args declaration))
                                   [(:return declaration)])
                  :when (and zig-type
                             (not= :void zig-type)
                             (not (contains? scalar-layouts
                                             (scalar-key zig-type)))
                             (nil? (native-type-schema module zig-type)))]
            (ensure-native-type-binding! module zig-type))
        _ (materialize-jvm-callable! qualified-name)
        function-binding (acquire-function-binding! qualified-name arguments nil)]
    (invoke-binding! module function-binding arguments)))

(defn invoke-version!
  "Invoke a retained scalar ABI version of an exported Zig function. Obtain
  version fingerprints from `function-versions` or Var declaration metadata."
  [function abi-fingerprint arguments]
  (let [qualified-name (qualified-function-name function)
        module (namespace qualified-name)
        _ (await-callable-generation! module)
        function-binding
        (acquire-function-binding! qualified-name arguments abi-fingerprint)]
    (invoke-binding! module function-binding arguments)))

(def ^:private process-main-host-symbol "__aguafria_run_process_main")

(defn- process-main-host-source
  [host-module target-module entry-name]
  (str "// Generated by Aguafria's development process host.\n"
       "// Host module: " host-module "\n"
       "const std = @import(\"std\");\n"
       "const builtin = @import(\"builtin\");\n"
       "const application = @import(" (emit/emit-expr target-module) ");\n\n"
       "comptime {\n"
       "    if (builtin.os.tag == .windows) {\n"
       "        @compileError(\"Aguafria's process-main host does not yet encode Windows UTF-16 argv\");\n"
       "    }\n"
       "}\n\n"
       "export fn " process-main-host-symbol
       "(argc: usize, argv_pointer: [*]const [*:0]const u8, "
       "envc: usize, env_pointer: [*]const ?[*:0]const u8) callconv(.c) u8 {\n"
       "    const args: std.process.Args.Vector = argv_pointer[0..argc];\n"
       "    const environ: std.process.Environ.Block = .{\n"
       "        .slice = env_pointer[0..envc :null],\n"
       "    };\n"
       "    const gpa = std.heap.smp_allocator;\n\n"
       "    var arena_allocator = std.heap.ArenaAllocator.init(std.heap.page_allocator);\n"
       "    defer arena_allocator.deinit();\n\n"
       "    var threaded: std.Io.Threaded = .init(gpa, .{\n"
       "        .argv0 = .init(.{ .vector = args }),\n"
       "        .environ = .{ .block = environ },\n"
       "    });\n"
       "    defer threaded.deinit();\n\n"
       "    var environ_map = std.process.Environ.createMap(\n"
       "        .{ .block = environ }, gpa,\n"
       "    ) catch |err| {\n"
       "        std.log.err(\"failed to parse environment variables: {t}\", .{err});\n"
       "        return 1;\n"
       "    };\n"
       "    defer environ_map.deinit();\n\n"
       "    const preopens = std.process.Preopens.init(\n"
       "        arena_allocator.allocator(),\n"
       "    ) catch |err| {\n"
       "        std.log.err(\"failed to initialize process preopens: {t}\", .{err});\n"
       "        return 1;\n"
       "    };\n\n"
       "    application." entry-name "(.{\n"
       "        .minimal = .{\n"
       "            .args = .{ .vector = args },\n"
       "            .environ = .{ .block = environ },\n"
       "        },\n"
       "        .arena = &arena_allocator,\n"
       "        .gpa = gpa,\n"
       "        .io = threaded.io(),\n"
       "        .environ_map = &environ_map,\n"
       "        .preopens = preopens,\n"
       "    }) catch |err| {\n"
       "        std.log.err(\"application main failed: {t}\", .{err});\n"
       "        return 1;\n"
       "    };\n"
       "    return 0;\n"
       "}\n"))

(defn- process-main-host-declaration
  [host-module target-module]
  {:kind :const
   :name 'application
   :declaration-key [:host-import target-module]
   :module host-module
   :public? false
   :export? false
   :attributes {:zig/import-name target-module
                :zig/import-namespace (symbol target-module)
                ;; The synthetic host is only a wrapper. Build-generated
                ;; modules must be selected from the application root, just
                ;; as they are when that converted namespace is compiled.
                :zig/build-profile-owner target-module}})

(defn- find-required-symbol
  [^SymbolLookup lookup symbol-name data]
  (-> (.find lookup symbol-name)
      (.orElseThrow
       (reify java.util.function.Supplier
         (get [_]
           (ex-info "Aguafria native host symbol was not found"
                    (assoc data :symbol symbol-name)))))))

(defn- allocate-host-string-vector
  [^Arena arena arguments null-terminated?]
  (let [strings (mapv #(.allocateFrom arena ^String %) arguments)
        pointer-count (+ (count strings) (if null-terminated? 1 0))
        layout (MemoryLayout/sequenceLayout (long pointer-count)
                                            ValueLayout/ADDRESS)
        pointers (.allocate arena layout)]
    (doseq [[index string] (map-indexed vector strings)]
      (.setAtIndex ^MemorySegment pointers ValueLayout/ADDRESS
                   (long index) ^MemorySegment string))
    (when null-terminated?
      (.setAtIndex ^MemorySegment pointers ValueLayout/ADDRESS
                   (long (count strings)) MemorySegment/NULL))
    pointers))

(defn- copy-native-state!
  [source-address target-address size]
  (when (and (pos? (long size))
             (pos? (long source-address))
             (pos? (long target-address))
             (not= (long source-address) (long target-address)))
    (let [source (.reinterpret (MemorySegment/ofAddress (long source-address))
                               (long size))
          target (.reinterpret (MemorySegment/ofAddress (long target-address))
                               (long size))]
      (MemorySegment/copy source 0 target 0 (long size))))
  nil)

(defn- active-state-versions
  []
  (into {}
        (keep (fn [version]
                (when (:active? version)
                  [(:version-key version) version])))
        (mapcat :state-versions (vals @registry))))

(defn- promote-host-state!
  [host-id loaded]
  (let [active (active-state-versions)
        ownership
        (into {}
              (keep
               (fn [[version-key binding]]
                 (when-let [previous (get active version-key)]
                   (when-not (= (:size previous) (:size binding))
                     (throw
                      (ex-info "A native host state capsule changed size without a new schema"
                               {:aguafria/phase :native-host-state
                                :host-id host-id
                                :version-key version-key
                                :registered-size (:size previous)
                                :host-size (:size binding)})))
                   (copy-native-state! (:address previous) (:address binding)
                                       (:size binding))
                   [version-key
                    {:version-key version-key
                     :previous-address (:address previous)
                     :host-address (:address binding)
                     :size (:size binding)}])))
              (:state-bindings loaded))]
    (swap! registry
           (fn [modules]
             (into {}
                   (map
                    (fn [[module module-state]]
                      [module
                       (update module-state :state-versions
                               (fn [versions]
                                 (mapv
                                  (fn [version]
                                    (if-let [owned (get ownership
                                                        (:version-key version))]
                                      (if (:active? version)
                                        (assoc version
                                               :address (:host-address owned)
                                               :host-id host-id)
                                        version)
                                      version))
                                  versions)))])
                    modules))))
    ownership))

(defn- restore-host-state!
  [host-id ownership]
  (doseq [[_ {:keys [previous-address host-address size]}] ownership]
    (copy-native-state! host-address previous-address size))
  (swap! registry
         (fn [modules]
           (into {}
                 (map
                  (fn [[module module-state]]
                    [module
                     (update module-state :state-versions
                             (fn [versions]
                               (mapv
                                (fn [version]
                                  (if-let [{:keys [previous-address host-address]}
                                           (get ownership (:version-key version))]
                                    (if (and (= host-id (:host-id version))
                                             (= host-address (:address version)))
                                      (-> version
                                          (assoc :address previous-address)
                                          (dissoc :host-id))
                                      version)
                                    version))
                                versions)))])
                  modules))))
  nil)

(defn- host-id
  [host]
  (cond
    (integer? host) (long host)
    (map? host) (long (:id host))
    :else (throw (ex-info "Expected an Aguafria host id or handle"
                          {:host host :type (type host)}))))

(defn- host-view
  [host]
  (when host
    (-> (select-keys host [:id :function :module :arguments :status
                           :share-state? :stack-size-bytes
                           :replaces-host-id
                           :dispatch-frozen? :dispatch-frozen-reason
                           :requested-at-ms :started-at-ms :finished-at-ms
                           :duration-ms :exit-code :thread :source-path
                           :library-path :error])
        (assoc :active? (contains? #{:starting :running :quiescing}
                                   (:status host))))))

(defn start-process-main!
  "Start a Zig `std.process.Init` main function on a dedicated native host
  thread in this JVM. `arguments` excludes argv[0]; use `:argv0` to set it.

  `:stack-size-bytes` defaults to Zig's 16 MiB thread-stack default instead of
  the JVM's much smaller platform default. It may be raised for an application
  with larger native stack frames.

  The loaded host participates in Aguafria's atomic dispatch/state publication,
  so compatible Var edits are visible to the already-running program. Returns
  a serializable host handle immediately after native compilation/loading."
  ([function arguments] (start-process-main! function arguments {}))
  ([function arguments options]
   (when-not (and (sequential? arguments) (every? string? arguments))
     (throw (ex-info "Process-main arguments must be a sequence of strings"
                     {:arguments arguments})))
   (when-not (map? options)
     (throw (ex-info "Process-main host options must be a map"
                     {:options options})))
   (let [stack-size-value (or (:stack-size-bytes options)
                              default-native-host-stack-size-bytes)
         _ (when-not (and (integer? stack-size-value)
                          (<= (* 64 1024) stack-size-value Long/MAX_VALUE))
             (throw
              (ex-info
               "Process-main :stack-size-bytes must be an integer of at least 65536"
               {:stack-size-bytes stack-size-value
                :minimum-stack-size-bytes (* 64 1024)})))
         stack-size-bytes (long stack-size-value)
         qualified-name (qualified-function-name function)
         target-module (namespace qualified-name)
         _ (await-callable-generation! target-module)
         id (.incrementAndGet live-host-sequence)
         host-module (str "aguafria.host." id)
         requested-at (System/currentTimeMillis)
         argv (vec (cons (or (:argv0 options) (name qualified-name)) arguments))
         environment (->> (System/getenv)
                          (map (fn [[key value]] (str key "=" value)))
                          sort
                          vec)
         completion (promise)
         prepared
         (locking compile-lock
           (let [module-state (get @registry target-module)
                 declaration (current-function-declaration module-state qualified-name)]
             (when-not declaration
               (throw (ex-info "Zig process main is not registered"
                               {:function qualified-name
                                :module target-module})))
             (when-not (:public? declaration)
               (throw (ex-info "Zig process main must be public"
                               {:function qualified-name
                                :declaration (declaration-summary declaration)})))
             (when-not (and (= :fn (:kind declaration))
                            (= 1 (count (:args declaration)))
                            (= :void (:return declaration))
                            (str/includes? (or (:zig-qualifiers declaration) "") "!"))
               (throw
                (ex-info
                 "Native process hosting requires a `pub fn main(std.process.Init) !void` shape"
                 {:function qualified-name
                  :arguments (mapv :type (:args declaration))
                  :return (:return declaration)
                  :zig-qualifiers (:zig-qualifiers declaration)})))
             (when-not (= 8 (.byteSize ValueLayout/ADDRESS))
               (throw (ex-info "Aguafria process hosting currently requires a 64-bit JVM"
                               {:address-bytes (.byteSize ValueLayout/ADDRESS)})))
             (when (and (not= false (:share-state? options))
                        (some #(and (:share-state? %)
                                    (contains? #{:starting :running}
                                               (:status %)))
                              (vals @live-hosts)))
               (throw
                (ex-info
                 "Only one active native host can own Aguafria state capsules"
                 {:aguafria/phase :native-host-state
                  :active-hosts (->> @live-hosts vals
                                     (filter :share-state?)
                                     (mapv host-view))})))
             (let [host-declaration
                   (process-main-host-declaration host-module target-module)
                   declarations [host-declaration]
                   dependency-snapshot
                   (development-dependency-snapshot declarations)
                   source (process-main-host-source
                           host-module target-module
                           (emit/identifier (or (:zig-name declaration)
                                                (:name declaration))))
                   compiled (compile-source! host-module source declarations
                                             dependency-snapshot)
                   loaded (load-module
                           compiled [] {}
                           (dependency-dispatch-entries dependency-snapshot)
                           (dependency-state-entries dependency-snapshot))
                   linker ^Linker (:linker loaded)
                   lookup ^SymbolLookup (:lookup loaded)
                   descriptor
                   (FunctionDescriptor/of
                    ^MemoryLayout ValueLayout/JAVA_BYTE
                    (into-array MemoryLayout
                                [ValueLayout/JAVA_LONG ValueLayout/ADDRESS
                                 ValueLayout/JAVA_LONG ValueLayout/ADDRESS]))
                   run-handle
                   (.downcallHandle
                    linker
                    (find-required-symbol lookup process-main-host-symbol
                                          {:function qualified-name
                                           :library-path (:library-path compiled)})
                    descriptor (into-array Linker$Option []))
                   argument-arena (Arena/ofShared)
                   argv-pointer (allocate-host-string-vector
                                 argument-arena argv false)
                   environment-pointer (allocate-host-string-vector
                                        argument-arena environment true)
                   share-state? (not= false (:share-state? options))
                   owned-state (when share-state?
                                 (promote-host-state! id loaded))
                   record {:id id
                           :function (str qualified-name)
                           :module target-module
                           :arguments argv
                           :share-state? share-state?
                           :stack-size-bytes stack-size-bytes
                           :replaces-host-id (:replaces-host-id options)
                           :owned-state owned-state
                           :status :starting
                           :requested-at-ms requested-at
                           :source-path (:source-path compiled)
                           :library-path (:library-path compiled)
                           :completion completion
                           :loaded loaded
                           :argument-arena argument-arena
                           :argv-pointer argv-pointer
                           :environment-count (count environment)
                           :environment-pointer environment-pointer
                           :run-handle run-handle}]
               (swap! live-hosts assoc id record)
               ;; A running process is a coarse native execution lease: every
               ;; generation stays loaded until it returns. Avoiding two
               ;; atomic RMWs on every nested Var call keeps development hosts
               ;; fast without weakening ordinary REPL-call retirement.
               (set-all-active-call-tracking! false)
               (refresh-project-dispatch!)
               record)))
         runner
         (fn []
           (let [started-at (System/currentTimeMillis)
                 outcome (atom nil)]
             (locking compile-lock
               (swap! live-hosts update id assoc
                      :status :running
                      :started-at-ms started-at
                      :thread (.getName (Thread/currentThread))))
             (try
               (let [values (ArrayList. ^java.util.Collection
                                        [(long (count argv))
                                         (:argv-pointer prepared)
                                         (long (:environment-count prepared))
                                         (:environment-pointer prepared)])
                     result (.invokeWithArguments
                             ^MethodHandle (:run-handle prepared) values)
                     exit-code (bit-and 0xff (long result))
                     finished-at (System/currentTimeMillis)]
                 (reset! outcome
                         {:id id :status :finished :exit-code exit-code
                          :finished-at-ms finished-at
                          :duration-ms (- finished-at started-at)}))
               (catch Throwable error
                 (let [finished-at (System/currentTimeMillis)]
                   (reset! outcome
                           {:id id :status :failed :error error
                            :finished-at-ms finished-at
                            :duration-ms (- finished-at started-at)})))
               (finally
                 (try
                   (locking compile-lock
                     (swap! live-hosts update id assoc :status :quiescing)
                     (let [{:keys [loaded argument-arena owned-state]}
                           (get @live-hosts id)]
                       (try
                         (when (seq owned-state)
                           (restore-host-state! id owned-state))
                         (finally
                           (swap! live-hosts update id dissoc
                                  :loaded :argument-arena :argv-pointer
                                  :environment-pointer :environment-count
                                  :run-handle :owned-state)
                           (when (seq owned-state)
                             (refresh-project-dispatch!))
                           (when loaded (.close ^Arena (:arena loaded)))
                           (when argument-arena (.close ^Arena argument-arena))
                           (when-not (native-host-active?)
                             (set-all-active-call-tracking! true)
                             (when (seq @retirement-pending-modules)
                               (retire-pending-generations!))))))
                     (let [{:keys [status exit-code error finished-at-ms
                                   duration-ms]}
                           @outcome]
                       (swap! live-hosts update id assoc
                              :status status
                              :exit-code exit-code
                              :error (some-> error ex-message)
                              :finished-at-ms finished-at-ms
                              :duration-ms duration-ms)))
                   (catch Throwable cleanup-error
                     (let [finished-at (System/currentTimeMillis)]
                       (reset! outcome
                               {:id id :status :failed :error cleanup-error
                                :finished-at-ms finished-at
                                :duration-ms (- finished-at started-at)})
                       (locking compile-lock
                         (swap! live-hosts update id assoc
                                :status :failed
                                :error (ex-message cleanup-error)
                                :finished-at-ms finished-at
                                :duration-ms (- finished-at started-at)))))
                   (finally
                     ;; Completion means state ownership has returned to the
                     ;; JVM and every host arena is closed. This is the safe
                     ;; quiescent boundary for a structural replacement.
                     (deliver completion @outcome)))))))
         thread (doto (Thread. (.getThreadGroup (Thread/currentThread))
                               ^Runnable (reify Runnable
                                           (run [_] (runner)))
                               (str "aguafria-native-host-" id)
                               stack-size-bytes)
                  (.setDaemon true))]
     (.start thread)
     (host-view (get @live-hosts id)))))

(defn await-host!
  "Wait for a native process host. Throws an invocation failure and otherwise
  returns its final serializable status, including the process-style exit code."
  [host]
  (let [id (host-id host)
        completion (:completion (get @live-hosts id))]
    (when-not completion
      (throw (ex-info "Aguafria native host is not known"
                      {:host-id id :known-hosts (sort (keys @live-hosts))})))
    (let [{:keys [status error] :as outcome} @completion]
      (if (= :failed status)
        (throw error)
        ;; Lifecycle timestamps remain available through host-info/stats. Keep
        ;; the established process-style await result compact and compatible.
        (dissoc outcome :finished-at-ms)))))

(defn restart-process-main!
  "Wait for a native host to reach its natural quiescent boundary, after state
  ownership and native arenas have been restored/closed, then start the current
  registered generation in the same JVM process. Structural edits and explicit
  state migrations should be completed before calling this function (or after
  a separate `await-host!`). Options may override `:arguments`, `:argv0`,
  `:share-state?`, and `:stack-size-bytes`."
  ([host] (restart-process-main! host {}))
  ([host options]
   (when-not (map? options)
     (throw (ex-info "Native host restart options must be a map"
                     {:options options})))
   (let [id (host-id host)
         previous (get @live-hosts id)]
     (when-not previous
       (throw (ex-info "Aguafria native host is not known"
                       {:host-id id :known-hosts (sort (keys @live-hosts))})))
     ;; Completion is delivered only after cleanup, so returning from this wait
     ;; is the exact point at which a replacement may safely own state.
     (await-host! id)
     (let [previous (get @live-hosts id)
           previous-argv (vec (:arguments previous))
           arguments (if (contains? options :arguments)
                       (vec (:arguments options))
                       (vec (rest previous-argv)))
           start-options
           (-> options
               (dissoc :arguments)
               (assoc :argv0 (or (:argv0 options)
                                 (first previous-argv)
                                 (name (symbol (:function previous))))
                      :share-state?
                      (if (contains? options :share-state?)
                        (:share-state? options)
                        (:share-state? previous))
                      :stack-size-bytes
                      (if (contains? options :stack-size-bytes)
                        (:stack-size-bytes options)
                        (:stack-size-bytes previous))
                      :replaces-host-id id))]
       (start-process-main! (symbol (:function previous))
                            arguments start-options)))))

(defn host-info
  "Return serializable status for one native process host."
  [host]
  (host-view (get @live-hosts (host-id host))))

(defn host-stats
  "Return serializable status for all native process hosts, newest first."
  []
  (locking compile-lock
    (->> @live-hosts vals (sort-by :id >) (mapv host-view))))
