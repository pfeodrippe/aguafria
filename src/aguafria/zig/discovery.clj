(ns aguafria.zig.discovery
  "Compile-only operation discovery. Zig supplies every observed type; emitter
  records supply operation identity, never Clojure type inference."
  (:require [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- qualified-name [{:keys [ns name]}]
  (when (and ns name) (symbol (str (ns-name ns)) (str name))))

(def ^:private contextual-builtins
  ;; These consume Zig's result location. Querying their result separately
  ;; discards that context; this is a syntax constraint, not type inference.
  #{"@intCast" "@floatCast" "@ptrCast" "@alignCast" "@addrSpaceCast"
    "@constCast" "@volatileCast" "@bitCast" "@ptrFromInt" "@fieldParentPtr"
    "@splat" "@enumFromInt" "@errorCast" "@intFromFloat" "@truncate"})

(defn- needs-result-context? [source]
  (boolean (some contextual-builtins (re-seq #"@[A-Za-z0-9_]+(?=\()" source))))

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

(defn- literal-data? [form]
  (cond
    (or (nil? form) (number? form) (boolean? form) (char? form)
        (string? form) (keyword? form)) true
    (vector? form) (every? literal-data? form)
    (map? form) (every? literal-data? (mapcat identity form))
    :else false))

(defn- declared-type? [{:keys [module name kind]}]
  (or (container-kinds kind)
      (some-> (find-ns (symbol module))
              (ns-resolve name) meta :aguafria/zig-reference :type-reference?)))

(defn- observer [operations selected]
  (fn [{:keys [form source location var-meta render placement place-probe assignment
               method-call? receiver member]}]
    (let [syntax (:aguafria/token var-meta)
          function (if method-call? 'aguafria.zig/field (qualified-name var-meta))
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
          operator? (and (= :operator (:kind syntax))
                         (not (contains? #{"&" "*" ".." "..."} (:zig-token syntax))))
          operator? (or operator? (and (= :operator (:kind syntax))
                                       (= "*" (:zig-token syntax)) (> (count form) 2)))
          imported? (and (:aguafria/zig-reference var-meta)
                         (not (:aguafria/declaration var-meta)))
          declaration (:aguafria/declaration var-meta)
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
              contextual-input? (and conversion? (needs-result-context? (render (second form))))
              storage? (or address? (#{:field :index :slice :deref} placement))
              probe? (or method-call? source-literal? assignment storage? constructor? operator? imported?
                         (= 'aguafria.zig/unwrap function)
                         (= :fn (:kind declaration))
                         (and (= :call (:kind syntax))
                              (not (contains? #{"@branchHint" "@compileError" "@compileLog"
                                                "@setEvalBranchQuota" "@setRuntimeSafety"
                                                "@setFloatMode" "@setCold"
                                                "@cImport" "@cDefine" "@cInclude" "@cUndef"}
                                              (:zig-name syntax)))))
              expressions (when probe?
                            (cond
                              method-call?
                              (into [(render receiver)
                                     (str "(if (@TypeOf(" (render receiver)
                                          ") == type) {} else &(" (render receiver) "))")]
                                    (map render (rest form)))
                              source-literal? [(render form)]
                              (and assignment (= "=" (:zig-token syntax))
                                   (needs-result-context? (render (nth form 2))))
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
                              constructor? [(render form)]
                              :else (mapv render (rest form))))
              parameters (when (and probe? (not (or method-call? source-literal? storage? constructor? assignment operator?)))
                           ((requiring-resolve 'aguafria.zig.jvm/call-parameters)
                            var-meta (dec (count form))))
              operation (merge location
                               {:id id :function function :form (pr-str form)
                                :returns-type? (or (= :type (:return declaration))
                                                   (= :type-function (get-in var-meta [:aguafria/zig-reference :category])))
                                :parameter-types (mapv :type parameters)
                                :constructor? constructor?
                                :literal-constructor? (and constructor? (literal-data? (second form)))
                                :constructor-value (when (and constructor? (literal-data? (second form)))
                                                     (second form))
                                :conversion? conversion?
                                :contextual-input? contextual-input?
                                :requires-result-context? (contains? contextual-builtins (:zig-name syntax))
                                :storage-kind (when storage? (if address? :address placement))
                                :method-call? method-call?
                                :assignment assignment
                                :literal-arguments (when source-literal? (vec (rest form)))
                                :member (if method-call? member (when (= :field placement) (nth form 2)))
                                :concrete-function? (and (= :fn (:kind declaration))
                                                         (not-any? #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                                                        (= "comptime" (get-in % [:properties :zig/prefix])))
                                                                   (:args declaration)))
                                :status (if probe? :unobserved :unsupported)
                                :reason (when-not probe? :inspection-placement)})]
          (swap! operations conj operation)
          (if-not (and probe? (or (nil? selected) (contains? selected id)))
            source
            (let [label (str "aguafria_operation_" id)
                  schemas (map (fn [index expression parameter argument-form]
                                 (str "(if (@TypeOf(" expression ") == type) \"{:comptime-type \" ++ __aguafria_probe.schema(" expression ") ++ \"}\" "
                                      "else if (@TypeOf(" expression ") == comptime_int or @TypeOf(" expression ") == comptime_float) "
                                      "__aguafria_probe.literal(" expression ") "
                                      "else if (@typeInfo(@TypeOf(" expression ")) == .enum_literal) "
                                      "__aguafria_probe.comptimeValue(" expression ") else "
                                      (if (or (= "comptime" (get-in parameter [:properties :zig/prefix]))
                                              (and (string? argument-form)
                                                   (get-in parameter [:properties :jvm/literal?])))
                                        (str "__aguafria_probe.comptimeValue(" expression ")")
                                        (str "__aguafria_probe."
                                             (if (and (zero? index) (or assignment constructor? address?))
                                               "schema" "argumentSchema")
                                             "(@TypeOf(" expression "))"))
                                      ") "))
                               (range) expressions (concat parameters (repeat nil))
                               (concat (rest form) (repeat nil)))
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
                  payload (str (pr-str (str "aguafria.operation:" id ":["))
                               (apply str (map #(str " ++ " % " ++ \" \"") schemas))
                               " ++ \"]\"")]
              (if place-probe
                (place-probe (str "__aguafria_probe.log(" payload ");") label)
                (str "(" (when-not noreturn? (str label ": "))
                     "{ __aguafria_probe.log(" payload "); "
                     (when-not noreturn?
                       (str "break :" label " "))
                     source "; })")))))))))

(defn- roots [declarations]
  ;; Referencing a container alone does not analyze its method bodies. Let Zig
  ;; enumerate concrete declarations recursively, preserving nominal identity
  ;; and leaving genuinely generic methods unspecialized. These roots are only
  ;; compiled with --test-no-exec; no constructor or method is invoked.
  (str "\nfn __aguafria_inspect_declarations(comptime T: type, comptime visited: anytype) void {\n"
       "    inline for (visited) |Seen| if (Seen == T) return;\n"
       "    const declarations = switch (@typeInfo(T)) {\n"
       "        .@\"struct\" => |info| info.decls, .@\"enum\" => |info| info.decls,\n"
       "        .@\"union\" => |info| info.decls, .@\"opaque\" => |info| info.decls, else => return,\n"
       "    };\n"
       "    inline for (declarations) |declaration| {\n"
       "        const D = @field(T, declaration.name);\n"
       "        if (@TypeOf(D) == type) {\n"
       "            __aguafria_inspect_declarations(D, visited ++ .{T});\n"
       "        } else if (@typeInfo(@TypeOf(D)) == .@\"fn\" and !@typeInfo(@TypeOf(D)).@\"fn\".is_generic) {\n"
       "            _ = &@field(T, declaration.name);\n"
       "        }\n"
       "    }\n"
       "}\n"
       "\ntest \"aguafria inspection roots\" {\n"
       (apply str
              (for [{:keys [kind name zig-name args]} declarations
                    :when (or (contains? #{:const :var :struct :enum :union :opaque} kind)
                              (and (= :fn kind)
                                   (not-any? #(or (contains? #{:type 'type :anytype 'anytype} (:type %))
                                                  (= "comptime" (get-in % [:properties :zig/prefix]))) args)))]
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

(defn- type-catalog [module declarations]
  (let [local (for [{:keys [name zig-name] :as declaration} declarations
                    :when (declared-type? declaration)]
                [(emitter/identifier (or zig-name name)) (str module "/" name)])
        imported (for [symbol (->> declarations (tree-seq coll? seq)
                                   (filter qualified-symbol?) distinct (sort-by str))
                       :let [v (some-> (find-ns (clojure.core/symbol (namespace symbol)))
                                       (ns-resolve (clojure.core/symbol (name symbol))))
                             reference (:aguafria/zig-reference (meta v))]
                       :when (and (:zig-name reference) (not= (str module) (namespace symbol)))]
                   (if (= :container (:category reference))
                     [(:zig-name reference) (str symbol)]
                     [(str "@TypeOf(" (:zig-name reference) ")")
                      (pr-str (list 'aguafria.keyword/TypeOf symbol))]))]
    ;; These are existing declaration identities, not inferred schemas. The
    ;; inspector compares the observed type with each identity inside Zig.
    (str/join ", "
              (map (fn [[expression name]] (str ".{ " expression ", " (pr-str name) " }"))
                   (concat local imported
                           [["@import(\"std\").builtin.Type"
                             "(aguafria.keyword/TypeOf (aguafria.keyword/typeInfo :u8))"]]
                           (for [expression *observed-type-identities*]
                             [(emitter/emit-expr (the-ns (symbol (str module))) expression)
                              (pr-str expression)]))))))

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

(defn- inspect-operations! [module selected]
  (let [operations (atom [])
        result (runtime/inspect-module!
                module
                (fn [declarations]
                  {:source (str "const __aguafria_probe = @import(\"operation_probe.zig\").Inspector(.{"
                                (type-catalog module declarations)
                                "});\n"
                                (binding [emitter/*expression-observer* (observer operations selected)]
                                  (emitter/emit-module module declarations))
                                (roots declarations))
                   :files {"operation_probe.zig" (slurp (io/resource "aguafria/operation_probe.zig"))}}))]
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

(defn analyze!
  "Inventory observable emitted operations in a registered namespace. A missing compiler
  observation stays unresolved; unsupported placements are reported explicitly.
  Native test/function bodies are compiled, never executed."
  [module]
  (binding [runtime/*compile-only?* true]
    (let [baseline (runtime/inspect-module!
                    module (fn [declarations]
                             {:source (str (emitter/emit-module module declarations) (roots declarations))}))
          initial (inspect-operations! module nil)
          identities (observed-type-identities initial)
          result (if (seq identities)
                   (binding [*observed-type-identities* identities]
                     (inspect-operations! module nil))
                   initial)
          {:keys [observed failures attempts]}
          (if (and (zero? (:exit baseline)) (compiler-errors? result))
            (binding [*observed-type-identities* identities]
              (isolate-probes! module result))
            {:observed (:observed result) :failures {} :attempts 1})]
      {:namespace (symbol (str module))
       :basis :zig-compiler
       :type-identities identities
       :compiler-mode :test
       :baseline {:exit (:exit baseline) :command (:command baseline)
                  :diagnostics (:err baseline) :source-path (:source-path baseline)}
       :command (:command result)
       :source-path (:source-path result)
       :diagnostics (:err result)
       :compiler-errors? (compiler-errors? result)
       :inspection-attempts (+ attempts (if (seq identities) 1 0))
       :probe-failures failures
       :operations
       (mapv (fn [operation]
               (if-let [types (get observed (:id operation))]
                 (-> operation
                     (assoc :status :observed :signatures (vec (sort-by pr-str types)))
                     (dissoc :reason))
                 (cond-> operation
                   (contains? failures (:id operation))
                   (assoc :status :inspection-failed :reason :compiler-rejected-probe))))
             (:operations result))})))

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
               (contains? #{:type :comptime_int :comptime_float} (:comptime-type argument))
               (and (= #{:tuple} (set (keys argument)))
                    (vector? (:tuple argument))
                    (every? supported-argument? (:tuple argument)))))))

(declare signature-variants)

(defn- argument-variants [argument]
  (cond
    (and (map? argument) (= #{:representations} (set (keys argument))))
    (mapcat argument-variants (:representations argument))

    (and (map? argument) (= #{:tuple} (set (keys argument))))
    (map #(hash-map :tuple %) (signature-variants (:tuple argument)))

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
        prepare-storage (requiring-resolve 'aguafria.zig.jvm/precompile-storage!)
        prepare-method (requiring-resolve 'aguafria.zig.jvm/precompile-method!)
        prepare-assignment (requiring-resolve 'aguafria.zig.jvm/precompile-assignment!)
        prepare-literal (requiring-resolve 'aguafria.zig.jvm/precompile-source-literal!)
        prepared (atom {})]
    (assoc report :functions (prepare-declared-functions! module)
           :operations
           (mapv
            (fn [{:keys [status signatures constructor? literal-constructor? constructor-value conversion? contextual-input? requires-result-context? concrete-function? function storage-kind member assignment literal-arguments method-call?] :as operation}]
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
                                      {:status :unsupported :reason :result-context-required :types types}
                                      (not (or concrete-function? literal-arguments
                                               (every? supported-argument? input-types)))
                                      {:status :unsupported :reason :non-runtime-or-nominal-type :types types}
                                      :else
                                      (let [key [constructor? constructor-value function types storage-kind member literal-arguments method-call?]]
                                        (or (get @prepared key)
                                            (let [result (try
                                                           (cond
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
                                                                               :address (second types) :member member
                                                                               :indices (vec (drop 2 types))})
                                                             (and conversion? (structural-type? (second types)))
                                                             (prepare-conversion (second types) (first types))
                                                             constructor?
                                                             (cond-> (if (and literal-constructor?
                                                                              (or (seq? (first types))
                                                                                  (native-literal? constructor-value)))
                                                                       (prepare-literal-type (first types) constructor-value)
                                                                       (prepare-type (first types)))
                                                               contextual-input?
                                                               (assoc :status :partial :reason :result-context-required))
                                                             concrete-function? (runtime/precompile-function! function)
                                                             :else (prepare-call {:function function :args types}))
                                                           (catch Exception error
                                                             (assoc (error-report error) :status :failed)))]
                                              (swap! prepared assoc key result)
                                              result)))))) prepared-signatures)
                           limited? (conj {:status :partial
                                           :reason :representation-limit
                                           :prepared-variant-limit max-representation-variants}))))))
            (:operations report)))))
