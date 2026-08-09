(ns aguafria.zig.emitter
  "A deliberately small data representation of Zig syntax.

  Nothing in this namespace evaluates or macroexpands a Zig form as Clojure.
  It only validates data and renders deterministic Zig source."
  (:require [aguafria.keyword :as keyword]
            [aguafria.zig.project :as project]
            [clojure.string :as str]))

(defn- fail!
  [message form & [data]]
  (throw (ex-info message (merge {:form form} data))))

(defn identifier
  "Render a Clojure name as a legal, conventional Zig identifier.

  Hyphens become underscores and namespace separators become double
  underscores for Clojure symbols/keywords. Strings and `:zig/name` metadata
  are already-exact Zig spellings and are returned unchanged."
  [x]
  (cond
    (string? x) x
    (and (symbol? x) (:zig/name (meta x))) (:zig/name (meta x))
    :else
    (let [s (cond
              (symbol? x) (if-let [n (namespace x)]
                            (str n "__" (name x))
                            (name x))
              (keyword? x) (name x)
              :else (fail! "Expected a Zig identifier" x))]
      (-> s
          (str/replace "-" "_")
          (str/replace "?" "_q")
          (str/replace "!" "_bang")
          (str/replace "/" "__")))))

(declare emit-expr emit-stmt emit-statements emit-type emit-block-expr
         postfix-source multiline-string-tail? indent braced capture-source
         emit-container emit-while-loop emit-for emit-for-loop emit-let-expr)

(def ^:private structural-operators
  '#{raw raw-chunks raw-statements raw-statement-chunks
     do block labeled-block object init array-init op type
     number-literal string-literal multiline-string char-literal
     identifier-literal enum-literal error-value
     pointer-capture else-clause else-expression catch-capture
     switch labeled-switch switch-stmt labeled-switch-stmt
     case inline-case case-else inline-case-else
     container fn-decl const-decl var-decl extern-var-decl struct-decl import-decl
     field-decl enum-field-decl tuple-field-decl comptime-decl
     test-decl fn-proto-decl
     if when when-not if-capture if-capture-stmt
     field deref unwrap index slice slice-sentinel try comptime comptime-stmt nosuspend
     comment return const var let set! assign assign-expr destructure while for dotimes defer errdefer
     inline-for for-loop while-loop
     break break-label continue unreachable})

(defn structural-operator?
  "True when `op` is an unqualified Aguafria syntax form, not a Zig Var call."
  [op]
  (and (symbol? op)
       (nil? (namespace op))
       (not (:zig/reference (meta op)))
       (contains? structural-operators op)))

(defn syntax-operators
  "Return Aguafria's structural form names for tooling and generated Vars."
  []
  structural-operators)

(def ^:dynamic *keyword-context*
  "Namespace used to resolve aliases such as `ak/intCast` during emission."
  nil)

