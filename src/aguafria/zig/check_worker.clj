(ns aguafria.zig.check-worker
  "Internal fresh-process worker for aguafria.zig.check. No live REPL changes."
  (:require [aguafria.zig.compiler-work :as work]
            [aguafria.zig.explain :as explain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- receipt-data [x]
  ;; Never print/realize foreign application objects while writing diagnostics.
  (cond
    (or (nil? x) (string? x) (number? x) (keyword? x) (symbol? x)
        (char? x) (boolean? x)) x
    (map? x) (into {} (map (fn [[k v]] [(receipt-data k) (receipt-data v)])) x)
    (vector? x) (mapv receipt-data x)
    (set? x) (set (map receipt-data x))
    (list? x) (apply list (map receipt-data x))
    :else {:opaque-class (.getName (class x))}))

(defn- exception-observation [failure stage]
  {:stage stage :type (:type (last (:via failure)))
   :message (:cause failure) :data (:data failure)})

(defn- policy-failure? [failure]
  (some #(= :check-compiler-forbidden (get-in % [:data :aguafria/phase])) (:via failure)))

(defn- matches? [expected observation]
  (every? (fn [[key v]]
            (if (= :exception key)
              (let [actual (:exception observation)]
                (and (= (:stage v) (:stage actual))
                     (= (:type v) (:type actual))
                     (string? (:message actual))
                     (re-find (re-pattern (:message-pattern v)) (:message actual))
                     (every? (fn [[k value]]
                               (and (contains? (:data actual) k)
                                    (= value (get-in actual [:data k])))) (:data v))))
              (= (if (= :value key) (pr-str v) v) (get observation key)))) expected))

(defn- prepare! [job]
  (require 'aguafria.zig)
  (when-let [configuration (:compiler-configuration job)]
    ((requiring-resolve 'aguafria.zig/configure!) configuration))
  ;; The producer must own first loading: a bootstrap require here would hide
  ;; intermediate test-definition snapshots from compile-only collection.
  ;; Resolve candidate entry kinds only after loading inside precompile!.
  (let [report ((requiring-resolve 'aguafria.zig/precompile!)
                (merge {:analyze (:namespaces job)
                        :test-candidates (mapv :call (:scenarios job))
                        :report-file (str (io/file (:cache-dir job) "preparation.edn"))}
                       (:precompile-options job)))]
    {:status :prepared :accepted? true :preparation-report report}))

(defn- native-error-name [raw decoded]
  (let [zig-error? (requiring-resolve 'aguafria.zig.value/zig-error?)
        zig-value? (requiring-resolve 'aguafria.zig.value/zig-value?)]
    (cond
      (zig-error? raw) (some-> raw :name keyword)
      (zig-value? raw)
      (case (get-in ((requiring-resolve 'aguafria.zig.value/realize!) raw) [:schema :kind])
        :error-union (some-> decoded :error :name keyword)
        :error-set (some-> decoded :name keyword)
        nil))))

(defn- consume! [job stage attempts events]
  (require 'aguafria.zig 'aguafria.zig.explain)
  (when-let [configuration (:compiler-configuration job)]
    ((requiring-resolve 'aguafria.zig/configure!) configuration))
  (let [{:keys [call args expect]} (:scenario job)
        _ (require (symbol (namespace call)))
        _ (doseq [path (get-in job [:scenario :load-files])]
            (binding [*ns* *ns*]
              (with-open [reader (io/reader path)]
                (clojure.lang.Compiler/load reader path (.getName (io/file path))))))
        v (or (find-var call) (throw (ex-info "Unknown scenario callable" {:call call})))
        _ (when-not (ifn? @v) (throw (ex-info "Scenario Var is not callable" {:call call})))
        output (java.io.StringWriter.) errors (java.io.StringWriter.)
        ;; The process-wide event hook installed by -main also observes events
        ;; inside explain!, which intentionally replaces its dynamic reporter.
        cache-reporter (fn [_] nil)
        _ (doseq [[v root] {(requiring-resolve 'aguafria.zig.explain/*reporter*) cache-reporter
                            #'*out* output #'*err* errors}]
            ;; Only this dedicated worker is affected, including newly created
            ;; threads that do not convey Clojure dynamic bindings.
            (alter-var-root v (constantly root)))
        result
        (do
          (reset! stage :evaluate)
          (try
            (with-bindings {(requiring-resolve 'aguafria.zig.explain/*reporter*) cache-reporter
                            #'*out* output #'*err* errors}
              (let [raw (apply @v (or args []))
                    ;; Printing/decoding is part of evaluation, not post-audit work.
                    decoded ((requiring-resolve 'aguafria.zig/value) raw)
                    error-name (native-error-name raw decoded)
                    test? (= :test (get-in (meta v) [:aguafria/declaration :kind]))]
                (cond-> {:status (if error-name :returned-error (if test? (:status raw) :completed))
                         :value (pr-str (cond test? (select-keys raw [:test :status])
                                              error-name {:error-name error-name}
                                              :else decoded))
                         :raw-value (pr-str decoded)}
                  error-name (assoc :error-name error-name))))
            (catch Throwable e
              {:status :failed
               :exception (Throwable->map e)
               :error-name (:error-name (ex-data e))})))
        observation (cond-> (assoc (select-keys result [:value :status :error-name])
                                    :stdout (str output) :stderr (str errors))
                      (:exception result)
                      (assoc :exception (exception-observation (:exception result) @stage)))
        forbidden (vec (filter #(and (or (:strict-load? job) (= :evaluate (:stage %)))
                                     (#{:compiled :compile-failed :disk-cache-hit} (:event %))) @events))
        evaluation-attempts (vec (filter #(= :evaluate (:stage %)) @attempts))
        forbidden-attempts (if (:strict-load? job) @attempts evaluation-attempts)
        accepted? (and (or (not= :failed (:status result)) (:exception expect))
                       (not (policy-failure? (:exception result)))
                       (matches? expect observation)
                       (or (= :on-demand (:compilation job))
                           (and (empty? forbidden) (empty? forbidden-attempts))))]
    {:status (cond (not accepted?) :failed
                   (:exception result) :expected-exception
                   (= :returned-error (:status result)) :expected-error
                   (= :skipped (:status result)) :skipped
                   :else :passed)
     :accepted? (boolean accepted?) :observation observation
     :failure (:exception result) :raw-value (:raw-value result)
     :evaluation-compiler-attempts evaluation-attempts
     :forbidden-evaluation-events forbidden}))

(defn -main [request]
  (let [job (edn/read-string (slurp request))
        stage (atom :load) attempts (atom []) events (atom []) collector (work/collector)
        audit-file (io/file (.getParentFile (io/file request)) "compiler-attempts.ednlog")
        policy (fn [command]
                 (let [event {:stage @stage :command command}]
                   (swap! attempts conj event)
                   (locking attempts (spit audit-file (str (pr-str event) "\n") :append true)))
                 (when (and (= :prepared (:compilation job))
                            (or (:strict-load? job) (= :evaluate @stage)))
                   (throw (ex-info "Strict prepared consumer attempted compilation"
                                   {:aguafria/phase :check-compiler-forbidden :command command}))))
        original-event! explain/event!
        _ (when (= :consume (:phase job))
            (alter-var-root
             #'explain/event!
             (constantly
              (fn [event]
                (let [entry (assoc (select-keys event [:event :module :function :path :bundle-id :duration-ms
                                                     :artifact-key :recipe-id :rejection-id])
                                   :stage @stage)]
                  (swap! events conj entry)
                  (locking events
                    (spit (io/file (.getParentFile (io/file request)) "all-native-events.ednlog")
                          (str (pr-str entry) "\n") :append true)
                    (when (#{:compiled :compile-failed :disk-cache-hit} (:event event))
                      (spit (io/file (.getParentFile (io/file request)) "native-events.ednlog")
                            (str (pr-str (select-keys entry [:stage :event])) "\n") :append true))))
                (original-event! event))))
            (alter-var-root #'explain/*reporter* (constantly (fn [_] nil))))
        _ (alter-var-root #'work/*collector* (constantly collector))
        _ (alter-var-root #'work/*compiler-command-policy* (constantly policy))
        result
        (binding [work/*collector* collector work/*compiler-command-policy* policy]
          (try
            (case (:phase job)
              :prepare (do (reset! stage :prepare) (prepare! job))
              :consume (consume! job stage attempts events))
            (catch Throwable e
              (let [failure (Throwable->map e)
                    observation {:status :failed
                                 :exception (exception-observation failure @stage)}
                    expected (get-in job [:scenario :expect])
                    accepted? (and (= :consume (:phase job)) (:exception expected)
                                   (not (policy-failure? failure))
                                   (matches? expected observation)
                                   (or (= :on-demand (:compilation job))
                                       (and (empty? @attempts)
                                            (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) @events))))]
                {:status (if accepted? :expected-exception :failed)
                 :accepted? (boolean accepted?) :observation observation
                 :failure-stage @stage :failure failure}))))
        result (assoc result :compiler-attempts @attempts :compiler-work (work/report collector))]
    (spit (:result-file job) (pr-str (receipt-data result)))
    (shutdown-agents)))
