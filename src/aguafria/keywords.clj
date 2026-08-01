(ns aguafria.keywords
  "Reader-safe names for Zig syntax that Clojure cannot spell directly.

  Require this namespace as `ak`. Every Zig `@builtin` is exposed as a real,
  documented Var, generated from the installed Zig compiler and matching ZLS
  language-reference data. Ordinary readable Zig forms such as `if`, `while`,
  and `try` stay unqualified."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private catalog-resource
  "aguafria/zig-builtins.edn")

(defn- load-catalog
  []
  (if-let [resource (io/resource catalog-resource)]
    (edn/read-string (slurp resource))
    (throw (ex-info "Aguafria's generated Zig keyword catalog is missing"
                    {:resource catalog-resource
                     :regenerate-with "clojure -M:generate-keywords"}))))

(def ^:private generated-catalog
  (load-catalog))

(defn catalog-info
  "Return generation/version/source metadata for the bundled Zig catalog."
  []
  (dissoc generated-catalog :builtins :keywords :reader-tokens))

(defn builtins
  "Return the generated catalog of all Zig compiler `@builtins`."
  []
  (:builtins generated-catalog))

(defn language-keywords
  "Return Zig's mechanically discovered ordinary keyword catalog.

  These are inspectable but are not interned as `ak/...` Vars because readable
  forms such as `if` and `while` are intentionally written without a prefix."
  []
  (:keywords generated-catalog))

(defn reader-tokens
  "Return the Zig tokens that need reader-safe Aguafria names."
  []
  (:reader-tokens generated-catalog))

