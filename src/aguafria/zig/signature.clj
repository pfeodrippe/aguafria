(ns aguafria.zig.signature
  "Read Zig function signatures for native emission and JVM call adapters."
  (:require [clojure.string :as str]))

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
