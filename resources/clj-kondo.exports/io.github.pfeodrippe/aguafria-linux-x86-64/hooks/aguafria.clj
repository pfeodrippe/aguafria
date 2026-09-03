(ns hooks.aguafria
  "Structural clj-kondo views of Aguafria's declaration macros."
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

(defn- expression-node
  [nodes]
  (case (count nodes)
    0 (token nil)
    1 (first nodes)
    (call-node 'do nodes)))

(defn- declaration-prefix
  [nodes]
  (loop [remaining nodes
         docstring nil]
    (let [value (some-> remaining first sexpr)]
      (cond
        (string? value)
        (recur (rest remaining) (first remaining))

        (map? value)
        (recur (rest remaining) docstring)

        :else
        {:declaration remaining
         :docstring docstring}))))

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

(defn function-declaration
  "Lint an `az/defn`, `az/defn-`, or `az/defextern` as a normal Clojure fn."
  [{:keys [node]}]
  (let [[operator definition-name & raw-declaration] (:children node)
        {:keys [declaration docstring]} (declaration-prefix raw-declaration)
        marker (marker-index declaration)
        return-type (when marker (nth declaration (inc marker) nil))
        bindings (when marker (nth declaration (+ marker 2) nil))
        body (if marker (drop (+ marker 3) declaration) [])
        {:keys [arguments types]}
        (if (= :vector (:tag bindings))
          (typed-bindings bindings)
          {:arguments [] :types []})
        operator-name (some-> operator sexpr name)
        private? (= "defn-" operator-name)
        extern? (= "defextern" operator-name)
        function-body (expression-node
                       (vec (concat (when return-type [return-type])
                                    types
                                    (when extern? arguments)
                                    body)))
        rewritten (concat [(token (if private? 'defn- 'defn)) definition-name]
                          (when docstring [docstring])
                          [(api/vector-node arguments) function-body])]
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
  "Analyze a Zig test body without interpreting its string/symbol test name."
  [{:keys [node]}]
  (let [[_ & raw-declaration] (:children node)
        declaration (if (map? (some-> raw-declaration first sexpr))
                      (rest raw-declaration)
                      raw-declaration)
        body (rest declaration)]
    {:node (expression-node (vec body))}))

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
