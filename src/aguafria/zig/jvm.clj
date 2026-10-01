(ns aguafria.zig.jvm
  "Native specialization and value transport for Clojure and Java callers.

  Comptime inputs remain in Zig source, never in an invalid C ABI trampoline.
  Concrete call adapters run in the same live module as the original function."
  (:require [aguafria.zig.convert :as convert]
            [aguafria.keyword :as keyword]
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

(defn- register! [namespace descriptor]
  (binding [emitter/*registered-declaration-names*
            (set (map :name (:definitions (runtime/module-info (ns-name namespace)))))]
    (runtime/register-declaration!
     (emitter/prepare-declaration
      namespace
      (merge {:module (str (ns-name namespace)) :public? false :export? false
              :jvm-adapter? true
              :implicit-return? true}
             descriptor)))))

(declare coerce! call-inputs)

(def ^:dynamic ^:private *retain-result-generation* nil)

(defn- comptime-expression [operand]
  (when (value/zig-value? operand)
    (let [state (value/realize! operand)]
      (when (or (= :comptime-expression (:representation state))
                (and (= :scalar (:representation state))
                     (#{:comptime_int :comptime_float} (value/qualified-type operand))))
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
  (emitter/qualify-form
   namespace
   (if (empty? parameters)
     expression
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
                     [(list 'aguafria.keyword/break label expression)])))))))

(defn- native-expression-result
  [namespace expression parameters arguments {:keys [address size alignment path scalar-type native-type tuple-length]}]
  (let [module (str (ns-name namespace))
        root-type (list 'aguafria.keyword/TypeOf
                        (expression-without-runtime-inputs namespace expression parameters))
        type (or native-type scalar-type (reduce (fn [type [kind member]]
                                                   (list 'field
                                                         (list 'field
                                                               (list 'aguafria.keyword/typeInfo type) (keyword kind))
                                                         (keyword member)))
                                                 root-type (partition 2 path)))
        release-generation! (*retain-result-generation*)
        result (value/native-value
                {:module module :kind :return :type type}
                (fn []
                  {:representation :native
                   :segment (.reinterpret (MemorySegment/ofAddress address) size)
                   :size size :alignment alignment :owners arguments
                   :tuple-length tuple-length
                   :schema (when (and scalar-type
                                      (or (#{:usize :isize :f32 :f64} scalar-type)
                                          (re-matches #"[iu][0-9]+" (name scalar-type))))
                             {:kind :scalar :type scalar-type})
                   :close! #(try
                              (runtime/invoke! (symbol module "__jvm_release_native")
                                               [address size alignment])
                              (finally (release-generation!)))}))]
    ;; The allocation already exists, so register its cleanup immediately.
    (value/value result)
    result))

(defn- expression-result
  [namespace expression parameters arguments result]
  (cond
    (and (map? result) (= #{:aguafria.jvm/comptime-expression} (set (keys result))))
    (let [source (expression-without-runtime-inputs namespace expression parameters)
          snapshot (inspection-view (get-in result [:aguafria.jvm/comptime-expression :snapshot]))]
      (value/native-value
       {:module (str (ns-name namespace)) :kind :const
        :type (list 'aguafria.keyword/TypeOf source)}
       (constantly {:representation :comptime-expression
                    :expression source
                    :decoded-fn (fn [_] snapshot)})))

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
          view (value/native-value
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
      (value/native-value {:kind :const :type type}
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
      (when-not (ns-resolve context name)
        (binding [runtime/*source-only-registration?* true]
          (register! context {:kind :raw :name name
                              :declaration-key [:raw name] :code source})))
      (alter-meta! (or (ns-resolve context name) (intern context name nil))
                   assoc :aguafria/zig-reference reference))
    (with-meta (:symbol reference) {:aguafria/zig-reference reference})))

(defn- prepare-expression!
  "Register the exact adapter used by invocation, without executing it."
  [namespace expression parameters result-writer]
  (locking namespace
    ;; Discovery and live values must carry the same resolved type references
    ;; before hashing, not only when register! qualifies the declaration.
    (let [parameters (mapv #(update % :type (partial emitter/qualify-type namespace))
                           parameters)
          module (str (ns-name namespace))
          helper-source (slurp (io/resource "aguafria/jvm_result.zig"))
          call-name (symbol (str "__jvm_call_" (token [expression parameters helper-source result-writer])))
          helper-name (result-helper-reference! helper-source)
          expression (walk/postwalk-replace {'__aguafria_jvm helper-name} expression)
          adapter-key [module call-name expression parameters helper-source result-writer]]
      (when-not (contains? @prepared-adapters adapter-key)
        (binding [runtime/*source-only-registration?* true]
          (register! namespace
                     {:kind :fn :name '__jvm_release
                      :qualified-name (symbol module "__jvm_release")
                      :declaration-key [:fn '__jvm_release]
                      :return :void :args [{:name 'address :type :usize}]
                      :body [(list (list 'field helper-name :release) 'address)]})
          (register! namespace
                     {:kind :fn :name '__jvm_release_native
                      :qualified-name (symbol module "__jvm_release_native")
                      :declaration-key [:fn '__jvm_release_native]
                      :return :void
                      :args [{:name 'address :type :usize}
                             {:name 'size :type :usize}
                             {:name 'alignment :type :usize}]
                      :body [(list (list 'field helper-name :releaseNative) 'address 'size 'alignment)]})
          (register! namespace
                     {:kind :fn :name call-name
                      :qualified-name (symbol module (str call-name))
                      :declaration-key [:fn call-name]
                      :return :usize :args parameters
                      :body [(list (list 'field helper-name (keyword result-writer)) expression)]})))
      {:function (symbol module (str call-name))
       :release (symbol module "__jvm_release")
       :release-native (symbol module "__jvm_release_native")
       :expression expression
       :adapter-key adapter-key})))

(defn- precompile-expression! [adapter]
  ;; The result envelope and owned native payload have separate lifetimes.
  ;; Prepare both cleanup paths; GC must not trigger a first-time compilation.
  (doseq [function ((juxt :function :release :release-native) adapter)]
    (runtime/precompile-function! function))
  (swap! prepared-adapters conj (:adapter-key adapter)))

(defn- invoke-expression!
  ([namespace expression parameters arguments]
   (invoke-expression! namespace expression parameters arguments 'result))
  ([namespace expression parameters arguments result-writer]
   (binding [runtime/*native-test-context?*
             (or runtime/*native-test-context?*
                 (some #(and (value/zig-value? %)
                             (= :test (:execution-context (value/info %)))) arguments))]
     (locking namespace
       (let [{:keys [function release expression adapter-key]}
             (prepare-expression! namespace expression parameters result-writer)]
         (runtime/invoke-with-result!
          function arguments
          (fn [address retain-generation!]
            (try
              (let [result (edn/read-string
                            (.getString (.reinterpret (MemorySegment/ofAddress address)
                                                      Long/MAX_VALUE) 0))]
                (swap! prepared-adapters conj adapter-key)
                (binding [*retain-result-generation* retain-generation!]
                  (expression-result namespace expression parameters arguments result)))
              (finally
                (runtime/invoke! release [address]))))))))))

(defn comptime-constant-value!
  "Read storage-free constants through Zig's value transport, never @sizeOf."
  [{:keys [module name]}]
  (invoke-expression! (the-ns (symbol module)) (symbol module (str name)) [] [] 'comptimeResult))

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

(defn- inspection-context [context type]
  ;; Structural schemas need no producer's lexical scope. Reuse their reader
  ;; across constructors, operators, fields and tuple results. Nominal and
  ;; expression types still require the original scope (including privacy).
  (if (structural-schema? type)
    (let [name (symbol (str "aguafria.jvm.inspect-" (token type)))]
      (or (find-ns name) (create-ns name)))
    context))

(defn- precompile-inspection! [context type]
  (let [adapter (prepare-expression! (inspection-context context type) (inspection-expression type)
                                     [{:name 'address :type :usize}] 'inspectResult)]
    (precompile-expression! adapter)))

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
  (let [type (value/qualified-type native-value)
        namespace-name (symbol (str "aguafria.jvm.inspect-" (token type)))
        context (inspection-context
                 (or (some-> (:module (value/info native-value)) symbol find-ns)
                     (find-ns namespace-name)
                     (create-ns namespace-name))
                 type)]
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
      (or (comptime-expression type) (value/value type))

      (= :container (get-in (meta type) [:aguafria/zig-reference :category]))
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

      (and (seq? type) (qualified-symbol? (first type)))
      (emitter/qualify-form (the-ns (symbol (namespace (first type)))) type)

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
      (when-not (contains? @prepared-adapters module)
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
  (let [reference (cond
                    (qualified-symbol? type) type
                    (and (seq? type) (qualified-symbol? (first type))) (first type))
        declaration (some-> reference find-var meta :aguafria/declaration)
        owner (when (false? (:public? declaration))
                (some-> declaration :module symbol find-ns))
        suffix (token [type input-type literal])
        module (if owner (ns-name owner)
                   (symbol (str "aguafria.jvm.coercion-" suffix)))
        name (if owner (symbol (str "__jvm_coerce_" suffix)) 'coerce)]
    ;; A private type factory must be referenced from its defining Zig file.
    ;; Keep an adapter-specific name there rather than exporting the factory.
    {:context (or owner (find-ns module) (create-ns module))
     :name name
     :function (symbol (str module) (str name))}))

(defn- prepare-coercion! [type input-type expression]
  (let [{:keys [context name function]} (coercion-location type input-type nil)
        qualified-name function]
    (locking context
      (when-not (contains? @prepared-coercions qualified-name)
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
  "Compile a value constructor and any numeric-result storage without creating
  a value. The schema is explicit; compiler analysis decides layout/validity."
  [schema]
  (let [type (constructor-type schema)
        extended? (contains? #{:f16 :f80 :f128} type)
        input-type (if extended? :f64 type)
        expression (if extended? '(aguafria.keyword/floatCast input) 'input)
        numeric? (and (keyword? type)
                      (or (#{:usize :isize :f16 :f32 :f64 :f80 :f128} type)
                          (re-matches #"[iu][0-9]+" (name type))))
        type-preparation (when (qualified-symbol? type)
                           (runtime/precompile-type! type))]
    (when (qualified-symbol? type)
      (precompile-inspection! (the-ns (symbol (namespace type))) type))
    (let [function (prepare-coercion! type input-type expression)]
      (runtime/precompile-function! function)
      ;; Returning/printing a native value must not introduce its first decoder
      ;; build after restart. Prepare the same owner's reflection adapter too.
      (precompile-inspection! (the-ns (symbol (namespace function))) type))
    (when numeric?
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
  "Prepare the normal computed-type constructor for explicit literal data.
  The compiler supplies the type; data is retained from the initializer, not
  evaluated to discover values. This does not warm arbitrary future values."
  [schema argument]
  (let [type (constructor-type schema)
        function (prepare-literal-coercion! type argument)]
    (runtime/precompile-function! function)
    (precompile-inspection! (the-ns (symbol (namespace function))) type)
    {:type schema :status :prepared :literal-value argument}))

(defn precompile-conversion!
  "Prepare the same adapter as coercing an existing native value, without
  constructing that value. Both schemas must be compiler-confirmed."
  [input-type result-type]
  (let [input-type (constructor-type input-type)
        result-type (constructor-type result-type)]
    (when-not (= input-type result-type)
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
        context (or (find-ns module) (create-ns module))
        name (symbol (str module) "construct")]
    (locking context
      (when-not (contains? @prepared-coercions name)
        (binding [runtime/*source-only-registration?* true]
          (register! context {:kind :fn :name 'construct :qualified-name name
                              :declaration-key [:fn 'construct] :return type
                              :args parameters :body [expression]}))
        (swap! prepared-coercions conj name)))
    name))

(defrecord ContextualCall [syntax arguments])

(defmethod print-method ContextualCall
  [call writer]
  (.write ^java.io.Writer writer
          (str "#aguafria/contextual-call[" (get-in call [:syntax :symbol])
               " — result type required; use k/as]")))

(defn- coerce-raw!
  "Internal coercion; the public boundary below owns numeric scalar results."
  [argument type]
  (let [source (comptime-expression argument)
        argument (if (and (value/zig-value? argument) (not source)) (value/value argument) argument)
        type (constructor-type type)
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
                     (and (seq? type)
                          (or (nil? argument) (boolean? argument) (number? argument)
                              (char? argument) (string? argument)
                              (map? argument) (vector? argument))))
        input-type (if native? (constructor-type (value/qualified-type argument)) type)
        extended-float? (and (not native?) (#{:f16 :f80 :f128} type))
        input-type (if extended-float? :f64 input-type)
        namespace-name (symbol (str "aguafria.jvm.coercion-" (token [type input-type (when (or error? literal?) argument)])))
        context (or (find-ns namespace-name) (create-ns namespace-name))
        qualified-name (symbol (str namespace-name) "coerce")
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
      (do
        (prepare-coercion! type input-type expression)
        (runtime/invoke! qualified-name [argument])))))

(defn coerce!
  "Coerce while preserving the exact Zig type and addressable numeric storage.
  Use az/value for explicit conversion back to a plain JVM value."
  [argument type]
  (let [result (coerce-raw! argument type)]
    (if (number? result)
      (if (#{:comptime_int :comptime_float} type)
        (value/native-value {:kind :const :type type}
                            (constantly {:representation :scalar :value result}))
        (value/array-element-view (coerce-raw! [result] [:array 1 (constructor-type type)]) 0))
      result)))

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

(defn invoke-assignment!
  "Execute a compound assignment in Zig with independently typed operands.
  Literals retain Zig's contextual typing; native operands retain their types."
  [syntax target operand]
  (when-not (and (value/zig-value? target) (= :var (:kind (value/info target))))
    (throw (ex-info "Assignment requires a mutable native value; create it with ak/var"
                    {:target target})))
  (let [storage (value/address-value target true)
        {:keys [expression-arguments parameters arguments]}
        (call-inputs [{:type :anytype}
                      (if-let [type (when (number? operand)
                                      (handlers/assignment-operand-type target (:zig-token syntax)))]
                        {:type type}
                        {:type :anytype :properties {:jvm/literal? true}})]
                     [storage operand])
        expression (list (:symbol syntax)
                         (list 'aguafria.zig/deref (first expression-arguments))
                         (second expression-arguments))
        module (symbol (str "aguafria.jvm.assignment-"
                            (token [expression parameters])))
        context (or (find-ns module) (create-ns module))
        qualified-name (symbol (str module) "assign")]
    (locking context
      (when-not (contains? @prepared-adapters qualified-name)
        (binding [runtime/*source-only-registration?* true]
          (register! context
                     {:kind :fn :name 'assign :qualified-name qualified-name
                      :declaration-key [:fn 'assign] :return :void
                      :args parameters
                      :body [expression]}))
        (swap! prepared-adapters conj qualified-name))
      (try
        (runtime/invoke! qualified-name arguments)
        (finally (java.lang.ref.Reference/reachabilityFence storage))))))

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
          context (or (find-ns module) (create-ns module))
          qualified-name (symbol (str module) "assign")]
      (locking context
        (when-not (contains? @prepared-adapters qualified-name)
          (binding [runtime/*source-only-registration?* true]
            (register! context {:kind :fn
                                :name 'assign
                                :qualified-name qualified-name
                                :declaration-key [:fn 'assign]
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

(declare signature-arguments)

(defn- builtin-arguments [signature]
  ;; Compiler-dependent parameter types remain in source. Zig supplies the
  ;; actual type; native handles keep their explicit transport types.
  (mapv (fn [parameter]
          (cond-> parameter
            (or (symbol? (:type parameter)) (seq? (:type parameter)))
            (assoc-in [:properties :jvm/literal?] true)))
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
                                                       (get-in declaration [:properties :jvm/literal?])))
                                               declarations arguments))]
                    (if (or (nil? expected) (#{:anytype 'anytype} expected))
                      expression
                      (list 'aguafria.keyword/as expression (constructor-type expected))))
                  (comptime-expression original) (comptime-expression original)
                  (instance? PreparedType argument) (type-expression (:schema argument))
                  (#{:type 'type} expected) (type-expression argument)
                  (:aguafria/zig-reference (meta argument))
                  (let [reference (:aguafria/zig-reference (meta argument))]
                    (if (and (#{:global-const :global-variable} (:category reference))
                             (= "aguafria.std.testing" (namespace (:symbol reference))))
                      (lift (test-resource! reference) expected literal?)
                      (with-meta (:symbol reference) {:aguafria/zig-reference reference})))
                  (value/zig-error? argument) (value/error-form argument)
                  (primitive-literal? argument) argument
                  ;; A JVM Character is a Zig character literal, not a Java
                  ;; object pointer or a string. Keep its code point in source.
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
                  (value/zig-type? argument)
                  (type-expression argument)
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

(defn invoke-scoped!
  "Execute native scoped syntax with JVM lexical captures in the same process.
  Mutable captures are passed by address, not silently copied into parameters."
  ([caller form locals] (invoke-scoped! caller form locals false))
  ([caller form locals result?]
   (let [entries (sort-by (comp str key) locals)
         mutable? #(and (value/zig-value? %) (= :var (:kind (value/info %))))
         operands (mapv (fn [[_ v]]
                          (if (mutable? v) (.address ^MemorySegment (value/segment v)) v))
                        entries)
         {:keys [parameters arguments expression-arguments]}
         (call-inputs (mapv (fn [[_ v]]
                              (if (mutable? v)
                                {:type :usize}
                                {:type :anytype :properties {:jvm/literal? true}})) entries)
                      operands)
         replacements (into {}
                            (map (fn [[[name v] argument]]
                                   [name (if (mutable? v)
                                           (list 'aguafria.zig/deref
                                                 (list 'aguafria.keyword/as
                                                       (list 'aguafria.keyword/ptrFromInt argument)
                                                       [:* (value/qualified-type v)]))
                                           argument)]))
                            (map vector entries expression-arguments))
         expression (binding [emitter/*local-type-bindings* (zipmap (keys locals) (repeat false))
                              emitter/*local-name-bindings* replacements]
                      (emitter/qualify-form (the-ns caller) form))
         context (the-ns caller)
         name (symbol (str "__jvm_scope_" (token [expression parameters])))
         qualified-name (symbol (str caller) (str name))]
     (if result?
       (try
         (invoke-expression! context expression parameters arguments)
         (finally (java.lang.ref.Reference/reachabilityFence locals)))
       (locking context
         (when-not (contains? @prepared-adapters qualified-name)
           (binding [runtime/*source-only-registration?* true]
             (register! context {:kind :fn :name name :qualified-name qualified-name
                                 :declaration-key [:fn name] :return :void
                                 :args parameters :body [expression]}))
           (swap! prepared-adapters conj qualified-name))
         (try
           (runtime/invoke! qualified-name arguments)
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
        reference (cond
                    (qualified-symbol? container) container
                    (and (seq? container) (qualified-symbol? (first container)))
                    (first container))
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
  (let [{:keys [context container]} (member-owner receiver)
        member-name (native-member-name member)
        ordinary (list '(field __aguafria_jvm :fieldView) 'input_0 member-name)
        type (list 'type container)
        method? (when context
                  (list 'aguafria.keyword/switch
                        (list 'aguafria.keyword/typeInfo type)
                        (list 'case
                              (mapv #(list 'aguafria.zig/enum-literal (str ".@\"" % "\""))
                                    ["struct" "union" "enum" "opaque"])
                              (list 'and
                                    (list 'aguafria.keyword/hasDecl type member-name)
                                    (list 'aguafria.keyword/==
                                          (list 'aguafria.keyword/typeInfo
                                                (list 'aguafria.keyword/TypeOf
                                                      (list 'aguafria.keyword/field type member-name)))
                                          '(aguafria.zig/enum-literal ".@\"fn\""))))
                        (list 'aguafria.zig/case-else false)))]
    {:module (if context (ns-name context) 'aguafria.jvm.field-storage)
     ;; @hasDecl and the method reference must be analyzed in their defining
     ;; Zig file: an imported helper cannot see a private declaration.
     :expression (if context
                   (list 'if (list 'aguafria.keyword/comptime method?)
                         '((field __aguafria_jvm :boundMethod))
                         ordinary)
                   ordinary)
     :types [address]}))

(defn- method-plan
  [receiver-type member mutable? native? {:keys [parameters expression-arguments]}]
  (let [address-name 'receiver_address
        pointer (list 'aguafria.keyword/as
                      (list 'aguafria.keyword/ptrFromInt address-name)
                      [(if mutable? :* :*const) receiver-type])
        target (if native? (list 'aguafria.zig/deref pointer) (first expression-arguments))
        module (symbol (str "aguafria.jvm.method-"
                            (token [receiver-type member mutable? native?])))]
    {:context (or (:context (member-owner receiver-type))
                  (find-ns module) (create-ns module))
     :expression (apply list (list 'aguafria.zig/field target member)
                        (if native? expression-arguments (rest expression-arguments)))
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
        accessor (clojure.core/symbol (str "__jvm_member_" (token [field field-type mutable?])))
        qualified-name (clojure.core/symbol (str namespace-name) (str accessor))
        pointer (list 'aguafria.keyword/as
                      (list 'aguafria.keyword/ptrCast (list 'aguafria.keyword/& field))
                      [(if mutable? :* :*const) [:array 1 field-type]])]
    (locking context
      (when-not (contains? @prepared-adapters qualified-name)
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
        {:keys [module expression]}
        (field-view-plan (value/qualified-type receiver) member (:type (first parameters)))
        context (or (find-ns module) (create-ns module))
        result (invoke-expression! context expression parameters arguments 'borrowedResult)]
    (if (= {"__aguafria_bound_method" true} result)
      (bound-method receiver member)
      (value/retain-owners! result [receiver storage]))))

(defn- ordinary-array-type [receiver]
  (when (value/zig-value? receiver)
    (let [type (value/qualified-type receiver)]
      (when (and (vector? type) (= :array (first type)) (= 3 (count type)))
        type))))

(defn- runtime-array-operation!
  [receiver operation operands]
  (let [type (ordinary-array-type receiver)
        pointer (value/array-elements-pointer receiver)
        inputs (into [pointer (second type)] operands)
        {:keys [expression-arguments parameters arguments]}
        (call-inputs (cons {:type :anytype} (repeat (dec (count inputs)) {:type :usize})) inputs)
        context (or (find-ns 'aguafria.jvm.array-storage)
                    (create-ns 'aguafria.jvm.array-storage))
        expression (apply list (list 'field '__aguafria_jvm operation) expression-arguments)
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

(defn invoke-syntax!
  "Execute a Zig builtin or value expression through the shared native bridge.
  Comptime parameters stay in source; runtime operands retain their Zig types."
  [{:keys [symbol signature param-count minimum-param-count] :as syntax} arguments]
  (when (or (and param-count (not= param-count (count arguments)))
            (and minimum-param-count (< (count arguments) minimum-param-count)))
    (throw (ex-info "Wrong number of arguments for Zig call"
                    {:function symbol :actual (count arguments)
                     :expected param-count :minimum minimum-param-count})))
  (cond
    (= 'assoc! (:name syntax))
    (assoc-native! (first arguments) (rest arguments))

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
         (some #(and (value/zig-value? %)
                     (not= :comptime_int (value/qualified-type %)))
               (rest arguments)))
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
                              (some #(and (value/zig-value? %)
                                          (not (#{:comptime_int} (value/qualified-type %))))
                                    (rest arguments)))
          declarations
          (cond
            runtime-slice?
            (cons {:type :anytype} (repeat (dec (count arguments)) {:type :usize}))

            handler-plan
            (mapv #(hash-map :type %) (:types handler-plan))

            (and signature (not (re-find #"\.\.\." signature)))
            (builtin-arguments signature)

            :else
            ;; Genuine source literals retain their comptime context. Native
            ;; handles still become typed runtime parameters in call-inputs.
            (repeat (count arguments)
                    {:type :anytype
                     :properties {:jvm/literal? (or (#{:syntax :operator} (:kind syntax))
                                                    (:literal-arguments? syntax))}}))
          operands (or (:arguments handler-plan) arguments)
          storage-source? (and (= 'slice (:name syntax)) (value/zig-value? receiver)
                               (not (comptime-expression receiver)))
          {:keys [expression-arguments parameters arguments]}
          (if storage-source?
            ;; Slicing a copied array would return a dangling stack pointer.
            ;; Borrow the receiver's storage; retain it on the resulting view.
            (let [pointer (value/address-value receiver (= :var (:kind (value/info receiver))))
                  inputs (call-inputs declarations (cons pointer (rest arguments)))]
              (update inputs :expression-arguments
                      #(assoc % 0 (list 'aguafria.zig/deref (first %)))))
            (call-inputs declarations operands))
          namespace-name (clojure.core/symbol (str "aguafria.jvm.expression-" (token syntax)))
          context (or (find-ns namespace-name) (create-ns namespace-name))
          expression-arguments (if (:float-literals? handler-plan)
                                 (mapv #(list '(field __aguafria_jvm :parseComptimeFloat) %)
                                       expression-arguments)
                                 expression-arguments)
          expression (if native-field?
                       (list '(field __aguafria_jvm :lookupField)
                             (first expression-arguments) (name member))
                       (apply list symbol expression-arguments))
          result (invoke-expression! context expression parameters arguments
                                     (cond
                                       (and (#{'field 'index 'slice 'deref} (:name syntax))
                                            (comptime-expression receiver)
                                            (empty? parameters))
                                       'comptimeExpressionResult
                                       (:result-type handler-plan) 'inspectResult
                                       (= 'field (:name syntax)) 'fieldResult
                                       :else 'result))]
      (cond
        (:result-type handler-plan)
        (value/native-value {:kind :const :type (:result-type handler-plan)}
                            (constantly {:representation :scalar :value result}))

        (and (= 'field (:name syntax)) (= {"__aguafria_bound_method" true} result))
        (bound-method receiver member)

        :else (value/retain-owners! result [receiver])))))

(defn- signature-arguments [signature]
  (mapv (fn [parameter]
          (cond-> parameter
            ;; Preserve the sentinel when passing a source string as a C string.
            (= [:sentinel-const :u8 0] (:type parameter))
            (assoc-in [:properties :jvm/literal?] true)))
        (:args (signature/declaration signature))))

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
             (when-let [source (reference-source reference)]
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
   (let [{syntax :aguafria/token
          reference :aguafria/zig-reference
          declaration :aguafria/declaration} var-meta
         parameters (cond
                      declaration (:args declaration)
                      (re-find #"\bfn\s" (or (:signature reference) ""))
                      (signature-arguments (:signature reference))
                      (and (:signature syntax) (not (re-find #"\.\.\." (:signature syntax))))
                      (signature-arguments
                       (str/replace-first (:signature syntax) #"^@[A-Za-z0-9_]+" "fn builtin")))]
     (if (and parameters argument-count)
       (call-declarations parameters argument-count)
       parameters))))

(defn invoke-generic!
  "Specialize a registered generic call with the actual JVM arguments."
  [declaration arguments]
  (let [{:keys [expression-arguments parameters arguments]}
        (call-inputs (:args declaration) arguments)]
    (invoke-expression! (the-ns (symbol (:module declaration)))
                        (apply list (symbol (:module declaration) (str (:name declaration)))
                               expression-arguments)
                        parameters arguments)))

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
                                              (signature-arguments (:signature reference)) arguments)
             {:keys [expression-arguments parameters arguments]}
             (call-inputs declarations arguments)
             namespace-name (symbol (str "aguafria.jvm.imported-" (token reference)))
             namespace (or (find-ns namespace-name) (create-ns namespace-name))
             expression (with-meta (apply list (:symbol reference) expression-arguments)
                          {:aguafria/zig-reference reference})]
         (invoke-expression! namespace expression parameters arguments))))))

(defn- slice-signature-plan [receiver address indices]
  (let [ordinary-array? (and (vector? receiver) (= :array (first receiver)) (= 3 (count receiver)))
        runtime-index? (some #(not= :comptime_int (:type %)) indices)
        mutable? (= :* (first address))]
    (if (and ordinary-array? runtime-index?)
      {:module 'aguafria.jvm.array-storage
       :expression '((field __aguafria_jvm :runtimeArraySlice) input_0 input_1 input_2 input_3)
       :types [[(if mutable? :many :many-const) (nth receiver 2)] :usize :usize :usize]}
      (let [syntax {:kind :syntax :name 'slice :symbol 'aguafria.zig/slice}
            operands (into [(->PreparedOperand address)]
                           (map #(if (map? %) (:literal %) (->PreparedOperand %))) indices)
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

(defn precompile-storage!
  "Prepare borrowed field/index views from compiler-confirmed receiver and
  address types. Uses the normal view helpers without allocating a receiver."
  [{:keys [kind receiver address member indices] :as signature}]
  (cond
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
              module (symbol (str "aguafria.jvm.expression-" (token syntax)))
              context (or (find-ns module) (create-ns module))
              expression (list 'aguafria.zig/field (type-expression type) member)]
          (precompile-expression! (prepare-expression! context expression [] 'fieldResult))))
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
              {:module 'aguafria.jvm.array-storage
               :expression '((field __aguafria_jvm :arrayIndexView) input_0 input_1 input_2)
               :types [[(if mutable? :many :many-const) (nth receiver 2)] :usize :usize]}
              (let [index (first indices)
                    comptime? (and (map? index) (contains? index :comptime))]
                (index-plan receiver (->PreparedOperand address)
                            (if comptime? (:comptime index) (->PreparedOperand :usize))
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
      (assoc signature :status :prepared))))

(defn- prepared-operand [argument declaration]
  (cond
    (= :null argument) nil
    (= :undefined argument) 'aguafria.keyword/undefined
    (and (map? argument) (contains? argument :tuple))
    (mapv #(prepared-operand % nil) (:tuple argument))
    (and (map? argument) (contains? argument :comptime-type))
    (->PreparedType (:comptime-type argument))
    (and (map? argument) (contains? argument :literal)) (:literal argument)
    (and (map? argument) (contains? argument :comptime))
    (if (#{:type 'type} (:type declaration))
      (->PreparedType (:comptime argument))
      (:comptime argument))
    :else
    (let [type (constructor-type argument)]
      (emitter/emit-type type)
      (->PreparedOperand type))))

(defn- precompile-inputs
  ([arguments declarations] (precompile-inputs arguments declarations nil))
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
     (cond-> (select-keys (call-inputs declarations operands) [:parameters :expression-arguments])
       (some #(get-in % [:properties :jvm/peer-float?]) declarations)
       (assoc :inspection-types
              (into #{} (keep #(when (instance? PreparedOperand %) (:type %))) operands))))))

(defn precompile-method!
  "Prepare a member call from compiler-observed receiver/argument types.
  Shares the normal JVM method planner; never constructs or invokes a receiver."
  [{:keys [receiver address member args] :as signature}]
  (let [native? (not (and (map? receiver) (contains? receiver :comptime-type)))
        receiver-type (emitter/qualify-type *ns* (if native? receiver (:comptime-type receiver)))
        mutable? (and native? (= :* (first address)))
        operands (if native? args (into [receiver] args))
        inputs (precompile-inputs operands
                                  (repeat (count operands)
                                          {:type :anytype :properties {:jvm/literal? true}}))
        {:keys [context expression parameters]}
        (method-plan receiver-type member mutable? native? inputs)]
    (precompile-expression! (prepare-expression! context expression parameters 'result))
    (assoc signature :status :prepared)))

(defn precompile-assignment!
  "Compile the ordinary assignment adapter using types observed by Zig, without
  making storage or performing the write."
  [{:keys [function operation target operand] :as signature}]
  (if (= "=" operation)
    (let [preparation
          (cond
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
    (let [operand-type (when (and (map? operand) (number? (:literal operand)))
                         (handlers/assignment-signature-operand-type target operation))
          {:keys [expression-arguments parameters]}
          (precompile-inputs [[:* target] operand]
                             [{:type :anytype}
                              (if operand-type {:type operand-type}
                                  {:type :anytype :properties {:jvm/literal? true}})])
          expression (list function (list 'aguafria.zig/deref (first expression-arguments))
                           (second expression-arguments))
          module (symbol (str "aguafria.jvm.assignment-" (token [expression parameters])))
          context (or (find-ns module) (create-ns module))
          qualified-name (symbol (str module) "assign")]
      (locking context
        (when-not (contains? @prepared-adapters qualified-name)
          (binding [runtime/*source-only-registration?* true]
            (register! context {:kind :fn :name 'assign :qualified-name qualified-name
                                :declaration-key [:fn 'assign] :return :void
                                :args parameters :body [expression]}))
          (swap! prepared-adapters conj qualified-name))
        (runtime/precompile-function! qualified-name))
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
         false)))

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
    (when (= function 'aguafria.zig/string-literal)
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
        generic? (and (= :fn (:kind declaration))
                      (some #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                 (= "comptime" (get-in % [:properties :zig/prefix])))
                            (:args declaration)))
        supported? (or generic?
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
                           (not (contains? #{#{:literal :type} #{:comptime-type} #{:tuple}}
                                           (set (keys %))))
                           (not (and (= #{:comptime} (set (keys %)))
                                     (or (boolean? (:comptime %))
                                         (native-literal? (:comptime %)))))) args))
      (throw (ex-info "Operator signatures require native operand types, not source literals"
                      {:call call})))
    (let [native-parameters (or (:args declaration)
                                (when imported? (signature-arguments (:signature zig-reference))))
          variadic? (boolean (some #(get-in % [:properties :zig/variadic]) native-parameters))
          expected (or (:param-count syntax)
                       (when declaration (count (:args declaration)))
                       (when imported? (count (signature-arguments (:signature zig-reference)))))
          minimum (if variadic? (dec (count native-parameters)) (:minimum-param-count syntax))]
      (when (or (and expected (not variadic?) (not= expected (count args)))
                (and minimum (< (count args) minimum)))
        (throw (ex-info "Wrong number of precompilation arguments"
                        {:call call :expected expected :minimum minimum}))))
    (let [argument-declarations
          (cond
            declaration (:args declaration)
            imported? (signature-arguments (:signature zig-reference))
            (and (:signature syntax) (not (re-find #"\.\.\." (:signature syntax))))
            (builtin-arguments (:signature syntax))
            :else (repeat (count args)
                          {:type :anytype
                           :properties {:jvm/literal? (or (#{:syntax :operator} (:kind syntax))
                                                          (:literal-arguments? syntax))}}))
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
          {:keys [parameters expression-arguments inspection-types]}
          (precompile-inputs (or (:types handler-plan) args)
                             (if handler-plan
                               (mapv #(hash-map :type %) (:types handler-plan))
                               argument-declarations)
                             (when imported? zig-reference))
          expression-arguments (if (:float-literals? handler-plan)
                                 (mapv #(list '(field __aguafria_jvm :parseComptimeFloat) %)
                                       expression-arguments)
                                 expression-arguments)
          module (cond
                   declaration (symbol (:module declaration))
                   imported? (symbol (str "aguafria.jvm.imported-" (token zig-reference)))
                   :else (symbol (str "aguafria.jvm.expression-" (token syntax))))
          context (or (find-ns module) (create-ns module))
          expression (cond
                       declaration (apply list (symbol (:module declaration) (str (:name declaration)))
                                          expression-arguments)
                       imported? (with-meta (apply list function expression-arguments)
                                   {:aguafria/zig-reference zig-reference})
                       :else (apply list function expression-arguments))
          adapter (prepare-expression! context expression parameters
                                       (cond
                                         (:result-type handler-plan) 'inspectResult
                                         (= 'field (:name syntax)) 'fieldResult
                                         :else 'result))]
      (precompile-expression! adapter)
      (doseq [type inspection-types]
        (precompile-inspection! context type))
      (let [return-type (or (:return declaration)
                            (when imported?
                              (:return (signature/declaration (:signature zig-reference)))))]
        ;; Native C integer aliases retain their compiler identity (c_int is
        ;; not renamed to a guessed host integer). Their JVM value reader uses
        ;; the existing compiler reflection adapter instead of a scalar codec.
        (when (and (keyword? return-type) (str/starts-with? (name return-type) "c_"))
          (precompile-inspection! context return-type)))
      {:function function :args args :status :prepared})))
