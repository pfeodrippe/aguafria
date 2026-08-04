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
           [java.util ArrayList HexFormat IdentityHashMap]
           [java.util.concurrent ExecutionException ExecutorService Executors
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
(defonce ^:private external-file-fingerprints (atom {}))
(defonce ^:private declaration-reference-index
  (atom {:by-module {} :by-logical {} :references {} :revision 0}))
(defonce ^:private development-linkage-closure-cache (atom {}))
(defonce ^:private development-linkage-snapshot-cache (atom {}))
(def ^:private module-source-cache-entry-limit 64)
(def ^:private module-source-cache-weight-limit (* 32 1024 1024))
(def ^:private empty-module-source-cache
  {:entries {}
   :order []
   :weight-chars 0
   :hit-count 0
   :miss-count 0
   :eviction-count 0
   :oversized-count 0})
(defonce ^:private module-source-cache (atom empty-module-source-cache))
(def ^:dynamic *preserve-source-fingerprint?* false)
(defonce ^IdentityHashMap development-dependency-entry-cache
  (IdentityHashMap.))
(defonce ^IdentityHashMap module-dependency-entry-cache
  (IdentityHashMap.))
(defonce ^:private dependency-topology-cache
  (atom {:graph nil :topology nil}))
(defonce ^:private retirement-pending-modules (atom #{}))
(defonce ^:private retirement-thread-sequence (AtomicLong. 0))
(defonce ^ExecutorService generation-retirement-executor
  (Executors/newSingleThreadExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable
                      (str "aguafria-generation-retirement-"
                           (.incrementAndGet retirement-thread-sequence)))
         (.setDaemon true))))))
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

(def ^:dynamic *force-recompile?* false)

(def ^:dynamic ^:no-doc *publication-plan-capture*
  "Optional Atom receiving a delayed inspectable plan for benchmark tooling."
  nil)

(def ^:dynamic *source-only-registration?*
  "When true, declaration macros update complete module source without asking
  Zig for a development library. Standalone build tooling binds this while it
  loads a project, then performs one final program build."
  false)

(def ^:dynamic *propagate-dependent-changes?*
  "Internal publication policy. Ordinary declaration edits propagate; a
  development-only JVM-call trampoline does not change Zig behavior and must
  not rebuild otherwise-unchanged dependents."
  true)

(def ^:dynamic *exact-declaration-publication?*
  "Internal propagation mode: publish only the selected declaration's live
  slice even when other declarations in its namespace also observe the same
  upstream identity change. Each selected dependent is registered explicitly."
  false)

(def ^:dynamic *materialize-declaration-key*
  "When non-nil, compile the exact live slice for a Clojure-demanded Var.
  Registration still retains the complete namespace descriptor/source graph;
  only this native publication is deliberately partial."
  nil)

(def ^:dynamic *file-load-registration?*
  "True only while a declaration is being registered by Clojure's file
  loader. File loads debounce/coalesce; an individual REPL form starts now."
  false)

(declare register-batch! recompile-component!)

(declare declaration-info declaration-type-value
         materialize-constant! materialize-state!
         materialize-type!
         native-error-union-field-schema native-optional-field-schema
         native-slice-field-schema native-storage-binding-schema
         native-type-schema
         scalar-key scalar-layouts)

(defn- referenced-declaration
  [context-module reference]
  (when (symbol? reference)
    (let [zig-reference (-> reference meta :aguafria/zig-reference)
          target-symbol (:symbol zig-reference)
          target-module (or (:module zig-reference)
                            (some-> target-symbol namespace)
                            (namespace reference)
                            context-module)
          target-name (or (some-> target-symbol name)
                          (name reference))]
      (some #(when (= target-name (str (:name %))) %)
            (vals (get-in @registry [target-module :definitions]))))))

(defn- type-factory-call
  [{:keys [kind module value]}]
  (when (and (= :const kind) (seq? value) (symbol? (first value)))
    (let [factory (referenced-declaration module (first value))]
      (when (and (= :fn (:kind factory)) (= :type (:return factory)))
        {:factory factory :arguments (vec (rest value))}))))

