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

(defn- native-expression
  [node]
  (let [operator (when (= :list (:tag node))
                   (some-> node :children first sexpr))]
    (cond
      (or (= :quote (:tag node)) (#{'quote 'clojure.core/quote} operator)) node
      (:children node)
      (assoc node :children
             (mapv native-expression
                   (if (#{'try 'clojure.core/try} operator)
                     (cons (token 'do) (rest (:children node)))
                     (:children node))))
      :else node)))

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
         options {}]
    (let [value (some-> remaining first sexpr)]
      (cond
        (string? value)
        (recur (rest remaining) (first remaining) options)

        (map? value)
        (recur (rest remaining) docstring (merge options value))

        :else
        {:declaration remaining
         :docstring docstring
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
        private? (= "defn-" operator-name)
        nested? (contains? #{"fn-decl" "fn-proto-decl"} operator-name)
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
        {:keys [declaration]} (declaration-prefix raw-declaration)]
    {:node (call-node 'def
                      [definition-name (expression-node (vec declaration))])}))

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
  "Analyze both the value and Zig output type accepted by `az/cast`."
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