(defn- parameter-arglists
  [{:keys [param-count minimum-param-count]}]
  (cond
    (some? param-count)
    (list (vec (map #(symbol (str "argument-" %))
                    (range 1 (inc param-count)))))

    minimum-param-count
    (list (vec (concat (map #(symbol (str "argument-" %))
                            (range 1 (inc minimum-param-count)))
                       ['& 'more])))

    :else
    '([& arguments])))

(defn- builtin-doc
  [{:keys [documentation documentation-source signature zig-name]}]
  (str signature "\n\n"
       documentation "\n\n"
       "Require this namespace as `ak`; inside an Aguafria declaration, "
       "`(ak/" (subs zig-name 1) " ...)` emits `" zig-name "(...)`. The Var is generated "
       "for Zig " (:zig-version generated-catalog) " from `"
       (get-in generated-catalog [:sources :builtin-table :path]) "`"
       (when (= :zls-langref documentation-source)
         " and enriched with ZLS's Zig language-reference data")
       ". Compiler metadata is available through the Var's `:zig/*` keys."))

(defn- reader-token-doc
  [{:keys [documentation name zig-token]}]
  (str documentation "\n\n"
       "Inside an Aguafria declaration, `(ak/" name " ...)` emits Zig `"
       zig-token "`. Generated against Zig " (:zig-version generated-catalog)
       " `" (get-in generated-catalog [:sources :tokenizer :path]) "`."))

(defn- unusable-outside-declaration
  [token]
  (fn [& arguments]
    (throw
     (ex-info
      (str "`aguafria.keywords/" (:name token)
           "` is Zig syntax and can only be used inside an Aguafria form")
      {:arguments arguments
       :token token
       :example (str "(az/defn example :- :i32 [x :- :i64] (ak/"
                     (:name token) " x))")}))))

(defn- builtin-token
  [builtin]
  {:kind :builtin
   :name (:name builtin)
   :param-count (:param-count builtin)
   :signature (:signature builtin)
   :zig-name (:zig-name builtin)})

(defn- reader-token
  [token]
  (select-keys token [:kind :minimum-param-count :name :param-count :zig-token]))

(defn- intern-token!
  [token metadata]
  (let [sym (symbol (:name token))]
    (when-let [existing (get (ns-interns *ns*) sym)]
      (if (:aguafria/token (meta existing))
        (ns-unmap *ns* sym)
        (throw (ex-info "A generated Zig name collides with the keywords namespace API"
                        {:name sym :token token}))))
    ;; Avoid noisy replacement warnings for generated names such as `max`,
    ;; `min`, and `abs`, which Clojure happens to refer from clojure.core.
    (when (contains? (ns-map *ns*) sym)
      (ns-unmap *ns* sym))
    (let [v (intern *ns* sym (unusable-outside-declaration token))]
      (alter-meta! v merge metadata)
      v)))

(defn- resolve-qualified-var
  [context-ns sym]
  (when (and (symbol? sym) (namespace sym))
    (let [namespace-symbol (symbol (namespace sym))
          aliased-targets (->> (all-ns)
                               (keep #(get (ns-aliases %) namespace-symbol))
                               distinct
                               vec)
          target-ns (or (get (ns-aliases context-ns) namespace-symbol)
                        (find-ns namespace-symbol)
                        ;; Calls to az/emit-* may happen from a test runner or
                        ;; callback where `*ns*` is not the lexical namespace.
                        ;; An alias is still safe to recover when every loaded
                        ;; namespace maps it to the same target.
                        (when (= 1 (count aliased-targets))
                          (first aliased-targets)))]
      (when target-ns
        (ns-resolve target-ns (symbol (name sym)))))))

(defn- call-token
  [context-ns op]
  (some-> (resolve-qualified-var context-ns op)
          meta
          :aguafria/token))

(defn- validate-token-arity!
  [{:keys [minimum-param-count name param-count zig-name] :as token} args form]
  (when (and (some? param-count) (not= param-count (count args)))
    (throw (ex-info
            (str (or zig-name name) " expects " param-count " argument"
                 (when (not= 1 param-count) "s") ", got " (count args))
            {:actual (count args)
             :expected param-count
             :form form
             :token token})))
  (when (and minimum-param-count (< (count args) minimum-param-count))
    (throw (ex-info
            (str name " expects at least " minimum-param-count
                 " arguments, got " (count args))
            {:actual (count args)
             :expected-at-least minimum-param-count
             :form form
             :token token}))))

(declare normalize-form)

(defn- normalize-seq
  [context-ns form]
  (let [[op & raw-args] form
        token (call-token context-ns op)
        args (mapv #(normalize-form context-ns %) raw-args)
        normalized
        (if token
          (do
            (validate-token-arity! token args form)
            (case (:kind token)
              :builtin (list* 'builtin (symbol (:name token)) args)
              :operator (list* 'op (:zig-token token) args)
              :assignment (list* 'assign (:zig-token token) args)
              (throw (ex-info "Unknown Aguafria Zig token kind"
                              {:form form :token token}))))
          (apply list (normalize-form context-ns op) args))]
    (with-meta normalized (meta form))))

(defn normalize-form
  "Resolve `aguafria.keywords` Vars in captured Zig data.

  `context-ns` is the namespace where an Aguafria macro is expanded, allowing
  any alias (not only `ak`) to work. The returned data contains only the small
  canonical forms understood by the emitter."
  [context-ns form]
  (cond
    (seq? form) (normalize-seq context-ns form)
    (vector? form) (with-meta (mapv #(normalize-form context-ns %) form)
                              (meta form))
    (map? form) (with-meta
                  (into (empty form)
                        (map (fn [[key value]]
                               [(normalize-form context-ns key)
                                (normalize-form context-ns value)]))
                        form)
                  (meta form))
    (set? form) (with-meta (into #{} (map #(normalize-form context-ns %)) form)
                           (meta form))
    :else form))

;; `require :reload` must also remove tokens that disappeared in a newer Zig
;; catalog instead of leaving stale Vars in a long-running REPL.
(doseq [[sym v] (ns-interns *ns*)
        :when (:aguafria/token (meta v))]
  (ns-unmap *ns* sym))

(doseq [builtin (:builtins generated-catalog)]
  (let [token (builtin-token builtin)]
    (intern-token!
     token
     {:aguafria/token token
      :arglists (parameter-arglists builtin)
      :doc (builtin-doc builtin)
      :zig/allows-lvalue? (:allows-lvalue? builtin)
      :zig/documentation-format (:documentation-format builtin)
      :zig/documentation-source (:documentation-source builtin)
      :zig/eval-to-error (:eval-to-error builtin)
      :zig/illegal-outside-function? (:illegal-outside-function? builtin)
      :zig/name (:zig-name builtin)
      :zig/param-count (:param-count builtin)
      :zig/signature (:signature builtin)
      :zig/source (get-in generated-catalog [:sources :builtin-table :path])
      :zig/version (:zig-version generated-catalog)})))

(doseq [entry (:reader-tokens generated-catalog)]
  (let [token (reader-token entry)]
    (intern-token!
     token
     {:aguafria/token token
      :arglists (parameter-arglists entry)
      :doc (reader-token-doc entry)
      :zig/name (:zig-token entry)
      :zig/param-count (:param-count entry)
      :zig/source (get-in generated-catalog [:sources :tokenizer :path])
      :zig/version (:zig-version generated-catalog)})))
