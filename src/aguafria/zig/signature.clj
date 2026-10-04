(ns aguafria.zig.signature
  "Read Zig function signatures for native emission and JVM call adapters."
  (:require [aguafria.zig.artifact :as artifact]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def declaration
  (memoize
   (fn [signature]
     (when-not (seq signature)
       (throw (ex-info "Imported function has no Zig signature" {})))
     ;; Builtin documentation uses anytype as a result placeholder, which is
     ;; not a legal Zig function return type. Do not report it as a real void.
     (let [result-placeholder? (boolean (re-find #"\)\s+anytype$" signature))
           signature (str/replace signature #"\)\s+anytype$" ") void")
           source (str signature (if (re-find #"\bextern\b" signature) ";" " { unreachable; }"))
           ;; Conversion itself uses the emitter; resolve these at call time
           ;; so loading the shared signature reader does not create a cycle.
           parsed ((requiring-resolve 'aguafria.zig.convert/parse-source) source)
           converted ((requiring-resolve 'aguafria.zig.convert/convert-file)
                      "signature.zig" {:aguafria.zig.convert/parsed parsed})
           form (first (filter #(#{"defn" "defn-" "defextern"}
                                 (some-> % first name)) (:forms converted)))
           bindings (first (filter vector? (drop 3 form)))]
       (when-not form
         (throw (ex-info "Cannot read imported function signature" {:signature signature})))
       {:zig-name (:zig-name (first ((requiring-resolve 'aguafria.zig.convert/declaration-spans)
                                     parsed)))
        :return (when-not result-placeholder? (nth form 2))
        :args ((requiring-resolve 'aguafria.zig.emitter/parse-typed-bindings) bindings)}))))

(defn builtin-arguments [signature]
  (:args (declaration (str/replace-first signature #"^@[A-Za-z0-9_]+" "fn builtin"))))

(defonce ^:private selected-std-signatures (atom {}))

(defn- reflect-callable [reference]
  (let [fingerprint (requiring-resolve 'aguafria.zig.runtime/adapter-fingerprint)
        module (symbol (str "aguafria.jvm.reference-signature-"
                            (subs (fingerprint reference) 0 24)))
        context (or (find-ns module) (create-ns module))
        expression (with-meta (:symbol reference) {:aguafria/zig-reference reference})
        prepare (requiring-resolve 'aguafria.zig.emitter/prepare-declaration)
        descriptor (prepare context {:kind :const :name '__aguafria_callable
                                     :declaration-key [:const '__aguafria_callable]
                                     :module (str module) :value expression
                                     :jvm-adapter? false})
        source-only (requiring-resolve 'aguafria.zig.runtime/*source-only-registration?*)
        _ (with-bindings {source-only true}
            ((requiring-resolve 'aguafria.zig.runtime/register-declaration!) descriptor))
        type-expression (list 'aguafria.keyword/TypeOf expression)
        identity (artifact/print-data type-expression)
        result ((requiring-resolve 'aguafria.zig.runtime/inspect-module!)
                module
                (fn [declarations]
                  {:files {"__aguafria_signature_probe.zig"
                           (slurp (io/resource "aguafria/operation_probe.zig"))}
                   :source (str ((requiring-resolve 'aguafria.zig.emitter/emit-module)
                                 module declarations)
                                "\nconst __aguafria_signature = @import(\"__aguafria_signature_probe.zig\").Inspector(.{ .{ struct {\n"
                                "    pub fn get() type { return @TypeOf(__aguafria_callable); }\n"
                                "}, " (artifact/print-data identity) " } });\n"
                                "comptime { __aguafria_signature.log(\"aguafria.signature:\" ++ "
                                "__aguafria_signature.callableSignature(@TypeOf(__aguafria_callable))); }\n")}))
        encoded (second (re-find #"\"aguafria\.operation\.hex:([0-9a-f]+)\""
                                 (or (:err result) "")))
        decoded (when encoded
                  (String. (.parseHex (java.util.HexFormat/of) encoded)
                           java.nio.charset.StandardCharsets/UTF_8))]
    (when (or (not (str/starts-with? (or decoded "") "aguafria.signature:"))
              (re-find #"(?m)error: (?!found compile log statement)" (or (:err result) "")))
      (throw (ex-info "Zig could not determine the imported callable's signature"
                      (assoc result :reference reference))))
    (edn/read-string (subs decoded (count "aguafria.signature:")))))

(defn callable-declaration
  "Read function syntax or ask Zig for the selected callable behind an alias.
  Alias inspection compiles types only; it never calls the imported function."
  [reference]
  (if (re-find #"\bfn\s" (or (:signature reference) ""))
    (declaration (:signature reference))
    (if (= :std (:kind reference))
      ;; The embedded std source is immutable. Target/build configuration can
      ;; select another alias or ABI, and is part of this bounded planning cache.
      (let [key [reference ((requiring-resolve 'aguafria.zig.runtime/configuration))]]
        (or (get @selected-std-signatures key)
            (locking selected-std-signatures
              (or (get @selected-std-signatures key)
                  (let [selected (reflect-callable reference)]
                    (swap! selected-std-signatures
                           #(assoc (if (< (count %) 64) % {}) key selected))
                    selected)))))
      (reflect-callable reference))))
