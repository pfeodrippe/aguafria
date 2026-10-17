(ns aguafria.keyword
  "Reader-safe names for Zig syntax that Clojure cannot spell directly.

  Require this namespace as `k`. Every Zig `@` function is exposed as a real,
  documented Var, generated from the installed Zig compiler and matching ZLS
  language-reference data. Use `k/try` to unwrap a native error union both in
  an Aguafria declaration and during ordinary JVM evaluation."
  (:require [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Clear installed tokens and syntax helpers before compiling any host code
;; on reload. The cleanup itself cannot depend on names that we expose below.
(clojure.core/let [removed (clojure.core/->> (clojure.core/ns-interns clojure.core/*ns*)
                                             (clojure.core/keep
                                              (clojure.core/fn [[name v]]
                                                (clojure.core/when (clojure.core/or (:aguafria/token (clojure.core/meta v))
                                                                                    (:aguafria/core-syntax (clojure.core/meta v)))
                                                  name)))
                                             clojure.core/vec)]
  (clojure.core/doseq [name removed]
    (clojure.core/ns-unmap clojure.core/*ns* name))
  (clojure.core/doseq [name removed
                       :when (clojure.core/contains? (clojure.core/ns-publics 'clojure.core) name)]
    (clojure.core/refer 'clojure.core :only [name])))

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

;; Clojure keeps removed definitions across `require :reload`. Clean the old
;; pre-singular API so a long-running REPL observes the rename immediately.
(when (contains? (ns-interns *ns*) 'builtins)
  (ns-unmap *ns* 'builtins))

(defn catalog-info
  "Return generation/version/source metadata for the bundled Zig catalog."
  []
  (dissoc generated-catalog :builtins :keywords :primitives :reader-tokens))

(defn entries
  "Return the generated catalog of all Zig compiler `@` functions."
  []
  (:builtins generated-catalog))

(defn language-keywords
  "Return Zig's mechanically discovered ordinary keyword catalog.

  Every entry is backed by a documented `k/...` Var. Source may still use an
  ordinary Clojure form such as `if` when it has the same clear meaning, but
  spellings such as Zig `try`, `defer`, and `while` can always be explicit."
  []
  (:keywords generated-catalog))

(defn primitives
  "Return Zig's mechanically discovered primitive type/value catalog.

  Primitive values that have no Clojure literal, notably `undefined`, are
  exposed as qualified atom Vars such as `k/undefined`."
  []
  (:primitives generated-catalog))

(def ^:private primitive-spellings
  (into #{"_" "null" "undefined" "true" "false"}
        (map :name) (:primitives generated-catalog)))

(defn enum-literal-keyword?
  "Unqualified value keywords denote enum tags, except Zig primitive spellings.
  Dotted keywords remain readable for older source; explicit enum-literal syntax
  disambiguates a tag whose name is also a primitive type. Field-name positions
  and explicit type schemas do not use this predicate."
  [value]
  (and (keyword? value) (nil? (namespace value))
       (let [spelling (name value)]
         (or (str/starts-with? spelling ".")
             (not (or (contains? primitive-spellings spelling)
                      (re-matches #"[iu][0-9]+" spelling)))))))

(defn- constructible-primitive?
  [type-name]
  (or (boolean (re-matches #"[iu][0-9]+" type-name))
      (contains? #{"bool" "void" "isize" "usize" "f16" "f32" "f64" "f80" "f128"
                   "comptime_int" "comptime_float" "c_short" "c_ushort"
                   "c_int" "c_uint" "c_long" "c_ulong" "c_longlong" "c_ulonglong"}
                 type-name)))

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
       "Require this namespace as `k`; inside an Aguafria declaration, "
       "`(k/" (subs zig-name 1) " ...)` emits `" zig-name "(...)`. The Var is generated "
       "for Zig " (:zig-version generated-catalog) " from `"
       (get-in generated-catalog [:sources :builtin-table :path]) "`"
       (when (= :zls-langref documentation-source)
         " and enriched with ZLS's Zig language-reference data")
       ". JVM calls use the shared in-process Zig bridge; the compiler still "
       "checks types and required context. Compiler metadata is available "
       "through the Var's `:zig/*` keys."))

(defn- reader-token-doc
  [{:keys [documentation name zig-token]}]
  (str documentation "\n\n"
       "Inside an Aguafria declaration, `(k/" name " ...)` emits Zig `"
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
       :example (str "(a/defn example :i32 [[x :i64]] (k/"
                     (:name token) " x))")}))))

(defn- call-token
  [builtin]
  {:kind :call
   :name (:name builtin)
   :param-count (:param-count builtin)
   :symbol (symbol "aguafria.keyword" (:name builtin))
   :signature (:signature builtin)
   :zig-name (:zig-name builtin)})

(defn result-context-required?
  "Whether a Zig builtin requires an enclosing result type. This describes
  Zig syntax, not a guessed type for its operands or result."
  [zig-name]
  (contains? #{"@intCast" "@floatCast" "@ptrCast" "@alignCast" "@addrSpaceCast"
               "@bitCast" "@ptrFromInt" "@fieldParentPtr" "@splat" "@fromBackingInt"
               "@errorCast" "@intFromFloat" "@floatFromInt" "@truncate"}
             zig-name))

(defn- reader-token
  [token]
  (assoc (select-keys token [:kind :minimum-param-count :name :param-count :zig-token
                             :literal-arguments?])
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

(defn- primitive-token
  [entry]
  (cond-> {:kind :primitive
           :name (:name entry)
           :symbol (symbol "aguafria.keyword" (:name entry))
           :zig-token (:zig-token entry)}
    (constructible-primitive? (:name entry))
    (assoc :constructor? true :param-count 1)))

(defn- token-root
  [token]
  (cond
    (= "try" (:zig-token token))
    value/try-value!

    (= "var" (:zig-token token))
    (fn [& arguments]
      (apply (requiring-resolve 'aguafria.zig.jvm/mutable!) arguments))

    (= "=" (:zig-token token))
    (fn [target new-value]
      ((requiring-resolve 'aguafria.zig.jvm/assign!) target new-value))

    (= :assignment (:kind token))
    (fn [target operand]
      ((requiring-resolve 'aguafria.zig.jvm/invoke-assignment!) token target operand))

    (= "@field" (:zig-name token))
    (fn [receiver field-name]
      ;; A field is an lvalue when its receiver is mutable. The ordinary
      ;; builtin result bridge would copy it and lose assignment/aliasing.
      ((requiring-resolve 'aguafria.zig.jvm/invoke-syntax!)
       {:kind :syntax :name 'field :symbol 'aguafria.zig/field}
       [receiver field-name]))

    (= "@as" (:zig-name token))
    (fn [value type]
      ((requiring-resolve 'aguafria.zig.jvm/coerce!) value type))

    (:constructor? token)
    (value/zig-type
     {:kind :primitive :type (keyword (:zig-token token))}
     (fn [value]
       ((requiring-resolve 'aguafria.zig.jvm/coerce!)
        value (keyword (:zig-token token)))))

    (= :primitive (:kind token))
    (:symbol token)

    (contains? #{:call :operator} (:kind token))
    (fn [& arguments]
      ((requiring-resolve 'aguafria.zig.jvm/invoke-syntax!) token arguments))

    :else
    (unusable-outside-declaration token)))

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
    (let [root (token-root token)
          root (if (instance? clojure.lang.IObj root)
                 (with-meta root (assoc (meta root) :aguafria/token token))
                 root)
          v (intern *ns* sym root)]
      (alter-meta! v merge metadata)
      v)))

(defn- scoped-token-expansion
  [form environment result?]
  (let [locals ((requiring-resolve 'aguafria.zig.emitter/scoped-captures)
                *ns* form (keys environment))]
    `((requiring-resolve 'aguafria.zig.jvm/invoke-scoped!)
      '~(ns-name *ns*) '~form
      (hash-map ~@(mapcat (fn [local] [(list 'quote local) local]) locals))
      ~result?)))

(defn- resolve-qualified-var
  [context-ns sym]
  (when (and (symbol? sym) (namespace sym))
    (let [namespace-symbol (symbol (namespace sym))
          target-ns (or (get (ns-aliases context-ns) namespace-symbol)
                        (find-ns namespace-symbol)
                        ;; Calls to a/emit-* may happen from a test runner or
                        ;; callback where `*ns*` is not the lexical namespace.
                        ;; An alias is still safe to recover when every loaded
                        ;; namespace maps it to the same target.
                        (let [aliased-targets (->> (all-ns)
                                                   (keep #(get (ns-aliases %) namespace-symbol))
                                                   distinct
                                                   (take 2)
                                                   vec)]
                          (when (= 1 (count aliased-targets))
                            (first aliased-targets))))]
      (when target-ns
        (ns-resolve target-ns (symbol (name sym)))))))

(defn resolve-token
  "Resolve a native token Var in `context-ns`, including explicitly referred Vars.

  Spelling alone never selects a native operator. The resolved Var must carry
  native token metadata. Callers performing lexical analysis must respect local
  bindings before resolving namespace Vars. Results carry a canonical symbol
  so captured native code retains its meaning outside the source namespace."
  [context-ns op]
  (when (and (symbol? op) (not (:aguafria/local? (meta op))))
    (some-> (if (namespace op)
              (resolve-qualified-var context-ns op)
              (ns-resolve context-ns op))
            meta
            :aguafria/token)))

(defn normalize-attributes
  "Normalize an attribute set for native declaration emission.
  Accepts keyword flags, required k/... symbols, and their evaluated JVM values."
  [context attributes]
  (if-not (contains? attributes :attrs)
    attributes
    (let [raw (:attrs attributes)
          _ (when-not (set? raw)
              (throw (ex-info ":attrs must be a set" {:attrs raw})))
          flags (into #{}
                      (map (fn [flag]
                             (if (keyword? flag)
                               flag
                               (let [token (if (symbol? flag)
                                             (resolve-token context flag)
                                             (:aguafria/token (meta flag)))
                                     spelling (or (when (= :keyword (:kind token))
                                                    (:zig-token token))
                                                  (when (and (= :call (:kind token))
                                                             (some #(= (:name token) (:name %))
                                                                   (:keywords generated-catalog)))
                                                    (:name token)))]
                                 (when-not spelling
                                   (throw (ex-info "Attribute must be a Zig keyword token"
                                                   {:attribute flag})))
                                 (if (= "pub" spelling)
                                   :public
                                   (keyword spelling))))))
                      raw)
          existing (:zig/prefix attributes)
          existing-words (set (str/split (or existing "") #"\s+"))
          prefixes (remove #(contains? existing-words (name %))
                           (filter flags [:extern :inline :noinline :threadlocal :comptime :noalias]))
          prefix (str/join " " (map name prefixes))]
      (cond-> (assoc attributes :attrs flags)
        (seq prefix) (assoc :zig/prefix
                            (str (when (seq existing) (str existing " ")) prefix))))))

(defn token-name
  "Return the generated `k/...` Var name for a Zig keyword/operator token."
  [zig-token]
  (let [builtin-names (set (map :name (:builtins generated-catalog)))]
    (or (some (fn [entry]
                (when (= zig-token (:zig-token entry)) (:name entry)))
              (:reader-tokens generated-catalog))
        (some (fn [entry]
                (when (= zig-token (:name entry))
                  (:name (language-token entry builtin-names))))
              (:keywords generated-catalog))
        (some (fn [entry]
                (when (= zig-token (:zig-token entry)) (:name entry)))
              (:primitives generated-catalog)))))

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
      :arglists (if (= "@as" (:zig-name builtin))
                  '([value type])
                  (parameter-arglists builtin))
      :doc (if (= "@as" (:zig-name builtin))
             (str "Coerce value to a Zig type: (k/as value type).\n\n"
                  "Accepts the same type keywords and vectors as signatures. "
                  "Inside Aguafria emits @as(type, value); from the JVM returns "
                  "a checked scalar or an owning native value. Supports ->.\n\n"
                  (:documentation builtin))
             (compiler-doc builtin))
      :zig/allows-lvalue? (:allows-lvalue? builtin)
      :zig/documentation-format (:documentation-format builtin)
      :zig/documentation-source (:documentation-source builtin)
      :zig/eval-to-error (:eval-to-error builtin)
      :zig/illegal-outside-function? (:illegal-outside-function? builtin)
      :zig/name (:zig-name builtin)
      :zig/param-count (:param-count builtin)
      :zig/signature (:signature builtin)
      :zig/source (get-in generated-catalog [:sources :builtin-table :path])
      :zig/version (:zig-version generated-catalog)})
    (when (= "@TypeOf" (:zig-name builtin))
      ;; A function would let Clojure execute the operands before Zig sees
      ;; @TypeOf. Preserve the lexical syntax and use the native scoped bridge.
      (let [v (ns-resolve *ns* (symbol (:name token)))]
        (alter-var-root v (constantly (fn [form environment & arguments]
                                        (validate-call! token arguments form)
                                        (scoped-token-expansion form environment true))))
        (alter-meta! v assoc :macro true :aguafria/scoped? true
                     :doc (str "Determine native expression types without executing the operands. "
                               "JVM calls preserve the lexical form and ask Zig to analyze it.\n\n"
                               (compiler-doc builtin)))))))

(let [builtin-names (set (map :name (:builtins generated-catalog)))]
  (doseq [entry (:keywords generated-catalog)]
    (let [token (language-token entry builtin-names)]
      (intern-token!
       token
       {:aguafria/token token
        :arglists '([& forms])
        :doc (str (when (= "comptime" (:zig-token token))
                    "Evaluate one native expression, statement, or block at Zig compile time. JVM/REPL calls capture syntax and lexical bindings through the native scoped bridge; the body is not evaluated as Clojure. Use native operator Vars, qualified or referred. Zig enforces which captures and operations are comptime-valid. Use (k/comptime expression), (k/comptime (do ...)), or (k/comptime (let [...] ...)).\n\n")
                  (when (= "var" (:zig-token token))
                    "JVM: (k/var value) or (k/var value type) creates owned mutable native storage. Assign with (k/= handle value). In an Aguafria let initializer, declares a Zig var.\n\n")
                  (when (= "try" (:zig-token token))
                    "Unwrap one native Zig error union. In Zig, emits try and propagates errors. On the JVM, returns the typed successful payload or throws ExceptionInfo with :aguafria/phase :native-error and :error-name. Clojure's unqualified try remains its exception-handling special form.\n\n")
                  "Zig `" (:zig-token token) "` keyword, mechanically discovered "
                  "from Zig " (:zig-version generated-catalog) " `"
                  (get-in generated-catalog [:sources :tokenizer :path]) "`. "
                  (when-not (#{"var" "comptime" "try"} (:zig-token token))
                    "This Var is syntax and is only valid inside an Aguafria declaration."))
        :zig/name (:zig-token token)
        :zig/source (get-in generated-catalog [:sources :tokenizer :path])
        :zig/version (:zig-version generated-catalog)})
      (when (= "comptime" (:zig-token token))
        (let [v (ns-resolve *ns* (symbol (:name token)))]
          (alter-var-root v
                          (constantly
                           (fn [form environment & expressions]
                             (when-not (= 1 (count expressions))
                               (throw (ex-info "k/comptime expects one native expression or block"
                                               {:form form :arguments expressions})))
                             (scoped-token-expansion form environment true))))
          (alter-meta! v assoc :macro true :aguafria/scoped? true
                       :arglists '([expression]))))
      (when (contains? #{"for" "while" "switch"} (:zig-token token))
        (let [v (ns-resolve *ns* (symbol (:name token)))]
          (alter-var-root v (constantly (fn [form environment & _]
                                          (scoped-token-expansion form environment
                                                                  ((requiring-resolve
                                                                    'aguafria.zig.emitter/scoped-result?)
                                                                   *ns* form)))))
          (when (= "switch" (:zig-token token))
            (alter-meta! v assoc :style/indent 1))
          (alter-meta! v assoc :macro true :aguafria/scoped? true
                       :doc "Native scoped control form. Runs native Zig in the same process, captures lexical values and preserves mutable native storage. Only the selected switch branch executes; switch returns its native result."))))))

(doseq [entry (:reader-tokens generated-catalog)]
  (let [token (reader-token entry)]
    (intern-token!
     token
     {:aguafria/token token
      :arglists (clojure.core/or (:arglists entry) (parameter-arglists entry))
      :doc (reader-token-doc entry)
      :zig/name (:zig-token entry)
      :zig/param-count (:param-count entry)
      :zig/source (get-in generated-catalog [:sources :tokenizer :path])
      :zig/version (:zig-version generated-catalog)})))

;; Primitive values such as `undefined` are identifiers to Zig's tokenizer,
;; then resolved by semantic analysis. Intern these last so a primitive that
;; also appears in the keyword table (currently `anyframe`) has atom semantics.
(doseq [entry (concat (:primitives generated-catalog)
                     ;; Zig recognizes iN/uN algorithmically, outside its static
                     ;; primitive table. Expose widths through 128 as Vars;
                     ;; larger widths use the same API via (k/as value :u256).
                      (clojure.core/for [prefix ["i" "u"] bits (range 129)
                                         :let [name (str prefix bits)]]
                        {:name name :zig-token name}))]
  (let [token (primitive-token entry)]
    (intern-token!
     token
     {:aguafria/token token
      :arglists (when (:constructor? token) '([value]))
      :doc (str "Zig primitive `" (:zig-token token)
                "`, mechanically discovered from Zig "
                (:zig-version generated-catalog) " `"
                (get-in generated-catalog [:sources :primitives :path])
                "`. Use `k/" (:name token)
                "` as an atom inside an Aguafria declaration."
                (when (:constructor? token)
                  (str " Call (k/" (:name token) " value) to coerce a value. "
                       "Equivalent to (k/as value :" (:name token) "). "
                       "Also callable from Clojure/Java through native Zig.")))
      :zig/name (:zig-token token)
      :zig/source (get-in generated-catalog [:sources :primitives :path])
      :zig/version (:zig-version generated-catalog)})))

;; These are the ordinary Clojure macros, not new native execution scopes.
;; Sharing their macro implementations keeps standalone JVM behavior unchanged.
;; Native declaration capture continues to lower its supported binding syntax.
(clojure.core/doseq [name '[let -> ->> as-> cond cond-> cond->> some-> some->>
                            doto when when-not dotimes comment]]
  (clojure.core/let [source (clojure.core/ns-resolve 'clojure.core name)
                     source-symbol (clojure.core/symbol "clojure.core" (clojure.core/str name))]
    (clojure.core/assert (:macro (clojure.core/meta source)))
    (clojure.core/assert (clojure.core/not (:aguafria/token
                                            (clojure.core/meta (clojure.core/ns-resolve clojure.core/*ns* name)))))
    (clojure.core/ns-unmap clojure.core/*ns* name)
    (clojure.core/let [target (clojure.core/intern clojure.core/*ns* name @source)]
      (clojure.core/alter-meta!
       target clojure.core/merge
       (clojure.core/select-keys (clojure.core/meta source) [:macro :arglists :doc])
       {:aguafria/core-syntax source-symbol}
       (clojure.core/when (clojure.core/contains? '#{let when when-not dotimes comment} name)
         {:aguafria/syntax {:kind :syntax :name (clojure.core/str name)
                            :symbol (clojure.core/symbol "aguafria.keyword" (clojure.core/str name))}})))))

;; BEGIN GENERATED KEYWORD LINT MODEL
;; Generated by aguafria.generate-keyword; no runtime definitions.
(clojure.core/comment
  (clojure.core/declare
    ! != % %= & &= * *% *%= *=
    *| *|= + +% +%= ++ += +| +|= -
    -% -%= -= -> ->> -| -|= / < <<
    <<= <<| <<|= <= = == > >= >> >>=
    Enum EnumLiteral FieldType Fn Frame Int Pointer SpirvType Struct This
    Tuple TypeOf Union Vector abs addWithOverflow addrSpaceCast addrspace align alignCast
    alignOf allowzero and anyerror anyframe anyopaque anytype as as-> asm
    atomicLoad atomicRmw atomicStore backingInt bit-not bit-xor bitCast bitOffsetOf bitReverse bitSizeOf
    bool branchHint break breakpoint byteSwap cVaArg cVaCopy cVaEnd cVaStart c_char
    c_int c_long c_longdouble c_longlong c_short c_uint c_ulong c_ulonglong c_ushort call
    callconv catch ceil clz cmpxchgStrong cmpxchgWeak comment compileError compileLog comptime
    comptime_float comptime_int cond cond-> cond->> const constCast continue cos ctz
    defer disableInstrumentation disableIntrinsics div-assign divCeil divExact divFloor divTrunc dotimes doto
    else embedFile enum enumFromInt errdefer error errorCast errorFromInt errorName errorReturnTrace
    exp exp2 export extern f128 f16 f32 f64 f80 field
    fieldParentPtr floatCast floatFromInt floor fn for frame frameAddress fromBackingInt hasDecl
    hasField i0 i1 i10 i100 i101 i102 i103 i104 i105
    i106 i107 i108 i109 i11 i110 i111 i112 i113 i114
    i115 i116 i117 i118 i119 i12 i120 i121 i122 i123
    i124 i125 i126 i127 i128 i13 i14 i15 i16 i17
    i18 i19 i2 i20 i21 i22 i23 i24 i25 i26
    i27 i28 i29 i3 i30 i31 i32 i33 i34 i35
    i36 i37 i38 i39 i4 i40 i41 i42 i43 i44
    i45 i46 i47 i48 i49 i5 i50 i51 i52 i53
    i54 i55 i56 i57 i58 i59 i6 i60 i61 i62
    i63 i64 i65 i66 i67 i68 i69 i7 i70 i71
    i72 i73 i74 i75 i76 i77 i78 i79 i8 i80
    i81 i82 i83 i84 i85 i86 i87 i88 i89 i9
    i90 i91 i92 i93 i94 i95 i96 i97 i98 i99
    if import inComptime inline intCast intFromBool intFromEnum intFromError intFromFloat intFromPtr
    isize keyword-export keyword-extern let linksection log log10 log2 max memcpy
    memmove memset min mod mulAdd mulWithOverflow noalias noinline noreturn nosuspend
    null offsetOf opaque or orelse packed panic popCount prefetch ptrCast
    ptrFromInt pub reduce rem resume return returnAddress round select setEvalBranchQuota
    setFloatMode setRuntimeSafety shlExact shlWithOverflow shrExact shuffle sin sizeOf some-> some->>
    splat sqrt src struct subWithOverflow suspend switch tagName tan test
    threadlocal trap trunc truncate try type typeInfo typeName u0 u1
    u10 u100 u101 u102 u103 u104 u105 u106 u107 u108
    u109 u11 u110 u111 u112 u113 u114 u115 u116 u117
    u118 u119 u12 u120 u121 u122 u123 u124 u125 u126
    u127 u128 u13 u14 u15 u16 u17 u18 u19 u2
    u20 u21 u22 u23 u24 u25 u26 u27 u28 u29
    u3 u30 u31 u32 u33 u34 u35 u36 u37 u38
    u39 u4 u40 u41 u42 u43 u44 u45 u46 u47
    u48 u49 u5 u50 u51 u52 u53 u54 u55 u56
    u57 u58 u59 u6 u60 u61 u62 u63 u64 u65
    u66 u67 u68 u69 u7 u70 u71 u72 u73 u74
    u75 u76 u77 u78 u79 u8 u80 u81 u82 u83
    u84 u85 u86 u87 u88 u89 u9 u90 u91 u92
    u93 u94 u95 u96 u97 u98 u99 undefined union unionInit
    unreachable usize var void volatile volatileCast wasmMemoryGrow wasmMemorySize when when-not
    while workGroupId workGroupSize workItemId | |= ||))
;; END GENERATED KEYWORD LINT MODEL
