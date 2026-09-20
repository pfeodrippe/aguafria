(ns aguafria.zig.jvm
  "Native specialization and value transport for Clojure and Java callers.

  Comptime inputs remain in Zig source, never in an invalid C ABI trampoline.
  Concrete call adapters run in the same live module as the original function."
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util ArrayList HexFormat]))

(defonce ^:private output-lock (Object.))
(defonce ^:private prepared-adapters (atom #{}))
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
  (runtime/register-declaration!
   (emitter/prepare-declaration
    namespace
    (merge {:module (str (ns-name namespace)) :public? false :export? false
            :implicit-return? true}
           descriptor))))

(defn- invoke-expression! [namespace expression parameters arguments]
  (locking namespace
    (let [module (str (ns-name namespace))
          call-name (symbol (str "__jvm_call_" (token [expression parameters])))
          release-name '__jvm_release
          helper-name '__aguafria_jvm
          helper-source (slurp (io/resource "aguafria/jvm_result.zig"))
          adapter-key [module expression parameters helper-source]]
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
                      :body [(list '(field __aguafria_jvm :result) expression)]})))
      (let [address (runtime/invoke! (symbol module (str call-name)) arguments)]
        (try
          (let [result (edn/read-string
                        (.getString (.reinterpret (MemorySegment/ofAddress address)
                                                  Long/MAX_VALUE) 0))]
            (swap! prepared-adapters conj adapter-key)
            result)
          (finally
            (runtime/invoke! (symbol module (str release-name)) [address])))))))

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
    (letfn [(lift [argument expected]
              (let [expected (get type-arguments expected expected)
                    inferred (cond
                               (value/zig-value? argument)
                               (let [type (value/type argument)]
                                 (if (and (symbol? type) (nil? (clojure.core/namespace type)))
                                   (symbol (:module (value/info argument)) (name type))
                                   type))
                               (boolean? argument) :bool
                               (integer? argument) :i64
                               (float? argument) :f64
                               (string? argument) [:slice-const :u8])
                    zig-type (if (or (#{:bool :f16 :f32 :f64 :f80 :f128 :isize :usize} expected)
                                     (and (keyword? expected)
                                          (re-matches #"[iu][0-9]+" (name expected))))
                               expected
                               inferred)]
                (cond
                  zig-type
                  (let [name (symbol (str "input_" (count @parameters)))]
                    (swap! parameters conj {:name name :type zig-type})
                    (swap! values conj argument)
                    name)
                  (vector? argument) (mapv #(lift % nil) argument)
                  (map? argument) (into (empty argument)
                                        (map (fn [[key item]] [key (lift item nil)])) argument)
                  (or (keyword? argument) (nil? argument)) argument
                  (var? argument) (with-meta (symbol (str (ns-name (:ns (meta argument))))
                                                     (str (:name (meta argument))))
                                   (select-keys (meta argument) [:aguafria/zig-reference]))
                  :else (throw (ex-info "Cannot pass this value to native Zig"
                                        {:argument argument :type (type argument)})))))]
      {:expression-arguments
       (mapv (fn [{:keys [properties type]} argument]
               (if (or (= "comptime" (:zig/prefix properties))
                       (#{:type 'type} type))
                 argument
                 (lift argument type)))
             argument-declarations arguments)
       :parameters @parameters
       :arguments @values})))

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
     (let [{:keys [expression-arguments parameters arguments]}
           (call-inputs (signature-arguments (:signature reference)) arguments)
           namespace-name (symbol (str "aguafria.jvm.imported-" (token reference)))
           namespace (or (find-ns namespace-name) (create-ns namespace-name))
           expression (with-meta (apply list (:symbol reference) expression-arguments)
                        {:aguafria/zig-reference reference})]
       (invoke-expression! namespace expression parameters arguments)))))
