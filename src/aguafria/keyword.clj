(ns aguafria.keyword
  "Reader-safe names for Zig syntax that Clojure cannot spell directly.

  Require this namespace as `ak`. Every Zig `@` function is exposed as a real,
  documented Var, generated from the installed Zig compiler and matching ZLS
  language-reference data. Ordinary readable Zig forms such as `if`, `while`,
  and `try` stay unqualified."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private catalog-resource
  "aguafria/zig-keyword.edn")

(defn- load-catalog
  []
  (if-let [resource (io/resource catalog-resource)]
    (edn/read-string (slurp resource))
    (throw (ex-info "Aguafria's generated Zig keyword catalog is missing"
                    {:resource catalog-resource
                     :regenerate-with "clojure -M:generate-keyword"}))))

(def ^:private generated-catalog
  (load-catalog))

;; Remove generated Vars before compiling the rest of this namespace on
;; `require :reload`. In particular, a previous `ak/fn` or `ak/if` Var must
;; not shadow Clojure's own special-form spelling while this file is read.
(let [removed (->> (ns-interns *ns*)
                   (keep (fn [[sym v]]
                           (when (:aguafria/token (meta v)) sym)))
                   vec)]
  (doseq [sym removed]
    (ns-unmap *ns* sym))
  ;; Re-refer any clojure.core name that an older generated catalog shadowed.
  (doseq [sym removed
          :when (ns-resolve 'clojure.core sym)]
    (refer 'clojure.core :only [sym])))

;; Clojure keeps removed definitions across `require :reload`. Clean the old
;; pre-singular API so a long-running REPL observes the rename immediately.
(when (contains? (ns-interns *ns*) 'builtins)
  (ns-unmap *ns* 'builtins))

(defn catalog-info
  "Return generation/version/source metadata for the bundled Zig catalog."
  []
  (dissoc generated-catalog :builtins :keywords :reader-tokens))

(defn entries
  "Return the generated catalog of all Zig compiler `@` functions."
  []
  (:builtins generated-catalog))

(defn language-keywords
  "Return Zig's mechanically discovered ordinary keyword catalog.

  Every entry is also backed by a documented `ak/...` Var. Generated source
  may still use an ordinary Clojure form such as `if` when it has the same
  clear spelling and already resolves in Clojure."
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

(defn- compiler-doc
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
      (str "`aguafria.keyword/" (:name token)
           "` is Zig syntax and can only be used inside an Aguafria form")
      {:arguments arguments
       :token token
       :example (str "(az/defn example :- :i32 [x :- :i64] (ak/"
                     (:name token) " x))")}))))

(defn- call-token
  [builtin]
  {:kind :call
   :name (:name builtin)
   :param-count (:param-count builtin)
   :symbol (symbol "aguafria.keyword" (:name builtin))
   :signature (:signature builtin)
   :zig-name (:zig-name builtin)})

(defn- reader-token
  [token]
  (assoc (select-keys token [:kind :minimum-param-count :name :param-count :zig-token])
         :symbol (symbol "aguafria.keyword" (:name token))))

(defn- language-token
  [entry builtin-names]
  (let [zig-token (:name entry)
        clojure-name (if (contains? builtin-names zig-token)
                       (str "keyword-" zig-token)
                       zig-token)]
    {:kind :keyword
     :name clojure-name
     :param-count nil
     :symbol (symbol "aguafria.keyword" clojure-name)
     :zig-token zig-token}))

(defn- intern-token!
  [token metadata]
  (let [sym (symbol (:name token))]
    (when-let [existing (get (ns-interns *ns*) sym)]
      (if (:aguafria/token (meta existing))
        (ns-unmap *ns* sym)
        (throw (ex-info "A generated Zig name collides with the keyword namespace API"
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

(defn resolve-token
  "Resolve a qualified generated keyword symbol in `context-ns`.

  The result describes the Zig call/operator and includes its canonical
  `aguafria.keyword/...` symbol. Unqualified Zig forms intentionally return
  nil so ordinary forms such as `if` and `field` retain their meanings."
  [context-ns op]
  (some-> (resolve-qualified-var context-ns op)
          meta
          :aguafria/token))

(defn token-name
  "Return the generated `ak/...` Var name for a Zig keyword/operator token."
  [zig-token]
  (let [builtin-names (set (map :name (:builtins generated-catalog)))]
    (or (some (fn [entry]
                (when (= zig-token (:zig-token entry)) (:name entry)))
              (:reader-tokens generated-catalog))
        (some (fn [entry]
                (when (and (= zig-token (:name entry))
                           (not (special-symbol? (symbol zig-token)))
                           (nil? (ns-resolve 'clojure.core (symbol zig-token))))
                  (:name (language-token entry builtin-names))))
              (:keywords generated-catalog)))))

(defn validate-call!
  "Validate the argument count declared by a generated Zig keyword Var."
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

(doseq [builtin (:builtins generated-catalog)]
  (let [token (call-token builtin)]
    (intern-token!
     token
     {:aguafria/token token
      :arglists (parameter-arglists builtin)
      :doc (compiler-doc builtin)
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

(let [builtin-names (set (map :name (:builtins generated-catalog)))]
  (doseq [entry (:keywords generated-catalog)
          :let [operator (symbol (:name entry))]
          :when (and (not (special-symbol? operator))
                     (nil? (ns-resolve 'clojure.core operator)))]
    (let [token (language-token entry builtin-names)]
      (intern-token!
       token
       {:aguafria/token token
        :arglists '([& forms])
        :doc (str "Zig `" (:zig-token token) "` keyword, mechanically discovered "
                  "from Zig " (:zig-version generated-catalog) " `"
                  (get-in generated-catalog [:sources :tokenizer :path]) "`. "
                  "This Var is syntax and is only valid inside an Aguafria declaration.")
        :zig/name (:zig-token token)
        :zig/source (get-in generated-catalog [:sources :tokenizer :path])
        :zig/version (:zig-version generated-catalog)}))))

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
