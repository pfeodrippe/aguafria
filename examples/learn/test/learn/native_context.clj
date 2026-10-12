(ns learn.native-context
  "Execution-only context selection for the Learn audit. Does not change source
  files or the editor's metadata-only REPL plan."
  (:require [aguafria.zig :as a]
            [aguafria.zig.value :as value]))

(defn- resolve-form-var [context head]
  (when (symbol? head)
    (ns-resolve context head)))

(defn requires-function-scope?
  "Find cleanup forms not already enclosed by an explicit native scope. Calling
  the enclosing native function preserves their lifetime; replaying individual
  body forms does not. This inspects syntax only and never invokes a body."
  [context form]
  (if (seq? form)
    (let [head (first form)
          metadata (some-> (resolve-form-var context head) meta)
          token (get-in metadata [:aguafria/token :zig-token])]
      (cond
        (or (contains? #{"defer" "errdefer"} token)
            (and (symbol? head) (nil? (namespace head)) (nil? metadata)
                 (contains? #{"defer" "errdefer"} (name head)))) true
        (or (:aguafria/scoped? metadata)
            (contains? #{'quote 'clojure.core/quote 'fn 'fn* 'block 'with-block} head)) false
        :else (boolean (some #(requires-function-scope? context %) form))))
    (and (coll? form) (boolean (some #(requires-function-scope? context %) form)))))

(defn execution-rows
  "Retain ordinary replay rows, but replace each zero-argument native function
  requiring an enclosing cleanup scope with one explicitly labeled invocation.
  Parameterized/skipped functions remain unexercised rather than inventing args."
  [context rows]
  (let [groups (group-by :declaration rows)
        replacements
        (into {}
              (keep (fn [[name body-rows]]
                      (let [declaration (some-> (resolve-form-var context (symbol name))
                                                meta :aguafria/declaration)]
                        (when (and (contains? #{:fn :test} (:kind declaration))
                                   (empty? (:args declaration))
                                   (not-any? #(or (= :skipped (:status %))
                                                  (= :initializer (:kind %))) body-rows)
                                   (some #(requires-function-scope? context %) (:body declaration)))
                          [name (assoc (first body-rows)
                                       :kind :native-context
                                       :context-reason :function-scope-cleanup
                                       :generated-invocation? true
                                       :expression (str "(" name ")")
                                       :source-body-rows (vec body-rows))]))))
              groups)]
    (:rows
     (reduce (fn [{:keys [seen] :as state} row]
               (if-let [replacement (get replacements (:declaration row))]
                 (if (contains? seen (:declaration row)) state
                     (-> state (update :rows conj replacement)
                         (update :seen conj (:declaration row))))
                 (update state :rows conj row)))
             {:rows [] :seen #{}} rows))))

(defn check-result!
  "A positive native context invocation must not silently return a native error,
  including a failed std.testing assertion. Ordinary replay error values retain
  their existing meaning and are checked by the assertion instrumentation."
  [row result]
  (when (= :native-context (:kind row))
    (let [decoded (a/value result)]
      (when (or (value/zig-error? result)
                (and (map? decoded) (= #{:error} (set (keys decoded)))))
        (throw (ex-info "Native context invocation returned an error"
                        {:row row :native-result decoded})))))
  result)
