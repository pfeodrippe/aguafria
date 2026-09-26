(ns learn.jvm-audit
  "Source-driven JVM checks, independent of the documentation's entry-point calls.
  Run a batch in a fresh owned JVM: native globals intentionally survive reload."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.util.concurrent Callable Executors ExecutionException Future]))

(defn parallel-inventory
  "Bound independent source reads/plans on virtual threads. Native evaluation
  deliberately stays ordered: globals, test allocators and file descriptors
  are shared process state, and TLS additionally depends on the carrier thread."
  [jobs f inputs]
  (when-not (and (integer? jobs) (<= 1 jobs 32))
    (throw (ex-info "Audit jobs must be between 1 and 32" {:jobs jobs})))
  (with-open [executor (Executors/newFixedThreadPool jobs (.factory (Thread/ofVirtual)))]
    (let [tasks (mapv #(.submit executor ^Callable (bound-fn [] (f %))) inputs)]
      (try
        (mapv #(.get ^Future %) tasks)
        (catch ExecutionException error
          (doseq [task tasks] (.cancel ^Future task true))
          (throw (.getCause error)))))))

(defn read-forms [source]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                     (java.io.StringReader. source))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (try
                     (read {:eof ::eof} reader)
                     (catch Throwable error
                       (throw (ex-info "Cannot read the complete authored example"
                                       {:forms forms :line (.getLineNumber reader)}
                                       error))))]
          (if (= ::eof form) forms (recur (conj forms form))))))))

