(ns hooks.aguafria
  "Platform-independent clj-kondo views of Aguafria's declaration macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn- sexpr
  [node]
  (api/sexpr node))

(defn- token
  [value]
  (api/token-node value))

(defn- call-node
  [operator children]
  (api/list-node (cons (token operator) children)))

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
      (or (= :quote (:tag node)) (#{'quote 'clojure.core/quote} operator)) node
      ;; Host escapes contain ordinary Clojure, including real try/catch.
      ;; Do not apply the surrounding native syntax rewrites inside them.
      (= {:ns 'aguafria.zig :name 'clj!} resolved) node
      (= {:ns 'aguafria.keyword :name 'continue} resolved)
      (call-node 'do (cons (first (:children node))
                           (mapv native-expression (drop 2 (:children node)))))
      (= {:ns 'aguafria.keyword :name 'break} resolved)
      (call-node 'do (cons (first (:children node))
                           (mapv native-expression (take-last 1 (rest (:children node))))))
      (= {:ns 'aguafria.zig :name 'break-label} resolved)
      (call-node 'do [(first (:children node))])
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
  "Analyze native cases and their payload bindings without Clojure case semantics."
  [{:keys [node]}]
  (let [[operator & arguments] (:children node)
        labeled? (contains? #{'labeled-switch 'labeled-switch-stmt}
                            (:name (api/resolve {:name (sexpr operator)})))
        [condition & cases] (if labeled? (rest arguments) arguments)
        condition (native-expression condition)
        branches
        (mapv (fn [prong]
                (let [[operator & forms] (:children prong)
                      ordinary? (contains? #{'case 'inline-case} (sexpr operator))
                      [patterns forms] (if ordinary? [(first forms) (rest forms)] [nil forms])
                      captures (when (and (= :vector (:tag (first forms))) (next forms))
                                 (first forms))
                      forms (if captures (rest forms) forms)
                      patterns (remove #(= '_ (sexpr %)) (:children patterns))]
                  (call-node 'do
                             (concat (mapv native-expression patterns)
                                     [(capture-branch captures condition (call-node 'do forms))]))))
              cases)]
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

(defn- marker-index
  [nodes]
  (first
   (keep-indexed
    (fn [index node]
      (when (= :- (sexpr node)) index))
    nodes)))

(defn- typed-bindings
  [bindings]
  (let [entries (:children bindings)]
    (if (every? #(= :vector (:tag %)) entries)
      {:arguments (mapv #(first (:children %)) entries)
       :types (mapv #(last (:children %)) entries)}
      (let [entries (partition-all 3 entries)]
        {:arguments (mapv first entries)
         :types (mapv last entries)}))))

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
        rewritten (concat [(token (cond nested? 'fn private? 'defn- :else 'defn))
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
    {:node (call-node 'def
                      [definition-name (expression-node (into option-nodes declaration))])}))

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
            (concat [(token 'defn) definition-name]
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
    {:node (or target (token nil))}))

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
