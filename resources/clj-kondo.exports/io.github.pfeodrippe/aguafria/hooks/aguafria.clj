(ns hooks.aguafria
  "Platform-independent clj-kondo views of Aguafria's declaration macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn- sexpr
  [node]
  (when node (api/sexpr node)))

(defn- token
  [value]
  (api/token-node value))

(defn- call-node
  [operator children]
  ;; These are model operations introduced by the hook, not user operators.
  ;; They must not depend on the consumer's implicit core referrals.
  (let [operator (get {'let 'clojure.core/let
                       'atom 'clojure.core/atom
                       'first 'clojure.core/first} operator operator)]
    ;; Child transformations may call hooks-api/resolve. Realize them while
    ;; this hook's analysis context is still bound, not during later linting.
    (api/list-node (into [(token operator)] children))))

(declare core-referral-names)

(defn namespace-referrals
  "Model :only [] accurately for kondo versions which ignore that ns option.
  The exclusion set is generated from all actual clojure.core public Vars;
  this is only a lint view, never a change to the consumer namespace."
  [{:keys [node]}]
  {:node (assoc node :children
                (mapv (fn [child]
                        (cond
                          (= '(:refer-clojure :only []) (sexpr child))
                          (api/list-node [(token :refer-clojure) (token :exclude)
                                          (api/vector-node (mapv token core-referral-names))])
                          :else child))
                      (:children node)))})

(defn c-bindings
  "Expose native binding names while checking the ordinary Clojure import expression."
  [{:keys [node]}]
  (let [[_ api-name import-expression members] (:children node)]
    {:node (call-node 'do
                      (cons (call-node 'def [api-name import-expression])
                            (map #(call-node 'def [% api-name]) (:children members))))}))

(defn- native-expression
  [node]
  (let [operator (when (= :list (:tag node))
                   (some-> node :children first sexpr))
        resolved (when (symbol? operator)
                   (select-keys (api/resolve {:name operator}) [:ns :name]))]
    (cond
      ;; The reader's @ syntax denotes core deref even without core referrals.
      (= :deref (:tag node))
      (call-node 'clojure.core/deref (mapv native-expression (:children node)))
      (or (= :quote (:tag node)) (#{'quote 'clojure.core/quote} operator)) node
      ;; Host escapes contain ordinary Clojure, including real try/catch.
      ;; Do not apply the surrounding native syntax rewrites inside them.
      (= {:ns 'aguafria.zig :name 'clj!} resolved) node
      (= {:ns 'aguafria.zig :name 'field} resolved)
      (native-expression (second (:children node)))
      (= {:ns 'aguafria.keyword :name 'continue} resolved)
      (call-node 'do (cons (first (:children node))
                           (mapv native-expression (drop 2 (:children node)))))
      (= {:ns 'aguafria.keyword :name 'break} resolved)
      (call-node 'do (cons (first (:children node))
                           (mapv native-expression (take-last 1 (rest (:children node))))))
      (= {:ns 'aguafria.zig :name 'break-label} resolved)
      (call-node 'do [(first (:children node))])
      ;; Leave ordinary heads intact: resolving them here precedes analysis of
      ;; their enclosing bindings/definitions. In particular, :refer :all may
      ;; provisionally resolve an unknown or self-referential name to keyword.
      ;; Qualifying that guess would hide unknown calls and break local names.
      (:children node)
      (assoc node :children
             (mapv native-expression
                   (if (#{'try 'clojure.core/try} operator)
                     (cons (token 'do) (rest (:children node)))
                     (:children node))))
      :else node)))

(defn native-block
  "A native block label is data; analyze its body as expressions in lexical scope."
  [{:keys [node]}]
  ;; Resolve eagerly: the hooks API's analysis context ends when this call returns.
  {:node (call-node 'do (mapv native-expression (drop 2 (:children node))))})

(defn native-scope
  [{:keys [node]}]
  {:node (call-node 'do (mapv native-expression (rest (:children node))))})

(defn- capture-branch [captures condition branch]
  (let [bindings (mapcat (fn [capture]
                           (let [pointer? (= :list (:tag capture))]
                             [(if pointer? (second (:children capture)) capture)
                              (if pointer? (call-node 'atom [condition]) condition)]))
                         (:children captures))]
    (if (seq bindings)
      (call-node 'let [(api/vector-node bindings) (native-expression branch)])
      (native-expression branch))))

(defn native-if-capture
  [{:keys [node]}]
  (let [[_ options condition then else] (:children node)
        options (into {} (map (fn [[key value]] [(sexpr key) value]))
                      (partition 2 (:children options)))
        condition (native-expression condition)]
    {:node (call-node 'if
                      [condition
                       (capture-branch (:payload options) condition then)
                       (if else
                         (capture-branch (:error options) condition else)
                         (token nil))])}))

(defn native-catch-capture
  [{:keys [node]}]
  (let [[_ captures expression handler] (:children node)
        expression (native-expression expression)]
    {:node (call-node 'do
                      [expression (capture-branch captures expression handler)])}))

(defn native-switch
  "Analyze flat selector/result pairs and branch-local native captures."
  [{:keys [node]}]
  (let [[_ header & cases] (:children node)
        condition (native-expression (if (= :vector (:tag header)) (second (:children header)) header))
        branch (fn [target]
                 (let [inline? (and (= :list (:tag target))
                                    (symbol? (sexpr (first (:children target))))
                                    (= {:ns 'aguafria.keyword :name 'inline}
                                       (select-keys (api/resolve {:name (sexpr (first (:children target)))}) [:ns :name])))
                       target (if inline? (second (:children target)) target)
                       capture? (and (= :list (:tag target))
                                     (symbol? (sexpr (first (:children target))))
                                     (= {:ns 'aguafria.zig :name 'with-block}
                                        (select-keys (api/resolve {:name (sexpr (first (:children target)))}) [:ns :name]))
                                     (= :vector (:tag (nth (:children target) 2 nil))))]
                   (if capture?
                     (capture-branch (nth (:children target) 2) condition
                                     (call-node 'do (drop 3 (:children target))))
                     (native-expression target))))
        branches
        (vec (concat
              (map (fn [[pattern target]]
                     (let [value (sexpr pattern)
                           grouped? (or (= :vector (:tag pattern))
                                        (and (seq? value) (or (seq? (first value)) (number? (first value))
                                                              (char? (first value)) (every? keyword? value))))
                           patterns (if grouped? (:children pattern) [pattern])]
                       (call-node 'do
                                  (concat (map native-expression (remove #(= '_ (sexpr %)) patterns))
                                          [(branch target)]))))
                   (partition 2 cases))
              (when (odd? (count cases)) [(branch (last cases))])))]
    {:node (call-node 'do (cons condition branches))}))

(defn native-while
  "Keep loop payload/error bindings lexical and labels as syntax."
  [{:keys [node]}]
  (let [[_ options condition & body] (:children node)
        options (into {} (map (fn [[key value]] [(sexpr key) value]))
                      (partition 2 (:children options)))
        condition (native-expression condition)
        payload (capture-branch (:payload options) condition
                                (call-node 'do (concat body (when-let [step (:continue options)] [step]))))
        otherwise (capture-branch (:error options) condition
                                  (call-node 'do (concat (:children (:else options))
                                                         (when-let [value (:else-expression options)] [value]))))]
    {:node (call-node 'do [condition payload otherwise])}))

(defn native-for
  "Treat native captures as lexical bindings; pointer capture is not multiplication."
  [{:keys [node]}]
  (let [[operator & arguments] (:children node)
        labeled? (= {:ns 'aguafria.zig :name 'for-loop}
                    (select-keys (api/resolve {:name (sexpr operator)}) [:ns :name]))
        [bindings & body] (if labeled? (rest arguments) arguments)
        otherwise (last body)
        else? (and (= :list (:tag otherwise))
                   (contains? #{'else-clause 'else-expression}
                              (some-> otherwise :children first sexpr name symbol)))
        body (if else? (butlast body) body)
        pairs (partition 2 (:children bindings))
        binding-nodes
        (into [] (mapcat (fn [[capture input]]
                           (let [pointer? (and (= :list (:tag capture))
                                               (= {:ns 'aguafria.keyword :name '*}
                                                  (select-keys
                                                   (api/resolve {:name (sexpr (first (:children capture)))})
                                                   [:ns :name])))
                                 capture (if pointer? (second (:children capture)) capture)
                                 element (call-node 'first [(native-expression input)])]
                             [capture (if pointer? (call-node 'atom [element]) element)])))
              pairs)
        loop-body (call-node 'let (cons (api/vector-node binding-nodes)
                                        (mapv native-expression body)))]
    {:node (if else?
             (call-node 'do (cons loop-body
                                  (mapv native-expression (rest (:children otherwise)))))
             loop-body)}))

(defn- expression-node
  [nodes]
  (native-expression
   (case (count nodes)
     0 (token nil)
     1 (first nodes)
     (call-node 'do nodes))))

(defn- declaration-prefix
  [nodes]
  (loop [remaining nodes
         docstring nil
         options {}
         option-nodes []]
    (let [value (some-> remaining first sexpr)]
      (cond
        (string? value)
        (recur (rest remaining) (first remaining) options option-nodes)

        (map? value)
        (recur (rest remaining) docstring (merge options value)
               (conj option-nodes (first remaining)))

        :else
        {:declaration remaining
         :docstring docstring
         :option-nodes option-nodes
         :options options}))))

(defn- typed-bindings
  [bindings]
  (let [entries (:children bindings)]
    (if (every? #(= :vector (:tag %)) entries)
      {:arguments (mapv #(first (:children %)) entries)
       :types (mapv #(last (:children %)) entries)}
      (do
        (api/reg-finding! (assoc (meta bindings)
                                 :type :aguafria/invalid-typed-bindings
                                 :level :error
                                 :message "Typed bindings require parameter vectors: [[name type] ...]"))
        {:arguments [] :types []}))))

(defn- process-entry?
  [operator definition-name return-type types options]
  (let [type (some-> types first sexpr)]
    (and (= "defn" operator)
         (= 'main (sexpr definition-name))
         (not (false? (:public options)))
         (not (:private options))
         (not (if (contains? options :export)
                (not= false (:export options))
                (contains? (:attrs options) :export)))
         (contains? #{:!void [:! :void] [:error-union :void]} (sexpr return-type))
         (= 1 (count types))
         (symbol? type)
         (contains? '#{{:ns aguafria.std.process :name Init}
                       {:ns aguafria.std.process.Init :name Minimal}}
                    (select-keys (api/resolve {:name type}) [:ns :name])))))

(defn function-declaration
  "Analyze top-level functions and container methods with their typed arguments."
  [{:keys [node]}]
  (let [[operator definition-name & raw-declaration] (:children node)
        operator-name (some-> operator sexpr name)
        private? (contains? #{"defn-" "fn-"} operator-name)
        nested? (contains? #{"fn" "fn-" "fn-decl" "fn-proto-decl"} operator-name)
        extern? (contains? #{"defextern" "fn-proto-decl"} operator-name)
        {:keys [declaration docstring options]}
        (declaration-prefix (rest raw-declaration))
        return-type (first raw-declaration)
        options (merge (meta (sexpr definition-name)) options)
        bindings (first declaration)
        body (if extern? [] (rest declaration))
        {:keys [arguments types]}
        (if (= :vector (:tag bindings))
          (typed-bindings bindings)
          {:arguments [] :types []})
        function-body (expression-node
                       (vec (concat (when return-type [return-type])
                                    types
                                    (when extern? arguments)
                                    body)))
        rewritten (concat [(token (cond nested? 'clojure.core/fn private? 'clojure.core/defn- :else 'clojure.core/defn))
                           definition-name]
                          (when (and docstring (not nested?)) [docstring])
                          (if (process-entry? operator-name definition-name return-type types options)
                            [(api/list-node [(api/vector-node []) (token nil)])
                             (api/list-node [(api/vector-node arguments) function-body])]
                            [(api/vector-node arguments) function-body]))]
    {:node (api/list-node rewritten)}))

(defn top-level-declaration
  "Lint an Aguafria `def*` form as a definition while still analyzing values."
  [{:keys [node]}]
  (let [[_ definition-name & raw-declaration] (:children node)
        {:keys [declaration option-nodes]} (declaration-prefix raw-declaration)]
    ;; Native containers can refer to their own declaration in field types and
    ;; methods. Register that name before walking its initializer, especially
    ;; when a wildcard import could otherwise claim an unresolved self-name.
    {:node (call-node 'do
                      [(call-node 'clojure.core/declare [definition-name])
                       (call-node 'def
                                  [definition-name (expression-node (into option-nodes declaration))])])}))

(defn import-declaration
  "Register the import alias Var without treating Zig member names as locals."
  [{:keys [node]}]
  (let [[_ definition-name] (:children node)]
    {:node (call-node 'def [definition-name (token nil)])}))

(defn test-declaration
  "Register a normal test Var while analyzing the body, not its native label."
  [{:keys [node]}]
  (let [[_ definition-name & raw-declaration] (:children node)
        {:keys [declaration docstring]} (declaration-prefix raw-declaration)]
    {:node (api/list-node
            (concat [(token 'clojure.core/defn) definition-name]
                    (when docstring [docstring])
                    [(api/vector-node []) (expression-node (vec declaration))]))}))

(defn cast
  "Analyze both the value and Zig output type accepted by `a/cast`."
  [{:keys [node]}]
  (let [[_ value output-type] (:children node)]
    {:node (expression-node (vec (remove nil? [output-type value])))}))

(defn field-access
  "Analyze the container expression, treating the field label as Zig data."
  [{:keys [node]}]
  (let [[_ target] (:children node)]
    {:node (if target (native-expression target) (token nil))}))

(defn field-declaration
  "Ignore a struct/enum field label while analyzing its type and initializer."
  [{:keys [node]}]
  (let [[_ _field-name & declaration] (:children node)
        declaration (remove #(or (string? (sexpr %))
                                 (map? (sexpr %)))
                            declaration)]
    {:node (expression-node (vec declaration))}))

(defn object-literal
  "Analyze object field values without resolving their Zig field labels."
  [{:keys [node]}]
  (let [[_ fields] (:children node)
        values (when (= :vector (:tag fields))
                 (keep (fn [entry]
                         (when (= :vector (:tag entry))
                           (last (:children entry))))
                       (:children fields)))]
    {:node (expression-node (vec values))}))

(defn identifier-literal
  "Treat an explicitly structural Zig identifier as data."
  [_]
  {:node (token nil)})

;; BEGIN GENERATED CORE REFERRALS
;; All core public Vars, not an operator exclusion list. Metadata only.
(def ^:private core-referral-names
  '[* *' *1 *2 *3 *agent* *allow-unresolved-vars* *assert* *clojure-version* *command-line-args*
    *compile-files* *compile-path* *compiler-options* *data-readers* *default-data-reader-fn* *e *err* *file* *flush-on-newline* *fn-loader*
    *in* *math-context* *ns* *out* *print-dup* *print-length* *print-level* *print-meta* *print-namespace-maps* *print-readably*
    *read-eval* *reader-resolver* *repl* *source-path* *suppress-read* *unchecked-math* *use-context-classloader* *verbose-defrecords* *warn-on-reflection* +
    +' - -' -> ->> ->ArrayChunk ->Eduction ->Vec ->VecNode ->VecSeq
    -cache-protocol-fn -reset-methods .. / < <= = == > >=
    EMPTY-NODE Inst NaN? PrintWriter-on StackTraceElement->vec Throwable->map abs accessor aclone add-classpath
    add-tap add-watch agent agent-error agent-errors aget alength alias all-ns alter
    alter-meta! alter-var-root amap ancestors and any? apply areduce array-map as->
    aset aset-boolean aset-byte aset-char aset-double aset-float aset-int aset-long aset-short assert
    assoc assoc! assoc-in associative? atom await await-for await1 bases bean
    bigdec bigint biginteger binding bit-and bit-and-not bit-clear bit-flip bit-not bit-or
    bit-set bit-shift-left bit-shift-right bit-test bit-xor boolean boolean-array boolean? booleans bound-fn
    bound-fn* bound? bounded-count butlast byte byte-array bytes bytes? case cast
    cat char char-array char-escape-string char-name-string char? chars chunk chunk-append chunk-buffer
    chunk-cons chunk-first chunk-next chunk-rest chunked-seq? class class? clear-agent-errors clojure-version coll?
    comment commute comp comparator compare compare-and-set! compile complement completing concat
    cond cond-> cond->> condp conj conj! cons constantly construct-proxy contains?
    count counted? create-ns create-struct cycle dec dec' decimal? declare dedupe
    default-data-readers definline definterface defmacro defmethod defmulti defn defn- defonce defprotocol
    defrecord defstruct deftype delay delay? deliver denominator deref derive descendants
    destructure disj disj! dissoc dissoc! distinct distinct? doall dorun doseq
    dosync dotimes doto double double-array double? doubles drop drop-last drop-while
    eduction empty empty? ensure ensure-reduced enumeration-seq error-handler error-mode eval even?
    every-pred every? ex-cause ex-data ex-info ex-message extend extend-protocol extend-type extenders
    extends? false? ffirst file-seq filter filterv find find-keyword find-ns find-protocol-impl
    find-protocol-method find-var first flatten float float-array float? floats flush fn
    fn? fnext fnil for force format frequencies future future-call future-cancel
    future-cancelled? future-done? future? gen-class gen-interface gensym get get-in get-method get-proxy-class
    get-thread-bindings get-validator group-by halt-when hash hash-combine hash-map hash-ordered-coll hash-set hash-unordered-coll
    ident? identical? identity if-let if-not if-some ifn? import in-ns inc
    inc' indexed? infinite? init-proxy inst-ms inst-ms* inst? instance? int int-array
    int? integer? interleave intern interpose into into-array ints io! isa?
    iterate iteration iterator-seq juxt keep keep-indexed key keys keyword keyword?
    last lazy-cat lazy-seq let letfn line-seq list list* list? load
    load-file load-reader load-string loaded-libs locking long long-array longs loop macroexpand
    macroexpand-1 make-array make-hierarchy map map-entry? map-indexed map? mapcat mapv max
    max-key memfn memoize merge merge-with meta method-sig methods min min-key
    mix-collection-hash mod munge name namespace namespace-munge nat-int? neg-int? neg? newline
    next nfirst nil? nnext not not-any? not-empty not-every? not= ns
    ns-aliases ns-imports ns-interns ns-map ns-name ns-publics ns-refers ns-resolve ns-unalias ns-unmap
    nth nthnext nthrest num number? numerator object-array odd? or parents
    parse-boolean parse-double parse-long parse-uuid partial partition partition-all partition-by partitionv partitionv-all
    pcalls peek persistent! pmap pop pop! pop-thread-bindings pos-int? pos? pr
    pr-str prefer-method prefers primitives-classnames print print-ctor print-dup print-method print-simple print-str
    printf println println-str prn prn-str promise proxy proxy-call-with-super proxy-mappings proxy-name
    proxy-super push-thread-bindings pvalues qualified-ident? qualified-keyword? qualified-symbol? quot rand rand-int rand-nth
    random-sample random-uuid range ratio? rational? rationalize re-find re-groups re-matcher re-matches
    re-pattern re-seq read read+string read-line read-string reader-conditional reader-conditional? realized? record?
    reduce reduce-kv reduced reduced? reductions ref ref-history-count ref-max-history ref-min-history ref-set
    refer refer-clojure reify release-pending-sends rem remove remove-all-methods remove-method remove-ns remove-tap
    remove-watch repeat repeatedly replace replicate require requiring-resolve reset! reset-meta! reset-vals!
    resolve rest restart-agent resultset-seq reverse reversible? rseq rsubseq run! satisfies?
    second select-keys send send-off send-via seq seq-to-map-for-destructuring seq? seqable? seque
    sequence sequential? set set-agent-send-executor! set-agent-send-off-executor! set-error-handler! set-error-mode! set-validator! set? short
    short-array shorts shuffle shutdown-agents simple-ident? simple-keyword? simple-symbol? slurp some some->
    some->> some-fn some? sort sort-by sorted-map sorted-map-by sorted-set sorted-set-by sorted?
    special-symbol? spit split-at split-with splitv-at str stream-into! stream-reduce! stream-seq! stream-transduce!
    string? struct struct-map subs subseq subvec supers swap! swap-vals! symbol
    symbol? sync tagged-literal tagged-literal? take take-last take-nth take-while tap> test
    the-ns thread-bound? time to-array to-array-2d trampoline transduce transient tree-seq true?
    type unchecked-add unchecked-add-int unchecked-byte unchecked-char unchecked-dec unchecked-dec-int unchecked-divide-int unchecked-double unchecked-float
    unchecked-inc unchecked-inc-int unchecked-int unchecked-long unchecked-multiply unchecked-multiply-int unchecked-negate unchecked-negate-int unchecked-remainder-int unchecked-short
    unchecked-subtract unchecked-subtract-int underive unquote unquote-splicing unreduced unsigned-bit-shift-right update update-in update-keys
    update-proxy update-vals uri? use uuid? val vals var-get var-set var?
    vary-meta vec vector vector-of vector? volatile! volatile? vreset! vswap! when
    when-first when-let when-not when-some while with-bindings with-bindings* with-in-str with-loading-context with-local-vars
    with-meta with-open with-out-str with-precision with-redefs with-redefs-fn xml-seq zero? zipmap])
;; END GENERATED CORE REFERRALS
