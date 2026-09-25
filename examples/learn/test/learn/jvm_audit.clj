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
        (let [form (read {:eof ::eof} reader)]
          (if (= ::eof form) forms (recur (conj forms form))))))))

(defn- operator [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn- probe [kind declaration form context path]
  {:kind kind :declaration declaration :path path
   :line (:line (meta form)) :column (:column (meta form))
   :expression form :form (context form)})

(declare inner-probes)

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
                      "labeled-block" "while" "switch" "quote" "defer"
                      "->" "->>" "some->" "some->>" "cond->" "cond->>"
                      "as->" "doto"}
                    (operator form)))
    [{:kind :subexpression :declaration declaration :path path
      :expression form :status :requires-control-context}]

    (or (seq? form) (vector? form))
    (mapcat (fn [[index child]]
              (when (coll? child)
                (let [location (conj path index)]
                  (cons (probe :subexpression declaration child context location)
                        (inner-probes declaration child context location)))))
            (map-indexed vector (if (seq? form) (rest form) form)))

    :else nil))

(defn plan
  "Inventory authored declarations and direct JVM forms. No source translation,
  wrapper az/defn, native file runner, or guessed function arguments are used."
  [source]
  (let [forms (read-forms source)]
    {:namespace (second (first forms))
     :cases
     (vec
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
                  [{:kind :function-body :declaration declaration :path [index :body]
                    :status :requires-arguments :arguments arguments :expression form}]))

              nil))))
       (map-indexed vector (rest forms))))}))

(defn corpus []
  (let [overrides (edn/read-string (slurp (io/resource "learn/overrides.edn")))]
    (->> overrides
         (keep (fn [[zig {:keys [source]}]]
                 (when source
                   {:zig zig :source source})))
         (sort-by :zig)
         vec)))

(defn- execution-policy [zig]
  (let [source (slurp (io/file "resources/upstream/doc/langref" zig))]
    (cond
      (re-find #"(?m)^// target=" source) :target-specific
      (re-find #"(?m)^// (?:exe=fail|test_safety=)" source) :requires-disposable-jvm
      ;; A custom panic handler can explicitly exit instead of reaching the
      ;; native panic guard; do not execute it in the shared audit process.
      (re-find #"(?:process|posix)\.exit\(" source) :requires-disposable-jvm
      :else :host)))

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
           {:status :failed :error (.getName (class error))
            :message (ex-message error)
            :cause (some-> error .getCause ex-message)})))
     {:stdout (str stdout) :stderr (str stderr)
      :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})))

(defn audit-example!
  "Evaluate declarations through ns-interns, then exact authored JVM forms.
  `:kinds` limits execution, never inventory: other rows remain :not-run."
  [{:keys [zig source inventory]} {:keys [kinds] :or {kinds #{:var :initializer}}}]
  (let [{:keys [namespace cases]} (or inventory (plan (slurp (io/resource source))))
        policy (execution-policy zig)
        load-result (if (= policy :host)
                      (try (require namespace)
                           {:status :loaded}
                           (catch Throwable error
                             {:status :load-failed :message (ex-message error)
                              :cause (some-> error .getCause ex-message)}))
                      {:status policy})
        interns (when (= :loaded (:status load-result)) (ns-interns namespace))]
    {:zig zig :namespace namespace :source source :load load-result
     :cases
     (mapv
      (fn [{:keys [kind declaration form status] :as case}]
        (cond
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
          :else (evaluate namespace case #(eval form))))
      cases)}))

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