(defn- operator [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn- probe [kind declaration form context path]
  {:kind kind :declaration declaration :path path
   :line (:line (meta form)) :column (:column (meta form))
   :expression form :form (context form)})

(declare inner-probes)

(defn- context-probes
  "Keep dependent syntax visible without guessing its missing bindings or type."
  [declaration form path status]
  (cons {:kind :subexpression :declaration declaration :path path
         :line (:line (meta form)) :column (:column (meta form))
         :expression form :status status}
        (mapcat (fn [[index child]]
                  (when (or (coll? child) (symbol? child))
                    (context-probes declaration child (conj path index) status)))
                (map-indexed vector
                             (cond
                               (map? form) (mapcat identity form)
                               (seq? form) (rest form)
                               (coll? form) form
                               :else [])))))

(defn- body-probes [declaration body context path]
  (vec
   (mapcat
    (fn [index]
      (let [form (nth body index)
            preceding (take index body)
            wrap #(context (cons 'do (concat preceding [%])))
            location (conj path index)]
        (cons (probe :body-expression declaration form wrap location)
              (inner-probes declaration form wrap location))))
    (range (count body)))))

(defn- inner-probes [declaration form context path]
  (cond
    (= "do" (operator form))
    (body-probes declaration (vec (rest form)) context (conj path :body))

    ;; A native block owns defer/break scope. Preserve the block around probes;
    ;; descendants still requiring that scope are inventoried below, not run.
    (= "block" (operator form))
    (body-probes declaration (vec (rest form))
                 #(context (list (first form) %)) (conj path :body))

    (and (= "let" (operator form)) (vector? (second form))
         (even? (count (second form))))
    (let [bindings (second form)
          body (vec (drop 2 form))]
      (concat
       (mapcat
        (fn [index]
          (let [initializer (nth bindings (inc index))
                wrap #(context (list 'let (subvec bindings 0 index) %))
                location (conj path :bindings index)]
            (concat
             [(probe :local-initializer declaration initializer wrap location)]
             (when (symbol? (nth bindings index))
               [(probe :local-value declaration (nth bindings index)
                       #(context (list 'let (subvec bindings 0 (+ index 2)) %))
                       (conj location :value))])
             (inner-probes declaration initializer wrap location))))
        (range 0 (count bindings) 2))
       (body-probes declaration body #(context (list 'let bindings %))
                    (conj path :body))))

    ;; Do not execute unselected branches, loop iterations, declaration forms,
    ;; quoted syntax or calls stripped of their lexical/control-flow context.
    ;; Inventory them explicitly rather than misreporting them as verified.
    (and (seq? form)
         (contains? #{"if" "if-not" "when" "when-not" "cond" "case"
                      "for" "doseq" "dotimes" "loop" "fn" "fn-"
                      "with-block" "while" "while-loop" "switch" "switch-stmt"
                      "if-capture" "if-capture-stmt" "while-capture" "inline-for"
                      "catch-capture" "catch-expr" "catch" "finally" "try"
                      "comptime" "comptime-stmt" "quote" "defer" "errdefer"
                      "and" "or" "orelse" "return" "break" "continue"
                      "struct" "union" "enum" "container" "type" "asm"
                      "->" "->>" "some->" "some->>" "cond->" "cond->>"
                      "as->" "doto" "with-open" "binding" "letfn"
                      "when-let" "if-let" "when-some" "if-some"}
                    (operator form)))
    (context-probes declaration form path :requires-control-context)

    (and (= "let" (operator form)) (vector? (second form)))
    (context-probes declaration form path :invalid-binding-context)

    (coll? form)
    (mapcat (fn [[index child]]
              (when (or (coll? child) (symbol? child))
                (let [location (conj path index)]
                  (cons (probe :subexpression declaration child context location)
                        (inner-probes declaration child context location)))))
            (map-indexed vector (cond
                                  (map? form) (vals form)
                                  (and (seq? form) (coll? (first form))) form
                                  (seq? form) (rest form)
                                  :else form)))

    :else nil))

(defn plan
  "Inventory authored declarations and direct JVM forms. No source translation,
  wrapper az/defn, native file runner, or guessed function arguments are used."
  [source]
  (let [{:keys [forms read-error]}
        (try {:forms (read-forms source)}
             (catch Throwable error
               {:forms (:forms (ex-data error))
                :read-error {:kind :source-read :status :source-read-failed
                             :line (:line (ex-data error))
                             :message (some-> error .getCause ex-message)}}))]
    {:namespace (second (first forms))
     :cases
     (vec
      (concat
       (mapcat
       (fn [[index form]]
         (let [op (operator form)
               declaration (second form)
               tail (drop 2 form)
               base (when (str/starts-with? (or op "") "def")
                      [(probe :var declaration declaration identity [index])])]
           (concat
            base
            (case op
              ("defconst" "defvar")
              (cons (probe :initializer declaration (last form) identity [index :initializer])
                    (inner-probes declaration (last form) identity [index :initializer]))

              ("deftest" "defcomptime")
              (concat
               (when (= op "deftest")
                 [(probe :native-test declaration (list declaration) identity [index :call])])
               (body-probes declaration (vec (drop-while #(or (string? %) (map? %)) tail))
                            identity [index :body]))

              ("defn" "defn-")
              (let [[arguments & body] (drop-while #(not (vector? %)) (rest tail))]
                (if (empty? arguments)
                  (body-probes declaration (vec body) identity [index :body])
                  (cons {:kind :function-body :declaration declaration :path [index :body]
                         :status :requires-arguments :arguments arguments :expression form}
                        (mapcat (fn [[body-index expression]]
                                  (context-probes declaration expression
                                                  [index :body body-index]
                                                  :requires-arguments))
                                (map-indexed vector body)))))

              ("defstruct" "defenum" "defunion" "defextern" "defimport")
              (mapcat (fn [[part-index part]]
                        (when (coll? part)
                          (context-probes declaration part [index :declaration part-index]
                                          :requires-declaration-context)))
                      (map-indexed vector tail))

              "comment"
              (map #(cond-> %
                      (and (= :body-expression (:kind %))
                           (= 3 (count (:path %)))) (assoc :kind :comment-call))
                   (body-probes nil (vec (rest form)) identity [index :comment]))

              nil))))
        (map-indexed vector (rest forms)))
       (when read-error [read-error])))}))

(defn corpus []
  (let [overrides (edn/read-string (slurp (io/resource "learn/overrides.edn")))]
    (->> overrides
         (keep (fn [[zig {:keys [source]}]]
                 (when source
                   {:zig zig :source source})))
         (sort-by :zig)
         vec)))

(defn execution-policy [zig]
  (let [source (slurp (io/file "resources/upstream/doc/langref" zig))]
    (cond
      (re-find #"(?m)^// target=" source) :target-specific
      (re-find #"(?m)^// (?:exe=fail|test_safety=)" source) :requires-disposable-jvm
      ;; A custom panic handler can explicitly exit instead of reaching the
      ;; native panic guard; do not execute it in the shared audit process.
      (re-find #"(?:process|posix)\.exit\(" source) :requires-disposable-jvm
      :else :host)))

(defn upstream-expectation [zig]
  (let [source (slurp (io/file "resources/upstream/doc/langref" zig))]
    (cond
      (re-find #"(?m)^// (?:exe|test)=build_fail" source) :compile-failure
      (re-find #"(?m)^// test_error=" source) :test-failure
      (re-find #"(?m)^// (?:exe=fail|test_safety=)" source) :runtime-failure
      :else :success-or-context)))

(defn error-details [error]
  (let [chain (take-while some? (iterate ex-cause error))
        data (apply merge (map ex-data (reverse chain)))]
    {:error (.getName (class error))
     :message (ex-message error)
     :cause (some-> error ex-cause ex-message)
     :phase (or (:aguafria/phase data) (:clojure.error/phase data))
     :location (select-keys data [:clojure.error/source :clojure.error/line
                                 :clojure.error/column])
     :native-state (:native-state data)}))

(defn- evaluate [namespace case invoke]
  (let [stdout (java.io.StringWriter.)
        stderr (java.io.StringWriter.)
        started (System/nanoTime)]
    (merge
     (dissoc case :status)
     (binding [*ns* (the-ns namespace) *out* stdout *err* stderr]
       (try
         ;; Printing forces lazy results and the ZigValue materialization path.
         ;; Real errors are recorded, not suppressed or counted as passes.
         {:status :passed :printed-value (pr-str (invoke))}
         (catch Throwable error
           (assoc (error-details error) :status :failed))))
     {:stdout (str stdout) :stderr (str stderr)
      :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})))

(defn audit-example!
  "Evaluate declarations through ns-interns, then exact authored JVM forms.
  `:kinds` limits execution, never inventory: other rows remain :not-run."
  [{:keys [zig source inventory]}
   {:keys [kinds disposable? start-index on-case stop-after-panic?]
    :or {kinds #{:var :initializer} start-index 0 on-case (constantly nil)}}]
  (let [{:keys [namespace cases]} (or inventory (plan (slurp (io/resource source))))
        policy (execution-policy zig)
        load-result (if (or (= policy :host)
                            (and disposable? (= policy :requires-disposable-jvm)))
                      (try (require namespace)
                           {:status :loaded}
                           (catch Throwable error
                             (assoc (error-details error) :status :load-failed
                                    :partial-vars (some-> namespace find-ns ns-interns keys vec))))
                      {:status policy})
        interns (when (= :loaded (:status load-result)) (ns-interns namespace))]
    (on-case {:phase :loaded :load load-result})
    {:zig zig :namespace namespace :source source :load load-result
     :upstream-expectation (upstream-expectation zig)
     :cases
     (mapv
      (fn [[index {:keys [kind declaration form status] :as case}]]
        (on-case {:phase :evaluating :index index :case case})
        (let [result (cond
          (not= :loaded (:status load-result))
          (assoc case :status :blocked-by-load :reason (:status load-result))
          status case
          (not (contains? kinds kind)) (assoc case :status :not-run)
          (= kind :var)
          (evaluate namespace case
                    #(if-let [v (get interns declaration)]
                       (var-get v)
                       (throw (ex-info "Authored declaration has no JVM Var"
                                       {:declaration declaration}))))
          :else (evaluate namespace case #(eval form)))]
          (on-case {:phase :evaluated :index index :case result})
          (when (and stop-after-panic?
                     (or (= :native-panic (:phase result))
                         (= :potentially-inconsistent (:native-state result))))
            (throw (ex-info "Audit worker must restart after native panic"
                            {:audit/restart true :index index})))
          result))
      (drop start-index (map-indexed vector cases)))}))

(defn run!
  "Audit a batch and write an EDN report with every inventoried case/status.
  Use :files for a focused batch and :kinds to enable inner-form/test probes."
  [{:keys [files report jobs] :as options
    :or {report "build/jvm-audit.edn" jobs 4}}]
  (let [started (System/nanoTime)
        examples (cond->> (corpus) files (filter #(contains? (set files) (:zig %))))
        planned (parallel-inventory jobs
                                    #(assoc % :inventory (plan (slurp (io/resource (:source %)))))
                                    examples)
        results (mapv (fn [example]
                        (let [result (audit-example! example options)]
                          (println (:zig result) (frequencies (map :status (:cases result))))
                          (flush)
                          result)) planned)
        summary (frequencies (map :status (mapcat :cases results)))
        result {:summary summary :examples results :inventory-jobs jobs
                :native-execution :serial
                :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}]
    (io/make-parents report)
    ;; pprint's code dispatch abbreviates (deref x) as @x, which is not EDN.
    (spit report (pr-str result))
    (assoc (select-keys result [:summary]) :report report)))
