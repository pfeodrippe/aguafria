(ns aguafria.zig.std
  "EDN-backed loader and inspection API for Zig std Vars.

  Each Zig std namespace has a generated classpath entry point that installs
  its Vars from the catalog. Direct requires need no bootstrap namespace.
  `aguafria.std` additionally supports eager installation of the whole catalog."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- compiler-builtin-members
  []
  (let [zig ((requiring-resolve 'aguafria.zig.runtime/zig-executable))
        {:keys [exit out err]} (shell/sh zig "build-lib" "--show-builtin")]
    (when-not (zero? exit)
      (throw (ex-info "Cannot discover Zig's compiler-provided builtin module"
                      {:command [zig "build-lib" "--show-builtin"] :stderr err :exit exit})))
    (let [parsed ((requiring-resolve 'aguafria.zig.convert/parse-source) out)
          spans ((requiring-resolve 'aguafria.zig.convert/declaration-spans) parsed)]
      (into []
            (keep (fn [{:keys [start-byte end-byte] :as span}]
                    (let [source (String. ^bytes (:source-bytes parsed) (int start-byte)
                                          (int (- end-byte start-byte))
                                          java.nio.charset.StandardCharsets/UTF_8)]
                      (when (str/starts-with? source "pub const ")
                        (cond-> span
                          (re-find #"=\s*(?:true|false);$" source)
                          (assoc :type :bool))))))
            spans))))

(defn install-builtin!
  "Install real compiler-provided members, discovered from the pinned Zig.

  Public Vars emit @import(\"builtin\") references in the consuming compilation,
  so is_test/target/mode are never frozen to the discovery compiler's values.
  JVM inspection uses lazy native constants through the same value bridge."
  [target-ns]
  (let [backing-name 'aguafria.zig.import.compiler-builtin
        backing (or (find-ns backing-name) (create-ns backing-name))
        prepare (requiring-resolve 'aguafria.zig.emitter/prepare-declaration)
        register (requiring-resolve 'aguafria.zig.runtime/register-declaration!)
        root-value (requiring-resolve 'aguafria.zig.runtime/declaration-root-value)
        source-only (requiring-resolve 'aguafria.zig.runtime/*source-only-registration?*)]
    (with-bindings {source-only true}
      (doseq [{:keys [zig-name type]} (compiler-builtin-members)]
        (let [name (symbol zig-name)
              expression (list 'aguafria.zig/field
                               '(aguafria.keyword/import "builtin")
                               (keyword zig-name))
              descriptor (prepare backing
                                  {:kind :const :module (str backing-name) :name name
                                   :declaration-key [:const name]
                                   :type (or type (list 'aguafria.keyword/TypeOf expression))
                                   :value expression})
              reference {:kind :compiler-module :category :constant
                         :symbol (symbol (str (ns-name target-ns)) zig-name)
                         :zig-name (str "@import(\"builtin\")." zig-name)}]
          (register descriptor)
          (let [v (intern target-ns name (root-value descriptor))]
            (alter-meta! v assoc
                         :aguafria/zig-reference reference
                         :zig/name (:zig-name reference)
                         :doc (str "Compiler-provided Zig `" (:zig-name reference)
                                   "`. Its value follows the consuming compilation."))))))
    nil))

(def ^:private catalog-resource
  "aguafria/zig-std.edn")

(defn- load-catalog
  []
  (if-let [resource (io/resource catalog-resource)]
    ;; Stream instead of `slurp`/`read-string`: the catalog is deliberately
    ;; complete, and retaining a second 10 MB source string creates a large,
    ;; needless cold-start allocation spike.
    (with-open [reader (java.io.PushbackReader. (io/reader resource))]
      (edn/read {:eof nil} reader))
    (throw (ex-info "Aguafria's generated Zig std catalog is missing"
                    {:resource catalog-resource
                     :regenerate-with "clojure -M:generate-keyword"}))))

(def ^:private generated-catalog
  (load-catalog))

(def ^:private namespaces-by-name
  (into {} (map (juxt :name identity)) (:namespaces generated-catalog)))

(defonce ^:private installation-lock
  (Object.))

(defn catalog-info
  "Return generation, Zig version, source hashes, and catalog counts."
  []
  (dissoc generated-catalog :namespaces))

(defn namespaces
  "Return every EDN-derived Clojure std namespace as a symbol."
  []
  (mapv :name (:namespaces generated-catalog)))

(defn entries
  "Return std declaration metadata globally or for one generated namespace."
  ([]
   (into [] (mapcat :members) (:namespaces generated-catalog)))
  ([namespace-name]
   (if-let [namespace (get namespaces-by-name (symbol (str namespace-name)))]
     (:members namespace)
     (throw (ex-info "Unknown EDN-derived Zig std namespace"
                     {:namespace namespace-name
                      :known-count (count namespaces-by-name)})))))

(defn- reference-form-builder
  [reference]
  (with-meta
    (fn [& arguments]
      (if (contains? #{:function :type-function} (:category reference))
        ((requiring-resolve 'aguafria.zig.jvm/invoke-reference!) reference arguments)
        (with-meta (apply list (:symbol reference) arguments)
          {:aguafria/zig-reference reference})))
    {:aguafria/zig-reference reference}))

(defn- member-reference
  [member]
  (cond-> {:category (:category member)
   :signature (:signature member)
   :kind :std
   :symbol (:symbol member)
   :zig-name (:zig-name member)}
    (:receiver-method member)
    (assoc :receiver-method? true :member-name (:name member))))

(defn- member-doc
  [{:keys [category documentation signature source zig-name zig-version]}]
  (str (when (seq signature) (str signature "\n\n"))
       (when (seq documentation) (str documentation "\n\n"))
       "This Var represents Zig `" zig-name "` (" (name category) ") from `"
       source "`, generated against Zig " zig-version ". Inside an `az/defn` "
       "form it emits the Zig reference directly. "
       (if (= :function category)
         "Calling this Var from Clojure or Java executes native Zig, specializing comptime arguments as needed."
         "This declaration represents Zig type/constant syntax inside Aguafria forms.")))

(defn- install-member!
  [target-ns member]
  (let [sym (symbol (:clojure-name member))
        ;; `ns-interns` and `ns-map` both materialize a complete persistent
        ;; map. Calling either for every member made large std containers
        ;; quadratic during cold bootstrap; Namespace provides direct lookups.
        existing (.findInternedVar ^clojure.lang.Namespace target-ns sym)
        reference (member-reference member)]
    (when (and existing (not (:aguafria/std (meta existing))))
      (throw (ex-info "EDN-derived Zig std Var collides with an existing Var"
                      {:namespace (ns-name target-ns)
                       :symbol sym
                       :existing (meta existing)})))
    (when (and (nil? existing)
               (.getMapping ^clojure.lang.Namespace target-ns sym))
      (ns-unmap target-ns sym))
    (let [value (reference-form-builder reference)
          v (if existing
              (do (alter-var-root existing (constantly value)) existing)
              (intern target-ns sym value))]
      (alter-meta!
       v merge
       {:aguafria/std true
        :aguafria/zig-reference reference
        :arglists (if (and (seq (:parameters member))
                           (every? :name (:parameters member)))
                    (list (mapv (comp symbol :name) (:parameters member)))
                    '([& arguments]))
        :doc (member-doc member)
        :zig/category (:category member)
        :zig/documentation-source :zig-std
        :zig/name (:zig-name member)
        :zig/param-count (:param-count member)
        :zig/signature (:signature member)
        :zig/source (:source member)
        :zig/version (:zig-version member)})
      v)))

(defn install!
  "Intern the catalog Vars belonging to `target-ns`.

  This is idempotent and preserves existing Var identities across REPL reloads."
  [target-ns]
  (let [target-ns (if (instance? clojure.lang.Namespace target-ns)
                    target-ns
                    (the-ns target-ns))
        namespace-name (ns-name target-ns)
        namespace (get namespaces-by-name namespace-name)]
    (when-not namespace
      (throw (ex-info "No Zig std catalog entry exists for this namespace"
                      {:namespace namespace-name
                       :known-count (count namespaces-by-name)})))
    (let [expected (set (map (comp symbol :clojure-name) (:members namespace)))]
      (doseq [[sym v] (ns-interns target-ns)
              :when (and (:aguafria/std (meta v)) (not (contains? expected sym)))]
        (ns-unmap target-ns sym))
      (mapv (partial install-member! target-ns) (:members namespace)))))

(defn- loaded-libs-ref
  []
  (let [loaded-libs-var (ns-resolve 'clojure.core '*loaded-libs*)
        loaded-libs (when loaded-libs-var (var-get loaded-libs-var))]
    (when-not (instance? clojure.lang.Ref loaded-libs)
      (throw (ex-info "This Clojure runtime cannot register EDN-backed namespaces"
                      {:clojure-version (clojure-version)
                       :expected 'clojure.lang.Ref
                       :actual (some-> loaded-libs type str)})))
    loaded-libs))

(defn- ensure-namespace!
  [namespace-name]
  (or (find-ns namespace-name)
      (let [target-ns (create-ns namespace-name)]
        ;; `create-ns` intentionally does not perform the implicit clojure.core
        ;; referral that `(ns ...)` does. Mirror normal namespace semantics.
        (binding [*ns* target-ns]
          (clojure.core/refer 'clojure.core))
        target-ns)))

(defn install-all!
  "Materialize all Zig std namespaces and Vars from the EDN catalog.

  The namespace symbols are registered with Clojure's loader only after every
  Var has installed successfully, so subsequent ordered `:require` libspecs
  work without reloading their entry points. Safe to call repeatedly at a REPL.
  Returns a small installation summary."
  []
  (locking installation-lock
    (let [namespace-names (namespaces)
          vars (reduce +
                       (map (fn [namespace-name]
                              (count (install! (ensure-namespace! namespace-name))))
                            namespace-names))
          loaded-libs (loaded-libs-ref)]
      (dosync
       (alter loaded-libs into namespace-names))
      {:namespace-count (count namespace-names)
       :var-count vars})))
