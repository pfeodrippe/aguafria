(ns aguafria.zig.discovery
  "Compile-only operation discovery. Zig supplies every observed type; emitter
  records supply operation identity, never Clojure type inference."
  (:require [aguafria.keyword :as keyword]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- qualified-name [{:keys [ns name]}]
  (when (and ns name) (symbol (str (ns-name ns)) (str name))))

(defn- needs-result-context? [source]
  (boolean (some keyword/result-context-required?
                 (re-seq #"@[A-Za-z0-9_]+(?=\()" source))))

(defn- slice-index-probe [render receiver index]
  (let [source (render index)]
    (if (needs-result-context? source)
      ;; Query the native receiver's slice length type inside Zig. An isolated
      ;; @TypeOf(@intCast(...)) loses the index result location. This annotation
      ;; is only for the probe; the original slice expression stays unchanged.
      (str "@as(@TypeOf(((" (render receiver) ")[0..0]).len), " source ")")
      source)))

(def ^:private container-kinds #{:struct :enum :union :opaque})

(def ^:dynamic ^:private *observed-type-identities* [])
(def ^:dynamic ^:private *local-type-identities* #{})
(def ^:dynamic ^:private *inspection-roots* nil)
(def ^:dynamic ^:private *rejected-inspection-roots* #{})
(def ^:dynamic ^:private *inspection-specializations* [])

(defn- literal-data? [form]
  (cond
    (or (nil? form) (number? form) (boolean? form) (char? form)
        (string? form) (keyword? form)) true
    (symbol? form) (= :primitive (:kind (keyword/resolve-token
                                         (or emitter/*keyword-context* *ns*) form)))
    (vector? form) (every? literal-data? form)
    (map? form) (every? literal-data? (mapcat identity form))
    :else false))

(defn- declared-type? [{:keys [module name kind]}]
  (or (container-kinds kind)
      (some-> (find-ns (symbol module))
              (ns-resolve name) meta :aguafria/zig-reference :type-reference?)))

(defn- constructor-literal [form]
  (let [form (if (seq? form)
               ((requiring-resolve 'aguafria.zig.jvm/qualify-native-type)
                (or emitter/*keyword-context* *ns*) form)
               form)
        data ((requiring-resolve 'aguafria.zig.jvm/canonical-comptime-object) form)]
    ;; Literal objects and maps share the ordinary JVM constructor identity.
    ;; This normalizes initializer syntax; Zig supplies the destination type.
    (when (literal-data? data) {:value data})))

(defn- aggregate-address-form? [form]
  (and (seq? form) (= 2 (count form)) (symbol? (first form))
       (= "&" (some-> (ns-resolve (or emitter/*keyword-context* *ns*) (first form))
                      meta :aguafria/token :zig-token))
       (or (vector? (second form)) (map? (second form)))))

(defn- contextual-call-plan [form]
  ;; Retain only call syntax here. Zig observes the types of the leaf operands
  ;; in their original scope; nested casts are not queried without a result type.
  (let [leaves (atom [])
        parameters (atom [])]
    (letfn [(visit [form parameter]
              (let [metadata (when (and (seq? form) (symbol? (first form)))
                               (some-> (ns-resolve (or emitter/*keyword-context* *ns*)
                                                   (first form)) meta))
                    syntax (:aguafria/token metadata)]
                (cond
                  (keyword/result-context-required? (:zig-name syntax))
                  {:function (qualified-name metadata)
                   :arguments (mapv visit (rest form)
                                    (concat ((requiring-resolve 'aguafria.zig.jvm/call-parameters)
                                             metadata (dec (count form)))
                                            (repeat nil)))}

                  (literal-data? form)
                  {:literal form}

                  :else
                  (let [index (count @leaves)]
                    (swap! leaves conj form)
                    (swap! parameters conj parameter)
                    {:input index}))))]
      (let [plan (visit form nil)]
        (when (:function plan)
          {:plan plan :leaves @leaves :parameters @parameters})))))

(defn- contextual-scope-plan [form]
  (let [context (or emitter/*keyword-context* *ns*)
        metadata (meta form)]
    (when (and (seq? form)
               (:aguafria/scoped-template metadata)
               (emitter/scoped-result-context-required? context form))
      {:form (:aguafria/scoped-template metadata)
       :references (:aguafria/scoped-captures metadata)
       :captures (mapv first (:aguafria/scoped-captures metadata))})))

(declare operand-schema)

(defn- scoped-capture-contracts [references parameters]
  (let [parameters (into {} (map (juxt :name identity)) parameters)]
    (into {}
          (keep (fn [[name reference]]
                  (let [parameter (get parameters reference)]
                    (when (= "comptime" (get-in parameter [:properties :zig/prefix]))
                      [name {:phase :comptime :type (:type parameter)}]))))
          references)))

(defn- scoped-capture-schemas
  ([render references] (scoped-capture-schemas render references {}))
  ([render references contracts]
   (mapcat (fn [[name reference]]
             [(if (= :comptime (get-in contracts [name :phase]))
                ;; The declaring parameter supplies the phase; Zig supplies
                ;; both the actual native type and value in this specialization.
                (str "\"{:comptime-capture [\" ++ __aguafria_probe.schema(@TypeOf("
                     (render reference) ")) ++ \" \" ++ __aguafria_probe.comptimeValue("
                     (render reference) ") ++ \"]}\"")
                (operand-schema render (render reference)
                                {:properties {:jvm/literal? true}} reference "argumentSchema"))
              (str "__aguafria_probe.schema(@TypeOf(&(" (render reference) ")))")])
           references)))

(defn- field-reference? [form]
  (and (seq? form) (symbol? (first form))
       (= "field" (name (first form))) (= 3 (count form))
       (or (keyword? (nth form 2)) (string? (nth form 2)))))

(defn- native-function-source [form]
  (when (and (symbol? form) (not (contains? emitter/*lexical-bindings* form)))
    (let [metadata (some-> (ns-resolve (or emitter/*keyword-context* *ns*) form) meta)]
      (when (contains? #{:fn :fn-proto} (get-in metadata [:aguafria/declaration :kind]))
        (qualified-name metadata)))))

(defn- declaration-probe [render form]
  (when (field-reference? form)
    (let [receiver (render (second form))
          member (nth form 2)
          identifier (emitter/identifier member)
          member-string (if (str/starts-with? identifier "@\"")
                          (subs identifier 1)
                          (artifact/print-data identifier))]
      {:receiver receiver
       :available (str "(@TypeOf(" receiver ") == type and "
                       "__aguafria_probe.hasDeclaration(" receiver ", " member-string "))")
       :schema (str "__aguafria_probe.declarationArgumentSchema(" receiver ", "
                    member-string ", " (artifact/print-data (artifact/print-data member)) ")")})))

(defn- declaration-argument-schema [render form fallback]
  (let [metadata (when (and (symbol? form)
                            (not (contains? emitter/*lexical-bindings* form)))
                   (some-> (ns-resolve (or emitter/*keyword-context* *ns*) form) meta))
        reference (:aguafria/zig-reference metadata)
        function-source (native-function-source form)]
    (cond
      function-source
      (str "__aguafria_probe.comptimeExpression(" (render form) ", "
           (artifact/print-data (artifact/print-data function-source)) ")")

      reference
      (str "(if (@typeInfo(@TypeOf(" (render form) ")) == .@\"fn\") "
           "__aguafria_probe.comptimeExpression(" (render form) ", "
           (artifact/print-data (artifact/print-data (qualified-name metadata))) ") else " fallback ")")

      (field-reference? form)
      (let [{:keys [receiver schema]} (declaration-probe render form)]
        (str "(if (@TypeOf(" receiver ") == type) " schema " else " fallback ")"))

      (and (seq? form) (= 'if (first form)) (= 4 (count form))
           (every? field-reference? (drop 2 form)))
      (let [branches (mapv #(declaration-probe render %) (drop 2 form))]
      ;; A JVM if supplies one branch value to the call. Ask Zig for both
      ;; declaration representations, without evaluating the condition or body.
      ;; The guard avoids forcing a missing member in a dead source branch.
        (str "(if (" (str/join " and " (map :available branches)) ") "
             "\"{:representations [\" ++ " (str/join " ++ \" \" ++ " (map :schema branches))
             " ++ \"]}\" else " fallback ")"))

      :else fallback)))

(defn- literal-branches [form]
  (if (and (seq? form) (= 'if (first form)) (= 4 (count form)))
    (let [branches (map literal-branches (drop 2 form))]
      (when (every? some? branches) (mapcat identity branches)))
    (when (literal-data? form) [form])))

(defn- initializer-source [form]
  (letfn [(expand [form visiting]
            (let [candidates (filter #(and (symbol? %)
                                           (contains? (meta %) :aguafria/jvm-initializer))
                                     (tree-seq coll? seq form))
                  free (set (emitter/scoped-captures
                             (or emitter/*keyword-context* *ns*) form candidates))]
              ;; Qualified bindings have distinct names. Expand only free
              ;; references; nested bindings and their uses stay in their scope.
              (letfn [(visit [item]
                        (if (and (symbol? item) (free item)
                                 (contains? (meta item) :aguafria/jvm-initializer)
                                 (not (contains? visiting item)))
                          (expand (:aguafria/jvm-initializer (meta item)) (conj visiting item))
                          (walk/walk visit identity item)))]
                (visit form))))]
    (expand form #{})))

(defn- compile-scoped-constant-proof!
  "Optionally prove closed immutable captures in their original lexical scope.
  A failed proof is evidence only: it does not register declarations or replace
  the ordinary observation. Both native type and value must agree at comptime."
  [caller form capture-types]
  (let [context (the-ns caller)
        captures (set (keys capture-types))
        canonical (fn [form]
                    (binding [emitter/*local-name-bindings* (zipmap captures captures)
                              emitter/*local-type-bindings* (zipmap captures (repeat false))]
                      (artifact/print-data (emitter/qualify-form context form))))
        expected (canonical form)
        matches (atom [])
        result
        (runtime/inspect-module!
         caller
         (fn [declarations]
           (let [reserved-labels (into #{}
                                       (keep #(when (or (symbol? %) (keyword? %))
                                                (emitter/identifier %)))
                                       (tree-seq coll? seq declarations))]
             {:source
              (str "const __aguafria_scoped_proof = @import(\"jvm_result.zig\").__aguafria_jvm;\n"
                   (binding [emitter/*expression-observer*
                             (fn [{:keys [form source source-bindings place-probe render]}]
                               (if-let [template (:aguafria/scoped-template (meta form))]
                                 (if (= expected (canonical template))
                                   (let [references (:aguafria/scoped-captures (meta form))
                                         candidates
                                         (into (array-map)
                                               (keep
                                                (fn [[name reference]]
                                                  (when (and (captures name)
                                                             (:aguafria/jvm-initializer (meta reference)))
                                                    (let [candidate (initializer-source reference)]
                                                      (when-not (some (set source-bindings)
                                                                      (tree-seq coll? seq candidate))
                                                        [name {:source candidate
                                                               :native-source (render candidate)
                                                               :reference (render reference)}])))))
                                               references)]
                                     (let [index (count @matches)]
                                       (swap! matches conj candidates)
                                       (if (seq candidates)
                                         (let [proof
                                               (str "comptime { if ("
                                                    (str/join " and "
                                                              (for [[name {:keys [reference]}] candidates]
                                                                (str "@TypeOf(" reference ") == "
                                                                     (emitter/emit-type (get capture-types name)))))
                                                    ") { "
                                                    (apply str
                                                           (for [[_ {:keys [reference native-source]}] candidates]
                                                             (str "__aguafria_scoped_proof.proveScopedCapture("
                                                                  reference ", " native-source "); ")))
                                                    "@compileLog(\"aguafria.scoped.proof.accepted:" index "\"); } }")]
                                           {:source
                                            (if place-probe
                                              (place-probe proof "aguafria_scoped_constant_proof")
                                          ;; A value observation has no statement
                                          ;; placement callback. Keep its actual
                                          ;; destination, owners and rendered
                                          ;; value in the original lexical scope.
                                          ;; Reuse the emitter's collision-safe
                                          ;; lexical naming scope for this label.
                                              (let [label (first (remove reserved-labels
                                                                         (repeatedly #(emitter/identifier
                                                                                       (#'emitter/binding-temp form)))))]
                                                (str "(" label ": { " proof " break :" label " " source "; })")))})
                                         source)))
                                   source)
                                 source))]
                     (emitter/emit-module (str caller) declarations)))
              :files {"jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}})))
        log (second (str/split (:err result) #"Compile Log Output:\r?\n" 2))
        accepted (mapv #(Long/parseLong (second %))
                       (re-seq #"\"aguafria\.scoped\.proof\.accepted:([0-9]+)\"" (or log "")))
        selected (distinct (map #(nth @matches %) accepted))
        candidates (when (= 1 (count selected)) (first selected))
        proven? (and (= 1 (:exit result)) (seq candidates)
                     (seq accepted)
                     (not (re-find #"(?m)error: (?!found compile log statement)" (:err result))))]
    (merge {:proven? (boolean proven?)
            :candidates (when proven? candidates)
            :matches (count @matches) :accepted accepted :basis :zig-compiler}
           (select-keys result [:exit :err :command :source-path]))))

(defn prove-scoped-constants!
  "Prove value-only scoped captures; storage-observing/opaque scopes retain
  their ordinary runtime contract. Value equality cannot establish an address."
  [caller form capture-types]
  (let [context (the-ns caller)
        names (keys capture-types)
        canonical (binding [emitter/*local-name-bindings* (zipmap names names)
                            emitter/*local-type-bindings* (zipmap names (repeat false))]
                    (emitter/qualify-form context form))
        storage-heads #{"&" "pointer-capture" "deref" "slice" "slice-sentinel"
                        "intFromPtr" "ptrCast" "constCast" "raw" "raw-statements"
                        "asm" "asm-expr"}
        observed (into #{}
                       (keep #(when (and (seq? %) (symbol? (first %))
                                         (storage-heads (name (first %))))
                                (first %)))
                       (tree-seq coll? seq canonical))]
    (if (seq observed)
      {:proven? false :reason :scoped-storage-identity-observed :forms observed}
      (compile-scoped-constant-proof! caller canonical capture-types))))

(defn- jvm-anonymous-type-source
  "Retain an explicit container macro and its explicit lexical type operands.
  This is source provenance, not a schema inferred from the Clojure expression."
  [context form]
  (letfn [(source [form visiting]
            (cond
              (and (symbol? form) (not (contains? visiting form))
                   (contains? (meta form) :aguafria/jvm-initializer))
              (source (:aguafria/jvm-initializer (meta form)) (conj visiting form))

              (and (seq? form) (symbol? (first form))
                   (= "container" (name (first form))))
              (let [names (into #{} (filter #(and (symbol? %)
                                                  (:aguafria/local? (meta %))))
                                (tree-seq coll? seq form))
                    captures (emitter/scoped-captures context form names)
                    locals (mapv (fn [name] [name (source name visiting)]) captures)]
                (when (every? (comp some? second) locals)
                  {:caller (ns-name context) :container form :locals (into {} locals)}))

              :else nil))]
    (source form #{})))

(defn- jvm-value-type-source
  "Record explicit construction, field and capture provenance for JVM values.
  No initializer is evaluated and no host expression supplies a native type."
  [context form]
  (letfn [(source [form visiting]
            (cond
              (and (symbol? form) (not (contains? visiting form))
                   (:aguafria/jvm-binding-source (meta form)))
              (let [{:keys [initializer variable?]} (:aguafria/jvm-binding-source (meta form))]
                (some-> (source initializer (conj visiting form))
                        (assoc :mutable? variable?)))

              (and (symbol? form) (:aguafria/jvm-switch-capture (meta form)))
              (let [{:keys [receiver patterns pointer?]} (:aguafria/jvm-switch-capture (meta form))
                    receiver (source receiver visiting)
                    members (when (every? field-reference? patterns) (mapv #(nth % 2) patterns))]
                ;; Each prong may name several tags. Zig must establish that
                ;; their actual payload types agree in the inspection query.
                (when (and receiver (seq members))
                  (cond-> (assoc receiver :capture-fields members :pointer? pointer?)
                    pointer? (assoc :pointer-mutable? (:mutable? receiver) :mutable? false))))

              (and (seq? form) (symbol? (first form))
                   (= "var" (name (first form))))
              (some-> (source (second form) visiting) (assoc :mutable? true))

              (and (seq? form) (field-reference? form))
              (some-> (source (second form) visiting)
                      (update :fields (fnil conj []) (nth form 2)))

              (and (seq? form) (symbol? (first form)) (= "deref" (name (first form))))
              (when-let [pointer (source (second form) visiting)]
                (when (:pointer? pointer)
                  (assoc pointer :pointer? false :mutable? (:pointer-mutable? pointer))))

              (and (seq? form) (symbol? (first form)))
              (when-let [type (jvm-anonymous-type-source context (first form))]
                (cond-> {:type-source type :fields [] :mutable? false}
                  (and (= 2 (count form)) (map? (second form)))
                  (assoc :input-source (second form))))

              :else nil))]
    (source form #{})))

(defn- comptime-storage-origin [render form]
  (when (and (seq? form) (symbol? (first form))
             (contains? #{'field 'index 'slice 'deref 'unwrap
                          'aguafria.zig/field 'aguafria.zig/index 'aguafria.zig/get
                          'aguafria.zig/slice 'aguafria.zig/deref 'aguafria.zig/unwrap}
                        (first form)))
    (let [receiver (second form)
          parent (comptime-storage-origin render receiver)
          predicate (str "(if (@TypeOf(" (render receiver) ") == type) false else "
                         "__aguafria_probe.requiresComptime(@TypeOf(" (render receiver) ")))")]
      (if parent (str "(" predicate " or " parent ")") predicate))))

(defn- comptime-type-captures [context source lexical-bindings]
  (let [captures (atom [])
        names (into #{} (filter symbol?) (tree-seq coll? seq source))
        next-name (atom 0)]
    (letfn [(visit [form]
              (if (and (seq? form) (symbol? (first form))
                       (= 'aguafria.keyword/TypeOf
                          (some-> (ns-resolve context (first form)) meta qualified-name))
                       (seq (emitter/scoped-captures context form lexical-bindings)))
                ;; @TypeOf does not read its operand. Capture Zig's type result,
                ;; not the runtime local that happens to appear inside the call.
                (let [name (loop []
                             (let [candidate (symbol (str "__aguafria_comptime_type_"
                                                          (swap! next-name inc)))]
                               (if (contains? names candidate) (recur) candidate)))]
                  (swap! captures conj [name form])
                  name)
                (walk/walk visit identity form)))]
      {:source (visit source) :captures @captures})))

(defn- comptime-source-schema [render expression source captures]
  (if (empty? captures)
    (str "__aguafria_probe.comptimeExpression(" expression ", "
         (artifact/print-data (artifact/print-data source)) ")")
    (str (artifact/print-data (str "{:comptime-template [" (artifact/print-data source) " {"))
         " ++ "
         (str/join " ++ \" \" ++ "
                   (map (fn [[name capture]]
                          (str (artifact/print-data (str name " ")) " ++ "
                               "(if (@TypeOf(" (render capture) ") == type) "
                               "\"{:comptime-type \" ++ __aguafria_probe.schema(" (render capture)
                               ") ++ \"}\" else if (@typeInfo(@TypeOf(" (render capture) ")) == .int) "
                               "__aguafria_probe.constantCoercion(" (render capture) ") else "
                               "__aguafria_probe.comptimeValue(" (render capture) "))"))
                        captures))
         " ++ \"}]}\"")))

(defn- concrete-call-result-type [render form]
  (when (and (seq? form) (symbol? (first form)))
    (let [declaration (some-> (ns-resolve (or emitter/*keyword-context* *ns*)
                                          (first form))
                              meta :aguafria/declaration)]
      (when (and (contains? #{:fn :fn-proto} (:kind declaration))
                 (not-any? #(or (#{:anytype 'anytype :type 'type} (:type %))
                                (= "comptime" (get-in % [:properties :zig/prefix])))
                           (:args declaration)))
        (str "@typeInfo(@TypeOf(" (render (first form))
             ")).@\"fn\".return_type.?")))))

(defn- payload-projection-type [render form]
  ;; Reflect the function signature, then ask Zig to type-check projections.
  ;; Taking the address of a try-call would otherwise promote its comptime
  ;; backing storage to runtime storage merely to inspect the pointer type.
  (when (seq? form)
    (cond
      (:aguafria/native-try? (meta form))
      (when-let [carrier (concrete-call-result-type render (second form))]
        (str "@typeInfo(" carrier ").error_union.payload"))

      (field-reference? form)
      (when-let [receiver (payload-projection-type render (second form))]
        (let [member (emitter/identifier (nth form 2))]
          (str "@TypeOf(@field(@as(" receiver ", undefined), "
               (if (str/starts-with? member "@\"")
                 (subs member 1)
                 (artifact/print-data member)) "))"))))))

(defn- render-type-query [render form]
  (render
   (walk/postwalk
    (fn [item]
      (if-let [type (payload-projection-type render item)]
        (list 'aguafria.zig/raw (str "@as(" type ", undefined)"))
        item))
    form)))

(defn- operand-schema [render expression parameter form schema-kind]
  (let [form (or (:aguafria/jvm-field-source (meta form)) form)
        metadata (when (and (seq? form) (symbol? (first form)))
                   (some-> (ns-resolve (or emitter/*keyword-context* *ns*) (first form)) meta))
        syntax (:aguafria/token metadata)
        projection-type (payload-projection-type render form)
        jvm-operand (or (:aguafria/jvm-operand (meta form))
                        (when (and (seq? form) (= 'try (first form)) (= 2 (count form))
                                   (not (:aguafria/native-try? (meta form))))
                          (second form)))
        jvm-declaration (when (and (seq? jvm-operand) (symbol? (first jvm-operand)))
                          (some-> (ns-resolve (or emitter/*keyword-context* *ns*)
                                              (first jvm-operand))
                                  meta :aguafria/declaration))
        jvm-transport-type
        (when (and jvm-declaration
                   (not-any? #(or (#{:anytype 'anytype :type 'type} (:type %))
                                  (= "comptime" (get-in % [:properties :zig/prefix])))
                             (:args jvm-declaration)))
          (if (emitter/inferred-error-payload (:return jvm-declaration))
            (render (list 'aguafria.zig/type
                          ((requiring-resolve 'aguafria.zig.jvm/constructor-type)
                           (:return jvm-declaration))))
            (str "@typeInfo(@TypeOf(" (render (first jvm-operand))
                 ")).@\"fn\".return_type.?")))
        branches (when (and (seq? form) (= 'if (first form))) (literal-branches form))
        value-form (walk/postwalk
                    (fn [item]
                      (cond
                        (and (seq? item) (= 3 (count item))
                             (symbol? (first item)) (= "field" (name (first item))))
                        (with-meta (list 'aguafria.zig/field (second item)
                                         (if (symbol? (nth item 2))
                                           (keyword (name (nth item 2))) (nth item 2)))
                          (meta item))
                        (and (seq? item) (simple-symbol? (first item))
                             (contains? #{'index 'slice 'deref 'unwrap} (first item)))
                        (with-meta (cons (symbol "aguafria.zig" (name (first item))) (rest item))
                          (meta item))
                        :else item)) (initializer-source form))
        context (or emitter/*keyword-context* *ns*)
        lexical-bindings (into emitter/*lexical-bindings*
                               (filter #(and (symbol? %) (:aguafria/local? (meta %))))
                               (tree-seq coll? seq value-form))
        type-captures (comptime-type-captures context value-form lexical-bindings)
        value-form (:source type-captures)
        source-captures (into (:captures type-captures)
                              (map #(vector % %))
                              (emitter/scoped-captures context value-form lexical-bindings))
        qualified-source (when value-form
                           (walk/postwalk
                            (fn [item]
                              (if (and (seq? item) (simple-symbol? (first item))
                                       (contains? #{'field 'index 'slice 'deref 'unwrap} (first item)))
                                (with-meta (cons (symbol "aguafria.zig" (name (first item))) (rest item))
                                  (meta item))
                                item))
                            ((requiring-resolve 'aguafria.zig.jvm/qualify-native-type)
                             (or emitter/*keyword-context* *ns*) value-form)))
        closed-source (when (empty? source-captures) qualified-source)
        comptime-origin (when form
                          (comptime-storage-origin render form))
        join-schemas #(if (seq %) (str/join " ++ \" \" ++ " %) "\"\"")]
    (cond
      (aggregate-address-form? form)
      ;; A source initializer's anonymous pointer schema omits its destination
      ;; element type. Retain the JVM aggregate representation; the real callee
      ;; parameter supplies that type during both preparation and demand.
      (str "\"{:contextual-address \" ++ "
           (if (vector? (second form))
             (str "\"{:tuple [\" ++ "
                  (join-schemas
                   (map #(operand-schema render (render %) nil % "argumentSchema")
                        (second form)))
                  " ++ \"]}\"")
             (operand-schema render (render (second form)) nil (second form) "argumentSchema"))
           " ++ \"}\"")

      projection-type
      (str "__aguafria_probe." schema-kind "(" projection-type ")")

      jvm-operand
      ;; Clojure try preserves its result; Zig try unwraps an error union.
      ;; Ask Zig for the payload and the JVM carrier, including lexical aliases.
      ;; Inferred-error returns use the existing anyerror transport contract.
      ;; This is inspection-only and never calls the operand's function.
      (str "\"{:representations [\" ++ "
           (if jvm-transport-type
             (str "__aguafria_probe.schema(@typeInfo(" jvm-transport-type
                  ").error_union.payload)")
             (operand-schema render expression parameter
                             (if (symbol? form)
                               (vary-meta form dissoc :aguafria/jvm-operand)
                               nil)
                             schema-kind))
           " ++ \" \" ++ __aguafria_probe.schema("
           (if jvm-transport-type
             jvm-transport-type
             (str "@TypeOf(" (render jvm-operand) ")"))
           ") ++ \"]}\"")

      (:jvm/comptime-slice? (:properties parameter))
      (str "(if (@typeInfo(@TypeOf(" expression ")) == .pointer and "
           "@typeInfo(@TypeOf(" expression ")).pointer.size == .slice) "
           "__aguafria_probe.comptimeValue(" expression ") else "
           (operand-schema render expression
                           (update parameter :properties dissoc :jvm/comptime-slice?)
                           form schema-kind) ")")

      ((requiring-resolve 'aguafria.zig.jvm/source-concatenation-form?) form)
      ;; Preserve the same source expression returned by the ordinary JVM
      ;; concatenation adapter. The probe's comptime parameter asks Zig to
      ;; validate it before the representation enters a preparation signature.
      (str "__aguafria_probe.comptimeExpression(" expression ", "
           (artifact/print-data (artifact/print-data form)) ")")

      (and (= 'aguafria.zig/error-value (qualified-name metadata))
           ((requiring-resolve 'aguafria.zig.jvm/source-literal-call?)
            'aguafria.zig/error-value (rest form)))
      (str "__aguafria_probe.comptimeValue(" expression ")")

      (keyword/result-context-required? (:zig-name syntax))
      (let [parameters ((requiring-resolve 'aguafria.zig.jvm/call-parameters)
                        metadata (dec (count form)))]
        ;; The JVM passes this deferred call, not its result. Query only leaf
        ;; types; the receiving Zig call supplies the result location later.
        (str (artifact/print-data (str "{:contextual-call [" (qualified-name metadata) " [")) " ++ "
             (join-schemas (map #(operand-schema render (render %1) %2 %1 "argumentSchema")
                                (rest form) (concat parameters (repeat nil))))
             " ++ \"]]}\""))

      (seq branches)
      (str "\"{:representations [\" ++ "
           (join-schemas (map #(operand-schema render (render %) parameter % schema-kind) branches))
           " ++ \"]}\"")

      (map? form)
      (str "\"{:map {\" ++ "
           (join-schemas (map (fn [[key item]]
                                (str (artifact/print-data (str (artifact/print-data key) " ")) " ++ "
                                     ;; A map inherits its containing argument's
                                     ;; phase. A string accepted by a comptime
                                     ;; parameter cannot acquire a runtime slice
                                     ;; alternative merely because it is nested.
                                     (if (and (string? item)
                                              (not= "comptime"
                                                    (get-in parameter [:properties :zig/prefix])))
                                       (str "__aguafria_probe.jvmMapStringArgumentSchema(" (render item) ")")
                                       (operand-schema render (render item) parameter item "argumentSchema")))) form))
           " ++ \"}}\"")

      :else
      (let [schema
            (str "(if (@TypeOf(" expression ") == type) \"{:comptime-type \" ++ __aguafria_probe.schema(" expression ") ++ \"}\" "
                 "else if (@TypeOf(" expression ") == comptime_int or @TypeOf(" expression ") == comptime_float) "
                 "__aguafria_probe.literal(" expression ") "
                 "else if (@typeInfo(@TypeOf(" expression ")) == .enum_literal) "
                 "__aguafria_probe.comptimeValue(" expression ") "
                 "else if (@typeInfo(@TypeOf(" expression ")) == .null or "
                 "@typeInfo(@TypeOf(" expression ")) == .undefined) "
                 "__aguafria_probe.schema(@TypeOf(" expression ")) "
                 "else if (__aguafria_probe.requiresComptime(@TypeOf(" expression "))"
                 (when comptime-origin (str " or " comptime-origin)) ") "
                 (if qualified-source
                   (comptime-source-schema render expression qualified-source source-captures)
                   (str "\"{:comptime-local-type \" ++ __aguafria_probe.schema(@TypeOf("
                        expression ")) ++ \"}\""))
                 " else "
                 (if (or (= "comptime" (get-in parameter [:properties :zig/prefix]))
                         (and (string? form) (get-in parameter [:properties :jvm/literal?])))
                   (let [value (if (string? form) (render form) expression)
                         schema (if closed-source
                                  (str "__aguafria_probe.comptimeArgument(" value ", "
                                       (artifact/print-data (artifact/print-data closed-source)) ")")
                                  (str "__aguafria_probe.comptimeValue(" value ")"))]
                     (if (symbol? form)
                       (declaration-argument-schema render form schema)
                       schema))
                   (declaration-argument-schema render form
                                                (str "__aguafria_probe." schema-kind "(@TypeOf(" expression "))")))
                 ") ")]
        (if-let [result-type (concrete-call-result-type render form)]
          (str "(if (__aguafria_probe.requiresComptime(" result-type ")) " schema
               " else __aguafria_probe." schema-kind "(" result-type "))")
          schema)))))

(defn- observer [operations selected]
  (fn [{:keys [form source-form source location var-meta render placement place-probe defer-probe assignment
               method-call? receiver member declaration-name declaration-kind root-declaration-name
               declared-constant-initializer?
               source-bindings signature-position? result-context result-context-origin
               result-context-envelope source-parameters]}]
    ;; Probe construction must not observe its own emitted type expressions.
    (binding [emitter/*lexical-bindings* (into emitter/*lexical-bindings* source-bindings)
              emitter/*expression-observer* nil
              emitter/*inspection-placement* nil]
      (let [syntax (:aguafria/token var-meta)
            function (if method-call? 'aguafria.zig/field (qualified-name var-meta))
            type-expression? (= 'aguafria.zig/type function)
            scoped? (:aguafria/scoped? var-meta)
            scope-result? (and scoped?
                               (emitter/scoped-result? (or emitter/*keyword-context* *ns*) form))
            scope-result-context? (boolean
                                   (and scope-result? result-context
                                        (or result-context-origin
                                            (emitter/scoped-result-context-required?
                                             (or emitter/*keyword-context* *ns*) form))))
            capture-references (when scoped? (:aguafria/scoped-captures (meta form)))
            captures (when scoped? (mapv first capture-references))
            capture-contracts (scoped-capture-contracts capture-references source-parameters)
            source-literal? (and function
                                 ((requiring-resolve 'aguafria.zig.jvm/source-literal-call?)
                                  function (rest form)))
            constructor? (or (container-kinds (get-in var-meta [:aguafria/declaration :kind]))
                             (get-in var-meta [:aguafria/zig-reference :type-reference?])
                             (= :container (get-in var-meta [:aguafria/zig-reference :category]))
                             (= :primitive (:kind syntax))
                             (= "@as" (:zig-name syntax))
                             (contains? #{'aguafria.zig/array 'aguafria.zig/vector
                                          'aguafria.zig/init} function))
            declared-constructor? (or (container-kinds (get-in var-meta [:aguafria/declaration :kind]))
                                      (get-in var-meta [:aguafria/zig-reference :type-reference?])
                                      (= :container (get-in var-meta [:aguafria/zig-reference :category])))
            conversion? (or (= :primitive (:kind syntax)) (= "@as" (:zig-name syntax)))
            address? (= "&" (:zig-token syntax))
            address-reference (when (and address? (symbol? (second form)))
                                (let [metadata (some-> (ns-resolve (or emitter/*keyword-context* *ns*)
                                                                   (second form)) meta)]
                                  (when (contains? #{:fn :fn-proto}
                                                   (get-in metadata [:aguafria/declaration :kind]))
                                    (qualified-name metadata))))
            operator? (and (= :operator (:kind syntax))
                           (not (contains? #{"&" "*" ".." "..."} (:zig-token syntax))))
            operator? (or operator? (and (= :operator (:kind syntax))
                                         (= "*" (:zig-token syntax)) (> (count form) 2)))
            imported? (and (:aguafria/zig-reference var-meta)
                           (not (:aguafria/declaration var-meta)))
            declaration (:aguafria/declaration var-meta)
            native-function? (or (contains? #{:fn :fn-proto} (:kind declaration))
                                 (= :function (get-in var-meta [:aguafria/zig-reference :category])))
            concrete-function? (and (contains? #{:fn :fn-proto} (:kind declaration))
                                    (not-any? runtime/generic-function-argument?
                                              (:args declaration)))
            noreturn? (or (= :noreturn (:return declaration))
                          (some-> (or (:signature syntax)
                                      (get-in var-meta [:aguafria/zig-reference :signature]))
                                  (str/ends-with? ") noreturn")))
            candidate? (and function (or method-call? syntax imported? declaration (:aguafria/syntax var-meta)
                                         (= :deref placement)))]
        (if-not candidate?
          source
          (let [id (str (count @operations))
              ;; Only value operations can be transparently wrapped. Lvalues,
              ;; control flow and result-location-dependent builtins require
              ;; dedicated inspection placement, not a guessed type.
                contextual-plan (when conversion? (contextual-call-plan (second form)))
                contextual-scope (when conversion? (contextual-scope-plan (second form)))
              ;; Only an unconsumed deferred call needs this outer cast's
              ;; result location. A nested @as may already provide it. For
              ;; ordinary operands, query Zig for the operand type directly.
                contextual-input? (boolean contextual-plan)
                aggregate-construction? (and constructor? (not conversion?)
                                             (map? (second form))
                                             (not (literal-data? (second form))))
                deferred-plan (when (keyword/result-context-required? (:zig-name syntax))
                                (contextual-call-plan form))
                storage? (or address? (#{:field :index :slice :slice-sentinel :deref} placement))
                probe? (or scoped? type-expression? method-call? source-literal? assignment storage? constructor? operator? imported?
                           (= "try" (:zig-token syntax))
                           (= :const (:kind declaration))
                           (= 'aguafria.zig/unwrap function)
                           (contains? #{:fn :fn-proto} (:kind declaration))
                           (and (= :call (:kind syntax))
                                (not (contains? #{"@branchHint" "@compileError" "@compileLog"
                                                  "@setEvalBranchQuota" "@setRuntimeSafety"
                                                  "@setFloatMode" "@setCold" "@cVaStart"}
                                                (:zig-name syntax)))))
                expressions (when probe?
                              (cond
                                scoped?
                                (mapcat (fn [[_ reference]]
                                          [(render reference) (str "&(" (render reference) ")")])
                                        capture-references)
                                type-expression? [(render form)]
                                method-call?
                                (into [(render receiver)
                                       (str "(if (@TypeOf(" (render receiver)
                                            ") == type) {} else &(" (render receiver) "))")]
                                      (map render (rest form)))
                                source-literal? [(render form)]
                                (and assignment (= "=" (:zig-token syntax))
                                     (not (contains? #{:_ '_} (second form))))
                                [(render (second form))
                                 (str "@as(@TypeOf(" (render (second form)) "), "
                                      (render (nth form 2)) ")")]
                                (= :deref placement) [(render (second form))]
                                storage? (into [(render (second form))
                                                (if-let [receiver-type
                                                         (payload-projection-type render (second form))]
                                                  (str "&@as(" receiver-type ", undefined)")
                                                  (if (= :field placement)
                                                    (str "(if (@TypeOf(" (render (second form))
                                                         ") == type) {} else &(" (render (second form)) "))")
                                                    (str "&(" (render (second form)) ")")))]
                                               (case placement
                                                 :slice (map #(slice-index-probe render (second form) %) (drop 2 form))
                                                 :slice-sentinel
                                                 (concat (map #(slice-index-probe render (second form) %)
                                                              (take 2 (drop 2 form)))
                                                         [(render (last form))])
                                                 :index (map render (drop 2 form))
                                                 nil))
                                (and conversion? (not contextual-input?) (not contextual-scope))
                                [(render form) (render (second form))]
                                contextual-scope [(render form)]
                                contextual-plan
                                (into [(render form)] (map render (:leaves contextual-plan)))
                                aggregate-construction? [(render form) (render (second form))]
                                constructor? [(render form)]
                                deferred-plan (mapv render (:leaves deferred-plan))
                                :else
                                (mapv (fn [index argument]
                                        (let [builtin-parameters (when (and (:signature syntax)
                                                                            (not (str/includes? (:signature syntax) "...")))
                                                                   ((requiring-resolve 'aguafria.zig.signature/builtin-arguments)
                                                                    (:signature syntax)))
                                              expression (render (if (= :type (:type (nth builtin-parameters index nil)))
                                                                   (list 'aguafria.zig/type argument)
                                                                   argument))
                                              parameters (when native-function?
                                                           (str "@typeInfo(@TypeOf(" (render (first form))
                                                                ")).@\"fn\".param_types"))
                                              parameter (str parameters "[" index "]")]
                                          (if (and native-function?
                                                   (or (string? argument)
                                                       (needs-result-context? expression)))
                                          ;; Source strings use the concrete call parameter's ABI.
                                          ;; Other operands keep their own type unless a deferred
                                          ;; builtin needs that parameter's result location.
                                            (str "(if (" index " < " parameters ".len and "
                                                 parameter " != null) @as(" parameter
                                                 ".?, " expression ") else " expression ")")
                                            expression)))
                                      (range) (rest form))))
                parameters (cond
                             scoped? (mapcat (fn [_] [{:properties {:jvm/literal? true}} nil]) captures)
                             method-call?
                           ;; The JVM member planner embeds source strings. Keep
                           ;; that representation here, including comptime formats.
                             (concat [nil nil]
                                     (repeat (dec (count form))
                                             {:properties {:jvm/literal? true}}))
                             operator?
                             (repeat (dec (count form))
                                     {:properties (cond-> {:jvm/literal? true}
                                                    (= "++" (:zig-token syntax))
                                                    (assoc :jvm/comptime-slice? true))})
                             aggregate-construction?
                             [nil {:properties {:jvm/literal? true}}]
                             contextual-plan (into [nil] (:parameters contextual-plan))
                             deferred-plan (:parameters deferred-plan)
                             (and probe? (not (or method-call? source-literal? storage? constructor? assignment operator?)))
                             ((requiring-resolve 'aguafria.zig.jvm/call-parameters)
                              var-meta (dec (count form))))
                non-call-reason (cond
                                  (= 'aguafria.zig/container function) :type-declaration
                                  (= "@cVaStart" (:zig-name syntax)) :variadic-frame-required
                                  (contains? #{"@branchHint" "@compileError" "@compileLog"
                                               "@setEvalBranchQuota" "@setRuntimeSafety"
                                               "@setFloatMode" "@setCold"} (:zig-name syntax))
                                  :compiler-directive)
                constructor-literal (when constructor? (constructor-literal (second form)))
                operation (merge location
                                 {:id id :function function :form (artifact/print-data (or source-form form))
                                  :declaration-name declaration-name
                                  :declaration-kind declaration-kind
                                  :declared-constant-initializer? declared-constant-initializer?
                                  :root-declaration-name root-declaration-name
                                  :signature-position? signature-position?
                                  :scoped-form (when scoped?
                                                 (vary-meta (:aguafria/scoped-template (meta form))
                                                            assoc :aguafria/scoped-capture-contracts
                                                            capture-contracts))
                                  :scope-captures captures
                                  :scope-capture-contracts capture-contracts
                                  :scope-result? scope-result?
                                  :scope-result-context? scope-result-context?
                                  :scope-result-context-origin (when scope-result-context?
                                                                 result-context-origin)
                                  :scope-result-envelope (when (and scope-result-context?
                                                                    result-context-envelope)
                                                           (artifact/print-data result-context-envelope))
                                  :result-reader? (and (or operator? method-call?)
                                                       (not (needs-result-context? source)))
                                  :returns-type? (or (= :type (:return declaration))
                                                     (= :type-function (get-in var-meta [:aguafria/zig-reference :category])))
                                  :parameter-types (mapv :type parameters)
                                  :constructor? constructor?
                                  :literal-constructor? (boolean constructor-literal)
                                  :constructor-value (:value constructor-literal)
                                  :constructor-source (when constructor?
                                                        ((requiring-resolve 'aguafria.zig.jvm/qualify-native-type)
                                                         (or emitter/*keyword-context* *ns*)
                                                         (initializer-source form)))
                                  :conversion? conversion?
                                  :contextual-input? contextual-input?
                                  :contextual-plan (:plan contextual-plan)
                                  :contextual-scope (some-> contextual-scope (dissoc :references))
                                  :requires-result-context? (or (keyword/result-context-required? (:zig-name syntax))
                                                                (aggregate-address-form? form))
                                  :storage-kind (when storage? (if address? :address placement))
                                  :address-reference address-reference
                                  :method-call? method-call?
                                  :assignment assignment
                                  :literal-arguments (when source-literal? (vec (rest form)))
                                  :member (if method-call? member (when (= :field placement) (nth form 2)))
                                  :concrete-function? concrete-function?
                                  :argument-sources
                                  (when concrete-function?
                                    (mapv (fn [argument]
                                            (let [source (or (:aguafria/jvm-field-source (meta argument))
                                                             argument)]
                                              (or (native-function-source source)
                                                  (when (and (field-reference? source)
                                                             (not-any? #(contains? emitter/*lexical-bindings* %)
                                                                       (tree-seq coll? seq source)))
                                                    source))))
                                          (rest form)))
                                  :native-function? native-function?
                                  :jvm-type-source
                                  (jvm-anonymous-type-source
                                   (or emitter/*keyword-context* *ns*) (second form))
                                  :jvm-value-sources
                                  (let [context (or emitter/*keyword-context* *ns*)
                                        forms (cond scoped? (mapv second capture-references)
                                                    storage? [(second form)]
                                                    (not constructor?) (vec (rest form)))]
                                    (when (seq forms)
                                      (mapv #(or (jvm-value-type-source context %)
                                                 (when scoped?
                                                   (when-let [type (jvm-anonymous-type-source context %)]
                                                     {:type-source type :type-value? true :fields []
                                                      :mutable? false})))
                                            forms)))
                                  :status (if probe? :unobserved :unsupported)
                                  :reason (when-not probe? (or non-call-reason :inspection-placement))})]
            (swap! operations conj operation)
            (if-not (and probe? (or (nil? selected) (contains? selected id)))
              source
              (let [label (str "aguafria_operation_" id)
                    schemas (map (fn [index expression parameter argument-form]
                                   (if (or (and concrete-function? (contextual-call-plan argument-form))
                                           (and assignment (= "=" (:zig-token syntax))
                                                (= 1 index)
                                                (not (contains? #{:_ '_} (second form)))))
                                     (str "\"{:contextual-argument [\" ++ __aguafria_probe.schema(@TypeOf("
                                          expression ")) ++ \" \" ++ "
                                          (operand-schema render (render argument-form) parameter
                                                          argument-form "argumentSchema")
                                          " ++ \"]}\"")
                                     (operand-schema render expression parameter
                                                     (cond
                                                       concrete-function?
                                                       (or (native-function-source argument-form)
                                                           (when (aggregate-address-form? argument-form)
                                                             argument-form))
                                                       assignment
                                                       ;; Compound assignments can contain deferred calls.
                                                       (when (and (= 1 index)
                                                                  (contextual-call-plan argument-form))
                                                         argument-form)
                                                       :else argument-form)
                                                     (if (and (zero? index) (or assignment constructor? address?))
                                                       "schema" "argumentSchema"))))
                                 (range) expressions (concat parameters (repeat nil))
                                 (concat (when method-call? (concat [receiver nil] (rest form)))
                                         (when (and storage? (not method-call?))
                                           (case placement
                                             :field [(second form) nil]
                                             :index (concat [(second form) nil] (drop 2 form))
                                             :slice (concat [(second form) nil] (drop 2 form))
                                             :slice-sentinel (concat [(second form) nil] (drop 2 form))
                                             :deref [(second form)]
                                             nil))
                                         (when (and conversion? (not contextual-input?)) [nil (second form)])
                                         (when aggregate-construction? [nil (second form)])
                                         (when-not (or storage? constructor?) (rest form))
                                         (repeat nil)))
                    schemas (cond
                              declared-constructor?
                              ;; A self-initializer excludes its enclosing type
                              ;; from the catalog to avoid a probe cycle. The
                              ;; constructor still names that exact declaration;
                              ;; Zig verifies the identity without searching it.
                              (cons (str "(if (__aguafria_probe.requiresComptime(@TypeOf("
                                         (first expressions) "))) \"{:comptime-construction \" ++ "
                                         "__aguafria_probe.declaredSchema(@TypeOf("
                                         (first expressions) "), " (render (first form)) ", "
                                         (artifact/print-data (artifact/print-data function)) ") ++ \"}\" else "
                                         "__aguafria_probe.declaredSchema(@TypeOf("
                                         (first expressions) "), " (render (first form)) ", "
                                         (artifact/print-data (artifact/print-data function)) "))")
                                    (rest schemas))

                              (and constructor? (not conversion?))
                              (cons (str "(if (__aguafria_probe.requiresComptime(@TypeOf("
                                         (first expressions) "))) \"{:comptime-construction \" ++ "
                                         "__aguafria_probe.schema(@TypeOf(" (first expressions)
                                         ")) ++ \"}\" else " (first schemas) ")")
                                    (rest schemas))

                              (and conversion? (not contextual-input?) (not contextual-scope))
                              [(first schemas)
                               (str "(if (__aguafria_probe.integerCoercionRequiresConstant(@TypeOf("
                                    (first expressions) "), @TypeOf(" (second expressions) "))) "
                                    "__aguafria_probe.constantCoercion(" (second expressions) ") "
                                    "else " (second schemas) ")")]

                              scoped?
                              (concat
                               (scoped-capture-schemas render capture-references capture-contracts)
                               (when scope-result-context?
                                 (when-let [use! (:aguafria/use-peer-envelope! (meta result-context))]
                                   (use!))
                                 [(str "__aguafria_probe.schema("
                                       (emitter/emit-type result-context) ")")]))

                              contextual-scope
                              (concat [(str "__aguafria_probe.schema(@TypeOf(" (render form) "))")]
                                      (scoped-capture-schemas
                                       render (:references contextual-scope)
                                       (scoped-capture-contracts (:references contextual-scope)
                                                                 source-parameters)))

                              (= :index placement)
                              (map-indexed
                               (fn [index schema]
                                 (if (< index 2)
                                   schema
                                   (let [receiver (first expressions)
                                         expression (nth expressions index)]
                                     (str "(if (__aguafria_probe.indexRequiresComptime(@TypeOf(" receiver "))) "
                                          "__aguafria_probe.comptimeValue(@as(usize, " expression ")) else "
                                          schema ")"))))
                               schemas)

                              (= :slice-sentinel placement)
                              (concat (butlast schemas)
                                      [(str "__aguafria_probe.comptimeValue("
                                            (render (last form)) ")")])
                              :else schemas)
                    payload (str (artifact/print-data (str "aguafria.operation:" id ":["))
                                 (apply str (map #(str " ++ " % " ++ \" \"") schemas))
                                 " ++ \"]\"")
                    result-log (when (and native-function? (not concrete-function?)
                                          (not (:returns-type? operation)) (not noreturn?))
                                 (str "__aguafria_probe.log("
                                      (artifact/print-data (str "aguafria.result:" id ":"))
                                      " ++ __aguafria_probe.schema(@TypeOf(" (render-type-query render form) "))); "))
                    reader-log (when (:result-reader? operation)
                                 (str "__aguafria_probe.log("
                                      (artifact/print-data (str "aguafria.reader:" id ":"))
                                      " ++ __aguafria_probe."
                                      (if method-call? "runtimeSchema" "storageSchema")
                                      "(@TypeOf(" (render-type-query render form) "))); "))]
                (cond
                  defer-probe
                  (do (defer-probe (str "__aguafria_probe.log(" payload ");" reader-log)) source)

                  place-probe
                  (let [log (str "__aguafria_probe.log(" payload ");" reader-log)]
                    {:source (place-probe log label)
                     :place-probe (fn [outer-log outer-label]
                                    (place-probe (str log " " outer-log) outer-label))})
                  :else
                  (str "(" (when-not noreturn? (str label ": "))
                       "{ __aguafria_probe.log(" payload "); " result-log reader-log
                       (when-not noreturn?
                         (str "break :" label " "))
                       (if scope-result-context?
                         ;; This inspection block would otherwise sever the
                         ;; parent's peer/return destination at its break. The
                         ;; original native envelope supplies this exact type.
                         (str "@as(" (emitter/emit-type result-context) ", " source ")")
                         source)
                       "; })"))))))))))

(defn- root-declarations [declarations]
  (filterv (fn [{:keys [kind args jvm-adapter?]}]
             (and (not jvm-adapter?)
                  (or (contains? #{:const :var :struct :enum :union :opaque} kind)
                      (and (= :fn kind)
                           (not-any? #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                          (= "comptime" (get-in % [:properties :zig/prefix])))
                                     args)))))
           declarations))

(defn- source-specializations [declarations]
  (let [generic (into {}
                      (comp
                       (filter #(and (= :fn (:kind %)) (not (:jvm-adapter? %))
                                     (some (fn [{:keys [type properties]}]
                                             (or (#{:type 'type :anytype 'anytype} type)
                                                 (= "comptime" (:zig/prefix properties))))
                                           (:args %))))
                       (map (juxt :qualified-name identity)))
                      declarations)]
    (->> (runtime/inspection-callers (map :logical-id (vals generic)))
         (mapcat
          (fn [caller]
            (for [form (:body caller)
                  :when (and (seq? form) (contains? generic (first form)))
                  :let [declaration (generic (first form))
                        arguments (vec (rest form))]
                  :when (and (= (count arguments) (count (:args declaration)))
                             (every? #(or (literal-data? %)
                                          (and (seq? %) (= 'type (first %))
                                               (= 2 (count %))
                                               (literal-data? (second %))))
                                     arguments))]
              {:name (:name declaration)
               :module (:module declaration)
               :form (cons (:name declaration) arguments)
               :caller (:qualified-name caller)
               :source (:source caller)})))
         distinct
         vec)))

(defn- container-inspection-paths [context expression value]
  (when-let [{:keys [members]} (emitter/container-description context value)]
    (mapcat
     (fn [{:keys [kind name zig-name value]}]
       (when-let [member-name (or zig-name name)]
         (let [path (str expression "." (emitter/identifier member-name))]
           (if (= :fn kind)
             [path]
             (container-inspection-paths context path value)))))
     members)))

(defn- roots [declarations]
  ;; Referencing a container alone does not analyze its method bodies. Let Zig
  ;; enumerate concrete declarations recursively, preserving nominal identity
  ;; and leaving genuinely generic methods unspecialized. These roots are only
  ;; compiled with --test-no-exec; no constructor or method is invoked.
  (str "\nfn __aguafria_inspect_function(comptime function: anytype) void {\n"
       "    const info = @typeInfo(@TypeOf(function)).@\"fn\";\n"
       "    if (info.is_generic) return;\n"
       "    if (info.attrs.@\"callconv\" != .@\"inline\") { _ = &function; return; }\n"
       "    const Entry = struct {\n"
       "        fn call(args: @import(\"std\").meta.ArgsTuple(@TypeOf(function))) info.return_type.? {\n"
       "            return @call(.auto, function, args);\n"
       "        }\n"
       "    };\n"
       "    _ = &Entry.call;\n"
       "}\n"
       "\nfn __aguafria_inspect_declarations(comptime T: type, comptime visited: anytype) void {\n"
       "    inline for (visited) |Seen| if (Seen == T) return;\n"
       "    const declarations = switch (@typeInfo(T)) {\n"
       "        .@\"struct\" => |info| info.decl_names, .@\"enum\" => |info| info.decl_names,\n"
       "        .@\"union\" => |info| info.decl_names, .@\"opaque\" => |info| info.decl_names, else => return,\n"
       "    };\n"
       "    inline for (declarations) |declaration| {\n"
       "        const D = @field(T, declaration);\n"
       "        if (@TypeOf(D) == type) {\n"
       "            __aguafria_inspect_declarations(D, visited ++ .{T});\n"
       "        } else if (@typeInfo(@TypeOf(D)) == .@\"fn\" and !@typeInfo(@TypeOf(D)).@\"fn\".is_generic) {\n"
       "            __aguafria_inspect_function(@field(T, declaration));\n"
       "        }\n"
       "    }\n"
       "}\n"
       "\ntest \"aguafria inspection roots\" {\n"
       (apply str
              (for [{:keys [name zig-name module value] :as declaration} (root-declarations declarations)
                    :when (or (nil? *inspection-roots*) (*inspection-roots* name))]
                (let [reference (emitter/identifier (or zig-name name))
                      context (the-ns (symbol module))]
                  (str (if (= :fn (:kind declaration))
                         (str "    __aguafria_inspect_function(" reference ");\n")
                         (str "    _ = &" reference ";\n"))
                       (when (declared-type? declaration)
                         (str "    if (comptime @TypeOf(" reference ") == type) "
                              "__aguafria_inspect_declarations(" reference ", .{});\n"))
                       ;; Zig's reflected declaration list omits private members.
                       ;; The emitter supplies their paths; Zig decides whether
                       ;; each function is concrete before analyzing its body.
                       (apply str
                              (for [path (container-inspection-paths context reference value)]
                                (str "    __aguafria_inspect_function(" path ");\n")))))))
       (apply str
              (for [{:keys [name module form]} *inspection-specializations*
                    :when (or (nil? *inspection-roots*) (*inspection-roots* name))]
                (str "    _ = " (emitter/emit-expr (the-ns (symbol module)) form) ";\n")))
       "}\n"))

(defn- compiler-observations [stderr prefix]
  (let [log (second (str/split stderr #"Compile Log Output:\r?\n" 2))]
    (reduce (fn [found [_ encoded]]
              (let [decoded (String. (.parseHex (java.util.HexFormat/of) encoded)
                                     java.nio.charset.StandardCharsets/UTF_8)
                    [_ id schemas] (re-matches (re-pattern (str prefix ":([0-9]+):(.*)")) decoded)]
                (if id (update found id (fnil conj #{}) (edn/read-string schemas)) found)))
            {} (re-seq #"\"aguafria\.operation\.hex:([0-9a-f]+)\"" (or log "")))))

(defn- observations [stderr]
  (compiler-observations stderr "aguafria\\.operation"))

(defn- compiler-errors? [result]
  (boolean (re-find #"(?m)error: (?!found compile log statement)" (:err result))))

(defn- inspection-result [result]
  (assoc (select-keys result [:exit :command :source-path]) :diagnostics (:err result)))

(defn- inspect-roots! [module selected]
  (binding [*inspection-roots* selected]
    (runtime/inspect-module!
     module
     (fn [declarations]
       {:source (str (emitter/emit-module module declarations) (roots declarations))}))))

(defn- analyze-roots! [module]
  (let [baseline (inspect-roots! module nil)]
    (if (zero? (:exit baseline))
      {:baseline baseline :analysis-baseline baseline :attempts 1 :failures {}}
      (let [empty-result (inspect-roots! module #{})]
        (if-not (zero? (:exit empty-result))
          {:baseline baseline :analysis-baseline baseline :attempts 2 :failures {}}
          ;; Zig declarations are lazy. An invalid unused export must remain a
          ;; reported error, but must not prevent analysis of independent roots.
          (let [names (vec (distinct
                            (concat (map :name (root-declarations
                                                (runtime/registered-declarations module)))
                                    (map :name *inspection-specializations*))))
                failures (atom {})
                attempts (atom 2)]
            (letfn [(inspect [names]
                      (when (seq names)
                        (let [result (inspect-roots! module (set names))]
                          (swap! attempts inc)
                          (when-not (zero? (:exit result))
                            (if (= 1 (count names))
                              (swap! failures assoc (first names) (inspection-result result))
                              (let [[left right] (split-at (quot (count names) 2) names)]
                                (inspect left)
                                (inspect right)))))))]
              (let [[left right] (split-at (quot (count names) 2) names)]
                (inspect left)
                (inspect right)))
            (let [selected (into #{} (remove (set (keys @failures))) names)
                  verified (inspect-roots! module selected)]
              {:baseline baseline :analysis-baseline verified :selected selected
               :failures @failures :attempts (inc @attempts)})))))))

(defn- local-type-identities! [module]
  ;; Query the uninstrumented module. Asking about an initializer while its
  ;; operation probes are resolving the catalog would introduce a type cycle.
  (let [candidates (->> (runtime/registered-declarations module)
                        (filter #(and (= :const (:kind %))
                                      (not (contains? *rejected-inspection-roots* (:name %)))
                                      (not (:jvm-adapter? %))
                                      (not (declared-type? %))))
                        vec)
        result (when (seq candidates)
                 (runtime/inspect-module!
                  module
                  (fn [declarations]
                    {:source
                     (str (emitter/emit-module module declarations)
                          "\ncomptime {\n"
                          (apply str
                                 (for [[index {:keys [name zig-name]}] (map-indexed vector candidates)]
                                   (str "    if (@TypeOf(" (emitter/identifier (or zig-name name))
                                        ") == type) @compileLog(\"aguafria.local-type:" index "\");\n")))
                          "}\n")})))
        log (second (str/split (or (:err result) "") #"Compile Log Output:\r?\n" 2))]
    {:identities (set (map (fn [[_ index]] (:name (nth candidates (Long/parseLong index))))
                           (re-seq #"\"aguafria\.local-type:([0-9]+)\"" (or log ""))))
     :query (when result
              (assoc (select-keys result [:exit :command :source-path])
                     :diagnostics (:err result)
                     :compiler-errors? (compiler-errors? result)))}))

(defn- catalog-exclusions [module declarations initial]
  ;; A function signature or another alias may depend on an initializer that
  ;; currently contains a probe. Follow declaration references, not type guesses.
  (loop [excluded initial]
    (let [references (into excluded (map #(symbol (str module) (str %))) excluded)
          extended (into excluded
                         (keep (fn [{:keys [kind name args return value fields]}]
                                 (let [signature (if (contains? #{:fn :fn-proto} kind)
                                                   [return (map :type args)]
                                                   [value fields])]
                                   (when (some references (filter symbol? (tree-seq coll? seq signature)))
                                     name))))
                         declarations)]
      (if (= excluded extended) excluded (recur extended)))))

(defn- container-declaration-catalog
  [context expression identity value]
  (when-let [{:keys [members]} (emitter/container-description context value)]
    (mapcat
     (fn [{:keys [kind name zig-name value]}]
       (when-let [member-name (or zig-name name)]
         (let [member-source (str expression "." (emitter/identifier member-name))
               member-form (list 'aguafria.zig/field identity
                                 (if (symbol? member-name) (keyword (clojure.core/name member-name)) member-name))]
           (if (contains? #{:fn :fn-proto} kind)
             [[(str "@TypeOf(" member-source ")")
               (artifact/print-data (list 'aguafria.keyword/TypeOf member-form)) true]]
             (when (emitter/container-description context value)
               (cons [member-source (artifact/print-data member-form) false]
                     (container-declaration-catalog context member-source member-form value)))))))
     members)))

(defn- type-catalog [module declarations excluded]
  (let [excluded (catalog-exclusions module declarations
                                     (into excluded *rejected-inspection-roots*))
        context (the-ns (symbol (str module)))
        field-owners (into #{} (keep #(some-> % meta :aguafria/field-owner))
                           (tree-seq coll? seq declarations))
        local (mapcat
               (fn [{:keys [name zig-name kind value]}]
                 (let [reference (emitter/identifier (or zig-name name))
                       qualified (symbol (str module) (str name))
                       entry (if (contains? #{:fn :fn-proto} kind)
                               [(str "@TypeOf(" reference ")")
                                (artifact/print-data (list 'aguafria.keyword/TypeOf qualified)) true]
                               [reference (str qualified) false])]
                   ;; Nested method signatures can contain anonymous parameter
                   ;; types. Their declaration paths are known; Zig's @TypeOf
                   ;; and parameter reflection establish the type identities.
                   (cons entry (container-declaration-catalog
                                context reference qualified value))))
               (for [{:keys [name kind] :as declaration} declarations
                     :when (and (not (contains? excluded name))
                                (or (declared-type? declaration)
                                    (contains? *local-type-identities* name)
                                    (contains? #{:fn :fn-proto} kind)))]
                 declaration))
        imported (for [symbol (sort-by str
                                       (into field-owners (filter qualified-symbol?)
                                             (tree-seq coll? seq declarations)))
                       :let [v (some-> (find-ns (clojure.core/symbol (namespace symbol)))
                                       (ns-resolve (clojure.core/symbol (name symbol))))
                             v (or v (when (contains? field-owners symbol)
                                       (requiring-resolve symbol)))
                             reference (:aguafria/zig-reference (meta v))]
                       :when (and (:zig-name reference) (not= (str module) (namespace symbol)))]
                   (let [expression (emitter/emit-expr (the-ns (clojure.core/symbol (str module)))
                                                       symbol)]
                     [(str "if (@TypeOf(" expression ") == type) " expression
                           " else @TypeOf(" expression ")")
                      {:source (str "if (@TypeOf(" expression ") == type) "
                                    (artifact/print-data (str symbol)) " else "
                                    (artifact/print-data (artifact/print-data (list 'aguafria.keyword/TypeOf symbol))))}
                      (or (contains? #{:fn :fn-proto} (get-in (meta v) [:aguafria/declaration :kind]))
                          (contains? #{:function :type-function} (:category reference)))]))]
    ;; Resolve candidates lazily: a function signature can itself contain a
    ;; probe. Eagerly typing the whole catalog would make that probe depend on
    ;; the signature it is inspecting. Zig still performs every type comparison.
    (str/join ", "
              (map (fn [[expression name function-root?]]
                     (str "struct { pub const function_root = " (boolean function-root?) "; "
                          "pub fn get() type { return " expression "; } "
                          "pub fn name() []const u8 { return "
                          (if (map? name) (:source name) (artifact/print-data name)) "; } }"))
                   (concat local imported
                           [["@import(\"std\").lang.Type"
                             "(aguafria.keyword/TypeOf (aguafria.keyword/typeInfo :u8))" false]]
                           (for [expression *observed-type-identities*]
                             [(let [context (the-ns (symbol (str module)))]
                                (emitter/emit-expr context
                                                   (emitter/qualify-form context expression)))
                              (artifact/print-data expression)]))))))

(defn- observed-type-identities [result]
  ;; Reuse actual type-function calls with compiler-observed constant operands.
  ;; The next inspection asks Zig whether a result type equals that expression;
  ;; no anonymous container is replaced with a guessed structural equivalent.
  (vec
   (distinct
    (for [{:keys [id function returns-type? parameter-types]} (:operations result)
          :when returns-type?
          signature (get (:observed result) id)
          :when (every?
                 (fn [[argument parameter-type]]
                   (and (map? argument)
                        (or (contains? argument :comptime-type)
                            (contains? argument :literal)
                            ;; A raw constant lacks its original sized type.
                            ;; Only reuse it when the real parameter declares
                            ;; the concrete type, never for an anytype input.
                            (and (contains? argument :comptime)
                                 (or (vector? parameter-type)
                                     (and (keyword? parameter-type)
                                          (not (#{:anytype :type} parameter-type))))))
                        (not-any? nil? (tree-seq coll? seq argument))))
                 (map vector signature (concat parameter-types (repeat nil))))]
      (apply list function
             (map (fn [argument]
                    (cond
                      (contains? argument :comptime-type) (:comptime-type argument)
                      (contains? argument :comptime) (:comptime argument)
                      :else (:literal argument))) signature))))))

(declare preparation-signatures)

(defn- observed-call-result-identities [result]
  (vec
   (distinct
    (for [{:keys [id function native-function? concrete-function? returns-type?]}
          (:operations result)
          :when (and native-function? (not concrete-function?) (not returns-type?))
          :when (some #(some nil? (tree-seq coll? seq %)) (get (:result-schemas result) id))
          signature (:signatures (preparation-signatures (get (:observed result) id)))
          :when (not-any? nil? (tree-seq coll? seq signature))]
      ((requiring-resolve 'aguafria.zig.jvm/call-result-identity) function signature)))))

(defn- inspection-declarations [module declarations]
  (let [context (the-ns (symbol (str module)))
        expressions (mapv #(emitter/qualify-form context %) *observed-type-identities*)
        existing (emitter/declaration-imports declarations)
        imports (emitter/declaration-imports [{:body expressions}])]
    (into (vec declarations)
          (for [[alias {:keys [import-name]}] imports
                :when (not (contains? existing alias))]
            {:kind :import :module (str module) :name (symbol alias) :zig-name alias
             :import-name import-name :emit-source-comment? false}))))

(defn- inspect-operations! [module selected]
  (let [operations (atom [])
        result (runtime/inspect-module!
                module
                (fn [declarations]
                  (let [declarations (inspection-declarations module declarations)
                        source (binding [emitter/*expression-observer* (observer operations selected)]
                                 (emitter/emit-module module declarations))
                        ;; Initializers and function signatures cannot resolve
                        ;; their enclosing declaration through its own catalog
                        ;; entry. Other declarations still establish its identity.
                        excluded (into #{}
                                       (keep (fn [{:keys [id status declaration-name declaration-kind
                                                          root-declaration-name signature-position?]}]
                                               (when (and (= :unobserved status)
                                                          (or (= :const declaration-kind)
                                                              (and signature-position?
                                                                   (contains? #{:fn :fn-proto} declaration-kind)))
                                                          (or (nil? selected) (contains? selected id)))
                                                 (or root-declaration-name declaration-name))))
                                       @operations)]
                    {:source (str "const __aguafria_probe = @import(\"operation_probe.zig\").Inspector(.{"
                                  (type-catalog module declarations excluded)
                                  "});\n" source (roots declarations))
                     :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                             "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}})))]
    (assoc result :operations @operations :observed (observations (:err result))
           :result-schemas (compiler-observations (:err result) "aguafria\\.result")
           :reader-schemas (compiler-observations (:err result) "aguafria\\.reader"))))

(defn- isolate-probes! [module initial]
  ;; Compile smaller probe groups only when inspection changed a valid module
  ;; into an invalid one. Keep successful compiler observations, and retain the
  ;; exact diagnostic for each failed singleton. No source/type guessing.
  (let [observed (atom (:observed initial))
        confirmed (atom (when-not (compiler-errors? initial) (:observed initial)))
        readers (atom (when-not (compiler-errors? initial) (:reader-schemas initial)))
        failures (atom {})
        attempts (atom 1)]
    (letfn [(inspect [ids]
              (let [result (inspect-operations! module (set ids))]
                (swap! attempts inc)
                (swap! observed #(merge-with into % (:observed result)))
                (when-not (compiler-errors? result)
                  (swap! confirmed #(merge-with into % (:observed result)))
                  (swap! readers #(merge-with into % (:reader-schemas result))))
                (when (compiler-errors? result)
                  (if (= 1 (count ids))
                    (swap! failures assoc (first ids)
                           {:diagnostics (:err result) :command (:command result)
                            :source-path (:source-path result)})
                    (let [[left right] (split-at (quot (count ids) 2) ids)]
                      (inspect left)
                      (inspect right))))))]
      (let [ids (mapv :id (filter #(= :unobserved (:status %)) (:operations initial)))]
        (when (seq ids)
          (if (= 1 (count ids))
            (inspect ids)
            (let [[left right] (split-at (quot (count ids) 2) ids)]
              (inspect left)
              (inspect right)))))
      {:observed @observed :confirmed-observed @confirmed :reader-schemas @readers
       :failures @failures :attempts @attempts})))

(defn- refine-type-identities! [module result]
  ;; Alias-initializer probes temporarily exclude their dependents from the
  ;; catalog. Query unresolved signatures without the other probes so function
  ;; bodies can use those aliases. Only a clean compiler pass may replace them.
  (let [incomplete (into #{}
                         (keep (fn [[id signatures]]
                                 (when (some nil? (tree-seq coll? seq signatures)) id)))
                         (:observed result))]
    (when (seq incomplete)
      (let [refined (inspect-operations! module incomplete)
            rejected? (compiler-errors? refined)
            isolated (when rejected?
                       (isolate-probes!
                        module
                        (update refined :operations
                                #(filterv (comp incomplete :id) %))))
            observed (if rejected? (:confirmed-observed isolated) (:observed refined))]
        {:observed (into {}
                         (filter (fn [[_ signatures]]
                                   (not-any? nil? (tree-seq coll? seq signatures))))
                         observed)
         :reader-schemas (if rejected? (:reader-schemas isolated) (:reader-schemas refined))
         :inspection-attempts (or (:attempts isolated) 1)
         :probe-failures (:failures isolated)
         :compiler-errors? (compiler-errors? refined)
         :source-path (:source-path refined)
         :diagnostics (:err refined)}))))

(defn analyze!
  "Inventory observable emitted operations in a registered namespace. A missing compiler
  observation stays unresolved; unsupported placements are reported explicitly.
  Native test/function bodies are compiled, never executed."
  [module]
  (binding [runtime/*compile-only?* true]
    (runtime/call-with-inspection-context
     module
     (fn []
       (binding [*inspection-specializations*
                 (source-specializations (runtime/registered-declarations module))]
         (let [{:keys [baseline analysis-baseline selected failures attempts]}
               (analyze-roots! module)]
           (binding [*inspection-roots* selected
                     *rejected-inspection-roots* (set (keys failures))]
             (let [local-types (local-type-identities! module)]
               (binding [*local-type-identities* (:identities local-types)]
                 (let [initial (inspect-operations! module nil)
                       type-identities (observed-type-identities initial)
                       call-result-identities (observed-call-result-identities initial)
                       identities (into type-identities call-result-identities)
                       result (if (seq identities)
                                (binding [*observed-type-identities* identities]
                                  (inspect-operations! module nil))
                                initial)
                       probes (if (and (zero? (:exit analysis-baseline))
                                       (compiler-errors? result))
                                (binding [*observed-type-identities* identities]
                                  (isolate-probes! module result))
                                {:observed (:observed result)
                                 :reader-schemas (when-not (compiler-errors? result) (:reader-schemas result))
                                 :failures {} :attempts 1})
                       refinement (binding [*observed-type-identities* identities]
                                    (refine-type-identities!
                                     module {:observed (:observed probes)}))
                       observed (merge (:observed probes) (:observed refinement))
                       reader-schemas (merge-with into (:reader-schemas probes)
                                                  (:reader-schemas refinement))]
                   {:namespace (symbol (str module))
                    :basis :zig-compiler
                    :inspection-specializations *inspection-specializations*
                    :local-type-identities (:identities local-types)
                    :local-type-query (:query local-types)
                    :identity-refinement (some-> refinement (dissoc :observed))
                    :type-identities type-identities
                    :call-result-identities call-result-identities
                    :compiler-mode :test
                    :baseline (inspection-result baseline)
                    :analysis-baseline (inspection-result analysis-baseline)
                    :root-failures failures
                    :root-inspection-attempts attempts
                    :command (:command result)
                    :source-path (:source-path result)
                    :diagnostics (:err result)
                    :compiler-errors? (compiler-errors? result)
                    :inspection-attempts (+ (:attempts probes)
                                            (if (seq identities) 1 0)
                                            (or (:inspection-attempts refinement) 0))
                    :probe-failures (:failures probes)
                    :operations
                    (mapv (fn [operation]
                            (if-let [types (get observed (:id operation))]
                              (-> operation
                                  (assoc :status :observed
                                         :signatures (vec (sort-by artifact/print-data types))
                                         :result-reader-types
                                         (vec (sort-by artifact/print-data
                                                       (remove nil? (get reader-schemas (:id operation))))))
                                  (dissoc :reason))
                              (cond
                                (contains? failures (:declaration-name operation))
                                (assoc operation :status :inspection-failed
                                       :reason :compiler-rejected-root)

                                (contains? (:failures probes) (:id operation))
                                (assoc operation :status :inspection-failed
                                       :reason :compiler-rejected-probe)

                                :else operation)))
                          (:operations result))}))))))))))

(defn- structural-type? [schema]
  (and (or (keyword? schema) (vector? schema) (qualified-symbol? schema)
           (and (seq? schema) (qualified-symbol? (first schema))))
       (not-any? nil? (tree-seq coll? seq schema))
       (not-any? #{:type :null :undefined :comptime_int :comptime_float}
                 (tree-seq coll? seq schema))))

(defn error-report
  "Retain the primary exception and native diagnostics through compiler wrappers."
  [error]
  (let [causes (take-while some? (iterate ex-cause error))
        data (some #(when (:stderr (ex-data %)) (ex-data %)) causes)]
    (merge {:message (ex-message error)
            :causes (mapv ex-message causes)}
           (select-keys data [:stderr :command :source-path]))))

(defn- supported-argument? [argument]
  (or (structural-type? argument)
      (#{:comptime_float :null :undefined} argument)
      (and (map? argument)
           (or (and (= #{:comptime-capture} (set (keys argument)))
                    (= 2 (count (:comptime-capture argument)))
                    (or (structural-type? (first (:comptime-capture argument)))
                        (contains? #{:type :comptime_int :comptime_float}
                                   (first (:comptime-capture argument))))
                    (supported-argument? (second (:comptime-capture argument))))
               (and (#{:comptime_int :comptime_float} (:type argument))
                    (number? (:literal argument)))
               (and (= #{:constant-coercion} (set (keys argument)))
                    (= 2 (count (:constant-coercion argument)))
                    (structural-type? (first (:constant-coercion argument)))
                    (supported-argument?
                     {:comptime-expression (second (:constant-coercion argument))}))
               (and (= #{:comptime} (set (keys argument)))
                    (or (string? (:comptime argument))
                        (number? (:comptime argument))
                        (boolean? (:comptime argument))
                        (and (keyword? (:comptime argument))
                             (nil? (namespace (:comptime argument)))
                             (str/starts-with? (name (:comptime argument)) "."))))
               (structural-type? (:comptime-type argument))
               (and (= #{:comptime-expression} (set (keys argument)))
                    (or (seq? (:comptime-expression argument))
                        (qualified-symbol? (:comptime-expression argument)))
                    (not-any? nil? (tree-seq coll? seq (:comptime-expression argument))))
               (contains? #{:type :comptime_int :comptime_float} (:comptime-type argument))
               (and (= #{:tuple} (set (keys argument)))
                    (vector? (:tuple argument))
                    (every? supported-argument? (:tuple argument)))
               (and (= #{:map} (set (keys argument)))
                    (map? (:map argument))
                    (every? supported-argument? (vals (:map argument))))
               (and (= #{:contextual-argument} (set (keys argument)))
                    (= 2 (count (:contextual-argument argument)))
                    (structural-type? (first (:contextual-argument argument)))
                    (supported-argument? (second (:contextual-argument argument))))
               (and (= #{:contextual-address} (set (keys argument)))
                    (supported-argument? (:contextual-address argument)))
               (and (= #{:contextual-call} (set (keys argument)))
                    (qualified-symbol? (first (:contextual-call argument)))
                    (every? supported-argument? (second (:contextual-call argument))))))))

(declare signature-variants)

(defn- captured-source [schema]
  (cond
    (and (map? schema) (contains? schema :literal)) [true (:literal schema)]
    (and (map? schema) (contains? schema :comptime)) [true (:comptime schema)]
    (:constant-coercion schema) [true (second (:constant-coercion schema))]
    (:comptime-expression schema) [true (:comptime-expression schema)]
    (and (:comptime-type schema) (structural-type? (:comptime-type schema)))
    [true (list 'aguafria.zig/type (:comptime-type schema))]
    (= :null schema) [true nil]
    :else [false nil]))

(defn- argument-variants [argument]
  (cond
    (and (map? argument) (= #{:comptime-template} (set (keys argument))))
    (let [[source captures] (:comptime-template argument)
          bindings (mapv (fn [[name schema]] [name (captured-source schema)]) captures)]
      (if (every? (comp first second) bindings)
        [{:comptime-expression
          (walk/postwalk-replace (into {} (map (fn [[name [_ value]]] [name value])) bindings)
                                 source)}]
        [argument]))

    (and (map? argument) (= #{:representations} (set (keys argument))))
    (mapcat argument-variants (:representations argument))

    (and (map? argument) (= #{:tuple} (set (keys argument))))
    (map #(hash-map :tuple %) (signature-variants (:tuple argument)))

    (and (map? argument) (= #{:map} (set (keys argument))))
    (map #(hash-map :map (zipmap (keys (:map argument)) %))
         (signature-variants (vals (:map argument))))

    (and (map? argument) (= #{:contextual-argument} (set (keys argument))))
    (map #(hash-map :contextual-argument [(first (:contextual-argument argument)) %])
         (argument-variants (second (:contextual-argument argument))))

    (and (map? argument) (= #{:contextual-call} (set (keys argument))))
    (map #(hash-map :contextual-call [(first (:contextual-call argument)) %])
         (signature-variants (second (:contextual-call argument))))

    (and (map? argument) (= #{:contextual-address} (set (keys argument))))
    (map #(hash-map :contextual-address %)
         (argument-variants (:contextual-address argument)))

    :else [argument]))

(defn- signature-variants [arguments]
  (reduce (fn [prefixes argument]
            (for [prefix prefixes
                  variant (argument-variants argument)]
              (conj prefix variant)))
          [[]] arguments))

(def ^:private max-representation-variants 256)

(defn- preparation-signatures [signatures]
  ;; A compiler-known constant may be supplied from the JVM as either an
  ;; ordinary literal or an explicit native handle. Keep both identities.
  ;; Bound combinatorial work; omitted variants remain an explicit gap.
  (let [variants (vec (take (inc max-representation-variants)
                            (distinct (mapcat signature-variants signatures))))]
    {:signatures (vec (take max-representation-variants variants))
     :limited? (> (count variants) max-representation-variants)}))

(defn- prepare-declared-functions! [module]
  (mapv (fn [{:keys [name kind]}]
          (let [function (symbol (str module) (str name))]
            (if (= :test kind)
              {:function function :status :skipped :reason :test-runner}
              (try
                (runtime/precompile-function! function)
                (catch Exception error
                  (assoc (error-report error) :function function :status :failed))))))
        (->> (runtime/registered-declarations module)
             (remove :jvm-adapter?)
             (filter #(contains? #{:fn :fn-proto :test} (:kind %)))
             (sort-by (comp str :name)))))

(defn- prepare-constant-readers! [module rejected-roots]
  (let [prepare (requiring-resolve 'aguafria.zig.jvm/precompile-constant-reader!)]
    (mapv (fn [{:keys [name] :as declaration}]
            (let [constant (symbol (str module) (str name))]
              (if (contains? rejected-roots name)
                {:constant constant :status :skipped :reason :compiler-rejected-root}
                (try
                  (prepare declaration)
                  (catch Exception error
                    (assoc (error-report error) :constant constant :status :failed))))))
          (->> (runtime/registered-declarations module)
               (remove :jvm-adapter?)
               (filter #(= :const (:kind %)))
               (sort-by (comp str :name))))))

(defn- prepare-in-observed-context [declaration-kind prepare]
  ;; JVM callers normally use build-lib. Only a compiler rejection inside an
  ;; authored test warrants testing the same adapter in Zig's test environment.
  (try
    (assoc (prepare) :execution-context
           (if runtime/*native-test-context?* :test :runtime))
    (catch Exception error
      (if (and (= :test declaration-kind)
               (not runtime/*native-test-context?*)
               (some #(= :zig-compile (:aguafria/phase (ex-data %)))
                     (take-while some? (iterate ex-cause error))))
        (binding [runtime/*native-test-context?* true]
          (assoc (prepare) :execution-context :test))
        (throw error)))))

(defn- retain-declared-initializer-owner [module readers operation]
  (if (and (= :observed (:status operation))
           (:declared-constant-initializer? operation))
    (let [constant (symbol (str module) (str (:root-declaration-name operation)))
          reader (or (get readers constant)
                     {:constant constant :status :failed :reason :unprepared-constant-owner})]
      (assoc operation
             :enclosing-context :comptime
             :execution-plan :declared-constant
             :independent-call? false
             :independent-call-handlers (:handlers operation)
             :handlers [(assoc reader :execution-context :comptime)]))
    operation))

(defn- refine-jvm-map-representations!
  "Reflect unknown native field receivers as independently supplied JVM maps.
  Query the actual lexical receiver, never a reconstructed anonymous type.
  Native observations stay intact; map field adapters do not borrow that
  receiver's storage or claim its nominal identity."
  [report]
  (let [candidates (filterv
                    (fn [{:keys [status signatures storage-kind method-call? jvm-value-sources]}]
                      (and (= :observed status) (= :field storage-kind)
                           (not method-call?) (seq signatures)
                           (not-any? identity jvm-value-sources)
                           (every? #(nil? (first %)) signatures)))
                    (:operations report))]
    (when (seq candidates)
      (let [ids (set (map :id candidates))
            operations (atom [])
            inventory (observer operations #{})
            result
            (binding [*inspection-specializations* (:inspection-specializations report)
                      *local-type-identities* (:local-type-identities report)
                      *observed-type-identities* (vec (concat (:type-identities report)
                                                              (:call-result-identities report)))
                      *rejected-inspection-roots* (set (keys (:root-failures report)))]
              (runtime/inspect-module!
               (:namespace report)
               (fn [declarations]
                 (let [declarations (inspection-declarations (:namespace report) declarations)
                       source
                       (binding [emitter/*expression-observer*
                                 (fn [{:keys [form render place-probe source] :as event}]
                                   (let [before (count @operations)
                                         unchanged (inventory event)
                                         operation (when (< before (count @operations)) (peek @operations))]
                                     (if (and (ids (:id operation)) place-probe)
                                       {:source
                                        (place-probe
                                         (str "__aguafria_probe.log("
                                              (artifact/print-data
                                               (str "aguafria.jvm-map:" (:id operation) ":"))
                                              " ++ __aguafria_probe.jvmMapArgumentSchema(@TypeOf("
                                              (render (second form)) "))); ")
                                         (str "aguafria_jvm_map_" (:id operation)))}
                                       unchanged)))]
                         (emitter/emit-module (str (:namespace report)) declarations))]
                   {:source (str "const __aguafria_probe = @import(\"operation_probe.zig\").Inspector(.{"
                                 (type-catalog (:namespace report) declarations #{})
                                 "});\n" source (roots declarations))
                    :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                            "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}}))))
            schemas (when-not (compiler-errors? result)
                      (compiler-observations (:err result) "aguafria\\.jvm-map"))
            observed (into {}
                           (for [{:keys [id signatures]} candidates
                                 :let [maps (filter #(and (map? %) (contains? % :map)) (get schemas id))]
                                 :when (seq maps)]
                             [id (set (for [schema maps signature signatures]
                                        (assoc signature 0 schema)))]))]
        {:basis :zig-compiler :representation :ordinary-jvm-map
         :nominal-equivalence? false :native-storage? false
         :sources (into {} (map (juxt :id #(select-keys % [:form :declaration-name :signatures]))) candidates)
         :observed observed :compiler-errors? (compiler-errors? result)
         :command (:command result) :source-path (:source-path result)
         :diagnostics (:err result)}))))

(defn- refine-jvm-type-representations!
  "Query ordinary JVM container descriptors separately from the native body.
  A source descriptor is a JVM representation, never an identity assertion
  about the original test/function-local Zig container."
  [report]
  (let [candidates (filterv
                    (fn [{:keys [jvm-type-source signatures]}]
                      (and jvm-type-source (seq signatures)
                           (every? #(and (= {:comptime-type nil} (first %))
                                         (not-any? nil? (tree-seq coll? seq (rest %))))
                                   signatures)))
                    (:operations report))]
    (when (seq candidates)
      (let [plan-type (requiring-resolve 'aguafria.zig.jvm/anonymous-type-plan)
            register-type (requiring-resolve 'aguafria.zig.jvm/register-anonymous-type-plan!)
            plans (atom {})
            register (fn register [{:keys [caller container locals] :as source}]
                       (or (get @plans source)
                           (let [locals (into {} (map (fn [[name source]] [name (register source)])) locals)
                                 plan (plan-type caller container locals)]
                             (register-type plan)
                             (swap! plans assoc source (:type plan))
                             (:type plan))))
            types (binding [runtime/*source-only-registration?* true]
                    (into {} (map (fn [{:keys [id jvm-type-source]}]
                                    [id (register jvm-type-source)])) candidates))
            module (symbol (str "aguafria.jvm.container-inspection-"
                                (subs (runtime/adapter-fingerprint [(:namespace report) types]) 0 24)))
            context (or (find-ns module) (create-ns module))
            declarations
            (binding [runtime/*source-only-registration?* true]
              (mapv (fn [[id type]]
                      (let [descriptor (emitter/prepare-declaration
                                        context {:kind :const :name (symbol (str "Type_" id))
                                                 :declaration-key [:const (symbol (str "Type_" id))]
                                                 :module (str module) :public? true
                                                 :value (list 'aguafria.zig/type type)})]
                        (runtime/register-declaration! descriptor)
                        descriptor))
                    (sort-by key types)))
            reader-declarations
            (binding [runtime/*source-only-registration?* true]
              (into []
                    (keep (fn [{:keys [id function storage-kind signatures]}]
                          ;; This closed unary call has no runtime operands. Ask
                          ;; Zig about the exact JVM descriptor's actual result;
                          ;; do not reuse an original lexical type's reader.
                            (when (and (nil? storage-kind)
                                       (= 1 (count (first signatures)))
                                       (:aguafria/token (meta (find-var function))))
                              (let [name (symbol (str "Result_" id))
                                    descriptor (emitter/prepare-declaration
                                                context {:kind :const :name name
                                                         :declaration-key [:const name]
                                                         :module (str module) :public? true
                                                         :value (list function
                                                                      (list 'aguafria.zig/type (get types id)))})]
                                (runtime/register-declaration! descriptor)
                                descriptor))))
                    candidates))
            result
            (runtime/inspect-module!
             module
             (fn [_]
               (let [catalog (str/join
                              ", "
                              (for [{:keys [name]} declarations
                                    :let [id (subs (str name) 5) type (get types id)]]
                                (str "struct { pub const function_root = false; "
                                     "pub fn get() type { return " (emitter/identifier name) "; } "
                                     "pub fn name() []const u8 { return "
                                     (artifact/print-data (artifact/print-data type)) "; } }")))]
                 {:source (str "const __aguafria_probe = @import(\"operation_probe.zig\").Inspector(.{"
                               catalog "});\n"
                               ;; Inspection dependencies keep their original
                               ;; public aliases; only builtin emission uses the
                               ;; ordinary helper, shared verbatim with demand.
                               (emitter/logical-type-name-helper-source) "\n"
                               (binding [emitter/*logical-type-names?* true]
                                 (emitter/emit-module module (concat declarations reader-declarations)))
                               "\ncomptime {\n"
                               (apply str
                                      (for [{:keys [name]} declarations
                                            :let [id (subs (str name) 5)]]
                                        (str "    __aguafria_probe.log("
                                             (artifact/print-data (str "aguafria.jvm-type:" id ":"))
                                             " ++ __aguafria_probe.schema(" (emitter/identifier name) "));\n")))
                               (apply str
                                      (for [{:keys [name]} reader-declarations
                                            :let [id (subs (str name) 7)]]
                                        (str "    __aguafria_probe.log("
                                             (artifact/print-data (str "aguafria.jvm-reader:" id ":"))
                                             " ++ __aguafria_probe.schema(@TypeOf(" (emitter/identifier name) ")));\n")))
                               "}\n")
                  :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                          "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}})))
            observed (when-not (compiler-errors? result)
                       (compiler-observations (:err result) "aguafria\\.jvm-type"))
            readers (when-not (compiler-errors? result)
                      (compiler-observations (:err result) "aguafria\\.jvm-reader"))]
        {:basis :zig-compiler :representation :ordinary-jvm
         :nominal-equivalence? false :types types
         :observed observed :reader-schemas readers :plans @plans :module module
         :compiler-errors? (compiler-errors? result)
         :command (:command result) :source-path (:source-path result)
         :diagnostics (:err result)}))))

(defn- refine-jvm-value-representations!
  "Query explicit ordinary JVM value/capture representations in a separate
  compiler root. Native observations and native lexical identities stay intact."
  [report]
  (let [candidates (filterv
                    (fn [{:keys [signatures jvm-value-sources storage-kind scoped-form scope-result-context?]}]
                      (and (= 1 (count signatures)) (some identity jvm-value-sources)
                           (some #(some nil? (tree-seq coll? seq %)) signatures)
                           (cond
                             scoped-form (and (every? identity jvm-value-sources)
                                              (not (and scope-result-context?
                                                        (some #(some nil? (tree-seq coll? seq (peek %))) signatures))))
                             storage-kind (some? (first jvm-value-sources))
                             :else (every? (fn [signature]
                                             (and (= (count signature) (count jvm-value-sources))
                                                  (every? (fn [[type source]]
                                                            (or source (not-any? nil? (tree-seq coll? seq type))))
                                                          (map vector signature jvm-value-sources))))
                                           signatures))))
                    (:operations report))]
    (when (seq candidates)
      (let [plan-type (requiring-resolve 'aguafria.zig.jvm/anonymous-type-plan)
            register-type (requiring-resolve 'aguafria.zig.jvm/register-anonymous-type-plan!)
            plans (atom {})
            register (fn register [{:keys [caller container locals] :as source}]
                       (or (get @plans source)
                           (let [locals (into {} (map (fn [[name source]] [name (register source)])) locals)
                                 plan (plan-type caller container locals)]
                             (register-type plan)
                             (swap! plans assoc source (:type plan))
                             (:type plan))))
            sources (binding [runtime/*source-only-registration?* true]
                      (into {}
                            (for [{:keys [id jvm-value-sources]} candidates]
                              [id (mapv #(when % (assoc % :root-type (register (:type-source %))))
                                        jvm-value-sources)])))
            module (symbol (str "aguafria.jvm.value-inspection-"
                                (subs (runtime/adapter-fingerprint [(:namespace report) sources]) 0 24)))
            context (or (find-ns module) (create-ns module))
            operands
            (into {}
                  (for [[id sources] sources [index source] (map-indexed vector sources) :when source
                        :let [{:keys [root-type fields capture-fields pointer? pointer-mutable?]} source
                              path (concat (when (seq capture-fields) [(first capture-fields)]) fields)
                              child (reduce (fn [type member]
                                              (list 'aguafria.keyword/FieldType type (name member)))
                                            root-type path)
                              root (symbol (str "root_" id "_" index))
                              query (when pointer?
                                      (list 'aguafria.keyword/TypeOf
                                            (list 'aguafria.zig/with-block :pointer_type
                                                  (list 'let [root (list (if pointer-mutable?
                                                                           'aguafria.keyword/var 'aguafria.keyword/as)
                                                                         'aguafria.keyword/undefined root-type)]
                                                        (list 'aguafria.keyword/break :pointer_type
                                                              (list 'aguafria.keyword/&
                                                                    (reduce (fn [value member]
                                                                              (list 'aguafria.zig/field value member))
                                                                            root path)))))))]]
                    [[id index] (assoc source :child-type child :type (or query child))]))
            declarations
            (binding [runtime/*source-only-registration?* true]
              (mapv (fn [[[id index] {:keys [type child-type]}]]
                      (let [child-name (symbol (str "Child_" id "_" index))
                            name (symbol (str "Value_" id "_" index))
                            descriptors
                            (mapv (fn [[name type]]
                                    (let [descriptor (emitter/prepare-declaration
                                                      context {:kind :const :name name :module (str module)
                                                               :declaration-key [:const name] :public? true
                                                               :value (list 'aguafria.zig/type type)})]
                                      (runtime/register-declaration! descriptor)
                                      descriptor))
                                  [[child-name child-type] [name type]])]
                        descriptors))
                    (sort-by key operands)))
            result
            (runtime/inspect-module!
             module
             (fn [_]
               {:source
                (str (emitter/emit-module module (mapcat identity declarations))
                     "\ncomptime {\n"
                     (apply str
                            (for [{:keys [id signatures storage-kind scoped-form scope-result-context?]} candidates
                                  :let [sources (get sources id)
                                        schema (fn [index address?]
                                                 (let [inspector (str "probe_" id "_" index)
                                                       value (str "value_" id "_" index)]
                                                   (if (and (not address?) (:type-value? (nth sources index)))
                                                     (str "\"{:comptime-type \" ++ " inspector ".schema(" value ") ++ \"}\"")
                                                     (str inspector ".schema(@TypeOf(" (when address? "&") value "))"))))
                                        slots (cond
                                                scoped-form (mapcat (fn [index] [(schema index false) (schema index true)])
                                                                    (range (count sources)))
                                                (= :deref storage-kind) [(schema 0 false)]
                                                storage-kind (concat [(schema 0 false) (schema 0 true)]
                                                                     (map #(artifact/print-data (artifact/print-data %))
                                                                          (drop 2 (first signatures))))
                                                :else (map-indexed (fn [index source]
                                                                     (if source (schema index false)
                                                                         (artifact/print-data
                                                                          (artifact/print-data (nth (first signatures) index)))))
                                                                   sources))
                                        slots (cond-> (vec slots)
                                                scope-result-context?
                                                (conj (artifact/print-data (artifact/print-data (peek (first signatures))))))]]
                              (str "    {\n"
                                   (apply str
                                          (for [[index source] (map-indexed vector sources) :when source
                                                :let [{:keys [child-type mutable? capture-fields root-type fields type-value?]} source
                                                      child-type (:child-type (get operands [id index]))
                                                      value-type (str "Value_" id "_" index)
                                                      child-name (str "Child_" id "_" index)]]
                                            (str "        const probe_" id "_" index
                                                 " = @import(\"operation_probe.zig\").Inspector(.{struct { "
                                                 "pub const function_root = false; pub fn get() type { return " child-name "; } "
                                                 "pub fn name() []const u8 { return "
                                                 (artifact/print-data (artifact/print-data child-type)) "; } }});\n"
                                                 "        " (if mutable? "var " "const ") "value_" id "_" index ": "
                                                 (if type-value? "type" value-type) " = "
                                                 (if type-value? child-name "undefined") ";\n"
                                                 (apply str
                                                        (for [member (rest capture-fields)]
                                                          (str "        if (" child-name " != "
                                                               (binding [emitter/*keyword-context* context]
                                                                 (emitter/emit-type
                                                                  (reduce (fn [type member]
                                                                            (list 'aguafria.keyword/FieldType type (name member)))
                                                                          root-type (concat [member] fields))))
                                                               ") @compileError(\"JVM capture payload types disagree\");\n"))))))
                                   "        @import(\"operation_probe.zig\").Inspector(.{}).log("
                                   (artifact/print-data (str "aguafria.jvm-value:" id ":["))
                                   (apply str (map #(str " ++ " % " ++ \" \"") slots))
                                   " ++ \"]\");\n    }\n")))
                     "}\n")
                :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                        "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}}))
            observed (when-not (compiler-errors? result)
                       (compiler-observations (:err result) "aguafria\\.jvm-value"))]
        {:basis :zig-compiler :representation :ordinary-jvm :nominal-equivalence? false
         :observed observed :plans @plans :sources sources :module module
         :compiler-errors? (compiler-errors? result) :command (:command result)
         :source-path (:source-path result) :diagnostics (:err result)}))))

(defn- inspect-jvm-construction-inputs!
  "Describe explicit embedded construction operands with the Zig compiler.
  Container operands use the very same ordinary JVM descriptor planner; the
  query does not claim equality to the original native lexical container."
  [report]
  (let [inputs (->> (:operations report)
                    (mapcat :jvm-value-sources)
                    (filter #(and (:input-source %)
                                  (not (literal-data? (initializer-source (:input-source %))))))
                    (map #(select-keys % [:type-source :input-source]))
                    distinct vec)]
    (when (seq inputs)
      (let [plan-type (requiring-resolve 'aguafria.zig.jvm/anonymous-type-plan)
            register-type (requiring-resolve 'aguafria.zig.jvm/register-anonymous-type-plan!)
            plans (atom {})
            register (fn register [{:keys [caller container locals] :as source}]
                       (or (get @plans source)
                           (let [locals (into {} (map (fn [[name source]] [name (register source)])) locals)
                                 plan (plan-type caller container locals)]
                             (register-type plan)
                             (swap! plans assoc source (:type plan))
                             (:type plan))))
            module (symbol (str "aguafria.jvm.construction-inspection-"
                                (subs (runtime/adapter-fingerprint [(:namespace report) inputs]) 0 24)))
            context (or (find-ns module) (create-ns module))
            aliases (atom {})
            alias-type (fn [type]
                         (or (get @aliases type)
                             (let [name (symbol (str "InputType_" (count @aliases)))]
                               (swap! aliases assoc type name)
                               name)))
            inputs
            (binding [runtime/*source-only-registration?* true]
              (mapv (fn [{:keys [type-source input-source] :as input}]
                      (let [caller (the-ns (:caller type-source))
                            root (register type-source)
                            _ (alias-type root)
                            query-source (binding [emitter/*keyword-context* caller]
                                           (initializer-source
                                            (walk/prewalk
                                             (fn [form]
                                               (if-let [source (jvm-value-type-source caller form)]
                                          ;; A typed native constructor becomes a type-only
                                          ;; leaf, not a reconstructed anonymous @Struct.
                                                 (if (:pointer? source)
                                                   form
                                                   (let [root (register (:type-source source))
                                                         path (concat (when-let [member (first (:capture-fields source))]
                                                                        [member])
                                                                      (:fields source))
                                                         type (reduce (fn [type member]
                                                                        (list 'aguafria.keyword/FieldType type (name member)))
                                                                      root path)]
                                                     (list 'aguafria.keyword/as 'aguafria.keyword/undefined
                                                           (alias-type type))))
                                                 form))
                                             input-source)))
                            schema (binding [emitter/*keyword-context* caller]
                                     (let [render #(emitter/emit-expr %)]
                                       (operand-schema render (render query-source) nil query-source "argumentSchema")))]
                        (assoc input :root-type root :query-source query-source :schema-source schema)))
                    inputs))
            declarations
            (binding [runtime/*source-only-registration?* true]
              (mapv (fn [[type name]]
                      (let [descriptor (emitter/prepare-declaration
                                        context {:kind :const :name name :module (str module)
                                                 :declaration-key [:const name] :public? true
                                                 :value (list 'aguafria.zig/type type)})]
                        (runtime/register-declaration! descriptor)
                        descriptor))
                    (sort-by (comp str val) @aliases)))
            result
            (runtime/inspect-module!
             module
             (fn [_]
               {:source
                (str (emitter/emit-module module declarations)
                     "\nconst __aguafria_probe = @import(\"operation_probe.zig\").Inspector(.{"
                     (str/join ", "
                               (for [[type name] (sort-by (comp str val) @aliases)]
                                 (str "struct { pub const function_root = false; pub fn get() type { return " name
                                      "; } pub fn name() []const u8 { return "
                                      (artifact/print-data (artifact/print-data type)) "; } }")))
                     "});\ncomptime {\n"
                     (apply str
                            (map-indexed
                             (fn [index {:keys [schema-source]}]
                               (str "    __aguafria_probe.log("
                                    (artifact/print-data (str "aguafria.jvm-construction:" index ":"))
                                    " ++ " schema-source ");\n")) inputs))
                     "}\n")
                :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))
                        "jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}}))
            observed (when-not (compiler-errors? result)
                       (compiler-observations (:err result) "aguafria\\.jvm-construction"))]
        {:basis :zig-compiler :representation :ordinary-jvm :nominal-equivalence? false
         :inputs (mapv #(dissoc % :schema-source) inputs) :observed observed :plans @plans
         :module module :compiler-errors? (compiler-errors? result) :command (:command result)
         :source-path (:source-path result) :diagnostics (:err result)}))))

(defn- observed-type-argument-sources
  [operation types]
  (when (and (string? (:form operation))
             (some #(and (map? %) (:comptime-type %)) types))
    (let [form (binding [*read-eval* false] (read-string (:form operation)))]
      (when (and (seq? form) (= (count types) (count (rest form))))
        (vec (rest form))))))

(defn prepare!
  "Connect compiler observations to the ordinary JVM adapter generators. Reports
  unsupported/unobserved operations instead of executing them for discovery."
  [module]
  (let [report (analyze! module)
        jvm-maps (refine-jvm-map-representations! report)
        jvm-refinement (refine-jvm-type-representations! report)
        jvm-values (refine-jvm-value-representations! report)
        jvm-constructions (inspect-jvm-construction-inputs! report)
        report (cond-> report
                 jvm-maps (assoc :jvm-map-representation-refinement (dissoc jvm-maps :observed))
                 jvm-refinement (assoc :jvm-representation-refinement (dissoc jvm-refinement :observed :plans))
                 jvm-values (assoc :jvm-value-representation-refinement (dissoc jvm-values :observed :plans)))
        type-preparation-errors
        (into {}
              (keep (fn [type]
                      (try (runtime/precompile-type! type) nil
                           (catch Exception error [type (error-report error)]))))
              (distinct (concat (when-not (:compiler-errors? jvm-refinement) (vals (:plans jvm-refinement)))
                                (when-not (:compiler-errors? jvm-values) (vals (:plans jvm-values)))
                                (when-not (:compiler-errors? jvm-constructions) (vals (:plans jvm-constructions))))))
        prepare-call (requiring-resolve 'aguafria.zig.jvm/precompile-call!)
        prepare-type (requiring-resolve 'aguafria.zig.jvm/precompile-coercion!)
        prepare-literal-type (requiring-resolve 'aguafria.zig.jvm/precompile-literal-coercion!)
        native-literal? (requiring-resolve 'aguafria.zig.jvm/native-literal?)
        prepare-conversion (requiring-resolve 'aguafria.zig.jvm/precompile-conversion!)
        prepare-constant-conversion (requiring-resolve 'aguafria.zig.jvm/precompile-constant-conversion!)
        prepare-contextual (requiring-resolve 'aguafria.zig.jvm/precompile-contextual-conversion!)
        prepare-concrete (requiring-resolve 'aguafria.zig.jvm/precompile-concrete-call!)
        prepare-construction (requiring-resolve 'aguafria.zig.jvm/precompile-construction!)
        prepare-source-construction (requiring-resolve 'aguafria.zig.jvm/precompile-source-construction!)
        prepare-storage (requiring-resolve 'aguafria.zig.jvm/precompile-storage!)
        prepare-method (requiring-resolve 'aguafria.zig.jvm/precompile-method!)
        prepare-scoped (requiring-resolve 'aguafria.zig.jvm/precompile-scoped!)
        prepare-assignment (requiring-resolve 'aguafria.zig.jvm/precompile-assignment!)
        prepare-literal (requiring-resolve 'aguafria.zig.jvm/precompile-source-literal!)
        prepared (atom {})
        construction-dependencies
        (when jvm-constructions
          (mapv (fn [index {:keys [root-type]}]
                  (let [observations (get-in jvm-constructions [:observed (str index)])]
                    (if (or (:compiler-errors? jvm-constructions) (empty? observations))
                      {:type root-type :status :failed :reason :unobserved-construction-input}
                      (let [{:keys [signatures limited?]}
                            (preparation-signatures (mapv vector observations))]
                        {:type root-type
                         :handlers (cond->
                                    (mapv (fn [[input]]
                                            (try (assoc (prepare-construction root-type input) :input input)
                                                 (catch Exception error
                                                   (assoc (error-report error) :input input :status :failed))))
                                          signatures)
                                     limited? (conj {:status :unsupported :reason :representation-expansion-limit}))}))))
                (range) (:inputs jvm-constructions)))
        construction-dependency-errors
        (into {}
              (keep (fn [{:keys [type status handlers] :as dependency}]
                      (when-let [failure (or (when (= :failed status) dependency)
                                             (first (remove #(= :prepared (:status %)) handlers)))]
                        [type (assoc failure :status :failed :dependency :construction-input)])))
              construction-dependencies)
        functions (prepare-declared-functions! module)
        constant-readers (prepare-constant-readers! module (set (keys (:root-failures report))))
        reader-plans (into {} (map (juxt :constant identity)) constant-readers)]
    (assoc (cond-> report
             jvm-constructions
             (assoc :jvm-construction-input-refinement
                    (assoc (dissoc jvm-constructions :observed :plans)
                           :dependencies construction-dependencies)))
           :functions functions
           :constant-readers constant-readers
           :operations
           (mapv
            (comp
             #(retain-declared-initializer-owner module reader-plans %)
             (fn [{:keys [status signatures result-reader-types declaration-kind constructor? literal-constructor? constructor-value constructor-source conversion? contextual-input? contextual-plan contextual-scope requires-result-context? concrete-function? argument-sources function storage-kind address-reference member assignment literal-arguments method-call? scoped-form scope-captures scope-result? scope-result-context?] :as operation}]
               (let [operation (assoc operation :enclosing-context
                                      (if (= :test declaration-kind) :test :runtime))]
                 (if-not (= :observed status)
                   operation
                   (let [jvm-types (get-in jvm-refinement [:observed (:id operation)])
                         jvm-root-types (concat jvm-types
                                                (keep #(get (:plans jvm-values) (:type-source %))
                                                      (:jvm-value-sources operation)))
                         dependency-error (some #(or (get type-preparation-errors %)
                                                     (get construction-dependency-errors %))
                                                jvm-root-types)
                         jvm-signatures (or (some->> (get-in jvm-maps [:observed (:id operation)])
                                                     (sort-by artifact/print-data) vec)
                                            (some->> (get-in jvm-values [:observed (:id operation)])
                                                     (sort-by artifact/print-data) vec)
                                            (when (seq jvm-types)
                                              (mapv (fn [signature]
                                                      (into [{:comptime-type (first jvm-types)}] (rest signature)))
                                                    signatures)))
                         jvm-readers (vec (sort-by artifact/print-data
                                                   (remove nil? (get-in jvm-refinement
                                                                        [:reader-schemas (:id operation)]))))
                         result-reader-types (vec (distinct (concat result-reader-types jvm-readers)))
                         {prepared-signatures :signatures limited? :limited?}
                         (preparation-signatures (or jvm-signatures signatures))]
                     (cond-> (assoc operation :handlers
                                    (cond->
                                     (mapv (fn [types]
                                             (let [tuple-access? (and (#{:field :index} storage-kind)
                                                                      (map? (first types))
                                                                      (or (contains? (first types) :tuple)
                                                                          (and (= :field storage-kind)
                                                                               (contains? (first types) :map))))
                                                   comptime-construction (when constructor?
                                                                           (:comptime-construction (first types)))
                                                   input-types (cond
                                                                 comptime-construction [comptime-construction]
                                                                 scoped-form
                                                                 (cond-> (map first (partition 2 types))
                                                                   scope-result-context? (concat [(peek types)]))
                                                                 (or tuple-access?
                                                                     (and storage-kind
                                                                          (:comptime-expression (first types))))
                                                                 (cons (first types) (drop 2 types))
                                                                 :else types)]
                                               (cond
                                                 dependency-error
                                                 (assoc dependency-error
                                                        :status :failed :types types)

                                                 (= :import-member
                                                    (get-in (meta (find-var function))
                                                            [:aguafria/zig-reference :kind]))
                                                 {:status :unsupported :reason :declaration-only-import :types types}
                                                 requires-result-context?
                                                 {:status :deferred :reason :result-context-required :types types}
                                                 (and (not (or concrete-function? address-reference))
                                                      (some #(and (map? %) (:comptime-local-type %)) types))
                                                 {:status :unsupported :reason :comptime-receiver-specialization :types types}
                                                 (not (or concrete-function? address-reference literal-arguments
                                                          (every? supported-argument? input-types)))
                                                 {:status :unsupported :reason :non-runtime-or-nominal-type :types types}
                                                 :else
                                                 (let [source-arguments (observed-type-argument-sources operation types)
                                                       key [runtime/*native-test-context?* (= :test declaration-kind) constructor? constructor-value contextual-plan contextual-scope function types argument-sources source-arguments result-reader-types storage-kind address-reference member literal-arguments method-call? scoped-form scope-captures]]
                                                   (or (get @prepared key)
                                                       (let [result (try
                                                                      (prepare-in-observed-context
                                                                       declaration-kind
                                                                       #(cond
                                                                          comptime-construction
                                                                          (prepare-source-construction comptime-construction
                                                                                                       constructor-source)
                                                                          scoped-form
                                                                          (prepare-scoped {:caller (symbol (str module))
                                                                                           :form (vary-meta scoped-form assoc
                                                                                                            :aguafria/scoped-capture-contracts
                                                                                                            (:scope-capture-contracts operation))
                                                                                           :captures scope-captures
                                                                                           :types (cond-> types scope-result-context? pop)
                                                                                           :result-type (when scope-result-context? (peek types))
                                                                                           :result? (not (false? scope-result?))})
                                                                          contextual-plan
                                                                          (prepare-contextual {:type (first types)
                                                                                               :plan contextual-plan
                                                                                               :args (vec (rest types))})
                                                                          contextual-scope
                                                                          (prepare-scoped {:caller (symbol (str module))
                                                                                           :form (:form contextual-scope)
                                                                                           :captures (:captures contextual-scope)
                                                                                           :types (vec (rest types))
                                                                                           :result-type (first types)
                                                                                           :result? true})
                                                                          method-call?
                                                                          (prepare-method {:receiver (first types) :address (second types)
                                                                                           :member member :args (vec (drop 2 types))
                                                                                           :result-reader-types result-reader-types})
                                                                          literal-arguments
                                                                          (prepare-literal function literal-arguments (first types))
                                                                          assignment
                                                                          (prepare-assignment {:function function :operation assignment
                                                                                               :target (first types) :operand (second types)})
                                                                          tuple-access?
                                                                          (prepare-call {:function function
                                                                                         :args [(first types)
                                                                                                (if (= :field storage-kind)
                                                                                                  {:comptime member}
                                                                                                  (nth types 2))]})
                                                                          storage-kind
                                                                          (prepare-storage {:kind storage-kind :receiver (first types)
                                                                                            :address (second types) :reference address-reference :member member
                                                                                            :indices (vec (drop 2 types))})
                                                                          (and conversion? (:constant-coercion (second types)))
                                                                          (let [[input-type source] (:constant-coercion (second types))]
                                                                            (prepare-constant-conversion input-type (first types) source))
                                                                          (and conversion? (:comptime-expression (second types)))
                                                                          (prepare-literal-type (first types)
                                                                                                (:comptime-expression (second types)))
                                                                          (and conversion? (structural-type? (second types)))
                                                                          (prepare-conversion (second types) (first types))
                                                                          (and conversion? (:map (second types)))
                                                                          (prepare-construction (first types) (second types))
                                                                          constructor?
                                                                          (cond-> (cond
                                                                                    (:map (second types))
                                                                                    (prepare-construction (first types) (second types))
                                                                                    (and literal-constructor?
                                                                                         (or (seq? (first types))
                                                                                             (native-literal? constructor-value)))
                                                                                    (prepare-literal-type (first types) constructor-value)
                                                                                    :else (prepare-type (first types)))
                                                                            contextual-input?
                                                                            (assoc :status :partial :reason :result-context-required))
                                                                          concrete-function? (prepare-concrete function types argument-sources)
                                                                          :else (prepare-call {:function function :args types
                                                                                               :caller (symbol (str module))
                                                                                               :source-arguments source-arguments
                                                                                               :result-reader-types result-reader-types})))
                                                                      (catch Exception error
                                                                        (assoc (error-report error) :status :failed)))]
                                                         (swap! prepared assoc key result)
                                                         result)))))) prepared-signatures)
                                      limited? (conj {:status :partial
                                                      :reason :representation-limit
                                                      :prepared-variant-limit max-representation-variants})))
                       jvm-signatures (assoc :jvm-signatures jvm-signatures
                                             :jvm-result-reader-types jvm-readers)))))))
            (:operations report)))))
