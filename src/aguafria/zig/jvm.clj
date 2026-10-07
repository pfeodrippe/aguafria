(ns aguafria.zig.jvm
  "Native specialization and value transport for Clojure and Java callers.

  Comptime inputs remain in Zig source, never in an invalid C ABI trampoline.
  Concrete call adapters run in the same live module as the original function."
  (:require [aguafria.zig.convert :as convert]
            [aguafria.keyword :as keyword]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.handlers :as handlers]
            [aguafria.zig.peer-call :as peer-call]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.signature :as signature]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file Files]
           [java.util ArrayList]))

(defonce ^:private output-lock (Object.))
(defonce ^:private prepared-adapters (atom #{}))
(defonce ^:private prepared-coercions (atom #{}))
(defonce ^:private tuple-profiles (atom {}))
(defonce ^:private scoped-plan-proofs (atom {}))
(defonce ^:private test-resources (atom {}))
(def ^:dynamic ^:private *capturing-output?* false)

(def ^:private posix-output
  (delay
    (let [linker (Linker/nativeLinker)
          lookup (.defaultLookup linker)]
      (into {}
            (for [[name result arguments]
                  [["dup" ValueLayout/JAVA_INT [ValueLayout/JAVA_INT]]
                   ["dup2" ValueLayout/JAVA_INT [ValueLayout/JAVA_INT ValueLayout/JAVA_INT]]
                   ["close" ValueLayout/JAVA_INT [ValueLayout/JAVA_INT]]
                   ["fflush" ValueLayout/JAVA_INT [ValueLayout/ADDRESS]]
                   ["open" ValueLayout/JAVA_INT [ValueLayout/ADDRESS ValueLayout/JAVA_INT]]]]
              [(keyword name)
               (.downcallHandle linker (.orElseThrow (.find lookup name))
                                (FunctionDescriptor/of result (into-array MemoryLayout arguments))
                                (make-array Linker$Option 0))])))))

(defn- fd-call [operation & arguments]
  (let [result (.invokeWithArguments ^MethodHandle (get @posix-output operation)
                                     (ArrayList. ^java.util.Collection arguments))]
    (when (neg? (long result))
      (throw (ex-info "Cannot route native output" {:operation operation :arguments arguments})))
    result))

(defn- capture-stream! [descriptor writer]
  (let [path (Files/createTempFile "aguafria-native-output-" ".log"
                                   (make-array java.nio.file.attribute.FileAttribute 0))
        backup (fd-call :dup (int descriptor))]
    (try
      (with-open [arena (Arena/ofConfined)]
        (let [file (fd-call :open (.allocateFrom arena (str path)) (int 1))]
          (try
            (fd-call :dup2 (int file) (int descriptor))
            (finally (fd-call :close (int file))))))
      {:descriptor descriptor :backup backup :path path :writer writer}
      (catch Throwable failure
        (fd-call :close (int backup))
        (Files/delete path)
        (throw failure)))))

(defn call-with-output
  "Forward native stdout/stderr to the caller's bound Clojure writers.

  POSIX descriptors are process-wide. Short synchronous calls are serialized
  during capture; long-running native application hosts should inherit their
  process streams instead. No nREPL middleware is involved."
  [invoke]
  (if (or *capturing-output?*
          (not (or (thread-bound? #'*out*) (thread-bound? #'*err*))))
    (invoke)
    (locking output-lock
      (binding [*capturing-output?* true]
        (let [streams (atom [])]
          (try
            (fd-call :fflush MemorySegment/NULL)
            (swap! streams conj (capture-stream! 1 *out*))
            (swap! streams conj (capture-stream! 2 *err*))
            (invoke)
            (finally
              ;; Restore both process streams before touching a Clojure writer.
              ;; Its destination may itself ultimately write to stdout/stderr.
              (try
                (fd-call :fflush MemorySegment/NULL)
                (finally
                  (doseq [{:keys [descriptor backup]} @streams]
                    (try
                      (fd-call :dup2 (int backup) (int descriptor))
                      (finally (fd-call :close (int backup)))))))
              (doseq [{:keys [path writer]} @streams]
                (try
                  (with-open [reader (io/reader (.toFile path) :encoding "UTF-8")]
                    (io/copy reader writer)
                    (.flush ^java.io.Writer writer))
                  (finally (Files/delete path)))))))))))

(defn- token [value]
  (subs (runtime/adapter-fingerprint value) 0 24))

(defn- registered-adapter? [prepared key function]
  (and (contains? @prepared key)
       (runtime/registered-declaration?
        (namespace function) [:fn (symbol (name function))])))

(defn- adapter-local-names [namespace names]
  (:names
   (reduce
    (fn [{:keys [used] :as state} preferred]
      (let [selected
            (first
             (remove #(contains? used (emitter/identifier %))
                     (cons preferred
                           (map #(symbol (str "__aguafria_jvm_" preferred "_" %))
                                (range)))))]
        (-> state
            (update :names conj selected)
            (update :used conj (emitter/identifier selected)))))
    {:names []
     :used (into #{}
                 (map #(emitter/identifier (or (:zig-name %) (:name %))))
                 (runtime/registered-declarations (ns-name namespace)))}
    names)))

(defn- register! [namespace descriptor]
  (binding [emitter/*registered-declaration-names*
            (set (map :name (runtime/registered-declarations (ns-name namespace))))]
    (runtime/register-declaration!
     (emitter/prepare-declaration
      namespace
      (merge {:module (str (ns-name namespace)) :public? false :export? false
              :jvm-adapter? true
              :implicit-return? true}
             descriptor)))))

(declare coerce! call-inputs prepared-operand canonical-comptime-object canonical-comptime-source-operands
         call-parameters invoke-scoped! invoke-syntax!
         precompile-tuple-sequence! tuple-profile-key)

(def ^:dynamic ^:private *retain-result-generation* nil)

(defn- comptime-expression [operand]
  (when (value/zig-value? operand)
    (let [state (value/realize! operand)
          type (value/qualified-type operand)]
      (cond
        (:comptime-expression state) (:comptime-expression state)

        ;; Zig transports comptime integers losslessly. Their producing
        ;; expression is irrelevant to subsequent specialization identity.
        (and (= :scalar (:representation state)) (= :comptime_int type))
        (:value state)

        (or (= :comptime-expression (:representation state))
            (and (= :scalar (:representation state)) (= :comptime_float type)))
        (:expression state)))))

(defn- inspection-view [result]
  (walk/postwalk
   (fn [item]
     (cond
       (and (map? item) (= #{:aguafria.jvm/struct} (set (keys item))))
       (into (array-map)
             (map (fn [[name field]] [(keyword name) field]))
             (:aguafria.jvm/struct item))

       (and (map? item) (= #{:aguafria.jvm/enum} (set (keys item))))
       (keyword (:aguafria.jvm/enum item))

       (and (map? item) (= #{:aguafria.jvm/pointer} (set (keys item))))
       (let [{:keys [address type]} (:aguafria.jvm/pointer item)]
         (value/->ZigPointer address type))

       :else item))
   result))

(defn- expression-without-runtime-inputs
  [namespace expression parameters]
  (let [expression (emitter/qualify-form namespace expression)
        parameters (mapv #(update % :type (partial emitter/qualify-type namespace))
                         parameters)]
    (emitter/qualify-form
     namespace
     (if (empty? parameters)
       (or (:aguafria/jvm-value-expression (meta expression)) expression)
     ;; @TypeOf must analyze unknown runtime operands, not evaluate a known
     ;; undefined pointer. Runtime locals preserve that distinction, including
     ;; pointer dereferences and slices. This block is used only for type queries.
       (let [id (token [expression parameters])
             label (keyword (str "jvm_type_" id))
             names (into {} (map (fn [{:keys [name]}]
                                   [name (symbol (str "__jvm_type_arg_" id "_" name))]) parameters))
             expression (walk/postwalk-replace names expression)]
         (list 'aguafria.zig/with-block label
               (list* 'let
                      (vec (mapcat (fn [{:keys [name type]}]
                                     [(names name) (list 'aguafria.keyword/var
                                                         'aguafria.keyword/undefined type)])
                                   parameters))
                      (concat
                       (map (fn [{:keys [name]}]
                              (list 'aguafria.keyword/= :_ (list 'aguafria.keyword/& (names name))))
                            parameters)
                       [(list 'aguafria.keyword/break label expression)]))))))))

(defn- expression-cleanup-name [native?]
  (symbol (str "__jvm_release" (when native? "_native")
               (when runtime/*native-test-context?* "_test"))))

(defn- expression-value [descriptor materialize]
  (value/native-value
   (cond-> descriptor runtime/*native-test-context?* (assoc :execution-context :test))
   materialize))

(defn- native-expression-result
  [namespace expression parameters arguments {:keys [address size alignment path scalar-type native-type tuple-length comptime-expression enum-schema]}]
  (let [module (str (ns-name namespace))
        enum-schema (when enum-schema
                      (update enum-schema :members
                              #(mapv (fn [member]
                                       (-> member
                                           (update :name keyword)
                                           (update :bytes (partial mapv unchecked-byte)))) %)))
        root-type (list 'aguafria.keyword/TypeOf
                        (expression-without-runtime-inputs namespace expression parameters))
        type (or native-type scalar-type
                 (reduce (fn [type [kind member]]
                           (list 'aguafria.zig/field
                                 (list 'aguafria.zig/field
                                       (list 'aguafria.keyword/typeInfo type) kind)
                                 member))
                         (or (:aguafria/jvm-result-type (meta expression)) root-type)
                         (partition 2 path)))
        release-generation! (*retain-result-generation*)
        test-context? runtime/*native-test-context?*
        release (symbol module (str (expression-cleanup-name true)))
        result (expression-value
                {:module module :kind :return :type type}
                (fn []
                  {:representation :native
                   :segment (.reinterpret (MemorySegment/ofAddress address) size)
                   :size size :alignment alignment :owners arguments
                   :tuple-length tuple-length
                   :comptime-expression comptime-expression
                   :schema (or enum-schema (when (and scalar-type
                                                      (or (#{:usize :isize :f32 :f64} scalar-type)
                                                          (re-matches #"[iu][0-9]+" (name scalar-type))))
                                             {:kind :scalar :type scalar-type}))
                   :close! #(try
                              (binding [runtime/*native-test-context?* test-context?]
                                (runtime/invoke! release [address size alignment]))
                              (finally (release-generation!)))}))]
    ;; The allocation already exists, so register its cleanup immediately.
    (value/value result)
    result))

(defn- expression-result
  [namespace expression parameters arguments result]
  (cond
    (and (map? result) (= #{:aguafria.jvm/error-union} (set (keys result))))
    (let [{native-type :type envelope :value} (:aguafria.jvm/error-union result)
          type (or native-type (:aguafria/jvm-result-type (meta expression))
                   (list 'aguafria.keyword/TypeOf
                         (expression-without-runtime-inputs namespace expression parameters)))]
      (with-meta (expression-result namespace expression parameters arguments envelope)
        {:aguafria/native-error-union-type type
         :aguafria/native-error-union-owners arguments}))

    (and (map? result) (= #{:aguafria.jvm/comptime-expression} (set (keys result))))
    (let [source (expression-without-runtime-inputs namespace expression parameters)
          envelope (:aguafria.jvm/comptime-expression result)]
      (if-let [native (get-in envelope [:native :aguafria.jvm/native])]
        (native-expression-result namespace expression parameters arguments
                                  (assoc native :comptime-expression source))
        (let [snapshot (inspection-view (:snapshot envelope))]
          (expression-value
           {:module (str (ns-name namespace)) :kind :const
            :type (list 'aguafria.keyword/TypeOf source)}
           (constantly {:representation :comptime-expression
                        :expression source
                        :decoded-fn (fn [_] snapshot)})))))

    (and (map? result) (= #{:aguafria.jvm/borrowed} (set (keys result))))
    (let [{:keys [address size alignment pointer-alignment mutable? scalar-type native-kind native-type tuple-length]} (:aguafria.jvm/borrowed result)
          pointer (list 'aguafria.zig/field
                        (expression-without-runtime-inputs namespace expression parameters)
                        :pointer)
          type (or native-type scalar-type
                   (:aguafria/jvm-borrowed-type (meta expression))
                   (emitter/qualify-form
                    namespace
                    (list 'aguafria.keyword/TypeOf (list 'aguafria.zig/deref pointer))))
          release-generation! (*retain-result-generation*)
          view (expression-value
                {:module (str (ns-name namespace))
                 :kind (if mutable? :var :const) :type type}
                (constantly {:representation :native
                             :segment (.reinterpret (MemorySegment/ofAddress address) size)
                             :size size :alignment alignment :owners arguments
                             :pointer-alignment pointer-alignment
                             :native-kind native-kind
                             :tuple-length tuple-length
                             :schema (when (and scalar-type
                                                (or (#{:usize :isize :f32 :f64} scalar-type)
                                                    (re-matches #"[iu][0-9]+" (name scalar-type))))
                                       {:kind :scalar :type scalar-type})
                             :close! release-generation!}))]
      (value/realize! view)
      (value/retain-storage-provenance! view arguments))

    (and (map? result) (= #{:aguafria.jvm/comptime} (set (keys result))))
    (let [{:keys [type value]} (:aguafria.jvm/comptime result)]
      (expression-value {:kind :const :type type}
                        (constantly
                         (cond-> {:representation :scalar :value value}
                           (#{:comptime_int :comptime_float} type)
                           (assoc :expression (expression-without-runtime-inputs
                                               namespace expression parameters))))))

    (and (map? result) (= #{:aguafria.jvm/native} (set (keys result))))
    (native-expression-result namespace expression parameters arguments (:aguafria.jvm/native result))

    (and (map? result) (= #{:aguafria.jvm/pointer} (set (keys result))))
    (let [{:keys [address type]} (:aguafria.jvm/pointer result)]
      (value/->ZigPointer address type))

    (and (map? result) (= #{:aguafria.jvm/error} (set (keys result))))
    (let [{:keys [name members]} (:aguafria.jvm/error result)]
      (value/->ZigError name (if members [:error-set (mapv keyword members)] :anyerror)))

    (and (map? result) (= #{:aguafria.jvm/type} (set (keys result))))
    ;; Use the compiler's exact structural schema when representable. Retain
    ;; the native expression for nominal/qualified types; never infer a type
    ;; from the display name or erase its identity to match another adapter.
    (let [{:keys [name schema]} (:aguafria.jvm/type result)
          type (or schema (expression-without-runtime-inputs namespace expression parameters))]
      (value/zig-type {:kind :type :type type :zig-name name}
                      #(coerce! % type)))

    (and (map? result) (= #{:aguafria.jvm/struct} (set (keys result))))
    (inspection-view result)

    (map? result)
    (into (empty result)
          (map (fn [[key item]]
                 [key (expression-result namespace expression parameters arguments item)]))
          result)

    (vector? result)
    (mapv (fn [index item]
            (expression-result namespace
                               (list 'aguafria.zig/index expression index)
                               parameters arguments item))
          (range) result)

    :else result))

(defn- result-helper-symbol [source]
  (let [module (symbol (str "aguafria.jvm.transport-" (token source)))
        name '__aguafria_jvm
        reference {:kind :const :module (str module) :name name
                   :zig-name (str name) :declaration-kind :const
                   :symbol (symbol (str module) (str name)) :public? true}]
    (with-meta (:symbol reference) {:aguafria/zig-reference reference})))

(defn- result-helper-reference! [source]
  ;; A separate Zig file gives the transport its own lexical scope. Inlining
  ;; it in a user's container makes ordinary names such as `text` shadow its
  ;; locals, and Zig correctly rejects that even inside a nested struct.
  (let [helper (result-helper-symbol source)
        reference (:aguafria/zig-reference (meta helper))
        module (symbol (:module reference))
        context (or (find-ns module) (create-ns module))
        name '__aguafria_jvm]
    (locking context
      (when-not (runtime/registered-declaration? module [:raw name])
        (binding [runtime/*source-only-registration?* true]
          (register! context {:kind :raw :name name
                              :declaration-key [:raw name] :code source})))
      (alter-meta! (or (ns-resolve context name) (intern context name nil))
                   assoc :aguafria/zig-reference reference))
    helper))

(defn- declared-member-reference [form]
  (cond
    (and (seq? form) (= 2 (count form))
         (contains? #{'type 'aguafria.zig/type} (first form)))
    (declared-member-reference (second form))

    (and (seq? form) (= 2 (count form))
         (contains? #{'deref 'aguafria.zig/deref} (first form))
         (seq? (second form)) (= 'aguafria.keyword/as (first (second form)))
         (= 3 (count (second form)))
         (vector? (last (second form)))
         (#{:* :*const} (first (last (second form)))))
    ;; The native adapter explicitly casts this pointer to its receiver type.
    ;; Preserve that declaration's member scope in subsequent type queries.
    (declared-member-reference (last (last (second form))))

    (qualified-symbol? form)
    (when-let [declaration (:aguafria/declaration (meta (find-var form)))]
      {:declaration declaration :module (:module declaration)
       :private? (false? (:public? declaration))})

    (and (seq? form) (= 3 (count form))
         (symbol? (first form)) (= "field" (name (first form)))
         (or (symbol? (nth form 2)) (keyword? (nth form 2)) (string? (nth form 2))))
    (when-let [{:keys [declaration module private?]} (declared-member-reference (second form))]
      (let [context (the-ns (symbol module))
            spelling (emitter/identifier (nth form 2))
            members (:members (emitter/container-description context (:value declaration)))
            member (first (filter #(= spelling (emitter/identifier (or (:zig-name %) (:name %))))
                                  (filter :name members)))]
        (when member
          {:declaration member :module module
           :private? (or private?
                         (and (not (#{:field :tuple-field :enum-field} (:kind member)))
                              (false? (:public? member))))})))

    :else nil))

(defn- private-adapter-context [context forms]
  (let [owners (into #{}
                     (keep (fn [form]
                             (let [{:keys [module private?]} (declared-member-reference form)]
                               (when private? module))))
                     (tree-seq coll? seq forms))]
    (when (> (count owners) 1)
      (throw (ex-info "A JVM adapter cannot access private declarations from different Zig modules"
                      {:aguafria/phase :adapter-planning :private-modules (sort owners)})))
    ;; Reflection can mention a private function deep inside a pointer/array
    ;; schema. Emit the adapter in that function's scope, not just when the
    ;; outermost schema is a private type factory.
    (if-let [owner (first owners)]
      (the-ns (symbol owner))
      context)))

(defn- function-adapter-location [module name forms]
  (let [default (or (find-ns module) (create-ns module))
        context (private-adapter-context default forms)
        name (if (= default context) name
                 (symbol (str "__jvm_" name "_" (token forms))))]
    {:context context :name name
     :function (symbol (str (ns-name context)) (str name))}))

(defn- expression-adapter-plan
  "Build the exact transport declarations without publishing helper or adapter
  state. Optional preconditions run in the same invocation as the result."
  [namespace expression parameters result-writer {:keys [preconditions]}]
  (let [expression (emitter/qualify-form namespace expression)
        parameters (mapv #(update % :type (partial emitter/qualify-type namespace))
                         parameters)
        result-writer (if (vector? result-writer)
                        (into [(first result-writer)]
                              (map (partial emitter/qualify-form namespace))
                              (rest result-writer))
                        result-writer)
        preconditions (mapv (partial emitter/qualify-form namespace) preconditions)
        namespace (private-adapter-context namespace (cond-> [expression parameters result-writer]
                                                       (seq preconditions) (conj preconditions)))]
    ;; Discovery and live values must carry the same resolved type references
    ;; before hashing, not only when register! qualifies the declaration.
    (let [local-names (adapter-local-names namespace (map :name parameters))
          renames (zipmap (map :name parameters) local-names)
          expression (walk/postwalk-replace renames expression)
          preconditions (mapv (partial walk/postwalk-replace renames) preconditions)
          parameters (mapv #(assoc %1 :name %2) parameters local-names)
          module (str (ns-name namespace))
          helper-source (slurp (io/resource (if (= 'layoutResult result-writer)
                                              "aguafria/jvm_layout.zig"
                                              "aguafria/jvm_result.zig")))
            ;; A declaration lookup reads one receiver schema generation.
          field-schemas
          (when (and (vector? result-writer)
                     (= 'declarationFieldResult (first result-writer)))
            (->> (tree-seq coll? seq (second result-writer))
                 (keep #(when-let [reference (:aguafria/zig-reference (meta %))]
                          (when (:schema-fingerprint reference)
                            [(:logical-id reference) (:schema-fingerprint reference)])))
                 distinct
                 (sort-by pr-str)
                 vec))
          call-name (symbol (str "__jvm_call_"
                                 (token (cond-> [expression parameters helper-source result-writer]
                                          (seq field-schemas) (conj field-schemas)
                                          (seq preconditions) (conj [:preconditions preconditions])
                                          runtime/*native-test-context?* (conj :test)))))
          release-name (expression-cleanup-name false)
          release-native-name (expression-cleanup-name true)
          helper-name (result-helper-symbol helper-source)
          expression (let [metadata (meta expression)
                           replaced (walk/postwalk-replace {'__aguafria_jvm helper-name} expression)]
                       (if metadata (with-meta replaced metadata) replaced))
          [writer writer-arguments] (if (vector? result-writer)
                                      [(first result-writer) (rest result-writer)]
                                      [result-writer nil])
          preconditions (mapv (partial walk/postwalk-replace {'__aguafria_jvm helper-name})
                              preconditions)
          adapter-key (cond-> [module call-name expression parameters helper-source result-writer]
                        (seq preconditions) (conj [:preconditions preconditions]))
          [release-address release-size release-alignment]
          (adapter-local-names namespace '[address size alignment])]
      {:declarations [{:kind :fn :name release-name
                       :qualified-name (symbol module (str release-name))
                       :declaration-key [:fn release-name]
                       :return :void :args [{:name release-address :type :usize}]
                       :body [(list (list 'field helper-name :release) release-address)]}
                      {:kind :fn :name release-native-name
                       :qualified-name (symbol module (str release-native-name))
                       :declaration-key [:fn release-native-name]
                       :return :void
                       :args [{:name release-address :type :usize}
                              {:name release-size :type :usize}
                              {:name release-alignment :type :usize}]
                       :body [(list (list 'field helper-name :releaseNative)
                                    release-address release-size release-alignment)]}
                      {:kind :fn :name call-name
                       :qualified-name (symbol module (str call-name))
                       :declaration-key [:fn call-name]
                       :return :usize :args parameters
                       :body (vec (concat
                                   (for [condition preconditions]
                                     (list 'if (list 'aguafria.keyword/! condition)
                                           (list 'return
                                                 (list (list 'field helper-name :scopedCaptureMismatch)))))
                                   [(apply list (list 'field helper-name (keyword writer))
                                           expression writer-arguments)]))}]
       :helper-source helper-source :helper-reference helper-name
       :function (symbol module (str call-name))
       :context namespace
       :release (symbol module (str release-name))
       :release-native (symbol module (str release-native-name))
       :expression expression
       :parameters parameters
       :adapter-key adapter-key})))

(defn- publish-expression-plan! [{:keys [context function release release-native adapter-key
                                         helper-source declarations] :as plan}]
  (locking context
    (when-not (and (registered-adapter? prepared-adapters adapter-key function)
                   (runtime/registered-declaration? (namespace release) [:fn (symbol (name release))])
                   (runtime/registered-declaration? (namespace release-native) [:fn (symbol (name release-native))]))
      (result-helper-reference! helper-source)
      (binding [runtime/*source-only-registration?* true]
        (doseq [declaration declarations] (register! context declaration))))
    plan))

(defn- prepare-expression!
  "Register the exact adapter used by invocation, without executing it."
  [namespace expression parameters result-writer]
  (locking namespace
    (publish-expression-plan!
     (expression-adapter-plan namespace expression parameters result-writer nil))))

(defn- precompile-expression! [adapter]
  ;; The result envelope and owned native payload have separate lifetimes.
  ;; Prepare both cleanup paths; GC must not trigger a first-time compilation.
  (doseq [function ((juxt :function :release :release-native) adapter)]
    (runtime/precompile-function! function))
  (swap! prepared-adapters conj (:adapter-key adapter)))

(defn- invoke-adapter!
  [{:keys [function release expression adapter-key context parameters]} arguments]
  (binding [runtime/*native-test-context?*
            (or runtime/*native-test-context?*
                (some #(and (value/zig-value? %)
                            (= :test (:execution-context (value/info %)))) arguments))]
    (locking context
      (runtime/invoke-with-result!
       function arguments
       (fn [address retain-generation!]
         (try
           (let [result (edn/read-string
                         (.getString (.reinterpret (MemorySegment/ofAddress address)
                                                   Long/MAX_VALUE) 0))]
             ;; The compiler-validated adapter has run successfully even when
             ;; its live precondition rejects this invocation. Keep its exact
             ;; prepared identity before interpreting that bridge envelope;
             ;; retrying changed data must not revalidate the published plan.
             (swap! prepared-adapters conj adapter-key)
             (when (= {:aguafria.jvm/scoped-capture-mismatch true} result)
               (throw (ex-info "Scoped capture changed; the original compiler-known branch is no longer valid"
                               {:aguafria/phase :scoped-capture-contract
                                :reason :scoped-capture-value-changed :function function})))
             (binding [*retain-result-generation* retain-generation!]
               (expression-result context expression parameters arguments result)))
           (finally
             (runtime/invoke! release [address]))))))))

(defn- invoke-expression!
  ([namespace expression parameters arguments]
   (invoke-expression! namespace expression parameters arguments 'result))
  ([namespace expression parameters arguments result-writer]
   (binding [runtime/*native-test-context?*
             (or runtime/*native-test-context?*
                 (some #(and (value/zig-value? %)
                             (= :test (:execution-context (value/info %)))) arguments))]
     (invoke-adapter! (prepare-expression! namespace expression parameters result-writer)
                      arguments))))

(defn- constant-reader-plan
  [{:keys [module name type] :as declaration}]
  (let [namespace (symbol module)
        context (or (find-ns namespace) (create-ns namespace))
        reference (with-meta (symbol module (str name))
                    {:aguafria/zig-reference (runtime/declaration-reference declaration)})]
    ;; Pure-Zig editor documents register native declarations, not Clojure Vars.
    ;; The published descriptor supplies the reference and exact Zig spelling.
    (prepare-expression!
     context
     (with-meta (list 'aguafria.keyword/& reference)
       {:aguafria/jvm-borrowed-type (or type (list 'aguafria.keyword/TypeOf reference))
        :aguafria/jvm-value-expression reference})
     [] ['constantResult (boolean type)])))

(defn precompile-constant-reader!
  "Prepare the ordinary lazy constant reader and cleanup without reading it."
  [declaration]
  (precompile-expression! (constant-reader-plan declaration))
  {:constant (symbol (:module declaration) (str (:name declaration)))
   :status :prepared})

(defn declared-constant-value!
  "Let Zig transport compiler-only constants or borrow addressable declarations."
  [declaration]
  (invoke-adapter! (constant-reader-plan declaration) []))

(defn qualify-native-type
  "Bind declaration references to their source namespace before moving a type
  expression into a shared JVM adapter. This does not infer its Zig type."
  [context type]
  (emitter/qualify-type
   context
   (walk/postwalk
    (fn [form]
      (if-let [v (when (and (symbol? form) (nil? (namespace form)))
                   (ns-resolve context form))]
        (if (:aguafria/zig-reference (meta v))
          (with-meta (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))
            (meta form))
          form)
        form))
    type)))

(defn- type-equivalence-plan [module expected actual]
  (let [context (the-ns (symbol module))
        expected (qualify-native-type context expected)
        actual (qualify-native-type context actual)
        name (symbol (str "aguafria.jvm.type-equivalence-" (token [expected actual])))]
    {:context (private-adapter-context (or (find-ns name) (create-ns name)) [expected actual])
     :expression (list 'aguafria.keyword/==
                       (list 'aguafria.zig/type expected)
                       (list 'aguafria.zig/type actual))}))

(defn precompile-type-equivalence!
  "Prepare Zig's type equality check without invoking it."
  [module expected actual]
  (let [{:keys [context expression]} (type-equivalence-plan module expected actual)]
    (precompile-expression! (prepare-expression! context expression [] 'inspectResult))))

(defn equivalent-types?
  "Ask Zig whether two qualified type expressions denote the same type.
  Different Clojure spellings are not evidence of incompatible native storage."
  [module expected actual]
  (let [{:keys [context expression]} (type-equivalence-plan module expected actual)]
    (invoke-expression! context expression [] [] 'inspectResult)))

(defn- inspection-expression [type]
  (list 'if
        (list 'aguafria.keyword/comptime
              (list '(field __aguafria_jvm :requiresComptime)
                    (list 'aguafria.zig/type type)))
        nil
        (list 'aguafria.zig/deref
              (list 'aguafria.keyword/as
                    '(aguafria.keyword/ptrFromInt address)
                    [:*const type]))))

(defn- structural-schema?
  [type]
  (cond
    (keyword? type) true
    (vector? type) (every? structural-schema? type)
    (map? type) (every? structural-schema? (concat (keys type) (vals type)))
    (seq? type) (and (contains? #{'aguafria.keyword/Tuple 'aguafria.keyword/&
                                  'aguafria.zig/type} (first type))
                     (every? structural-schema? (rest type)))
    :else (not (symbol? type))))

(defn result-reader-type
  "Use the producer's actual return type for nominal or computed results.
  Structural schemas remain shareable across producers."
  [{:keys [module name return reference] :as declaration}]
  (if (structural-schema? return)
    return
    (list 'aguafria.zig/unwrap
          (list 'aguafria.zig/field
                (list 'aguafria.zig/field
                      (list 'aguafria.keyword/typeInfo
                            (list 'aguafria.keyword/TypeOf
                                  (with-meta (symbol module (str name))
                                    {:aguafria/zig-reference
                                     (or reference
                                         (runtime/declaration-reference declaration))})))
                      :fn)
                "return_type"))))

(defn argument-reader-type
  "Preserve an inline container parameter's exact Zig type. Re-emitting its
  literal creates a different nominal type; reflect the original parameter."
  [{:keys [module name args]} index]
  (let [type (:type (nth args index))
        inline-container? (some #(and (seq? %) (symbol? (first %))
                                      (= "container" (clojure.core/name (first %))))
                                (tree-seq coll? seq type))]
    (if-not inline-container?
      type
      (qualify-native-type
       (the-ns 'aguafria.zig.jvm)
       (list 'aguafria.zig/unwrap
             (list 'aguafria.zig/index
                   (list 'aguafria.zig/field
                         (list 'aguafria.zig/field
                               (list 'aguafria.keyword/typeInfo
                                     (list 'aguafria.keyword/TypeOf
                                           (symbol module (str name))))
                               :fn)
                         "param_types")
                   index))))))

(defn- inspection-context [context type]
  ;; Structural schemas need no producer's lexical scope. Reuse their reader
  ;; across constructors, operators, fields and tuple results. Nominal and
  ;; expression types still require the original scope (including privacy).
  (if (structural-schema? type)
    (let [name (symbol (str "aguafria.jvm.inspect-" (token type)))]
      (or (find-ns name) (create-ns name)))
    context))

(defn- precompile-inspection! [context type]
  (let [type (emitter/qualify-type context type)]
    ;; These compiler-declared result types have no addressable value storage.
    (when-not (#{:comptime_int :comptime_float :type :null :undefined} type)
      (let [adapter (prepare-expression! (inspection-context context type) (inspection-expression type)
                                         [{:name 'address :type :usize}] 'inspectResult)]
        (precompile-expression! adapter)))))

(defn- layout-adapter [type]
  (let [type (qualify-native-type (the-ns 'aguafria.zig.jvm) type)
        module (symbol (str "aguafria.jvm.layout-" (token type)))
        context (or (find-ns module) (create-ns module))]
    (prepare-expression! context (list 'aguafria.zig/type type) [] 'layoutResult)))

(defn- bind-reflected-layout [schema]
  (when schema
    (let [schema (walk/postwalk
                  (fn [item]
                    (if (and (map? item) (= :slice (:kind item)))
                      (let [{:keys [pointer-offset length-offset]} item
                            word-layout (if (= 8 (.byteSize ValueLayout/ADDRESS))
                                          ValueLayout/JAVA_LONG ValueLayout/JAVA_INT)]
                        (assoc item
                               :ownership :borrowed
                               :set-fn (fn [^MemorySegment storage ^MemorySegment backing length]
                                         (.set storage ValueLayout/ADDRESS pointer-offset backing)
                                         (if (= 8 (.byteSize word-layout))
                                           (.set storage ValueLayout/JAVA_LONG length-offset (long length))
                                           (.set storage ValueLayout/JAVA_INT length-offset (int length)))
                                         storage)
                               :read-fn (fn [^MemorySegment storage]
                                          {:address (.address (.get storage ValueLayout/ADDRESS pointer-offset))
                                           :length (if (= 8 (.byteSize word-layout))
                                                     (.get storage ValueLayout/JAVA_LONG length-offset)
                                                     (.get storage ValueLayout/JAVA_INT length-offset))})))
                      item)) schema)]
      schema)))

(defn reflected-layout!
  "Read native construction layout from Zig reflection, without reading a
  value or calling the function that owns the type. Field offsets and slice
  member offsets are compiler-authored, including anonymous nominal types."
  [module type]
  (let [adapter (layout-adapter (qualify-native-type (the-ns (symbol module)) type))]
    (bind-reflected-layout
     (invoke-adapter! adapter []))))

(defn precompile-reflected-layout!
  "Prepare the ordinary compiler-layout reader without invoking it."
  [module type]
  (precompile-expression!
   (layout-adapter (qualify-native-type (the-ns (symbol module)) type))))

(defn precompile-result-reader!
  "Prepare the ordinary native value reader in its producer's scope, without
  calling the producer or allocating a result. Imported types need this reader
  even when they have no Aguafria-owned layout declaration."
  [module type]
  (let [context (the-ns (symbol module))]
    (precompile-inspection! context (emitter/qualify-type context type))))

(defn precompile-function-result-reader!
  "Prepare an ordinary function's result decoding and typed tuple iteration.
  Adapter-only coercion modules have no authored inspection roots and continue
  to use precompile-result-reader! directly."
  [module type]
  (precompile-result-reader! module type)
  (precompile-tuple-sequence! (the-ns (symbol module)) type true))

(defn inspect-value!
  "Decode an otherwise unschematized native value using Zig's own reflection.
  Read the current storage; retain the owner and never follow unbounded pointers."
  [native-value]
  (requiring-resolve 'aguafria.zig/deref)
  (let [type (or (:inspection-type (value/info native-value))
                 (value/storage-type native-value))
        namespace-name (symbol (str "aguafria.jvm.inspect-" (token type)))
        context (inspection-context
                 (or (some-> (:module (value/info native-value)) symbol find-ns)
                     (find-ns namespace-name)
                     (create-ns namespace-name))
                 type)
        type (emitter/qualify-type context type)]
    (binding [runtime/*native-test-context?*
              (or runtime/*native-test-context?*
                  (= :test (:execution-context (value/info native-value))))]
      (try
        (let [result (invoke-expression!
                      context (inspection-expression type)
                      [{:name 'address :type :usize}]
                      [(.address ^MemorySegment (value/segment native-value))]
                      'inspectResult)]
          (inspection-view result))
        (finally (java.lang.ref.Reference/reachabilityFence native-value))))))

(defn test-resource!
  "Materialize a stable test-owned native resource. Its library and allocator
  state remain pinned while JVM values refer to it, across separate calls."
  [reference]
  (or (get @test-resources reference)
      (locking test-resources
        (or (get @test-resources reference)
            (let [namespace-name (symbol (str "aguafria.jvm.test-resource-" (token reference)))
                  context (or (find-ns namespace-name) (create-ns namespace-name))
                  expression (with-meta (:symbol reference) {:aguafria/zig-reference reference})
                  descriptor (emitter/prepare-declaration
                              context {:kind :const :name 'resource
                                       :module (str namespace-name)
                                       :declaration-key [:const 'resource]
                                       :type (list 'aguafria.keyword/TypeOf expression)
                                       :value expression})
                  _ (binding [runtime/*source-only-registration?* true]
                      (runtime/register-declaration! descriptor))
                  resource (value/native-value
                            (assoc descriptor :execution-context :test)
                            #(binding [runtime/*native-test-context?* true]
                               (runtime/materialize-constant! descriptor)))]
              (swap! test-resources assoc reference resource)
              resource)))))

(defn constructor-type
  "Resolve actual type constructors inside the same compositional type data
  accepted by declarations. No caller namespace is guessed from a REPL."
  [type]
  (if-let [payload (emitter/inferred-error-payload type)]
    [:error-union :anyerror (constructor-type payload)]
    (cond
      (value/zig-type? type)
      (let [{:keys [module name type]} (value/type-info type)]
        (constructor-type (or type (symbol module (str name)))))

      (var? type)
      (constructor-type (var-get type))

      (value/zig-value? type)
      ;; Dimensions and pointer options can be computed by native operations.
      ;; Preserve their Zig expression when available; otherwise embed the
      ;; compiler-produced scalar (for example @alignOf's usize result).
      (let [resolved (or (comptime-expression type) (value/value type))]
        (if (value/zig-type? resolved)
          (constructor-type resolved)
          resolved))

      (#{:type :container} (get-in (meta type) [:aguafria/zig-reference :category]))
      (emitter/qualify-type (the-ns 'aguafria.zig.jvm)
                            (get-in (meta type) [:aguafria/zig-reference :symbol]))

      (qualified-symbol? type)
      ;; Compiler observations contain declaration names; native handles also
      ;; carry reference metadata. Resolve both in one bridge scope before
      ;; hashing so they select the same constructor/conversion adapter.
      (do
        (when (and (nil? (:aguafria/zig-reference (meta type)))
                   (nil? (find-var type)))
          (binding [runtime/*source-only-registration?* true]
            (requiring-resolve type)))
        (emitter/qualify-type (the-ns 'aguafria.zig.jvm) type))

      (and (seq? type) (= 2 (count type))
           (contains? #{'type 'aguafria.zig/type} (first type)))
      (constructor-type (second type))

      (seq? type)
      (let [type (emitter/qualify-type (the-ns 'aguafria.zig.jvm) type)]
        (walk/postwalk
         (fn [form]
           (if-let [parameters
                    (when (and (seq? form) (qualified-symbol? (first form)))
                      (some-> (find-var (first form)) meta
                              (call-parameters (count (rest form)))))]
             (with-meta
               (apply list (first form)
                      (map (fn [argument parameter]
                             ;; The native signature, not the argument's shape,
                             ;; determines which operands are types.
                             (if (#{:type 'type} (:type parameter))
                               (constructor-type argument)
                               argument))
                           (rest form) parameters))
               (meta form))
             form))
         type))

      (vector? type)
      (mapv constructor-type type)

      (map? type)
      (into (empty type) (map (fn [[key item]] [key (constructor-type item)])) type)

      :else type)))

(defn anonymous-type-plan
  "Plan the declaration used by an ordinary JVM anonymous-container macro.
  Explicit local values are substituted through the normal constructor path;
  this neither registers declarations nor establishes native type equality."
  [caller container locals]
  (let [container (walk/postwalk-replace
                   (into {} (map (fn [[name value]] [name (constructor-type value)])) locals)
                   container)
        container (emitter/qualify-form (the-ns caller) container)
        module (symbol (str "aguafria.jvm.container-" (token [caller container])))]
    {:module module :container container :type (symbol (str module) "Type")}))

(defn register-anonymous-type-plan!
  "Register a planned ordinary JVM container without allocating an instance.
  Compile-only callers bind source-only registration around this operation."
  [{:keys [module container]}]
  (let [context (or (find-ns module) (create-ns module))
        descriptor (emitter/prepare-declaration
                    context {:kind :const :name 'Type :module (str module)
                             :declaration-key [:const 'Type] :public? true
                             :value container})]
    (locking context
      (when-not (and (contains? @prepared-adapters module)
                     (runtime/registered-declaration? module [:const 'Type]))
        (runtime/register-declaration! descriptor)
        (intern context 'Type (runtime/declaration-type-value descriptor))
        (runtime/refresh-declaration-var! descriptor)
        (swap! prepared-adapters conj module))
      (runtime/declaration-type-value descriptor))))

(defn anonymous-type!
  "Materialize anonymous container declarations without evaluating member names
  as Clojure Vars. The native compiler and type metadata use the normal path."
  [caller container locals]
  ;; A type descriptor is source, not a demand for its standalone startup image.
  ;; Its first operation/construction resolves the normal prepared module image.
  (binding [runtime/*source-only-registration?* true]
    (register-anonymous-type-plan! (anonymous-type-plan caller container locals))))

(defn- primitive-literal? [argument]
  (and (symbol? argument)
       (namespace argument)
       (= :primitive (some-> (find-var argument) meta :aguafria/token :kind))))

(defn native-literal?
  "True for values Zig must resolve from source in their requested type context."
  [argument]
  (or (primitive-literal? argument)
      (and (keyword? argument)
           (nil? (namespace argument))
           (str/starts-with? (name argument) "."))))

(defn- coercion-location [type input-type literal]
  (let [suffix (token [type input-type literal])
        module (symbol (str "aguafria.jvm.coercion-" suffix))
        default (or (find-ns module) (create-ns module))
        context (private-adapter-context default [type input-type literal])
        owner? (not= default context)
        module (ns-name context)
        name (if owner? (symbol (str "__jvm_coerce_" suffix)) 'coerce)]
    ;; A private type factory must be referenced from its defining Zig file.
    ;; Keep an adapter-specific name there rather than exporting the factory.
    {:context context
     :name name
     :function (symbol (str module) (str name))}))

(defn- prepare-coercion! [type input-type expression]
  (let [{:keys [context name function]} (coercion-location type input-type nil)
        qualified-name function]
    (locking context
      (when-not (registered-adapter? prepared-coercions qualified-name qualified-name)
        (binding [runtime/*source-only-registration?* true]
          (register! context
                     {:kind :fn :name name :qualified-name qualified-name
                      :declaration-key [:fn name]
                      :return type :args [{:name 'input :type input-type}]
                      :body [(list 'aguafria.keyword/return
                                   (list 'aguafria.keyword/as expression type))]}))
        (swap! prepared-coercions conj qualified-name)))
    qualified-name))

(defn- coercion-profile-plan [type input-type]
  (let [input-type (case input-type
                     :null '(aguafria.keyword/TypeOf nil)
                     :undefined '(aguafria.keyword/TypeOf aguafria.keyword/undefined)
                     input-type)
        expression
        (walk/postwalk-replace
         {'Target (list 'aguafria.zig/type type)
          'Input (list 'aguafria.zig/type input-type)}
         '(or ((field __aguafria_jvm :enumCoercionRequiresConstant) Target Input)
              (let [target-info (aguafria.keyword/typeInfo Target)
                    input-info (aguafria.keyword/typeInfo Input)]
                (if (and (aguafria.keyword/== target-info :.int)
                         (aguafria.keyword/== input-info :.int))
                  (let [target (aguafria.zig/field target-info :int)
                        input (aguafria.zig/field input-info :int)]
                    (if (aguafria.keyword/== (aguafria.zig/field target :signedness)
                                             (aguafria.zig/field input :signedness))
                      (aguafria.keyword/< (aguafria.zig/field target :bits)
                                          (aguafria.zig/field input :bits))
                      (or (aguafria.keyword/== (aguafria.zig/field target :signedness) :.unsigned)
                          (aguafria.keyword/<= (aguafria.zig/field target :bits)
                                               (aguafria.zig/field input :bits)))))
                  (if (and (aguafria.keyword/== target-info :.float)
                           (aguafria.keyword/== input-info :.float))
                    (aguafria.keyword/< (aguafria.zig/field (aguafria.zig/field target-info :float) :bits)
                                        (aguafria.zig/field (aguafria.zig/field input-info :float) :bits))
                    (and (or (aguafria.keyword/== target-info :.int)
                             (aguafria.keyword/== target-info :.float))
                         (or (aguafria.keyword/== input-info :.int)
                             (aguafria.keyword/== input-info :.float))))))))
        module (symbol (str "aguafria.jvm.coercion-profile-" (token [type input-type])))
        default (or (find-ns module) (create-ns module))
        context (private-adapter-context default [type input-type expression])]
    (prepare-expression! context expression [] 'inspectResult)))

(defn- coercion-requires-constant? [type input-type]
  (boolean (invoke-adapter! (coercion-profile-plan type input-type) [])))

(defn- precompile-coercion-reader! [function]
  (let [module (namespace function)
        declaration (some #(when (and (= :fn (:kind %))
                                      (= (name function) (str (:name %)))) %)
                          (runtime/registered-declarations module))]
    (precompile-result-reader! module (result-reader-type declaration))))

(defn precompile-coercion!
  "Compile a value constructor and its scalar storage without creating
  a value. The schema is explicit; compiler analysis decides layout/validity."
  [schema]
  (let [type (constructor-type schema)
        extended? (contains? #{:f16 :f80 :f128} type)
        input-type (if extended? :f64 type)
        expression (if extended? '(aguafria.keyword/floatCast input) 'input)
        numeric? (and (keyword? type)
                      (or (runtime/numeric-abi-type? type)
                          (#{:usize :isize :f16 :f32 :f64 :f80 :f128} type)
                          (re-matches #"[iu][0-9]+" (name type))))
        type-preparation (when (qualified-symbol? type)
                           (runtime/precompile-type! type))]
    (when (and runtime/*prepared-scalar-constructors* (or numeric? (= :bool type)))
      (swap! runtime/*prepared-scalar-constructors* conj type))
    (when (qualified-symbol? type)
      (precompile-inspection! (the-ns (symbol (namespace type))) type))
    (let [function (prepare-coercion! type input-type expression)]
      (runtime/precompile-function! function)
      (precompile-coercion-reader! function)
      ;; Returning/printing a native value must not introduce its first decoder
      ;; build after restart. Prepare the same owner's reflection adapter too.
      (precompile-inspection! (the-ns (symbol (namespace function))) type)
      (when (seq? type)
        (precompile-expression! (layout-adapter type))))
    (when (or numeric? (= :bool type))
      (let [storage-type [:array 1 type]
            function (prepare-coercion! storage-type storage-type 'input)]
        (runtime/precompile-function! function)
        (precompile-inspection! (the-ns (symbol (namespace function))) type)))
    (cond
      (= :unsupported (:status type-preparation))
      {:type schema :status :partial :reason (:reason type-preparation)
       :native-values-only? true}
      (seq? type)
      {:type schema :status :partial :reason :computed-type-literal-construction
       :native-values-only? true}
      :else {:type schema :status :prepared})))

(defn- prepare-literal-coercion! [type argument]
  (let [argument (canonical-comptime-object
                  (emitter/qualify-form (the-ns 'aguafria.zig.jvm) argument))
        {:keys [context name function]} (coercion-location type type argument)
        local-literal?
        (or (nil? argument) (boolean? argument) (number? argument)
            (char? argument) (string? argument) (native-literal? argument)
            (and (seq? argument) (= 'aguafria.keyword/as (first argument))
                 (= 3 (count argument))
                 (let [error-type (nth argument 2)
                       field (second argument)]
                   (and (vector? error-type) (= :error-set (first error-type))
                        (seq? field) (= 'field (first field))
                        (= (list 'type error-type) (second field))
                        (some #{(nth field 2 nil)} (second error-type))))))]
    (binding [runtime/*source-only-registration?* true]
      (register! context
                 {:kind :fn :name name :qualified-name function
                  :declaration-key [:fn name] :return type :args []
                  :jvm/local-native-image? (boolean local-literal?)
                  :body [(list 'aguafria.keyword/return
                               (list 'aguafria.keyword/as argument type))]}))
    function))

(defn- literal-construction-plan [type argument]
  (let [argument (canonical-comptime-object
                  (emitter/qualify-form (the-ns 'aguafria.zig.jvm) argument))
        {:keys [context]} (coercion-location type type argument)
        expression (with-meta (list 'aguafria.keyword/as argument type)
                     {:aguafria/jvm-result-type type})]
    ;; Zig's result transport distinguishes addressable values from values
    ;; that require comptime. Neither representation needs a guessed layout.
    (prepare-expression! context expression [] 'result)))

(defn- comptime-construction-plan [type]
  (let [type (qualify-native-type (the-ns 'aguafria.zig.jvm) type)
        module (symbol (str "aguafria.jvm.construction-profile-" (token type)))
        context (or (find-ns module) (create-ns module))]
    (prepare-expression! context
                         (list '(field __aguafria_jvm :requiresComptime)
                               (list 'aguafria.zig/type type))
                         [] 'inspectResult)))

(defn precompile-construction-profile!
  "Prepare Zig's storage requirement query without invoking it."
  [type]
  (precompile-expression! (comptime-construction-plan type)))

(defn construction-requires-comptime?
  "Ask Zig whether this exact type can exist in runtime storage."
  [type]
  (boolean (invoke-adapter! (comptime-construction-plan type) [])))

(defn construct-comptime!
  "Construct a storage-free value using Zig's compile-time result transport."
  [type argument]
  (let [inputs (call-inputs [{:type :anytype :properties {:jvm/literal? true}}] [argument])
        {:keys [context]} (coercion-location type type (first (:expression-arguments inputs)))
        expression (with-meta (list 'aguafria.keyword/as (first (:expression-arguments inputs)) type)
                     {:aguafria/jvm-result-type type})]
    (invoke-adapter! (prepare-expression! context expression (:parameters inputs) 'result)
                     (:arguments inputs))))

(defn precompile-source-construction!
  "Prepare a compiler-confirmed comptime constructor without runtime storage."
  [type source]
  (let [type (constructor-type type)
        adapter (literal-construction-plan type (second source))]
    (precompile-construction-profile! type)
    (precompile-expression! adapter)
    {:type type :status :prepared}))

(defn precompile-literal-coercion!
  "Prepare an explicit initializer and the reusable aggregate constructor.
  Zig supplies the type and layout; the initializer is not evaluated."
  [schema argument]
  (let [type (constructor-type schema)
        adapter (when (coll? argument) (literal-construction-plan type argument))
        function (when-not adapter (prepare-literal-coercion! type argument))
        context (or (:context adapter) (the-ns (symbol (namespace function))))]
    (if adapter
      (precompile-expression! adapter)
      (do (runtime/precompile-function! function)
          (precompile-coercion-reader! function)))
    (precompile-inspection! context type)
    ;; Live aggregate construction asks Zig for its layout, then passes the
    ;; data through the runtime constructor when that layout is encodable.
    (when (and (seq? type) (or (map? argument) (vector? argument)))
      (precompile-coercion! type))
    ;; mutable! stores primitive literals in an owned one-element array. Both
    ;; forms of the constructor must be prepared without reading undefined data.
    (when (primitive-literal? argument)
      (let [storage-function (prepare-literal-coercion! [:array 1 type] argument)]
        (runtime/precompile-function! storage-function)
        (precompile-inspection! (the-ns (symbol (namespace storage-function))) type)))
    {:type schema :status :prepared :literal-value argument}))

(defn precompile-constant-conversion!
  "Prepare a compiler-observed constant coercion and its ordinary JVM storage."
  [input-type result-type source]
  (precompile-expression! (coercion-profile-plan result-type input-type))
  (precompile-literal-coercion! result-type source)
  (precompile-coercion! result-type))

(defn- precompile-named-error-coercion! [type error]
  (let [function (prepare-literal-coercion! type (value/error-form error))]
    (runtime/precompile-function! function)
    (precompile-coercion-reader! function)))

(defn- precompile-operand-storage! [type]
  ;; JVM strings use owned UTF-8 arrays before pointer/slice coercion. Zig's
  ;; observed pointer type supplies the array length, element type and sentinel.
  (when (and (vector? type) (= :*const (first type))
             (vector? (second type))
             (= :array (first (second type)))
             (= {:sentinel 0} (nth (second type) 2 nil))
             (= :u8 (last (second type))))
    (precompile-coercion! (second type))))

(defn precompile-conversion!
  "Prepare the same adapter as coercing an existing native value, without
  constructing that value. Both schemas must be compiler-confirmed."
  [input-type result-type]
  (let [input-type (constructor-type input-type)
        result-type (constructor-type result-type)]
    (precompile-operand-storage! input-type)
    (when-not (= input-type result-type)
      (precompile-expression! (coercion-profile-plan result-type input-type)))
    ;; Null has no runtime storage. The ordinary JVM call encodes nil directly
    ;; in the destination type instead of passing an input of type `null`.
    (when-not (or (= input-type result-type) (= :null input-type))
      (runtime/precompile-function!
       (prepare-coercion! result-type input-type 'input)))
    ;; Error results cross library boundaries by name, never by a library-local
    ;; integer code. Prepare the same literal conversion as coerce-raw! for each
    ;; member of the compiler-observed finite set, without invoking the source.
    (when (and (vector? input-type) (= :error-set (first input-type)))
      (doseq [member (second input-type)]
        (precompile-named-error-coercion!
         result-type (value/->ZigError (name member) input-type))))
    (precompile-coercion! result-type)
    (cond-> {:type result-type :input-type input-type :status :prepared}
      (= :anyerror input-type)
      (assoc :status :partial :reason :error-name-specialization))))

(defn- prepare-construction!
  [type {:keys [expression-arguments parameters]}]
  (let [;; Compiler placeholders and allocated native operands must anchor named
        ;; types through the same normal qualifier before planning identity.
        parameters (mapv #(update % :type constructor-type) parameters)
        expression (list 'aguafria.keyword/as (first expression-arguments) type)
        module (symbol (str "aguafria.jvm.construction-" (token [expression parameters])))
        default (or (find-ns module) (create-ns module))
        context (private-adapter-context default [type expression parameters])
        local-name (if (= default context) 'construct
                       (symbol (str "__jvm_construct_" (token [expression parameters]))))
        name (symbol (str (ns-name context)) (str local-name))]
    (locking context
      (when-not (registered-adapter? prepared-coercions name name)
        (binding [runtime/*source-only-registration?* true]
          (register! context {:kind :fn :name local-name :qualified-name name
                              :declaration-key [:fn local-name] :return type
                              :args parameters :body [expression]}))
        (swap! prepared-coercions conj name)))
    name))

(defrecord ContextualCall [syntax arguments])
(defrecord ContextualScope [caller form locals])
(defrecord ContextualAddress [initializer])

(defn native-construction-argument?
  "Whether construction must retain embedded native/deferred operands for Zig.
  This classifies JVM values only; the requested Zig type is never inferred."
  [argument]
  (boolean
   (some #(or (value/zig-value? %)
              (instance? ContextualCall %) (instance? ContextualScope %)
              (instance? ContextualAddress %))
         (tree-seq coll? seq argument))))

(defmethod print-method ContextualAddress
  [_ writer]
  (.write ^java.io.Writer writer "#aguafria/contextual-address[destination pointer type required; use k/as]"))

(defn- contextual-address-backing-type [target element-count]
  ;; The receiving pointer decides the storage type. In particular, enum
  ;; literals and empty aggregates cannot supply an element type themselves.
  (let [pointer (list 'aguafria.zig/field
                      (list 'aguafria.keyword/typeInfo (list 'aguafria.zig/type target))
                      :pointer)
        child (list 'aguafria.zig/field pointer :child)]
    (constructor-type
     (list 'if
           (list 'aguafria.keyword/== (list 'aguafria.zig/field pointer :size) :.one)
           child
           (list 'aguafria.zig/type [:array element-count child])))))

(defmethod print-method ContextualScope
  [_ writer]
  (.write ^java.io.Writer writer "#aguafria/contextual-scope[result type required; use k/as]"))

(defmethod print-method ContextualCall
  [call writer]
  (.write ^java.io.Writer writer
          (str "#aguafria/contextual-call[" (get-in call [:syntax :symbol])
               " — result type required; use k/as]")))

(defn- integer-transport-type? [type]
  (and (keyword? type)
       (or (#{:usize :isize} type)
           (re-matches #"[iu][1-9][0-9]*" (name type))
           (and (runtime/numeric-abi-type? type)
                (not (#{:f32 :f64} type))))))

(defn- coercion-expression [argument]
  (when (and (value/zig-value? argument) (not= :var (:kind (value/info argument))))
    (:coercion-expression (value/realize! argument))))

(defn- function-source [argument]
  (let [reference (:aguafria/zig-reference (meta argument))]
    (when (contains? #{:fn :fn-proto} (:declaration-kind reference))
      (with-meta (:symbol reference) {:aguafria/zig-reference reference}))))

(defn- owned-string-target? [type]
  (or (= [:slice-const :u8] type)
      (and (vector? type)
           (or (and (= :optional (first type)) (= 2 (count type)))
               (and (= :error-union (first type)) (= 3 (count type))))
           (owned-string-target? (peek type)))))

(defn- coerce-raw!
  "Internal coercion; the public boundary below owns numeric scalar results."
  [argument type]
  (let [type-value (cond
                     (value/zig-value? type) (value/value type)
                     (var? type) (var-get type)
                     :else type)
        compiler-type? (value/zig-type? type-value)
        type (constructor-type type)
        ;; A concrete byte slice uses the existing owner-scoped UTF-8 encoder.
        ;; Its backing length is data, not a new array/coercion handler type.
        argument (if (and (string? argument) (not (owned-string-target? type)))
                   (let [bytes (.getBytes ^String argument java.nio.charset.StandardCharsets/UTF_8)
                         storage (coerce-raw! (mapv #(bit-and 0xff %) bytes)
                                              [:array (alength bytes) {:sentinel 0} :u8])]
                     (value/address-value storage false))
                   argument)
        typed-source (coercion-expression argument)
        source (some-> (or (when (and typed-source
                                      (not= type (value/storage-type argument))
                                      (coercion-requires-constant?
                                       type (constructor-type (value/storage-type argument))))
                             typed-source)
                           (comptime-expression argument)
                           (function-source argument))
                       canonical-comptime-object)
        ;; Zig already supplied this exact integer. An explicit integer target
        ;; can use the normal checked transport, without specializing its digits.
        ;; Float conversions still need Zig's original comptime expression.
        transport-integer? (and (integer? source) (integer-transport-type? type))
        argument (if transport-integer? source
                     (if (and (value/zig-value? argument) (not source))
                       (value/value argument) argument))
        source (when-not transport-integer? source)
        type (if (and (vector? type)
                      (= :array (first type))
                      (= :_ (second type)))
               (assoc type 1 (count argument))
               type)
        argument (if (and (vector? type) (= :error-union (first type))
                          (not (value/zig-value? argument))
                          (not (value/zig-error? argument))
                          (not (native-literal? argument))
                          (not (and (map? argument)
                                    (or (contains? argument :ok) (contains? argument :error)))))
                   {:ok argument}
                   argument)
        native? (value/zig-value? argument)
        error? (value/zig-error? argument)
        literal? (or (native-literal? argument)
                     ;; A computed type has no JVM ABI until Zig resolves it.
                     ;; Supply ordinary data in its native result context instead
                     ;; of claiming the JVM operand already has that type.
                     (and (or (and (seq? type)
                                   (not (and (or (map? argument) (vector? argument))
                                             (reflected-layout! (str (ns-name *ns*)) type))))
                              (and compiler-type? (coll? argument)))
                          (or (nil? argument) (boolean? argument) (number? argument)
                              (char? argument) (string? argument)
                              (map? argument) (vector? argument))))
        input-type (if native? (constructor-type (value/storage-type argument)) type)
        extended-float? (and (not native?) (#{:f16 :f80 :f128} type))
        input-type (if extended-float? :f64 input-type)
        namespace-name (symbol (str "aguafria.jvm.coercion-" (token [type input-type (when (or error? literal?) argument)])))
        context (or (find-ns namespace-name) (create-ns namespace-name))
        expression (if extended-float?
                     '(aguafria.keyword/floatCast input)
                     'input)]
    (when-not (or (keyword? type) (vector? type) (symbol? type) (seq? type))
      (throw (ex-info "ak/as expects a Zig type as its second argument"
                      {:value argument :type type})))
    ;; A coercion to the same type does not copy a borrowed pointer or detach
    ;; its owner. Return the original owning value rather than a dangling view.
    (cond
      (instance? ContextualScope argument)
      (invoke-scoped! (:caller argument) (:form argument) (:locals argument) true type)

      (instance? ContextualAddress argument)
      (let [initializer (:initializer argument)
            backing (contextual-address-backing-type type (count initializer))
            owner (coerce! initializer backing)
            pointer (value/address-value owner false)
            result (coerce-raw! pointer type)]
        ;; Never return the address of a construction adapter's stack local.
        ;; The array/struct result owns native storage before we borrow it.
        (value/retain-owners! result [owner pointer]))

      (and native? (= type input-type))
      (do (value/realize! argument) argument)

      source
      ;; Keep comptime expressions intact until Zig applies the requested type.
      ;; Decoding a float to a JVM double first can round it to the wrong side
      ;; of a narrower float's midpoint.
      (if (coll? source)
        (invoke-adapter! (literal-construction-plan type source) [])
        (runtime/invoke! (prepare-literal-coercion! type source) []))

      (instance? ContextualCall argument)
      (let [inputs (call-inputs [{:type :anytype}] [argument])
            result (runtime/invoke! (prepare-construction! type inputs) (:arguments inputs))]
        (value/retain-owners! result [argument]))

      (and (coll? argument) (native-construction-argument? argument))
      ;; Embedded native values need Zig's typed construction, not a field
      ;; encoder that expects every struct element to be a Clojure map.
      (let [inputs (call-inputs [{:type :anytype :properties {:jvm/literal? true}}] [argument])
            result (if (empty? (:parameters inputs))
                     (invoke-adapter! (literal-construction-plan type (first (:expression-arguments inputs))) [])
                     (runtime/invoke! (prepare-construction! type inputs) (:arguments inputs)))]
        (value/retain-owners! result [argument]))

      (or error? literal?)
      (let [source (if error? (value/error-form argument) argument)]
        (if (and (coll? source) (not error?))
          (invoke-adapter! (literal-construction-plan type source) [])
          (runtime/invoke! (prepare-literal-coercion! type source) [])))

      (#{:comptime_int :comptime_float} type)
      (invoke-expression! context (list 'aguafria.keyword/as argument type) [] [])

      :else
      (runtime/invoke! (prepare-coercion! type input-type expression) [argument]))))

(defn- own-numeric-result! [result type]
  (if (number? result)
    (if (#{:comptime_int :comptime_float} type)
      (value/native-value {:kind :const :type type}
                          (constantly {:representation :scalar :value result}))
      (value/array-element-view (coerce-raw! [result] [:array 1 (constructor-type type)]) 0))
    result))

(defn coerce!
  "Coerce while preserving the exact Zig type and addressable numeric storage.
  Use a/value for explicit conversion back to a plain JVM value."
  [argument type]
  (let [source (or (coercion-expression argument) (comptime-expression argument)
                   (when (or (number? argument) (string? argument)
                             (and (keyword? argument) (native-literal? argument)))
                     argument))
        raw (coerce-raw! argument type)
        result (own-numeric-result! raw type)]
    (when (and source (value/zig-value? result) (not (identical? argument result)))
      ;; Coercion provenance is separate from operator specialization: ordinary
      ;; operators continue passing typed values through reusable handlers.
      (value/retain-coercion-expression!
       result (list 'aguafria.keyword/as (if (integer? raw) raw source)
                    (value/qualified-type result))))
    result))

(defn coerce-contextual-arguments!
  "Let Zig coerce native values and deferred expressions to declared parameters."
  [module declaration arguments]
  (let [context (the-ns (symbol module))]
    (mapv (fn [index argument]
            (if (or (value/zig-value? argument)
                    (instance? ContextualCall argument) (instance? ContextualScope argument)
                    (instance? ContextualAddress argument)
                    (function-source argument))
              (let [target (qualify-native-type context
                                                (argument-reader-type declaration index))
                    actual (when (value/zig-value? argument)
                             (constructor-type (value/storage-type argument)))]
                (if (and actual
                         (or (= actual (constructor-type target))
                             (and (not (and (structural-schema? actual)
                                            (structural-schema? target)))
                                  (equivalent-types? module target actual))))
                  argument
                  (coerce! argument target)))
              argument))
          (range (count (:args declaration))) arguments)))

(defn invoke-value!
  "Public Var call: retain numeric results; internal ABI calls remain scalars."
  [declaration arguments]
  (let [result (runtime/invoke! (:qualified-name declaration) arguments)]
    (own-numeric-result! result (:return declaration))))

(defn- mutable-type [argument]
  (cond
    (value/zig-value? argument) (value/qualified-type argument)
    (boolean? argument) :bool
    (instance? Integer argument) :i32
    (integer? argument) :i64
    (instance? Float argument) :f32
    (float? argument) :f64
    (string? argument) [:slice-const :u8]
    :else (throw (ex-info "A mutable value needs an explicit Zig type" {:value argument}))))

(defn mutable!
  "Create independent native storage for assignment from ordinary JVM code.
  An optional explicit type uses the same schemas as ak/as."
  ([argument]
   (mutable! argument (mutable-type argument)))
  ([argument type]
   (mutable! argument type {}))
  ([argument type options]
   (let [options (keyword/normalize-attributes *ns* options)
         type (constructor-type (or type (mutable-type argument)))
         literal? (primitive-literal? argument)
         converted (coerce! argument (if literal? [:array 1 type] type))]
     (if (and (value/zig-value? converted) (not literal?))
       (value/mutable-copy converted type (:schema (value/realize! converted)) options)
       ;; One-element arrays force owned native storage even for ABI scalars.
       (let [storage (if literal? converted (coerce! [converted] [:array 1 type]))]
         (value/mutable-copy storage type
                             (:element-schema (:schema (value/realize! storage))) options))))))

(defn assign!
  "Assign through a mutable native handle, using the ordinary Zig coercion path."
  [target new-value]
  (cond
    (= :_ target) nil
    (vector? target)
    (let [items (if (value/zig-value? new-value) @new-value new-value)]
      (when-not (and (sequential? items) (= (count target) (count items)))
        (throw (ex-info "Destructuring assignment requires one value per target"
                        {:targets (count target) :value items})))
      ;; Snapshot before writing: [x y] <- [y x] is a swap, not two dependent writes.
      (let [items (mapv #(if (value/zig-value? %) @% %) items)]
        (doseq [[destination item] (map vector target items)]
          (assign! destination item))))
    :else
    (do
      (when-not (and (value/zig-value? target) (= :var (:kind (value/info target))))
        (throw (ex-info "Assignment requires a mutable native value; create it with ak/var"
                        {:target target})))
      (value/set-value! target (coerce! new-value (value/type target)))))
  nil)

(defn- prepare-assignment! [function {:keys [expression-arguments parameters]}]
  (let [parameters (mapv #(update % :type (partial emitter/qualify-type *ns*)) parameters)
        expression (list function
                         (list 'aguafria.zig/deref (first expression-arguments))
                         (second expression-arguments))
        module (symbol (str "aguafria.jvm.assignment-" (token [expression parameters])))
        {:keys [context name function]}
        (function-adapter-location module 'assign [expression parameters])]
    (locking context
      (when-not (registered-adapter? prepared-adapters function function)
        (binding [runtime/*source-only-registration?* true]
          (register! context {:kind :fn :name name :qualified-name function
                              :declaration-key [:fn name] :return :void
                              :args parameters :body [expression]}))
        (swap! prepared-adapters conj function)))
    function))

(defn invoke-assignment!
  "Execute a compound assignment in Zig with independently typed operands.
  Literals retain Zig's contextual typing; native operands retain their types."
  [syntax target operand]
  (when-not (and (value/zig-value? target) (= :var (:kind (value/info target))))
    (throw (ex-info "Assignment requires a mutable native value; create it with ak/var"
                    {:target target})))
  (let [storage (value/address-value target true)
        {:keys [arguments] :as inputs}
        (call-inputs [{:type :anytype}
                      (if-let [type (when (number? operand)
                                      (handlers/assignment-operand-type target (:zig-token syntax)))]
                        {:type type}
                        {:type :anytype :properties {:jvm/literal? true}})]
                     [storage operand])
        function (prepare-assignment! (:symbol syntax) inputs)]
    (try
      (runtime/invoke! function arguments)
      (finally (java.lang.ref.Reference/reachabilityFence storage)))))

(defn- assoc-native! [target keyvals]
  (when-not (even? (count keyvals))
    (throw (ex-info "assoc! expects key/value pairs" {:keyvals keyvals})))
  (when-not (and (value/zig-value? target) (= :var (:kind (value/info target))))
    (throw (ex-info "assoc! requires mutable native storage; create it with k/var"
                    {:target target})))
  (when (seq keyvals)
    (let [storage (value/address-value target true)
          entries (partition 2 keyvals)
          operands (into [storage] (mapcat (fn [[key v]]
                                             (if (keyword? key) [v] [key v])) entries))
          {:keys [parameters arguments expression-arguments]}
          (call-inputs (repeat (count operands) {:type :anytype}) operands)
          parameters (mapv #(update % :type (partial emitter/qualify-type *ns*)) parameters)
          pairs (loop [entries entries inputs (next expression-arguments) out []]
                  (if-let [[key _] (first entries)]
                    (if (keyword? key)
                      (recur (next entries) (next inputs) (conj out key (first inputs)))
                      (recur (next entries) (nnext inputs)
                             (conj out (first inputs) (second inputs))))
                    out))
          expression (apply list 'aguafria.zig/assoc!
                            (list 'aguafria.zig/deref (first expression-arguments)) pairs)
          module (symbol (str "aguafria.jvm.assoc-" (token [expression parameters])))
          {:keys [context name function]} (function-adapter-location module 'assign [expression parameters])
          qualified-name function]
      (locking context
        (when-not (registered-adapter? prepared-adapters qualified-name qualified-name)
          (binding [runtime/*source-only-registration?* true]
            (register! context {:kind :fn
                                :name name
                                :qualified-name qualified-name
                                :declaration-key [:fn name]
                                :return [:array 1 (:type (first parameters))]
                                :args parameters
                                :body [expression
                                       (list 'aguafria.zig/array
                                             [(first expression-arguments)]
                                             (:type (first parameters)))]}))
          (swap! prepared-adapters conj qualified-name))
        (try
          ;; The returned pointer holder retains the call arena as well as the
          ;; inputs. Slice/string fields must not point into a closed call arena.
          (value/retain-mutation-owners! target [(runtime/invoke! qualified-name arguments)])
          (finally (java.lang.ref.Reference/reachabilityFence operands))))))
  target)

(defrecord ^:private PreparedOperand [type])
(defrecord ^:private PreparedType [schema])
(defrecord ^:private PreparedExpression [expression])

(defn- type-expression [schema]
  (let [schema (constructor-type schema)]
    (if (or (keyword? schema) (symbol? schema))
      schema
      (list 'type schema))))

(defn- unsigned-integer-width [argument]
  (when (and (not (comptime-expression argument))
             (or (value/zig-value? argument) (instance? PreparedOperand argument)))
    (let [type (if (instance? PreparedOperand argument)
                 (:type argument) (value/storage-type argument))]
      (cond
        (= :usize type) (* 8 (.byteSize ValueLayout/ADDRESS))
        (keyword? type)
        (some-> (re-matches #"u([0-9]+)" (name type)) second Long/parseLong)
        ;; A borrowed field can carry a @TypeOf expression rather than a
        ;; primitive keyword. Ask Zig; the printed JVM number loses signedness.
        (seq? type)
        (let [context (or (find-ns 'aguafria.jvm.integer-width)
                          (create-ns 'aguafria.jvm.integer-width))]
          (invoke-expression! context
                              (list '(field __aguafria_jvm :unsignedIntegerBits)
                                    (list 'type type))
                              [] [] 'inspectResult))))))

(defn- floating-operand!
  "Let Zig round a source-spelled literal to its contextual native float type."
  [argument type]
  (let [context (or (find-ns 'aguafria.jvm.floating-operand)
                    (create-ns 'aguafria.jvm.floating-operand))
        expression (list 'aguafria.keyword/as
                         (list 'aguafria.keyword/floatCast
                               (list '(field __aguafria_jvm :parseComptimeFloat) 'text))
                         type)]
    (invoke-expression! context expression
                        [{:name 'text :type [:slice-const :u8]}]
                        [(str argument)])))

(defn- call-declarations [declarations argument-count]
  (let [fixed (vec (take-while #(not (get-in % [:properties :zig/variadic])) declarations))]
    (if (= (count fixed) (count declarations))
      (do
        (when-not (= (count declarations) argument-count)
          (throw (ex-info "Wrong number of arguments for Zig function"
                          {:expected (count declarations) :actual argument-count})))
        declarations)
      (do
        (when-not (and (= (inc (count fixed)) (count declarations))
                       (<= (count fixed) argument-count))
          (throw (ex-info "Invalid variadic argument count or parameter placement"
                          {:minimum (count fixed) :actual argument-count})))
        (into fixed
              (repeat (- argument-count (count fixed))
                      {:type :anytype :properties {:jvm/literal? true}}))))))

(declare signature-arguments call-parameters call-expression-plan)

(defn- canonical-comptime-object [expression]
  ;; Literal objects and JVM maps emit the same named initializer. Keep one
  ;; adapter identity, without changing ordered or executable source expressions.
  (walk/postwalk
   (fn [form]
     (let [declaration (when (and (seq? form) (= 2 (count form))
                                  (qualified-symbol? (first form)) (map? (second form)))
                         (:aguafria/declaration (meta (find-var (first form)))))
           named-constructor? (or (= :struct (:kind declaration))
                                  (and (= :const (:kind declaration))
                                       (emitter/container-description
                                        (the-ns (symbol (:module declaration)))
                                        (:value declaration))))
           fields (when (and (seq? form) (= 2 (count form))
                             (= 'object (first form)) (vector? (second form)))
                    (second form))
           entries (when (and fields
                              (every? #(and (vector? %) (= 2 (count %))
                                            (or (keyword? (first %)) (symbol? (first %))
                                                (string? (first %)))) fields))
                     (mapv (fn [[field item]]
                             [(if (symbol? field) (clojure.core/keyword (name field)) field) item])
                           fields))]
       (cond
         named-constructor?
         (with-meta (list 'aguafria.keyword/as (second form) (first form))
           (meta form))

         (and (seq? form) (symbol? (first form))
              (contains? #{'array 'aguafria.zig/array} (first form))
              (vector? (second form))
              (or (= 3 (count form))
                  (and (= 4 (count form)) (map? (nth form 2))
                       (= #{:sentinel} (set (keys (nth form 2)))))))
         (with-meta
           (list 'aguafria.keyword/as (second form)
                 (into [:array (count (second form))]
                       (if (= 4 (count form)) [(nth form 2) (nth form 3)] [(nth form 2)])))
           (meta form))

         ;; emit-type delegates sequence schemas directly to emit-expr. A
         ;; retained type value may add this wrapper during a JVM round trip;
         ;; removing it preserves the emitted Zig and the prepared call key.
         (and (seq? form) (= 2 (count form)) (seq? (second form))
              (symbol? (first form))
              (contains? #{'type 'aguafria.zig/type} (first form)))
         (second form)

         (and entries
              (= (count entries) (count (distinct (map first entries))))
              (every? #(or (nil? %) (number? %) (boolean? %) (char? %)
                           (string? %) (keyword? %) (vector? %) (map? %))
                      (tree-seq coll? seq (mapv second entries))))
         (into {} entries)

         :else form)))
   expression))

(defn- builtin-arguments [signature]
  ;; Dependent builtin parameter spellings are documentation placeholders.
  ;; Keep operands in Zig's call context so the compiler resolves their types.
  (mapv (fn [parameter]
          (cond-> parameter
            (or (symbol? (:type parameter)) (seq? (:type parameter)))
            (-> (assoc :type :anytype)
                (assoc-in [:properties :jvm/literal?] true))))
        (signature/builtin-arguments signature)))

(defn- call-inputs [argument-declarations arguments]
  (let [argument-declarations (call-declarations argument-declarations (count arguments))
        parameters (atom [])
        values (atom [])
        ;; A plain JVM integer has no user-selected Zig signedness. Keep a
        ;; lossless signed carrier when an anytype call also contains native
        ;; unsigned values. Never coerce explicitly typed native operands, and
        ;; never narrow/wrap negative values merely to make their types match.
        runtime-integer? (some (fn [[declaration argument]]
                                 (and (integer? argument)
                                      (#{:anytype 'anytype} (:type declaration))
                                      (not (get-in declaration [:properties :jvm/literal?]))))
                               (map vector argument-declarations arguments))
        unsigned-width (if runtime-integer?
                         (reduce max 0 (keep unsigned-integer-width arguments))
                         0)
        type-arguments (into {}
                             (keep (fn [[declaration argument]]
                                     (when (#{:type 'type} (:type declaration))
                                       [(:name declaration)
                                        (if (instance? PreparedType argument) (:schema argument) argument)])))
                             (map vector argument-declarations arguments))]
    (letfn [(lift [argument expected literal?]
              (let [argument
                    (if-let [type (:aguafria/native-error-union-type (meta argument))]
                      (value/retain-owners!
                       (coerce! (if (contains? argument :error)
                                  (value/->ZigError (name (get-in argument [:error :name]))
                                                    (if (and (vector? type) (= 3 (count type)))
                                                      (second type) :anyerror))
                                  (:ok argument))
                                type)
                       (cons argument (:aguafria/native-error-union-owners (meta argument))))
                      argument)
                    original argument
                    argument (if (value/zig-value? argument) (value/value argument) argument)
                    expected (get type-arguments expected expected)
                    inferred (cond
                               (instance? PreparedOperand original) (:type original)
                               (value/zig-value? original)
                               (let [type (value/storage-type original)]
                                 (if (emitter/inferred-error-payload type)
                                   (constructor-type type)
                                   type))
                               (value/zig-pointer? argument)
                               (value/pointer-type argument)
                               (boolean? argument) :bool
                               (instance? Byte argument) :i8
                               (instance? Short argument) :i16
                               (instance? Integer argument) :i32
                               (instance? Float argument) :f32
                               (integer? argument)
                               (if (and (#{:anytype 'anytype} expected)
                                        (>= unsigned-width 64))
                                 (clojure.core/keyword (str "i" (inc unsigned-width)))
                                 :i64)
                               (float? argument) :f64
                               (string? argument) [:slice-const :u8])
                    zig-type (if (or (#{:bool :f16 :f32 :f64 :f80 :f128 :isize :usize
                                        :c_short :c_ushort :c_int :c_uint :c_long :c_ulong
                                        :c_longlong :c_ulonglong} expected)
                                     (and (keyword? expected)
                                          (re-matches #"[iu][0-9]+" (name expected))))
                               expected
                               inferred)]
                (cond
                  (instance? ContextualScope argument)
                  (if (or (nil? expected) (#{:anytype 'anytype} expected))
                    (throw (ex-info "Scoped Zig expression needs a destination type; use k/as"
                                    {:form (:form argument)}))
                    (lift (invoke-scoped! (:caller argument) (:form argument) (:locals argument)
                                          true (constructor-type expected)) expected literal?))

                  (instance? ContextualCall argument)
                  (let [{:keys [syntax arguments]} argument
                        declarations (builtin-arguments (:signature syntax))
                        expression (apply list (:symbol syntax)
                                          (map (fn [declaration operand]
                                                 (lift operand (:type declaration)
                                                       (or (= "comptime" (get-in declaration [:properties :zig/prefix]))
                                                           (#{:type 'type :comptime_int :comptime_float} (:type declaration))
                                                           (get-in declaration [:properties :jvm/literal?]))))
                                               declarations arguments))]
                    (if (or (nil? expected) (#{:anytype 'anytype} expected))
                      expression
                      (list 'aguafria.keyword/as expression (constructor-type expected))))
                  (comptime-expression original)
                  (canonical-comptime-object
                   (canonical-comptime-source-operands (comptime-expression original)))
                  (instance? PreparedExpression argument)
                  (canonical-comptime-object
                   (canonical-comptime-source-operands (:expression argument)))
                  (instance? PreparedType argument) (type-expression (:schema argument))
                  (value/zig-type? argument) (type-expression argument)
                  (#{:type 'type} expected) (type-expression argument)
                  (:aguafria/zig-reference (meta argument))
                  (let [reference (:aguafria/zig-reference (meta argument))]
                    (if (and (#{:global-const :global-variable} (:category reference))
                             (= "aguafria.std.testing" (namespace (:symbol reference))))
                      (lift (test-resource! reference) expected literal?)
                      (with-meta (:symbol reference) {:aguafria/zig-reference reference})))
                  (value/zig-error? argument)
                  (canonical-comptime-source-operands
                   (emitter/qualify-form *ns* (value/error-form argument)))
                  (primitive-literal? argument) argument
                  ;; Zig characters are integer code points. Use the same source
                  ;; spelling as compiler-observed integer literals in AOT plans.
                  (and (char? argument) (not literal?) (keyword? expected)
                       (or (#{:usize :isize} expected)
                           (re-matches #"[iu][0-9]+" (name expected))))
                  (lift (int argument) expected false)
                  (char? argument) (int argument)
                  (and (#{:comptime_int :comptime_float} inferred) (number? argument)) argument
                  ;; Untyped source numbers stay comptime values for generic
                  ;; parameters (and tuple elements). Treating 1.2 as runtime
                  ;; f64 changes e.g. expectEqual(1.2, an_f32). Explicit native
                  ;; values and concrete transport parameters stay typed.
                  (and (not (value/zig-value? original))
                       (number? argument)
                       (or (nil? expected) (#{:anytype 'anytype} expected)))
                  argument
                  (and literal? (not (value/zig-value? original))
                       (or (number? argument) (boolean? argument)
                           (string? argument)))
                  argument
                  (and (#{:f16 :f80 :f128} zig-type)
                       (number? argument) (not (value/zig-value? original)))
                  (lift (floating-operand! argument zig-type) expected false)
                  zig-type
                  (let [name (symbol (str "input_" (count @parameters)))]
                    ;; Native handles and compiler-only operands can name the
                    ;; same declaration with different reference metadata.
                    ;; Resolve its source reference before any planner hashes
                    ;; the parameter, just as prepare-expression! does later.
                    (swap! parameters conj {:name name :type (qualify-native-type *ns* zig-type)})
                    (swap! values conj argument)
                    name)
                  (vector? argument) (mapv #(lift % nil literal?) argument)
                  (map? argument) (into (empty argument)
                                        (map (fn [[key item]] [key (lift item nil literal?)]))
                                        ;; Field emission is canonical already.
                                        ;; ABI parameter numbering must use the
                                        ;; same order for compiler placeholders
                                        ;; and ordinary JVM map insertion orders.
                                        (sort-by (comp artifact/print-data key) argument))
                  (or (keyword? argument) (nil? argument)) argument
                  (var? argument) (with-meta (symbol (str (ns-name (:ns (meta argument))))
                                                     (str (:name (meta argument))))
                                    (select-keys (meta argument) [:aguafria/zig-reference]))
                  :else (throw (ex-info "Cannot pass this value to native Zig"
                                        {:argument argument :type (type argument)})))))]
      {:expression-arguments
       (mapv (fn [{:keys [properties type]} argument]
               (cond
                 (and (:jvm/peer-integer? properties)
                      (value/zig-value? argument)
                      (= :comptime_int (value/qualified-type argument)))
                 ;; The original Zig wrapper and a compiler check established
                 ;; this concrete common type. Transport the exact integer as
                 ;; a runtime operand, just as preparation does for its literal.
                 (lift (value/value argument) type false)
                 (and (:jvm/peer-float? properties)
                      (or (instance? Double argument) (instance? Float argument)))
                 ;; Keep the source decimal spelling: Zig parses and rounds it,
                 ;; not the JVM's double-to-float conversion. Text is a runtime
                 ;; slice, so changing its digits never changes adapter source.
                 (list 'aguafria.keyword/as
                       (list 'aguafria.keyword/floatCast
                             (list '(field __aguafria_jvm :parseComptimeFloat)
                                   (lift (str argument) [:slice-const :u8] false))) type)
                 (or (= "comptime" (:zig/prefix properties))
                     (#{:type 'type :comptime_int :comptime_float} type))
                 (lift argument type true)
                 :else (lift argument type (:jvm/literal? properties))))
             argument-declarations arguments)
       :parameters @parameters
       :arguments @values})))

(defn invoke-native-value!
  "Call a native function value. Declaration aliases stay in their defining
  Zig scope; other callable values are passed through the normal native ABI."
  [callee arguments]
  (let [{:keys [module name kind]} (value/info callee)
        declaration? (and module name (= :const kind))
        operands (if declaration? arguments (cons callee arguments))
        {:keys [expression-arguments parameters arguments]}
        (call-inputs (repeat (count operands)
                             {:type :anytype :properties {:jvm/literal? true}})
                     operands)
        context-name (if declaration? (symbol module) 'aguafria.jvm.value-call)
        context (or (find-ns context-name) (create-ns context-name))
        expression (if declaration?
                     (apply list (symbol module (str name)) expression-arguments)
                     (apply list expression-arguments))]
    (try
      (invoke-expression! context expression parameters arguments)
      (finally (java.lang.ref.Reference/reachabilityFence callee)))))

(defn- scoped-result-type-expression
  [expression result-context]
  (let [propagates? (volatile! false)]
    (letfn [(visit [form]
              (cond
                (and (seq? form) (#{'quote 'container 'fn-decl} (first form))) form
                (and (seq? form) (= 'try (first form)) (= 2 (count form)))
                (do
                  (vreset! propagates? true)
                  (list 'catch (visit (second form)) 'aguafria.keyword/undefined))
                (coll? form) (walk/walk visit identity form)
                :else form))]
      (let [type-expression (visit expression)]
        (when @propagates?
          ;; @TypeOf cannot contain `try` outside a function body. The catch
          ;; yields the same payload type without propagating an error; this
          ;; expression is used only for type analysis, never execution.
          (list 'aguafria.keyword/TypeOf
                (if result-context
                  (list 'aguafria.keyword/as type-expression result-context)
                  type-expression)))))))

(defn- scoped-comptime-source [type descriptor]
  (let [source (cond
                 (and (map? descriptor) (= #{:comptime} (set (keys descriptor))))
                 (:comptime descriptor)
                 (and (map? descriptor) (= #{:literal :type} (set (keys descriptor))))
                 (:literal descriptor)
                 (and (map? descriptor) (= #{:comptime-type} (set (keys descriptor))))
                 (type-expression (:comptime-type descriptor))
                 (and (map? descriptor) (= #{:comptime-expression} (set (keys descriptor))))
                 (:comptime-expression descriptor)
                 (= :null descriptor) nil
                 :else (throw (ex-info "Scoped comptime capture has no closed native value"
                                       {:type type :descriptor descriptor
                                        :reason :comptime-capture-source-unavailable})))]
    (when (or (nil? type)
              (some #(and (symbol? %) (:aguafria/local? (meta %)))
                    (tree-seq coll? seq [type source])))
      (throw (ex-info "Scoped comptime capture has detached lexical source"
                      {:type type :descriptor descriptor
                       :reason :comptime-capture-source-unavailable})))
    ;; This type and value came from the same compiler specialization. Keep
    ;; the type too: replacing a typed comptime u1 with comptime_int changes
    ;; @TypeOf and dependent branches even when the integer value is equal.
    (list 'aguafria.keyword/as source type)))

(defn- scoped-comptime-operand [entry argument]
  (when (:mutable? entry)
    (throw (ex-info "A scoped comptime parameter cannot use live mutable storage"
                    {:capture (:name entry) :reason :comptime-capture-mutable})))
  (let [descriptor
        (cond
          (instance? PreparedExpression argument) {:comptime-expression (:expression argument)}
          (instance? PreparedType argument) {:comptime-type (:schema argument)}
          (value/zig-type? argument) {:comptime-type (constructor-type argument)}
          (value/zig-value? argument)
          (let [state (value/realize! argument)]
            ;; Const handles may still expose writable bytes through segment or
            ;; address aliases. Never substitute retained source for that live
            ;; storage: it can denote an older value. Scalar transports without
            ;; storage are immutable values and carry their current value.
            (if (and (= :scalar (:representation state)) (nil? (:segment state)))
              {:comptime (:value state)}
              (throw (ex-info "Scoped comptime capture cannot use addressable native storage"
                              {:capture (:name entry) :reason :comptime-capture-source-unavailable}))))
          (value/zig-error? argument) {:comptime-expression (value/error-form argument)}
          :else {:comptime argument})]
    (->PreparedExpression (scoped-comptime-source (:type entry) descriptor))))

(defn- scoped-argument-declarations [entries]
  (mapv #(if (:mutable? %) {:type :usize}
             {:type :anytype :properties {:jvm/literal? true}}) entries))

(defn- scoped-declaration
  ([context descriptor] (scoped-declaration context descriptor #{}))
  ([context descriptor staged-names]
   (binding [emitter/*registered-declaration-names*
             (into staged-names
                   (map :name (runtime/registered-declarations (ns-name context))))]
     (emitter/prepare-declaration
      context
      (merge {:module (str (ns-name context)) :public? false :export? false
              :jvm-adapter? true :implicit-return? true}
             descriptor)))))

(defn- scoped-proof-key [context profile]
  (tuple-profile-key
   context
   [:scoped-proof-v4
    (dissoc (runtime/toolchain-information) :materialized? :executable)
    (token (slurp (io/resource "aguafria/jvm_result.zig")))
    (boolean runtime/*native-test-context?*)
    profile]))

(defn- validate-scoped-declaration!
  "Analyze an unpublished adapter in an isolated compiler root. A rejected
  candidate must never enter the live declaration or prepared-adapter registries."
  ([context descriptor] (validate-scoped-declaration! context descriptor nil))
  ([context descriptor {:keys [support-declarations helper-reference helper-source] :as support}]
   (let [helper-source (or helper-source (slurp (io/resource "aguafria/jvm_result.zig")))
         key (scoped-proof-key context [:scoped-plan-validation-v3 descriptor support helper-source])
         cache-file (io/file (:cache-dir (runtime/configuration))
                             "scoped-proofs" (str key ".edn"))]
     (locking scoped-plan-proofs
       (or (get @scoped-plan-proofs key)
           (let [stored (when (.isFile cache-file)
                          (try (edn/read-string (slurp cache-file))
                               (catch Exception _ nil)))
                 proof
                 (if (and (= key (:key stored)) (= :zig-compiler (:basis stored))
                          (zero? (or (:exit stored) -1)))
                   stored
                   (let [staged-names (into #{} (keep :name)
                                            (cons descriptor support-declarations))
                         ephemeral (fn [descriptor]
                                     (scoped-declaration
                                      context
                                      (if helper-reference
                                        (walk/postwalk-replace
                                         {helper-reference
                                          '(aguafria.zig/raw "@import(\"jvm_result.zig\").__aguafria_jvm")}
                                         descriptor)
                                        descriptor)
                                      staged-names))
                         declaration (ephemeral descriptor)
                         module (str (ns-name context))
                         result
                         (runtime/inspect-module!
                          module
                          (fn [declarations]
                            (let [declarations
                                  (vals (into (array-map)
                                              (map (juxt :declaration-key identity))
                                              (concat (remove #(= :test (:kind %)) declarations)
                                                      (filter :jvm-adapter?
                                                              (runtime/registered-declarations module))
                                                      (map ephemeral support-declarations)
                                                      [declaration])))
                                  function (emitter/identifier (:name declaration))]
                              {:declarations declarations
                               :source
                               (str (emitter/emit-module module declarations)
                                    "\nfn __aguafria_validate_scoped(args: @import(\"std\").meta.ArgsTuple(@TypeOf("
                                    function "))) anyerror!void {\n"
                                    "    const result = @call(.auto, " function ", args);\n"
                                    "    _ = if (@typeInfo(@TypeOf(result)) == .error_union) try result else result;\n}\n"
                                    "test \"aguafria scoped validation\" { _ = &__aguafria_validate_scoped; }\n")
                               :files {"jvm_result.zig" helper-source}})))]
                     (when-not (zero? (:exit result))
                       (throw (ex-info "Native scoped adapter requires unavailable lexical context"
                                       (merge {:aguafria/phase :scoped-preparation
                                               :function (:qualified-name descriptor)
                                               :unpublished? true :stderr (:err result)}
                                              (select-keys result [:exit :command :source-path])))))
                     (let [proof (merge {:key key :basis :zig-compiler}
                                        (select-keys result [:exit :command :source-path]))]
                       (io/make-parents cache-file)
                       (spit cache-file (artifact/print-data proof))
                       proof)))]
             (swap! scoped-plan-proofs assoc key proof)
             proof))))))

(defn- scoped-plan [caller form entries {:keys [parameters expression-arguments]} result? result-context retained]
  (let [context (the-ns caller)
        ;; Stored compiler observations may carry an imported reference while
        ;; an ordinary native handle carries the same module-local reference.
        ;; Hash both only after rebasing them into the actual scoped caller.
        parameters (mapv #(update % :type (partial qualify-native-type context)) parameters)
        replacements (into {}
                           (map (fn [[{:keys [name type mutable?]} argument]]
                                  [name (cond
                                          (contains? retained name)
                                          (list 'aguafria.zig/raw (get-in retained [name :native-source]))
                                          mutable?
                                          (list 'aguafria.zig/deref
                                                (list 'aguafria.keyword/as
                                                      (list 'aguafria.keyword/ptrFromInt argument)
                                                      [:* type]))
                                          :else argument)]))
                           (map vector entries expression-arguments))
        expression (binding [emitter/*local-type-bindings* (zipmap (map :name entries) (repeat false))
                             emitter/*local-name-bindings* replacements]
                     (emitter/qualify-form context form))
        expression (if (and (seq? expression)
                            (= "if-capture-stmt" (name (first expression))))
                     (list 'aguafria.zig/block expression)
                     expression)
        guards (for [[entry argument] (map vector entries expression-arguments)
                     :let [candidate (get retained (:name entry))]
                     :when candidate]
                 (list 'try
                       (list (list 'aguafria.zig/raw
                                   (str "(struct { fn guard(actual: anytype, comptime candidate: @TypeOf(actual)) "
                                        "error{AguafriaScopedCaptureValueChanged}!void { "
                                        "if (!@import(\"std\").meta.eql(actual, candidate)) "
                                        "return error.AguafriaScopedCaptureValueChanged; } }).guard"))
                             argument (list 'aguafria.zig/raw (:native-source candidate)))))
        result-preconditions
        (when result?
          (mapv (fn [[entry argument]]
                  (when-let [candidate (get retained (:name entry))]
                    (list (list 'field '__aguafria_jvm :scopedCaptureMatches)
                          argument (list 'aguafria.zig/raw (:native-source candidate)))))
                (filter #(contains? retained (:name (first %)))
                        (map vector entries expression-arguments))))
        expression (if (and (not result?) (seq guards))
                     (apply list 'aguafria.zig/block (concat guards [expression]))
                     expression)
        name (symbol (str "__jvm_scope_"
                          (token (cond-> [expression parameters]
                                   result-context (conj result-context)
                                   result? (conj :result)
                                   runtime/*native-test-context?* (conj :test)))))
        function (symbol (str caller) (str name))
        result-type (when-let [queried (scoped-result-type-expression expression result-context)]
                      (if result? queried :void))]
    {:context context :function function :parameters parameters
     :retained-captures retained
     :result-preconditions result-preconditions
     :declaration
     (when (or (not result?) result-type)
       (cond-> {:kind :fn :name name :qualified-name function
                :declaration-key [:fn name] :args parameters
                :return :void :body [expression]}
         result-type (assoc :zig-prefix "inline"
                            :return [:error-union :anyerror result-type]
                            :body (if result? [(list 'return expression)] [expression]))))
     :propagates-errors? (some? result-type)
     :inspection-type (when (and result? result-context (not result-type)) result-context)
     :expression (cond
                   result-type (apply list name (map :name parameters))
                   result-context
                   (with-meta (list 'aguafria.keyword/as expression result-context)
                     {:aguafria/jvm-result-type result-context})
                   :else expression)}))

(defn- scoped-constant-profile! [caller form entries inputs query?]
  (let [context (the-ns caller)
        names (vec (sort-by str (map :name (remove #(or (:mutable? %) (:comptime? %)) entries))))
        canonical (binding [emitter/*local-name-bindings* (zipmap (map :name entries) (map :name entries))
                            emitter/*local-type-bindings* (zipmap (map :name entries) (repeat false))]
                    (emitter/qualify-form context form))
        parameter-types (into {} (map (juxt :name :type)) (:parameters inputs))
        types (into (sorted-map)
                    (keep (fn [[entry argument]]
                            (when (some #{(:name entry)} names)
                              [(:name entry) (or (get parameter-types argument)
                                                 (list 'aguafria.keyword/TypeOf argument))])))
                    (map vector entries (:expression-arguments inputs)))
        key (scoped-proof-key context [:scoped-constant-proof canonical types
                                       (or (:aguafria/scoped-capture-contracts (meta form)) {})])
        file (io/file (:cache-dir (runtime/configuration)) "scoped-proofs" (str key ".edn"))
        saved (when (.isFile file)
                (try (edn/read-string (slurp file)) (catch Exception _ nil)))]
    (if (and (= key (:key saved)) (= :zig-compiler (:basis saved)))
      saved
      (when query?
        (let [proof (assoc ((requiring-resolve 'aguafria.zig.discovery/prove-scoped-constants!)
                            caller canonical types)
                           :key key)]
          (when (= true query?)
            (io/make-parents file)
            (spit file (artifact/print-data proof)))
          proof)))))

(defn- prepare-scoped-plan! [caller form entries inputs result? result-context]
  (let [transport-plan
        (fn [plan]
          (if result?
            (assoc plan :transport-plan
                   (expression-adapter-plan
                    (:context plan) (:expression plan) (:parameters plan)
                    (if (seq (:result-preconditions plan)) 'scopedResult 'result)
                    {:preconditions (:result-preconditions plan)}))
            plan))
        validate!
        (fn [plan]
          (if-let [adapter (:transport-plan plan)]
            (when-not (registered-adapter? prepared-adapters (:adapter-key adapter) (:function adapter))
              (validate-scoped-declaration!
               (:context adapter) (last (:declarations adapter))
               {:support-declarations (keep identity [(:declaration plan)])
                :helper-reference (:helper-reference adapter)
                :helper-source (:helper-source adapter)}))
            (when-let [declaration (:declaration plan)]
              (when-not (registered-adapter? prepared-adapters (:function plan) (:function plan))
                (validate-scoped-declaration! (:context plan) declaration))))
          plan)
        ordinary (transport-plan (scoped-plan caller form entries inputs result? result-context nil))
        saved (scoped-constant-profile! caller form entries inputs false)
        candidate (if (:proven? saved)
                    (transport-plan (scoped-plan caller form entries inputs result? result-context (:candidates saved)))
                    ordinary)
        plan
        (try
          (validate! candidate)
          (catch clojure.lang.ExceptionInfo failure
              ;; Only a failed ordinary compiler contract can acquire an
              ;; optional proven profile. Failed candidates remain unpublished.
            (if (and (:unpublished? (ex-data failure)) (not (:proven? saved))
                     (some #(not (or (:mutable? %) (:comptime? %))) entries))
              (let [proof (scoped-constant-profile! caller form entries inputs :ephemeral)]
                (if (:proven? proof)
                  (let [retained (validate! (transport-plan
                                             (scoped-plan caller form entries inputs result? result-context
                                                          (:candidates proof))))
                        file (io/file (:cache-dir (runtime/configuration)) "scoped-proofs"
                                      (str (:key proof) ".edn"))]
                    (io/make-parents file)
                    (spit file (artifact/print-data proof))
                    retained)
                  (throw (ex-info (ex-message failure)
                                  (assoc (ex-data failure) :optional-proof proof) failure))))
              (throw failure))))
        context (:context plan)]
    (when-let [declaration (:declaration plan)]
      (locking context
        (when-not (registered-adapter? prepared-adapters (:function plan) (:function plan))
          (binding [runtime/*source-only-registration?* true]
            (register! context declaration))
          (swap! prepared-adapters conj (:function plan)))))
    (when-let [adapter (:transport-plan plan)]
      (publish-expression-plan! adapter))
    plan))

(defn invoke-scoped!
  "Execute native scoped syntax with JVM lexical captures in the same process.
  Mutable captures are passed by address, not silently copied into parameters."
  ([caller form locals] (invoke-scoped! caller form locals false nil))
  ([caller form locals result?] (invoke-scoped! caller form locals result? nil))
  ([caller form locals result? result-context]
   (if (and result? (nil? result-context)
            (emitter/scoped-result-context-required? (the-ns caller) form))
     (->ContextualScope caller form locals)
     (binding [runtime/*native-test-context?*
               (or runtime/*native-test-context?*
                   (some #(and (value/zig-value? %)
                               (= :test (:execution-context (value/info %)))) (vals locals)))]
       (let [values (sort-by (comp str key) locals)
             contracts (:aguafria/scoped-capture-contracts (meta form))
             entries (mapv (fn [[name v]]
                             (let [mutable? (and (value/zig-value? v) (= :var (:kind (value/info v))))
                                   contract (get contracts name)
                                   comptime? (= :comptime (:phase contract))]
                               {:name name :mutable? mutable?
                                :comptime? comptime?
                                :type (if comptime? (:type contract)
                                          (when mutable? (value/qualified-type v)))})) values)
             operands (mapv (fn [[entry [_ v]]]
                              (cond
                                (:comptime? entry) (scoped-comptime-operand entry v)
                                (:mutable? entry) (.address ^MemorySegment (value/segment v))
                                :else v))
                            (map vector entries values))
             inputs (call-inputs (scoped-argument-declarations entries) operands)
             {:keys [context expression parameters function propagates-errors? transport-plan]}
             (prepare-scoped-plan! caller form entries inputs result? result-context)]
         (try
           (let [result (if result?
                          (invoke-adapter! transport-plan (:arguments inputs))
                          (runtime/invoke! function (:arguments inputs)))]
             (if propagates-errors? (value/try-value! result) result))
           (catch clojure.lang.ExceptionInfo failure
             (if (and (not result?)
                      (= :AguafriaScopedCaptureValueChanged (:error-name (ex-data failure))))
               (throw (ex-info "Scoped capture changed; the original compiler-known branch is no longer valid"
                               {:aguafria/phase :scoped-capture-contract
                                :reason :scoped-capture-value-changed
                                :function function
                                :hint "Run this form in its enclosing native function, or use a runtime-valid branch."}
                               failure))
               (throw failure)))
           (finally (java.lang.ref.Reference/reachabilityFence locals))))))))

(defn- describe-native-type!
  [target]
  (let [var-reference (when (var? target) (:aguafria/zig-reference (meta target)))
        target (if (var? target) (var-get target) target)
        reference (or var-reference (:aguafria/zig-reference (meta target)))
        owner (or (when (= :declaration (:kind reference)) (:module reference))
                  (when (value/zig-value? target) (:module (value/info target)))
                  (when (value/zig-type? target) (:module (value/type-info target))))
        namespace-name 'aguafria.jvm.describe
        context (or (find-ns namespace-name) (create-ns namespace-name))
        type (cond
               (value/zig-type? target) (constructor-type target)
               var-reference (list 'aguafria.keyword/TypeOf
                                   (with-meta (:symbol var-reference)
                                     {:aguafria/zig-reference var-reference}))
               (value/zig-value? target) (value/qualified-type target)
               (value/zig-pointer? target) (value/pointer-type target)
               :else
               (let [{:keys [expression-arguments parameters]}
                     (call-inputs [{:type :anytype :properties {:jvm/literal? true}}]
                                  [target])]
                 (list 'aguafria.keyword/TypeOf
                       (expression-without-runtime-inputs
                        context (first expression-arguments) parameters))))
        adapter-name (symbol (str namespace-name "-" (token type)))
        ;; Private declarations are legal in their defining module only.
        ;; Reflect there rather than importing them or making them public.
        adapter-context (or (some-> owner symbol find-ns)
                            (find-ns adapter-name)
                            (create-ns adapter-name))
        description (invoke-expression! adapter-context (list 'type type) [] [] 'describeResult)]
    (-> description
        (update :kind keyword)
        (update :fields #(mapv (fn [field] (update field :name keyword)) %))
        (update :members #(mapv (fn [member]
                                  (-> member (update :name keyword) (update :kind keyword))) %)))))

(defn describe!
  "Describe a receiver through native type reflection, without reading its storage."
  [target]
  (let [reference (or (:aguafria/zig-reference (meta target))
                      (when (var? target)
                        (:aguafria/zig-reference (meta (var-get target)))))]
    (if (or (:receiver-method? reference) (:field-accessor? reference))
      ;; An unspecialized accessor is a JVM callable, not the invalid Zig
      ;; expression `ArrayList.append`. Its native signature needs a receiver.
      {:kind :fn :signature (:signature reference)
       :requires-receiver? true :var (:symbol reference) :fields [] :members []}
      (describe-native-type! target))))

(defn- member-owner
  "Locate the defining scope of an explicit native type identity, not its layout.
  Imported library types stay in the ordinary external adapter scope."
  [type]
  (let [container (if (and (vector? type) (#{:* :*const} (first type)))
                    (last type)
                    type)
        reference (some (fn [candidate]
                          (when (and (qualified-symbol? candidate)
                                     (:aguafria/declaration
                                      (some-> candidate find-var meta)))
                            candidate))
                        (if (seq? container)
                          (tree-seq coll? seq container)
                          [container]))
        declaration (some-> reference find-var meta :aguafria/declaration)
        context (some-> declaration :module symbol find-ns)]
    (when context
      {:context context :container (emitter/qualify-type context container)})))

(defn- native-member-name [member]
  (let [spelling (emitter/identifier member)]
    (if (str/starts-with? spelling "@\"")
      (list 'aguafria.zig/string-literal (subs spelling 1))
      spelling)))

(defn- field-view-plan [receiver member address]
  (let [receiver (walk/postwalk
                  (fn [form]
                    (if (and (seq? form) (= 2 (count form))
                             (contains? #{'type 'aguafria.zig/type} (first form)))
                      (second form)
                      form))
                  receiver)
        {:keys [context container]} (member-owner receiver)
        receiver (if context (emitter/qualify-type context receiver) receiver)
        address (if context (emitter/qualify-type context address) address)
        member-name (native-member-name member)
        ordinary (list '(field __aguafria_jvm :fieldView) 'input_0 member-name)
        type (list 'type container)
        declaration-method? (list 'aguafria.keyword/==
                                  (list 'aguafria.keyword/typeInfo
                                        (list 'aguafria.keyword/TypeOf
                                              (list 'aguafria.keyword/field type member-name)))
                                  '(aguafria.zig/enum-literal ".@\"fn\""))
        method? (when context
                  (list 'aguafria.keyword/switch
                        (list 'aguafria.keyword/typeInfo type)
                        (list 'case
                              (mapv #(list 'aguafria.zig/enum-literal (str ".@\"" % "\""))
                                    ["struct" "union" "enum"])
                              (list 'if
                                    (list 'aguafria.keyword/hasField type member-name)
                                    false
                                    declaration-method?))
                        (list 'case ['(aguafria.zig/enum-literal ".@\"opaque\"")]
                              declaration-method?)
                        (list 'aguafria.zig/case-else false)))]
    {:module (if context (ns-name context) 'aguafria.jvm.field-storage)
     ;; Zig 0.17 hides private declarations from @hasDecl even in their owner.
     ;; Query the member's actual type in the owner after excluding fields.
     ;; An unknown member is a compiler error, not an absent-method fallback.
     :expression (if context
                   (list 'if (list 'aguafria.keyword/comptime method?)
                         '((field __aguafria_jvm :boundMethod))
                         ordinary)
                   ordinary)
     :writer ['borrowedFieldResult (list 'type receiver)
              (artifact/print-data receiver) (artifact/print-data member-name)]
     :types [address]}))

(defn- method-plan
  [receiver-type member mutable? native? {:keys [parameters expression-arguments]}]
  (let [address-name 'receiver_address
        pointer (list 'aguafria.keyword/as
                      (list 'aguafria.keyword/ptrFromInt address-name)
                      [(if mutable? :* :*const) receiver-type])
        target (if native? (list 'aguafria.zig/deref pointer) (first expression-arguments))
        module (symbol (str "aguafria.jvm.method-"
                            (token [receiver-type member mutable? native?])))
        callable (list 'aguafria.zig/field target member)
        arguments (if native? expression-arguments (rest expression-arguments))
        receiver-schema (list 'type receiver-type)
        receiver-info (list 'aguafria.keyword/typeInfo receiver-schema)
        pointer-info (list 'aguafria.zig/field receiver-info :pointer)
        container (when native?
                    (list 'if (list 'aguafria.keyword/== receiver-info :.pointer)
                          (list 'if (list 'aguafria.keyword/==
                                          (list 'aguafria.zig/field pointer-info :size) :.one)
                                (list 'aguafria.zig/field pointer-info :child) receiver-schema)
                          receiver-schema))
        function-reference (if native? (list 'aguafria.zig/field container member) callable)
        function-parameters
        (list 'aguafria.zig/field
              (list 'aguafria.zig/field
                    (list 'aguafria.keyword/typeInfo
                          (list 'aguafria.keyword/TypeOf function-reference)) :fn) :param_types)
        parameter-types (into {} (map (juxt :name :type)) parameters)
        arguments
        (mapv (fn [index argument]
                (if-let [input-type (and (symbol? argument) (get parameter-types argument))]
                  (let [index (+ index (if native? 1 0))
                        target-type (list 'if
                                          (list 'aguafria.keyword/< index
                                                (list 'aguafria.zig/field function-parameters :len))
                                          (list 'aguafria.keyword/orelse
                                                (list 'aguafria.zig/index function-parameters index)
                                                (list 'aguafria.keyword/TypeOf argument))
                                          (list 'aguafria.keyword/TypeOf argument))
                        target-name (symbol (str "__aguafria_parameter_type_" index))
                        kind? (fn [type kind]
                                (list 'aguafria.keyword/== (list 'aguafria.keyword/typeInfo type)
                                      (keyword (str "." kind))))
                        pair? (fn [target-kind input-kind]
                                (list 'aguafria.keyword/comptime
                                      (list 'and (kind? target-name target-kind)
                                            (kind? (list 'aguafria.keyword/TypeOf argument) input-kind))))]
                    ;; A concrete JVM call converts scalar inputs to its ABI
                    ;; parameter types. Ask Zig for the member's types too;
                    ;; generic/variadic inputs keep their own type. math.cast
                    ;; checks integer range even in optimized adapters.
                    (list 'let [target-name target-type]
                          (list 'if (pair? "int" "int")
                                (list 'aguafria.keyword/orelse
                                      (list '(field (field (aguafria.keyword/import "std") :math) :cast)
                                            target-name argument)
                                      (list 'aguafria.keyword/panic "Zig integer argument is out of range"))
                                (list 'if (pair? "float" "float")
                                      (list 'aguafria.keyword/as
                                            (list 'aguafria.keyword/floatCast argument) target-name)
                                      (list 'if (pair? "float" "int")
                                            (list 'aguafria.keyword/as
                                                  (list 'aguafria.keyword/floatFromInt argument) target-name)
                                            argument)))))
                  argument)) (range) arguments)]
    {:context (or (:context (member-owner receiver-type))
                  (find-ns module) (create-ns module))
     :expression (apply list callable arguments)
     :writer (if (and (not native?) (qualified-symbol? receiver-type))
               ['nominalResult receiver-schema (artifact/print-data receiver-type)]
               'result)
     :parameters (if native? (into [{:name address-name :type :usize}] parameters) parameters)}))

(defn- bound-method [receiver member]
  (fn [& arguments]
    (let [native? (and (value/zig-value? receiver)
                       (= :native (:representation (value/realize! receiver))))
          receiver-type (emitter/qualify-type *ns*
                                              (if (value/zig-value? receiver) (value/qualified-type receiver)
                                                  (constructor-type receiver)))
          mutable? (and native? (= :var (:kind (value/info receiver))))
          operands (if native? arguments (cons receiver arguments))
          {:keys [arguments] :as inputs}
          (call-inputs (repeat (count operands)
                               {:type :anytype :properties {:jvm/literal? true}})
                       operands)
          {:keys [context expression parameters writer]}
          (method-plan receiver-type member mutable? native? inputs)]
      (binding [runtime/*native-test-context?*
                (or runtime/*native-test-context?*
                    (and native? (= :test (:execution-context (value/info receiver)))))]
        (try
          (invoke-expression! context expression
                              parameters
                              (if native?
                                (into [(.address ^MemorySegment (value/segment receiver))] arguments)
                                arguments)
                              writer)
          (finally (java.lang.ref.Reference/reachabilityFence receiver)))))))

(defn- container-variable
  [receiver member]
  (when (value/zig-type? receiver)
    (some #(when (and (= :var (:kind %))
                      (= (name member) (name (:name %)))) %)
          (:members (value/type-info receiver)))))

(defn- prepare-variable-storage! [namespace-name field field-type mutable?]
  (let [namespace-name (clojure.core/symbol namespace-name)
        context (or (find-ns namespace-name) (create-ns namespace-name))
        field (emitter/qualify-form context field)
        field-type (emitter/qualify-type context field-type)
        accessor (clojure.core/symbol (str "__jvm_member_" (token [field field-type mutable?])))
        qualified-name (clojure.core/symbol (str namespace-name) (str accessor))
        pointer (list 'aguafria.keyword/as
                      (list 'aguafria.keyword/ptrCast (list 'aguafria.keyword/& field))
                      [(if mutable? :* :*const) [:array 1 field-type]])]
    (locking context
      (when-not (registered-adapter? prepared-adapters qualified-name qualified-name)
        (binding [runtime/*source-only-registration?* true]
          (register! context
                     {:kind :fn :name accessor :qualified-name qualified-name
                      :declaration-key [:fn accessor]
                      :return [(if mutable? :slice :slice-const) field-type]
                      :args [] :body [(list 'aguafria.zig/slice pointer 0 1)]}))
        (swap! prepared-adapters conj qualified-name))
      qualified-name)))

(defn- variable-storage-view!
  ([namespace-name field field-type]
   (variable-storage-view! namespace-name field field-type true))
  ([namespace-name field field-type mutable?]
   (let [function (prepare-variable-storage! namespace-name field field-type mutable?)]
     ;; The native slice return pins its library generation. Its element view
     ;; points to the actual container variable, not a detached JVM copy.
     (value/slice-element-view (runtime/invoke! function []) 0 mutable?))))

(defn constant-view!
  "Borrow immutable constant storage with its Zig-inferred type and owner."
  [{:keys [module name type]}]
  (let [field (symbol module (str name))]
    (variable-storage-view! module field
                            (or type (list 'aguafria.keyword/TypeOf field)) false)))

(defn- comptime-field-expression [receiver member]
  (with-meta (list '(field __aguafria_jvm :lookupField) receiver (native-member-name member))
    {:aguafria/jvm-value-expression (list 'aguafria.zig/field receiver member)}))

(defn variable-view!
  "Borrow a top-level variable, letting Zig resolve its inferred storage type."
  [{:keys [module name type]}]
  (let [field (symbol module (str name))]
    (variable-storage-view! module field (or type (list 'aguafria.keyword/TypeOf field)))))

(defn- container-variable-view!
  [receiver member]
  (let [type (constructor-type receiver)
        owner (:module (value/type-info receiver))
        field (list 'aguafria.zig/field (list 'type type) member)]
    (variable-storage-view!
     (or owner (str "aguafria.jvm.container-" (token type))) field
     (or (:type (container-variable receiver member))
         (list 'aguafria.keyword/TypeOf field)))))

(defn dereference-pointer!
  "Borrow a pointer's pointee, retaining native arguments and constness.
  A mutable pointee remains an assignable native handle, not a detached scalar."
  [pointer]
  (let [type (if (value/zig-value? pointer)
               (value/qualified-type pointer)
               (value/pointer-type pointer))
        module (clojure.core/symbol (str "aguafria.jvm.pointee-" (token type)))
        context (or (find-ns module) (create-ns module))
        {:keys [expression-arguments parameters arguments]}
        (call-inputs [{:type :anytype}] [pointer])
        expression (cond-> (list '(field __aguafria_jvm :dereferenceView) (first expression-arguments))
                     (and (vector? type) (#{:* :*const :pointer} (first type)))
                     (with-meta {:aguafria/jvm-borrowed-type (peek type)}))]
    (value/retain-owners!
     (invoke-expression! context expression parameters arguments 'borrowedResult)
     [pointer])))

(defn- field-storage-view!
  [receiver member]
  (let [storage (value/address-value receiver (= :var (:kind (value/info receiver))))
        {:keys [parameters arguments]}
        (call-inputs [{:type :anytype}] [storage])
        {:keys [module expression writer]}
        (field-view-plan (value/qualified-type receiver) member (:type (first parameters)))
        context (or (find-ns module) (create-ns module))
        result (invoke-expression! context expression parameters arguments writer)]
    (if (= {:aguafria.jvm/bound-method true} result)
      (bound-method receiver member)
      (value/retain-owners! result [receiver storage]))))

(defn- ordinary-array-type [receiver]
  (when (value/zig-value? receiver)
    (let [type (value/qualified-type receiver)]
      (when (and (vector? type) (= :array (first type)) (= 3 (count type)))
        type))))

(defn- runtime-array-plan [operation inputs]
  (let [{:keys [expression-arguments] :as plan}
        (call-inputs (cons {:type :anytype} (repeat (dec (count inputs)) {:type :usize})) inputs)]
    (assoc plan :module 'aguafria.jvm.array-storage
           :expression (apply list (list 'field '__aguafria_jvm operation) expression-arguments))))

(defn- runtime-array-operation!
  [receiver operation operands]
  (let [type (ordinary-array-type receiver)
        pointer (value/array-elements-pointer receiver)
        inputs (into [pointer (second type)] operands)
        {:keys [expression parameters arguments]}
        (runtime-array-plan operation inputs)
        context (or (find-ns 'aguafria.jvm.array-storage)
                    (create-ns 'aguafria.jvm.array-storage))
        result (invoke-expression! context expression parameters arguments 'borrowedResult)]
    (value/retain-owners! result [receiver pointer])))

(defn- index-reflection? [type]
  (not (and (vector? type)
            (#{:array :vector :slice :slice-const :* :*const
               :many :many-const :c-pointer} (first type)))))

(defn- index-plan [type storage index comptime-index?]
  (let [inputs (call-inputs [{:type :anytype}
                             (if comptime-index? {:type :comptime_int} {:type :usize})]
                            [storage index])]
    (assoc inputs
           :module (symbol (str "aguafria.jvm.index-" (token type)))
           :expression (apply list
                              (list 'field '__aguafria_jvm
                                    (if comptime-index? :tupleIndexView :indexView))
                              (:expression-arguments inputs)))))

(defn- tuple-length-plan [type]
  (let [module (symbol (str "aguafria.jvm.index-" (token type)))
        context (or (find-ns module) (create-ns module))
        expression
        (list 'if
              (list 'aguafria.keyword/comptime
                    (list '(field __aguafria_jvm :indexRequiresComptime) (list 'type type)))
              (list 'aguafria.zig/field
                    (list 'aguafria.zig/field
                          (list 'aguafria.zig/field
                                (list 'aguafria.keyword/typeInfo (list 'type type)) :struct)
                          "field_types")
                    :len)
              nil)]
    (prepare-expression! context expression [] 'inspectResult)))

(defn- native-index-type [native-value]
  (let [descriptor (value/info native-value)
        context (or (some-> (:module descriptor) symbol find-ns) *ns*)]
    ;; Use the same qualified reflection form before choosing a module token
    ;; that preparation uses. Public and private producer types both keep
    ;; their declaration identity; metadata is not a second module identity.
    (emitter/qualify-type context
                          (or (:inspection-type descriptor)
                              (value/qualified-type native-value)))))

(defn tuple-length!
  "Ask Zig whether native storage is a tuple and, if so, its element count.
  This examines only its exact type, never its contents or source declaration."
  [native-value]
  (let [type (native-index-type native-value)]
    (when (index-reflection? type)
      (binding [runtime/*native-test-context?*
                (or runtime/*native-test-context?*
                    (= :test (:execution-context (value/info native-value))))]
        (invoke-adapter! (tuple-length-plan type) [])))))

(defn- query-compiler-tuple-profile! [context type]
  ;; Builtin scalar and structural vector type operators cannot denote a
  ;; struct. Only unresolved native type identities need a compiler query.
  (when (and (not (keyword? type)) (not (vector? type)))
    (let [module (str (ns-name context))
          type (emitter/qualify-type context type)
          result
          (runtime/inspect-module!
           module
           (fn [declarations]
             (let [inspector (str "__aguafria_tuple_profile_" (token type))
                   declarations
                   (vals (into (array-map)
                               (map (juxt :declaration-key identity))
                               (concat declarations
                                       (filter :jvm-adapter?
                                               (runtime/registered-declarations module)))))]
               {:declarations declarations
                :source
                (str (emitter/emit-module module declarations)
                     "\ncomptime {\n"
                     "    const T = " (emitter/emit-type context type) ";\n"
                     "    const " inspector " = @import(\"operation_probe.zig\").Inspector(.{});\n"
                     "    if (@typeInfo(T) == .@\"struct\" and @typeInfo(T).@\"struct\".is_tuple) {\n"
                     "        const info = @typeInfo(T).@\"struct\";\n"
                     "        var elements: []const u8 = \"[\";\n"
                     "        for (info.field_types) |Field| elements = elements ++ " inspector ".schema(Field) ++ \" \";\n"
                     "        " inspector ".log(\"aguafria.tuple-profile:0:\" ++ @import(\"std\").fmt.comptimePrint(\"{{:length {d} :type {s} :elements {s}]}}\", .{info.field_types.len, " inspector ".schema(T), elements}));\n"
                     "    } else " inspector ".log(\"aguafria.tuple-profile:0:nil\");\n"
                     "}\n")
                :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                        "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}})))
          log (second (str/split (:err result) #"Compile Log Output:\r?\n" 2))
          observations
          (keep (fn [[_ encoded]]
                  (let [message (String. (.parseHex (java.util.HexFormat/of) encoded)
                                         java.nio.charset.StandardCharsets/UTF_8)]
                    (when (str/starts-with? message "aguafria.tuple-profile:0:")
                      {:profile (edn/read-string (subs message (count "aguafria.tuple-profile:0:")))})))
                (re-seq #"\"aguafria\.operation\.hex:([0-9a-f]+)\"" (or log "")))]
      (when (or (re-find #"(?m)error: (?!found compile log statement)" (:err result))
                (not= 1 (count observations)))
        (throw (ex-info "Zig could not describe native tuple dependencies"
                        (select-keys result [:exit :err :command :source-path]))))
      (:profile (first observations)))))

(defn- tuple-profile-key [context type]
  (let [module (str (ns-name context))
        declarations (runtime/registered-declarations module)
        adapters (filterv :jvm-adapter? declarations)
        references (fn [forms] (into #{} (filter symbol?) (tree-seq coll? seq forms)))
        selected
        (loop [names (references type) selected #{}]
          (let [found (into selected
                            (keep #(when (or (contains? names (:name %))
                                             (contains? names (:qualified-name %)))
                                     (:declaration-key %))) adapters)]
            (if (= found selected)
              (filterv #(contains? found (:declaration-key %)) adapters)
              (recur (into names (references (filterv #(contains? found (:declaration-key %)) adapters)))
                     found))))]
    ;; Source generation, dependency identities and compiler options invalidate
    ;; metadata. Adding unrelated adapters does not. No inferred type enters
    ;; this key; the cached result is always an actual compiler observation.
    (runtime/adapter-fingerprint
     [module type (dissoc (runtime/configuration) :cache-dir)
      (sort-by pr-str
               (map #(select-keys % [:declaration-key :logical-id :schema-fingerprint
                                     :shape-fingerprint :implementation-fingerprint
                                     :source-fingerprint :type-dependency-fingerprints
                                     :callable-dependency-fingerprints])
                    (concat (remove :jvm-adapter? declarations) selected)))
      (slurp (io/resource "aguafria/operation_probe.zig"))
      (slurp (io/resource "aguafria/jvm_result.zig"))])))

(defn- compiler-tuple-profile! [context type]
  (when (and (not (keyword? type)) (not (vector? type)))
    (let [key (tuple-profile-key context type)]
      (locking tuple-profiles
        (if (contains? @tuple-profiles key)
          (get @tuple-profiles key)
          (let [profile (query-compiler-tuple-profile! context type)]
            (swap! tuple-profiles #(assoc (if (< (count %) 256) % {}) key profile))
            profile))))))

(defn precompile-tuple-sequence!
  "Prepare ordinary tuple iteration from compiler-owned result metadata.
  Direct callable ABI values retain their reflected result identity. Expression
  envelopes use Zig's structural schema only when the compiler certifies it."
  ([context type] (precompile-tuple-sequence! context type false))
  ([context type callable-result?]
   (when-let [{:keys [length elements] native-type :type} (compiler-tuple-profile! context type)]
     (let [receiver (if callable-result? (emitter/qualify-type context type)
                        (or native-type (emitter/qualify-type context type)))]
       (precompile-expression! (tuple-length-plan receiver))
       (doseq [index (range length)]
         ((requiring-resolve 'aguafria.zig.jvm/precompile-storage!)
          {:kind :index :receiver receiver :address [:*const receiver]
           :indices [{:comptime index}]})
         (precompile-inspection!
          context (or (nth elements index)
                      (list 'aguafria.keyword/FieldType receiver (str index)))))
       {:type receiver :length length :basis :zig-compiler}))))

(defn- indexed-value!
  "Keep addressable indexed values attached to their original native storage.
  Zig decides the element type and pointee constness; vector lanes remain values."
  [receiver index]
  (let [owner (if (value/zig-value? receiver)
                receiver
                (coerce! receiver (value/pointer-type receiver)))
        type (native-index-type owner)
        module (clojure.core/symbol (str "aguafria.jvm.index-" (token type)))
        context (or (find-ns module) (create-ns module))
        comptime-index? (and (index-reflection? type)
                             (invoke-expression! context
                                                 (list '(field __aguafria_jvm :indexRequiresComptime)
                                                       (list 'type type))
                                                 [] [] 'inspectResult))
        storage (value/address-value owner (= :var (:kind (value/info owner))) type)
        {:keys [expression parameters arguments]}
        (index-plan type storage index comptime-index?)
        result (invoke-expression! context expression parameters arguments 'borrowedResult)]
    (value/retain-owners! result [owner storage])))

(defn- syntax-result-writer [syntax parameters handler-plan]
  (cond
    ;; An untyped object initializer must reach its destination before Zig
    ;; gives it a named struct type. Retain literal syntax across JVM calls.
    (and (= 'object (:name syntax)) (empty? parameters)) 'comptimeExpressionResult
    (and (= "++" (:zig-token syntax)) (empty? parameters)) 'concatenationResult
    (:result-type handler-plan) 'inspectResult))

(defn- runtime-index-operand? [operand]
  (cond
    (instance? ContextualCall operand)
    (boolean (some runtime-index-operand? (:arguments operand)))

    (instance? PreparedOperand operand)
    (not= :comptime_int (:type operand))

    (value/zig-value? operand)
    (not= :comptime_int (value/qualified-type operand))

    :else false))

(defn- syntax-call-declarations [syntax argument-count handler-plan runtime-slice?]
  (cond
    (= 'type (:name syntax))
    (call-parameters {:aguafria/syntax syntax} argument-count)

    runtime-slice?
    (let [parameters (into [{:type :anytype}]
                           (repeat (dec argument-count) {:type :usize}))]
      (if (= 'slice-sentinel (:name syntax))
        (assoc parameters (dec argument-count)
               {:type :anytype :properties {:jvm/literal? true}})
        parameters))

    handler-plan
    (mapv #(hash-map :type %) (:types handler-plan))

    (and (:signature syntax) (not (re-find #"\.\.\." (:signature syntax))))
    (builtin-arguments (:signature syntax))

    :else
    (repeat argument-count
            {:type :anytype
             :properties {:jvm/literal? (or (#{:syntax :operator} (:kind syntax))
                                            (:literal-arguments? syntax))}})))

(defn invoke-syntax!
  "Execute a Zig builtin or value expression through the shared native bridge.
  Comptime parameters stay in source; runtime operands retain their Zig types."
  [{:keys [symbol param-count minimum-param-count] :as syntax} arguments]
  (when (or (and param-count (not= param-count (count arguments)))
            (and minimum-param-count (< (count arguments) minimum-param-count)))
    (throw (ex-info "Wrong number of arguments for Zig call"
                    {:function symbol :actual (count arguments)
                     :expected param-count :minimum minimum-param-count})))
  (cond
    (and (= 'field (:name syntax))
         (value/zig-value? (first arguments))
         (value/zig-type? (value/value (first arguments))))
    (invoke-syntax! syntax (cons (value/value (first arguments)) (rest arguments)))

    (= 'assoc! (:name syntax))
    (assoc-native! (first arguments) (rest arguments))

    (= 'assign-expr (:name syntax))
    (let [[operator target operand] arguments
          assignment-name (when (string? operator) (keyword/token-name operator))
          assignment (when assignment-name
                       (ns-resolve 'aguafria.keyword (clojure.core/symbol assignment-name)))]
      (when-not (and (= 3 (count arguments))
                     (= :assignment (get-in (meta assignment) [:aguafria/token :kind])))
        (throw (ex-info "assign-expr expects an assignment operator string, target, and value"
                        {:arguments arguments})))
      (assignment target operand))

    (keyword/result-context-required? (:zig-name syntax))
    (->ContextualCall syntax (vec arguments))

    (and (= "&" (:zig-token syntax)) (= 1 (count arguments))
         (or (vector? (first arguments))
             (and (map? (first arguments)) (not (record? (first arguments))))))
    (->ContextualAddress (first arguments))

    (and (= "&" (:zig-token syntax)) (= 1 (count arguments))
         (not (:aguafria/zig-reference (meta (first arguments))))
         (not (comptime-expression (first arguments))))
    (let [argument (first arguments)
          native? (value/zig-value? argument)
          storage? (and native? (= :native (:representation (value/realize! argument))))
          owner (if storage? argument
                    (if native? (mutable! argument (value/qualified-type argument))
                        (mutable! argument)))
          mutable? (and native? (= :var (:kind (value/info argument))))]
      (value/address-value owner mutable?))

    (= 'vector (:name syntax))
    (coerce! (first arguments) (emitter/vector-initializer-type arguments))

    (= 'array (:name syntax))
    (let [type (emitter/array-initializer-type arguments)
          elements (first arguments)
          result (coerce! elements (assoc type 1 (count elements)))
          alignment (when (= 3 (count arguments)) (:align (second arguments)))]
      (if (some? alignment)
        (value/aligned-copy result (if (value/zig-value? alignment) (value/value alignment) alignment))
        result))

    (= 'init (:name syntax))
    (apply coerce! arguments)

    (and (= 'deref (:name syntax))
         (or (value/zig-value? (first arguments))
             (value/zig-pointer? (first arguments)))
         (not (comptime-expression (first arguments))))
    (dereference-pointer! (first arguments))

    (and (= 'index (:name syntax)) (ordinary-array-type (first arguments)))
    (runtime-array-operation! (first arguments) :arrayIndexView [(second arguments)])

    (and (= 'slice (:name syntax))
         (ordinary-array-type (first arguments))
         (some runtime-index-operand? (rest arguments)))
    (let [[receiver start end] arguments]
      (runtime-array-operation! receiver :runtimeArraySlice
                                [start (or end (second (ordinary-array-type receiver)))]))

    (and (= 'field (:name syntax))
         (container-variable (first arguments) (second arguments)))
    (container-variable-view! (first arguments) (second arguments))

    (and (= 'field (:name syntax))
         (value/zig-value? (first arguments))
         (= :native (:representation (value/realize! (first arguments)))))
    (apply field-storage-view! arguments)

    (and (= 'index (:name syntax))
         (not (comptime-expression (first arguments)))
         (or (value/zig-value? (first arguments))
             (value/zig-pointer? (first arguments))))
    (apply indexed-value! arguments)

    :else
    (let [receiver (first arguments)
          member (second arguments)
          native-field? (and (= 'field (:name syntax))
                             (or (value/zig-value? receiver)
                                 (:aguafria/zig-reference (meta receiver))))
          handler-plan (handlers/operator-plan syntax arguments)
          runtime-slice? (and (#{'slice 'slice-sentinel} (:name syntax))
                              (some runtime-index-operand?
                                    (if (= 'slice-sentinel (:name syntax))
                                      (butlast (rest arguments)) (rest arguments))))
          declarations (syntax-call-declarations syntax (count arguments) handler-plan runtime-slice?)
          operands (or (:arguments handler-plan) arguments)
          storage-source? (and (#{'slice 'slice-sentinel} (:name syntax)) (value/zig-value? receiver)
                               (not (comptime-expression receiver)))
          {:keys [parameters arguments] :as inputs}
          (if storage-source?
            ;; Slicing a copied array would return a dangling stack pointer.
            ;; Borrow the receiver's storage; retain it on the resulting view.
            (let [pointer (value/address-value receiver (= :var (:kind (value/info receiver))))
                  inputs (call-inputs declarations (cons pointer (rest arguments)))]
              (update inputs :expression-arguments
                      #(assoc % 0 (list 'aguafria.zig/deref (first %)))))
            (call-inputs declarations operands))
          comptime-storage? (and (#{'field 'index 'slice 'slice-sentinel 'deref} (:name syntax))
                                 (some? (comptime-expression receiver)))
          {:keys [context expression expression-arguments writer]}
          (call-expression-plan {:aguafria/syntax syntax}
                                (assoc inputs :comptime-storage? comptime-storage?) handler-plan)
          expression (if (and native-field? (not comptime-storage?))
                       (list '(field __aguafria_jvm :lookupField)
                             (first expression-arguments) (name member))
                       expression)
          result (invoke-expression! context expression parameters arguments
                                     (or (syntax-result-writer syntax parameters handler-plan)
                                         (cond
                                           (and (= 'field (:name syntax)) (value/zig-type? receiver))
                                           ['declarationFieldResult (first expression-arguments)
                                            (native-member-name member)]
                                           :else writer)))]
      (cond
        (:result-type handler-plan)
        (value/native-value {:kind :const :type (:result-type handler-plan)}
                            (constantly {:representation :scalar :value result}))

        (and (= 'field (:name syntax)) (= {:aguafria.jvm/bound-method true} result))
        (bound-method receiver member)

        :else (value/retain-owners! result [receiver])))))

(defn- signature-arguments [signature-or-reference]
  (let [source-signature? (or (string? signature-or-reference)
                              (re-find #"\bfn\s" (or (:signature signature-or-reference) "")))]
    (mapv (fn [parameter]
            (cond-> parameter
              ;; A source literal receives its dependent parameter type from
              ;; Zig, just as it does for builtin signatures. Reflected alias
              ;; parameter identities already denote concrete types.
              (and source-signature? (seq? (:type parameter)))
              (assoc-in [:properties :jvm/literal?] true)
              ;; Preserve the sentinel when passing a source string as a C string.
              (= [:sentinel-const :u8 0] (:type parameter))
              (assoc-in [:properties :jvm/literal?] true)))
          (:args (if (map? signature-or-reference)
                   (signature/callable-declaration signature-or-reference)
                   (signature/declaration signature-or-reference))))))

(defn- reference-source [reference]
  (when-let [source (let [metadata (some-> (:symbol reference) find-var meta)]
                      (or (:zig/source-file metadata) (:zig/source metadata)))]
    (let [file (io/file source)
          file (cond
                 (.isAbsolute file) file
                 (= :std (:kind reference))
                 (io/file (.getParentFile (io/file (:executable (runtime/toolchain-information))))
                          "lib" source))]
      (when (and file (.isFile file)) (slurp file)))))

(defn- peer-number-type [argument]
  (let [type (cond
               (instance? PreparedOperand argument) (:type argument)
               (value/zig-value? argument) (value/storage-type argument))]
    (cond
      (and (keyword? type)
           (or (#{:comptime_int :usize :isize :f16 :f32 :f64 :f80 :f128} type)
               (re-matches #"[iu][0-9]+" (name type)))) type
      (and (nil? type) (or (integer? argument) (char? argument))) :comptime_int
      (and (nil? type) (or (instance? Double argument) (instance? Float argument))
           (Double/isFinite (double argument))) :comptime_float)))

(defonce ^:private peer-type-proofs (atom {}))

(defn- compiler-metadata-proof-key [profile source]
  (runtime/adapter-fingerprint
   [:compiler-metadata-proof-v1
    (dissoc (runtime/toolchain-information) :materialized? :executable)
    (artifact/compiler-options-identity
     (select-keys (runtime/configuration)
                  [:optimize :target :cpu :zig-args :zig-args-by-module
                   :modules :module-dependencies :module-zig-args :module-cache-tokens]))
    profile source]))

(defn- confirmed-peer-type [types]
     ;; Compile a type check without loading a library or running a function.
     ;; Zero stands for the literal's type; the call still checks its real value.
  (let [module (symbol (str "aguafria.jvm.peer-type-" (token types)))
        context (or (find-ns module) (create-ns module))
        expected (first (remove #{:comptime_int :comptime_float} types))
        expression (apply list 'aguafria.keyword/TypeOf
                          (map #(case %
                                  :comptime_int 0
                                  :comptime_float 0.0
                                  (list 'aguafria.keyword/as 'aguafria.keyword/undefined %)) types))
        source (str "comptime { if (" (emitter/emit-expr context expression)
                    " != " (emitter/emit-type context expected)
                    ") @compileError(\"JVM operand changes the common Zig type\"); }")
        key (compiler-metadata-proof-key [:peer-number-type-v1 types expected] source)
        file (io/file (:cache-dir (runtime/configuration)) "peer-proofs" (str key ".edn"))]
    (locking peer-type-proofs
      (or (get @peer-type-proofs key)
          (let [stored (when (.isFile file)
                         (try (edn/read-string (slurp file)) (catch Exception _ nil)))
                proof
                (if (and (= key (:key stored)) (= :zig-compiler (:basis stored))
                         (= types (:types stored)) (= expected (:expected stored))
                         (= source (:source stored)) (zero? (or (:exit stored) -1)))
                  stored
                  (do
                    (binding [runtime/*source-only-registration?* true]
                      (register! context {:kind :const :name 'CommonType :value expression
                                          :declaration-key [:const 'CommonType] :jvm-adapter? false}))
                    (let [result (runtime/inspect-module! module (fn [_] {:source source}))]
                      (when-not (zero? (:exit result))
                        (throw (ex-info "Zig could not confirm the common argument type" result)))
                      (let [proof (merge {:key key :basis :zig-compiler :types types
                                          :expected expected :source source}
                                         (select-keys result [:exit :command :source-path]))]
                        (io/make-parents file)
                        (spit file (artifact/print-data proof))
                        proof))))]
            (swap! peer-type-proofs #(assoc (if (< (count %) 256) % {}) key (:expected proof)))
            (:expected proof))))))

(defn- reference-arguments [reference declarations arguments]
  (let [types (mapv peer-number-type arguments)
        native-types (disj (set types) :comptime_int :comptime_float)
        native-type (first native-types)
        floating? (contains? #{:f16 :f32 :f64 :f80 :f128} native-type)
        literal-type (if floating? :comptime_float :comptime_int)]
    (if (and (= 1 (count native-types)) (not (contains? native-types nil))
             (some #{literal-type} types)
             (every? #{native-type literal-type} types)
             (every? #(and (#{:anytype 'anytype} (:type %))
                           (not (get-in % [:properties :jvm/literal?]))
                           (not= "comptime" (get-in % [:properties :zig/prefix]))) declarations)
             (when-let [source (and (re-find #"\bfn\s" (or (:signature reference) ""))
                                    (reference-source reference))]
               (peer-call/peer-wrapper-source? source
                                               (:zig-name (signature/declaration (:signature reference)))))
             (= native-type (confirmed-peer-type types)))
      (mapv #(cond-> (assoc % :type native-type)
               (not floating?) (assoc-in [:properties :jvm/peer-integer?] true)
               floating? (assoc-in [:properties :jvm/peer-float?] true)) declarations)
      declarations)))

(defn call-parameters
  "Read native parameter declarations from a registered declaration or the
  Zig-parsed imported/builtin signature, in Aguafria call order."
  ([var-meta] (call-parameters var-meta nil))
  ([var-meta argument-count]
   (let [{token-syntax :aguafria/token
          value-syntax :aguafria/syntax
          reference :aguafria/zig-reference
          declaration :aguafria/declaration} var-meta
         syntax (or token-syntax value-syntax)
         parameters (cond
                      (= 'type (:name syntax)) [{:name 'T :type :type}]
                      (and (= :const (:kind declaration)) (symbol? (:value declaration)))
                      (when-let [target (ns-resolve (the-ns (symbol (:module declaration)))
                                                    (:value declaration))]
                        (mapv #(update % :type (partial qualify-native-type (:ns (meta target))))
                              (call-parameters (meta target) argument-count)))
                      declaration (:args declaration)
                      (or (re-find #"\bfn\s" (or (:signature reference) ""))
                          (= :global-const (:category reference)))
                      (signature-arguments reference)
                      (and (:signature syntax) (not (re-find #"\.\.\." (:signature syntax))))
                      (signature-arguments
                       (str/replace-first (:signature syntax) #"^@[A-Za-z0-9_]+" "fn builtin")))
         parameters (if (= "@as" (:zig-name syntax))
                      (vec (reverse parameters))
                      parameters)]
     (if (and parameters argument-count)
       (call-declarations parameters argument-count)
       parameters))))

(defn- call-result-expression [expression declaration expression-arguments reference]
  (let [inferred-error-return? (or (emitter/inferred-error-payload (:return declaration))
                                   (= "!" (str/trim (or (:zig-qualifiers declaration) ""))))
        index (when-not inferred-error-return?
                (first (keep-indexed
                        (fn [index parameter]
                          (when (and (= (:name parameter) (:return declaration))
                                     (#{:type 'type} (:type parameter)))
                            index)) (:args declaration))))
        type-form (when index (nth expression-arguments index))
        parameter-names (set (map :name (:args declaration)))
        stable-return? (and (:return declaration)
                            (not (#{:type :void :noreturn :anytype} (:return declaration)))
                            (not (emitter/inferred-error-payload (:return declaration)))
                            (not-any? parameter-names
                                      (tree-seq coll? seq (:return declaration))))
        result-type (cond
                      index
                      (if (and (seq? type-form)
                               (#{'type 'aguafria.zig/type} (first type-form)))
                        (second type-form) type-form)

                      stable-return?
                      (result-reader-type
                       {:module (or (:module declaration) (namespace (:symbol reference)))
                        :name (or (:name declaration) (symbol (name (:symbol reference))))
                        :reference reference
                        :return (:return declaration)}))]
    (if result-type
      (let [result '__aguafria_call_result]
        ;; Zig checks the declared return identity before the JVM retains it.
        (with-meta
          (list 'let [result expression]
                (list 'aguafria.keyword/comptime
                      (list 'when-not
                            (list 'aguafria.keyword/==
                                  (list 'aguafria.keyword/TypeOf result)
                                  (list 'aguafria.zig/type result-type))
                            (list 'aguafria.keyword/compileError
                                  "Native result does not match its declared type argument")))
                result)
          {:aguafria/jvm-result-type result-type}))
      expression)))

(defn- comptime-function-body? [{:keys [body module]}]
  (let [form (first body)
        operator (when (seq? form) (first form))]
    (and (= 1 (count body))
         (or (= 'comptime operator)
             (= "comptime" (:zig-token
                            (keyword/resolve-token (the-ns (symbol module)) operator)))))))

(defn- call-expression-plan
  "Build the callable adapter shared by invocation and compile-only preparation."
  ([metadata inputs] (call-expression-plan metadata inputs nil))
  ([{token-syntax :aguafria/token
     value-syntax :aguafria/syntax
     reference :aguafria/zig-reference
     declaration :aguafria/declaration}
    {:keys [parameters expression-arguments comptime-storage?]} handler-plan]
   (let [syntax (or token-syntax value-syntax)
         imported? (and reference (nil? declaration))
         module (cond
                  declaration (symbol (:module declaration))
                  imported? (symbol (str "aguafria.jvm.imported-" (token reference)))
                  :else (symbol (str "aguafria.jvm.expression-" (token syntax))))
         receiver (first expression-arguments)
         receiver-type (if (and (seq? receiver) (= 2 (count receiver))
                                (#{'type 'aguafria.zig/type} (first receiver)))
                         (second receiver)
                         receiver)
         context (or (when (= 'field (:name syntax))
                       (:context (member-owner receiver-type)))
                     (find-ns module) (create-ns module))
         expression-arguments (if (:float-literals? handler-plan)
                                (mapv #(list '(field __aguafria_jvm :parseComptimeFloat) %)
                                      expression-arguments)
                                expression-arguments)
         function (cond
                    declaration (symbol (:module declaration) (str (:name declaration)))
                    imported? (:symbol reference)
                    :else (:symbol syntax))
         expression (cond-> (apply list function expression-arguments)
                      imported? (with-meta {:aguafria/zig-reference reference}))
         expression (if (or declaration imported?)
                      (call-result-expression expression
                                              (or declaration (signature/callable-declaration reference))
                                              expression-arguments
                                              (when imported? reference))
                      expression)
         expression (if (and declaration (comptime-function-body? declaration))
                      (with-meta (list 'aguafria.keyword/comptime expression)
                        (meta expression))
                      expression)
         expression (if (and comptime-storage? (= 'field (:name syntax)))
                      (comptime-field-expression (first expression-arguments)
                                                 (second expression-arguments))
                      expression)]
     {:context context :expression expression :parameters parameters
      :expression-arguments expression-arguments
      :writer (or (syntax-result-writer syntax parameters handler-plan)
                  (when (and comptime-storage? (empty? parameters))
                    'comptimeExpressionResult)
                  (if (= 'field (:name syntax)) 'fieldResult 'result))})))

(defn invoke-generic!
  "Specialize a registered generic call with the actual JVM arguments."
  [declaration arguments]
  (let [{:keys [arguments] :as inputs} (call-inputs (:args declaration) arguments)
        {:keys [context expression parameters writer]}
        (call-expression-plan {:aguafria/declaration declaration} inputs)]
    (invoke-expression! context expression parameters arguments writer)))

(defn invoke-reference!
  "Execute an imported Zig function, with Zig specializing its actual inputs."
  [reference arguments]
  (call-with-output
   (fn []
     (cond
       (:field-accessor? reference)
       (do
         (when-not (= 1 (count arguments))
           (throw (ex-info "A field accessor requires exactly one receiver"
                           {:function (:symbol reference) :actual (count arguments)})))
         (invoke-syntax! (:aguafria/syntax (meta (find-var 'aguafria.zig/field)))
                         [(first arguments) (keyword (:member-name reference))]))

       (:receiver-method? reference)
       (let [[receiver & operands] arguments]
         (when-not (value/zig-value? receiver)
           (throw (ex-info "A container method requires a native receiver"
                           {:function (:symbol reference) :receiver receiver})))
         (apply (bound-method receiver (keyword (:member-name reference))) operands))
       :else
       (let [declarations (reference-arguments reference
                                               (signature-arguments reference) arguments)
             {:keys [arguments] :as inputs}
             (call-inputs declarations arguments)
             {:keys [context expression parameters writer]}
             (call-expression-plan {:aguafria/zig-reference reference} inputs)]
         (invoke-expression! context expression parameters arguments writer))))))

(defn- slice-signature-plan [kind receiver address indices]
  (let [ordinary-array? (and (vector? receiver) (= :array (first receiver)) (= 3 (count receiver)))
        operands (mapv #(prepared-operand % {:type :usize}) indices)
        runtime-index? (some runtime-index-operand?
                             (if (= :slice-sentinel kind) (butlast operands) operands))
        mutable? (= :* (first address))]
    (if (and (= :slice kind) ordinary-array? runtime-index?)
      (assoc (runtime-array-plan
              :runtimeArraySlice
              (into [(->PreparedOperand [(if mutable? :many :many-const) (nth receiver 2)])
                     (->PreparedOperand :usize)]
                    (if (= 1 (count operands))
                      (conj operands (->PreparedOperand :usize)) operands)))
             :writer 'borrowedResult)
      (let [syntax {:kind :syntax :name (symbol (name kind))
                    :symbol (symbol "aguafria.zig" (name kind))}
            operands (into [(->PreparedOperand address)] operands)
            {:keys [parameters expression-arguments]}
            (call-inputs (syntax-call-declarations syntax (count operands) nil runtime-index?)
                         operands)]
        {:module (symbol (str "aguafria.jvm.expression-" (token syntax)))
         :expression (apply list (:symbol syntax)
                            (list 'aguafria.zig/deref (first expression-arguments))
                            (rest expression-arguments))
         :parameters parameters :writer 'result}))))

(defn- precompile-operand-readers! [operands]
  (doseq [operand (tree-seq coll? seq operands)]
    (cond
      (instance? PreparedExpression operand)
      (doseq [reference (distinct (filter qualified-symbol?
                                          (tree-seq coll? seq (:expression operand))))
              :let [declaration (:aguafria/declaration (meta (find-var reference)))]
              :when (= :const (:kind declaration))]
        (precompile-expression! (constant-reader-plan declaration)))

      (instance? PreparedOperand operand)
      (let [type (:type operand)]
        (precompile-operand-storage! type)
        (when (and (vector? type) (= :error-union (first type)))
          (precompile-conversion! (last type) type)
          (let [errors (second type)]
            (when (and (vector? errors) (= :error-set (first errors)))
              (doseq [error (second errors)]
                (precompile-named-error-coercion!
                 type (value/->ZigError (name error) errors))))))
        (precompile-inspection! *ns* type)
        (when (seq? type)
          (precompile-expression! (layout-adapter type))))

      (and (instance? PreparedType operand) (qualified-symbol? (:schema operand)))
      (let [type (:schema operand)
            declaration (:aguafria/declaration (meta (find-var type)))]
        (cond
          (= :const (:kind declaration))
          ;; Ordinary JVM field access first resolves a lazy type constant.
          ;; Prepare that same reader without loading or evaluating it.
          (precompile-expression! (constant-reader-plan declaration))

          (:aguafria/zig-reference (meta (find-var type)))
          (let [syntax (:aguafria/syntax (meta (find-var 'aguafria.zig/type)))
                module (symbol (str "aguafria.jvm.expression-" (token syntax)))
                context (or (find-ns module) (create-ns module))
                reference (:aguafria/zig-reference (meta (find-var type)))
                expression (list 'aguafria.zig/type
                                 (with-meta type {:aguafria/zig-reference reference}))]
            (precompile-expression! (prepare-expression! context expression [] 'result))))))))

(defn precompile-storage!
  "Prepare borrowed field/index views from compiler-confirmed receiver and
  address types. Uses the normal view helpers without allocating a receiver."
  [{:keys [kind receiver address reference member indices] :as signature}]
  (let [member (if (symbol? member) (keyword (name member)) member)]
    (when (#{:index :slice :slice-sentinel} kind)
      (precompile-operand-readers! (mapv #(prepared-operand % nil) indices)))
    (cond
      (and (= :address kind) reference)
      (let [syntax (:aguafria/token (meta (find-var 'aguafria.keyword/&)))
            module (symbol (str "aguafria.jvm.expression-" (token syntax)))
            context (or (find-ns module) (create-ns module))
            {:keys [expression-arguments parameters]}
            (call-inputs [{:type :anytype :properties {:jvm/literal? true}}]
                         [(var-get (find-var reference))])
            expression (apply list 'aguafria.keyword/& expression-arguments)
            adapter (prepare-expression! context expression parameters 'result)]
        (precompile-expression! adapter)
        (precompile-inspection! (:context adapter)
                                (list 'aguafria.keyword/TypeOf expression))
        (assoc signature :status :prepared))

      (= :address kind)
      (do (precompile-coercion! receiver)
          (assoc signature :status :prepared))

      (and (= :field kind) (:comptime-type receiver))
      (let [type (:comptime-type receiver)
            type-value (when (qualified-symbol? type) (some-> (find-var type) var-get))
            variable (container-variable type-value member)]
        (precompile-operand-readers! [(prepared-operand receiver nil)])
        (if variable
          (let [owner (:module (value/type-info type-value))
                field (list 'aguafria.zig/field (list 'type type) member)
                field-type (or (:type variable) (list 'aguafria.keyword/TypeOf field))]
            (runtime/precompile-function!
             (prepare-variable-storage! owner field field-type true)))
          (let [syntax {:kind :syntax :name 'field :symbol 'aguafria.zig/field}
                {:keys [context expression]}
                (call-expression-plan
                 {:aguafria/syntax syntax}
                 {:parameters [] :expression-arguments [(type-expression type) member]})]
            (precompile-expression!
             (prepare-expression! context expression []
                                  ['declarationFieldResult (type-expression type)
                                   (native-member-name member)]))))
        (assoc signature :status :prepared))

      (and (#{:field :index :slice :slice-sentinel :deref} kind) (:comptime-expression receiver))
      (let [operands (into [(prepared-operand receiver nil)]
                           (if (= :field kind) [member]
                               (mapv #(prepared-operand % nil) indices)))
            syntax {:kind :syntax :name (symbol (name kind))
                    :symbol (symbol "aguafria.zig" (name kind))}
            runtime-slice? (and (#{:slice :slice-sentinel} kind)
                                (some runtime-index-operand?
                                      (if (= :slice-sentinel kind)
                                        (butlast (rest operands)) (rest operands))))
            declarations (syntax-call-declarations syntax (count operands) nil runtime-slice?)
            inputs (assoc (call-inputs declarations operands) :comptime-storage? true)
            {:keys [context expression parameters writer]}
            (call-expression-plan {:aguafria/syntax syntax} inputs)]
        (precompile-operand-readers! operands)
        (precompile-expression! (prepare-expression! context expression parameters writer))
        (assoc signature :status :prepared))

      :else
      (let [ordinary-array? (and (vector? receiver) (= :array (first receiver))
                                 (= 3 (count receiver)))
            mutable? (and (sequential? address) (= :* (first address)))
            {:keys [module expression types parameters writer]}
            (case kind
              :deref
              {:module (symbol (str "aguafria.jvm.pointee-" (token receiver)))
               :expression (cond-> '((field __aguafria_jvm :dereferenceView) input_0)
                             (and (vector? receiver) (#{:* :*const :pointer} (first receiver)))
                             (with-meta {:aguafria/jvm-borrowed-type (peek receiver)}))
               :types [receiver]}

              (:slice :slice-sentinel) (slice-signature-plan kind receiver address indices)
              :field
              (field-view-plan receiver member address)

              :index
              (if ordinary-array?
                (runtime-array-plan :arrayIndexView
                                    [(->PreparedOperand [(if mutable? :many :many-const) (nth receiver 2)])
                                     (->PreparedOperand :usize)
                                     (prepared-operand (first indices) {:type :usize})])
                (let [index (first indices)
                      comptime? (and (map? index) (contains? index :comptime))]
                  (index-plan receiver (->PreparedOperand address)
                              (prepared-operand index {:type (if comptime? :comptime_int :usize)})
                              comptime?))))
            context (or (find-ns module) (create-ns module))
            parameters (or parameters
                           (mapv (fn [index type]
                                   {:name (symbol (str "input_" index)) :type type})
                                 (range) types))
            adapter (prepare-expression! context expression parameters (or writer 'borrowedResult))]
        (when (and (= :index kind) (index-reflection? receiver))
          (precompile-expression!
           (prepare-expression! context
                                (list '(field __aguafria_jvm :indexRequiresComptime)
                                      (list 'type receiver))
                                [] 'inspectResult)))
        (precompile-expression! adapter)
        ;; A borrowed pointee is decoded separately from the view envelope.
        ;; Use the child in Zig's observed pointer schema, not a new type guess.
        (when (and (= :deref kind) (vector? receiver) (#{:* :*const} (first receiver)))
          (precompile-inspection! context (peek receiver)))
        (assoc signature :status :prepared)))))

(defn- canonical-comptime-source-operands
  "Match ordinary type-value lifting using explicit native parameter contracts.
  Source observation retains authored type syntax; call-inputs carries the same
  structural schema as a type expression. Normalize that spelling, not its type."
  [expression]
  (letfn [(visit [form]
            (cond
              (seq? form)
              (if (= 'quote (first form))
                form
                (let [forms (mapv visit form)
                      head (first forms)
                      arguments (subvec forms 1)
                      ;; An explicit type expression is already canonical.
                      parameters (when (and (qualified-symbol? head)
                                            (not= 'aguafria.zig/type head))
                                   (some-> (find-var head) meta
                                           (call-parameters (count arguments))))
                      arguments
                      (map-indexed
                       (fn [index argument]
                         (if (and (contains? #{:type 'type} (:type (nth parameters index nil)))
                                  (not (and (seq? argument)
                                            (contains? #{'type 'aguafria.zig/type} (first argument)))))
                           (type-expression argument)
                           argument)) arguments)]
                  (with-meta (apply list head arguments) (meta form))))

              (vector? form) (with-meta (mapv visit form) (meta form))
              (map? form) (with-meta (into (empty form)
                                           (map (fn [[key value]] [(visit key) (visit value)])) form)
                            (meta form))
              :else form))]
    (visit expression)))

(defn- prepared-operand [argument declaration]
  (cond
    (= :null argument) nil
    (= :undefined argument) 'aguafria.keyword/undefined
    (and (map? argument) (contains? argument :tuple))
    (mapv #(prepared-operand % nil) (:tuple argument))
    (and (map? argument) (contains? argument :map))
    (into {} (map (fn [[key item]] [key (prepared-operand item nil)])) (:map argument))
    (and (map? argument) (contains? argument :contextual-call))
    (let [[function arguments] (:contextual-call argument)
          syntax (:aguafria/token (meta (find-var function)))]
      (when-not (keyword/result-context-required? (:zig-name syntax))
        (throw (ex-info "Expected a result-context builtin" {:function function})))
      (->ContextualCall syntax (mapv #(prepared-operand % nil) arguments)))
    (and (map? argument) (contains? argument :contextual-address))
    (->ContextualAddress (prepared-operand (:contextual-address argument) nil))
    (and (map? argument) (contains? argument :comptime-type))
    (->PreparedType (:comptime-type argument))
    (and (map? argument) (contains? argument :comptime-expression))
    (let [expression (:comptime-expression argument)]
      (if (qualified-symbol? expression)
        (let [reference (find-var expression)
              declaration (:aguafria/declaration (meta reference))]
          (if (= :const (:kind declaration))
            (->PreparedExpression expression)
            (let [function (var-get reference)]
              (when-not (:aguafria/zig-reference (meta function))
                (throw (ex-info "Prepared function requires a native declaration"
                                {:function expression})))
              function)))
        (->PreparedExpression
         (canonical-comptime-source-operands (emitter/qualify-form *ns* expression)))))
    (and (map? argument) (contains? argument :literal)) (:literal argument)
    (and (map? argument) (contains? argument :comptime))
    (if (#{:type 'type} (:type declaration))
      (->PreparedType (:comptime argument))
      (:comptime argument))
    :else
    (let [type (constructor-type argument)]
      (emitter/emit-type type)
      (->PreparedOperand type))))

(defn- prepared-call-inputs
  ([arguments declarations] (prepared-call-inputs arguments declarations nil))
  ([arguments declarations reference]
   (let [declarations (mapv (fn [argument declaration]
                              (cond-> (or declaration {:type :anytype})
                                (and (map? argument)
                                     (or (contains? argument :comptime) (contains? argument :comptime-type)))
                                (assoc-in [:properties :jvm/literal?] true)))
                            arguments (concat declarations (repeat nil)))
         operands (mapv prepared-operand arguments declarations)
         declarations (if reference (reference-arguments reference declarations operands) declarations)]
     ;; Use the actual call planner, including contextual primitive parameters,
     ;; comptime arguments and lossless unsigned/integer carriers. These are
     ;; type-only placeholders, never allocated or passed to native code.
     (cond-> (assoc (select-keys (call-inputs declarations operands) [:parameters :expression-arguments])
                    :operands operands)
       (some #(get-in % [:properties :jvm/peer-float?]) declarations)
       (assoc :inspection-types
              (into #{} (keep #(when (instance? PreparedOperand %) (:type %))) operands))))))

(defn- precompile-inputs
  ([arguments declarations] (precompile-inputs arguments declarations nil))
  ([arguments declarations reference]
   (let [inputs (prepared-call-inputs arguments declarations reference)]
     (precompile-operand-readers! (:operands inputs))
     (dissoc inputs :operands))))

(defn call-result-identity
  "Plan the native result type of a compiler-observed function call. Uses the
  ordinary call planner without compiling readers, allocating or invoking it."
  [function arguments]
  (let [metadata (meta (find-var function))
        reference (when-not (:aguafria/declaration metadata)
                    (:aguafria/zig-reference metadata))
        inputs (prepared-call-inputs arguments (call-parameters metadata (count arguments))
                                     reference)
        {:keys [context expression parameters]} (call-expression-plan metadata inputs)]
    (list 'aguafria.keyword/TypeOf
          (expression-without-runtime-inputs context expression parameters))))

(defn precompile-construction!
  "Prepare typed construction from compiler-observed JVM input representations,
  without allocating values or executing initializer expressions."
  [schema argument]
  (let [type (constructor-type schema)
        inputs (precompile-inputs [argument] [{:type :anytype :properties {:jvm/literal? true}}])
        adapter (when (empty? (:parameters inputs))
                  (literal-construction-plan type (first (:expression-arguments inputs))))
        function (when-not adapter (prepare-construction! type inputs))]
    (if adapter
      (precompile-expression! adapter)
      (runtime/precompile-function! function))
    (precompile-inspection! (or (:context adapter) (the-ns (symbol (namespace function)))) type)
    (precompile-coercion! type)
    {:type schema :status :prepared}))

(defn precompile-contextual-conversion!
  "Prepare a deferred builtin expression in its compiler-observed result type.
  The plan contains call syntax and leaf indexes, never inferred operand types."
  [{:keys [type plan args] :as signature}]
  (letfn [(operand [{:keys [function arguments input literal] :as part}]
            (cond
              function
              (let [syntax (:aguafria/token (meta (find-var function)))]
                (when-not (keyword/result-context-required? (:zig-name syntax))
                  (throw (ex-info "Expected a result-context builtin"
                                  {:function function})))
                (->ContextualCall syntax (mapv operand arguments)))

              (contains? part :literal) literal

              :else
              (prepared-operand (nth args input) nil)))]
    (let [type (constructor-type type)
          inputs (call-inputs [{:type :anytype}] [(operand plan)])
          function (prepare-construction! type inputs)]
      (runtime/precompile-function! function)
      (precompile-inspection! (the-ns (symbol (namespace function))) type)
      (precompile-coercion! type)
      (assoc signature :status :prepared))))

(defn- precompile-contextual-address! [target initializer]
  (let [operand (prepared-operand initializer nil)
        backing (contextual-address-backing-type target (count operand))]
    (precompile-construction! backing initializer)
    (precompile-conversion! [:*const backing] target)))

(defn precompile-concrete-call!
  "Prepare the function and deferred arguments using compiler-observed parameter types."
  ([function arguments] (precompile-concrete-call! function arguments nil))
  ([function arguments sources]
   (let [result (runtime/precompile-function! function)
         declaration (runtime/jvm-callable-argument-declaration
                      (:aguafria/declaration (meta (find-var function))))
         module (namespace function)]
     (doseq [[index source] (map-indexed vector sources)
             :when source]
       (precompile-type-equivalence!
        module (argument-reader-type declaration index)
        (list 'aguafria.keyword/TypeOf source))
       (when (contains? #{:fn :fn-proto} (:kind (:declaration (declared-member-reference source))))
         (precompile-literal-coercion!
          (qualify-native-type (the-ns (symbol module))
                               (argument-reader-type declaration index))
          source)))
     (doseq [[index argument] (map-indexed vector arguments)]
       (if-let [[target operand] (:contextual-argument argument)]
         (precompile-construction! target operand)
         (let [operand (prepared-operand argument nil)]
           (when (or (instance? PreparedOperand operand)
                     (instance? ContextualAddress operand))
             (let [target (qualify-native-type (the-ns (symbol module))
                                               (argument-reader-type declaration index))]
               (if (instance? ContextualAddress operand)
                 (precompile-contextual-address! target (:contextual-address argument))
                 (when-not (= (:type operand) (constructor-type target))
                   (precompile-conversion! (:type operand) target))))))))
     result)))

(defn precompile-method!
  "Prepare a member call from compiler-observed receiver/argument types.
  Shares the normal JVM method planner; never constructs or invokes a receiver."
  [{:keys [receiver address member args result-reader-types] :as signature}]
  (let [member (if (symbol? member) (keyword (name member)) member)
        native? (not (and (map? receiver)
                          (or (contains? receiver :comptime-type)
                              (contains? receiver :comptime-expression))))
        receiver-type (emitter/qualify-type
                       *ns* (cond
                              (:comptime-expression receiver)
                              (list 'aguafria.keyword/TypeOf (:comptime-expression receiver))
                              native? receiver
                              :else (:comptime-type receiver)))
        mutable? (and native? (= :* (first address)))
        operands (if native? args (into [receiver] args))
        inputs (precompile-inputs operands
                                  (repeat (count operands)
                                          {:type :anytype :properties {:jvm/literal? true}}))
        {:keys [context expression parameters writer]}
        (method-plan receiver-type member mutable? native? inputs)]
    (when-not native?
      (precompile-storage! {:kind :field :receiver receiver :member member}))
    (precompile-expression! (prepare-expression! context expression parameters writer))
    (let [runtime-type (list 'aguafria.keyword/TypeOf
                             (expression-without-runtime-inputs context expression parameters))]
      (doseq [observed-type (distinct result-reader-types)
              :when (not (structural-schema? observed-type))]
        (precompile-type-equivalence! (str (ns-name context)) observed-type runtime-type)
        (precompile-inspection! context runtime-type)))
    (assoc signature :status :prepared)))

(defn precompile-scoped!
  "Prepare the ordinary scoped adapter from compiler-observed capture schemas.
  Mutable captures keep their native storage addresses; no body is executed."
  [{:keys [caller form captures types result? result-type] :as signature}]
  (when-not (= (* 2 (count captures)) (count types))
    (throw (ex-info "Scoped capture signature does not match its bindings"
                    {:captures captures :types types})))
  (let [entries (mapv (fn [name [type address]]
                        (let [options (when (and (vector? address) (map? (second address)))
                                        (second address))
                              mutable? (and (vector? address) (= :* (first address))
                                            (not (:const? options)))
                              comptime-type (when (and (map? type) (:comptime-capture type))
                                              (first (:comptime-capture type)))]
                          (when (and comptime-type
                                     (or mutable? (not= comptime-type (peek address))))
                            (throw (ex-info "Scoped comptime capture disagrees with its native address"
                                            {:capture name :type type :address address
                                             :reason :invalid-comptime-capture-contract})))
                          {:name name
                           :type (constructor-type (or comptime-type (if mutable? (peek address) type)))
                           :comptime? (some? comptime-type)
                           :mutable? mutable?}))
                      captures (partition 2 types))]
    ;; A compiler-observed address identifies the storage type. Comptime-only
    ;; storage cannot be captured by an adapter that runs outside that scope.
    (if (some #(and (:mutable? %)
                    (contains? #{:comptime_int :comptime_float :type :null :undefined}
                               (:type %))) entries)
      (assoc signature :status :unsupported :reason :comptime-only-mutation)
      (let [inputs (prepared-call-inputs
                    (mapv (fn [entry [type _]]
                            (cond
                              (:comptime? entry)
                              {:comptime-expression
                               (scoped-comptime-source (:type entry) (second (:comptime-capture type)))}
                              (:mutable? entry) :usize
                              :else type))
                          entries (partition 2 types))
                    (scoped-argument-declarations entries))
            {:keys [context expression parameters function inspection-type transport-plan]}
            (prepare-scoped-plan! caller form entries inputs result?
                                  (some-> result-type constructor-type))]
        (precompile-operand-readers! (:operands inputs))
        (if result?
          (precompile-expression! transport-plan)
          (runtime/precompile-function! function))
        (when inspection-type
          (precompile-inspection! context inspection-type))
        (when result?
          (let [runtime-type (or inspection-type
                                 (list 'aguafria.keyword/TypeOf
                                       (expression-without-runtime-inputs context expression parameters)))]
            ;; Ordinary native result envelopes retain this exact reflected
            ;; type. Decoding a nominal payload must not depend on a later
            ;; body or accessor incidentally preparing its value reader.
            (when-not inspection-type
              (precompile-inspection! context runtime-type))
            (precompile-tuple-sequence! context runtime-type)))
        (assoc signature :status :prepared)))))

(defn precompile-assignment!
  "Compile the ordinary assignment adapter using types observed by Zig, without
  making storage or performing the write."
  [{:keys [function operation target operand] :as signature}]
  (cond
    (contains? #{:comptime_int :comptime_float} (if (map? target) (:type target) target))
    (assoc signature :status :unsupported :reason :comptime-only-mutation)

    (= "=" operation)
    (let [preparation
          (cond
            (and (map? operand) (contains? operand :contextual-argument))
            (let [[context input] (:contextual-argument operand)]
              (when-not (= target context)
                (throw (ex-info "Assignment context differs from its target"
                                {:target target :context context})))
              (precompile-assignment! (assoc signature :operand input)))

            (and (map? operand) (contains? operand :contextual-call))
            (precompile-construction! target operand)

            (= target operand)
            (let [context (inspection-context *ns* target)]
              (precompile-inspection! context target)
              {:status :prepared})

            (and (map? operand) (contains? operand :tuple))
            (let [inputs (precompile-inputs [operand]
                                            [{:type :anytype :properties {:jvm/literal? true}}])
                  function (prepare-construction! target inputs)]
              (runtime/precompile-function! function)
              (precompile-inspection! (the-ns (symbol (namespace function))) target)
              {:status :prepared})

            (not (map? operand)) (precompile-conversion! operand target)
            :else (precompile-coercion! target))]
      (merge signature (select-keys preparation [:status :reason :native-values-only?])))
    :else
    (let [operand-type (when (and (map? operand) (number? (:literal operand)))
                         (handlers/assignment-signature-operand-type target operation))
          inputs
          (precompile-inputs [[:* target] operand]
                             [{:type :anytype}
                              (if operand-type {:type operand-type}
                                  {:type :anytype :properties {:jvm/literal? true}})])
          function (prepare-assignment! function inputs)]
      (runtime/precompile-function! function)
      (assoc signature :status :prepared))))

(defn source-literal-call?
  "Whether this syntax call has self-contained source arguments. This checks
  the syntax contract only; the native compiler validates the literal itself."
  [function arguments]
  (and (= 1 (count arguments))
       (case function
         (aguafria.zig/deref aguafria.zig/number-literal aguafria.zig/string-literal
                             aguafria.zig/char-literal aguafria.zig/enum-literal)
         (string? (first arguments))
         aguafria.zig/error-value
         (or (keyword? (first arguments)) (string? (first arguments)))
         aguafria.zig/multiline-string
         (and (vector? (first arguments)) (seq (first arguments))
              (every? string? (first arguments)))
         false)))

(defn source-concatenation-form?
  "Whether concatenation operands stay in source in the ordinary JVM planner.
  This recognizes call syntax and source strings; Zig evaluates the expression."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "++" (some-> (ns-resolve (or emitter/*keyword-context* *ns*) (first form))
                       meta :aguafria/token :zig-token))
       (every? #(or (string? %) (source-concatenation-form? %)) (rest form))))

(defn precompile-source-literal!
  "Prepare the ordinary syntax adapter for a source literal, without evaluating
  it. No runtime inputs or inferred transport types are needed."
  [function arguments result-type]
  (when-not (source-literal-call? function arguments)
    (throw (ex-info "Expected a self-contained native literal call"
                    {:function function :arguments arguments})))
  (let [syntax {:kind :syntax :name (symbol (name function)) :symbol function}
        module (symbol (str "aguafria.jvm.expression-" (token syntax)))
        context (or (find-ns module) (create-ns module))
        {:keys [parameters expression-arguments]}
        (call-inputs (repeat (count arguments)
                             {:type :anytype :properties {:jvm/literal? true}})
                     arguments)
        expression (apply list function expression-arguments)]
    (precompile-expression! (prepare-expression! context expression parameters 'result))
    (when (#{'aguafria.zig/string-literal 'aguafria.zig/multiline-string} function)
      ;; The compiler observation uses the same structural pointer schema as
      ;; native result transport. An equivalent TypeOf expression would create
      ;; a different decoder identity and miss the persisted cache on restart.
      (when-not result-type
        (throw (ex-info "String literal preparation requires its compiler-observed result type"
                        {:function function :arguments arguments})))
      (precompile-inspection! context result-type))
    {:function function :arguments arguments :status :prepared}))

(defn- precompile-try! [{:keys [function args]}]
  (let [input (first args)
        context (the-ns 'aguafria.zig.jvm)
        type (constructor-type input)
        payload (list 'aguafria.zig/field
                      (list 'aguafria.zig/field
                            (list 'aguafria.keyword/typeInfo (list 'aguafria.zig/type type))
                            :error_union)
                      :payload)]
    (when-not (= 1 (count args))
      (throw (ex-info "k/try expects one error-union operand" {:args args})))
    (precompile-inspection! context type)
    (precompile-inspection! context payload)
    {:function function :args args :status :prepared :adapter :native-error-payload}))

(defn- precompile-call-base!
  "Compile an explicit native call signature without creating operands or making
  the call. Argument entries are native types or {:comptime source-value}.
  Uses the same adapter registration and cache identity as normal JVM calls."
  [{:keys [function args result-reader-types] :as call}]
  (if (= 'aguafria.keyword/try function)
    (precompile-try! call)
    (let [v (find-var function)
          {token-syntax :aguafria/token
           value-syntax :aguafria/syntax
           zig-reference :aguafria/zig-reference
           declaration :aguafria/declaration} (meta v)
          syntax (or token-syntax value-syntax)
          imported? (and zig-reference (nil? declaration))
          value-call? (= :const (:kind declaration))
          generic? (and (contains? #{:fn :fn-proto} (:kind declaration))
                        (some runtime/generic-function-argument? (:args declaration)))
          supported? (or generic? value-call?
                         (and (= 'object (:name syntax))
                              (every? #(and (map? %) (contains? % :comptime)) args))
                         (= 'aguafria.zig/type function)
                         (= 'aguafria.zig/unwrap function)
                         (and (#{'aguafria.zig/field 'aguafria.zig/index} function)
                              (map? (first args))
                              (or (contains? (first args) :tuple)
                                  (and (= 'aguafria.zig/field function)
                                       (contains? (first args) :map))))
                         (and imported?
                              (:signature zig-reference)
                              (not (:field-accessor? zig-reference))
                              (not (:receiver-method? zig-reference)))
                         (and (#{:operator :call} (:kind syntax))
                              (not= "@as" (:zig-name syntax))
                              (not= "&" (:zig-token syntax))))]
      (when-not supported?
        (throw (ex-info "Unsupported call adapter: use :namespaces for concrete functions or :coercions for constructors; storage adapters require additional context"
                        {:call call})))
      (when (and (= :operator (:kind syntax))
                 (some #(and (map? %)
                             (not (contains? #{#{:literal :type} #{:comptime-type} #{:tuple} #{:comptime-expression}}
                                             (set (keys %))))
                             (not (and (= #{:comptime} (set (keys %)))
                                       (or (boolean? (:comptime %))
                                           (string? (:comptime %))
                                           (native-literal? (:comptime %)))))) args))
        (throw (ex-info "Operator signatures require native operand types, not source literals"
                        {:call call})))
      (let [native-parameters (or (:args declaration)
                                  (when imported? (signature-arguments zig-reference)))
            variadic? (boolean (some #(get-in % [:properties :zig/variadic]) native-parameters))
            expected (or (:param-count syntax)
                         (when (and declaration (not value-call?)) (count (:args declaration)))
                         (when imported? (count (signature-arguments zig-reference))))
            minimum (if variadic? (dec (count native-parameters)) (:minimum-param-count syntax))]
        (when (or (and expected (not variadic?) (not= expected (count args)))
                  (and minimum (< (count args) minimum)))
          (throw (ex-info "Wrong number of precompilation arguments"
                          {:call call :expected expected :minimum minimum}))))
      (let [argument-declarations
            (cond
              value-call? (repeat (count args) {:type :anytype :properties {:jvm/literal? true}})
              declaration (:args declaration)
              imported? (signature-arguments zig-reference)
              :else (syntax-call-declarations syntax (count args) nil false))
            argument-declarations (call-declarations argument-declarations (count args))
            args (if (= :operator (:kind syntax))
                   args
                   (mapv (fn [argument parameter]
                           (if (and (map? argument) (contains? argument :literal)
                                    (or (= "comptime" (get-in parameter [:properties :zig/prefix]))
                                        (#{:comptime_int :comptime_float} (:type parameter))))
                             {:comptime (:literal argument)}
                             argument))
                         args (concat argument-declarations (repeat nil))))
            handler-plan (when (= :operator (:kind syntax))
                           (handlers/operator-signature-plan syntax args))
            {:keys [inspection-types] :as inputs}
            (precompile-inputs (or (:types handler-plan) args)
                               (if handler-plan
                                 (mapv #(hash-map :type %) (:types handler-plan))
                                 argument-declarations)
                               (when imported? zig-reference))
            {:keys [context expression parameters writer]}
            (call-expression-plan (meta v) inputs handler-plan)
            adapter (prepare-expression! context expression parameters writer)]
        (precompile-expression! adapter)
        (doseq [type (distinct
                      (keep identity
                            (concat inspection-types result-reader-types
                                    [(:aguafria/jvm-result-type (meta expression))])))]
          (precompile-inspection! context type))
        (when-let [type (:aguafria/jvm-result-type (meta expression))]
          (let [return-type (:return declaration)]
            (when (and (vector? return-type) (= :error-union (first return-type)))
            ;; Result transport reflects a nominal payload through its producer,
            ;; so prepare that exact reader as well as the whole error union.
              (let [payload (list 'aguafria.zig/field
                                  (list 'aguafria.zig/field
                                        (list 'aguafria.keyword/typeInfo type) "error_union")
                                  "payload")
                    declared-payload (last return-type)
                    payload-declaration (when (symbol? declared-payload)
                                          (some-> (ns-resolve context declared-payload)
                                                  meta :aguafria/declaration))]
                (precompile-inspection! context payload)
                (when (= :struct (:kind payload-declaration))
                  (doseq [field (:fields payload-declaration)]
                    (precompile-storage! {:kind :field :receiver payload
                                          :address [:*const payload] :member (:name field)})))))))
        (let [return-type (or (:return declaration)
                              (when imported?
                                (:return (signature/callable-declaration zig-reference))))]
        ;; Native C integer aliases retain their compiler identity (c_int is
        ;; not renamed to a guessed host integer). Their JVM value reader uses
        ;; the existing compiler reflection adapter instead of a scalar codec.
          (when (and (keyword? return-type) (str/starts-with? (name return-type) "c_"))
            (precompile-inspection! context return-type)))
        {:function function :args args :status :prepared}))))

(defonce ^:private type-source-proofs (atom {}))

(defn- closed-type-argument-source [context source]
  (try
    (let [call-heads
          (walk/postwalk
           (fn [form]
             (if (and (seq? form) (symbol? (first form)))
               (let [head (emitter/qualify-form context (first form))]
                 (with-meta (apply list (with-meta head (assoc (meta head) :aguafria/type-source-call-head? true))
                                   (rest form))
                   (meta form)))
               form)) source)
          candidates
          (into #{}
                (filter (fn [form]
                          (and (symbol? form)
                               (or (:aguafria/local? (meta form))
                                   (and (simple-symbol? form)
                                        (not (:aguafria/type-source-call-head? (meta form)))
                                        (let [metadata (some-> (ns-resolve context form) meta)]
                                          (not (or (:aguafria/declaration metadata)
                                                   (:aguafria/zig-reference metadata)))))))))
                (tree-seq coll? seq call-heads))
          captures (emitter/scoped-captures context call-heads candidates)
          _ (when (seq captures)
              (throw (ex-info "Retained type source has detached lexical captures"
                              {:captures captures})))
          source (qualify-native-type context source)]
      ;; A lexical operand without its actual source closure cannot be moved
      ;; into an adapter. Validate names, not types; Zig proves the latter.
      (emitter/validate-declaration-references!
       context {:kind :const :name '__aguafria_type_argument :value source}
       (set (map :name (runtime/registered-declarations (ns-name context)))))
      (constructor-type source))
    (catch clojure.lang.ExceptionInfo _ nil)))

(defn- confirm-type-argument-source! [context expected actual]
  (let [expected (qualify-native-type context expected)
        actual (qualify-native-type context actual)
        key (tuple-profile-key context [:declaration-backed-type-proof expected actual])]
    (locking type-source-proofs
      (if (contains? @type-source-proofs key)
        (:equivalent? (get @type-source-proofs key))
        (let [module (str (ns-name context))
              actual-name '__aguafria_type_source_actual
              expected-name '__aguafria_type_source_expected
              proof-name (symbol (str "__aguafria_type_source_proof_" (token [expected actual])))
              proof
              (emitter/prepare-declaration
               context
               {:module module :kind :comptime :name proof-name
                :declaration-key [:comptime proof-name]
                :body [(list 'let [actual-name (list 'aguafria.zig/type actual)
                                   expected-name (list 'aguafria.zig/type expected)]
                             (list 'raw-statements
                                   (str "@import(\"operation_probe.zig\").Inspector(.{}).log(if ("
                                        (emitter/identifier actual-name) " == "
                                        (emitter/identifier expected-name)
                                        ") \"aguafria.type-source:0:true\" else \"aguafria.type-source:0:false\");")))]})
              result
              (runtime/inspect-module!
               module
               (fn [declarations]
                 (let [declarations
                       (vals (into (array-map) (map (juxt :declaration-key identity))
                                   (concat declarations
                                           (filter :jvm-adapter?
                                                   (runtime/registered-declarations module))
                                           [proof])))]
                   {:declarations declarations
                    :source (emitter/emit-module module declarations)
                    :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                            "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}})))
              observations
              (keep (fn [[_ encoded]]
                      (let [message (String. (.parseHex (java.util.HexFormat/of) encoded)
                                             java.nio.charset.StandardCharsets/UTF_8)]
                        (when (str/starts-with? message "aguafria.type-source:0:")
                          (edn/read-string (subs message (count "aguafria.type-source:0:"))))))
                    (re-seq #"\"aguafria\.operation\.hex:([0-9a-f]+)\"" (or (:err result) "")))]
          (when (or (not= 1 (:exit result))
                    (re-find #"(?m)error: (?!found compile log statement)" (or (:err result) ""))
                    (not= 1 (count observations))
                    (not (boolean? (first observations))))
            (throw (ex-info "Zig could not confirm the retained type argument source"
                            (select-keys result [:exit :err :command :source-path]))))
          (let [proof-result (merge (select-keys result [:command :source-path])
                                    {:basis :zig-compiler :equivalent? (first observations)
                                     :expected expected :actual actual})]
            (swap! type-source-proofs #(assoc (if (< (count %) 256) % {}) key proof-result))
            (:equivalent? proof-result)))))))

(defn precompile-call!
  "Prepare the ordinary call and compiler-confirmed source spellings of its
  type-valued arguments. Nominal observation and retained source both keep
  their identities; no display-name substitution or body evaluation is used."
  [{:keys [args source-arguments caller] :as call}]
  (let [prepared (precompile-call-base! call)
        context (when caller (find-ns (symbol caller)))
        source-args
        (when (and context (= (count args) (count source-arguments)))
          (mapv (fn [argument source]
                  (if (and (map? argument) (contains? argument :comptime-type))
                    (if-let [actual (closed-type-argument-source context source)]
                      (let [expected (constructor-type (:comptime-type argument))]
                        (if (= (runtime/adapter-fingerprint expected)
                               (runtime/adapter-fingerprint actual))
                          argument
                          (if (confirm-type-argument-source! context expected actual)
                            {:comptime-type actual}
                            argument)))
                      argument)
                    argument)) args source-arguments))]
    (when (and source-args
               (not= (runtime/adapter-fingerprint args)
                     (runtime/adapter-fingerprint source-args)))
      (precompile-call-base! (assoc call :args source-args)))
    prepared))
