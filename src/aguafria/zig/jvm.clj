(ns aguafria.zig.jvm
  "Native specialization and value transport for Clojure and Java callers.

  Comptime inputs remain in Zig source, never in an invalid C ABI trampoline.
  Concrete call adapters run in the same live module as the original function."
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util ArrayList HexFormat]))

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
            (swap! streams conj (capture-stream! 1 *out*))
            (swap! streams conj (capture-stream! 2 *err*))
            (invoke)
            (finally
              ;; Restore both process streams before touching a Clojure writer.
              ;; Its destination may itself ultimately write to stdout/stderr.
              (doseq [{:keys [descriptor backup]} @streams]
                (try
                  (fd-call :dup2 (int backup) (int descriptor))
                  (finally (fd-call :close (int backup)))))
              (doseq [{:keys [path writer]} @streams]
                (try
                  (with-open [reader (io/reader (.toFile path) :encoding "UTF-8")]
                    (io/copy reader writer)
                    (.flush ^java.io.Writer writer))
                  (finally (Files/delete path)))))))))))

(defn- token [value]
  (subs (.formatHex (HexFormat/of)
                    (.digest (MessageDigest/getInstance "SHA-256")
                             (.getBytes (pr-str value) "UTF-8")))
        0 24))

(defn- register! [namespace descriptor]
  (binding [emitter/*registered-declaration-names*
            (set (map :name (:definitions (runtime/module-info (ns-name namespace)))))]
    (runtime/register-declaration!
     (emitter/prepare-declaration
      namespace
      (merge {:module (str (ns-name namespace)) :public? false :export? false
              :implicit-return? true}
             descriptor)))))

(declare coerce!)

(defn- expression-result
  [namespace expression parameters result]
  (cond
    (and (map? result) (= #{:aguafria.jvm/pointer} (set (keys result))))
    (let [{:keys [address type]} (:aguafria.jvm/pointer result)]
      (value/->ZigPointer address type))

    (and (map? result) (= #{:aguafria.jvm/error} (set (keys result))))
    (let [{:keys [name members]} (:aguafria.jvm/error result)]
      (value/->ZigError name (if members [:error-set (mapv keyword members)] :anyerror)))

    (and (map? result) (= #{:aguafria.jvm/type} (set (keys result))))
    ;; A Zig type cannot depend on runtime operand values. Preserve the type
    ;; expression with typed undefined operands, rather than degrading it to a
    ;; string that cannot be passed back to another native call.
    (let [type (emitter/qualify-form
                namespace
                (walk/postwalk-replace
                 (into {} (map (fn [{:keys [name type]}]
                                 [name (list 'aguafria.keyword/as
                                             'aguafria.keyword/undefined type)]))
                       parameters)
                 expression))]
      (value/zig-type {:kind :type :type type :zig-name (:aguafria.jvm/type result)}
                      #(coerce! % type)))
    :else result))

(defn- invoke-expression!
  ([namespace expression parameters arguments]
   (invoke-expression! namespace expression parameters arguments 'result))
  ([namespace expression parameters arguments result-writer]
  (binding [runtime/*native-test-context?*
            (or runtime/*native-test-context?*
                (some #(and (value/zig-value? %)
                            (= :test (:execution-context (value/info %)))) arguments))]
    (locking namespace
    (let [module (str (ns-name namespace))
          helper-source (slurp (io/resource "aguafria/jvm_result.zig"))
          call-name (symbol (str "__jvm_call_" (token [expression parameters helper-source result-writer])))
          release-name '__jvm_release
          helper-name '__aguafria_jvm
          adapter-key [module call-name expression parameters helper-source result-writer]]
      (when-not (contains? @prepared-adapters adapter-key)
        (binding [runtime/*source-only-registration?* true]
          (register! namespace
                     {:kind :raw :name helper-name
                      :declaration-key [:raw helper-name] :code helper-source})
          (register! namespace
                     {:kind :fn :name release-name
                      :qualified-name (symbol module (str release-name))
                      :declaration-key [:fn release-name]
                      :return :void :args [{:name 'address :type :usize}]
                      :body ['((field __aguafria_jvm :release) address)]})
          (register! namespace
                     {:kind :fn :name call-name
                      :qualified-name (symbol module (str call-name))
                      :declaration-key [:fn call-name]
                      :return :usize :args parameters
                      :body [(list (list 'field helper-name (keyword result-writer)) expression)]})))
      (let [address (runtime/invoke! (symbol module (str call-name)) arguments)]
        (try
          (let [result (edn/read-string
                        (.getString (.reinterpret (MemorySegment/ofAddress address)
                                                  Long/MAX_VALUE) 0))]
            (swap! prepared-adapters conj adapter-key)
            (expression-result namespace expression parameters result))
          (finally
            (runtime/invoke! (symbol module (str release-name)) [address])))))))))

(defn inspect-value!
  "Decode an otherwise unschematized native value using Zig's own reflection.
  Read the current storage; retain the owner and never follow unbounded pointers."
  [native-value]
  (requiring-resolve 'aguafria.zig/deref)
  (let [type (value/qualified-type native-value)
        namespace-name (symbol (str "aguafria.jvm.inspect-" (token type)))
        context (or (some-> (:module (value/info native-value)) symbol find-ns)
                    (find-ns namespace-name)
                    (create-ns namespace-name))
        pointer (list 'aguafria.keyword/as
                      '(aguafria.keyword/ptrFromInt address)
                      [:*const type])]
    (binding [runtime/*native-test-context?*
              (or runtime/*native-test-context?*
                  (= :test (:execution-context (value/info native-value))))]
      (try
        (let [result (invoke-expression!
                      context (list 'aguafria.zig/deref pointer)
                      [{:name 'address :type :usize}]
                      [(.address ^MemorySegment (value/segment native-value))]
                      'inspectResult)]
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

      (vector? type)
      (mapv constructor-type type)

      (map? type)
      (into (empty type) (map (fn [[key item]] [key (constructor-type item)])) type)

      :else type)))

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

(defn coerce!
  "Coerce a JVM value using a cached in-process Zig adapter. Scalar results
  have their ordinary JVM representation; composites retain owned native
  storage (including slice backing). The adapter is keyed by types, not values."
  [argument type]
  (let [type (constructor-type type)
        type (if (and (vector? type)
                      (contains? #{:array :array-sentinel} (first type))
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
        literal? (native-literal? argument)
        input-type (if native? (value/qualified-type argument) type)
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
      (or error? literal?)
      (do
        (binding [runtime/*source-only-registration?* true]
          (register! context
                     {:kind :fn :name 'coerce :qualified-name qualified-name
                      :declaration-key [:fn 'coerce] :return type :args []
                      :body [(list 'aguafria.keyword/return
                                   (list 'aguafria.keyword/as
                                         (if error? (value/error-form argument) argument) type))]}))
        (runtime/invoke! qualified-name []))

      (#{:comptime_int :comptime_float} type)
      (invoke-expression! context (list 'aguafria.keyword/as argument type) [] [])

      (and native? (= type input-type))
      (do (value/realize! argument) argument)

      :else
      (do
        (locking context
          (when-not (contains? @prepared-coercions [type input-type])
            (binding [runtime/*source-only-registration?* true]
              (register! context
                         {:kind :fn :name 'coerce :qualified-name qualified-name
                          :declaration-key [:fn 'coerce]
                          :return type :args [{:name 'input :type input-type}]
                          :body [(list 'aguafria.keyword/return
                                       (list 'aguafria.keyword/as expression type))]}))
            (swap! prepared-coercions conj [type input-type])))
        (runtime/invoke! qualified-name [argument])))))

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
   (let [type (constructor-type (or type (mutable-type argument)))
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
  "Execute a compound assignment against owned native storage in Zig."
  [syntax target operand]
  (when-not (and (value/zig-value? target) (= :var (:kind (value/info target))))
    (throw (ex-info "Assignment requires a mutable native value; create it with ak/var"
                    {:target target})))
  (let [type (value/type target)
        module (symbol (str "aguafria.jvm.assignment-" (token [syntax type])))
        context (or (find-ns module) (create-ns module))
        qualified-name (symbol (str module) "assign")
        pointer (list 'aguafria.keyword/as
                      (list 'aguafria.keyword/ptrFromInt 'address) [:* type])]
    (locking context
      (when-not (contains? @prepared-adapters qualified-name)
        (binding [runtime/*source-only-registration?* true]
          (register! context
                     {:kind :fn :name 'assign :qualified-name qualified-name
                      :declaration-key [:fn 'assign] :return :void
                      :args [{:name 'address :type :usize}
                             {:name 'operand :type type}]
                      :body [(list (:symbol syntax)
                                   (list 'aguafria.zig/deref pointer) 'operand)]}))
        (swap! prepared-adapters conj qualified-name))
      (runtime/invoke! qualified-name
                       [(.address ^MemorySegment (value/segment target))
                        (coerce! operand type)]))))

(defn- call-inputs [argument-declarations arguments]
  (when-not (= (count argument-declarations) (count arguments))
    (throw (ex-info "Wrong number of arguments for Zig function"
                    {:expected (count argument-declarations) :actual (count arguments)})))
  (let [parameters (atom [])
        values (atom [])
        type-arguments (into {}
                             (keep (fn [[declaration argument]]
                                     (when (#{:type 'type} (:type declaration))
                                       [(:name declaration) argument])))
                             (map vector argument-declarations arguments))]
    (letfn [(lift [argument expected literal?]
              (let [expected (get type-arguments expected expected)
                    inferred (cond
                               (value/zig-value? argument)
                               (value/qualified-type argument)
                               (boolean? argument) :bool
                               (instance? Byte argument) :i8
                               (instance? Short argument) :i16
                               (instance? Integer argument) :i32
                               (instance? Float argument) :f32
                               (integer? argument) :i64
                               (float? argument) :f64
                               (string? argument) [:slice-const :u8])
                    zig-type (if (or (#{:bool :f16 :f32 :f64 :f80 :f128 :isize :usize} expected)
                                     (and (keyword? expected)
                                          (re-matches #"[iu][0-9]+" (name expected))))
                               expected
                               inferred)]
                (cond
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
                  (and literal? (or (number? argument) (boolean? argument)
                                    (string? argument)))
                  argument
                  zig-type
                  (let [name (symbol (str "input_" (count @parameters)))]
                    (swap! parameters conj {:name name :type zig-type})
                    (swap! values conj argument)
                    name)
                  (value/zig-type? argument)
                  (list 'type (constructor-type argument))
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
               (if (or (= "comptime" (:zig/prefix properties))
                       (#{:type 'type :comptime_int :comptime_float} type))
                 (if (value/zig-type? argument)
                   (list 'type (constructor-type argument))
                   argument)
                 (lift argument type (:jvm/literal? properties))))
             argument-declarations arguments)
       :parameters @parameters
       :arguments @values})))

(declare signature-arguments)

(defn- bound-method [receiver member]
  (fn [& arguments]
    (let [native? (value/zig-value? receiver)
          receiver-type (when native? (value/qualified-type receiver))
          mutable? (and native? (= :var (:kind (value/info receiver))))
          operands (if native? arguments (cons receiver arguments))
          {:keys [expression-arguments parameters arguments]}
          (call-inputs (repeat (count operands)
                               {:type :anytype :properties {:jvm/literal? true}})
                       operands)
          address-name 'receiver_address
          pointer (list 'aguafria.keyword/as
                        (list 'aguafria.keyword/ptrFromInt address-name)
                        [(if mutable? :* :*const) receiver-type])
          target (if native? (list 'aguafria.zig/deref pointer) (first expression-arguments))
          expression (apply list
                            (list 'aguafria.zig/field target member)
                            (if native? expression-arguments (rest expression-arguments)))
          namespace-name (symbol (str "aguafria.jvm.method-"
                                      (token [(or receiver-type (meta receiver)) member mutable?])))
          context (or (find-ns namespace-name) (create-ns namespace-name))]
      (binding [runtime/*native-test-context?*
                (or runtime/*native-test-context?*
                    (and native? (= :test (:execution-context (value/info receiver)))))]
        (try
          (invoke-expression! context expression
                              (if native? (into [{:name address-name :type :usize}] parameters) parameters)
                              (if native?
                                (into [(.address ^MemorySegment (value/segment receiver))] arguments)
                                arguments))
          (finally (java.lang.ref.Reference/reachabilityFence receiver)))))))

(defn invoke-syntax!
  "Execute a Zig builtin or value expression through the shared native bridge.
  Comptime parameters stay in source; runtime operands retain their Zig types."
  [{:keys [symbol signature param-count minimum-param-count] :as syntax} arguments]
  (when (or (and param-count (not= param-count (count arguments)))
            (and minimum-param-count (< (count arguments) minimum-param-count)))
    (throw (ex-info "Wrong number of arguments for Zig call"
                    {:function symbol :actual (count arguments)
                     :expected param-count :minimum minimum-param-count})))
  (if (contains? #{'init 'array-init} (:name syntax))
    (apply coerce! arguments)
    (let [receiver (first arguments)
          member (second arguments)
          native-field? (and (= 'field (:name syntax))
                            (or (value/zig-value? receiver)
                                (:aguafria/zig-reference (meta receiver))))
          declarations
          (if (and signature (not (re-find #"\.\.\." signature)))
            (signature-arguments
             (str/replace-first signature #"^@[A-Za-z0-9_]+" "fn builtin"))
            ;; Structural forms consume source literals (e.g. tuple indices and
            ;; field names), not an invented all-i64 function signature. Native
            ;; handles still become typed runtime parameters in call-inputs.
            (repeat (count arguments)
                    {:type :anytype
                     :properties {:jvm/literal? (= :syntax (:kind syntax))}}))
          {:keys [expression-arguments parameters arguments]}
          (call-inputs declarations arguments)
          namespace-name (clojure.core/symbol (str "aguafria.jvm.expression-" (token syntax)))
          context (or (find-ns namespace-name) (create-ns namespace-name))
          expression (if native-field?
                       (list '(field __aguafria_jvm :lookupField)
                             (first expression-arguments) (name member))
                       (apply list symbol expression-arguments))
          result (invoke-expression! context expression parameters arguments
                                     (if (= 'field (:name syntax)) 'fieldResult 'result))]
      (if (and native-field? (= {"__aguafria_bound_method" true} result))
        (bound-method receiver member)
        result))))

(def ^:private signature-arguments
  (memoize
   (fn [signature]
     (when-not (seq signature)
       (throw (ex-info "Imported function has no Zig signature" {})))
     (let [source (str signature (if (re-find #"\bextern\b" signature) ";" " { unreachable; }"))
           parsed (convert/parse-source source)
           converted (convert/convert-file "signature.zig" {::convert/parsed parsed})
           form (first (filter #(#{"defn" "defn-" "defextern"}
                                  (some-> % first name)) (:forms converted)))
           bindings (first (filter vector? (drop 3 form)))]
       (when-not form
         (throw (ex-info "Cannot read imported function signature" {:signature signature})))
       (emitter/parse-typed-bindings bindings)))))

(defn invoke-generic!
  "Specialize a registered generic call with the actual JVM arguments."
  [declaration arguments]
  (let [{:keys [expression-arguments parameters arguments]}
        (call-inputs (:args declaration) arguments)]
    (invoke-expression! (the-ns (symbol (:module declaration)))
                        (apply list (:name declaration) expression-arguments)
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
       (let [{:keys [expression-arguments parameters arguments]}
           (call-inputs (signature-arguments (:signature reference)) arguments)
           namespace-name (symbol (str "aguafria.jvm.imported-" (token reference)))
           namespace (or (find-ns namespace-name) (create-ns namespace-name))
           expression (with-meta (apply list (:symbol reference) expression-arguments)
                        {:aguafria/zig-reference reference})]
         (invoke-expression! namespace expression parameters arguments))))))
