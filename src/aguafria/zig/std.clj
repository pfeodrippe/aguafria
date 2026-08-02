(ns aguafria.zig.std
  "EDN-backed loader and inspection API for Zig std Vars.

  `aguafria.std` materializes every catalog namespace and Var in memory. No
  Clojure source is generated for the individual Zig std namespaces."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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
  (fn [& arguments]
    (with-meta (apply list (:symbol reference) arguments)
      {:aguafria/zig-reference reference})))

(defn- member-reference
  [member]
  {:category (:category member)
   :kind :std
   :symbol (:symbol member)
   :zig-name (:zig-name member)})

(defn- member-doc
  [{:keys [category documentation signature source zig-name zig-version]}]
  (str (when (seq signature) (str signature "\n\n"))
       (when (seq documentation) (str documentation "\n\n"))
       "This Var represents Zig `" zig-name "` (" (name category) ") from `"
       source "`, generated against Zig " zig-version ". Inside an `az/defn` "
       "form it emits the Zig reference directly. Calling it at the Clojure "
       "REPL returns inspectable Aguafria form data; it does not execute Zig "
       "until that form is compiled."))

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
        :arglists '([& arguments])
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
  work without generated `.clj` shim files. Safe to call repeatedly at a REPL.
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