(defn- returned-type-form
  [body]
  (let [candidate (last body)]
    (cond
      (and (seq? candidate)
           (contains? #{"return" "comptime"} (name (first candidate))))
      (second candidate)

      (and (seq? candidate)
           (contains? #{"do" "block"} (name (first candidate))))
      (returned-type-form (rest candidate))

      :else candidate)))

(defn- container-type-form
  [{:keys [kind module value] :as declaration}]
  (when (= :const kind)
    (or
     (when (and (seq? value) (symbol? (first value))
                (= "container" (name (first value))))
       value)
     (when-let [{:keys [factory arguments]} (type-factory-call declaration)]
       (let [parameters (mapv :name (:args factory))]
         (when (= (count parameters) (count arguments))
           (let [returned (walk/postwalk-replace
                           (zipmap parameters arguments)
                           (returned-type-form (:body factory)))]
             (when (and (seq? returned) (symbol? (first returned))
                        (= "container" (name (first returned))))
               returned))))))))

(defn- container-type-description
  [declaration]
  (when-let [form (container-type-form declaration)]
    (emit/container-description
     (or (some-> (:module declaration) symbol find-ns) *ns*)
     form)))

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
         :zig-args-by-module {}
         :modules {}
         :module-cache-tokens {}
         :build-history-limit 100
         :compile-debounce-ms 75
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
  `:optimize`, `:target`, `:cpu`, `:zig-args`, `:zig-args-by-module`, `:modules`,
  `:module-dependencies`, `:module-zig-args`, `:module-cache-tokens`, and
  `:async?`. `:modules` maps
  Zig import names to root source paths. `:module-dependencies` maps one
  configured module name to the other configured module names visible through
  its local `@import` table. `:module-zig-args` supplies compiler arguments
  scoped to one `-M` module (for example a C header include path).
  `:zig-args-by-module` supplies global compiler/linker arguments only when
  that module is actually present in the compilation slice.
  `:module-cache-tokens` maps configured modules to immutable version/content
  identities, allowing artifacts that select them to be reused safely.
  Returns the resulting configuration."
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
  (when-let [arguments-by-module (:zig-args-by-module options)]
    (when-not
     (and (map? arguments-by-module)
          (every? #(or (string? %) (symbol? %) (keyword? %))
                  (keys arguments-by-module))
          (every? #(and (sequential? %) (every? string? %))
                  (vals arguments-by-module)))
      (throw
       (ex-info ":zig-args-by-module must map module names to argument sequences"
                {:value arguments-by-module}))))
  (when-let [modules (:modules options)]
    (when-not (and (map? modules)
                   (every? #(or (string? %) (symbol? %) (keyword? %))
                           (keys modules))
                   (every? #(or (string? %) (instance? File %)) (vals modules)))
      (throw (ex-info ":modules must map import names to Zig source paths"
                      {:value modules}))))
  (when-let [cache-tokens (:module-cache-tokens options)]
    (when-not
     (and (map? cache-tokens)
          (every? #(or (string? %) (symbol? %) (keyword? %))
                  (keys cache-tokens))
          (every? some? (vals cache-tokens)))
      (throw
       (ex-info ":module-cache-tokens must map module names to non-nil identities"
                {:value cache-tokens}))))
  (when-let [dependencies (:module-dependencies options)]
    (when-not
     (and (map? dependencies)
          (every? #(or (= :root %)
                       (string? %)
                       (symbol? %)
                       (keyword? %))
                  (keys dependencies))
          (every? #(and (sequential? %)
                        (every? (fn [dependency]
                                  (or (string? dependency)
                                      (symbol? dependency)
                                      (keyword? dependency)))
                                %))
                  (vals dependencies)))
      (throw
       (ex-info
        ":module-dependencies must map module names to module-name sequences"
        {:value dependencies}))))
  (when-let [module-arguments (:module-zig-args options)]
    (when-not
     (and (map? module-arguments)
          (every? #(or (string? %) (symbol? %) (keyword? %))
                  (keys module-arguments))
          (every? #(and (sequential? %) (every? string? %))
                  (vals module-arguments)))
      (throw
       (ex-info ":module-zig-args must map module names to argument sequences"
                {:value module-arguments}))))
  (when-let [history-limit (:build-history-limit options)]
    (when-not (and (integer? history-limit) (pos? history-limit))
      (throw (ex-info ":build-history-limit must be a positive integer"
                      {:value history-limit}))))
  (when-let [debounce-ms (:converted-compile-debounce-ms options)]
    (when-not (and (integer? debounce-ms) (not (neg? debounce-ms)))
      (throw (ex-info ":converted-compile-debounce-ms must be a non-negative integer"
                      {:value debounce-ms}))))
  (when-let [debounce-ms (:compile-debounce-ms options)]
    (when-not (and (integer? debounce-ms) (not (neg? debounce-ms)))
      (throw (ex-info ":compile-debounce-ms must be a non-negative integer"
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
      (reset! external-file-fingerprints {})
      (reset! declaration-reference-index
              {:by-module {} :by-logical {} :references {} :revision 0})
      (reset! development-linkage-closure-cache {})
      (reset! development-linkage-snapshot-cache {})
      (reset! module-source-cache empty-module-source-cache)
      (locking development-dependency-entry-cache
        (.clear development-dependency-entry-cache))
      (locking module-dependency-entry-cache
        (.clear module-dependency-entry-cache))
      (reset! dependency-topology-cache {:graph nil :topology nil})
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

(defn- elapsed-nanos-ms
  [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1000000.0))

(defn- record-build!
  [{:keys [module generation declarations planning-duration-ms]} async?]
  (let [now (System/currentTimeMillis)
        record (cond->
                {:module module
                 :generation generation
                 :purpose :repl-shared-library
                 :status :queued
                 :async? async?
                 :requested-at-ms now
                 :declarations (mapv declaration-summary declarations)}
                 planning-duration-ms
                 (assoc :planning-duration-ms planning-duration-ms))]
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
                                  :full-compile-error
                                  :compiled-declaration-count
                                  :native-duration-ms
                                  :planning-duration-ms
                                  :dependency-preparation-duration-ms
                                  :compiler-duration-ms
                                  :dynamic-load-duration-ms
                                  :publication-duration-ms
                                  :propagation-duration-ms]))))))

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

(defn- file-sha256
  [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes (.toPath file)))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- external-argument-fingerprint
  [argument]
  (let [file (io/file argument)]
    (if-not (.isFile file)
      [:argument argument]
      (let [path (.getCanonicalPath file)
            size (.length file)
            modified (.lastModified file)
            cache-key [path size modified]
            fingerprint
            (or (get @external-file-fingerprints cache-key)
                (let [digest (file-sha256 file)]
                  (swap! external-file-fingerprints
                         (fn [cached]
                           (assoc
                            (into {}
                                  (remove (fn [[[cached-path] _]]
                                            (= path cached-path)))
                                  cached)
                            cache-key digest)))
                  digest))]
        [:file path size modified fingerprint]))))

(def ^:private identity-reference-fingerprint-keys
  [:kind :module :zig-name :import-name :import-alias :logical-id])

(def ^:private source-reference-fingerprint-keys
  [:kind :module :zig-name :import-name :import-alias :import-namespace
   :source-order :logical-id :type-reference? :state-accessor])

(defn- canonical-fingerprint-value
  ([value]
   (canonical-fingerprint-value value identity-reference-fingerprint-keys))
  ([value reference-keys]
   (cond
     (symbol? value)
     (let [reference (:aguafria/zig-reference (meta value))]
       (cond-> [:symbol (str value)]
         (:zig/name (meta value))
         (conj [:zig/name (:zig/name (meta value))])

         reference
         (conj [:zig/reference (select-keys reference reference-keys)])))

     (keyword? value) [:keyword (namespace value) (name value)]

     (map? value)
     [:map
      (->> value
           (map (fn [[key nested]]
                  [(canonical-fingerprint-value key reference-keys)
                   (canonical-fingerprint-value nested reference-keys)]))
           (sort-by (comp pr-str first))
           vec)]

     (set? value)
     [:set (->> value
                (map #(canonical-fingerprint-value % reference-keys))
                (sort-by pr-str)
                vec)]

     (vector? value)
     [:vector (mapv #(canonical-fingerprint-value % reference-keys) value)]

     (seq? value)
     [:list (mapv #(canonical-fingerprint-value % reference-keys) value)]

     :else value)))

(defn- data-fingerprint
  [value]
  (sha256 (pr-str (canonical-fingerprint-value value))))

(defn- source-data-fingerprint
  [value]
  (sha256 (pr-str (canonical-fingerprint-value
                   value source-reference-fingerprint-keys))))

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

(defn- type-dependency-shapes
  [declaration]
  (->> (:type-dependency-fingerprints declaration)
       (keep (fn [[logical-id _schema-fingerprint
                   _implementation-fingerprint shape-fingerprint]]
               (when (and logical-id
                          (not= logical-id (:logical-id declaration)))
                 [logical-id shape-fingerprint])))
       distinct
       (sort-by pr-str)
       vec))

(defn- struct-shape
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

(defn- struct-schema
  [declaration]
  {:shape (struct-shape declaration)
   :type-dependencies (type-dependency-shapes declaration)})

(defn- state-schema
  [{:keys [type] :as declaration}]
  {:kind :state-schema
   ;; Inferred Zig globals are verified again from exported @sizeOf/@alignOf
   ;; data when loaded. Their initializer is implementation, not layout,
   ;; otherwise changing `0` to `1` would spuriously demand a migration.
   :type (or type :zig-inferred)
   ;; A named type is not a complete storage identity: its Var can acquire a
   ;; new struct/container layout while retaining the same Zig name. Include
   ;; the referenced type shapes so that state migration is requested before
   ;; any new-size storage is published or reinterpreted.
   :type-dependencies (type-dependency-shapes declaration)})

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
  [declaration]
  (boolean (container-type-form declaration)))

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
  (let [logical-id [(str module) kind (declaration-zig-name declaration)]
        declaration (assoc declaration :logical-id logical-id)
        type-shape
        (cond
          (= :struct kind)
          (struct-shape declaration)

          (container-type-declaration? declaration)
          {:kind :container-type
           :symbol (declaration-zig-name declaration)
           :schema (container-value-schema
                    (container-type-form declaration))}

          (type-factory-declaration? declaration)
          {:kind :type-factory
           :symbol (declaration-zig-name declaration)
           :arguments (mapv #(select-keys % [:type :properties])
                            (:args declaration))
           :shape (type-factory-schema-value (:body declaration))})
        declaration
        (cond-> declaration
          type-shape
          (assoc :shape-fingerprint (data-fingerprint type-shape)))]
    (let [declaration
          (cond-> declaration
            (contains? #{:fn :fn-proto} kind)
            (assoc :abi-fingerprint (data-fingerprint (callable-abi declaration))
                   :implementation-fingerprint
                   (data-fingerprint
                    (select-keys declaration
                                 [:kind :name :zig-name :args :return :body
                                  :export? :public? :zig-prefix :zig-qualifiers
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
                    {:shape type-shape
                     :type-dependencies (type-dependency-shapes declaration)})
                   ;; The schema deliberately excludes nested method bodies,
                   ;; but a compatible method edit still changes every
                   ;; monomorphization that copied that method at comptime.
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
                    {:shape type-shape
                     :type-dependencies (type-dependency-shapes declaration)}))

            declaration-key
            (assoc :logical-key declaration-key))]
      ;; Emission identity intentionally includes docs, source mapping, value
      ;; forms, body forms, and emission-relevant reference metadata. Computing
      ;; it once at registration makes later source-plan cache lookups cheap
      ;; without treating symbol metadata as disposable.
      (assoc declaration
             :source-fingerprint
             (if (and *preserve-source-fingerprint?*
                      (:source-fingerprint declaration))
               (:source-fingerprint declaration)
               (source-data-fingerprint
                ;; These fields describe invalidation/history, not emitted Zig
                ;; text. Body/value/signature/schema/source fields and the
                ;; emission-relevant symbol metadata remain in the identity.
                (dissoc declaration
                        :source-fingerprint
                        :implementation-fingerprint
                        :type-dependency-fingerprints
                        :callable-dependency-fingerprints
                        :clojure-form
                        :logical-key)))))))

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
              (re-matches #"^\s*// Clojure source: (.*):(\d+):(\d+)$" line)]
       (merge location
              {:file file
               :line (parse-long source-line)
               :column (parse-long source-column)})
       (if-let [[_ declaration]
                (re-matches #"^\s*// Aguafria declaration: (.+)$" line)]
         (assoc location :declaration declaration)
         (if-let [[_ form-line form-column]
                  (re-matches #"^\s*// Clojure form: (\d+)(?::(\d+))?$" line)]
           (assoc location
                  :line (parse-long form-line)
                  :column (some-> form-column parse-long))
           location))))
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

(defn- same-file?
  [left right]
  (try
    (= (.getCanonicalFile (io/file left))
       (.getCanonicalFile (io/file right)))
    (catch Exception _
      (= (str left) (str right)))))

(defn- diagnostic-source
  [root-source root-source-path generated-file]
  (if (same-file? root-source-path generated-file)
    root-source
    (let [file (io/file generated-file)]
      (when (.isFile file)
        (slurp file)))))

(defn- enrich-zig-diagnostic
  [root-source root-source-path diagnostic]
  (let [{generated-file :file generated-line :line} diagnostic
        generated-source (diagnostic-source root-source root-source-path
                                            generated-file)
        location (when generated-source
                   (source-location-at generated-source generated-line))]
    (cond-> (assoc diagnostic :generated-source generated-source)
      location (assoc :aguafria/source location))))

(defn- format-zig-diagnostic
  [diagnostic]
  (let [{generated-file :file generated-line :line generated-column :column
         :keys [severity message generated-source]} diagnostic
        {:keys [file line column declaration]} (:aguafria/source diagnostic)
        clojure-line (existing-source-line file line)
        generated-source-line (or (when generated-source
                                    (line-at generated-source generated-line))
                                  (existing-source-line generated-file generated-line))]
    (str (name severity) "[aguafria::zig]: " message "\n"
         (when file
           (str "  --> " file
                (when line (str ":" line))
                (when column (str ":" column)) "\n"
                (code-frame line column clojure-line
                            "this Clojure form generated the failing Zig")))
         (when declaration
           (str "  = Aguafria declaration: " declaration "\n"))
         "  ::: " generated-file ":" generated-line ":" generated-column "\n"
         (code-frame generated-line generated-column generated-source-line
                     "Zig reported the error here"))))

(defn- pretty-zig-error
  [module source source-path command stderr]
  (let [diagnostics (mapv #(enrich-zig-diagnostic source source-path %)
                          (parse-zig-diagnostics stderr))
        rendered (if (seq diagnostics)
                   (str/join "\n" (map format-zig-diagnostic diagnostics))
                   (str "error[aguafria::zig]: Zig compilation failed without a location\n"))]
    {:diagnostics diagnostics
     :message
     (str "Zig compilation failed for " module "\n\n"
          rendered
          "\n  = generated module: " source-path
          "\n  = compiler command: " (str/join " " (take 2 command))
          " ... (" (count command) " arguments; full vector is in ex-data :command)"
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
    ;; This is the public/static source used by `az/source`, standalone
    ;; builds, and materialization. Named containers and dispatch machinery
    ;; belong exclusively to reload/dependency compiler variants.
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
  ([module declarations dispatch-specs state-specs]
   (emit-reload-source! module declarations dispatch-specs state-specs nil))
  ([module declarations dispatch-specs state-specs extra-body-source]
  (if (:reloadable? @config)
    (try
      (emit/emit-reloadable-module module declarations dispatch-specs
                                   state-specs
                                   {:extra-body-source extra-body-source})
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
    (emit-source! module declarations))))

(defn- emit-dependency-reload-source!
  ([module declarations dispatch-specs state-specs]
   (emit-dependency-reload-source! module declarations dispatch-specs
                                   state-specs #{}))
  ([module declarations dispatch-specs state-specs
    linkable-declaration-keys]
   (if (:reloadable? @config)
     (emit/emit-reloadable-module module declarations dispatch-specs state-specs
                                  {:dependency? true
                                   :linkable-declaration-keys
                                   (set linkable-declaration-keys)})
     (emit/emit-dependency-module module declarations))))

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
                       module-dependencies module-zig-args]}]
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
          (get module-zig-args (str module-name))
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
  (let [source (project/localize-module-assets module source)
        source-hash (subs (sha256 source) 0 24)
        directory (io/file (:cache-dir @config) "dependencies"
                           (safe-path-component module) source-hash)
        source-file (io/file directory "module.zig")]
    (.mkdirs directory)
    ;; The directory name is the source digest, so an existing file is already
    ;; the immutable requested variant. Avoid rereading multi-megabyte
    ;; generated modules on every cached REPL publication.
    (when-not (.isFile source-file)
      (Files/writeString (.toPath source-file) source StandardCharsets/UTF_8
                         (into-array StandardOpenOption
                                     [StandardOpenOption/CREATE
                                      StandardOpenOption/TRUNCATE_EXISTING
                                      StandardOpenOption/WRITE])))
    (project/materialize-module-assets! module source directory)
    (.getAbsolutePath source-file)))

(defn- ensure-static-module-source!
  [module]
  (let [module (str module)]
    (locking compile-lock
      (let [module-state (get @registry module)]
        (when (and module-state
                   (or (:source-dirty? module-state)
                       (not (string? (:source module-state)))))
          (let [declarations (vec (vals (:definitions module-state)))
                source (emit-source! module declarations)]
            (swap! registry update module assoc
                   :declarations declarations
                   :source source
                   :source-dirty? false)))
        (get @registry module)))))

(defn- materialize-registered-module-source!
  [module development-dependencies?]
  (let [{:keys [source reload-source dependency-source] :as module-state}
        (ensure-static-module-source! module)
        source (if development-dependencies?
                 (or dependency-source reload-source source)
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

(defn- development-dependency-entry
  [module module-state direct-dependencies]
  (when module-state
    (locking development-dependency-entry-cache
      (or (.get development-dependency-entry-cache module-state)
          (let [definitions (:definitions module-state)
                declarations (vals definitions)
                dependencies (direct-dependencies declarations)
                specs (or (:dependency-dispatch-specs module-state)
                          (:reload-source-dispatch-specs module-state)
                          (:dispatch-specs module-state)
                          (reloadable-dispatch-specs declarations))
                state-specs (reloadable-state-specs declarations)
                source (or (when-not (:source-dirty? module-state)
                             (:dependency-source module-state))
                           ;; Every converted dependency is selected through
                           ;; its stable namespace container.  The ordinary
                           ;; source remains the compiler root/inspection form;
                           ;; exact referenced Vars replace this dependency
                           ;; variant with reloadable dispatch below.
                           (when (seq declarations)
                             (emit/emit-dependency-module module declarations)))
                entries
                (->> specs
                     (keep (fn [[declaration-key spec]]
                             (when-let [declaration
                                        (get definitions declaration-key)]
                               {:declaration declaration
                                :spec spec
                                :owned? false})))
                     vec)
                state-entries
                (->> state-specs
                     (keep (fn [[declaration-key spec]]
                             (when-let [declaration
                                        (get definitions declaration-key)]
                               {:declaration declaration
                                :spec spec
                                :owned? false})))
                     vec)
                entry {:module module
                       :cache-token (Object.)
                       :source source
                       :source-fingerprint
                       (when source (subs (sha256 source) 0 24))
                       :dependencies dependencies
                       :named-module-imports
                       (declaration-named-module-imports declarations)
                       :dispatch-entries entries
                       :state-entries state-entries}]
            ;; Module-state maps are immutable identities. A different source,
            ;; definition, or dispatch publication necessarily creates a new
            ;; map and therefore a cache miss without deep comparisons.
            (when (> (.size development-dependency-entry-cache) 4096)
              (.clear development-dependency-entry-cache))
            (.put development-dependency-entry-cache module-state entry)
            entry)))))

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
                {:keys [source dependencies named-module-imports
                        dispatch-entries state-entries]}
                (development-dependency-entry module module-state
                                              direct-dependencies)
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
                                 :named-module-imports named-module-imports
                                 :dispatch-entries dispatch-entries
                                 :state-entries state-entries}))]
            (recur (concat (next pending) dependencies)
                   (conj seen module)
                   snapshot)))
        snapshot)))))

(defn- static-dependency-snapshot
  "Capture the complete ordinary Zig module graph for a standalone build.
  Unlike development dependencies, these sources contain no dispatch cells or
  JVM-call wrappers, so optimizer-visible calls remain normal Zig calls."
  [declarations]
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
           seen (cond-> #{} root-module (conj root-module))
           snapshot (sorted-map)]
      (if-let [module (first pending)]
        (if (contains? seen module)
          (recur (next pending) seen snapshot)
          (let [module-state (ensure-static-module-source! module)
                module-declarations (vec (vals (:definitions module-state)))
                dependencies (direct-dependencies module-declarations)
                dependency-source
                (emit/emit-static-dependency-module module module-declarations)]
            (recur
             (concat (next pending) dependencies)
             (conj seen module)
             (cond-> snapshot
               (string? dependency-source)
               (assoc module
                      {:module module
                       :source dependency-source
                       :dependencies dependencies
                       :named-module-imports
                       (declaration-named-module-imports module-declarations)
                       :dispatch-entries []
                       :state-entries []})))))
        snapshot))))

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

(defn- declaration-reference-logical-ids
  [declaration]
  (let [same-module-by-name
        (get-in @declaration-reference-index
                [:by-module (:module declaration) :by-name])]
    (into
     (into #{}
           (map first)
           (concat (:type-dependency-fingerprints declaration)
                   (:callable-dependency-fingerprints declaration)))
     (keep (fn [value]
             (when (symbol? value)
               (or (get-in (meta value)
                           [:aguafria/zig-reference :logical-id])
                   ;; Same-file Zig calls and forward references are commonly
                   ;; stored as plain unqualified symbols. Resolve them
                   ;; against callable declarations in the namespace so an
                   ;; exact live slice retains their dispatch hooks instead
                   ;; of relying on a whole-module build. State references
                   ;; require explicit Var metadata: treating every symbol in
                   ;; a lazy container method as live state would force
                   ;; target-disabled globals that Zig intentionally never
                   ;; analyzes (for example TigerBeetle testing marks).
                   (when (nil? (namespace value))
                     (let [candidate (get same-module-by-name (name value))]
                       (when (contains? #{:fn :fn-proto} (:kind candidate))
                         (:logical-id candidate))))))))
     (tree-seq coll? seq declaration))))

(declare registered-declarations-by-logical-id)

(defn- development-linkage-logical-ids
  "Return the exact transitive declaration closure embedded by a live slice.

  A converted module graph can contain thousands of source namespaces while
  Zig lazily analyzes only a handful. Retaining hooks outside this closure
  would both defeat that laziness and force target-specific declarations that
  the real program never reaches."
  [root-declarations]
  (let [by-logical-id (registered-declarations-by-logical-id)
        index-state @declaration-reference-index
        indexed-references (:references index-state)
        root-reference-pairs
        (mapv (fn [declaration]
                [(:logical-id declaration)
                 (declaration-reference-logical-ids declaration)])
              root-declarations)
        root-references
        (into {} (keep (fn [[logical-id references]]
                         (when logical-id [logical-id references])))
              root-reference-pairs)
        ;; Synthetic program/host wrappers are compiler roots rather than
        ;; user Vars and deliberately have no logical id. Their explicit edge
        ;; to the real entry point must still seed the transitive closure.
        anonymous-root-references
        (into #{}
              (mapcat (fn [[logical-id references]]
                        (when-not logical-id references)))
              root-reference-pairs)
        cache-key [(:revision index-state) root-references
                   anonymous-root-references]]
    (if-some [cached (get @development-linkage-closure-cache cache-key)]
      cached
      (let [result
            (loop [pending (concat anonymous-root-references
                                   (mapcat val root-references))
                   seen #{}]
              (if-let [logical-id (first pending)]
                (if (contains? seen logical-id)
                  (recur (next pending) seen)
                  (let [declaration (get by-logical-id logical-id)
                        references
                        (or (get root-references logical-id)
                            (get indexed-references logical-id)
                            (when declaration
                              (declaration-reference-logical-ids declaration)))]
                    (recur (concat (next pending) references)
                           (conj seen logical-id))))
                seen))]
        (swap! development-linkage-closure-cache
               (fn [cached]
                 (let [cached (if (> (count cached) 2048) {} cached)]
                   (assoc cached cache-key result))))
        result))))

(defn- linkage-entry?
  [logical-ids {:keys [declaration]}]
  (contains? logical-ids (:logical-id declaration)))

(defn- live-state-entry?
  "True when a state Var owns native storage that another live slice must use.

  Converted source-only globals deliberately have no state generation yet.
  Linking their helpers would defeat Zig's lazy analysis and can force
  compile-time-disabled declarations whose target type is intentionally
  `void`."
  [{:keys [declaration]}]
  (let [{:keys [module logical-id]} declaration]
    (boolean
     (some #(and (:active? %)
                 (= logical-id (:logical-id %)))
           (get-in @registry [(str module) :state-versions])))))

(defn- development-linkage-modules
  "Return exact transitive modules whose embedded dispatch/state hooks must
  remain reachable from the development loader despite Zig's lazy analysis."
  [dependency-snapshot logical-ids]
  (->> dependency-snapshot
       (keep (fn [[module {:keys [dispatch-entries state-entries]}]]
               (when (or (some #(linkage-entry? logical-ids %)
                               dispatch-entries)
                         (some #(linkage-entry? logical-ids %)
                               state-entries))
                 (str module))))
       sort
       vec))

(defn- compute-linkable-development-dependency-snapshot
  "Materialize public linkage helpers only for exact referenced Vars.

  The ordinary dependency source keeps helpers private so merely supplying a
  large converted module graph to Zig does not make it analyze every helper.
  A live slice gets a content-addressed source variant for the handful of
  declarations whose cells the outer artifact must actually expose to FFM."
  [dependency-snapshot logical-ids]
  (reduce-kv
   (fn [snapshot module {:keys [dispatch-entries state-entries] :as entry}]
     (let [linkable-dispatch-entries
           (vec (filter #(linkage-entry? logical-ids %) dispatch-entries))
           linkable-state-entries
           (vec (filter #(and (linkage-entry? logical-ids %)
                              (live-state-entry? %))
                        state-entries))
           linkable-entries
           (concat linkable-dispatch-entries linkable-state-entries)
           linkable-keys
           (into #{} (map (comp :declaration-key :declaration))
                 linkable-entries)]
       (if (empty? linkable-keys)
         ;; Capture an immutable empty linkage decision for this compilation.
         ;; A concurrent publication must not make the loader retain helpers
         ;; that this source snapshot did not expose.
         (assoc snapshot module
                (assoc entry :dispatch-entries [] :state-entries []))
         (let [declarations
               (vec (vals (get-in @registry [(str module) :definitions])))
               dispatch-specs
               (into {}
                     (map (fn [{:keys [declaration spec]}]
                            [(:declaration-key declaration) spec]))
                     linkable-dispatch-entries)
               state-specs
               (into {}
                     (map (fn [{:keys [declaration spec]}]
                            ;; State references throughout this source still
                            ;; use their stable accessor. The emitter exposes
                            ;; native layout/linkage helpers only for exact
                            ;; live state keys, so inactive Vars remain lazy.
                            [(:declaration-key declaration) spec]))
                     state-entries)]
           (assoc snapshot module
                  (assoc entry
                         :source
                         (emit-dependency-reload-source!
                          module declarations dispatch-specs state-specs
                          linkable-keys)
                         :dispatch-entries linkable-dispatch-entries
                         :state-entries linkable-state-entries))))))
   (or (empty dependency-snapshot) (sorted-map))
   dependency-snapshot))

(defn- linkable-development-dependency-snapshot
  [dependency-snapshot logical-ids]
  (let [cache-key
        [(mapv (fn [[module entry]]
                 (let [linkable-dispatch
                       (->> (:dispatch-entries entry)
                            (filter #(linkage-entry? logical-ids %))
                            (mapv (comp :version-key :spec)))
                       linkable-state
                       (->> (:state-entries entry)
                            (filter #(and (linkage-entry? logical-ids %)
                                          (live-state-entry? %)))
                            (mapv (comp :version-key :spec)))]
                   [module
                    (or (:source-fingerprint entry)
                        (some-> (:source entry) sha256 (subs 0 24)))
                    (:dependencies entry)
                    (:named-module-imports entry)
                    linkable-dispatch
                    linkable-state]))
               dependency-snapshot)
         logical-ids]]
    (if-some [cached (get @development-linkage-snapshot-cache cache-key)]
      cached
      (let [result
            (compute-linkable-development-dependency-snapshot
             dependency-snapshot logical-ids)]
        (swap! development-linkage-snapshot-cache
               (fn [cached]
                 (let [cached (if (> (count cached) 1024) {} cached)]
                   (assoc cached cache-key result))))
        result))))

(defn- compiler-options-for-declarations
  [compiler-options declarations]
  (let [development-dependencies?
        (boolean (:development-dependencies? compiler-options))
        transitive-dependencies?
        (or development-dependencies?
            (boolean (:transitive-dependencies? compiler-options)))
        dependency-snapshot (:dependency-snapshot compiler-options)
        development-root-source (:development-root-source compiler-options)
        development-root-dependencies
        (:development-root-dependencies compiler-options)
        development-root-module (some-> declarations first :module str)
        development-profile-module (development-profile-module declarations)
        development-linkage-logical-ids
        (development-linkage-logical-ids declarations)
        development-linkage-modules
        (development-linkage-modules dependency-snapshot
                                     development-linkage-logical-ids)
        configured-module-dependencies
        (into {}
              (map (fn [[module dependencies]]
                     [(if (= :root module)
                        :root
                        (if (instance? clojure.lang.Named module)
                          (name module)
                          (str module)))
                      (mapv #(if (instance? clojure.lang.Named %)
                               (name %)
                               (str %))
                            dependencies)]))
              (:module-dependencies compiler-options))
        compiler-options (dissoc compiler-options :development-dependencies?
                                 :transitive-dependencies?
                                 :dependency-snapshot
                                 :development-root-source
                                 :development-root-dependencies
                                 :module-dependencies)
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
        (if transitive-dependencies?
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
        (if transitive-dependencies?
          (reduce
           (fn [modules module]
             (if (contains? modules module)
               modules
               (assoc modules module
                      (materialize-registered-module-source!
                       module development-dependencies?))))
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
        configured-cache-tokens
        (into {}
              (map (fn [[module token]]
                     [(if (instance? clojure.lang.Named module)
                        (name module)
                        (str module))
                      token]))
              (:module-cache-tokens compiler-options))
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
        (loop [required
               (->> (concat root-named-module-imports
                            (mapcat :named-module-imports
                                    (vals dependency-snapshot)))
                    (filter (set (keys external-candidates)))
                    set)]
          (let [expanded
                (into required
                      (comp
                       (mapcat #(get configured-module-dependencies %))
                       (filter (set (keys external-candidates))))
                      required)]
            (if (= required expanded) required (recur expanded))))
        ;; Zig 0.16 rejects even an otherwise valid `-Mname=...` module when
        ;; no module in this compilation graph declares it through `--dep`.
        ;; A declaration live-slice therefore carries only the named modules
        ;; it (or its reachable namespace modules) actually imports.
        external (select-keys external-candidates required-external-names)
        selected-configured-module-names
        (set (filter #(contains? configured %) (keys external)))
        external-cache-tokens
        (select-keys configured-cache-tokens selected-configured-module-names)
        external-names (set (keys external))
        automatic-module-dependencies
        (when transitive-dependencies?
          (into {:root (->> (concat
                             (when development-dependencies?
                               [development-root-module
                                development-profile-module])
                             (when development-dependencies?
                               development-linkage-modules)
                             (when-not development-dependencies?
                               (concat root-dependencies
                                       (filter external-names
                                               root-named-module-imports))))
                            distinct
                            (remove nil?)
                            (sort-by str)
                            vec)
                 development-root-module
                 (->> (concat (or development-root-dependencies
                                   root-dependencies)
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
        configured-module-dependencies
        (into {}
              (keep (fn [[module dependencies]]
                      (let [dependencies (->> dependencies
                                              (filter external-names)
                                              distinct
                                              (sort-by str)
                                              vec)]
                        (when (and (or (= :root module)
                                       (contains? external-names module))
                                   (seq dependencies))
                          [module dependencies]))))
              configured-module-dependencies)
        module-dependencies
        (merge-with (fn [left right]
                      (->> (concat left right) distinct (sort-by str) vec))
                    automatic-module-dependencies
                    configured-module-dependencies)
        arguments-by-module
        (into {}
              (map (fn [[module arguments]]
                     [(if (instance? clojure.lang.Named module)
                        (name module)
                        (str module))
                      arguments]))
              (:zig-args-by-module compiler-options))
        active-module-names
        (into #{(str development-root-module)}
              (map str)
              (concat root-dependencies
                      (keys automatic)
                      (keys external)))
        activated-zig-args
        (->> arguments-by-module
             (filter (comp active-module-names key))
             (sort-by key)
             (mapcat val)
             vec)
        compiler-options
        (-> compiler-options
            (update :zig-args #(vec (concat (or % []) activated-zig-args)))
            (dissoc :zig-args-by-module))
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
                   :module-cache-tokens external-cache-tokens
                   :development-linkage-logical-ids
                   development-linkage-logical-ids
                   ;; Automatic and build-generated modules are materialized
                   ;; beneath content-addressed paths, so they cannot make an
                   ;; otherwise identical artifact stale. User-configured
                   ;; modules make only slices that actually select them
                   ;; non-cacheable; an unrelated leaf keeps its native cache.
                   :cache-safe?
                   (every? #(contains? external-cache-tokens %)
                           selected-configured-module-names))
      (seq module-dependencies)
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
    (when-not (or (contains? *converted-dependency-loading* module)
                  (true? (get-in @registry
                                 [module :converted-dependency-closure-loaded?])))
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
                                  :replace? true})))))
        (swap! registry assoc-in
               [module :converted-dependency-closure-loaded?] true)))))

(defn- ensure-converted-dependency-sources!
  [module declarations]
  (let [converted-roots
        (if (project/converted-module? module)
          [[module declarations]]
          (into []
                (keep
                 (fn [dependency]
                   (when (project/converted-module? dependency)
                     [dependency
                      (vec (vals (get-in @registry
                                         [(str dependency) :definitions])))])))
                (->> (emit/declaration-imports declarations)
                     vals
                     (keep :namespace)
                     distinct)))]
    (locking converted-load-lock
      (doseq [[converted-module converted-declarations] converted-roots
              :let [root (converted-source-root converted-module
                                                converted-declarations)]
              :when root]
        ;; Traverse even when the direct converted module already has source:
        ;; a hand-written namespace can require that module before any of its
        ;; `:as-alias` cycle edges have been loaded. The traversal is cycle-safe
        ;; and only evaluates source for modules absent from the registry.
        (binding [*converted-dependency-loading* #{}]
          (load-converted-source-only! root converted-module))))))

(defn- development-linkage-source
  "Force transitive reload hooks into a loaded development artifact.

  Merely declaring an `export fn` inside an imported Zig module does not make
  Zig analyze that declaration. Referencing each public helper from the root
  loader keeps the corresponding symbols available to FFM, which can then
  point every embedded dispatch/state cell at the current owning generation."
  [dependency-snapshot logical-ids]
  (let [modules
        (->> dependency-snapshot
             (keep
              (fn [[module {:keys [dispatch-entries state-entries]}]]
                (let [symbols
                      (->> (concat
                            (mapcat
                             (fn [{:keys [spec]}]
                               [(:setter spec)
                                (:publication-epoch-setter spec)])
                             (filter #(linkage-entry? logical-ids %)
                                     dispatch-entries))
                            (mapcat
                             (fn [{:keys [spec]}]
                               [(:getter spec) (:setter spec)
                                (:size-getter spec) (:align-getter spec)])
                             (filter #(linkage-entry? logical-ids %)
                                     state-entries)))
                           (remove nil?)
                           distinct
                           sort
                           vec)]
                  (when (seq symbols)
                    {:module (str module)
                     :alias (str "__aguafria_dependency_"
                                 (subs (sha256 (str module)) 0 16))
                     :symbols symbols}))))
             (sort-by :module)
             vec)]
    (when (seq modules)
      (str "\n// Retain transitive development dispatch/state hooks.\n"
           (apply str
                   (map (fn [{:keys [module alias]}]
                         (str "const " alias " = @import("
                              (emit/emit-expr module) ")."
                              (emit/named-module-container module) ";\n"))
                       modules))
           "comptime {\n"
           (apply str
                  (mapcat
                   (fn [{:keys [alias symbols]}]
                     (map #(str "    _ = &" alias "." % ";\n") symbols))
                   modules))
           "}\n"))))

(defn- compile-source!
  ([module-name source declarations]
   (compile-source! module-name source declarations nil))
  ([module-name source declarations dependency-snapshot]
   (compile-source! module-name source declarations dependency-snapshot source
                    nil))
  ([module-name source declarations dependency-snapshot development-root-source]
   (compile-source! module-name source declarations dependency-snapshot
                    development-root-source nil))
  ([module-name source declarations dependency-snapshot development-root-source
    development-root-dependencies]
   (let [development-dependencies? true
         development-linkage-logical-ids
         (development-linkage-logical-ids declarations)
         dependency-snapshot
         (linkable-development-dependency-snapshot
          dependency-snapshot development-linkage-logical-ids)
         profile-module (development-profile-module declarations)
         {:keys [cache-dir optimize zig development-linkage-logical-ids]
          :as compiler-options}
         (compiler-options-for-declarations
          (cond-> (assoc @config
                         :development-dependencies? development-dependencies?
                         :development-root-source development-root-source
                         :development-root-dependencies
                         development-root-dependencies)
            dependency-snapshot (assoc :dependency-snapshot dependency-snapshot))
          declarations)
        compiler-version (zig-version)
        zig-argument-inputs
        (mapv external-argument-fingerprint (:zig-args compiler-options))
        linkage-source
        (development-linkage-source
         dependency-snapshot development-linkage-logical-ids)
        hash-input [source compiler-version
                    (assoc (select-keys compiler-options
                                         [:optimize :target :cpu :zig-args
                                         :modules :module-dependencies
                                         :module-zig-args
                                         :module-cache-tokens])
                           :zig-argument-inputs zig-argument-inputs
                           ;; Hash what Zig actually analyzes, not the larger
                           ;; runtime-only set used to select those references.
                           ;; A module becoming live must not invalidate an
                           ;; otherwise identical dependency-free artifact.
                           :development-linkage-source linkage-source)
                    (System/getProperty "os.name") (System/getProperty "os.arch")]
        source-hash (subs (sha256 (pr-str hash-input)) 0 24)
        module-dir (io/file cache-dir (safe-path-component module-name) source-hash)
        source-file (io/file module-dir "module.zig")
        profile-root-declarations
        (if (and (= (str module-name) profile-module)
                 (= source development-root-source))
          ;; A breaking declaration may compile as an intentionally small
          ;; live slice of the profile root. Re-exporting every declaration
          ;; registered in the full module would reference names that are not
          ;; present in that slice (for example VOPR's `std_options`).
          declarations
          (vals (get-in @registry [profile-module :definitions])))
        module-container (emit/named-module-container module-name)
        wrapped-development-root?
        (str/includes? (or development-root-source "")
                       (str "pub const " module-container " = struct"))
        compiler-source
        (if development-dependencies?
          (str "// Aguafria development loader.\n"
               "const aguafria_module = @import("
               (emit/emit-expr (str module-name)) ")"
               (when wrapped-development-root?
                 (str "." module-container))
               ";\n"
               (when (and profile-module
                          (not= (str module-name) profile-module))
                 (str "const aguafria_profile_root = @import("
                      (emit/emit-expr profile-module) ")."
                      (emit/named-module-container profile-module) ";\n"))
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
               "comptime { _ = aguafria_module; }\n"
               linkage-source)
          source)
        asset-module (or profile-module (str module-name))
        compiler-source (project/localize-module-assets asset-module
                                                         compiler-source)
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
    (project/materialize-module-assets! asset-module compiler-source module-dir)
    (let [artifact-lock (get (swap! artifact-locks
                                    #(if (contains? % (.getAbsolutePath library-file))
                                       %
                                       (assoc % (.getAbsolutePath library-file) (Object.))))
                             (.getAbsolutePath library-file))]
      (locking artifact-lock
        (let [cache-safe? (:cache-safe? compiler-options)
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

(defn- compilation-dependency-dispatch-entries
  [compilation]
  (let [logical-ids
        (development-linkage-logical-ids (:declarations compilation))]
    (into (filter #(linkage-entry? logical-ids %)
                  (dependency-dispatch-entries
                   (:dependency-snapshot compilation)))
          (:embedded-root-dispatch-entries compilation))))

(defn- compilation-dependency-state-entries
  [compilation]
  (let [logical-ids
        (development-linkage-logical-ids (:declarations compilation))]
    (into (filter #(linkage-entry? logical-ids %)
                  (dependency-state-entries
                   (:dependency-snapshot compilation)))
          (:embedded-root-state-entries compilation))))

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

(defn- constructor-type-reference?
  [{:keys [kind value] :as declaration}]
  (or (= :struct kind)
      (and (= :const kind)
           (or (container-type-declaration? declaration)
               (and (symbol? value)
                    (-> value meta :aguafria/zig-reference
                        :type-reference?))))))

(defn- declaration-reference-view
  [{:keys [kind module name zig-name logical-id abi-fingerprint
           schema-fingerprint shape-fingerprint
           implementation-fingerprint value]
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
    shape-fingerprint (assoc :shape-fingerprint shape-fingerprint)
    (= :var kind)
    (assoc :state-accessor (:accessor (declaration-state-spec declaration)))
    ;; `:type-reference?` means the Var itself is usable with Zig's
    ;; `Type{...}` constructor syntax. A function returning `type` is invoked
    ;; normally and must not be rewritten to `function{...}`.
    (constructor-type-reference? declaration)
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
                     (contains? #{:fn :fn-proto :const :var :extern-var
                                  :struct :import :raw :field}
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

(defn- schedule-module-generation-retirement!
  "Close obsolete arenas just after the new dispatch targets become visible."
  [module]
  (.execute
   generation-retirement-executor
   (fn []
     ;; The same lock protects publication, host preparation, active-call
     ;; counts, native-value ownership, and retirement. The worker therefore
     ;; cannot close a generation during any ownership handoff.
     (locking compile-lock
       (retire-module-quiescent-generations! module))))
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

(defn- replacement-declaration-state
  "Prepare one top-level replacement by Zig name, not only declaration kind.

  A REPL edit may legitimately change `(az/defstruct Value ...)` into a type
  alias `(az/defconst Value OtherValue)`. The old native generation remains
  retained for active callers, while the new module snapshot must contain
  exactly one `Value` declaration."
  [definitions declaration]
  (let [declaration-name (declaration-zig-name declaration)
        exact (get definitions (:declaration-key declaration))
        conflict
        (some (fn [[key candidate]]
                (when (and (not= key (:declaration-key declaration))
                           (= declaration-name
                              (declaration-zig-name candidate)))
                  candidate))
              definitions)
        old-declaration (or exact conflict)
        declaration
        (if (or (some? (:source-order declaration))
                (nil? (:source-order old-declaration)))
          (stable-source-order definitions declaration)
          (assoc declaration :source-order (:source-order old-declaration)))
        definitions
        (into {}
              (remove (fn [[_ candidate]]
                        (= declaration-name
                           (declaration-zig-name candidate))))
              definitions)]
    {:old-declaration old-declaration
     :declaration declaration
     :definitions (assoc definitions (:declaration-key declaration) declaration)}))

(defn- published-loaded-declarations
  "Keep the complete logical module view after publishing a native live slice.

  The new generation owns only its compiled slice, while unchanged declarations
  remain callable through retained generations. Merge by Zig declaration name
  so a later edit can compare against the complete published program and emit
  the implementation getter required to repoint its dispatch cell."
  [current loaded partial-publication?]
  (if-not partial-publication?
    (vec (:loaded-declarations loaded))
    (let [initial (into {}
                        (map (juxt :declaration-key identity))
                        (:loaded-declarations current))]
      (->> (:loaded-declarations loaded)
           (reduce (fn [definitions declaration]
                     (:definitions
                      (replacement-declaration-state definitions declaration)))
                   initial)
           vals
           (sort-by (juxt :source-order (comp str :name)))
           vec))))

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

(defn- jvm-callable-result-type
  "Return the complete Zig result type used by a JVM call bridge.

  Zig's inferred error-set spelling, `!T`, is represented by converted source
  as a `!` function qualifier plus the payload type `T`. The native bridge must
  nevertheless store and inspect the complete error union; exposing only `T`
  would generate an invalid C-callable wrapper."
  [{:keys [return zig-qualifiers]}]
  (if (and (string? zig-qualifiers)
           (re-find #"(?:^|\s)!\s*$" zig-qualifiers))
    ;; An inferred error set (`!T`) is legal in a function result but not as a
    ;; standalone storage type (`*!T`). The bridge stores it as `anyerror!T`,
    ;; which retains the exact runtime error code/name and accepts the inferred
    ;; function result without constraining or guessing its compile-time set.
    [:error-union :anyerror return]
    return))

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
                     result-type (jvm-callable-result-type declaration)
                     bridge-declaration (assoc declaration :return result-type)
                     return-mode
                     (cond
                       (= :void result-type) :void
                       (contains? scalar-layouts (scalar-key result-type)) :scalar
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
                  {:declaration bridge-declaration
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

(defn- module-source-specs
  [module declarations getter-declaration-keys]
  (let [reload-source-dispatch-specs
        (reloadable-dispatch-specs declarations)
        dispatch-specs
        (into {}
              (map (fn [[declaration-key spec]]
                     [declaration-key
                      (cond-> spec
                        (contains? getter-declaration-keys declaration-key)
                        (assoc :emit-getter? true))]))
              reload-source-dispatch-specs)]
    {:dispatch-specs dispatch-specs
     :reload-source-dispatch-specs reload-source-dispatch-specs
     :state-specs (reloadable-state-specs declarations)
     :jvm-callable-specs (jvm-callable-wrapper-specs module declarations)
     :jvm-value-specs (jvm-value-wrapper-specs module declarations)
     :jvm-type-specs (jvm-type-wrapper-specs module declarations)}))

(defn- compute-module-sources
  ([module declarations]
   (compute-module-sources module declarations #{}))
  ([module declarations getter-declaration-keys]
   (let [{:keys [dispatch-specs reload-source-dispatch-specs state-specs
                 jvm-callable-specs jvm-value-specs jvm-type-specs]
          :as specs}
         (module-source-specs module declarations getter-declaration-keys)
         source (emit-source! module declarations)
         jvm-callable-source (emit-jvm-callable-wrappers jvm-callable-specs)
         jvm-value-source (emit-jvm-value-wrappers jvm-value-specs)
         jvm-type-source (emit-jvm-type-wrappers jvm-type-specs)
         jvm-wrapper-source
         (str jvm-callable-source jvm-value-source jvm-type-source)
         reload-source
         (emit-reload-source! module declarations reload-source-dispatch-specs
                              state-specs jvm-wrapper-source)
         dependency-source
         (emit-dependency-reload-source!
          module declarations reload-source-dispatch-specs state-specs)]
     (merge
      specs
      {:source source
      ;; Dependency copies always contain setters and a local implementation,
      ;; but never an exported implementation-address getter. This preserves
      ;; Zig's lazy/platform analysis when another module imports this source.
      :reload-source reload-source
      :dependency-source dependency-source
      ;; Only declarations whose already-published implementation changed
      ;; expose a getter in the owning generation.
      :compile-source
      (if (= dispatch-specs reload-source-dispatch-specs)
        reload-source
        (emit-reload-source! module declarations dispatch-specs state-specs
                             jvm-wrapper-source))}))))

(defn- module-source-weight
  [sources]
  (reduce + 0
          (map (fn [key]
                 (count (or (get sources key) "")))
               [:source :reload-source :dependency-source :compile-source])))

(defn- trim-module-source-cache
  [cache]
  (loop [cache cache]
    (if (and (<= (count (:entries cache)) module-source-cache-entry-limit)
             (<= (:weight-chars cache) module-source-cache-weight-limit))
      cache
      (if-let [oldest-key (first (:order cache))]
        (let [oldest-weight (get-in cache [:entries oldest-key :weight-chars] 0)]
          (recur (-> cache
                     (update :entries dissoc oldest-key)
                     ;; Realize a new small vector so a long-running REPL does
                     ;; not retain the backing vector of evicted cache keys.
                     (assoc :order (vec (next (:order cache))))
                     (update :weight-chars - oldest-weight)
                     (update :eviction-count inc))))
        cache))))

(defn- module-source-cache-key
  [module declarations getter-declaration-keys]
  (let [module-state (get @registry (str module))
        declaration-fingerprints
        (->> declarations
             (sort-by (juxt :source-order (comp str :name)))
             (mapv #(or (:source-fingerprint %)
                        (source-data-fingerprint
                         (dissoc %
                                 :source-fingerprint
                                 :implementation-fingerprint
                                 :type-dependency-fingerprints
                                 :callable-dependency-fingerprints
                                 :clojure-form
                                 :logical-key)))))]
    [:module-sources-v1
     (str module)
     (boolean (:reloadable? @config))
     declaration-fingerprints
     (vec (sort-by pr-str getter-declaration-keys))
     (vec (sort-by pr-str (:jvm-callable-declaration-keys module-state)))
     (vec (sort-by pr-str (:jvm-value-declaration-keys module-state)))
     (vec (sort-by pr-str (:jvm-type-declaration-keys module-state)))]))

(def ^:private rendered-module-source-keys
  [:source :reload-source :dependency-source :compile-source])

(defn- cache-module-sources!
  [cache-key sources]
  (let [rendered (select-keys sources rendered-module-source-keys)
        weight (module-source-weight rendered)]
    (swap! module-source-cache
           (fn [cache]
             (cond
               (get-in cache [:entries cache-key])
               cache

               (> weight module-source-cache-weight-limit)
               (update cache :oversized-count inc)

               :else
               (trim-module-source-cache
                (-> cache
                    (assoc-in [:entries cache-key]
                              {:rendered rendered :weight-chars weight})
                    (update :order conj cache-key)
                    (update :weight-chars + weight)))))))
  sources)

(defn- module-sources
  ([module declarations]
   (module-sources module declarations #{}))
  ([module declarations getter-declaration-keys]
   (let [cache-key (module-source-cache-key module declarations
                                            getter-declaration-keys)]
     (if-let [sources (get-in @module-source-cache
                              [:entries cache-key :rendered])]
       (do
         (swap! module-source-cache update :hit-count inc)
         ;; Specs retain current implementation/schema identities and current
         ;; declaration objects even when the rendered Zig is exactly reused.
         (merge (module-source-specs module declarations
                                     getter-declaration-keys)
                sources))
       (do
         (swap! module-source-cache update :miss-count inc)
         (cache-module-sources!
          cache-key
          (compute-module-sources module declarations
                                  getter-declaration-keys)))))))

(defn- module-source-cache-stats
  []
  (let [{:keys [entries weight-chars hit-count miss-count eviction-count
                oversized-count]}
        @module-source-cache
        lookup-count (+ hit-count miss-count)]
    {:entry-count (count entries)
     :entry-limit module-source-cache-entry-limit
     :weight-chars weight-chars
     :weight-limit-chars module-source-cache-weight-limit
     :hit-count hit-count
     :miss-count miss-count
     :hit-rate (if (pos? lookup-count)
                 (/ (double hit-count) lookup-count)
                 0.0)
     :eviction-count eviction-count
     :oversized-count oversized-count}))

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

(defn- changed-dispatch-declarations
  "Return only concrete function implementations absent from or different
  from the published module. Unlike implementation-address getter emission,
  this excludes unchanged exported wrappers."
  [module-state declarations]
  (let [published
        (into {}
              (comp
               (filter dispatchable-declaration?)
               (map (fn [declaration]
                      [[(:logical-id declaration)
                        (:abi-fingerprint declaration)]
                       declaration])))
              (:loaded-declarations module-state))]
    (filterv
     (fn [declaration]
       (when (dispatchable-declaration? declaration)
         (let [previous
               (get published [(:logical-id declaration)
                               (:abi-fingerprint declaration)])]
           (or (nil? previous)
               (not= (:implementation-fingerprint previous)
                     (:implementation-fingerprint declaration))))))
     declarations)))

(defn- breaking-callable-change?
  [old-declaration declaration]
  (and old-declaration
       (= :fn (:kind old-declaration) (:kind declaration))
       (dispatchable-declaration? old-declaration)
       (dispatchable-declaration? declaration)
       (not= (:abi-fingerprint old-declaration)
             (:abi-fingerprint declaration))))

(defn- incremental-dispatch-publication?
  "A new or ABI-compatible concrete function can be published as a small
  declaration slice. Existing callers already enter through its stable cell."
  [module-state old-declaration declaration]
  (and (:published-generation module-state)
       (= :fn (:kind declaration))
       (dispatchable-declaration? declaration)
       (or (nil? old-declaration)
           (and (= :fn (:kind old-declaration))
                (dispatchable-declaration? old-declaration)
                (= (:abi-fingerprint old-declaration)
                   (:abi-fingerprint declaration))
                (not= (:implementation-fingerprint old-declaration)
                      (:implementation-fingerprint declaration))))))

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

(defn- registered-declarations-by-logical-id
  []
  (locking declaration-reference-index
    (let [module-definitions
          (into {} (map (fn [[module state]] [module (:definitions state)]))
                @registry)
          index-state @declaration-reference-index
          {:keys [by-module by-logical references]} index-state
          references-present? (contains? index-state :references)
          references (or references {})
          previous-references references
          removed (vec (remove #(contains? module-definitions %)
                               (keys by-module)))
          changed
          (into []
                (keep (fn [[module definitions]]
                        (when-not
                         (and references-present?
                              (identical?
                               definitions
                               (get-in by-module [module :definitions])))
                          [module definitions])))
                module-definitions)
          invalidated-modules (concat removed (map first changed))
          invalidated-logical-ids
          (into #{}
                (mapcat #(get-in by-module [% :logical-ids]))
                invalidated-modules)
          by-logical
          (reduce
           (fn [index module]
             (reduce dissoc index (get-in by-module [module :logical-ids])))
           by-logical
           invalidated-modules)
          references (apply dissoc references invalidated-logical-ids)
          by-module (apply dissoc by-module removed)
          [by-module by-logical]
          (reduce
           (fn [[modules index] [module definitions]]
             (let [declarations (vals definitions)
                   logical-ids (into #{} (keep :logical-id) declarations)
                   by-name
                   (into {}
                         (mapcat
                          (fn [declaration]
                            (map (fn [declaration-name]
                                   [(str declaration-name) declaration])
                                 (distinct
                                  (remove nil?
                                          [(:name declaration)
                                           (:zig-name declaration)
                                           (declaration-zig-name
                                            declaration)])))))
                         declarations)]
               [(assoc modules module {:definitions definitions
                                       :logical-ids logical-ids
                                       :by-name by-name})
                (into index (map (juxt :logical-id identity)) declarations)]))
           [by-module by-logical]
           changed)
          preliminary {:by-module by-module
                       :by-logical by-logical
                       :references references
                       :revision (long (or (:revision index-state) 0))}
          ;; Direct reference extraction needs the just-updated same-module
          ;; name index (not the prior namespace snapshot), so publish that
          ;; immutable index before filling only the changed adjacency rows.
          _ (reset! declaration-reference-index preliminary)
          references
          (reduce
           (fn [index [_ definitions]]
             (reduce
              (fn [index declaration]
                (if-let [logical-id (:logical-id declaration)]
                  (assoc index logical-id
                         (declaration-reference-logical-ids declaration))
                  index))
              index
              (vals definitions)))
           references
           changed)
          new-logical-ids
          (into #{}
                (comp (mapcat (comp vals second)) (keep :logical-id))
                changed)
          affected-logical-ids
          (into invalidated-logical-ids new-logical-ids)
          reference-graph-changed?
          (or (not references-present?)
              (some #(not= (get previous-references %)
                            (get references %))
                    affected-logical-ids))
          updated
          (assoc preliminary
                 :references references
                 :revision
                 (cond-> (long (or (:revision index-state) 0))
                   reference-graph-changed? inc))]
      (reset! declaration-reference-index updated)
      by-logical)))

(defn- refresh-live-declaration-references
  [declarations]
  (letfn [(refresh-pass [declarations]
            (let [registered-by-logical
                  (registered-declarations-by-logical-id)
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
                       source-reference-changed? (volatile! false)
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
                                  (when (not=
                                         (select-keys
                                          (:aguafria/zig-reference (meta value))
                                          source-reference-fingerprint-keys)
                                         (select-keys reference
                                                      source-reference-fingerprint-keys))
                                    (vreset! source-reference-changed? true))
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
                                             (:schema-fingerprint current)
                                             :shape-fingerprint
                                             (:shape-fingerprint current))]
                                  (when (not=
                                         (select-keys
                                          (:aguafria/zig-reference (meta value))
                                          source-reference-fingerprint-keys)
                                         (select-keys reference
                                                      source-reference-fingerprint-keys))
                                    (vreset! source-reference-changed? true))
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
                                  (when (not=
                                         (select-keys
                                          (:aguafria/zig-reference (meta value))
                                          source-reference-fingerprint-keys)
                                         (select-keys reference
                                                      source-reference-fingerprint-keys))
                                    (vreset! source-reference-changed? true))
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
                                            current)
                                           (:shape-fingerprint current)])))))
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
                            vec)
                       dependency-identities-changed?
                       (not= [(:type-dependency-fingerprints declaration)
                              (:callable-dependency-fingerprints declaration)]
                             [type-dependency-fingerprints
                              callable-dependency-fingerprints])
                       refreshed
                       (assoc refreshed
                              :type-dependency-fingerprints
                              type-dependency-fingerprints
                              :callable-dependency-fingerprints
                              callable-dependency-fingerprints)]
                   (binding [*preserve-source-fingerprint?*
                             (and (:source-fingerprint declaration)
                                  (not @source-reference-changed?)
                                  (not dependency-identities-changed?))]
                     (declaration-info refreshed))))
               declarations)))]
    (let [refreshed (refresh-pass declarations)
          before-by-logical (into {} (map (juxt :logical-id identity))
                                  declarations)
          changed-logical-ids
          (into #{}
                (keep
                 (fn [declaration]
                   (let [before (get before-by-logical
                                     (:logical-id declaration))]
                     (when (not= (select-keys before
                                             [:abi-fingerprint
                                              :schema-fingerprint
                                              :shape-fingerprint
                                              :implementation-fingerprint])
                                 (select-keys declaration
                                             [:abi-fingerprint
                                              :schema-fingerprint
                                              :shape-fingerprint
                                              :implementation-fingerprint]))
                       (:logical-id declaration)))))
                refreshed)
          second-pass-required?
          (and (> (count refreshed) 1)
               (seq changed-logical-ids)
               (some
                (fn [declaration]
                  (some changed-logical-ids
                        (concat
                         (map first (:type-dependency-fingerprints declaration))
                         (map first (:callable-dependency-fingerprints
                                     declaration)))))
                refreshed))]
      ;; Only declarations that refer to another declaration whose identity
      ;; changed need the transitive second pass. A single large type factory
      ;; no longer walks and fingerprints its entire body twice.
      (if second-pass-required?
        (refresh-pass refreshed)
        refreshed))))

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

(defn- direct-declaration-dependencies
  [declarations]
  (->> (emit/declaration-imports declarations)
       vals
       (keep :namespace)
       (map str)
       distinct
       sort
       vec))

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
        incremental-publication?
        (incremental-dispatch-publication?
         module-state old-declaration declaration)
        concrete-caller-recompile?
        (concrete-caller-recompile-change? old-declaration declaration)
        materialization?
        (= *materialize-declaration-key* (:declaration-key declaration))
        fallback
        (when (or materialization?
                  incremental-publication?
                  concrete-caller-recompile?
                  (breaking-callable-change? old-declaration declaration)
                  (breaking-state-change? old-declaration declaration)
                  (breaking-type-change? old-declaration declaration))
          (let [refreshed-declaration
                (or (some #(when (= (:declaration-key declaration)
                                    (:declaration-key %))
                            %)
                          declarations)
                    declaration)
                fallback-declarations
                (->> (concat
                      (filter #(= :import (:kind %)) declarations)
                      (if (and incremental-publication?
                               (not *exact-declaration-publication?*))
                        (mapcat #(declaration-live-slice declarations %)
                                (changed-dispatch-declarations
                                 module-state declarations))
                        (declaration-live-slice
                         declarations refreshed-declaration)))
                     (reduce (fn [selected candidate]
                               (assoc selected
                                      (:declaration-key candidate)
                                      candidate))
                             {})
                     vals
                     (sort-by (juxt :source-order (comp str :name)))
                     vec)
                fallback-dependencies
                (development-dependency-snapshot fallback-declarations)
                fallback-cyclic-root?
                (boolean
                 (some (fn [{:keys [dependencies]}]
                         (some #{(str module)} dependencies))
                       (vals fallback-dependencies)))
                fallback-root-context-required?
                (boolean
                 (some (fn [{:keys [named-module-imports]}]
                         (some #{"root"} named-module-imports))
                       (vals fallback-dependencies)))
                fallback-complete-development-root?
                (or fallback-cyclic-root? fallback-root-context-required?)]
            (assoc (module-sources module fallback-declarations
                                   getter-declaration-keys)
                   :declarations fallback-declarations
                   :dependency-snapshot fallback-dependencies
                   :complete-development-root?
                   fallback-complete-development-root?
                   :partial-publication? true)))]
    {:primary (assoc primary
                     :development-root-source (:compile-source primary)
                     :development-root-dependencies
                     (direct-declaration-dependencies declarations))
     :fallback (when fallback
                 (let [fallback-complete-development-root?
                       (:complete-development-root? fallback)
                       fallback-dispatch-keys
                       (set (keys (:reload-source-dispatch-specs fallback)))
                       fallback-state-keys
                       (set (keys (:state-specs fallback)))
                       declarations-by-key
                       (into {} (map (juxt :declaration-key identity))
                             declarations)]
                   (assoc fallback
                        ;; A partial generation remains the callable root, but
                        ;; cyclic dependencies must import the complete module
                        ;; namespace. Otherwise an untouched type/function can
                        ;; disappear merely because this edit publishes one
                        ;; live slice.
                        :development-root-source
                        (if fallback-complete-development-root?
                          (:compile-source primary)
                          (:compile-source fallback))
                        :development-root-dependencies
                        (direct-declaration-dependencies
                         (if fallback-complete-development-root?
                           declarations
                           (:declarations fallback)))
                        :complete-development-root?
                        fallback-complete-development-root?
                        ;; The complete root source can retain imports that
                        ;; are intentionally absent from the edited live
                        ;; slice, so it needs the complete dependency graph as
                        ;; well.
                        :dependency-snapshot
                        (if fallback-complete-development-root?
                          (:dependency-snapshot primary)
                          (:dependency-snapshot fallback))
                        :embedded-root-dispatch-entries
                        (when fallback-complete-development-root?
                          (->> (:reload-source-dispatch-specs primary)
                               (remove (comp fallback-dispatch-keys key))
                               (keep (fn [[declaration-key spec]]
                                       (when-let [embedded
                                                  (get declarations-by-key
                                                       declaration-key)]
                                         {:declaration embedded
                                          :spec spec
                                          :owned? false})))
                               vec))
                        :embedded-root-state-entries
                        (when fallback-complete-development-root?
                          (->> (:state-specs primary)
                               (remove (comp fallback-state-keys key))
                               (keep (fn [[declaration-key spec]]
                                       (when-let [embedded
                                                  (get declarations-by-key
                                                       declaration-key)]
                                         {:declaration embedded
                                          :spec spec
                                          :owned? false})))
                               vec)))))
     :old-declaration old-declaration
     :declaration declaration
     ;; A breaking type is a new logical generation. Compiling the complete
     ;; namespace here would silently redirect untouched dependents to it.
     ;; Publish only the edited Var and its declaration dependencies; each
     ;; caller/state owner adopts the new schema when explicitly reevaluated.
     :prefer-fallback? (or materialization?
                           (and incremental-publication?
                                (or (not (:partial-publication? module-state))
                                    (nil? (:full-compile-error module-state))))
                           concrete-caller-recompile?
                           (breaking-type-change? old-declaration declaration))}))

(defn- complete-compilation-plan
  [module module-state declarations]
  (let [declarations (refresh-live-declaration-references declarations)]
    (let [primary
          (assoc (module-sources
                  module declarations
                  (changed-dispatch-declaration-keys module-state declarations))
                 :declarations declarations
                 :dependency-snapshot
                 (development-dependency-snapshot declarations)
                 :partial-publication? false)]
      {:primary (assoc primary
                       :development-root-source (:compile-source primary)
                       :development-root-dependencies
                       (direct-declaration-dependencies declarations))})))

(defn- compilation-slice-view
  [slice]
  (when slice
    {:partial-publication? (boolean (:partial-publication? slice))
     :complete-development-root?
     (boolean (:complete-development-root? slice))
     :declaration-count (count (:declarations slice))
     :declarations (mapv declaration-summary (:declarations slice))
     :dependency-modules (-> slice :dependency-snapshot keys vec)
     :direct-dependency-modules
     (vec (:development-root-dependencies slice))}))

(declare dependent-propagation-impact)

(defn- publication-plan-view
  [module declaration-key module-state old-declaration declaration definitions
   plan]
  (let [propagation-impact
        (dependent-propagation-impact (:old-declaration plan)
                                      (:declaration plan))
        reason
        (cond
          (nil? old-declaration) :new-declaration
          (breaking-type-change? old-declaration declaration) :breaking-type
          (breaking-state-change? old-declaration declaration) :breaking-state
          (breaking-callable-change? old-declaration declaration)
          :breaking-callable
          (compatible-type-producing-change? old-declaration declaration)
          :compatible-type
          (concrete-caller-recompile-change? old-declaration declaration)
          :embedded-callable
          (incremental-dispatch-publication? module-state old-declaration
                                             declaration)
          :compatible-dispatch
          :else :explicit-or-complete)]
    {:module module
     :declaration-key declaration-key
     :logical-id (:logical-id declaration)
     :reason reason
     :preferred-slice (if (:prefer-fallback? plan) :fallback :primary)
     :propagation-impact propagation-impact
     :registered-declaration-count (count definitions)
     :primary (compilation-slice-view (:primary plan))
     :fallback (compilation-slice-view (:fallback plan))}))

(defn- capture-publication-plan!
  [module declaration-key module-state old-declaration declaration definitions
   plan]
  (when *publication-plan-capture*
    ;; Do not make ordinary registration format every declaration summary.
    ;; Benchmark/reporting callers force this delay after the latency boundary.
    (reset! *publication-plan-capture*
            (delay
              (publication-plan-view module declaration-key module-state
                                     old-declaration declaration definitions
                                     plan))))
  nil)

(defn publication-plan
  "Explain the immutable native slice Aguafria would publish for a descriptor.

  This is a read-only development/benchmark API. It intentionally omits
  generated Zig source while exposing the exact declarations, dependencies,
  fallback choice, and propagation reason suitable for REPL inspection."
  [descriptor]
  (let [{:keys [module declaration-key] :as descriptor}
        (declaration-info descriptor)
        module-state (get @registry module)
        old-definitions (or (:definitions module-state) {})
        {:keys [old-declaration declaration definitions]}
        (replacement-declaration-state old-definitions descriptor)
        declarations (vec (vals definitions))
        plan (compilation-plan module module-state declarations
                               old-declaration declaration)]
    (publication-plan-view module declaration-key module-state old-declaration
                           declaration definitions plan)))

(defn- compile-plan!
  [module {:keys [primary fallback prefer-fallback?]}]
  (if prefer-fallback?
    (assoc fallback :compiled
           (assoc (compile-source! module (:compile-source fallback)
                                   (:declarations fallback)
                                   (:dependency-snapshot fallback)
                                   (:development-root-source fallback)
                                   (:development-root-dependencies fallback))
                  :partial-publication? true))
    (try
      (assoc primary :compiled
             (compile-source! module (:compile-source primary)
                              (:declarations primary)
                              (:dependency-snapshot primary)
                              (:development-root-source primary)
                              (:development-root-dependencies primary)))
      (catch Throwable full-error
        (if-not fallback
          (throw full-error)
          (try
            (let [compiled (compile-source! module (:compile-source fallback)
                                            (:declarations fallback)
                                            (:dependency-snapshot fallback)
                                            (:development-root-source fallback)
                                            (:development-root-dependencies
                                             fallback))]
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
  (if-let [primary (:primary plan)]
    (let [primary-snapshot
          (development-dependency-snapshot (:declarations primary))]
      (cond-> (assoc-in plan [:primary :dependency-snapshot]
                        primary-snapshot)
        (:fallback plan)
        (assoc-in
         [:fallback :dependency-snapshot]
         (if (get-in plan [:fallback :complete-development-root?])
           ;; A cyclic fallback's development root is the complete primary
           ;; source, so refresh its complete graph from the same immutable
           ;; registry view.
           primary-snapshot
           (development-dependency-snapshot
            (get-in plan [:fallback :declarations]))))))
    plan))

(declare recompile-component! recompile-dependent-components!)

(defn- dependent-propagation-impact
  [old-declaration declaration]
  (cond
    (compatible-type-producing-change? old-declaration declaration)
    {:kind :type
     :logical-id (:logical-id declaration)}

    (concrete-caller-recompile-change? old-declaration declaration)
    {:kind :callable
     :logical-id (:logical-id declaration)}))

(defn- compile-and-publish-async!
  [{:keys [module declaration-key generation declarations source plan completion
           propagate-dependent-change? propagation-impact]}]
  (mark-build-started! module generation)
  (try
    (let [dependency-preparation-started-ns (System/nanoTime)
          _ (ensure-converted-dependency-sources! module declarations)
          plan (refresh-plan-dependency-snapshots plan)
          dependency-preparation-duration-ms
          (elapsed-nanos-ms dependency-preparation-started-ns)
          compiler-started-ns (System/nanoTime)
          {compiled-declarations :declarations
           :keys [compiled compile-source reload-source dispatch-specs
                  reload-source-dispatch-specs partial-publication?
                  dependency-snapshot jvm-callable-specs jvm-value-specs
                  jvm-type-specs]
           :as compilation}
          (compile-plan! module plan)
          compiler-duration-ms (elapsed-nanos-ms compiler-started-ns)
          dynamic-load-started-ns (System/nanoTime)
          ;; Stale snapshots are useful compiler work/history, but loading each
          ;; one would waste native-library arenas during a large REPL reload.
          loaded (when (= generation
                          (get-in @registry [module :requested-generation]))
                   (-> (load-module compiled compiled-declarations dispatch-specs
                                    (compilation-dependency-dispatch-entries
                                     compilation)
                                    (compilation-dependency-state-entries
                                     compilation)
                                    jvm-callable-specs
                                    jvm-value-specs
                                    jvm-type-specs)
                       (prepare-loaded-generation generation)))
          dynamic-load-duration-ms
          (elapsed-nanos-ms dynamic-load-started-ns)
          published? (atom false)
          publication-started-ns (System/nanoTime)
          _
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
                      values (if partial-publication?
                               (merge (:values current) (:values loaded))
                               (:values loaded))
                      types (if partial-publication?
                              (merge (:types current) (:types loaded))
                              (:types loaded))
                      loaded-declarations
                      (published-loaded-declarations current loaded
                                                     partial-publication?)
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
                                 :dependency-source
                                 (get-in plan [:primary :dependency-source])
                                 :dispatch-specs published-dispatch-specs
                                 :reload-source-dispatch-specs
                                 reload-source-dispatch-specs
                                 :dependency-dispatch-specs
                                 (get-in plan
                                         [:primary
                                          :reload-source-dispatch-specs])
                                 :definitions
                                 (if partial-publication?
                                   (merge (:definitions current)
                                          compiled-definitions)
                                   compiled-definitions)
                                 :loaded-declarations loaded-declarations
                                 :functions functions
                                 :values values
                                 :types types
                                 :partial-publication? partial-publication?
                                 :full-compile-error
                                 (:full-compile-error compiled)
                                 :source-only? false
                                 :last-error nil
                                 :failed-generation nil
                                 :last-dependent-publication-failure nil
                                 :last-dependent-publication-error nil}))
                  (refresh-project-dispatch!)
                  (publish-clojure-declaration-metadata!
                   compiled-declarations)
                  (schedule-module-generation-retirement! module)))))
          publication-duration-ms
          (elapsed-nanos-ms publication-started-ns)]
      (when (and loaded (not @published?))
        (.close ^Arena (:arena loaded)))
      (let [native-finished-at-ms (System/currentTimeMillis)
            native-duration-ms
            (when-let [started-at
                       (get-in @build-registry
                               [[module :repl generation] :started-at-ms])]
              (- native-finished-at-ms started-at))
            propagation
            (when (and @published? propagate-dependent-change?)
              (try
                {:affected (recompile-dependent-components!
                            module completion #{propagation-impact})}
                (catch Throwable error
                  {:error error})))
            propagation-error (:error propagation)
            affected (:affected propagation)
            status (if @published? :finished :stale)
            propagation-duration-ms
            (when propagation
              (- (System/currentTimeMillis) native-finished-at-ms))]
        (when @published?
          (swap! registry update module assoc
                 :last-dependent-publication affected))
        (mark-build-finished!
         module generation status
         (assoc compiled
                :compiled-declaration-count (count compiled-declarations)
                :native-duration-ms native-duration-ms
                :dependency-preparation-duration-ms
                dependency-preparation-duration-ms
                :compiler-duration-ms compiler-duration-ms
                :dynamic-load-duration-ms dynamic-load-duration-ms
                :publication-duration-ms publication-duration-ms
                :propagation-duration-ms propagation-duration-ms))
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
          (let [planning-started-ns (System/nanoTime)
                old-module (get @registry module)
                old-definitions (or (:definitions old-module) {})
                {:keys [old-declaration declaration definitions]}
                (replacement-declaration-state old-definitions declaration)
                declarations (vec (vals definitions))
                plan (compilation-plan module old-module declarations
                                       old-declaration declaration)
                plan-old-declaration (:old-declaration plan)
                plan-declaration (:declaration plan)
                _ (capture-publication-plan!
                   module declaration-key old-module old-declaration declaration
                   definitions plan)
                _ (when (and (native-host-active?)
                             (breaking-type-change? plan-old-declaration
                                                    plan-declaration))
                    (freeze-active-host-dispatch! plan-declaration))
                {:keys [source compile-source reload-source dependency-source
                        dispatch-specs
                        reload-source-dispatch-specs]} (:primary plan)
                generation (inc (or (:requested-generation old-module)
                                    (:generation old-module) 0))
                completion (promise)
                planning-duration-ms
                (elapsed-nanos-ms planning-started-ns)
                job {:module module
                     :declaration-key declaration-key
                     :generation generation
                     :definitions definitions
                     :declarations declarations
                     :source source
                     :compile-source compile-source
                     :dispatch-specs dispatch-specs
                     :plan plan
                     :planning-duration-ms planning-duration-ms
                     :propagate-dependent-change?
                     (and *propagate-dependent-changes?*
                          (compatible-dependent-propagation?
                           plan-old-declaration plan-declaration))
                     :propagation-impact
                     (dependent-propagation-impact
                      plan-old-declaration plan-declaration)
                     :completion completion}]
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :source source
                           :reload-source reload-source
                           :dependency-source dependency-source
                           :dispatch-specs dispatch-specs
                           :reload-source-dispatch-specs
                           reload-source-dispatch-specs
                           :dependency-dispatch-specs
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
     :planning-duration-ms (:planning-duration-ms job)
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
        old-declaration
        (:old-declaration
         (replacement-declaration-state definitions declaration))
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
          (let [planning-started-ns (System/nanoTime)
                old-module (get @registry module)
                old-definitions (or (:definitions old-module) {})
                {:keys [old-declaration declaration definitions]}
                (replacement-declaration-state old-definitions declaration)
                declarations (vec (vals definitions))
                plan (compilation-plan module old-module declarations
                                       old-declaration declaration)
                plan-old-declaration (:old-declaration plan)
                plan-declaration (:declaration plan)
                _ (capture-publication-plan!
                   module declaration-key old-module old-declaration declaration
                   definitions plan)
                _ (when (and (native-host-active?)
                             (breaking-type-change? plan-old-declaration
                                                    plan-declaration))
                    (freeze-active-host-dispatch! plan-declaration))
                {:keys [source compile-source reload-source dependency-source
                        dispatch-specs
                        reload-source-dispatch-specs]} (:primary plan)
                generation (inc (or (:requested-generation old-module)
                                    (:generation old-module) 0))
                expected-declaration-count
                (project/expected-declaration-count module)
                ready? (or (nil? expected-declaration-count)
                           (>= (count definitions)
                               expected-declaration-count))
                completion (promise)
                planning-duration-ms
                (elapsed-nanos-ms planning-started-ns)
                job {:module module
                     :declaration-key declaration-key
                     :generation generation
                     :definitions definitions
                     :declarations declarations
                     :source source
                     :compile-source compile-source
                     :dispatch-specs dispatch-specs
                     :plan plan
                     :planning-duration-ms planning-duration-ms
                     :propagate-dependent-change?
                     (and *propagate-dependent-changes?*
                          (compatible-dependent-propagation?
                           plan-old-declaration plan-declaration))
                     :propagation-impact
                     (dependent-propagation-impact
                      plan-old-declaration plan-declaration)
                     :ready? ready?
                     :completion completion}]
            (swap! registry assoc module
                   (merge old-module
                          {:module module
                           :definitions definitions
                           :declarations declarations
                           :source source
                           :reload-source reload-source
                           :dependency-source dependency-source
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
             (long
              (if *file-load-registration?*
                (if (project/converted-module? module)
                  (:converted-compile-debounce-ms @config)
                  (:compile-debounce-ms @config))
                0))
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
     :planning-duration-ms (:planning-duration-ms job)
     :pending? (:ready? job)}))

(defn- register-sync!
  [{:keys [module declaration-key] :as declaration}]
  ;; Converted dependency loading evaluates ordinary Clojure namespaces and
  ;; therefore briefly acquires `compile-lock` through register-batch!. Never
  ;; hold that lock while waiting for `converted-load-lock`: an async converted
  ;; compiler takes those locks in the opposite temporal order while loading a
  ;; cycle-aware dependency closure.
  (let [dependency-preparation-started-ns (System/nanoTime)
        dependency-declarations
        (locking compile-lock
          (let [definitions (or (:definitions (get @registry module)) {})]
            (-> (replacement-declaration-state definitions declaration)
                :definitions vals vec)))
        _ (ensure-converted-dependency-sources! module dependency-declarations)
        dependency-preparation-duration-ms
        (elapsed-nanos-ms dependency-preparation-started-ns)]
   (locking compile-lock
    (let [planning-started-ns (System/nanoTime)
          old-module (get @registry module)
          old-definitions (or (:definitions old-module) {})
          {:keys [old-declaration declaration definitions]}
          (replacement-declaration-state old-definitions declaration)
          declarations (vec (vals definitions))
          plan (compilation-plan module old-module declarations
                                 old-declaration declaration)
          plan-old-declaration (:old-declaration plan)
          plan-declaration (:declaration plan)
          _ (capture-publication-plan!
             module declaration-key old-module old-declaration declaration
             definitions plan)
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
          planning-duration-ms (elapsed-nanos-ms planning-started-ns)
          job {:module module
               :generation generation
               :declarations declarations
               :planning-duration-ms planning-duration-ms}
          prepared (atom nil)
          published? (atom false)]
      (record-build! job false)
      (mark-build-started! module generation)
      (try
        (let [plan (refresh-plan-dependency-snapshots plan)
              compiler-started-ns (System/nanoTime)
              {compiled-declarations :declarations
               :keys [compiled compile-source reload-source dispatch-specs
                      reload-source-dispatch-specs
                      partial-publication? dependency-snapshot
                      jvm-callable-specs jvm-value-specs jvm-type-specs]
               :as compilation}
              (compile-plan! module plan)
              compiler-duration-ms (elapsed-nanos-ms compiler-started-ns)
              dynamic-load-started-ns (System/nanoTime)
              loaded (-> (load-module compiled compiled-declarations dispatch-specs
                                      (compilation-dependency-dispatch-entries
                                       compilation)
                                      (compilation-dependency-state-entries
                                       compilation)
                                      jvm-callable-specs
                                      jvm-value-specs
                                      jvm-type-specs)
                         (prepare-loaded-generation generation))
              dynamic-load-duration-ms
              (elapsed-nanos-ms dynamic-load-started-ns)
              _ (reset! prepared loaded)
              publication-started-ns (System/nanoTime)
              dispatch (reconcile-dispatch! old-module loaded generation)
              compiled-definitions
              (into {}
                    (map (juxt :declaration-key identity))
                    compiled-declarations)
              functions (if partial-publication?
                          (merge (:functions old-module) (:functions loaded))
                          (:functions loaded))
              values (if partial-publication?
                       (merge (:values old-module) (:values loaded))
                       (:values loaded))
              types (if partial-publication?
                      (merge (:types old-module) (:types loaded))
                      (:types loaded))
              loaded-declarations
              (published-loaded-declarations old-module loaded
                                             partial-publication?)
              published-dispatch-specs dispatch-specs
              new-module
              (merge old-module loaded dispatch
                     {:module module
                      :generation generation
                      :published-generation generation
                      :requested-generation generation
                      :source source
                      :reload-source reload-source
                      :dependency-source
                      (get-in plan [:primary :dependency-source])
                      :dispatch-specs published-dispatch-specs
                      :reload-source-dispatch-specs
                      reload-source-dispatch-specs
                      :dependency-dispatch-specs
                      (get-in plan
                              [:primary :reload-source-dispatch-specs])
                      :functions functions
                      :values values
                      :types types
                      :loaded-declarations loaded-declarations
                      :partial-publication? partial-publication?
                      :full-compile-error (:full-compile-error compiled)
                      :pending nil
                      :source-only? false
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
          (schedule-module-generation-retirement! module)
          (let [publication-duration-ms
                (elapsed-nanos-ms publication-started-ns)
                propagation-started-ns (System/nanoTime)
                affected
                (when propagate-dependent-change?
                  (recompile-dependent-components!
                   module nil
                   #{(dependent-propagation-impact
                      plan-old-declaration plan-declaration)}))
                propagation-duration-ms
                (when propagate-dependent-change?
                  (elapsed-nanos-ms propagation-started-ns))]
            (mark-build-finished!
             module generation :finished
             (assoc compiled
                    :compiled-declaration-count (count compiled-declarations)
                    :planning-duration-ms planning-duration-ms
                    :dependency-preparation-duration-ms
                    dependency-preparation-duration-ms
                    :compiler-duration-ms compiler-duration-ms
                    :dynamic-load-duration-ms dynamic-load-duration-ms
                    :publication-duration-ms publication-duration-ms
                    :propagation-duration-ms propagation-duration-ms))
            (cond-> (assoc (registration-result module declaration-key generation
                                                compiled true)
                           :planning-duration-ms planning-duration-ms)
              affected (assoc :affected affected))))
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
          (throw error)))))))

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
             :dependency-source (:dependency-source sources)
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
                   :partial-publication? false
                   :development-root-source (:compile-source sources)
                   :development-root-dependencies
                   (direct-declaration-dependencies
                    (:declarations job)))})))

(defn- prepared-component-module-state
  [old-module {:keys [module generation definitions] :as job}
   {:keys [compiled compile-source reload-source dispatch-specs
           reload-source-dispatch-specs partial-publication?]
    :as compilation}
   loaded publication]
  (let [dispatch (reconcile-dispatch old-module loaded generation)
        functions (if partial-publication?
                    (merge (:functions old-module) (:functions loaded))
                    (:functions loaded))
        values (if partial-publication?
                 (merge (:values old-module) (:values loaded))
                 (:values loaded))
        types (if partial-publication?
                (merge (:types old-module) (:types loaded))
                (:types loaded))
        loaded-declarations
        (published-loaded-declarations old-module loaded partial-publication?)]
    (merge old-module loaded dispatch
           {:module module
            :generation generation
            :published-generation generation
            :requested-generation generation
            :source (get-in job [:plan :primary :source])
            :reload-source reload-source
            :dependency-source (get-in job [:plan :primary :dependency-source])
            :dispatch-specs dispatch-specs
            :reload-source-dispatch-specs reload-source-dispatch-specs
            :dependency-dispatch-specs
            (get-in job [:plan :primary :reload-source-dispatch-specs])
            :functions functions
            :values values
            :types types
            :loaded-declarations loaded-declarations
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
                                   (compilation-dependency-dispatch-entries
                                    compilation)
                                   (compilation-dependency-state-entries
                                    compilation)
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
              (schedule-module-generation-retirement! module))
            (doseq [{:keys [job compilation]} @prepared]
              (mark-build-finished! (:module job) (:generation job)
                                    :finished
                                    (assoc (:compiled compilation)
                                           :compiled-declaration-count
                                           (count (:declarations compilation)))))
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

(defn- declaration-references-impact?
  [declaration impacts]
  (let [type-ids (into #{} (keep #(when (= :type (:kind %))
                                    (:logical-id %))) impacts)
        callable-ids (into #{} (keep #(when (= :callable (:kind %))
                                        (:logical-id %))) impacts)]
    (or (some type-ids (map first (:type-dependency-fingerprints declaration)))
        (some callable-ids
              (map first (:callable-dependency-fingerprints declaration))))))

(defn- component-references-impact?
  [members impacts]
  (boolean
   (some (fn [module]
           (some #(declaration-references-impact? % impacts)
                 (vals (get-in @registry [module :definitions]))))
         members)))

(defn- impacted-component-declarations
  [members impacts]
  (->> members
       (mapcat #(vals (get-in @registry [% :definitions])))
       (filter #(declaration-references-impact? % impacts))
       (sort-by (juxt :module :source-order (comp str :name)))
       vec))

(defn- exact-impact-component-job
  "Plan one independently compilable affected declaration.

  The caller owns `compile-lock`. Dependency loading and planning happen
  before any compiler future starts, so every sibling observes one coherent
  registry generation. Components with more than one affected declaration or
  more than one module deliberately use the established atomic fallback."
  [component impacts]
  (let [members (:modules component)
        declarations (when (and (seq impacts)
                                (not (:cyclic? component))
                                (= 1 (count members)))
                       (impacted-component-declarations members impacts))]
    (when (= 1 (count declarations))
      (let [{:keys [module declaration-key] :as requested-declaration}
            (first declarations)
            planning-started-ns (System/nanoTime)
            old-module (get @registry module)
            old-definitions (or (:definitions old-module) {})
            {:keys [old-declaration declaration definitions]}
            (replacement-declaration-state old-definitions
                                           requested-declaration)
            all-declarations (vec (vals definitions))
            _ (ensure-converted-dependency-sources! module all-declarations)
            plan (-> (compilation-plan module old-module all-declarations
                                       old-declaration declaration)
                     refresh-plan-dependency-snapshots)
            plan-old-declaration (:old-declaration plan)
            plan-declaration (:declaration plan)
            _ (when (and (native-host-active?)
                         (breaking-type-change? plan-old-declaration
                                                plan-declaration))
                (freeze-active-host-dispatch! plan-declaration))
            generation (inc (or (:requested-generation old-module)
                                (:generation old-module)
                                0))
            job {:module module
                 :declaration-key declaration-key
                 :generation generation
                 :definitions definitions
                 :declarations all-declarations
                 :plan plan
                 :old-module old-module
                 :planning-duration-ms
                 (elapsed-nanos-ms planning-started-ns)
                 :requested-at-ms (System/currentTimeMillis)}]
        (record-build! job false)
        job))))

(defn- prepare-exact-impact-job!
  [{:keys [module generation plan] :as job}]
  (mark-build-started! module generation)
  (try
    (let [compiler-started-ns (System/nanoTime)
          {compiled-declarations :declarations
           :keys [compiled dispatch-specs jvm-callable-specs jvm-value-specs
                  jvm-type-specs]
           :as compilation}
          (compile-plan! module plan)
          compiler-duration-ms (elapsed-nanos-ms compiler-started-ns)
          dynamic-load-started-ns (System/nanoTime)
          loaded
          (-> (load-module compiled compiled-declarations dispatch-specs
                           (compilation-dependency-dispatch-entries compilation)
                           (compilation-dependency-state-entries compilation)
                           jvm-callable-specs
                           jvm-value-specs
                           jvm-type-specs)
              (prepare-loaded-generation generation))]
      (assoc job
             :compilation compilation
             :loaded loaded
             :compiler-duration-ms compiler-duration-ms
             :dynamic-load-duration-ms
             (elapsed-nanos-ms dynamic-load-started-ns)))
    (catch Throwable error
      (throw
       (ex-info "Independent affected-component preparation failed"
                {:aguafria/phase :zig-component-prepare
                 :module module
                 :generation generation}
                error)))))

(defn- prepared-exact-impact-module-state
  [{:keys [module generation old-module plan compilation loaded] :as prepared}
   publication]
  (let [{compiled-declarations :declarations
         :keys [compiled reload-source dispatch-specs
                reload-source-dispatch-specs partial-publication?]}
        compilation
        dispatch (reconcile-dispatch old-module loaded generation)
        compiled-definitions
        (into {} (map (juxt :declaration-key identity)) compiled-declarations)
        functions (if partial-publication?
                    (merge (:functions old-module) (:functions loaded))
                    (:functions loaded))
        values (if partial-publication?
                 (merge (:values old-module) (:values loaded))
                 (:values loaded))
        types (if partial-publication?
                (merge (:types old-module) (:types loaded))
                (:types loaded))
        loaded-declarations
        (published-loaded-declarations old-module loaded partial-publication?)]
    (merge old-module loaded dispatch
           {:module module
            :generation generation
            :published-generation generation
            :requested-generation generation
            :source (get-in plan [:primary :source])
            :reload-source reload-source
            :dependency-source (get-in plan [:primary :dependency-source])
            :dispatch-specs dispatch-specs
            :reload-source-dispatch-specs reload-source-dispatch-specs
            :dependency-dispatch-specs
            (get-in plan [:primary :reload-source-dispatch-specs])
            :functions functions
            :values values
            :types types
            :loaded-declarations loaded-declarations
            :partial-publication? partial-publication?
            :full-compile-error (:full-compile-error compiled)
            :pending nil
            :scheduled nil
            :source-only? false
            :last-error nil
            :last-dependent-publication-error nil
            :failed-generation nil
            :last-dependent-publication-failure nil
            :definitions
            (if partial-publication?
              (merge (:definitions old-module) compiled-definitions)
              compiled-definitions)
            :last-component-publication publication
            :last-component-publication-failure nil})))

(defn- publish-exact-impact-frontier!
  "Atomically publish a prepared set of independent one-module components."
  [prepared]
  (let [prepared (vec (sort-by :module prepared))
        published-at-ms (System/currentTimeMillis)
        publications
        (mapv (fn [{:keys [module requested-at-ms]}]
                (let [duration-ms (- published-at-ms requested-at-ms)]
                  {:id (swap! component-publication-sequence inc)
                   :modules [module]
                   :requested-at-ms requested-at-ms
                   :published-at-ms published-at-ms
                   :duration-ms duration-ms
                   :critical-path-ms duration-ms
                   :partial-publication? true
                   :parallel-prepared? true
                   :compiled-declaration-count 1}))
              prepared)
        prepared-with-publications
        (mapv (fn [entry publication]
                (assoc entry :publication publication))
              prepared publications)
        old-states (into {}
                         (map (juxt :module :old-module))
                         prepared-with-publications)
        published? (atom false)]
    (try
      (let [new-states
            (into {}
                  (map (fn [{:keys [module publication] :as entry}]
                         [module
                          (prepared-exact-impact-module-state
                           entry publication)]))
                  prepared-with-publications)]
        ;; Siblings have no dependency edge between them. Replace their
        ;; registry states together, then expose every new native target under
        ;; one odd dispatch epoch so callers cannot observe a mixed frontier.
        (swap! registry merge new-states)
        (let [publishing (begin-dispatch-publication!)]
          (try
            (refresh-project-dispatch-unguarded!)
            (catch Throwable publication-error
              (swap! registry merge old-states)
              (try
                (refresh-project-dispatch-unguarded!)
                (catch Throwable rollback-error
                  (.addSuppressed publication-error rollback-error)))
              (throw
               (ex-info "Atomic affected-component frontier publication failed"
                        {:aguafria/phase :zig-component-publication
                         :modules (mapv :module prepared)}
                        publication-error)))
            (finally
              (end-dispatch-publication! publishing))))
        (reset! published? true)
        (doseq [{:keys [module compilation compiler-duration-ms
                        dynamic-load-duration-ms planning-duration-ms]}
                prepared-with-publications]
          (publish-clojure-declaration-metadata! (:declarations compilation))
          (schedule-module-generation-retirement! module)
          (mark-build-finished!
           module (:generation (get new-states module)) :finished
           (assoc (:compiled compilation)
                  :compiled-declaration-count (count (:declarations compilation))
                  :planning-duration-ms planning-duration-ms
                  :compiler-duration-ms compiler-duration-ms
                  :dynamic-load-duration-ms dynamic-load-duration-ms
                  :publication-duration-ms
                  (- (System/currentTimeMillis) published-at-ms))))
        publications)
      (catch Throwable error
        (when-not @published?
          (close-prepared-component! prepared)
          (doseq [{:keys [module generation]} prepared]
            (mark-build-failed! module generation error)))
        (throw error)))))

(defn- recompile-component-impact-slice!
  "Republish only exact dependent declarations for an acyclic module.

  The ordinary registration planner closes over same-module prerequisites and
  publishes a partial native generation. Cyclic SCCs continue through the
  atomic component compiler because no member can be published coherently in
  isolation."
  [component impacts ignored-pending]
  (let [members (:modules component)]
    (if (and (seq impacts)
             (not (:cyclic? component))
             (= 1 (count members)))
      (let [module (first members)
            declarations (impacted-component-declarations members impacts)
            results
            (binding [*propagate-dependent-changes?* false
                      *exact-declaration-publication?* true]
              (mapv register-sync! declarations))]
        {:id (swap! component-publication-sequence inc)
         :modules members
         :requested-at-ms (System/currentTimeMillis)
         :partial-publication? true
         :compiled-declaration-count
         (count declarations)
         :results results})
      (compile-component-sync! (first members) ignored-pending))))

(defn- changed-dependent-impacts
  [before-states members]
  (into #{}
        (mapcat
         (fn [module]
           (let [before-by-logical
                 (into {} (map (juxt :logical-id identity))
                       (vals (get-in before-states [module :definitions])))
                 after (vals (get-in @registry [module :definitions]))]
             (keep
              (fn [declaration]
                (let [before (get before-by-logical (:logical-id declaration))]
                  (cond
                    (and before
                         (type-producing-declaration? declaration)
                         (or (not= (:schema-fingerprint before)
                                   (:schema-fingerprint declaration))
                             (not= (:implementation-fingerprint before)
                                   (:implementation-fingerprint declaration))))
                    {:kind :type :logical-id (:logical-id declaration)}

                    (and before
                         (contains? #{:fn :fn-proto} (:kind declaration))
                         (not (type-producing-declaration? declaration))
                         (not (dispatchable-declaration? declaration))
                         (or (not= (:abi-fingerprint before)
                                   (:abi-fingerprint declaration))
                             (not= (:implementation-fingerprint before)
                                   (:implementation-fingerprint declaration))))
                    {:kind :callable :logical-id (:logical-id declaration)})))
              after)))
         members)))

(defn- exact-impact-component-eligible?
  [component impacts]
  (and (seq impacts)
       (not (:cyclic? component))
       (= 1 (count (:modules component)))
       (= 1 (count (impacted-component-declarations (:modules component)
                                                     impacts)))))

(defn- prepare-exact-impact-frontier!
  [components-with-impacts]
  (let [jobs
        (binding [*propagate-dependent-changes?* false
                  *exact-declaration-publication?* true]
          (mapv (fn [[component impacts]]
                  (exact-impact-component-job component impacts))
                components-with-impacts))
        futures (mapv #(future (prepare-exact-impact-job! %)) jobs)
        results
        (mapv (fn [preparation]
                (try
                  {:prepared @preparation}
                  (catch Throwable error
                    {:error (unwrap-component-compile-error error)})))
              futures)
        error (some :error results)
        prepared (mapv :prepared (remove :error results))]
    (if error
      (do
        (close-prepared-component! prepared)
        (doseq [{:keys [module generation]} jobs]
          (mark-build-failed! module generation error))
        (throw error))
      prepared)))

(defn- merge-propagation-impact
  [current next]
  ;; `nil` is the explicit full-component propagation marker. It dominates a
  ;; set of exact logical identities when diamond-shaped fan-out converges.
  (if (or (nil? current) (nil? next))
    nil
    (into (set current) next)))

(defn- add-component-impact
  [impacts component-id next-impact]
  (if (contains? impacts component-id)
    (update impacts component-id merge-propagation-impact next-impact)
    (assoc impacts component-id next-impact)))

(defn- recompile-component-chain!
  [module include-root? ignored-pending initial-impacts]
  (locking compile-lock
    (let [module (str module)
          topology (dependency-topology @registry)
          component-by-module (:component-by-module topology)
          components (into {} (map (juxt :id identity)) (:components topology))
          root-component-id (get component-by-module module)
          root-component (get components root-component-id)
          component-dependency-ids
          (fn [component-id]
            (->> (:modules (get components component-id))
                 (mapcat #(get-in topology [:graph %]))
                 (keep component-by-module)
                 (remove #{component-id})
                 distinct
                 sort
                 vec))
          dependent-component-ids
          (fn [component-id]
            (->> (:modules (get components component-id))
                 (mapcat #(get-in topology [:reverse-graph %]))
                 (keep component-by-module)
                 (remove #{component-id})
                 distinct
                 sort
                 vec))
          live-module?
          (fn [member]
            ;; Converted source registration advances a module's published
            ;; source generation even when no native artifact has ever been
            ;; materialized. Only a loaded native generation is live behavior
            ;; that automatic propagation must update immediately.
            (boolean (seq (get-in @registry [member :native-generations]))))
          live-component-ids
          (into #{}
                (keep
                 (fn [{:keys [id modules]}]
                   (when (some live-module? modules)
                     id)))
                (vals components))
          live-ancestor-component-ids
          (loop [pending (seq live-component-ids)
                 seen #{}]
            (if-let [component-id (first pending)]
              (if (contains? seen component-id)
                (recur (next pending) seen)
                (recur (concat (next pending)
                               (component-dependency-ids component-id))
                       (conj seen component-id)))
              seen))
          live-root-peers
          (->> (:modules root-component)
               (remove #{module})
               (filter live-module?)
               vec)
          root-peer-recompile?
          (and (:cyclic? root-component)
               (or (nil? initial-impacts)
                   (and (seq initial-impacts)
                        (component-references-impact? live-root-peers
                                                      initial-impacts))))
          process-root? (or include-root? root-peer-recompile?)
          candidate-component-ids
          (loop [pending [root-component-id]
                 seen #{}]
            (if-let [component-id (first pending)]
              (if (contains? seen component-id)
                (recur (next pending) seen)
                (recur (concat (next pending)
                               (dependent-component-ids component-id))
                       (conj seen component-id)))
              seen))]
      (when-not (contains? component-by-module module)
        (throw (ex-info "Cannot find an Aguafria module to recompile"
                        {:module module
                         :known-modules (sort (keys @registry))})))
      ;; Component frontiers are Kahn layers of the SCC DAG. That makes
      ;; independent siblings available together and combines impacts before
      ;; a diamond-shaped child is planned. The root module's own generation
      ;; was already published by ordinary registration unless a cyclic SCC
      ;; requires its peers to move atomically.
      (loop [remaining (cond-> candidate-component-ids
                         (not process-root?) (disj root-component-id))
             processed (cond-> #{}
                         (not process-root?) (conj root-component-id))
             impacts
             (if process-root?
               {root-component-id initial-impacts}
               (reduce #(add-component-impact %1 %2 initial-impacts)
                       {}
                       (dependent-component-ids root-component-id)))
             skipped #{}
             publications []
             parallel-frontier-count 0
             parallel-component-count 0]
        (if (empty? remaining)
          {:status :finished
           :root module
           :root-component-recompiled?
           (boolean (some #(= (set (:modules %))
                              (set (:modules root-component)))
                          publications))
           :component-count (count publications)
           :module-count (reduce + 0 (map (comp count :modules) publications))
           :skipped-component-count (count skipped)
           :parallel-frontier-count parallel-frontier-count
           :parallel-component-count parallel-component-count
           :publications publications}
          (let [ready
                (->> remaining
                     (filter
                      (fn [component-id]
                        (every? #(or (not (contains? candidate-component-ids %))
                                     (contains? processed %))
                                (component-dependency-ids component-id))))
                     sort
                     vec)
                _ (when-not (seq ready)
                    (throw
                     (ex-info "Affected component graph has no publishable frontier"
                              {:aguafria/phase :zig-component-topology
                               :root module
                               :remaining (vec (sort remaining))
                               :processed (vec (sort processed))})))
                selected
                (->> ready
                     (keep
                      (fn [component-id]
                        (when (contains? impacts component-id)
                          (let [component (get components component-id)
                                component-impacts (get impacts component-id)
                                members (:modules component)
                                selected?
                                (or (nil? component-impacts)
                                    (= component-id root-component-id)
                                    (and (seq component-impacts)
                                         (contains? live-ancestor-component-ids
                                                    component-id)
                                         (component-references-impact?
                                          members component-impacts)))]
                            (when selected?
                              [component-id component component-impacts])))))
                     vec)
                skipped-now
                (into #{}
                      (filter
                       (fn [component-id]
                         (and (contains? impacts component-id)
                              (not (some #(= component-id (first %))
                                         selected)))))
                      ready)
                before-states
                (into {}
                      (map (fn [[component-id component]]
                             [component-id
                              (select-keys @registry (:modules component))]))
                      selected)
                parallel?
                (and (> (count selected) 1)
                     (every? (fn [[_ component component-impacts]]
                               (exact-impact-component-eligible?
                                component component-impacts))
                             selected))
                frontier-publications
                (if parallel?
                  (->> selected
                       (mapv (fn [[_ component component-impacts]]
                               [component component-impacts]))
                       prepare-exact-impact-frontier!
                       publish-exact-impact-frontier!)
                  (mapv
                   (fn [[_ component component-impacts]]
                     (recompile-component-impact-slice!
                      component component-impacts ignored-pending))
                   selected))
                next-impacts
                (reduce
                 (fn [next-map [component-id component component-impacts]]
                   (let [members (:modules component)
                         propagated
                         (when-not (nil? component-impacts)
                           (changed-dependent-impacts
                            (get before-states component-id) members))]
                     (if (or (nil? propagated) (seq propagated))
                       (reduce #(add-component-impact %1 %2 propagated)
                               next-map
                               (dependent-component-ids component-id))
                       next-map)))
                 impacts
                 selected)]
            (recur (apply disj remaining ready)
                   (into processed ready)
                   next-impacts
                   (into skipped skipped-now)
                   (into publications frontier-publications)
                   (+ parallel-frontier-count (if parallel? 1 0))
                   (+ parallel-component-count
                      (if parallel? (count selected) 0)))))))))

(defn- recompile-dependent-components!
  [module ignored-pending impacts]
  (recompile-component-chain! module false ignored-pending impacts))

(defn recompile-affected!
  "Recompile a module SCC and every transitively dependent SCC in dependency
  order. Each SCC prepares/publishes atomically; unchanged functions continue
  to use Zig's content-addressed cache and compatible dispatch identities."
  [module]
  (recompile-component-chain! module true nil nil))

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

(defn- native-declaration-source
  [declaration]
  (emit/emit-declaration
   (assoc declaration
          :doc nil
          :comments nil
          :leading-source nil
          :source nil
          :emit-source-comment? false)))

(defn- native-declaration-equivalent?
  [old-declaration declaration]
  (and old-declaration
       (= (:logical-id old-declaration) (:logical-id declaration))
       ;; Identical-looking Zig can close over a newer cross-namespace type or
       ;; non-dispatchable comptime implementation. Those fingerprints are
       ;; semantic inputs even when the local expression text is unchanged.
       (= (:abi-fingerprint old-declaration)
          (:abi-fingerprint declaration))
       (= (:schema-fingerprint old-declaration)
          (:schema-fingerprint declaration))
       (= (:implementation-fingerprint old-declaration)
          (:implementation-fingerprint declaration))
       (try
         (= (native-declaration-source old-declaration)
            (native-declaration-source declaration))
         (catch Throwable _
           false))))

(defn- register-unchanged-declaration!
  [module declaration-key declaration]
  (locking compile-lock
    (let [current (get @registry module)
          declaration (stable-source-order (:definitions current) declaration)]
      (swap! registry assoc-in [module :definitions declaration-key] declaration)
      (publish-clojure-declaration-metadata! [declaration])
      {:module module
       :declaration-key declaration-key
       :generation (or (:requested-generation current) (:generation current))
       :async? (:async? @config)
       :pending? (boolean (:pending current))
       :unchanged? true
       :compiled? false})))

(defn- register-source-only-declaration!
  [module declaration-key declaration]
  (locking compile-lock
    (let [current (get @registry module)
          definitions (or (:definitions current) {})
          {:keys [declaration definitions]}
          (replacement-declaration-state definitions declaration)]
      (swap! registry assoc module
             (merge current
                    {:module module
                     :definitions definitions
                     :declarations (vec (vals definitions))
                     :source nil
                     :source-dirty? true
                     :source-only? true
                     :pending nil
                     :scheduled nil
                     :last-error nil
                     :failed-generation nil}))
      (publish-clojure-declaration-metadata! [declaration])
      {:module module
       :declaration-key declaration-key
       :source-only? true
       :compiled? false})))

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
        (let [declaration
              ;; Registering/evaluating an existing descriptor is an explicit
              ;; adoption point. Refresh its referenced Vars before the
              ;; unchanged fast path so stored generated descriptors adopt
              ;; the same current type identities as freshly macroexpanded
              ;; hand-written forms.
              (first (refresh-live-declaration-references [declaration]))
              current (get @registry module)
              old-declaration (get-in current [:definitions declaration-key])
              file-load-registration?
              (boolean
               (some (fn [^StackTraceElement frame]
                       (and (= "clojure.lang.Compiler"
                               (.getClassName frame))
                            (= "load" (.getMethodName frame))))
                     (.getStackTrace (Thread/currentThread))))
              expected-declaration-count
              (when file-load-registration?
                (or (when (project/converted-module? module)
                      (project/expected-declaration-count module))
                    (project/expected-source-declaration-count
                     module (get-in declaration [:source :file]))))
              complete-namespace-loading?
              (and file-load-registration?
                   expected-declaration-count
                   (< (count (assoc (or (:definitions current) {})
                                    declaration-key declaration))
                      expected-declaration-count))]
          (cond
            *source-only-registration?*
            (register-source-only-declaration!
             module declaration-key declaration)

            ;; Generated namespaces are ordinary Clojure source and each Var
            ;; remains available immediately. During the first namespace load,
            ;; however, its EDN catalog gives us the exact declaration count.
            ;; Collect the incomplete prefix without repeatedly emitting and
            ;; dependency-scanning it; the final form schedules one complete
            ;; immutable compilation snapshot.
            complete-namespace-loading?
            (register-source-only-declaration!
             module declaration-key declaration)

            ;; Requiring a converted project makes every Var immediately
            ;; inspectable, but must not eagerly compile every target/test
            ;; namespace reachable through Clojure `:require`. The first
            ;; invocation or explicit `await!` materializes only the demanded
            ;; module and its exact Zig declaration closure. Once a module has
            ;; a published generation, whole-namespace reloads retain the
            ;; normal automatic hot-reload behavior.
            (and file-load-registration?
                 (project/converted-module? module)
                 (nil? (:published-generation current)))
            (register-source-only-declaration!
             module declaration-key declaration)

            (and (not *force-recompile?*)
                 (nil? (:last-error current))
                 (native-declaration-equivalent? old-declaration declaration))
            (register-unchanged-declaration!
             module declaration-key declaration)

            (project/converted-module? module)
            (binding [*file-load-registration?* file-load-registration?]
              (register-converted-async! declaration))

            (:async? @config)
            (binding [*file-load-registration?* file-load-registration?]
              (register-converted-async! declaration))

            :else
            (register-sync! declaration)))))))

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
     (binding [*force-recompile?* true]
       (register-declaration! declaration)))))

(defn await!
  "Materialize a source-only module on demand, then wait for its newest
  asynchronous compilation. With no argument, does this for every known
  module. Throws the compiler error from the newest failed generation."
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
             ;; A completed promise is the publication barrier. Normally its
             ;; publisher clears `:pending` immediately before delivery. A
             ;; superseded async generation can legitimately deliver after a
             ;; newer source-only snapshot copied that promise, though; clear
             ;; only the still-identical completed barrier so `await!` cannot
             ;; spin on it and can materialize the current snapshot below.
             (locking compile-lock
               (swap! registry update module
                      (fn [latest]
                        (if (identical? pending (:pending latest))
                          (assoc latest :pending nil :scheduled nil)
                          latest))))
             ;; Another generation may have been requested while we waited.
             (recur))

           (and last-error (= failed-generation requested-generation))
           (throw last-error)

           (and last-dependent-publication-error
                (= (:generation last-dependent-publication-failure)
                   requested-generation))
           (throw last-dependent-publication-error)

           (:source-only? current)
           (do
             ;; `await!` is already a blocking demand boundary. Materialize
             ;; the source-only snapshot synchronously instead of feeding it
             ;; back through the async debounce scheduler: a superseded or
             ;; already-delivered async promise can otherwise leave the module
             ;; source-only and make this loop repeatedly enqueue generations.
             (let [declaration (some-> current :definitions vals first)]
               (when-not declaration
                 (throw
                  (ex-info "Cannot materialize a module with no declarations"
                           {:module module
                            :known-modules (sort (keys @registry))})))
               (register-sync! declaration))
             (recur))

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
         ;; A standalone build consumes static source directly. Wait for an
         ;; already-requested development generation, but do not create one
         ;; merely because this module was registered source-only.
         _ (when (:pending (get @registry module))
             (await! module))
         module-state (ensure-static-module-source! module)]
     (when-not module-state
       (throw (ex-info "Cannot build an unknown Aguafria module"
                       {:module module :known-modules (sort (keys @registry))})))
     (let [declarations (vec (vals (:definitions module-state)))
           dependency-snapshot (static-dependency-snapshot declarations)
           compiler-options (compiler-options-for-declarations
                             (assoc (merge @config options)
                                    :transitive-dependencies? true
                                    :dependency-snapshot dependency-snapshot)
                             declarations)
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
       (let [result (run-command command
                                 (.getAbsolutePath (.getParentFile output-file)))
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
     :planning-ms
     (metric-summary (keep :planning-duration-ms completed))
     :dependency-preparation-ms
     (metric-summary (keep :dependency-preparation-duration-ms completed))
     :compiler-ms
     (metric-summary (keep :compiler-duration-ms completed))
     :dynamic-load-ms
     (metric-summary (keep :dynamic-load-duration-ms completed))
     :dispatch-publication-ms
     (metric-summary (keep :publication-duration-ms completed))
     :propagation-ms
     (metric-summary (keep :propagation-duration-ms completed))
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
                     (let [definitions (:definitions module-state)
                           dependencies
                           (locking module-dependency-entry-cache
                             (if (.containsKey module-dependency-entry-cache
                                              definitions)
                               (.get module-dependency-entry-cache definitions)
                               (let [value
                                     (->> definitions
                                          vals
                                          emit/declaration-imports
                                          vals
                                          (keep :namespace)
                                          (map str)
                                          distinct
                                          sort
                                          vec)]
                                 (.put module-dependency-entry-cache
                                       definitions value)
                                 (when (> (.size module-dependency-entry-cache)
                                          4096)
                                   (.clear module-dependency-entry-cache)
                                   (.put module-dependency-entry-cache
                                         definitions value))
                                 value)))]
                       [module dependencies])))
              module-states)
        nodes (->> (concat (keys direct) (mapcat val direct)) set sort)]
    (into (sorted-map)
          (map (fn [module] [module (get direct module [])]))
          nodes)))

(defn- compute-dependency-topology
  [graph]
  (let [nodes (vec (keys graph))
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

(defn- dependency-topology
  [module-states]
  (let [graph (module-dependency-graph module-states)
        cached @dependency-topology-cache]
    (if (and (= graph (:graph cached)) (:topology cached))
      (:topology cached)
      (let [topology (compute-dependency-topology graph)]
        (reset! dependency-topology-cache
                {:graph graph :topology topology})
        topology))))

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
           host-statuses (frequencies (map :status host-views))
           source-cache (module-source-cache-stats)]
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
                  :module-source-cache-hit-count (:hit-count source-cache)
                  :module-source-cache-miss-count (:miss-count source-cache)
                  :native-host-count (count host-views)
                  :active-native-host-count
                  (+ (get host-statuses :starting 0)
                     (get host-statuses :running 0))
                  :finished-native-host-count (get host-statuses :finished 0)
                  :failed-native-host-count (get host-statuses :failed 0)}
        :modules modules
        :dependency-components (:components topology)
        :module-source-cache source-cache
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

(defn- materialize-declaration-generation!
  "Publish one exact declaration slice for a Clojure-demanded Var.

  `request-key` selects the JVM wrapper registry set to extend, or nil when
  the declaration's ordinary reload/state hooks are sufficient."
  [declaration request-key]
  (let [module (:module declaration)]
    (when (:pending (get @registry module))
      (await! module))
    (when request-key
      (locking compile-lock
        (swap! registry update-in [module request-key]
               (fnil conj #{}) (:declaration-key declaration))))
    (binding [*propagate-dependent-changes?* false
              *exact-declaration-publication?* true
              *materialize-declaration-key* (:declaration-key declaration)]
      (register-sync! declaration))
    (await-callable-generation! module)))

(defn- materialize-jvm-callable!
  "Compile a development-only C ABI trampoline for a registered Zig Var whose
  original declaration is intentionally not `export`. Final/static Zig source
  and the declaration's Zig visibility remain unchanged."
  [qualified-name]
  (let [module (namespace qualified-name)]
    ;; Registration alone is intentionally source-only. Wait only for an
    ;; already requested edit; do not turn a whole converted namespace into a
    ;; prerequisite for calling one Var.
    (when (:pending (get @registry module))
      (await! module))
    (when-not (function-loaded? qualified-name)
      (let [declaration
            (locking compile-lock
              (current-function-declaration (get @registry module)
                                            qualified-name))]
        (when-not declaration
          (throw (ex-info "Zig function is not registered"
                          {:function qualified-name :module module})))
        (materialize-declaration-generation!
         declaration :jvm-callable-declaration-keys)))))

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
        (materialize-declaration-generation!
         declaration :jvm-type-declaration-keys)
        true))))

(defn materialize-type!
  "Construct a persistent native value from an ordinary callable Zig type Var."
  [declaration clojure-value]
  (let [module (:module declaration)
        type-name (:name declaration)
        qualified-name (symbol module (str type-name))]
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
    (when (:pending (get @registry module))
      (await! module))
    (when-not (current-value-binding qualified-name)
      (materialize-declaration-generation!
       declaration :jvm-value-declaration-keys))
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
    (when (:pending (get @registry module))
      (await! module))
    (let [qualified-name (symbol module (str name))
          storage-wrapper?
          (and (storage-helper-type? type)
               (nil? (current-value-binding qualified-name)))
          active-state?
          (locking compile-lock
            (boolean
             (some #(and (= logical-id (:logical-id %)) (:active? %))
                   (get-in @registry [module :state-versions]))))]
      (when (or storage-wrapper? (not active-state?))
        (materialize-declaration-generation!
         declaration
         (when storage-wrapper? :jvm-value-declaration-keys))))
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
        _ (when (:pending (get @registry module))
            (await! module))
        declaration
        (locking compile-lock
          (current-function-declaration (get @registry module)
                                        qualified-name))
        _ (materialize-jvm-callable! qualified-name)
        _ (doseq [zig-type (concat (map :type (:args declaration))
                                   [(:return declaration)])
                  :when (and zig-type
                             (not= :void zig-type)
                             (not (contains? scalar-layouts
                                             (scalar-key zig-type)))
                             (nil? (native-type-schema module zig-type)))]
            (ensure-native-type-binding! module zig-type))
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
       "const application = @import(" (emit/emit-expr target-module) ")."
       (emit/named-module-container target-module) ";\n\n"
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
  [host-module target-module target-declaration]
  {:kind :const
   :name 'application
   :declaration-key [:host-import target-module]
   :module host-module
   :public? false
   :export? false
   ;; The wrapper source calls the entry point through a generated Zig import,
   ;; rather than through a Clojure symbol in its declaration body. Preserve
   ;; that exact Var edge explicitly so the declaration-unit linker retains
   ;; only the entry point's transitive dispatch/state cells in the host.
   :callable-dependency-fingerprints
   [[(:logical-id target-declaration)
     (:abi-fingerprint target-declaration)
     (:implementation-fingerprint target-declaration)]]
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
                   (process-main-host-declaration host-module target-module
                                                  declaration)
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
