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

(declare coerce! call-inputs prepared-operand)

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
  [namespace expression parameters arguments {:keys [address size alignment path scalar-type native-type tuple-length comptime-expression]}]
  (let [module (str (ns-name namespace))
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
                   :schema (when (and scalar-type
                                      (or (#{:usize :isize :f32 :f64} scalar-type)
                                          (re-matches #"[iu][0-9]+" (name scalar-type))))
                             {:kind :scalar :type scalar-type})
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
    (let [{:keys [address size alignment mutable? scalar-type native-kind native-type tuple-length]} (:aguafria.jvm/borrowed result)
          pointer (list 'aguafria.zig/field
                        (expression-without-runtime-inputs namespace expression parameters)
                        :pointer)
          type (or native-type scalar-type
                   (emitter/qualify-form
                    namespace
                    (list 'aguafria.keyword/TypeOf (list 'aguafria.zig/deref pointer))))
          release-generation! (*retain-result-generation*)
          view (expression-value
                {:kind (if mutable? :var :const) :type type}
                (constantly {:representation :native
                             :segment (.reinterpret (MemorySegment/ofAddress address) size)
                             :size size :alignment alignment :owners arguments
                             :native-kind native-kind
                             :tuple-length tuple-length
                             :schema (when (and scalar-type
                                                (or (#{:usize :isize :f32 :f64} scalar-type)
                                                    (re-matches #"[iu][0-9]+" (name scalar-type))))
                                       {:kind :scalar :type scalar-type})
                             :close! release-generation!}))]
      (value/realize! view)
      view)

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

(defn- result-helper-reference! [source]
  ;; A separate Zig file gives the transport its own lexical scope. Inlining
  ;; it in a user's container makes ordinary names such as `text` shadow its
  ;; locals, and Zig correctly rejects that even inside a nested struct.
  (let [module (symbol (str "aguafria.jvm.transport-" (token source)))
        context (or (find-ns module) (create-ns module))
        name '__aguafria_jvm
        reference {:kind :const :module (str module) :name name
                   :zig-name (str name) :declaration-kind :const
                   :symbol (symbol (str module) (str name)) :public? true}]
    (locking context
      (when-not (runtime/registered-declaration? module [:raw name])
        (binding [runtime/*source-only-registration?* true]
          (register! context {:kind :raw :name name
                              :declaration-key [:raw name] :code source})))
      (alter-meta! (or (ns-resolve context name) (intern context name nil))
                   assoc :aguafria/zig-reference reference))
    (with-meta (:symbol reference) {:aguafria/zig-reference reference})))

(defn- declared-member-reference [form]
  (cond
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

(defn- prepare-expression!
  "Register the exact adapter used by invocation, without executing it."
  [namespace expression parameters result-writer]
  (let [expression (emitter/qualify-form namespace expression)
        parameters (mapv #(update % :type (partial emitter/qualify-type namespace))
                         parameters)
        result-writer (if (vector? result-writer)
                        (into [(first result-writer)]
                              (map (partial emitter/qualify-form namespace))
                              (rest result-writer))
                        result-writer)
        namespace (private-adapter-context namespace [expression parameters result-writer])]
    (locking namespace
    ;; Discovery and live values must carry the same resolved type references
    ;; before hashing, not only when register! qualifies the declaration.
      (let [local-names (adapter-local-names namespace (map :name parameters))
            renames (zipmap (map :name parameters) local-names)
            expression (walk/postwalk-replace renames expression)
            parameters (mapv #(assoc %1 :name %2) parameters local-names)
            module (str (ns-name namespace))
            helper-source (slurp (io/resource (if (= 'layoutResult result-writer)
                                                "aguafria/jvm_layout.zig"
                                                "aguafria/jvm_result.zig")))
            call-name (symbol (str "__jvm_call_"
                                   (token (cond-> [expression parameters helper-source result-writer]
                                            runtime/*native-test-context?* (conj :test)))))
            release-name (expression-cleanup-name false)
            release-native-name (expression-cleanup-name true)
            helper-name (result-helper-reference! helper-source)
            expression (let [metadata (meta expression)
                             replaced (walk/postwalk-replace {'__aguafria_jvm helper-name} expression)]
                         (if metadata (with-meta replaced metadata) replaced))
            [writer writer-arguments] (if (vector? result-writer)
                                        [(first result-writer) (rest result-writer)]
                                        [result-writer nil])
            adapter-key [module call-name expression parameters helper-source result-writer]
            [release-address release-size release-alignment]
            (adapter-local-names namespace '[address size alignment])]
        (when-not (and (registered-adapter? prepared-adapters adapter-key
                                            (symbol module (str call-name)))
                       (runtime/registered-declaration? module [:fn release-name])
                       (runtime/registered-declaration? module [:fn release-native-name]))
          (binding [runtime/*source-only-registration?* true]
            (register! namespace
                       {:kind :fn :name release-name
                        :qualified-name (symbol module (str release-name))
                        :declaration-key [:fn release-name]
                        :return :void :args [{:name release-address :type :usize}]
                        :body [(list (list 'field helper-name :release) release-address)]})
            (register! namespace
                       {:kind :fn :name release-native-name
                        :qualified-name (symbol module (str release-native-name))
                        :declaration-key [:fn release-native-name]
                        :return :void
                        :args [{:name release-address :type :usize}
                               {:name release-size :type :usize}
                               {:name release-alignment :type :usize}]
                        :body [(list (list 'field helper-name :releaseNative)
                                     release-address release-size release-alignment)]})
            (register! namespace
                       {:kind :fn :name call-name
                        :qualified-name (symbol module (str call-name))
                        :declaration-key [:fn call-name]
                        :return :usize :args parameters
                        :body [(apply list (list 'field helper-name (keyword writer))
                                      expression writer-arguments)]})))
        {:function (symbol module (str call-name))
         :context namespace
         :release (symbol module (str release-name))
         :release-native (symbol module (str release-native-name))
         :expression expression
         :parameters parameters
         :adapter-key adapter-key}))))

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
             (swap! prepared-adapters conj adapter-key)
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

(defn comptime-constant-value!
  "Read storage-free constants through Zig's value transport, never @sizeOf."
  [{:keys [module name type] :as declaration}]
  (let [namespace (symbol module)
        context (or (find-ns namespace) (create-ns namespace))
        reference (with-meta (symbol module (str name))
                    {:aguafria/zig-reference (runtime/declaration-reference declaration)})]
    ;; Pure-Zig editor documents register native declarations, not Clojure Vars.
    ;; The published descriptor supplies the reference and exact Zig spelling.
    (invoke-expression! context reference [] []
                        (if type 'storageFreeConstantResult 'comptimeResult))))

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
  (list 'aguafria.zig/deref
        (list 'aguafria.keyword/as
              '(aguafria.keyword/ptrFromInt address)
              [:*const type])))

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
  [{:keys [module name return]}]
  (if (structural-schema? return)
    return
    (list 'aguafria.zig/unwrap
          (list 'aguafria.zig/field
                (list 'aguafria.zig/field
                      (list 'aguafria.keyword/typeInfo
                            (list 'aguafria.keyword/TypeOf (symbol module (str name))))
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
  (let [type (emitter/qualify-type context type)
        adapter (prepare-expression! (inspection-context context type) (inspection-expression type)
                                     [{:name 'address :type :usize}] 'inspectResult)]
    (precompile-expression! adapter)))

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

(defn precompile-result-reader!
  "Prepare the ordinary native value reader in its producer's scope, without
  calling the producer or allocating a result. Imported types need this reader
  even when they have no Aguafria-owned layout declaration."
  [module type]
  (let [context (the-ns (symbol module))]
    (precompile-inspection! context (emitter/qualify-type context type))))

(defn inspect-value!
  "Decode an otherwise unschematized native value using Zig's own reflection.
  Read the current storage; retain the owner and never follow unbounded pointers."
  [native-value]
  (requiring-resolve 'aguafria.zig/deref)
  (let [type (or (:inspection-type (value/info native-value))
                 (value/qualified-type native-value))
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
      (emitter/qualify-type (the-ns 'aguafria.zig.jvm) type)

      (and (seq? type) (= 2 (count type))
           (contains? #{'type 'aguafria.zig/type} (first type)))
      (constructor-type (second type))

      (seq? type)
      (emitter/qualify-type (the-ns 'aguafria.zig.jvm) type)

      (vector? type)
      (mapv constructor-type type)

      (map? type)
      (into (empty type) (map (fn [[key item]] [key (constructor-type item)])) type)

      :else type)))

(defn anonymous-type!
  "Materialize anonymous container declarations without evaluating member names
  as Clojure Vars. The native compiler and type metadata use the normal path."
  [caller container locals]
  (let [container (walk/postwalk-replace
                   (into {} (map (fn [[name value]] [name (constructor-type value)])) locals)
                   container)
        container (emitter/qualify-form (the-ns caller) container)
        module (symbol (str "aguafria.jvm.container-" (token [caller container])))
        context (or (find-ns module) (create-ns module))
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
  (let [{:keys [context name function]} (coercion-location type type argument)]
    (binding [runtime/*source-only-registration?* true]
      (register! context
                 {:kind :fn :name name :qualified-name function
                  :declaration-key [:fn name] :return type :args []
                  :body [(list 'aguafria.keyword/return
                               (list 'aguafria.keyword/as argument type))]}))
    function))

(defn precompile-literal-coercion!
  "Prepare an explicit initializer and the reusable aggregate constructor.
  Zig supplies the type and layout; the initializer is not evaluated."
  [schema argument]
  (let [type (constructor-type schema)
        function (prepare-literal-coercion! type argument)]
    (runtime/precompile-function! function)
    (precompile-inspection! (the-ns (symbol (namespace function))) type)
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

(defn precompile-conversion!
  "Prepare the same adapter as coercing an existing native value, without
  constructing that value. Both schemas must be compiler-confirmed."
  [input-type result-type]
  (let [input-type (constructor-type input-type)
        result-type (constructor-type result-type)]
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
        (runtime/precompile-function!
         (prepare-literal-coercion!
          result-type (value/error-form (value/->ZigError (name member) input-type))))))
    (precompile-coercion! result-type)
    (cond-> {:type result-type :input-type input-type :status :prepared}
      (= :anyerror input-type)
      (assoc :status :partial :reason :error-name-specialization))))

(defn- prepare-construction!
  [type {:keys [expression-arguments parameters]}]
  (let [expression (list 'aguafria.keyword/as (first expression-arguments) type)
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

(defn- coerce-raw!
  "Internal coercion; the public boundary below owns numeric scalar results."
  [argument type]
  (let [type-value (cond
                     (value/zig-value? type) (value/value type)
                     (var? type) (var-get type)
                     :else type)
        compiler-type? (value/zig-type? type-value)
        type (constructor-type type)
        source (comptime-expression argument)
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
        input-type (if native? (constructor-type (value/qualified-type argument)) type)
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
      source
      ;; Keep comptime expressions intact until Zig applies the requested type.
      ;; Decoding a float to a JVM double first can round it to the wrong side
      ;; of a narrower float's midpoint.
      (runtime/invoke! (prepare-literal-coercion! type source) [])

      (instance? ContextualCall argument)
      (let [inputs (call-inputs [{:type :anytype}] [argument])
            result (runtime/invoke! (prepare-construction! type inputs) (:arguments inputs))]
        (value/retain-owners! result [argument]))

      (and (coll? argument)
           (some value/zig-value? (tree-seq coll? seq argument)))
      ;; Embedded native values need Zig's typed construction, not a field
      ;; encoder that expects every struct element to be a Clojure map.
      (let [inputs (call-inputs [{:type :anytype :properties {:jvm/literal? true}}] [argument])]
        (runtime/invoke! (prepare-construction! type inputs) (:arguments inputs)))

      (or error? literal?)
      (runtime/invoke! (prepare-literal-coercion!
                        type (if error? (value/error-form argument) argument)) [])

      (#{:comptime_int :comptime_float} type)
      (invoke-expression! context (list 'aguafria.keyword/as argument type) [] [])

      (and native? (= type input-type))
      (do (value/realize! argument) argument)

      :else
      (runtime/invoke! (prepare-coercion! type input-type expression) [argument]))))

(defn coerce!
  "Coerce while preserving the exact Zig type and addressable numeric storage.
  Use a/value for explicit conversion back to a plain JVM value."
  [argument type]
  (let [result (coerce-raw! argument type)]
    (if (number? result)
      (if (#{:comptime_int :comptime_float} type)
        (value/native-value {:kind :const :type type}
                            (constantly {:representation :scalar :value result}))
        (value/array-element-view (coerce-raw! [result] [:array 1 (constructor-type type)]) 0))
      result)))

(defn coerce-contextual-arguments!
  "Apply a concrete function's parameter types to deferred builtin arguments."
  [module declaration arguments]
  (let [context (the-ns (symbol module))]
    (mapv (fn [index argument]
            (if (instance? ContextualCall argument)
              (coerce! argument (qualify-native-type
                                 context (argument-reader-type declaration index)))
              argument))
          (range (count (:args declaration))) arguments)))

(defn invoke-value!
  "Public Var call: retain numeric results; internal ABI calls remain scalars."
  [declaration arguments]
  (let [result (runtime/invoke! (:qualified-name declaration) arguments)]
    (if (number? result)
      (coerce! result (:return declaration))
      result)))

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
                 (:type argument) (value/qualified-type argument))]
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
     (let [fields (when (and (seq? form) (= 2 (count form))
                             (= 'object (first form)) (vector? (second form)))
                    (second form))
           entries (when (and fields
                              (every? #(and (vector? %) (= 2 (count %))
                                            (or (keyword? (first %)) (symbol? (first %))
                                                (string? (first %)))) fields))
                     (mapv (fn [[field item]]
                             [(if (symbol? field) (clojure.core/keyword (name field)) field) item])
                           fields))]
       (if (and entries
                (= (count entries) (count (distinct (map first entries))))
                (every? #(or (nil? %) (number? %) (boolean? %) (char? %)
                             (string? %) (keyword? %) (vector? %) (map? %))
                        (tree-seq coll? seq (mapv second entries))))
         (into {} entries)
         form)))
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
              (let [original argument
                    argument (if (value/zig-value? argument) (value/value argument) argument)
                    expected (get type-arguments expected expected)
                    inferred (cond
                               (instance? PreparedOperand original) (:type original)
                               (value/zig-value? original)
                               (value/qualified-type original)
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
                    zig-type (if (or (#{:bool :f16 :f32 :f64 :f80 :f128 :isize :usize} expected)
                                     (and (keyword? expected)
                                          (re-matches #"[iu][0-9]+" (name expected))))
                               expected
                               inferred)]
                (cond
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
                  (comptime-expression original) (canonical-comptime-object (comptime-expression original))
                  (instance? PreparedExpression argument) (canonical-comptime-object (:expression argument))
                  (instance? PreparedType argument) (type-expression (:schema argument))
                  (value/zig-type? argument) (type-expression argument)
                  (#{:type 'type} expected) (type-expression argument)
                  (:aguafria/zig-reference (meta argument))
                  (let [reference (:aguafria/zig-reference (meta argument))]
                    (if (and (#{:global-const :global-variable} (:category reference))
                             (= "aguafria.std.testing" (namespace (:symbol reference))))
                      (lift (test-resource! reference) expected literal?)
                      (with-meta (:symbol reference) {:aguafria/zig-reference reference})))
                  (value/zig-error? argument) (value/error-form argument)
                  (primitive-literal? argument) argument
                  ;; Concrete integer parameters can carry character code points
                  ;; at runtime. Generic/comptime characters remain Zig literals.
                  (and (char? argument) (not literal?) (keyword? expected)
                       (or (#{:usize :isize} expected)
                           (re-matches #"[iu][0-9]+" (name expected))))
                  (lift (int argument) expected false)
                  (char? argument) argument
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
                    (swap! parameters conj {:name name :type zig-type})
                    (swap! values conj argument)
                    name)
                  (vector? argument) (mapv #(lift % nil literal?) argument)
                  (map? argument) (into (empty argument)
                                        (map (fn [[key item]] [key (lift item nil literal?)])) argument)
                  (or (keyword? argument) (nil? argument)) argument
                  (var? argument) (with-meta (symbol (str (ns-name (:ns (meta argument))))
                                                     (str (:name (meta argument))))
                                    (select-keys (meta argument) [:aguafria/zig-reference]))
                  :else (throw (ex-info "Cannot pass this value to native Zig"
                                        {:argument argument :type (type argument)})))))]
      {:expression-arguments
       (mapv (fn [{:keys [properties type]} argument]
               (cond
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
  [expression]
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
          (list 'aguafria.keyword/TypeOf type-expression))))))

(defn- scoped-argument-declarations [entries]
  (mapv #(if (:mutable? %) {:type :usize}
             {:type :anytype :properties {:jvm/literal? true}}) entries))

(defn- prepare-scoped-plan! [caller form entries {:keys [parameters expression-arguments]} result?]
  (let [replacements (into {}
                           (map (fn [[{:keys [name type mutable?]} argument]]
                                  [name (if mutable?
                                          (list 'aguafria.zig/deref
                                                (list 'aguafria.keyword/as
                                                      (list 'aguafria.keyword/ptrFromInt argument)
                                                      [:* type]))
                                          argument)]))
                           (map vector entries expression-arguments))
        context (the-ns caller)
        expression (binding [emitter/*local-type-bindings* (zipmap (map :name entries) (repeat false))
                             emitter/*local-name-bindings* replacements]
                     (emitter/qualify-form context form))
        name (symbol (str "__jvm_scope_"
                          (token (cond-> [expression parameters]
                                   result? (conj :result)
                                   runtime/*native-test-context?* (conj :test)))))
        function (symbol (str caller) (str name))
        result-type (when result? (scoped-result-type-expression expression))]
    (when (or (not result?) result-type)
      (locking context
        (when-not (registered-adapter? prepared-adapters function function)
          (binding [runtime/*source-only-registration?* true]
            (register! context
                       (cond-> {:kind :fn :name name :qualified-name function
                                :declaration-key [:fn name] :args parameters
                                :return :void :body [expression]}
                         result-type (assoc :zig-prefix "inline"
                                            :return [:error-union :anyerror result-type]
                                            :body [(list 'return expression)]))))
          (swap! prepared-adapters conj function))))
    {:context context :function function :parameters parameters
     :expression (if result-type (apply list name (map :name parameters)) expression)}))

(defn invoke-scoped!
  "Execute native scoped syntax with JVM lexical captures in the same process.
  Mutable captures are passed by address, not silently copied into parameters."
  ([caller form locals] (invoke-scoped! caller form locals false))
  ([caller form locals result?]
   (binding [runtime/*native-test-context?*
             (or runtime/*native-test-context?*
                 (some #(and (value/zig-value? %)
                             (= :test (:execution-context (value/info %)))) (vals locals)))]
     (let [values (sort-by (comp str key) locals)
           entries (mapv (fn [[name v]]
                           (let [mutable? (and (value/zig-value? v) (= :var (:kind (value/info v))))]
                             {:name name :mutable? mutable?
                              :type (when mutable? (value/qualified-type v))})) values)
           operands (mapv (fn [[entry [_ v]]]
                            (if (:mutable? entry) (.address ^MemorySegment (value/segment v)) v))
                          (map vector entries values))
           inputs (call-inputs (scoped-argument-declarations entries) operands)
           {:keys [context expression parameters function]}
           (prepare-scoped-plan! caller form entries inputs result?)]
       (try
         (if result?
           (invoke-expression! context expression parameters (:arguments inputs))
           (runtime/invoke! function (:arguments inputs)))
         (finally (java.lang.ref.Reference/reachabilityFence locals)))))))

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
                                                (list 'type input-type))
                                          (list 'type input-type))
                        target-name (symbol (str "__aguafria_parameter_type_" index))
                        kind? (fn [type kind]
                                (list 'aguafria.keyword/== (list 'aguafria.keyword/typeInfo type)
                                      (keyword (str "." kind))))
                        pair? (fn [target-kind input-kind]
                                (list 'aguafria.keyword/comptime
                                      (list 'and (kind? target-name target-kind)
                                            (kind? (list 'type input-type) input-kind))))]
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
          {:keys [context expression parameters]}
          (method-plan receiver-type member mutable? native? inputs)]
      (binding [runtime/*native-test-context?*
                (or runtime/*native-test-context?*
                    (and native? (= :test (:execution-context (value/info receiver)))))]
        (try
          (invoke-expression! context expression
                              parameters
                              (if native?
                                (into [(.address ^MemorySegment (value/segment receiver))] arguments)
                                arguments))
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
        expression (list '(field __aguafria_jvm :dereferenceView) (first expression-arguments))]
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
    (if (= {"__aguafria_bound_method" true} result)
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

(defn- indexed-value!
  "Keep addressable indexed values attached to their original native storage.
  Zig decides the element type and pointee constness; vector lanes remain values."
  [receiver index]
  (let [owner (if (value/zig-value? receiver)
                receiver
                (coerce! receiver (value/pointer-type receiver)))
        type (value/qualified-type owner)
        module (clojure.core/symbol (str "aguafria.jvm.index-" (token type)))
        context (or (find-ns module) (create-ns module))
        comptime-index? (and (index-reflection? type)
                             (invoke-expression! context
                                                 (list '(field __aguafria_jvm :indexRequiresComptime)
                                                       (list 'type type))
                                                 [] [] 'inspectResult))
        storage (value/address-value owner (= :var (:kind (value/info owner))))
        {:keys [expression parameters arguments]}
        (index-plan type storage index comptime-index?)
        result (invoke-expression! context expression parameters arguments 'borrowedResult)]
    (value/retain-owners! result [owner storage])))

(defn- syntax-result-writer [syntax parameters handler-plan]
  (cond
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
    (cons {:type :anytype} (repeat (dec argument-count) {:type :usize}))

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
          runtime-slice? (and (= 'slice (:name syntax))
                              (some runtime-index-operand? (rest arguments)))
          declarations (syntax-call-declarations syntax (count arguments) handler-plan runtime-slice?)
          operands (or (:arguments handler-plan) arguments)
          storage-source? (and (= 'slice (:name syntax)) (value/zig-value? receiver)
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
          {:keys [context expression expression-arguments writer]}
          (call-expression-plan {:aguafria/syntax syntax} inputs handler-plan)
          expression (if native-field?
                       (if (comptime-expression receiver)
                         (comptime-field-expression (first expression-arguments) member)
                         (list '(field __aguafria_jvm :lookupField)
                               (first expression-arguments) (name member)))
                       expression)
          result (invoke-expression! context expression parameters arguments
                                     (or (syntax-result-writer syntax parameters handler-plan)
                                         (cond
                                           (and (#{'field 'index 'slice 'deref} (:name syntax))
                                                (comptime-expression receiver)
                                                (empty? parameters))
                                           'comptimeExpressionResult
                                           (and (= 'field (:name syntax)) (value/zig-type? receiver))
                                           ['declarationFieldResult (first expression-arguments)
                                            (native-member-name member)]
                                           :else writer)))]
      (cond
        (:result-type handler-plan)
        (value/native-value {:kind :const :type (:result-type handler-plan)}
                            (constantly {:representation :scalar :value result}))

        (and (= 'field (:name syntax)) (= {"__aguafria_bound_method" true} result))
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
               (value/zig-value? argument) (value/qualified-type argument))]
    (cond
      (and (keyword? type)
           (or (#{:usize :isize :f16 :f32 :f64 :f80 :f128} type)
               (re-matches #"[iu][0-9]+" (name type)))) type
      (and (nil? type) (integer? argument)) :comptime_int
      (and (nil? type) (or (instance? Double argument) (instance? Float argument))
           (Double/isFinite (double argument))) :comptime_float)))

(def ^:private confirmed-peer-type
  (memoize
   (fn [types]
     ;; Compile a type check without loading a library or running a function.
     ;; Zero stands for the literal's type; the call still checks its real value.
     (let [module (symbol (str "aguafria.jvm.peer-type-" (token types)))
           context (or (find-ns module) (create-ns module))
           expected (first (remove #{:comptime_int :comptime_float} types))
           expression (apply list 'aguafria.keyword/TypeOf
                             (map #(case %
                                     :comptime_int 0
                                     :comptime_float 0.0
                                     (list 'aguafria.keyword/as 'aguafria.keyword/undefined %)) types))]
       (binding [runtime/*source-only-registration?* true]
         (register! context {:kind :const :name 'CommonType :value expression
                             :declaration-key [:const 'CommonType] :jvm-adapter? false}))
       (let [result (runtime/inspect-module!
                     module
                     (fn [_]
                       {:source (str "comptime { if (" (emitter/emit-expr context expression)
                                     " != " (emitter/emit-type expected)
                                     ") @compileError(\"JVM operand changes the common Zig type\"); }")}))]
         (when-not (zero? (:exit result))
           (throw (ex-info "Zig could not confirm the common argument type" result)))
         expected)))))

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
               floating? (assoc-in [:properties :jvm/peer-float?] true)) declarations)
      declarations)))

(defn call-parameters
  "Read native parameter declarations from a registered declaration or the
  Zig-parsed imported/builtin signature. Does not infer expression types."
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
                       (str/replace-first (:signature syntax) #"^@[A-Za-z0-9_]+" "fn builtin")))]
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
        stable-return? (and reference (:return declaration)
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
                       {:module (namespace (:symbol reference))
                        :name (symbol (name (:symbol reference)))
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
    {:keys [parameters expression-arguments]} handler-plan]
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
                      expression)]
     {:context context :expression expression :parameters parameters
      :expression-arguments expression-arguments
      :writer (or (syntax-result-writer syntax parameters handler-plan)
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
         (invoke-syntax! {:name 'field :symbol 'aguafria.zig/field
                          :kind :syntax :param-count 2}
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

(defn- slice-signature-plan [receiver address indices]
  (let [ordinary-array? (and (vector? receiver) (= :array (first receiver)) (= 3 (count receiver)))
        operands (mapv #(prepared-operand % {:type :usize}) indices)
        runtime-index? (some runtime-index-operand? operands)
        mutable? (= :* (first address))]
    (if (and ordinary-array? runtime-index?)
      (assoc (runtime-array-plan
              :runtimeArraySlice
              (into [(->PreparedOperand [(if mutable? :many :many-const) (nth receiver 2)])
                     (->PreparedOperand :usize)]
                    (if (= 1 (count operands))
                      (conj operands (->PreparedOperand :usize)) operands)))
             :writer 'borrowedResult)
      (let [syntax {:kind :syntax :name 'slice :symbol 'aguafria.zig/slice}
            operands (into [(->PreparedOperand address)] operands)
            {:keys [parameters expression-arguments]}
            (call-inputs (into [{:type :anytype}]
                               (repeat (count indices)
                                       (if runtime-index? {:type :usize}
                                           {:type :anytype :properties {:jvm/literal? true}})))
                         operands)]
        {:module (symbol (str "aguafria.jvm.expression-" (token syntax)))
         :expression (apply list 'aguafria.zig/slice
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
        (precompile-expression!
         (prepare-expression! (the-ns (symbol (namespace reference))) reference []
                              (if (:type declaration) 'storageFreeConstantResult 'comptimeResult))))

      (instance? PreparedOperand operand)
      (let [type (:type operand)]
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
          (precompile-expression!
           (prepare-expression! (the-ns (symbol (namespace type))) type []
                                (if (:type declaration) 'storageFreeConstantResult 'comptimeResult)))

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
    (when (#{:index :slice} kind)
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

      (and (= :field kind) (:comptime-expression receiver))
      (let [operands [(prepared-operand receiver nil) member]
            syntax {:kind :syntax :name 'field :symbol 'aguafria.zig/field}
            inputs (call-inputs (repeat 2 {:type :anytype :properties {:jvm/literal? true}})
                                operands)
            {:keys [context expression parameters]}
            (call-expression-plan {:aguafria/syntax syntax} inputs)
            expression (comptime-field-expression (first (:expression-arguments inputs)) member)]
        (precompile-operand-readers! operands)
        (precompile-expression! (prepare-expression! context expression parameters
                                                     'comptimeExpressionResult))
        (assoc signature :status :prepared))

      :else
      (let [ordinary-array? (and (vector? receiver) (= :array (first receiver))
                                 (= 3 (count receiver)))
            mutable? (and (sequential? address) (= :* (first address)))
            {:keys [module expression types parameters writer]}
            (case kind
              :deref
              {:module (symbol (str "aguafria.jvm.pointee-" (token receiver)))
               :expression '((field __aguafria_jvm :dereferenceView) input_0)
               :types [receiver]}

              :slice (slice-signature-plan receiver address indices)
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
        (->PreparedExpression (emitter/qualify-form *ns* expression))))
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
        function (prepare-construction! type inputs)]
    (runtime/precompile-function! function)
    (precompile-inspection! (the-ns (symbol (namespace function))) type)
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

(defn precompile-concrete-call!
  "Prepare the function and deferred arguments using compiler-observed parameter types."
  [function arguments]
  (let [result (runtime/precompile-function! function)]
    (doseq [argument arguments
            :let [[target operand] (:contextual-argument argument)]
            :when target]
      (precompile-construction! target operand))
    result))

(defn precompile-method!
  "Prepare a member call from compiler-observed receiver/argument types.
  Shares the normal JVM method planner; never constructs or invokes a receiver."
  [{:keys [receiver address member args] :as signature}]
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
        {:keys [context expression parameters]}
        (method-plan receiver-type member mutable? native? inputs)]
    (when-not native?
      (precompile-storage! {:kind :field :receiver receiver :member member}))
    (precompile-expression! (prepare-expression! context expression parameters 'result))
    (assoc signature :status :prepared)))

(defn precompile-scoped!
  "Prepare the ordinary scoped adapter from compiler-observed capture schemas.
  Mutable captures keep their native storage addresses; no body is executed."
  [{:keys [caller form captures types result?] :as signature}]
  (when-not (= (* 2 (count captures)) (count types))
    (throw (ex-info "Scoped capture signature does not match its bindings"
                    {:captures captures :types types})))
  (let [entries (mapv (fn [name [type address]]
                        {:name name :type (constructor-type type)
                         :mutable? (and (vector? address) (= :* (first address)))})
                      captures (partition 2 types))
        inputs (precompile-inputs
                (mapv (fn [entry [type _]] (if (:mutable? entry) :usize type))
                      entries (partition 2 types))
                (scoped-argument-declarations entries))
        {:keys [context expression parameters function]}
        (prepare-scoped-plan! caller form entries inputs result?)]
    (if result?
      (precompile-expression! (prepare-expression! context expression parameters 'result))
      (runtime/precompile-function! function))
    (assoc signature :status :prepared)))

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
         (aguafria.zig/number-literal aguafria.zig/string-literal
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

(defn precompile-call!
  "Compile an explicit native call signature without creating operands or making
  the call. Argument entries are native types or {:comptime source-value}.
  Uses the same adapter registration and cache identity as normal JVM calls."
  [{:keys [function args] :as call}]
  (let [v (find-var function)
        {token-syntax :aguafria/token
         value-syntax :aguafria/syntax
         zig-reference :aguafria/zig-reference
         declaration :aguafria/declaration} (meta v)
        syntax (or token-syntax value-syntax)
        imported? (and zig-reference (nil? declaration))
        value-call? (= :const (:kind declaration))
        generic? (and (= :fn (:kind declaration))
                      (some #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                 (= "comptime" (get-in % [:properties :zig/prefix])))
                            (:args declaration)))
        supported? (or generic? value-call?
                       (= 'aguafria.zig/type function)
                       (= 'aguafria.zig/unwrap function)
                       (and (#{'aguafria.zig/field 'aguafria.zig/index} function)
                            (map? (first args))
                            (contains? (first args) :tuple))
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
      (doseq [type inspection-types]
        (precompile-inspection! context type))
      (let [return-type (or (:return declaration)
                            (when imported?
                              (:return (signature/callable-declaration zig-reference))))]
        ;; Native C integer aliases retain their compiler identity (c_int is
        ;; not renamed to a guessed host integer). Their JVM value reader uses
        ;; the existing compiler reflection adapter instead of a scalar codec.
        (when (and (keyword? return-type) (str/starts-with? (name return-type) "c_"))
          (precompile-inspection! context return-type)))
      {:function function :args args :status :prepared})))
