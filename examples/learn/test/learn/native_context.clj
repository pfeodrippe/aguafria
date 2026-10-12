(ns learn.native-context
  "Execution-only context selection for the Learn audit. Does not change source
  files or the editor's metadata-only REPL plan."
  (:require [aguafria.zig :as a]
            [aguafria.zig.value :as value]
            [aguafria.zig.emitter :as emitter]))

(defn- resolve-form-var [context head]
  (when (symbol? head)
    (ns-resolve context head)))

(defn- function-return? [context form]
  (when (coll? form)
    (let [head (when (seq? form) (first form))
          metadata (some-> (resolve-form-var context head) meta)
          token (get-in metadata [:aguafria/token :zig-token])]
      (cond
        (contains? #{'quote 'clojure.core/quote 'fn 'fn* 'aguafria.zig/fn 'aguafria.zig/fn-} head) false
        (or (= "return" token) (= 'return head)) true
        :else (some #(function-return? context %) form)))))

(defn- test-context-dependency? [context form visiting]
  (cond
    (and (seq? form) (contains? #{'quote 'clojure.core/quote} (first form))) false
    (symbol? form)
    (when-not (:aguafria/local? (meta form))
      (let [metadata (some-> (resolve-form-var context form) meta)
            qualified (when (:ns metadata) (symbol (str (ns-name (:ns metadata))) (str (:name metadata))))]
        (contains? #{'aguafria.builtin/is_test 'aguafria.builtin/test_functions} qualified)))
    (coll? form)
    (or (when (seq? form)
          (let [metadata (some-> (resolve-form-var context (first form)) meta)
                declaration (:aguafria/declaration metadata)
                qualified (when (:module declaration) (symbol (:module declaration) (str (:name declaration))))]
            (when (and qualified (= :fn (:kind declaration)) (not (visiting qualified)))
              (test-context-dependency? (the-ns (symbol (:module declaration)))
                                        (:body declaration) (conj visiting qualified)))))
        (some #(test-context-dependency? context % visiting) form))
    :else false))

(defn- local-scope-reason
  "Find cleanup or compiler directives not enclosed by an explicit native scope.
  Their enclosing authored function supplies the lifetime/compiler phase that
  individual JVM body replay cannot supply. Inspect syntax without execution."
  [context form]
  (if (seq? form)
    (let [head (first form)
          metadata (some-> (resolve-form-var context head) meta)
          token (get-in metadata [:aguafria/token :zig-token])]
      (cond
        (or (contains? #{"defer" "errdefer"} token)
            (and (symbol? head) (nil? (namespace head)) (nil? metadata)
                 (contains? #{"defer" "errdefer"} (name head)))) :function-scope-cleanup
        (= "@setEvalBranchQuota" (get-in metadata [:aguafria/token :zig-name]))
        :function-scope-compiler-directive
        (or (:aguafria/scoped? metadata)
            (contains? #{'quote 'clojure.core/quote 'fn 'fn* 'block 'with-block} head)) false
        :else (some #(local-scope-reason context %) form)))
    (when (coll? form) (some #(local-scope-reason context %) form))))

(defn- inferred-error-call? [context form]
  (when (coll? form)
    (when-not (and (seq? form) (contains? #{'quote 'clojure.core/quote} (first form)))
      (or (when (seq? form)
            (let [declaration (some-> (resolve-form-var context (first form)) meta :aguafria/declaration)]
              (and (= :fn (:kind declaration))
                   (or (emitter/inferred-error-payload (:return declaration))
                       (when-let [qualifiers (:zig-qualifiers declaration)]
                         (re-find #"(?:^|\s)!\s*$" qualifiers))))))
          (some #(inferred-error-call? context %) form)))))

(defn- function-scope-reason [context form]
  (cond
    ;; Preserve the enclosing authored native function as the image-local
    ;; boundary. This does not make an inferred/open error transportable.
    (inferred-error-call? context form) :function-scope-error-image
    (test-context-dependency? context form #{}) :native-test-context
    (function-return? context form) :function-scope-return
    :else (local-scope-reason context form)))

(defn native-test-entries
  "Metadata-only entry selection after source registration. Does not invoke bodies."
  [context]
  (into [] (keep (fn [[name v]]
                   (let [declaration (:aguafria/declaration (meta v))]
                     (when (and (= :test (:kind declaration))
                                (some #(function-scope-reason context %) (:body declaration)))
                       (symbol (str (ns-name (the-ns context))) (str name))))))
        (ns-interns context)))

(defn requires-function-scope? [context form]
  (boolean (function-scope-reason context form)))

(defn- private-helpers-called-by-native-entries [context groups]
  (letfn [(called [name visiting]
            (if (visiting name)
              #{}
              (let [declaration (some-> (resolve-form-var context (symbol name))
                                        meta :aguafria/declaration)
                    body (emitter/qualify-form context (cons 'do (:body declaration)))
                    helpers (into #{}
                                  (keep (fn [form]
                                          (when (and (seq? form) (symbol? (first form))
                                                     (not (:aguafria/local? (meta (first form)))))
                                            (let [metadata (some-> (resolve-form-var context (first form)) meta)
                                                  target (str (:name metadata))]
                                              (when (and (:private metadata) (= (the-ns context) (:ns metadata))
                                                         (contains? groups target)
                                                         (= :fn (get-in metadata [:aguafria/declaration :kind])))
                                                target)))))
                                  (tree-seq #(and (coll? %)
                                                  (not (and (seq? %) (= 'quote (first %)))))
                                            seq body))]
                (into helpers (mapcat #(called % (conj visiting name))) helpers))))]
    (into #{}
          (mapcat (fn [[name _]]
                    (let [declaration (some-> (resolve-form-var context (symbol name))
                                              meta :aguafria/declaration)]
                      (when (or (= :test (:kind declaration))
                                (and (= :fn (:kind declaration))
                                     (empty? (:args declaration))
                                     (some #(function-scope-reason context %) (:body declaration))))
                        (called name #{})))))
          groups)))

(defn initializer-value!
  "Demand the compiled namespace initializer in its authored comptime context,
  without replaying its expression as an unrelated runtime function call."
  [initialized]
  (when (value/zig-value? initialized) (value/realize! initialized))
  initialized)

(defn- initializer-row [context row]
  (let [declaration (some-> (resolve-form-var context (symbol (:declaration row)))
                            meta :aguafria/declaration)]
    (if (and (= :initializer (:kind row)) (#{:const :var} (:kind declaration)))
      (-> row
          (dissoc :type-context)
          (assoc :context-reason :namespace-initialization
                 :source-initializer row
                 :expression (str "(learn.native-context/initializer-value! "
                                  (:declaration row) ")")))
      row)))

(defn execution-rows
  "Private helpers called by native entries are not additional entry points:
  replaying them first would execute application side effects before the entry.
  Retain ordinary replay rows, but replace each zero-argument native function
  requiring its authored context with one explicitly labeled invocation.
  Parameterized/skipped functions remain unexercised rather than inventing args."
  [context rows]
  (let [rows (mapv #(initializer-row context %) rows)
        groups (group-by :declaration rows)
        helpers (private-helpers-called-by-native-entries context groups)
        replacements
        (into {}
              (keep (fn [[name body-rows]]
                      (let [declaration (some-> (resolve-form-var context (symbol name))
                                                meta :aguafria/declaration)
                            reason (some #(function-scope-reason context %) (:body declaration))]
                        (when (and (contains? #{:fn :test} (:kind declaration))
                                   (empty? (:args declaration))
                                   (not-any? #(or (= :skipped (:status %))
                                                  (= :initializer (:kind %))) body-rows)
                                   reason)
                          [name (assoc (first body-rows)
                                       :kind :native-context
                                       :context-reason reason
                                       :generated-invocation? true
                                       :expression (str "(" name ")")
                                       :source-body-rows (vec body-rows))]))))
              groups)]
    (:rows
     (reduce (fn [{:keys [seen] :as state} row]
               (cond
                 (and (helpers (:declaration row)) (not= :initializer (:kind row)))
                 (update state :rows conj
                         (assoc row :status :skipped :reason :private-helper-called-by-native-entry))
                 (get replacements (:declaration row))
                 (if (contains? seen (:declaration row)) state
                     (-> state (update :rows conj (get replacements (:declaration row)))
                         (update :seen conj (:declaration row))))
                 :else (update state :rows conj row)))
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