(def ^:dynamic *reloadable-state-references?* false)
(def ^:dynamic *reloadable-state-accessors* nil)
(def ^:dynamic *lexical-bindings* #{})
(def ^:dynamic *named-module-imports?* false)
(def ^:dynamic *logical-type-names?* false)

(defn logical-module-type-name
  "Return the type-name prefix Zig assigned to a file in its original tree."
  [module-name]
  (if-let [relative-path (project/module-relative-path module-name)]
    (-> relative-path
        (str/replace "\\" "/")
        (str/replace #"^src/" "")
        (str/replace #"\.zig$" "")
        (str/replace "/" "."))
    (str module-name)))

(defn named-module-container
  "Return the stable Zig container used for a converted named module.

  Zig reports a nested type declared directly in every named module root as
  `module.Type`. Large projects commonly use `@typeName` as a compile-time
  identity. The quoted container retains the file's original logical path so
  Aguafria can remove only Zig's synthetic `module.` cache-file prefix."
  [module-name]
  (let [logical-name (logical-module-type-name module-name)]
    (str "@\"__aguafria_type__"
         (str/escape logical-name {\\ "\\\\" \" "\\\""})
         "\"")))

(defn- named-module-selector
  [module-name]
  (when (and *named-module-imports?* module-name)
    (str "." (named-module-container module-name))))

(def ^:private logical-type-name-helper
  (str "inline fn __aguafria_type_name(comptime T: type) [:0]const u8 {\n"
       ;; Reconstructing logical names adds comptime work to projects that
       ;; reflect over hundreds of types.  Keep this helper from consuming a
       ;; caller's deliberately tight quota; one million remains only an
       ;; upper bound and has no runtime cost.
       "    @setEvalBranchQuota(1_000_000);\n"
       "    const type_name = @typeName(T);\n"
       "    const cache_prefix = \"module.__aguafria_type__\";\n"
       "    if (comptime @import(\"std\").mem.startsWith(u8, type_name, cache_prefix)) {\n"
       "        return type_name[cache_prefix.len.. :0];\n"
       "    }\n"
       "    return type_name;\n"
       "}"))

(defn- current-keyword-token
  [op]
  (keyword/resolve-token (or *keyword-context* *ns*) op))

(defn- resolve-context-var
  [context-ns sym]
  (when (symbol? sym)
    (if-let [namespace-name (namespace sym)]
      (let [namespace-symbol (symbol namespace-name)
            target-ns (or (get (ns-aliases context-ns) namespace-symbol)
                          (find-ns namespace-symbol))]
        (when target-ns
          (ns-resolve target-ns (symbol (name sym)))))
      (ns-resolve context-ns sym))))

(defn- resolved-syntax-operator
  [context-ns op]
  (or (when (structural-operator? op) op)
      (some-> (resolve-context-var context-ns op)
              meta :aguafria/syntax :name symbol)))

(defn- declared-project-reference
  [context-ns sym]
  (when-let [requested-alias (when (symbol? sym)
                               (some-> sym namespace symbol))]
    (let [target-ns (get (ns-aliases context-ns) requested-alias)
          clojure-name (name sym)
          target-module (some-> target-ns ns-name str)
          catalog-name (when target-module
                         (project/declaration-zig-name target-module clojure-name))
          ;; A generated catalog records exact Zig spellings only when they
          ;; differ. For a hand-written namespace whose target Var has not yet
          ;; been evaluated in this REPL, the catalog falls back to its Clojure
          ;; name; normalize that fallback exactly like a loaded Var reference.
          zig-name (when catalog-name
                     (if (= catalog-name clojure-name)
                       (identifier (symbol clojure-name))
                       catalog-name))]
      (when (and zig-name target-module)
        (merge
         {:kind :declaration
          :module target-module
          :zig-name zig-name
          :symbol (symbol target-module clojure-name)}
         (select-keys
          (project/module-import (ns-name context-ns) requested-alias)
          [:source-order]))))))

(defn- namespace-root-reference
  [context-ns sym]
  (when (and (symbol? sym) (nil? (namespace sym)))
    (when-let [target-ns (get (ns-aliases context-ns) sym)]
      (let [module (str (ns-name target-ns))]
        (when-not (str/starts-with? module "aguafria.zig.import.")
          (cond-> (merge
                   {:kind :namespace-root
                    :module module
                    :zig-name (identifier sym)
                    :symbol sym}
                   (select-keys
                    (project/module-import (ns-name context-ns) sym)
                    [:source-order]))
            (= "aguafria.std" module)
            (assoc :module nil
                   :import-alias (identifier sym)
                   :import-name "std")))))))

(defn- resolve-zig-reference
  [context-ns sym]
  (or (:aguafria/zig-reference (meta sym))
      ;; Qualified symbols explicitly name Vars. Unqualified symbols may be
      ;; lexical binders, container members, error names, or declaration names;
      ;; resolving them through ns-resolve would capture an unrelated top-level
      ;; Var with the same spelling.
      (when (and (symbol? sym) (namespace sym))
        (some-> (resolve-context-var context-ns sym)
                meta
                :aguafria/zig-reference))
      (declared-project-reference context-ns sym)
      (namespace-root-reference context-ns sym)
      ;; Converted declarations whose Zig spelling is not reader-safe (for
      ;; example `@"false"`) have a deliberately different Clojure name.
      ;; Preserve that Var reference when a later declaration uses the safe
      ;; name. Ordinary unqualified names remain lexical/container names and
      ;; are intentionally not captured here.
      (when (and (symbol? sym)
                 (nil? (namespace sym))
                 (not (contains? *lexical-bindings* sym)))
        (some-> (resolve-context-var context-ns sym)
                meta
                :aguafria/zig-reference
                (#(when (not= (:zig-name %) (identifier sym)) %))))
      (when (and (symbol? sym) (nil? (namespace sym)))
        (let [module (str (or project/*catalog-namespace*
                              (ns-name context-ns)))
              zig-name (project/declaration-zig-name module (name sym))]
          (when (not= zig-name (name sym))
            {:kind :declaration
             :module module
             :zig-name zig-name
             :symbol (symbol module (name sym))})))))

(defn- contextual-reference
  [context-ns original-symbol reference]
  (let [actual-module (str (ns-name context-ns))
        current-module (str (or project/*catalog-namespace*
                                (ns-name context-ns)))
        target-module (:module reference)]
    (if (or (= :import-member (:kind reference))
            (nil? target-module)
            (= actual-module target-module)
            (= current-module target-module))
      reference
      (let [requested-alias (or (some-> original-symbol namespace symbol)
                                (when (= :namespace-root (:kind reference))
                                  original-symbol))
            catalog-import (project/module-import current-module requested-alias)
            aliased-target (some-> (get (ns-aliases context-ns) requested-alias)
                                   ns-name str)
            [import-alias import]
            (or (when (= aliased-target target-module)
                  [requested-alias {:namespace (symbol target-module)}])
                [requested-alias
                 {:namespace (symbol target-module)
                  :import-name target-module}])
            import-alias (or import-alias
                             (fail! "A cross-namespace Zig reference needs a Clojure require alias"
                                    original-symbol
                                    {:context-ns (ns-name context-ns)
                                     :target-module target-module}))
            zig-alias (identifier import-alias)]
        (assoc reference
               :kind (if (= :namespace-root (:kind reference))
                       :namespace-root
                       :namespace-member)
               :import-alias zig-alias
               ;; Live modules import the converted namespace identity. The
               ;; catalog retains the original `.zig` spelling solely for
               ;; optional standalone materialization.
               :import-name target-module
               :import-namespace (symbol target-module)
               :source-order (or (:source-order catalog-import)
                                 (:source-order reference))
               :zig-name (if (= :namespace-root (:kind reference))
                           zig-alias
                           (str zig-alias "." (:zig-name reference))))))))

(defn- reference-symbol
  [context-ns original-symbol reference]
  (let [reference (contextual-reference context-ns original-symbol reference)]
    (with-meta (:symbol reference)
      (assoc (meta (:symbol reference)) :aguafria/zig-reference reference))))

(defn- current-zig-reference
  [sym]
  (resolve-zig-reference (or *keyword-context* *ns*) sym))

(defn- known-type-reference
  [sym]
  (or (some-> (current-zig-reference sym)
              (#(when (:type-reference? %) %)))
      ;; Ordinary same-namespace type calls remain unqualified in stored
      ;; forms. Resolve only Vars explicitly marked as Zig type declarations;
      ;; arbitrary functions that receive maps must remain function calls.
      (when (and (symbol? sym) (nil? (namespace sym)))
        (some-> (resolve-context-var (or *keyword-context* *ns*) sym)
                meta
                :aguafria/zig-reference
                (#(when (:type-reference? %) %))))))

(declare qualify-form)

(def ^:private preserved-clojure-macro-operators
  "Clojure macros whose spelling is also a direct, zero-cost Zig operator.
  Structural Aguafria forms are preserved separately."
  #{"and" "or"})

(defn- expand-clojure-macro-once
  [context-ns form]
  (let [op (first form)
        structural? (some? (resolved-syntax-operator context-ns op))
        token (when-not structural? (keyword/resolve-token context-ns op))
        macro-var (when (and (symbol? op)
                             (nil? token)
                             (not (contains? *lexical-bindings* op)))
                    (resolve-context-var context-ns op))]
    (when (and (not structural?)
               (not (contains? preserved-clojure-macro-operators
                               (when (symbol? op) (name op))))
               (:macro (meta macro-var)))
      (try
        (binding [*ns* context-ns]
          (let [expanded (macroexpand-1 form)]
            ;; Macroexpansion may legitimately produce nil or false (the
            ;; terminating `(cond)`, for example). Wrap the value so those
            ;; results remain distinguishable from "not a Clojure macro".
            (when-not (= expanded form) {:expanded expanded})))
        (catch Throwable cause
          (throw (ex-info
                  (str "Clojure macro expansion failed inside an Aguafria declaration: `"
                       op "`")
                  {:form form
                   :operator op
                   :context-ns (ns-name context-ns)
                   :aguafria/phase :clojure-macroexpansion}
                  cause)))))))

(def ^:private declaration-name-operators
  '#{fn-decl fn-proto-decl const-decl var-decl extern-var-decl struct-decl import-decl
     field-decl enum-field-decl comptime-decl test-decl})

(defn- qualify-seq
  [context-ns form]
  (let [[op & raw-args] form
        structural-op (resolved-syntax-operator context-ns op)
        structural? (some? structural-op)
        token (when-not structural? (keyword/resolve-token context-ns op))
        reference (when-not structural? (resolve-zig-reference context-ns op))
        args (cond
               (and structural? (= 'field structural-op)
                    (= 2 (count raw-args)))
               ;; A field name is Zig syntax, not a Var reference. Qualifying
               ;; it would incorrectly capture a same-named top-level Var.
               [(qualify-form context-ns (first raw-args))
                (let [field-name (second raw-args)]
                  ;; Quoted names use Aguafria's explicit identifier-literal
                  ;; form, which still needs syntax normalization.
                  (if (seq? field-name)
                    (qualify-form context-ns field-name)
                    field-name))]

               (and structural?
                    (contains? declaration-name-operators structural-op)
                    (seq raw-args))
               (into [(first raw-args)]
                     (map #(qualify-form context-ns %))
                     (next raw-args))

               :else
               (mapv #(qualify-form context-ns %) raw-args))
        qualified-op (cond
                       structural? structural-op
                       (= :keyword (:kind token)) (symbol (:zig-token token))
                       token (:symbol token)
                       reference (reference-symbol context-ns op reference)
                       :else (qualify-form context-ns op))]
    (when (and token (not= :keyword (:kind token)))
      (keyword/validate-call! token args form))
    (when (and (symbol? op)
               (or (namespace op) (str/includes? (name op) "."))
               (not structural?)
               (nil? token)
               (nil? reference))
      (fail! (str "Unresolved Zig reference `" op "`. "
                  "Qualified calls must name a real Var from a required namespace.")
             form {:operator op :context-ns (ns-name context-ns)}))
    (with-meta (apply list qualified-op args) (meta form))))

(defn qualify-form
  "Replace keyword aliases with canonical `aguafria.keyword/...` Var symbols.

  The Zig form itself remains intact; no intermediate `builtin` form is
  introduced. This makes stored declaration metadata readable and allows the
  emitter to consume generated keyword Vars directly."
  [context-ns form]
  (cond
    (seq? form) (if-let [expansion (expand-clojure-macro-once context-ns form)]
                  (let [expanded (:expanded expansion)]
                    ;; `cond` expands its conventional `:else` clause to
                    ;; `(if :else value (cond))`. In Clojure the keyword is
                    ;; unconditionally truthy; simplify it before Zig sees an
                    ;; `else` keyword in expression position or the nil tail.
                    (if (and (seq? expanded)
                             (= 'if (first expanded))
                             (= :else (second expanded)))
                      (qualify-form context-ns (nth expanded 2))
                      (qualify-form context-ns expanded)))
                  (qualify-seq context-ns form))
    (vector? form) (with-meta (mapv #(qualify-form context-ns %) form)
                              (meta form))
    (map? form) (with-meta
                  (into (empty form)
                        (map (fn [[key value]]
                               [(qualify-form context-ns key)
                                (qualify-form context-ns value)]))
                        form)
                  (meta form))
    (set? form) (with-meta (into #{} (map #(qualify-form context-ns %)) form)
                           (meta form))
    (and (symbol? form) (nil? (namespace form))
         (namespace-root-reference context-ns form))
    (reference-symbol context-ns form
                      (namespace-root-reference context-ns form))

    ;; Preserve schema identity on ordinary same-namespace type/state
    ;; references. This lets implementation fingerprints notice a new
    ;; defstruct and lets development emission route a defvar through its
    ;; stable state-reference cell.
    (and (symbol? form) (nil? (namespace form))
         (not (contains? *lexical-bindings* form)))
    (if-let [reference
             (some-> (resolve-context-var context-ns form)
                     meta :aguafria/zig-reference
                     (#(when (or (:type-reference? %)
                                 (= :var (:declaration-kind %)))
                           %)))]
      (reference-symbol context-ns form reference)
      form)

    (and (symbol? form)
         (or (namespace form) (str/includes? (name form) ".")))
    (if-let [reference (resolve-zig-reference context-ns form)]
      (reference-symbol context-ns form reference)
      form)

    :else form))

(defn- declaration-local-bindings
  [context-ns declaration]
  (into (set (map :name (:args declaration)))
        (mapcat (fn [value]
                  (if-not (seq? value)
                    []
                    (let [form-operator (first value)
                          token (keyword/resolve-token context-ns form-operator)
                          operator (or (resolved-syntax-operator context-ns
                                                                 form-operator)
                                       (when (= :keyword (:kind token))
                                         (symbol (:zig-token token))))
                          bindings (second value)]
                      (cond
                        (and (contains? #{'const 'var} operator)
                             (symbol? bindings))
                        [bindings]

                        (and (contains? declaration-name-operators operator)
                             (symbol? bindings))
                        [bindings]

                        (and (= 'let operator) (vector? bindings))
                        (filterv symbol? (take-nth 2 bindings))

                        (and (= 'dotimes operator) (vector? bindings)
                             (symbol? (first bindings)))
                        [(first bindings)]

                        :else [])))))
        (tree-seq coll? seq (:body declaration))))

(defn prepare-declaration
  "Qualify every Zig form in a declaration using its defining Clojure ns."
  [context-ns declaration]
  (cond-> declaration
    (contains? declaration :type)
    (update :type #(when (some? %) (qualify-form context-ns %)))

    (contains? declaration :return)
    (update :return #(qualify-form context-ns %))

    (contains? declaration :value)
    (update :value #(qualify-form context-ns %))

    (contains? declaration :body)
    (update :body
            #(binding [*lexical-bindings*
                       (declaration-local-bindings context-ns declaration)]
               (mapv (partial qualify-form context-ns) %)))

    (contains? declaration :args)
    (update :args #(mapv (fn [arg]
                           (update arg :type (partial qualify-form context-ns)))
                         %))

    (contains? declaration :fields)
    (update :fields #(mapv (fn [field]
                             (update field :type
                                     (partial qualify-form context-ns)))
                           %))))

(def ^:dynamic *source-mapping?*
  "When true, statement emission includes Clojure line/column marker comments.
  Complete modules enable this automatically; standalone emitter calls stay
  uncluttered."
  false)

(defn- emit-general-pointer-type
  [options child form]
  (when-not (map? options)
    (fail! "Pointer type options must be a map" form))
  (let [{:keys [size const? volatile? allowzero? align addrspace sentinel
                bit-start bit-end]}
        options
        prefix (case size
                 :one "*"
                 :many (str "[*" (when (some? sentinel)
                                    (str ":" (emit-expr sentinel))) "]")
                 :slice (str "[" (when (some? sentinel)
                                    (str ":" (emit-expr sentinel))) "]")
                 :c "[*c]"
                 (fail! "Pointer :size must be :one, :many, :slice, or :c"
                        form))]
    (when (not= (some? bit-start) (some? bit-end))
      (fail! "Pointer bit ranges require both :bit-start and :bit-end" form))
    (str prefix
         (when align
           (str "align(" (emit-expr align)
                (when (some? bit-start)
                  (str ":" (emit-expr bit-start) ":" (emit-expr bit-end)))
                ") "))
         (when addrspace (str "addrspace(" (emit-expr addrspace) ") "))
         (when const? "const ")
         (when volatile? "volatile ")
         (when allowzero? "allowzero ")
         (emit-type child))))

(defn- emit-type*
  "Emit a Zig type from a keyword/symbol/string or a compositional vector.

  Supported vectors include `[:* t]`, `[:*const t]`, `[:many t]`,
  `[:many-const t]`, `[:sentinel t n]`, `[:slice t]`, `[:slice-const t]`,
  `[:array n t]`, `[:vector n t]`, `[:c-pointer t]`, `[:optional t]`, and
  `[:error-union t]`. A generated keyword call may also produce a type."
  [t]
  (cond
    (symbol? t)
    (if-let [reference (current-zig-reference t)]
      (:zig-name reference)
      (identifier t))

    (or (keyword? t) (string? t))
    (identifier t)

    (vector? t)
    (let [[op & xs] t]
      (case op
        :* (if (= 1 (count xs))
             (str "*" (emit-type (first xs)))
             (fail! "Pointer type expects one child type" t))
        :*const (if (= 1 (count xs))
                  (str "*const " (emit-type (first xs)))
                  (fail! "Const pointer type expects one child type" t))
        :many (if (= 1 (count xs))
                (str "[*]" (emit-type (first xs)))
                (fail! "Many pointer type expects one child type" t))
        :many-const (if (= 1 (count xs))
                      (str "[*]const " (emit-type (first xs)))
                      (fail! "Const many pointer type expects one child type" t))
        :sentinel (if (= 2 (count xs))
                    (str "[*:" (emit-expr (second xs)) "]"
                         (emit-type (first xs)))
                    (fail! "Sentinel pointer type expects a child type and sentinel" t))
        :sentinel-const (if (= 2 (count xs))
                          (str "[*:" (emit-expr (second xs)) "]const "
                               (emit-type (first xs)))
                          (fail! "Const sentinel pointer type expects a child type and sentinel" t))
        :slice (if (= 1 (count xs))
                 (str "[]" (emit-type (first xs)))
                 (fail! "Slice type expects one child type" t))
        :slice-const (if (= 1 (count xs))
                       (str "[]const " (emit-type (first xs)))
                       (fail! "Const slice type expects one child type" t))
        :array (if (= 2 (count xs))
                 (str "[" (emit-expr (first xs)) "]" (emit-type (second xs)))
                 (fail! "Array type expects a length and child type" t))
        :array-sentinel (if (= 3 (count xs))
                          (str "[" (emit-expr (first xs)) ":"
                               (emit-expr (second xs)) "]"
                               (emit-type (nth xs 2)))
                          (fail! "Sentinel array type expects a length, sentinel, and child type" t))
        :vector (if (= 2 (count xs))
                  (str "@Vector(" (emit-expr (first xs)) ", "
                       (emit-type (second xs)) ")")
                  (fail! "SIMD vector type expects a length and child type" t))
        :c-pointer (if (= 1 (count xs))
                     (str "[*c]" (emit-type (first xs)))
                     (fail! "C pointer type expects one child type" t))
        :optional (if (= 1 (count xs))
                    (str "?" (emit-type (first xs)))
                    (fail! "Optional type expects one child type" t))
        :error-union (if (= 1 (count xs))
                       (str "!" (emit-type (first xs)))
                       (if (= 2 (count xs))
                         (str (emit-type (first xs)) "!" (emit-type (second xs)))
                         (fail! "Error union type expects a child or error-set/child pair"
                                t)))
        :error-set (if (and (= 1 (count xs))
                            (vector? (first xs)))
                     (str "error{"
                          (str/join ", "
                                    (map (fn [member]
                                           (if (seq? member)
                                             (emit-expr member)
                                             (identifier member)))
                                         (first xs)))
                          "}")
                     (fail! "Error set type expects one vector of error names" t))
        :pointer (if (= 2 (count xs))
                   (emit-general-pointer-type (first xs) (second xs) t)
                   (fail! "General pointer type expects options and child type" t))
        :fn (let [[options arguments return-type & extra] xs]
              (when-not (and (empty? extra) (map? options)
                             (vector? arguments) return-type)
                (fail! "Function type expects options, argument maps, and return type" t))
              (let [argument-source
                    (mapv
                     (fn [{:keys [name type prefix variadic?] :as argument}]
                       (when-not (and (map? argument)
                                      (or variadic? (some? type)))
                         (fail! "Function type arguments require :type or :variadic?"
                                t {:argument argument}))
                       (str (when (seq prefix) (str prefix " "))
                            (when name (str (identifier name) ": "))
                            (if variadic? "..." (emit-type type))))
                     arguments)]
                (str "fn (" (str/join ", " argument-source) ")"
                     (when-let [alignment (:align options)]
                       (str " align(" (emit-expr alignment) ")"))
                     (when-let [address-space (:addrspace options)]
                       (str " addrspace(" (emit-expr address-space) ")"))
                     (when-let [section (:linksection options)]
                       (str " linksection(" (emit-expr section) ")"))
                     (when-let [calling-convention (:callconv options)]
                       (str " callconv(" (emit-expr calling-convention) ")"))
                     " " (emit-type return-type))))
        :const (if (= 1 (count xs))
                 (str "const " (emit-type (first xs)))
                 (fail! "Const type expects one child type" t))
        (fail! "Unknown composite Zig type" t {:operator op})))

    (seq? t)
    (emit-expr t)

    :else
    (fail! "Cannot emit Zig type" t)))

(defn emit-type
  "Emit a Zig type, resolving generated keyword Vars directly."
  ([t]
   (emit-type* t))
  ([context-ns t]
   (binding [*keyword-context* context-ns]
     (emit-type* t))))

(def ^:private infix-operators
  {"+" "+", "-" "-", "*" "*", "/" "/", "%" "%"
   "++" "++", "**" "**", "||" "||", "<<|" "<<|"
   ".." "..", "..." "..."
   "+%" "+%", "-%" "-%", "*%" "*%"
   "+|" "+|", "-|" "-|", "*|" "*|"
   "==" "==", "!=" "!=", "<" "<", "<=" "<=", ">" ">", ">=" ">="
   "and" "and", "or" "or"
   "&" "&", "|" "|", "^" "^", "<<" "<<", ">>" ">>"
   "orelse" "orelse", "catch" "catch"})

(def ^:private prefix-operators
  {"!" "!", "~" "~", "-" "-", "-%" "-%", "+" "+", "&" "&"})

(def ^:private assignment-operators
  {"=" "=", "+=" "+=", "-=" "-=", "*=" "*=", "/=" "/=", "%=" "%="
   "+%=" "+%=", "-%=" "-%=", "*%=" "*%="
   "+|=" "+|=", "-|=" "-|=", "*|=" "*|="
   "&=" "&=", "|=" "|=", "^=" "^=", "<<=" "<<=", ">>=" ">>="})

(defn- operator-name
  [op]
  (when (symbol? op) (name op)))

(defn- zig-string
  [s]
  ;; Clojure's string escapes are accepted by Zig for the common escape set.
  (pr-str s))

(defn- one-string-argument
  [operator args form]
  (if (and (= 1 (count args)) (string? (first args)))
    (first args)
    (fail! (str operator " expects exactly one string") form)))

(defn- identifier-fragment
  [form]
  (if (and (seq? form) (= 'identifier-literal (first form)))
    (let [source (one-string-argument "identifier-literal" (rest form) form)]
      ;; Zig permits keyword spellings after a field dot (`Enum.false`). Such
      ;; names cannot be represented by a Clojure symbol because `false` reads
      ;; as a boolean, so the explicit fragment form also accepts a plain Zig
      ;; identifier in addition to `@"quoted"` identifiers.
      (if (or (re-matches #"[A-Za-z_][A-Za-z0-9_]*" source)
              (re-matches #"@\"(?:[^\"\\\r\n]|\\.)*\"" source))
        source
        (fail! "identifier-literal expects one exact Zig identifier" form)))
    (identifier form)))

(defn- emit-lexical-literal
  [operator args form]
  (let [source (one-string-argument (name operator) args form)
        valid?
        (case operator
          number-literal
          (boolean (re-matches #"[0-9][0-9A-Fa-f_xXoObBpPeE.+-]*" source))

          string-literal
          (boolean (re-matches #"\"(?:[^\"\\\r\n]|\\.)*\"" source))

          char-literal
          (boolean (re-matches #"'(?:[^'\\\r\n]|\\.)+'" source))

          identifier-literal
          (boolean (re-matches #"@\"(?:[^\"\\\r\n]|\\.)*\"" source))

          enum-literal
          (boolean (re-matches #"\.(?:[A-Za-z_][A-Za-z0-9_]*|@\"(?:[^\"\\\r\n]|\\.)*\")"
                               source)))]
    (if valid?
      source
      (fail! (str (name operator) " contains an invalid Zig token")
             form {:source source}))))

(defn- emit-multiline-string
  [args form]
  (let [[lines & extra] args]
    (if (and (vector? lines) (empty? extra)
             (every? #(and (string? %) (not (re-find #"[\r\n]" %))) lines)
             (seq lines))
      (str (str/join "\n" (map #(str "\\\\" %) lines)) "\n")
      (fail! "multiline-string expects one non-empty vector of logical lines"
             form))))

(defn- emit-map-literal
  [m]
  (str ".{"
       (->> m
            (sort-by (comp str key))
            (map (fn [[k v]]
                   (str "." (identifier k) " = " (emit-expr v))))
            (str/join ", "))
       "}"))

(defn- emit-object-literal
  [fields form]
  (when-not (and (vector? fields)
                 (every? #(and (vector? %) (= 2 (count %))) fields))
    (fail! "object expects one vector of [field value] entries" form
           {:fields fields}))
  (str ".{"
       (->> fields
            (map (fn [[field value]]
                   (when-not (or (keyword? field) (symbol? field) (string? field))
                     (fail! "object field names must be keywords, symbols, or strings"
                            form {:field field}))
                   (str "." (identifier field) " = " (emit-expr value))))
            (str/join ", "))
       "}"))

(defn- emit-vector-literal
  [xs]
  (str ".{" (str/join ", " (map emit-expr xs)) "}"))

(defn- parenthesized-infix
  [operator args form]
  (when (and (< (count args) 2)
             (not (and (= ".." operator) (= 1 (count args)))))
    (fail! "Infix Zig operator expects at least two operands" form
           {:operator operator}))
  (let [rendered (if (and (= ".." operator) (= 1 (count args)))
                   (str (emit-expr (first args)) "..")
                   (str/join (str " " operator " ") (map emit-expr args)))]
    ;; Zig ranges are grammar productions used by `for` and `switch`, not
    ;; ordinary binary expressions. Parenthesizing the range itself is invalid.
    (if (contains? #{".." "..."} operator)
      rendered
      (str "(" rendered ")"))))

(defn- emit-if-expr
  [[test then else :as args] form]
  (when-not (<= 2 (count args) 3)
    (fail! "Zig if expression expects test, then, and optional else" form))
  (let [branch-source
        (fn [branch]
          (cond
            (and (seq? branch) (contains? #{'do 'block} (first branch)))
            (braced (rest branch) 0)

            (and (seq? branch)
                 (contains? #{'return 'break 'break-label 'continue}
                            (first branch)))
            (str/replace-first (emit-stmt branch) #";\s*$" "")

            :else
            (emit-expr branch)))]
    (str "(if (" (emit-expr test) ") " (branch-source then)
         (when (= 3 (count args))
           (str " else " (branch-source else)))
         ")")))

(defn- captures-source
  [captures form]
  (when (seq captures)
    (when-not (vector? captures)
      (fail! "Zig captures must be a vector" form {:captures captures}))
    (str "|" (str/join ", " (map #(capture-source % form) captures)) "| ")))

(defn- emit-if-capture-expr
  [[options test then else :as args] form]
  (when-not (and (<= 3 (count args) 4) (map? options))
    (fail! "if-capture expects options, condition, then, and optional else" form))
  (let [branch-source
        (fn [branch]
          (cond
            (and (seq? branch) (contains? #{'do 'block} (first branch)))
            (braced (rest branch) 0)

            (and (seq? branch)
                 (contains? #{'return 'break 'break-label 'continue}
                            (first branch)))
            (str/replace-first (emit-stmt branch) #";\s*$" "")

            :else
            (emit-expr branch)))]
    (str "(if (" (emit-expr test) ") "
         (captures-source (:payload options) form)
         (branch-source then)
         (when (= 4 (count args))
           (str " else " (captures-source (:error options) form)
                (branch-source else)))
         ")")))

(def ^:private switch-statement-targets
  '#{return break break-label continue})

(defn- emit-switch-target
  [target]
  (cond
    (and (seq? target) (contains? #{'do 'block} (first target)))
    (braced (rest target) 0)

    (and (seq? target) (contains? switch-statement-targets (first target)))
    (str/replace-first (emit-stmt target) #";\s*$" "")

    (and (seq? target) (= 'if (first target)) (= 3 (count target)))
    (emit-stmt target)

    (and (seq? target) (= 'if-capture-stmt (first target)))
    (emit-stmt target)

    :else
    (emit-expr target)))

(defn- emit-switch-case
  [clause]
  (when-not (and (seq? clause)
                 (contains? #{'case 'inline-case 'case-else 'inline-case-else}
                            (first clause)))
    (fail! "switch expects case, inline-case, case-else, or inline-case-else clauses"
           clause))
  (let [[operator & args] clause
        else? (contains? #{'case-else 'inline-case-else} operator)
        inline? (contains? #{'inline-case 'inline-case-else} operator)
        [values captures target]
        (if else?
          (case (count args)
            1 [[] nil (first args)]
            2 (if (vector? (first args))
                [[] (first args) (second args)]
                (fail! "switch else capture must be a vector" clause))
            (fail! "switch else expects optional captures and one target" clause))
          (case (count args)
            2 [(first args) nil (second args)]
            3 (if (vector? (second args))
                [(first args) (second args) (nth args 2)]
                (fail! "switch case captures must be a vector" clause))
            (fail! "switch case expects values, optional captures, and one target"
                   clause)))]
    (when-not (or else? (and (vector? values) (seq values)))
      (fail! "switch case values must be a non-empty vector" clause))
    (str (when inline? "inline ")
         (if else? "else" (str/join ", " (map emit-expr values)))
         " => "
         (when (seq captures)
           (str "|" (str/join ", " (map #(capture-source % clause) captures)) "| "))
         (emit-switch-target target))))

(defn- emit-switch
  [operator args form]
  (let [[label condition clauses]
        (if (contains? #{'labeled-switch 'labeled-switch-stmt} operator)
          (let [[label condition & clauses] args]
            [label condition clauses])
          (let [[condition & clauses] args]
            [nil condition clauses]))]
    (when-not (and condition (seq clauses)
                   (or (nil? label)
                       (symbol? label) (keyword? label) (string? label)))
      (fail! "switch expects a condition and one or more cases" form))
    (str (when label (str (identifier label) ": "))
         "switch (" (emit-expr condition) ") {\n"
         (indent 1 (str/join ",\n" (map emit-switch-case clauses)))
         "\n}")))

(defn- emit-asm
  [args form]
  (let [[template options :as all] args
        options (or options {})]
    (when-not (and (<= 1 (count all) 2)
                   (map? options))
      (fail! "asm expects a template and an optional options map" form))
    (let [{:keys [attrs outputs inputs clobbers]
           :or {attrs #{} outputs [] inputs []}} options
          unknown-options (seq (remove #{:attrs :outputs :inputs :clobbers}
                                       (keys options)))
          unknown-attrs (seq (remove #{:volatile} attrs))]
      (when-not (set? attrs)
        (fail! "asm :attrs must be a set" form {:attrs attrs}))
      (when unknown-options
        (fail! "asm options contain unsupported keys" form
               {:unsupported unknown-options}))
      (when unknown-attrs
        (fail! "asm :attrs contains unsupported attributes" form
               {:unsupported unknown-attrs}))
      (when-not (and (vector? outputs) (vector? inputs))
        (fail! "asm :outputs and :inputs must be vectors" form))
      (let [output-source
            (mapv
             (fn [output]
               (when-not (and (vector? output) (= 3 (count output)))
                 (fail! "Each asm output must be [name constraint {:value x}|{:type T}]"
                        form {:output output}))
               (let [[binding constraint result] output]
                 (when-not (string? constraint)
                   (fail! "An asm output constraint must be a string"
                          form {:output output}))
                 (when-not (and (map? result)
                                (= 1 (count result))
                                (or (contains? result :value)
                                    (contains? result :type)))
                   (fail! "An asm output result must contain exactly :value or :type"
                          form {:output output}))
                 (str "[" (identifier-fragment binding) "] "
                      (zig-string constraint) " ("
                      (if (contains? result :type)
                        (str "-> " (emit-type (:type result)))
                        (emit-expr (:value result)))
                      ")")))
             outputs)
            input-source
            (mapv
             (fn [input]
               (when-not (and (vector? input) (= 3 (count input)))
                 (fail! "Each asm input must be [name constraint value]"
                        form {:input input}))
               (let [[binding constraint value] input]
                 (when-not (string? constraint)
                   (fail! "An asm input constraint must be a string"
                          form {:input input}))
                 (str "[" (identifier-fragment binding) "] "
                      (zig-string constraint) " (" (emit-expr value) ")")))
             inputs)
            full? (or (seq outputs) (seq inputs)
                      (contains? options :clobbers))]
        (str "asm" (when (contains? attrs :volatile) " volatile")
             " (" (emit-expr template)
             (when full?
               (str "\n    : " (str/join ",\n      " output-source)
                    (when (or (seq inputs) (contains? options :clobbers))
                      (str "\n    : " (str/join ",\n      " input-source)))
                    (when (contains? options :clobbers)
                      (str "\n    : " (emit-expr clobbers)))))
             ")")))))

(defn- emit-keyword-expr
  [token args form]
  (keyword/validate-call! token args form)
  (case (:kind token)
    :call
    (let [logical-type-name? (and *logical-type-names?*
                                  (= "@typeName" (:zig-name token)))
          call-source
          (str (if logical-type-name?
                 "__aguafria_type_name"
                 (:zig-name token))
               "(" (str/join ", " (map emit-expr args)) ")")]
      call-source)

    :operator
    (let [operator (:zig-token token)]
      (cond
        (and (= 1 (count args)) (contains? prefix-operators operator))
        (str "(" operator (emit-expr (first args)) ")")

        (contains? infix-operators operator)
        (parenthesized-infix operator args form)

        :else
        (fail! "Generated keyword names an unsupported Zig operator"
               form {:token token})))

    :assignment
    (fail! "Assignment keyword cannot be used as a Zig expression"
           form {:token token})

    (fail! "Unknown generated Zig keyword kind" form {:token token})))

(defn- expand-thread
  [thread-last? args form]
  (when-not (seq args)
    (fail! (str (if thread-last? "->>" "->")
                " expects an initial value and optional steps")
           form))
  (reduce
   (fn [value step]
     (cond
       (seq? step)
       (with-meta
         (if thread-last?
           (apply list (first step) (concat (rest step) [value]))
           (apply list (first step) value (rest step)))
         (meta step))

       (or (symbol? step) (keyword? step))
       (list step value)

       :else
       (fail! "Clojure thread steps must be symbols or lists"
              form {:step step})))
   (first args)
   (rest args)))

(defn- emit-expr*
  [form]
  (cond
    (nil? form) "null"
    (true? form) "true"
    (false? form) "false"
    (string? form) (zig-string form)
    (char? form) (str "'" (case form
                            \newline "\\n"
                            \return "\\r"
                            \tab "\\t"
                            \' "\\'"
                            \\ "\\\\"
                            (str form)) "'")
    (and (instance? Double form) (Double/isNaN ^double form))
    "@as(f64, @bitCast(@as(u64, 0x7ff8000000000000)))"
    (and (instance? Double form) (Double/isInfinite ^double form))
    (if (neg? form)
      "@as(f64, @bitCast(@as(u64, 0xfff0000000000000)))"
      "@as(f64, @bitCast(@as(u64, 0x7ff0000000000000)))")
    (and (instance? Float form) (Float/isNaN ^float form))
    "@as(f32, @bitCast(@as(u32, 0x7fc00000)))"
    (and (instance? Float form) (Float/isInfinite ^float form))
    (if (neg? form)
      "@as(f32, @bitCast(@as(u32, 0xff800000)))"
      "@as(f32, @bitCast(@as(u32, 0x7f800000)))")
    (number? form) (str form)
    (symbol? form)
    (if-let [reference (current-zig-reference form)]
      (if (and *reloadable-state-references?*
               (:state-accessor reference)
               ;; The local accessor set only describes state declared by the
               ;; module currently being emitted. A qualified dependency Var
               ;; owns its accessor in the imported module, so rejecting it
               ;; here silently emitted `dependency.var` and created a second
               ;; native state capsule in every caller image.
               (or (:import-alias reference)
                   (nil? *reloadable-state-accessors*)
                   (contains? *reloadable-state-accessors*
                              (:state-accessor reference))))
        (let [accessor (if-let [alias (:import-alias reference)]
                         (str alias "." (:state-accessor reference))
                         (:state-accessor reference))]
          (str accessor "().*"))
        (:zig-name reference))
      (if (str/includes? (name form) ".")
        (fail! (str "Unresolved dotted Zig reference `" form "`. "
                    "Declare imported members with az/defimport.")
               form {:operator form})
        (identifier form)))
    (keyword? form) (let [n (name form)]
                      (if (str/starts-with? n ".")
                        n
                        (identifier form)))
    (map? form) (emit-map-literal form)
    (vector? form) (emit-vector-literal form)

    (seq? form)
    (let [[source-op & args] form
          source-token (current-keyword-token source-op)
          op (or (resolved-syntax-operator (or *keyword-context* *ns*) source-op)
                 (when (= :keyword (:kind source-token))
                   (symbol (:zig-token source-token)))
                 source-op)
          token (when-not (= :keyword (:kind source-token)) source-token)]
      (cond
        token
        (emit-keyword-expr token args form)

        (= "->" (operator-name op))
        (emit-expr (expand-thread false args form))

        (= "->>" (operator-name op))
        (emit-expr (expand-thread true args form))

        (= op 'raw)
        (if (and (= 1 (count args)) (string? (first args)))
          (first args)
          (fail! "raw expects exactly one string" form))

        (contains? #{'return 'break 'break-label 'continue} op)
        (str/replace-first (emit-stmt form) #";\s*$" "")

        (= op 'container)
        (emit-container form)

        (contains? #{'fn-decl 'const-decl 'var-decl 'extern-var-decl 'struct-decl
                     'import-decl 'field-decl 'enum-field-decl
                     'tuple-field-decl 'comptime-decl 'test-decl
                     'fn-proto-decl} op)
        (fail! "Nested declaration forms can only be used inside container" form)

        (= op 'raw-chunks)
        (let [[chunks & extra] args]
          (if (and (vector? chunks) (every? string? chunks) (empty? extra))
            (apply str chunks)
            (fail! "raw-chunks expects one vector of strings" form)))

        (contains? #{'number-literal 'string-literal 'char-literal
                     'identifier-literal 'enum-literal} op)
        (emit-lexical-literal op args form)

        (= op 'multiline-string)
        (emit-multiline-string args form)

        (= op 'asm)
        (emit-asm args form)

        (= op 'object)
        (let [[fields & extra] args]
          (if (empty? extra)
            (emit-object-literal fields form)
            (fail! "object expects one vector of [field value] entries" form)))

        (= op 'error-value)
        (if (= 1 (count args))
          (str "error." (identifier-fragment (first args)))
          (fail! "error-value expects one error name" form))

        (contains? #{'do 'block} op)
        (emit-block-expr nil args)

        (= op 'let)
        (emit-let-expr args form)

        (= op 'labeled-block)
        (let [[label & forms] args]
          (when-not (or (symbol? label) (keyword? label) (string? label))
            (fail! "labeled-block expects an identifier followed by statements" form))
          (emit-block-expr label forms))

        (= op 'init)
        (let [[type fields] args]
          (if (and (= 2 (count args))
                   (or (map? fields)
                       (and (seq? fields)
                            (= 'object
                               (resolved-syntax-operator
                                (or *keyword-context* *ns*) (first fields))))))
            (str (emit-type type) (subs (emit-expr fields) 1))
            (fail! "init expects a type and an object/map literal" form)))

        (= op 'array-init)
        (let [[type elements] args]
          (if (and (= 2 (count args)) (vector? elements))
            (str (emit-type type) (subs (emit-vector-literal elements) 1))
            (fail! "array-init expects a type and element vector" form)))

        (= op 'type)
        (if (= 1 (count args))
          (emit-type (first args))
          (fail! "type expects one Aguafria type form" form))

        (= op 'op)
        (let [[operator & operands] args
              operator (cond
                         (string? operator) operator
                         (or (symbol? operator) (keyword? operator)) (name operator)
                         :else nil)]
          (cond
            (and operator (= 1 (count operands))
                 (contains? prefix-operators operator))
            (str "(" (get prefix-operators operator)
                 (emit-expr (first operands)) ")")

            (and operator (contains? infix-operators operator))
            (parenthesized-infix (get infix-operators operator) operands form)

            :else
            (fail! "op expects a supported Zig operator token and operands" form
                   {:operator operator})))

        (= op 'assign-expr)
        (let [[operator target value :as assignment] args]
          (when-not (and (= 3 (count assignment))
                         (string? operator)
                         (contains? (set (vals assignment-operators)) operator))
            (fail! "assign-expr expects an assignment operator string, target, and value"
                   form {:operator operator}))
          (str (emit-expr target) " " operator " " (emit-expr value)))

        (= op 'destructure)
        (let [[options bindings value :as all] args]
          (when-not (and (= 3 (count all)) (map? options)
                         (vector? bindings) (seq bindings))
            (fail! "destructure expects options, a non-empty binding vector, and value"
                   form))
          (let [binding-source
                (mapv
                 (fn [{:keys [kind name type target prefix align addrspace
                              linksection] :as binding}]
                   (when-not (map? binding)
                     (fail! "Each destructure binding must be a map" form
                            {:binding binding}))
                   (case kind
                     :discard "_"
                     :target (if (some? target)
                               (emit-expr target)
                               (fail! "A :target destructure binding needs :target"
                                      form {:binding binding}))
                     :const
                     (do
                       (when-not name
                         (fail! "A declared destructure binding needs :name"
                                form {:binding binding}))
                       (str (when (seq prefix) (str prefix " "))
                            "const " (identifier name)
                            (when type (str ": " (emit-type type)))
                            (when align (str " align(" (emit-expr align) ")"))
                            (when addrspace
                              (str " addrspace(" (emit-expr addrspace) ")"))
                            (when linksection
                              (str " linksection(" (emit-expr linksection) ")"))))
                     :var
                     (do
                       (when-not name
                         (fail! "A declared destructure binding needs :name"
                                form {:binding binding}))
                       (str (when (seq prefix) (str prefix " "))
                            "var " (identifier name)
                            (when type (str ": " (emit-type type)))
                            (when align (str " align(" (emit-expr align) ")"))
                            (when addrspace
                              (str " addrspace(" (emit-expr addrspace) ")"))
                            (when linksection
                              (str " linksection(" (emit-expr linksection) ")"))))
                     (fail! "Destructure :kind must be :discard, :target, :const, or :var"
                            form {:binding binding})))
                 bindings)]
            (str (when (:comptime? options) "comptime ")
                 (str/join ", " binding-source) " = " (emit-expr value))))

        (= op 'if)
        (emit-if-expr args form)

        (contains? #{'if-capture 'if-capture-stmt} op)
        (emit-if-capture-expr args form)

        (= op 'while-loop)
        (emit-while-loop args 0 form)

        (contains? #{'for 'inline-for} op)
        (emit-for args 0 form)

        (= op 'for-loop)
        (emit-for-loop args 0 form)

        (= op 'catch-capture)
        (let [[captures value handler :as all] args]
          (when-not (and (= 3 (count all)) (vector? captures)
                         (= 1 (count captures)))
            (fail! "catch-capture expects [error], value, and handler" form))
          (let [handler-source
                (cond
                  (and (seq? handler) (contains? #{'do 'block} (first handler)))
                  (braced (rest handler) 0)

                  (and (seq? handler)
                       (contains? #{'return 'break 'break-label 'continue}
                                  (first handler)))
                  (str/replace-first (emit-stmt handler) #";\s*$" "")

                  :else
                  (emit-expr handler))]
            (str "(" (emit-expr value) " catch "
                 (captures-source captures form) handler-source ")")))

        (contains? #{'switch 'labeled-switch 'switch-stmt 'labeled-switch-stmt} op)
        (emit-switch op args form)

        (contains? #{'case 'inline-case 'case-else 'inline-case-else} op)
        (fail! "switch case forms can only be used inside switch" form)

        (= op 'field)
        (if (= 2 (count args))
          (str (postfix-source (first args)) "."
               (identifier-fragment (second args)))
          (fail! "field expects a target and field name" form))

        (= op 'deref)
        (if (= 1 (count args))
          (str (postfix-source (first args)) ".*")
          (fail! "deref expects one pointer expression" form))

        (= op 'unwrap)
        (if (= 1 (count args))
          (str (postfix-source (first args)) ".?")
          (fail! "unwrap expects one optional expression" form))

        (= op 'index)
        (if (= 2 (count args))
          (str (postfix-source (first args)) "[" (emit-expr (second args)) "]")
          (fail! "index expects a target and index" form))

        (= op 'slice)
        (if (<= 2 (count args) 3)
          (let [[target start end] args]
            (str (postfix-source target) "[" (emit-expr start) ".."
                 (when (some? end) (emit-expr end)) "]"))
          (fail! "slice expects a target, start, and optional end" form))

        (= op 'slice-sentinel)
        (if (= 4 (count args))
          (let [[target start end sentinel] args]
            (str (postfix-source target) "[" (emit-expr start) ".."
                 (when (some? end) (emit-expr end)) " :"
                 (emit-expr sentinel) "]"))
          (fail! "slice-sentinel expects a target, start, optional end, and sentinel" form))

        (= op 'try)
        (if (= 1 (count args))
          (str "try " (emit-expr (first args)))
          (fail! "try expects one expression" form))

        (= op 'comptime)
        (if (= 1 (count args))
          (str "(comptime " (emit-expr (first args)) ")")
          (fail! "comptime expects one expression" form))

        (= op 'nosuspend)
        (if (= 1 (count args))
          (str "nosuspend " (emit-expr (first args)))
          (fail! "nosuspend expects one expression" form))

        (= "mod" (operator-name op))
        (if (= 2 (count args))
          (str "@mod(" (emit-expr (first args)) ", "
               (emit-expr (second args)) ")")
          (fail! "Clojure mod expects a numerator and denominator" form))

        (contains? infix-operators (operator-name op))
        (if (and (= 1 (count args))
                 (contains? prefix-operators (operator-name op)))
          (str "(" (get prefix-operators (operator-name op))
               (emit-expr (first args)) ")")
          (parenthesized-infix (get infix-operators (operator-name op)) args form))

        (and (contains? prefix-operators (operator-name op)) (= 1 (count args)))
        (str "(" (get prefix-operators (operator-name op))
             (emit-expr (first args)) ")")

        (and (known-type-reference op)
             (= 1 (count args))
             (or (map? (first args))
                 (and (seq? (first args))
                      (= 'object
                         (resolved-syntax-operator
                          (or *keyword-context* *ns*)
                          (ffirst args))))))
        (str (postfix-source op) (subs (emit-expr (first args)) 1))

        (or (symbol? op) (keyword? op) (string? op) (seq? op))
        (str (postfix-source op)
             "(" (str/join ", " (map emit-expr args)) ")")

        :else
        (fail! "Cannot emit Zig invocation" form {:operator op})))

    :else
    (fail! "Cannot emit Zig expression" form {:class (class form)})))

(defn emit-expr
  "Emit one Zig expression, resolving `aguafria.keyword` Vars directly."
  ([form]
   (emit-expr* form))
  ([context-ns form]
   (binding [*keyword-context* context-ns]
     (emit-expr* form))))

(defn- indent
  [level text]
  (let [padding (apply str (repeat (* 4 level) " "))]
    (->> (str/split-lines (str text))
         (map #(if (str/blank? %) % (str padding %)))
         (str/join "\n"))))

(defn- form-source-comment
  [form]
  (let [{:keys [line column]} (meta form)]
    (when (and *source-mapping?* line)
      (str "// Aguafria form: " line (when column (str ":" column)) "\n"))))

(defn- braced
  [forms level]
  (if (seq forms)
    (str "{\n" (indent (inc level) (emit-statements forms (inc level))) "\n"
         (indent level "}"))
    "{}"))

(defn emit-block-expr
  [label forms]
  (str (when label (str (identifier label) ": "))
       (braced forms 0)))

(defn- branch-forms
  [form]
  (if (and (seq? form) (contains? #{'do 'block} (first form)))
    (rest form)
    [form]))

(defn- emit-statement-branch
  [form level]
  (if (and (seq? form) (= 'labeled-block (first form)))
    (emit-expr form)
    (braced (branch-forms form) level)))

(defn- expression-terminator
  [rendered]
  (if (multiline-string-tail? rendered)
    (if (re-find #"(?:\r\n|\r|\n)$" rendered) ";" "\n;")
    ";"))

(defn- emit-local
  [kind args form]
  (let [[n & declaration] args
        [options declaration] (if (and (next declaration)
                                       (map? (first declaration)))
                                [(first declaration) (next declaration)]
                                [{} declaration])
        [t value] (case (count declaration)
                    1 [nil (first declaration)]
                    2 [(first declaration) (second declaration)]
                    (fail! (str kind " expects name, optional options/type, and value")
                           form))]
    (when-not (map? options)
      (fail! (str kind " local options must be a map") form))
    (let [rendered (emit-expr value)]
      (str (when-let [prefix (:prefix options)] (str prefix " "))
           kind " " (identifier n)
           (when (and t (not= t '_)) (str ": " (emit-type t)))
           (when-let [alignment (:align options)]
             (str " align(" (emit-expr alignment) ")"))
           (when-let [address-space (:addrspace options)]
             (str " addrspace(" (emit-expr address-space) ")"))
           (when-let [section (:linksection options)]
             (str " linksection(" (emit-expr section) ")"))
           " = " rendered
           (expression-terminator rendered)))))

(defn- let-parts
  [args form]
  (let [[bindings & body] args]
    (when-not (and (vector? bindings) (even? (count bindings)))
      (fail! "let expects an even Clojure binding vector and a body" form))
    (let [pairs (mapv vec (partition 2 bindings))]
      (doseq [[binding] pairs]
        (when-not (symbol? binding)
          (fail! "Aguafria let currently binds names, not destructuring forms"
                 form {:binding binding})))
      {:pairs pairs :body body})))

(defn- let-local-form
  [[binding value]]
  (let [kind (if (:var (meta binding)) 'var 'const)
        type (or (:zig/type (meta binding))
                 (:tag (meta binding)))]
    (if type
      (list kind binding type value)
      (list kind binding value))))

(defn- emit-let-stmt
  [args level form]
  (let [{:keys [pairs body]} (let-parts args form)]
    (braced (concat (map let-local-form pairs) body) level)))

(defn- emit-let-expr
  [args form]
  (let [{:keys [pairs body]} (let-parts args form)]
    (when-not (seq body)
      (fail! "A let used as a value requires a result expression" form))
    (let [label (str "aguafria_let_"
                     (Integer/toUnsignedString (hash form) 16))
          preceding (concat (map let-local-form pairs) (butlast body))
          result (last body)]
      (str label ": {\n"
           (when (seq preceding)
             (str (indent 1 (emit-statements preceding 1)) "\n"))
           (indent 1 (str "break :" label " " (emit-expr result) ";"))
           "\n}"))))

(defn- emit-dotimes
  [args level form]
  (let [[bindings & body] args]
    (when-not (and (vector? bindings)
                   (= 2 (count bindings))
                   (symbol? (first bindings)))
      (fail! "dotimes expects [name count] followed by its body" form))
    (let [[binding count-expression] bindings]
      (str "for (0..@as(usize, @intCast("
           (emit-expr count-expression)
           "))) |" (identifier binding) "| "
           (braced body level)))))

(defn- ensure-semicolon
  [source]
  (if (str/ends-with? (str/trimr source) ";")
    source
    (str source ";")))

(def ^:private block-like-expression-ops
  #{"block" "labeled-block" "let" "if" "when" "when-not" "if-capture"
    "switch" "labeled-switch" "dotimes"})

(defn- expression-statement-needs-semicolon?
  [form]
  (not (and (seq? form)
            (contains? block-like-expression-ops
                       (operator-name (first form))))))

(defn- multiline-string-tail?
  [source]
  (some-> source str/split-lines last str/triml (str/starts-with? "\\\\")))

(defn- postfix-source
  [form]
  (let [source (emit-expr form)
        direct-postfix? (and (seq? form)
                             (contains? #{"field" "index" "slice"
                                          "slice-sentinel" "deref" "unwrap"}
                                        (operator-name (first form))))]
    (if (or (map? form)
            (vector? form)
            ;; Parenthesize every compound target before field access, calls,
            ;; indexing, or slicing, except an existing postfix chain. Keeping
            ;; `b.step(...)` unwrapped is required for Zig method lookup;
            ;; wrapping `try call()` is required before accessing its result.
            (and (seq? form) (not direct-postfix?)))
      (str "(" source ")")
      source)))

(defn- emit-if-stmt
  [args level form]
  (let [[test then else :as all] args]
    (when-not (<= 2 (count all) 3)
      (fail! "Zig if statement expects test, then, and optional else" form))
    (str "if (" (emit-expr test) ") "
         (emit-statement-branch then level)
         (when (some? else)
           (str " else " (if (and (seq? else) (= 'if (first else)))
                            (emit-if-stmt (rest else) level else)
                            (emit-statement-branch else level)))))))

(defn- emit-if-capture-stmt
  [args level form]
  (let [[options test then else :as all] args]
    (when-not (and (<= 3 (count all) 4) (map? options))
      (fail! "if-capture-stmt expects options, condition, then, and optional else"
             form))
    (when (and (:error options) (nil? else))
      (fail! "An error capture requires an else branch" form))
    (str "if (" (emit-expr test) ") "
         (captures-source (:payload options) form)
         (emit-statement-branch then level)
         (when (some? else)
           (str " else "
                (captures-source (:error options) form)
                (cond
                  (and (seq? else) (= 'if (first else)))
                  (emit-if-stmt (rest else) level else)

                  (and (seq? else) (= 'if-capture-stmt (first else)))
                  (emit-if-capture-stmt (rest else) level else)

                  :else
                  (emit-statement-branch else level)))))))

(defn- emit-control-flow-target
  [target level]
  (cond
    (and (seq? target) (= 'if (first target)))
    (emit-if-stmt (rest target) level target)

    (and (seq? target) (= 'if-capture-stmt (first target)))
    (emit-if-capture-stmt (rest target) level target)

    :else
    (emit-expr target)))

(defn- emit-loop
  [kind args level form]
  (let [[test & body] args]
    (when (or (nil? test) (empty? body))
      (fail! (str kind " expects a condition/iterable and a body") form))
    (str kind " (" (emit-expr test) ") " (braced body level))))

(defn- emit-while-loop
  [args level form]
  (let [[options condition & body] args]
    (when-not (and (map? options) (some? condition))
      (fail! "while-loop expects an options map and condition" form))
    (let [{:keys [label body-label inline? payload continue error else
                  else-expression]} options
          else-expression? (contains? options :else-expression)]
      (when-not (or (nil? label)
                    (symbol? label) (keyword? label) (string? label))
        (fail! "while-loop :label must be an identifier" form {:label label}))
      (when (and error (nil? else) (not else-expression?))
        (fail! "A while error capture requires an else branch" form))
      (when (and (some? else) (not (vector? else)))
        (fail! "while-loop :else must be a vector of statements" form
               {:else else}))
      (when-not (or (nil? inline?) (boolean? inline?))
        (fail! "while-loop :inline? must be boolean" form {:inline? inline?}))
      (when-not (or (nil? body-label)
                    (symbol? body-label) (keyword? body-label)
                    (string? body-label))
        (fail! "while-loop :body-label must be an identifier"
               form {:body-label body-label}))
      (str (when label (str (identifier label) ": "))
           (when inline? "inline ")
           "while (" (emit-expr condition) ") "
           (captures-source payload form)
           (when (some? continue)
             (str ": (" (emit-expr continue) ") "))
           (if body-label
             (str (identifier body-label) ": " (braced body level))
             (braced body level))
           (when (some? else)
             (str " else " (captures-source error form)
                  (braced else level)))
           (when else-expression?
             (str " else " (captures-source error form)
                  (emit-control-flow-target else-expression level)))))))

(defn- for-bindings
  [bindings form]
  (cond
    (and (vector? bindings) (= 2 (count bindings))
         (not (every? vector? bindings)))
    [bindings]

    (and (vector? bindings)
         (seq bindings)
         (every? #(and (vector? %) (= 2 (count %))) bindings))
    bindings

    :else
    (fail! "for expects [capture input] or [[capture input] ...]" form)))

(defn- capture-source
  [capture form]
  (cond
    (or (symbol? capture) (keyword? capture) (string? capture))
    (identifier capture)

    (and (seq? capture) (= 'pointer-capture (first capture))
         (= 2 (count capture)))
    (str "*" (identifier (second capture)))

    :else
    (fail! "for captures must be identifiers or (pointer-capture name)"
           form {:capture capture})))

(defn- emit-for-source
  [options bindings body level form]
  (when-not (map? options)
    (fail! "for-loop options must be a map" form))
  (let [pairs (for-bindings bindings form)
        else-form (when (and (seq? (last body))
                             (contains? #{'else-clause 'else-expression}
                                        (first (last body))))
                    (last body))
        body (if else-form (butlast body) body)
        {:keys [label body-label inline?]} options]
      (when-not (or (nil? label) (symbol? label) (keyword? label) (string? label))
        (fail! "for-loop :label must be an identifier" form {:label label}))
      (when-not (or (nil? inline?) (boolean? inline?))
        (fail! "for-loop :inline? must be boolean" form {:inline? inline?}))
      (when-not (or (nil? body-label)
                    (symbol? body-label) (keyword? body-label)
                    (string? body-label))
        (fail! "for-loop :body-label must be an identifier"
               form {:body-label body-label}))
      (str (when label (str (identifier label) ": "))
           (when inline? "inline ")
           "for (" (str/join ", " (map (comp emit-expr second) pairs)) ") |"
           (str/join ", " (map #(capture-source (first %) form) pairs)) "| "
           (if body-label
             (str (identifier body-label) ": " (braced body level))
             (braced body level))
           (when else-form
             (str " else "
                  (case (first else-form)
                    else-clause (braced (rest else-form) level)
                    else-expression
                    (if (= 2 (count else-form))
                      (emit-control-flow-target (second else-form) level)
                      (fail! "else-expression expects exactly one expression"
                             else-form))))))))

(defn- emit-for
  [args level form]
  (let [[bindings & body] args]
    (when-not (some? bindings)
      (fail! "for expects bindings" form))
    (let [prefix (if (= 'inline-for (first form))
                   "inline"
                   (:zig/prefix (meta form)))]
      (when-not (contains? #{nil "" "inline"} prefix)
        (fail! "for supports only Zig's inline prefix" form {:zig/prefix prefix}))
      (emit-for-source {:inline? (= "inline" prefix)}
                       bindings body level form))))

(defn- emit-for-loop
  [args level form]
  (let [[options bindings & body] args]
    (when-not (and (map? options) (some? bindings))
      (fail! "for-loop expects an options map and bindings" form))
    (emit-for-source options bindings body level form)))

(defn- for-else-expression
  [body]
  (let [else-form (last body)]
    (when (and (seq? else-form) (= 'else-expression (first else-form)))
      (second else-form))))

(defn emit-stmt
  "Emit one Zig statement. `level` is used only for nested block indentation."
  ([form] (emit-stmt form 0))
  ([form level]
   (let [rendered
         (if-not (seq? form)
           (str (emit-expr form) ";")
           (let [[source-op & args] form
                 source-token (current-keyword-token source-op)
                 op (or (resolved-syntax-operator (or *keyword-context* *ns*) source-op)
                        (when (= :keyword (:kind source-token))
                          (symbol (:zig-token source-token)))
                        source-op)
                 token (when-not (= :keyword (:kind source-token)) source-token)]
             (cond
               (and token (= :assignment (:kind token)))
               (do
                 (keyword/validate-call! token args form)
                 (let [[target value] args]
                   (str (emit-expr target) " " (:zig-token token) " "
                        (emit-expr value) ";")))

               (= op 'do) (emit-statements args level)
               (= op 'raw) (emit-expr form)
               (= op 'raw-statements)
               (if (and (= 1 (count args)) (string? (first args)))
                 (first args)
                 (fail! "raw-statements expects exactly one string" form))
               (= op 'raw-statement-chunks)
               (let [[chunks & extra] args]
                 (if (and (vector? chunks) (every? string? chunks) (empty? extra))
                   (apply str chunks)
                   (fail! "raw-statement-chunks expects one vector of strings" form)))
               (= op 'comment) (if (and (= 1 (count args)) (string? (first args)))
                                 (->> (str/split-lines (first args))
                                      (map #(str "// " %))
                                      (str/join "\n"))
                                 (fail! "comment expects one string" form))
               (= op 'return) (case (count args)
                                0 "return;"
                                1 (str "return " (emit-expr (first args)) ";")
                                (fail! "return expects zero or one expression" form))
               (= op 'const) (emit-local "const" args form)
               (= op 'var) (emit-local "var" args form)
               (= op 'let) (emit-let-stmt args level form)
               (= op 'set!) (if (= 2 (count args))
                              (str (emit-expr (first args)) " = "
                                   (emit-expr (second args)) ";")
                              (fail! "set! expects a target and value" form))
               (contains? #{'switch-stmt 'labeled-switch-stmt} op)
               (emit-expr form)
               (contains? #{'switch 'labeled-switch} op)
               ;; A value-producing switch used only for side effects must be
               ;; parenthesized before its semicolon. Without the grouping Zig
               ;; parses it as a switch statement, where `};` is invalid.
               (str "(" (emit-expr form) ");")
               (= op 'assign)
               (let [[operator target value :as assignment] args]
                 (when-not (and (= 3 (count assignment))
                                (string? operator)
                                (contains? (set (vals assignment-operators)) operator))
                   (fail! "assign expects an assignment operator string, target, and value"
                          form {:operator operator}))
                 (str (emit-expr target) " " operator " " (emit-expr value) ";"))
               (contains? assignment-operators (operator-name op))
               (if (= 2 (count args))
                 (str (emit-expr (first args)) " "
                      (get assignment-operators (operator-name op)) " "
                      (emit-expr (second args)) ";")
                 (fail! "Assignment operator expects a target and value" form
                        {:operator op}))
               (= op 'if) (emit-if-stmt args level form)
               (= op 'when)
               (let [[test & body] args]
                 (when (or (nil? test) (empty? body))
                   (fail! "when expects a condition and body" form))
                 (str "if (" (emit-expr test) ") " (braced body level)))
               (= op 'when-not)
               (let [[test & body] args]
                 (when (or (nil? test) (empty? body))
                   (fail! "when-not expects a condition and body" form))
                 (str "if (!(" (emit-expr test) ")) " (braced body level)))
               (= op 'if-capture-stmt)
               (emit-if-capture-stmt args level form)
               (= op 'while) (emit-loop "while" args level form)
               (= op 'while-loop)
               (let [[options] args
                     source (emit-while-loop args level form)]
                 (cond-> source
                   (and (map? options)
                        (contains? options :else-expression)
                        (expression-statement-needs-semicolon?
                         (:else-expression options)))
                   ensure-semicolon))
               (contains? #{'for 'inline-for} op)
               (let [[_bindings & body] args
                     source (emit-for args level form)
                     else-expression (for-else-expression body)]
                 (cond-> source
                   (and else-expression
                        (expression-statement-needs-semicolon? else-expression))
                   ensure-semicolon))
               (= op 'dotimes) (emit-dotimes args level form)
               (= op 'for-loop)
               (let [[_options _bindings & body] args
                     source (emit-for-loop args level form)
                     else-expression (for-else-expression body)]
                 (cond-> source
                   (and else-expression
                        (expression-statement-needs-semicolon? else-expression))
                   ensure-semicolon))
               (= op 'else-clause)
               (fail! "else-clause can only be the final form of a for" form)
               (= op 'else-expression)
               (fail! "else-expression can only be the final form of a for" form)
               (= op 'block) (braced args level)
               (= op 'labeled-block)
               (let [[label & forms] args]
                 (when-not (or (symbol? label) (keyword? label) (string? label))
                   (fail! "labeled-block expects an identifier followed by statements" form))
                 (str (identifier label) ": " (braced forms level)))
               (= op 'defer) (if (= 1 (count args))
                               (let [nested (first args)]
                                 (str "defer "
                                      (if (and (seq? nested)
                                               (contains? #{'do 'block}
                                                          (first nested)))
                                        (braced (rest nested) level)
                                        (ensure-semicolon
                                         (emit-stmt nested level)))))
                               (fail! "defer expects one statement or do block" form))
               (= op 'comptime-stmt)
               (if (= 1 (count args))
                 (str "comptime "
                      (if (and (seq? (first args))
                               (contains? #{'do 'block} (ffirst args)))
                        (braced (rest (first args)) level)
                        (ensure-semicolon
                         (emit-stmt (first args) level))))
                 (fail! "comptime-stmt expects one statement" form))
               (= op 'nosuspend)
               (if (= 1 (count args))
                 (let [nested (first args)]
                   (if (and (seq? nested)
                            (contains? #{'do 'block} (first nested)))
                     (str "nosuspend " (braced (rest nested) level))
                     (str (emit-expr form) ";")))
                 (fail! "nosuspend expects one expression or block" form))
               (= op 'errdefer) (if (= 1 (count args))
                                  (let [nested (first args)]
                                    (str "errdefer "
                                         (if (and (seq? nested)
                                                  (contains? #{'do 'block}
                                                             (first nested)))
                                           (braced (rest nested) level)
                                           (ensure-semicolon
                                            (emit-stmt nested level)))))
                                  (if (and (= 2 (count args))
                                           (vector? (first args))
                                           (= 1 (count (first args))))
                                    (let [[capture] (first args)
                                          nested (second args)]
                                      (str "errdefer |" (identifier capture) "| "
                                           (if (and (seq? nested)
                                                    (contains? #{'do 'block}
                                                               (first nested)))
                                             (braced (rest nested) level)
                                             (ensure-semicolon
                                              (emit-stmt nested level)))))
                                    (fail! "errdefer expects a statement, optionally preceded by [error]"
                                           form)))
               (= op 'break) (case (count args)
                               0 "break;"
                               1 (str "break " (emit-expr (first args)) ";")
                               2 (str "break :" (identifier (first args)) " "
                                      (emit-expr (second args)) ";")
                               (fail! "break expects optional value or label and value" form))
               (= op 'break-label) (if (= 1 (count args))
                                     (str "break :" (identifier (first args)) ";")
                                     (fail! "break-label expects one label" form))
               (= op 'continue) (case (count args)
                                  0 "continue;"
                                  1 (str "continue :" (identifier (first args)) ";")
                                  2 (str "continue :" (identifier (first args)) " "
                                         (emit-expr (second args)) ";")
                                  (fail! "continue expects an optional label and switch operand"
                                         form))
               (= op 'unreachable) (if (empty? args)
                                     "unreachable;"
                                     (fail! "unreachable takes no arguments" form))
               :else (str (emit-expr form) ";"))))]
     (str (form-source-comment form) rendered))))

(defn emit-stmt-in
  "Emit one statement while resolving aliases in `context-ns`."
  [context-ns form]
  (binding [*keyword-context* context-ns]
    (emit-stmt form)))

(defn emit-statements
  ([forms] (emit-statements forms 0))
  ([forms level]
   (str/join "\n" (map #(emit-stmt % level) forms))))

(declare emit-returning-statements)

(defn- returning-braced
  [forms level]
  (str "{\n"
       (indent (inc level) (emit-returning-statements forms (inc level)))
       "\n" (indent level "}")))

(def ^:private non-value-statement-ops
  #{"const" "var" "set!" "assign" "while" "while-loop" "for" "inline-for" "for-loop"
    "dotimes" "when" "when-not"
    "if-capture-stmt"
    "switch-stmt" "labeled-switch-stmt" "block" "labeled-block"
    "defer" "comptime-stmt" "errdefer" "break" "break-label" "continue"
    "unreachable" "comment"
    "=" "+=" "-=" "*=" "/=" "%=" "+%=" "-%=" "*%="
    "+|=" "-|=" "*|=" "&=" "|=" "^=" "<<=" ">>="})

(defn- emit-returning-tail
  [form level]
  (let [rendered
        (if-not (seq? form)
          (str "return " (emit-expr form) ";")
          (let [[op & args] form]
            (cond
              (= op 'return)
              ;; emit-stmt already writes this form's source marker.
              (emit-stmt form level)

              (= op 'do)
              (emit-returning-statements args level)

              (= op 'let)
              (let [{:keys [pairs body]} (let-parts args form)]
                (when-not (seq body)
                  (fail! "A tail let in a non-void function requires a result expression"
                         form))
                (str "{\n"
                     (indent (inc level)
                             (str/join
                              "\n"
                              (concat
                               (map #(emit-stmt (let-local-form %) (inc level)) pairs)
                               [(emit-returning-statements body (inc level))])))
                     "\n" (indent level "}")))

              (= op 'if)
              (let [[test then else :as all] args]
                (when-not (= 3 (count all))
                  (fail! "A tail if in a non-void function requires an else branch" form))
                (str "if (" (emit-expr test) ") "
                     (returning-braced (branch-forms then) level)
                     " else "
                     (returning-braced (branch-forms else) level)))

              (contains? non-value-statement-ops (operator-name op))
              (fail! "The final form of a non-void Zig function must produce a value"
                     form {:operator op})

              :else
              (str "return " (emit-expr form) ";"))))]
    (if (and (seq? form) (= 'return (first form)))
      rendered
      (str (form-source-comment form) rendered))))

(defn- emit-returning-statements
  [forms level]
  (when-not (seq forms)
    (fail! "A non-void Zig function requires a result expression" forms))
  (str/join "\n"
            (concat (map #(emit-stmt % level) (butlast forms))
                    [(emit-returning-tail (last forms) level)])))

(defn emit-function-body
  "Emit a function body. Non-void functions implicitly return their final
  expression; explicit `return` remains available for early exits."
  ([forms return-type]
   (emit-function-body forms return-type true))
  ([forms return-type implicit-return?]
   (cond
     (and (= 1 (count forms))
          (seq? (first forms))
          (contains? #{'raw-statements 'raw-statement-chunks} (ffirst forms)))
     (let [[_ source & extra] (first forms)]
       (cond
         (and (= 'raw-statements (ffirst forms))
              (string? source) (empty? extra)) source
         (and (= 'raw-statement-chunks (ffirst forms))
              (vector? source) (every? string? source) (empty? extra))
         (apply str source)
         :else (fail! "invalid raw statement boundary" (first forms))))

     (or (= :void return-type) (false? implicit-return?))
     (emit-statements forms 0)

     :else
     (emit-returning-statements forms 0))))

(defn parse-typed-bindings
  "Parse `[x :- :i32 y :- :f64]`, `[x :i32 y :f64]`, or
  `[[x :i32] [y :f64]]` into `[{:name ... :type ...}]`."
  [bindings]
  (when-not (vector? bindings)
    (fail! "Typed bindings must be a vector" bindings))
  (cond
    (every? vector? bindings)
    (mapv (fn [entry]
            (case (count entry)
              2 {:name (first entry) :type (second entry) :properties {}}
              3 (if (map? (second entry))
                  {:name (first entry)
                   :type (nth entry 2)
                   :properties (second entry)}
                  (fail! "The middle typed-binding value must be a properties map"
                         entry))
              (fail! "Typed binding expects [name type] or [name properties type]"
                     entry)))
          bindings)

    (some #{':-} bindings)
    (do
      (when-not (zero? (mod (count bindings) 3))
        (fail! "Typed bindings using :- must contain name :- type triples" bindings))
      (mapv (fn [[n marker t :as triple]]
              (when-not (= marker ':-)
                (fail! "Expected :- in typed binding" triple))
            {:name n :type t :properties {}})
            (partition 3 bindings)))

    :else
    (do
      (when-not (zero? (mod (count bindings) 2))
        (fail! "Typed bindings must contain name/type pairs" bindings))
      (mapv (fn [[n t]] {:name n :type t :properties {}}) (partition 2 bindings)))))

(defn parse-struct-fields
  "Parse Malli-style struct entries.

  Each entry is `[field type]` or `[field properties type]`. Properties and
  Clojure metadata attached to the entry or field name are preserved under
  `:properties` for inspection and future Zig-specific field features."
  [fields]
  (when-not (vector? fields)
    (fail! "az/defstruct expects a vector of Malli-style field entries" fields
           {:expected '[[:field :type]]}))
  (mapv
   (fn [entry]
     (when-not (vector? entry)
       (fail! "Each az/defstruct field must be a vector: [field type] or [field properties type]"
              entry {:fields fields :expected '[:field :type]}))
     (let [[field properties type]
           (case (count entry)
             2 [(nth entry 0) {} (nth entry 1)]
             3 (if (map? (nth entry 1))
                 [(nth entry 0) (nth entry 1) (nth entry 2)]
                 (fail! "The middle value of a three-element struct field must be a properties map"
                        entry {:expected '[:field {:property "value"} :type]}))
             (fail! "Each az/defstruct field expects two or three values"
                    entry {:expected '[:field :type]}))]
       (when-not (or (keyword? field) (symbol? field) (string? field))
         (fail! "A struct field name must be a keyword, symbol, or string"
                entry {:field field}))
       {:name field
        :type type
        :properties (merge (meta field) (meta entry) properties)}))
   fields))

(defn- source-comment
  [{:keys [file line column]}]
  (let [file (when (and (string? file)
                        (< (count file) 4096)
                        (not (re-find #"[\r\n]" file)))
               file)]
    (when (or file line)
      (str "// Aguafria source: " (or file "<repl>")
         (when line (str ":" line))
         (when column (str ":" column)) "\n"))))

(defn- declaration-source-comment
  [{:keys [module name qualified-name]}]
  (let [declaration-name (or (some-> qualified-name str)
                             (when (and module name)
                               (str module "/" name)))]
    (when (and (seq declaration-name)
               (< (count declaration-name) 4096)
               (not (re-find #"[\r\n]" declaration-name)))
      (str "// Aguafria declaration: " declaration-name "\n"))))

(defn- declaration-sort-key
  [{:keys [kind name source-order]}]
  (if (some? source-order)
    [0 source-order]
    [1 ({:import 0 :raw 1 :struct 2 :const 3 :var 4 :extern-var 5 :field 6
         :comptime 7 :test 8 :fn-proto 9 :fn 10} kind 12)
     (str name)]))

(defn- declaration-prefix
  [zig-prefix default-prefix]
  (if (some? zig-prefix)
    (when (seq zig-prefix) (str zig-prefix " "))
    default-prefix))

(defn- explicitly-exported?
  [{:keys [attributes]}]
  (or (true? (:export attributes))
      (contains? (set (:attrs attributes)) :export)))

(defn- generic-function-argument?
  [{:keys [type properties]}]
  (or (= "comptime" (:zig/prefix properties))
      (contains? #{:anytype 'anytype :type 'type} type)))

(defn- dependency-declaration
  [declaration]
  (if (and (= :fn (:kind declaration))
           (:export? declaration)
           (not (explicitly-exported? declaration)))
    ;; Keep the public function's original C ABI without changing the private
    ;; hot-dispatch implementation ABI. The implementation pointer is shared
    ;; with the owning generation, whose internal function convention remains
    ;; Zig's default.
    (assoc declaration
           :export? false
           :public? true
           :dependency-default-export? true)
    declaration))

(defn- comment-lines
  [prefix value]
  (when (seq value)
    (str (->> (if (string? value) (str/split-lines value) value)
              (map #(str prefix (when (seq %) " ") %))
              (str/join "\n"))
         "\n")))

(defn- declaration-notes
  [{:keys [doc comments]}]
  (str (comment-lines "///" doc)
       (comment-lines "//" comments)))

(defn- namespace-root-import
  [{:keys [name zig-name value]}]
  (let [reference (and (symbol? value)
                       (:aguafria/zig-reference (meta value)))
        declaration-name (identifier (or zig-name name))]
    (when (and (= :namespace-root (:kind reference))
               (string? (:import-name reference))
               (= declaration-name
                  (or (:import-alias reference) (:zig-name reference))))
      reference)))

(defn emit-declaration
  "Emit a normalized declaration descriptor."
  [{:keys [kind name type value fields body args return export? public? layout
           source code import-name leading-source zig-prefix zig-qualifiers
           zig-name implicit-return? emit-source-comment? test-name
           has-value? align doc comments body-prefix-source
           dependency-default-export? import-container]
    :as declaration}]
  (let [declaration-name (or zig-name name)]
    (str
     leading-source
     (declaration-notes {:doc doc :comments comments})
     (when (or *source-mapping?* (not= false emit-source-comment?))
       (str (declaration-source-comment declaration)
            (source-comment source)))
     (case kind
     :import
     (if (string? import-name)
       (str (declaration-prefix zig-prefix "") "const "
            (identifier declaration-name) " = @import(" (zig-string import-name) ")"
            (when import-container
              (str "." (identifier import-container)))
            ";")
       (fail! "Import declaration requires string :import-name" declaration))

     :raw
     (if (string? code) code (fail! "Raw declaration requires string :code" declaration))

     :const
     (let [rendered (if-let [import (namespace-root-import declaration)]
                      (str "@import(" (zig-string (:import-name import)) ")"
                           (named-module-selector (:import-namespace import)))
                      (emit-expr value))]
       (str (declaration-prefix zig-prefix
                                (when (not= false public?) "pub "))
            "const " (identifier declaration-name)
            (when type (str ": " (emit-type type)))
            (when (seq zig-qualifiers) (str " " zig-qualifiers))
            " = " rendered
            (expression-terminator rendered)))

     :var
     (let [rendered (emit-expr value)]
       (str (declaration-prefix zig-prefix
                                (when (not= false public?) "pub "))
            "var " (identifier declaration-name)
            (when type (str ": " (emit-type type)))
            (when (seq zig-qualifiers) (str " " zig-qualifiers))
            " = " rendered
            (expression-terminator rendered)))

     :extern-var
     (str (declaration-prefix zig-prefix
                              (when public? "pub extern "))
          "var " (identifier declaration-name)
          (when type (str ": " (emit-type type)))
          (when (seq zig-qualifiers) (str " " zig-qualifiers))
          ";")

     :struct
     (str (declaration-prefix zig-prefix
                              (when (not= false public?) "pub "))
          "const " (identifier declaration-name) " = "
          (case layout
            :extern "extern struct"
            :packed "packed struct"
            :normal "struct"
            nil "struct"
            (fail! "Struct :layout must be :extern, :packed, or :normal" declaration))
          " {\n"
          (indent 1 (->> fields
                         (map (fn [{:keys [name type]}]
                                (str (identifier name) ": " (emit-type type) ",")))
                         (str/join "\n")))
          "\n};")

     :field
     (str (declaration-prefix zig-prefix "")
          (identifier declaration-name) ": " (emit-type type)
          (when align (str " align(" (emit-expr align) ")"))
          (when has-value? (str " = " (emit-expr value)))
          ",")

     :comptime
     (let [body-source (binding [*source-mapping?*
                                 (not= false emit-source-comment?)]
                         (emit-function-body body :void false))]
       (str "comptime {\n"
            (when (seq body-source) (str (indent 1 body-source) "\n"))
            "}"))

     :fn-proto
     (str (declaration-prefix zig-prefix
                              (when public? "pub "))
          "fn " (identifier declaration-name) "("
          (->> args
               (map (fn [{:keys [name properties type]}]
                      (if (:zig/variadic properties)
                        "..."
                        (str (when-let [prefix (:zig/prefix properties)]
                               (str prefix " "))
                             (identifier name) ": " (emit-type type)))))
               (str/join ", "))
          ")"
          (when (seq zig-qualifiers) (str " " zig-qualifiers))
          " " (emit-type return) ";")

     :fn
     (let [body-source (binding [*source-mapping?*
                                 (not= false emit-source-comment?)]
                         (emit-function-body body return implicit-return?))
           body-source (str (when (seq body-prefix-source)
                              (str body-prefix-source
                                   (when (seq body-source) "\n")))
                            body-source)]
       (str (declaration-prefix zig-prefix
                                (cond
                                  (and export? public?) "pub export "
                                  export? "export "
                                  public? "pub "
                                  :else ""))
            "fn " (identifier declaration-name) "("
            (->> args
                 (map (fn [{:keys [name properties type]}]
                        (if (:zig/variadic properties)
                          "..."
                          (str (when-let [prefix (:zig/prefix properties)]
                                 (str prefix " "))
                               (identifier name) ": " (emit-type type)))))
                 (str/join ", "))
            ")"
            (when (seq zig-qualifiers) (str " " zig-qualifiers))
            (when (and (or export? dependency-default-export?)
                       (nil? zig-prefix)
                       (not (seq zig-qualifiers))
                       (not-any? generic-function-argument? args))
              " callconv(.c)")
            " " (emit-type return) " {\n"
            (when (seq body-source) (str (indent 1 body-source) "\n"))
            "}"))

     :test
     (let [body-source (binding [*source-mapping?*
                                 (not= false emit-source-comment?)]
                         (emit-function-body body :void false))]
       (str "test"
            (when (some? test-name)
              (str " " (if (string? test-name)
                          (zig-string test-name)
                          (identifier test-name))))
            " {\n"
            (when (seq body-source) (str (indent 1 body-source) "\n"))
            "}"))

     (fail! "Unknown Zig declaration kind" declaration {:kind kind})))))

(defn- exact-zig-symbol
  [name]
  (with-meta (symbol name) {:zig/name name}))

(declare synthesized-import-declarations)

(defn- emit-reloadable-function
  [declaration {:keys [implementation dispatch-frame trampoline
                       dispatch-type dispatch getter setter
                       active-counter active-depth active-tracking
                       publication-epoch emit-getter? linkable?]}]
  (let [dispatch-frame (or dispatch-frame
                           (str implementation "_dispatch_frame"))
        trampoline (or trampoline (str implementation "_trampoline"))
        original-body-prefix-source (:body-prefix-source declaration)
        reload-args
        (mapv (fn [index argument]
                (if (= "_" (str (:name argument)))
                  (assoc argument :name
                         (symbol (str "__aguafria_discard_" index)))
                  argument))
              (range) (:args declaration))
        discarded-arguments
        (->> (map vector (:args declaration) reload-args)
             (keep (fn [[original renamed]]
                     (when (= "_" (str (:name original)))
                       (:name renamed))))
             vec)
        discard-source
        (apply str
               (map #(str "_ = " (identifier %) ";\n")
                    discarded-arguments))
        arguments (mapv :name reload-args)
        argument-names (set arguments)
        target (str dispatch "_target")
        target-address (str dispatch "_target_address")
        track-active (str implementation "_track_active")
        frame-value (str dispatch-frame "_value")
        frame-result (str dispatch-frame "_result")
        frame-argument-names
        (mapv #(str dispatch-frame "_arg_" %) (range (count reload-args)))
        inferred-error-return?
        (= "!" (str/trim (or (:zig-qualifiers declaration) "")))
        return-type-source
        (str (when inferred-error-return? "!")
             (emit-type (:return declaration)))
        void-return?
        (and (= :void (:return declaration))
             (not inferred-error-return?))
        noreturn? (= :noreturn (:return declaration))
        frame-used? (or (seq reload-args)
                        (not (or void-return? noreturn?)))
        frame-source
        (str "const " dispatch-frame " = extern struct {\n"
             "    result: ?*anyopaque,\n"
             (apply str
                    (map-indexed
                     (fn [index _]
                       (str "    arg_" index ": *const anyopaque,\n"))
                     reload-args))
             "};")
        trampoline-argument-source
        (apply str
               (map-indexed
                (fn [index argument]
                  (str "const " (nth frame-argument-names index)
                       ": *const " (emit-type (:type argument))
                       " = @ptrCast(@alignCast(" frame-value ".arg_"
                       index "));\n"))
                reload-args))
        trampoline-call-source
        (str implementation "("
             (str/join ", " (map #(str % ".*") frame-argument-names))
             ")")
        direct-call-source
        (str implementation "("
             (str/join ", " (map (comp identifier :name) reload-args))
             ")")
        direct-invocation-source
        (cond
          noreturn? direct-call-source
          void-return? (str direct-call-source ";\nreturn;")
          :else (str "return " direct-call-source ";"))
        trampoline-source
        (str "fn " trampoline "(raw_frame: *anyopaque) callconv(.c) void {\n"
             (if frame-used?
               (str "    const " frame-value ": *" dispatch-frame
                    " = @ptrCast(@alignCast(raw_frame));\n")
               "    _ = raw_frame;\n")
             (when (seq reload-args)
               (str (indent 1 trampoline-argument-source) "\n"))
             (cond
               (or void-return? noreturn?)
               (str "    " trampoline-call-source ";\n")

               :else
               (str "    const " frame-result ": *" return-type-source
                    " = @ptrCast(@alignCast(" frame-value ".result.?));\n"
                    "    " frame-result ".* = " trampoline-call-source ";\n"))
             "}")
        wrapper-frame-fields
        (apply str
               (map-indexed
                (fn [index argument]
                  (str "    .arg_" index " = @ptrCast(&"
                       (identifier (:name argument)) "),\n"))
                reload-args))
        wrapper-invocation-source
        (str (when-not (or void-return? noreturn?)
               (str "var " frame-result ": " return-type-source
                    " = undefined;\n"))
             "var " frame-value ": " dispatch-frame " = .{\n"
             "    .result = "
             (if (or void-return? noreturn?)
               "null"
               (str "@ptrCast(&" frame-result ")"))
             ",\n"
             wrapper-frame-fields
             "};\n"
             target "(@ptrCast(&" frame-value "));\n"
             (cond
               noreturn? "unreachable;"
               void-return? "return;"
               :else (str "return " frame-result ";")))
        discard-parameter-form?
        (fn [form]
          (let [operator (when (seq? form) (first form))]
            (and (symbol? operator)
                 (= "set!" (name operator))
                 (= '_ (second form))
                 (contains? argument-names (nth form 2 nil)))))
        terminating-form?
        (fn terminating-form? [form]
          (let [operator (when (seq? form) (first form))
                operator-name (when (symbol? operator) (name operator))]
            (cond
              (and (symbol? form) (= "unreachable" (name form))) true
              (contains? #{"return" "unreachable"} operator-name) true
              (contains? #{"block" "labeled-block"} operator-name)
              (some-> form last terminating-form?)
              (= "if" operator-name)
              (and (<= 4 (count form))
                   (terminating-form? (nth form 2))
                   (terminating-form? (nth form 3)))
              :else false)))
        comptime-body
        (into [] (remove discard-parameter-form?) (:body declaration))
        comptime-body-terminates?
        (or (:implicit-return? declaration)
            (some-> comptime-body last terminating-form?))
        ;; Comptime calls cannot cross an indirect dispatch cell. Keep the
        ;; original body directly in the stable wrapper so a function invoked
        ;; thousands of times while constructing a type/table consumes exactly
        ;; the same evaluator budget as ordinary Zig. Runtime calls still use
        ;; the independently replaceable implementation below. Discards of
        ;; parameters are omitted from this copy because the wrapper's runtime
        ;; dispatch path already uses every parameter, making those discards
        ;; pointlessly invalid in Zig.
        comptime-body-source
        (binding [*reloadable-state-references?* false]
          (emit-function-body comptime-body
                              (:return declaration)
                              (:implicit-return? declaration)))
        implementation-declaration
        (assoc declaration
               :args reload-args
               :name (symbol implementation)
               :zig-name implementation
               :export? false
               :public? false
               ;; `inline fn` itself is not a stable addressable dispatch
               ;; target. Keep the public wrapper inline, but materialize its
               ;; versioned implementation as an ordinary private function so
               ;; the generated getter/cell can retain a real pointer.
               :zig-prefix nil
               :dependency-default-export? false
               :doc nil
               :comments nil
               ;; Keep the originating declaration location on the actual
               ;; versioned implementation as well as its public dispatch
               ;; wrapper. Zig errors normally occur in this body.
               :source (:source declaration)
               :emit-source-comment? false
               :body-prefix-source
               (str discard-source
                    "const " track-active " = !@inComptime() and @atomicLoad(bool, &"
                    active-tracking ", .acquire);\n"
                    "const " track-active "_outermost = if (" track-active
                    ") " active-depth " == 0 else false;\n"
                    "if (" track-active ") {\n"
                    "    " active-depth " += 1;\n"
                    "    if (" track-active "_outermost) { _ = @atomicRmw(usize, &"
                    active-counter ", .Add, 1, .acq_rel); }\n"
                    "}\n"
                    "defer if (" track-active ") {\n"
                    "    " active-depth " -= 1;\n"
                    "    if (" track-active
                    "_outermost) { _ = @atomicRmw(usize, &" active-counter
                    ", .Sub, 1, .acq_rel); }\n"
                    "};"
                    (when-not (str/blank? original-body-prefix-source)
                      (str "\n" original-body-prefix-source))))
        implementation-source
        (emit-declaration implementation-declaration)
        wrapper-declaration
        (assoc declaration
               :args reload-args
               :body []
               :implicit-return? false
               :body-prefix-source
               (str "if (@inComptime()) {\n"
                    (indent 1 (str comptime-body-source
                                   (when-not comptime-body-terminates?
                                     "\nreturn;")))
                    "\n"
                    "}\n"
                    "const " target-address ": usize = publication: {\n"
                    "    if (" publication-epoch ") |epoch| {\n"
                    "        while (true) {\n"
                    "            const before = @atomicLoad(usize, epoch, .acquire);\n"
                    "            if ((before & 1) != 0) continue;\n"
                    "            const candidate = @atomicLoad(usize, &"
                    dispatch ", .acquire);\n"
                    "            const after = @atomicLoad(usize, epoch, .acquire);\n"
                    "            if (before == after) break :publication candidate;\n"
                    "        }\n"
                    "    }\n"
                    "    break :publication @atomicLoad(usize, &" dispatch
                    ", .acquire);\n"
                    "};\n"
                    "if (" target-address " == 0) {\n"
                    (indent 1 direct-invocation-source) "\n"
                    "}\n"
                    "const " target ": " dispatch-type
                    " = @ptrFromInt(" target-address ");\n"
                    wrapper-invocation-source))
         wrapper-source
         (emit-declaration wrapper-declaration)]
    (str implementation-source "\n\n"
         frame-source "\n\n"
         trampoline-source "\n\n"
         "const " dispatch-type " = @TypeOf(&" trampoline ");\n"
         "var " dispatch ": usize = 0;\n\n"
         (when emit-getter?
           ;; The compiler's tiny root imports this generated module and
           ;; retains the getter by address. `export` gives it a native symbol
           ;; but does not make it visible through Zig's module namespace;
           ;; `pub export` is required for both forms of linkage.
           (str "pub export fn " getter "() callconv(.c) usize {\n"
                "    return @intFromPtr(&" trampoline ");\n"
                "}\n\n"))
         (when linkable? "pub ")
         "export fn " setter "(address: usize) callconv(.c) void {\n"
         "    @atomicStore(usize, &" dispatch ", address, .release);\n"
         "}\n\n"
         wrapper-source)))

(defn- emit-reloadable-state
  [declaration {:keys [accessor getter setter size-getter align-getter
                       linkable? emit-native-helpers?]}]
  (let [name (identifier (or (:zig-name declaration) (:name declaration)))]
    (str (emit-declaration declaration) "\n\n"
         "var " accessor "_pointer: @TypeOf(&" name ") = &" name ";\n\n"
         "pub fn " accessor "() @TypeOf(&" name ") {\n"
         "    return @atomicLoad(@TypeOf(&" name "), &" accessor
         "_pointer, .acquire);\n"
         "}"
         (when emit-native-helpers?
           (str "\n\n"
                (when linkable? "pub ")
                "export fn " getter "() callconv(.c) usize {\n"
                "    return @intFromPtr(&" name ");\n"
                "}\n\n"
                (when linkable? "pub ")
                "export fn " setter "(address: usize) callconv(.c) void {\n"
                "    @atomicStore(@TypeOf(&" name "), &" accessor
                "_pointer, @ptrFromInt(address), .release);\n"
                "}\n\n"
                (when linkable? "pub ")
                "export fn " size-getter "() callconv(.c) usize {\n"
                "    return @sizeOf(@TypeOf(" name "));\n"
                "}\n\n"
                (when linkable? "pub ")
                "export fn " align-getter "() callconv(.c) usize {\n"
                "    return @alignOf(@TypeOf(" name "));\n"
                "}")))))

(defn- emit-development-declaration
  "Emit one non-dispatched declaration in a reloadable module.

  Container-level constants and comptime blocks must preserve Zig's direct
  access to module state. Routing a referenced defvar through its atomically
  swappable runtime pointer makes an otherwise comptime-known initializer a
  runtime expression (for example `const io = io_threaded.io()`). Runtime
  function bodies continue to use the stable state accessor."
  [declaration]
  (if (contains? #{:const :comptime} (:kind declaration))
    (binding [*reloadable-state-references?* false]
      (emit-declaration declaration))
    (emit-declaration declaration)))

(defn emit-reloadable-module
  "Emit a development shared-library module with stable function dispatch and
  versioned state hooks. Ordinary `emit-module` remains the direct/static Zig
  source used for inspection and final builds."
  ([module-name declarations dispatch-specs]
   (emit-reloadable-module module-name declarations dispatch-specs {} {}))
  ([module-name declarations dispatch-specs state-specs]
   (emit-reloadable-module module-name declarations dispatch-specs state-specs {}))
  ([module-name declarations dispatch-specs state-specs
   {:keys [dependency? linkable-declaration-keys extra-body-source top-level?
           external-publication-epoch-symbol]}]
   (let [context-ns (or (some-> module-name str symbol find-ns) *ns*)]
     (binding [*source-mapping?* true
               *keyword-context* context-ns
               *reloadable-state-references?* true
               *reloadable-state-accessors*
               (set (map :accessor (vals state-specs)))
               ;; Development dylibs use a named container so their root can
               ;; import the logical module without colliding with wrappers.
               ;; A generated development application replaces the original
               ;; Zig file, so it must retain the file's ordinary top-level
               ;; namespace and ordinary relative-import semantics.
               *named-module-imports?* (not top-level?)
               *logical-type-names?* true]
       (let [imports (remove nil? (synthesized-import-declarations declarations
                                                                     true))
             {:keys [active-counter active-depth active-tracking
                     active-tracking-setter active-getter publication-epoch
                     publication-epoch-setter]}
             (some-> dispatch-specs first val)
             linkable? (boolean (seq linkable-declaration-keys))
             imports-source
             (->> imports
                  (sort-by declaration-sort-key)
                  (map emit-development-declaration)
                  (str/join "\n\n"))
             helper-source
             (when active-counter
               (str "var " active-counter ": usize = 0;\n"
                    "threadlocal var " active-depth ": usize = 0;\n\n"
                    "var " active-tracking ": bool = true;\n\n"
                    (when linkable? "pub ")
                    "export fn " active-tracking-setter
                    "(enabled: u8) callconv(.c) void {\n"
                    "    @atomicStore(bool, &" active-tracking
                    ", enabled != 0, .release);\n"
                    "}\n\n"
                    (when linkable? "pub ")
                    "export fn " active-getter "() callconv(.c) usize {\n"
                    "    return @atomicLoad(usize, &" active-counter
                    ", .acquire);\n"
                    "}\n\n"
                    (if external-publication-epoch-symbol
                      (str "extern var " external-publication-epoch-symbol
                           ": usize;\n"
                           "const " publication-epoch
                           ": ?*const usize = &"
                           external-publication-epoch-symbol ";")
                      (str "var " publication-epoch
                           ": ?*const usize = null;\n\n"
                           (when linkable? "pub ")
                           "export fn " publication-epoch-setter
                           "(address: usize) callconv(.c) void {\n"
                           "    " publication-epoch
                           " = @ptrFromInt(address);\n"
                           "}"))))
             declarations-source
             (->> declarations
                  (sort-by declaration-sort-key)
                  (map (fn [declaration]
                         (let [declaration
                               (if dependency?
                                 (dependency-declaration declaration)
                                 declaration)]
                           (cond
                             (get dispatch-specs (:declaration-key declaration))
                             (emit-reloadable-function
                              declaration
                              (cond->
                               (get dispatch-specs
                                    (:declaration-key declaration))
                                (contains? linkable-declaration-keys
                                           (:declaration-key declaration))
                                (assoc :linkable? true)))

                             (get state-specs (:declaration-key declaration))
                             (emit-reloadable-state
                              declaration
                              (let [linkable?
                                    (contains? linkable-declaration-keys
                                               (:declaration-key declaration))]
                                (assoc
                                 (get state-specs
                                      (:declaration-key declaration))
                                 :linkable? linkable?
                                 :emit-native-helpers?
                                 (or (not dependency?) linkable?))))

                             :else
                             (emit-development-declaration declaration)))))
                  (str/join "\n\n"))
             body-source (str/join "\n\n" (remove str/blank?
                                                   [logical-type-name-helper
                                                    helper-source
                                                    declarations-source
                                                    extra-body-source]))
             module-source
             (str (when-not (str/blank? imports-source)
                    (str imports-source "\n\n"))
                  (if top-level?
                    body-source
                    (str "pub const " (named-module-container module-name)
                         " = struct {\n"
                         (indent 1 body-source) "\n"
                         "};")))]
         (str "// Generated by Aguafria for reloadable development.\n"
              "// Module: " module-name "\n\n"
              module-source
              "\n"))))))

(defn- nested-doc-attributes
  [declaration]
  (let [[docstring declaration]
        (if (and (string? (first declaration)) (next declaration))
          [(first declaration) (next declaration)]
          [nil declaration])
        [attributes declaration]
        ;; An enum member can consist solely of its name and an attributes
        ;; map, for example `(az/enum-field-decl clojure-name
        ;; {:zig/name "@\"zig-name\""})`. Requiring a following value made
        ;; that map look like the enum's explicit Zig value. Object values are
        ;; represented by `az/object`, so an ordinary map in this position is
        ;; unambiguously declaration metadata throughout the structural API.
        (if (map? (first declaration))
          [(cond-> (first declaration)
             (not (contains? (first declaration) :attrs))
             (assoc :attrs #{}))
           (next declaration)]
          [{:attrs #{}} declaration])]
    [(or docstring (:doc attributes)) attributes declaration]))

(defn- nested-base
  [kind name attributes]
  (let [attributes (merge (meta name) attributes)
        compact? (contains? attributes :attrs)
        attrs (set (:attrs attributes))]
    {:kind kind
     :name name
     :zig-name (:zig/name attributes)
     :doc (:doc attributes)
     :comments (:comments attributes)
     :export? (if compact? (contains? attrs :export)
                  (not= false (:export attributes)))
     :public? (if compact? (contains? attrs :public)
                  (if (contains? attributes :public)
                    (:public attributes)
                    (not (:private attributes))))
     :leading-source (:zig/leading attributes)
     :zig-prefix (:zig/prefix attributes)
     :zig-qualifiers (:zig/qualifiers attributes)
     :implicit-return? (if compact? (contains? attrs :implicit-return)
                           (not= false (:implicit-return attributes)))
     :emit-source-comment? (if compact? (contains? attrs :source-comment)
                               (not= false (:source-comment attributes)))
     :align (:zig/align attributes)}))

(defn- nested-declaration
  [form]
  (let [[source-operator & declaration] form
        operator (or (resolved-syntax-operator (or *keyword-context* *ns*)
                                                  source-operator)
                     source-operator)]
    (case operator
      fn-decl
      (let [[name & declaration] declaration
            [doc attributes declaration] (nested-doc-attributes declaration)
            [marker return bindings & body] declaration]
        (when-not (= marker ':-)
          (fail! "fn-decl expects name, optional doc/attributes, :-, return, args, and body"
                 form))
        (merge (nested-base :fn name attributes)
               {:doc doc :return return
                :args (parse-typed-bindings bindings) :body (vec body)}))

      fn-proto-decl
      (let [[name & declaration] declaration
            [_doc attributes declaration] (nested-doc-attributes declaration)
            [marker return bindings] declaration]
        (when-not (and (= marker ':-) (= 3 (count declaration)))
          (fail! "fn-proto-decl expects name, optional attributes, :-, return, and args"
                 form))
        (merge (nested-base :fn-proto name attributes)
               {:return return :args (parse-typed-bindings bindings)}))

      const-decl
      (let [[name & declaration] declaration
            [doc attributes declaration] (nested-doc-attributes declaration)
            [type value] (case (count declaration)
                           1 [nil (first declaration)]
                           2 [(first declaration) (second declaration)]
                           (fail! "const-decl expects optional type and one value" form))]
        (merge (nested-base :const name attributes)
               {:doc doc :type (when-not (= '_ type) type) :value value}))

      var-decl
      (let [[name & declaration] declaration
            [doc attributes declaration] (nested-doc-attributes declaration)
            [type value] (case (count declaration)
                           1 [nil (first declaration)]
                           2 [(first declaration) (second declaration)]
                           (fail! "var-decl expects optional type and one value" form))]
        (merge (nested-base :var name attributes)
               {:doc doc :type (when-not (= '_ type) type) :value value}))

      extern-var-decl
      (let [[name & declaration] declaration
            [doc attributes declaration] (nested-doc-attributes declaration)
            [marker type] declaration]
        (when-not (and (= marker ':-) type (= 2 (count declaration)))
          (fail! "extern-var-decl expects name, optional attributes, :-, and type"
                 form))
        (merge (nested-base :extern-var name attributes)
               {:doc doc :type type}))

      struct-decl
      (let [[name & declaration] declaration
            [doc attributes declaration] (nested-doc-attributes declaration)
            [fields] declaration]
        (when-not (= 1 (count declaration))
          (fail! "struct-decl expects one Malli-style field vector" form))
        (merge (nested-base :struct name attributes)
               {:doc doc :layout (or (:layout attributes) :normal)
                :fields (parse-struct-fields fields)}))

      import-decl
      (let [[name import-name _members] declaration]
        (merge (nested-base :import name {}) {:import-name import-name}))

      field-decl
      (let [[name & declaration] declaration
            [doc attributes declaration] (nested-doc-attributes declaration)
            [type & initializer] declaration]
        (when-not (and type (<= (count initializer) 1))
          (fail! "field-decl expects name, optional doc/attributes, type, and optional value"
                 form))
        (merge (nested-base :field name attributes)
               {:doc doc :type type :has-value? (boolean (seq initializer))
                :value (first initializer)}))

      enum-field-decl
      (let [[name & declaration] declaration
            ;; A Zig enum value cannot be a string. Consequently a terminal
            ;; string here is documentation, not an explicit value. This is
            ;; the one nested declaration where the generic doc/value parser
            ;; would otherwise be ambiguous because no type follows the doc.
            [doc attributes initializer]
            (if (and (= 1 (count declaration))
                     (string? (first declaration)))
              [(first declaration) {:attrs #{}} nil]
              (nested-doc-attributes declaration))]
        (when-not (<= (count initializer) 1)
          (fail! "enum-field-decl expects name, optional doc/attributes, and optional value"
                 form))
        (merge (nested-base :enum-field name attributes)
               {:doc doc :attributes (merge (meta name) attributes)
                :has-value? (boolean (seq initializer))
                :value (first initializer)}))

      tuple-field-decl
      (let [[doc attributes declaration] (nested-doc-attributes declaration)
            [type & initializer] declaration]
        (when-not (and type (<= (count initializer) 1))
          (fail! "tuple-field-decl expects optional doc/attributes, type, and optional value"
                 form))
        (merge (nested-base :tuple-field nil attributes)
               {:doc doc :attributes attributes
                :type type
                :has-value? (boolean (seq initializer))
                :value (first initializer)}))

      comptime-decl
      (let [[name & declaration] declaration
            [docstring attributes body] (nested-doc-attributes declaration)]
        (merge (nested-base :comptime name attributes)
               {:doc docstring :body (vec body)}))

      test-decl
      (let [[attributes declaration] (if (map? (first declaration))
                                       [(first declaration) (next declaration)]
                                       [{:attrs #{}} declaration])
            [test-name & body] declaration]
        (merge (nested-base :test nil attributes)
               {:test-name test-name :body (vec body)}))

      (fail! "Unknown nested Zig declaration" form {:operator operator}))))

(defn container-description
  "Return the normalized options and member declarations for an Aguafria
  `container` form, or nil when the form is not a container. Runtime tooling
  uses the same parser as emission instead of independently guessing syntax."
  ([form] (container-description *ns* form))
  ([context-ns form]
   (when (seq? form)
     (let [[source-operator options & members] form
           operator (or (resolved-syntax-operator context-ns source-operator)
                        source-operator)]
       (when (= 'container operator)
         (when-not (map? options)
           (fail! "container expects an option map with :kind" form))
         {:options options
          :members (mapv #(binding [*keyword-context* context-ns]
                            (nested-declaration %))
                         members)})))))

(defn- emit-container-member
  [form]
  (let [{:keys [kind name type value has-value? attributes] :as declaration}
        (nested-declaration form)]
    (case kind
      :enum-field
      (str (:leading-source declaration)
           (declaration-notes declaration)
           (identifier (or (:zig/name attributes) name))
           (when has-value? (str " = " (emit-expr value))) ",")

      :tuple-field
      (str (:leading-source declaration)
           (declaration-notes declaration)
           (when-let [prefix (:zig/prefix attributes)] (str prefix " "))
           (emit-type type)
           (when-let [align (:zig/align attributes)]
             (str " align(" (emit-expr align) ")"))
           (when has-value? (str " = " (emit-expr value))) ",")

      (emit-declaration declaration))))

(defn- emit-container
  [form]
  (let [[_ options & members] form]
    (when-not (and (map? options) (keyword? (:kind options)))
      (fail! "container expects an option map with :kind" form))
    (let [{:keys [kind layout enum? argument zig/trailing attrs]} options
          enum? (or enum? (contains? (set attrs) :enum))
          layout-source (case layout
                          :extern "extern "
                          :packed "packed "
                          :normal ""
                          nil ""
                          (fail! "container layout must be :extern, :packed, or :normal"
                                 form))
          kind-source
          (case kind
            :struct (str "struct"
                         (when argument (str "(" (emit-type argument) ")")))
            :enum (str "enum" (when argument (str "(" (emit-type argument) ")")))
            :union (cond
                     enum? (str "union(enum"
                                (when argument (str "(" (emit-type argument) ")"))
                                ")")
                     argument (str "union(" (emit-type argument) ")")
                     :else "union")
            :opaque "opaque"
            (fail! "container kind must be :struct, :enum, :union, or :opaque"
                   form))
          member-source (str/join "\n" (map emit-container-member members))
          inner-source (str (when (seq member-source) (indent 1 member-source))
                            trailing)]
      (str layout-source kind-source " {"
           (when (seq inner-source)
             (str "\n" inner-source
                  (when-not (str/ends-with? inner-source "\n") "\n")))
           "}"))))

(defn declaration-imports
  "Return the namespace imports referenced by prepared declaration forms.

  Each entry is keyed by the Zig alias and records the logical import name and
  originating Clojure namespace. The data is serializable and can also be used
  by the compiler runtime to resolve generated module dependencies."
  [declarations]
  (let [explicit
        (into (sorted-map)
              (keep
               (fn [{:keys [name zig-name attributes]}]
                 (let [import-name (:zig/import-name attributes)
                       import-namespace (:zig/import-namespace attributes)]
                   (when (and (string? import-name) import-namespace)
                     (let [alias (identifier (or zig-name name))]
                       [alias {:alias alias
                               :import-name import-name
                               :namespace (symbol (str import-namespace))}])))))
              declarations)]
    (reduce
     (fn [imports value]
       (if-let [{:keys [import-alias import-name import-namespace source-order]}
                (and (symbol? value)
                     (:aguafria/zig-reference (meta value)))]
         (if (and import-alias import-name)
           (let [entry {:alias import-alias
                        :import-name import-name
                        :namespace import-namespace
                        :source-order source-order}]
             (if-let [existing (get imports import-alias)]
               (if (= (select-keys existing [:alias :import-name :namespace])
                      (select-keys entry [:alias :import-name :namespace]))
                 (assoc imports import-alias
                        (assoc existing :source-order
                               (or (:source-order existing) source-order)))
                 (fail! "Two required namespaces resolve to the same Zig import alias"
                        value {:alias import-alias
                               :first existing
                               :second entry}))
               (assoc imports import-alias entry)))
           imports)
         imports))
     explicit
     (tree-seq coll? seq declarations))))

(defn- synthesized-import-declarations
  ([declarations]
   (synthesized-import-declarations declarations false))
  ([declarations development?]
   (let [explicit (into {}
                       (keep (fn [{:keys [kind name zig-name import-name
                                         attributes]
                                  :as declaration}]
                               (let [ordinary-import
                                     (:zig/import-name attributes)
                                     root-import
                                     (namespace-root-import declaration)]
                                 (cond
                                   (= :import kind)
                                   [(identifier (or zig-name name)) import-name]

                                   root-import
                                   [(identifier (or zig-name name))
                                    (:import-name root-import)]

                                   (string? ordinary-import)
                                   [(identifier (or zig-name name))
                                    ordinary-import]))))
                       declarations)]
     (mapv
     (fn [[alias {:keys [import-name namespace source-order]}]]
       (let [import-name (if (and development? namespace)
                           (str namespace)
                           import-name)]
       (when-let [explicit-import (get explicit alias)]
         (when-not (= explicit-import import-name)
          (fail! "A synthesized namespace import conflicts with an explicit module Var"
                  alias {:alias alias
                         :namespace-import import-name
                         :explicit-import explicit-import})))
       (when-not (contains? explicit alias)
         {:kind :import
          :name (symbol alias)
          :source-order (or source-order Long/MIN_VALUE)
          :public? false
          :export? false
          :emit-source-comment? false
          :import-name import-name
          :import-container (when (and development? namespace)
                              (named-module-container namespace))})))
     (declaration-imports declarations)))))

(defn emit-named-module
  "Emit a compiler root whose converted namespace imports select Aguafria's
  stable named-module containers.  This is runtime/build plumbing; ordinary
  `emit-module` remains clean Zig source for inspection and materialization."
  [module-name declarations]
  (let [context-ns (or (some-> module-name str symbol find-ns) *ns*)]
    (binding [*source-mapping?* true
              *keyword-context* context-ns
              *named-module-imports?* true
              *logical-type-names?* true]
      (let [imports (remove nil? (synthesized-import-declarations declarations
                                                                    true))
            imports-source (->> imports
                                (sort-by declaration-sort-key)
                                (map emit-declaration)
                                (str/join "\n\n"))
            declarations-source (->> declarations
                                     (sort-by declaration-sort-key)
                                     (map emit-declaration)
                                     (str/join "\n\n"))
            container (named-module-container module-name)
            public-aliases
            (->> declarations
                 (filter :public?)
                 (filter #(contains? #{:const :struct :fn :fn-proto}
                                     (:kind %)))
                 (map (fn [declaration]
                        (let [declaration-name
                              (identifier (or (:zig-name declaration)
                                              (:name declaration)))]
                          (str "pub const " declaration-name " = " container "."
                               declaration-name ";"))))
                 distinct
                 sort
                 (str/join "\n"))]
        (str "// Generated by Aguafria. Edit the Clojure declarations, not this file.\n"
             "// Module: " module-name "\n\n"
             (when-not (str/blank? imports-source)
               (str imports-source "\n\n"))
             "pub const " container " = struct {\n"
             (indent 1 (str logical-type-name-helper "\n\n"
                            declarations-source)) "\n"
             "};"
             (when-not (str/blank? public-aliases)
               (str "\n\n" public-aliases))
             "\n")))))

(defn emit-module
  "Emit a complete deterministic Zig source module from declarations."
  ([module-name declarations]
   (let [context-ns (or (some-> module-name str symbol find-ns) *ns*)]
   (binding [*source-mapping?* true
             *keyword-context* context-ns]
     (let [imports (remove nil? (synthesized-import-declarations declarations))
           declarations (concat imports declarations)]
       (str "// Generated by Aguafria. Edit the Clojure declarations, not this file.\n"
            "// Module: " module-name "\n\n"
            (->> declarations
               (sort-by declaration-sort-key)
               (map emit-declaration)
               (str/join "\n\n"))
            "\n")))))
  ([context-ns module-name declarations]
   (emit-module module-name
                (mapv (partial prepare-declaration context-ns) declarations))))

(defn emit-static-dependency-module
  "Emit an ordinary static dependency module without development containers.

  Default REPL exports become namespace-public Zig declarations so separately
  named Aguafria Vars cannot collide at the final artifact's global ABI.
  Declarations explicitly marked `:export` retain their requested symbol."
  [module-name declarations]
  (let [context-ns (or (some-> module-name str symbol find-ns) *ns*)]
    (binding [*source-mapping?* true
              *keyword-context* context-ns]
      (let [imports (remove nil? (synthesized-import-declarations declarations))
            declarations (concat imports (map dependency-declaration declarations))]
        (str "// Generated by Aguafria for a static dependency.\n"
             "// Module: " module-name "\n\n"
             (->> declarations
                  (sort-by declaration-sort-key)
                  (map emit-declaration)
                  (str/join "\n\n"))
             "\n")))))

(defn emit-dependency-module
  "Emit a development optimizer-visible module for use below another module.
  Default REPL exports become namespace-public Zig functions; declarations
  explicitly marked `:export` retain their requested global ABI symbol."
  [module-name declarations]
  (let [context-ns (or (some-> module-name str symbol find-ns) *ns*)]
    (binding [*source-mapping?* true
              *keyword-context* context-ns
              *named-module-imports?* true
              *logical-type-names?* true]
      (let [imports (remove nil? (synthesized-import-declarations declarations
                                                                    true))
            declarations (map dependency-declaration declarations)
            imports-source (->> imports
                                (sort-by declaration-sort-key)
                                (map emit-declaration)
                                (str/join "\n\n"))
            declarations-source (->> declarations
                                     (sort-by declaration-sort-key)
                                     (map emit-declaration)
                                     (str/join "\n\n"))]
        (str "// Generated by Aguafria for a standalone dependency.\n"
             "// Module: " module-name "\n\n"
             (when-not (str/blank? imports-source)
               (str imports-source "\n\n"))
             "pub const " (named-module-container module-name) " = struct {\n"
             (indent 1 (str logical-type-name-helper "\n\n"
                            declarations-source)) "\n"
             "};"
             "\n")))))
