(ns aguafria.zig.discovery
  "Compile-only operation discovery. Zig supplies every observed type; emitter
  records supply operation identity, never Clojure type inference."
  (:require [aguafria.keyword :as keyword]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn- field-reference? [form]
  (and (seq? form) (symbol? (first form))
       (= "field" (name (first form))) (= 3 (count form))
       (or (keyword? (nth form 2)) (string? (nth form 2)))))

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
        reference (:aguafria/zig-reference metadata)]
    (cond
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

(defn- operand-schema [render expression parameter form schema-kind]
  (let [metadata (when (and (seq? form) (symbol? (first form)))
                   (some-> (ns-resolve (or emitter/*keyword-context* *ns*) (first form)) meta))
        syntax (:aguafria/token metadata)
        branches (when (and (seq? form) (= 'if (first form))) (literal-branches form))
        join-schemas #(if (seq %) (str/join " ++ \" \" ++ " %) "\"\"")]
    (cond
      ((requiring-resolve 'aguafria.zig.jvm/source-concatenation-form?) form)
      ;; Preserve the same source expression returned by the ordinary JVM
      ;; concatenation adapter. The probe's comptime parameter asks Zig to
      ;; validate it before the representation enters a preparation signature.
      (str "__aguafria_probe.comptimeExpression(" expression ", "
           (artifact/print-data (artifact/print-data form)) ")")

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
                                     (operand-schema render (render item) nil item "argumentSchema"))) form))
           " ++ \"}}\"")

      :else
      (str "(if (@TypeOf(" expression ") == type) \"{:comptime-type \" ++ __aguafria_probe.schema(" expression ") ++ \"}\" "
           "else if (@TypeOf(" expression ") == comptime_int or @TypeOf(" expression ") == comptime_float) "
           "__aguafria_probe.literal(" expression ") "
           "else if (@typeInfo(@TypeOf(" expression ")) == .enum_literal) "
           "__aguafria_probe.comptimeValue(" expression ") else "
           (if (or (= "comptime" (get-in parameter [:properties :zig/prefix]))
                   (and (string? form) (get-in parameter [:properties :jvm/literal?])))
             (let [schema (str "__aguafria_probe.comptimeValue(" expression ")")]
               (if (symbol? form)
                 (declaration-argument-schema render form schema)
                 schema))
             (declaration-argument-schema render form
                                          (str "__aguafria_probe." schema-kind "(@TypeOf(" expression "))")))
           ") "))))

(defn- observer [operations selected]
  (fn [{:keys [form source-form source location var-meta render placement place-probe defer-probe assignment
               method-call? receiver member declaration-name declaration-kind]}]
    (let [syntax (:aguafria/token var-meta)
          function (if method-call? 'aguafria.zig/field (qualified-name var-meta))
          type-expression? (= 'aguafria.zig/type function)
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
          concrete-function? (and (contains? #{:fn :fn-proto} (:kind declaration))
                                  (not-any? #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                                 (= "comptime" (get-in % [:properties :zig/prefix])))
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
              ;; Only an unconsumed deferred call needs this outer cast's
              ;; result location. A nested @as may already provide it. For
              ;; ordinary operands, query Zig for the operand type directly.
              contextual-input? (boolean contextual-plan)
              deferred-plan (when (keyword/result-context-required? (:zig-name syntax))
                              (contextual-call-plan form))
              storage? (or address? (#{:field :index :slice :deref} placement))
              probe? (or type-expression? method-call? source-literal? assignment storage? constructor? operator? imported?
                         (= :const (:kind declaration))
                         (= 'aguafria.zig/unwrap function)
                         (contains? #{:fn :fn-proto} (:kind declaration))
                         (and (= :call (:kind syntax))
                              (not (contains? #{"@branchHint" "@compileError" "@compileLog"
                                                "@setEvalBranchQuota" "@setRuntimeSafety"
                                                "@setFloatMode" "@setCold"}
                                              (:zig-name syntax)))))
              expressions (when probe?
                            (cond
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
                                              (if (= :field placement)
                                                (str "(if (@TypeOf(" (render (second form))
                                                     ") == type) {} else &(" (render (second form)) "))")
                                                (str "&(" (render (second form)) ")"))]
                                             (case placement
                                               :slice (map #(slice-index-probe render (second form) %) (drop 2 form))
                                               :index (map render (drop 2 form))
                                               nil))
                              (and conversion? (not contextual-input?))
                              [(render form) (render (second form))]
                              contextual-plan
                              (into [(render form)] (map render (:leaves contextual-plan)))
                              constructor? [(render form)]
                              deferred-plan (mapv render (:leaves deferred-plan))
                              :else
                              (mapv (fn [index argument]
                                      (let [expression (render argument)]
                                        (if concrete-function?
                                          ;; Keep the original call's parameter context, including
                                          ;; casts and runtime branches whose arms are literals.
                                          (str "@as(@typeInfo(@TypeOf(" (render (first form))
                                               ")).@\"fn\".param_types[" index "].?, " expression ")")
                                          expression)))
                                    (range) (rest form))))
              parameters (cond
                           method-call?
                           ;; The JVM member planner embeds source strings. Keep
                           ;; that representation here, including comptime formats.
                           (concat [nil nil]
                                   (repeat (dec (count form))
                                           {:properties {:jvm/literal? true}}))
                           operator?
                           (repeat (dec (count form)) {:properties {:jvm/literal? true}})
                           contextual-plan (into [nil] (:parameters contextual-plan))
                           deferred-plan (:parameters deferred-plan)
                           (and probe? (not (or method-call? source-literal? storage? constructor? assignment operator?)))
                           ((requiring-resolve 'aguafria.zig.jvm/call-parameters)
                            var-meta (dec (count form))))
              non-call-reason (cond
                                (= 'aguafria.zig/container function) :type-declaration
                                (contains? #{"@branchHint" "@compileError" "@compileLog"
                                             "@setEvalBranchQuota" "@setRuntimeSafety"
                                             "@setFloatMode" "@setCold"} (:zig-name syntax))
                                :compiler-directive)
              operation (merge location
                               {:id id :function function :form (artifact/print-data (or source-form form))
                                :declaration-name declaration-name
                                :declaration-kind declaration-kind
                                :returns-type? (or (= :type (:return declaration))
                                                   (= :type-function (get-in var-meta [:aguafria/zig-reference :category])))
                                :parameter-types (mapv :type parameters)
                                :constructor? constructor?
                                :literal-constructor? (and constructor? (literal-data? (second form)))
                                :constructor-value (when (and constructor? (literal-data? (second form)))
                                                     (second form))
                                :conversion? conversion?
                                :contextual-input? contextual-input?
                                :contextual-plan (:plan contextual-plan)
                                :requires-result-context? (keyword/result-context-required? (:zig-name syntax))
                                :storage-kind (when storage? (if address? :address placement))
                                :address-reference address-reference
                                :method-call? method-call?
                                :assignment assignment
                                :literal-arguments (when source-literal? (vec (rest form)))
                                :member (if method-call? member (when (= :field placement) (nth form 2)))
                                :concrete-function? concrete-function?
                                :status (if probe? :unobserved :unsupported)
                                :reason (when-not probe? (or non-call-reason :inspection-placement))})]
          (swap! operations conj operation)
          (if-not (and probe? (or (nil? selected) (contains? selected id)))
            source
            (let [label (str "aguafria_operation_" id)
                  schemas (map (fn [index expression parameter argument-form]
                                 (if (and concrete-function? (contextual-call-plan argument-form))
                                   (str "\"{:contextual-argument [\" ++ __aguafria_probe.schema(@TypeOf("
                                        expression ")) ++ \" \" ++ "
                                        (operand-schema render (render argument-form) parameter
                                                        argument-form "argumentSchema")
                                        " ++ \"]}\"")
                                   (operand-schema render expression parameter
                                                   (cond
                                                     concrete-function? nil
                                                     assignment
                                                     ;; Keep the assignment's result context for values;
                                                     ;; deferred calls instead need their leaf operand types.
                                                     (when (and (= 1 index)
                                                                (contextual-call-plan argument-form))
                                                       argument-form)
                                                     :else argument-form)
                                                   (if (and (zero? index) (or assignment constructor? address?))
                                                     "schema" "argumentSchema"))))
                               (range) expressions (concat parameters (repeat nil))
                               (concat (when method-call? [nil nil])
                                       (when (= :index placement) (concat [nil nil] (drop 2 form)))
                                       (when (and conversion? (not contextual-input?)) [nil (second form)])
                                       (when-not (or storage? constructor?) (rest form))
                                       (repeat nil)))
                  schemas (if (= :index placement)
                            (map-indexed
                             (fn [index schema]
                               (if (< index 2)
                                 schema
                                 (let [receiver (first expressions)
                                       expression (nth expressions index)]
                                   (str "(if (@typeInfo(@TypeOf(" receiver ")) == .@\"struct\" and "
                                        "@typeInfo(@TypeOf(" receiver ")).@\"struct\".is_tuple) "
                                        "__aguafria_probe.comptimeValue(@as(usize, " expression ")) else "
                                        schema ")"))))
                             schemas)
                            schemas)
                  payload (str (artifact/print-data (str "aguafria.operation:" id ":["))
                               (apply str (map #(str " ++ " % " ++ \" \"") schemas))
                               " ++ \"]\"")]
              (cond
                defer-probe
                (do (defer-probe (str "__aguafria_probe.log(" payload ");")) source)

                place-probe
                (let [log (str "__aguafria_probe.log(" payload ");")]
                  {:source (place-probe log label)
                   :place-probe (fn [outer-log outer-label]
                                  (place-probe (str log " " outer-log) outer-label))})
                :else
                (str "(" (when-not noreturn? (str label ": "))
                     "{ __aguafria_probe.log(" payload "); "
                     (when-not noreturn?
                       (str "break :" label " "))
                     source "; })")))))))))

(defn- root-declarations [declarations]
  (filterv (fn [{:keys [kind args jvm-adapter?]}]
             (and (not jvm-adapter?)
                  (or (contains? #{:const :var :struct :enum :union :opaque} kind)
                      (and (= :fn kind)
                           (not-any? #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                          (= "comptime" (get-in % [:properties :zig/prefix])))
                                     args)))))
           declarations))

(defn- roots [declarations]
  ;; Referencing a container alone does not analyze its method bodies. Let Zig
  ;; enumerate concrete declarations recursively, preserving nominal identity
  ;; and leaving genuinely generic methods unspecialized. These roots are only
  ;; compiled with --test-no-exec; no constructor or method is invoked.
  (str "\nfn __aguafria_inspect_declarations(comptime T: type, comptime visited: anytype) void {\n"
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
       "            _ = &@field(T, declaration);\n"
       "        }\n"
       "    }\n"
       "}\n"
       "\ntest \"aguafria inspection roots\" {\n"
       (apply str
              (for [{:keys [kind name zig-name]} (root-declarations declarations)
                    :when (or (nil? *inspection-roots*) (*inspection-roots* name))]
                (let [reference (emitter/identifier (or zig-name name))]
                  (str "    _ = &" reference ";\n"
                       (when (container-kinds kind)
                         (str "    __aguafria_inspect_declarations(" reference ", .{});\n"))))))
       "}\n"))

(defn- observations [stderr]
  (let [log (second (str/split stderr #"Compile Log Output:\r?\n" 2))]
    (reduce (fn [found [_ encoded]]
              (let [decoded (String. (.parseHex (java.util.HexFormat/of) encoded)
                                     java.nio.charset.StandardCharsets/UTF_8)
                    [_ id schemas] (re-matches #"aguafria\.operation:([0-9]+):(.*)" decoded)]
                (if id (update found id (fnil conj #{}) (edn/read-string schemas)) found)))
            {} (re-seq #"\"aguafria\.operation\.hex:([0-9a-f]+)\"" (or log "")))))

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
          (let [names (mapv :name (root-declarations
                                   (:definitions (runtime/module-info module))))
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
  (let [candidates (->> (:definitions (runtime/module-info module))
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

(defn- type-catalog [module declarations excluded]
  (let [excluded (catalog-exclusions module declarations
                                     (into excluded *rejected-inspection-roots*))
        local (for [{:keys [name zig-name kind] :as declaration} declarations
                    :when (and (not (contains? excluded name))
                               (or (declared-type? declaration)
                                   (contains? *local-type-identities* name)
                                   (contains? #{:fn :fn-proto} kind)))]
                (let [reference (emitter/identifier (or zig-name name))
                      qualified (symbol (str module) (str name))]
                  (if (contains? #{:fn :fn-proto} kind)
                    [(str "@TypeOf(" reference ")")
                     (artifact/print-data (list 'aguafria.keyword/TypeOf qualified))]
                    [reference (str qualified)])))
        imported (for [symbol (->> declarations (tree-seq coll? seq)
                                   (filter qualified-symbol?) distinct (sort-by str))
                       :let [v (some-> (find-ns (clojure.core/symbol (namespace symbol)))
                                       (ns-resolve (clojure.core/symbol (name symbol))))
                             reference (:aguafria/zig-reference (meta v))]
                       :when (and (:zig-name reference) (not= (str module) (namespace symbol)))]
                   (let [expression (emitter/emit-expr (the-ns (clojure.core/symbol (str module)))
                                                       symbol)]
                     [(str "if (@TypeOf(" expression ") == type) " expression
                           " else @TypeOf(" expression ")")
                      {:source (str "if (@TypeOf(" expression ") == type) "
                                    (artifact/print-data (str symbol)) " else "
                                    (artifact/print-data (artifact/print-data (list 'aguafria.keyword/TypeOf symbol))))}]))]
    ;; Resolve candidates lazily: a function signature can itself contain a
    ;; probe. Eagerly typing the whole catalog would make that probe depend on
    ;; the signature it is inspecting. Zig still performs every type comparison.
    (str/join ", "
              (map (fn [[expression name]]
                     (str ".{ struct { pub fn get() type { return " expression "; } }, "
                          (if (map? name) (:source name) (artifact/print-data name)) " }"))
                   (concat local imported
                           [["@import(\"std\").lang.Type"
                             "(aguafria.keyword/TypeOf (aguafria.keyword/typeInfo :u8))"]]
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

(defn- inspection-declarations [module declarations]
  (let [context (the-ns (symbol (str module)))
        expressions (mapv #(emitter/qualify-form context %) *observed-type-identities*)
        existing (emitter/declaration-imports declarations)
        imports (emitter/declaration-imports expressions)]
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
                        ;; A probe inside an alias initializer cannot resolve
                        ;; that same alias through the type catalog. Other
                        ;; declarations and C roots still establish its identity.
                        excluded (into #{}
                                       (keep (fn [{:keys [id status declaration-name declaration-kind]}]
                                               (when (and (= :unobserved status)
                                                          (= :const declaration-kind)
                                                          (contains? *local-type-identities* declaration-name)
                                                          (or (nil? selected) (contains? selected id)))
                                                 declaration-name)))
                                       @operations)]
                    {:source (str "const __aguafria_probe = @import(\"operation_probe.zig\").Inspector(.{"
                                  (type-catalog module declarations excluded)
                                  "});\n" source (roots declarations))
                     :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))}})))]
    (assoc result :operations @operations :observed (observations (:err result)))))

(defn- isolate-probes! [module initial]
  ;; Compile smaller probe groups only when inspection changed a valid module
  ;; into an invalid one. Keep successful compiler observations, and retain the
  ;; exact diagnostic for each failed singleton. No source/type guessing.
  (let [observed (atom (:observed initial))
        failures (atom {})
        attempts (atom 1)]
    (letfn [(inspect [ids]
              (let [result (inspect-operations! module (set ids))]
                (swap! attempts inc)
                (swap! observed #(merge-with into % (:observed result)))
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
      {:observed @observed :failures @failures :attempts @attempts})))

(defn- refine-type-identities! [module result]
  ;; Alias-initializer probes temporarily exclude their dependents from the
  ;; catalog. Query unresolved signatures without the other probes so function
  ;; bodies can use those aliases. Only a clean compiler pass may replace them.
  (let [incomplete (into #{}
                         (keep (fn [[id signatures]]
                                 (when (some nil? (tree-seq coll? seq signatures)) id)))
                         (:observed result))]
    (when (seq incomplete)
      (let [refined (inspect-operations! module incomplete)]
        {:observed (when-not (compiler-errors? refined)
                     (into {}
                           (filter (fn [[_ signatures]]
                                     (not-any? nil? (tree-seq coll? seq signatures))))
                           (:observed refined)))
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
       (let [{:keys [baseline analysis-baseline selected failures attempts]}
             (analyze-roots! module)]
         (binding [*inspection-roots* selected
                   *rejected-inspection-roots* (set (keys failures))]
           (let [local-types (local-type-identities! module)]
             (binding [*local-type-identities* (:identities local-types)]
               (let [initial (inspect-operations! module nil)
                     identities (observed-type-identities initial)
                     result (if (seq identities)
                              (binding [*observed-type-identities* identities]
                                (inspect-operations! module nil))
                              initial)
                     probes (if (and (zero? (:exit analysis-baseline))
                                     (compiler-errors? result))
                              (binding [*observed-type-identities* identities]
                                (isolate-probes! module result))
                              {:observed (:observed result) :failures {} :attempts 1})
                     refinement (binding [*observed-type-identities* identities]
                                  (refine-type-identities!
                                   module {:observed (:observed probes)}))
                     observed (merge (:observed probes) (:observed refinement))]
                 {:namespace (symbol (str module))
                  :basis :zig-compiler
                  :local-type-identities (:identities local-types)
                  :local-type-query (:query local-types)
                  :identity-refinement (some-> refinement (dissoc :observed))
                  :type-identities identities
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
                                          (if refinement 1 0))
                  :probe-failures (:failures probes)
                  :operations
                  (mapv (fn [operation]
                          (if-let [types (get observed (:id operation))]
                            (-> operation
                                (assoc :status :observed
                                       :signatures (vec (sort-by artifact/print-data types)))
                                (dissoc :reason))
                            (cond
                              (contains? failures (:declaration-name operation))
                              (assoc operation :status :inspection-failed
                                     :reason :compiler-rejected-root)

                              (contains? (:failures probes) (:id operation))
                              (assoc operation :status :inspection-failed
                                     :reason :compiler-rejected-probe)

                              :else operation)))
                        (:operations result))})))))))))

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
           (or (and (#{:comptime_int :comptime_float} (:type argument))
                    (number? (:literal argument)))
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
               (and (= #{:contextual-call} (set (keys argument)))
                    (qualified-symbol? (first (:contextual-call argument)))
                    (every? supported-argument? (second (:contextual-call argument))))))))

(declare signature-variants)

(defn- argument-variants [argument]
  (cond
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
        (->> (:definitions (runtime/module-info module))
             (remove :jvm-adapter?)
             (filter #(contains? #{:fn :fn-proto :test} (:kind %)))
             (sort-by (comp str :name)))))

(defn prepare!
  "Connect compiler observations to the ordinary JVM adapter generators. Reports
  unsupported/unobserved operations instead of executing them for discovery."
  [module]
  (let [report (analyze! module)
        prepare-call (requiring-resolve 'aguafria.zig.jvm/precompile-call!)
        prepare-type (requiring-resolve 'aguafria.zig.jvm/precompile-coercion!)
        prepare-literal-type (requiring-resolve 'aguafria.zig.jvm/precompile-literal-coercion!)
        native-literal? (requiring-resolve 'aguafria.zig.jvm/native-literal?)
        prepare-conversion (requiring-resolve 'aguafria.zig.jvm/precompile-conversion!)
        prepare-contextual (requiring-resolve 'aguafria.zig.jvm/precompile-contextual-conversion!)
        prepare-concrete (requiring-resolve 'aguafria.zig.jvm/precompile-concrete-call!)
        prepare-construction (requiring-resolve 'aguafria.zig.jvm/precompile-construction!)
        prepare-storage (requiring-resolve 'aguafria.zig.jvm/precompile-storage!)
        prepare-method (requiring-resolve 'aguafria.zig.jvm/precompile-method!)
        prepare-assignment (requiring-resolve 'aguafria.zig.jvm/precompile-assignment!)
        prepare-literal (requiring-resolve 'aguafria.zig.jvm/precompile-source-literal!)
        prepared (atom {})]
    (assoc report :functions (prepare-declared-functions! module)
           :operations
           (mapv
            (fn [{:keys [status signatures constructor? literal-constructor? constructor-value conversion? contextual-input? contextual-plan requires-result-context? concrete-function? function storage-kind address-reference member assignment literal-arguments method-call?] :as operation}]
              (if-not (= :observed status)
                operation
                (let [{prepared-signatures :signatures limited? :limited?}
                      (preparation-signatures signatures)]
                  (assoc operation :handlers
                         (cond->
                          (mapv (fn [types]
                                  (let [tuple-access? (and (#{:field :index} storage-kind)
                                                           (map? (first types))
                                                           (contains? (first types) :tuple))
                                        input-types (if tuple-access?
                                                      (cons (first types) (drop 2 types))
                                                      types)]
                                    (cond
                                      (= :import-member
                                         (get-in (meta (find-var function))
                                                 [:aguafria/zig-reference :kind]))
                                      {:status :unsupported :reason :declaration-only-import :types types}
                                      requires-result-context?
                                      {:status :deferred :reason :result-context-required :types types}
                                      (not (or concrete-function? literal-arguments
                                               (every? supported-argument? input-types)))
                                      {:status :unsupported :reason :non-runtime-or-nominal-type :types types}
                                      :else
                                      (let [key [constructor? constructor-value contextual-plan function types storage-kind address-reference member literal-arguments method-call?]]
                                        (or (get @prepared key)
                                            (let [result (try
                                                           (cond
                                                             contextual-plan
                                                             (prepare-contextual {:type (first types)
                                                                                  :plan contextual-plan
                                                                                  :args (vec (rest types))})
                                                             method-call?
                                                             (prepare-method {:receiver (first types) :address (second types)
                                                                              :member member :args (vec (drop 2 types))})
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
                                                             (and conversion? (structural-type? (second types)))
                                                             (prepare-conversion (second types) (first types))
                                                             (and conversion? (:map (second types)))
                                                             (prepare-construction (first types) (second types))
                                                             constructor?
                                                             (cond-> (if (and literal-constructor?
                                                                              (or (seq? (first types))
                                                                                  (native-literal? constructor-value)))
                                                                       (prepare-literal-type (first types) constructor-value)
                                                                       (prepare-type (first types)))
                                                               contextual-input?
                                                               (assoc :status :partial :reason :result-context-required))
                                                             concrete-function? (prepare-concrete function types)
                                                             :else (prepare-call {:function function :args types}))
                                                           (catch Exception error
                                                             (assoc (error-report error) :status :failed)))]
                                              (swap! prepared assoc key result)
                                              result)))))) prepared-signatures)
                           limited? (conj {:status :partial
                                           :reason :representation-limit
                                           :prepared-variant-limit max-representation-variants}))))))
            (:operations report)))))
