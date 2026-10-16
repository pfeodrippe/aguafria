(ns aguafria.zig.check-worker
  "Internal fresh-process worker for aguafria.zig.check. No live REPL changes."
  (:require [aguafria.zig.compiler-work :as work]
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

(defn- matches? [expected observation]
  (every? (fn [[key v]]
            (= (if (= :value key) (pr-str v) v) (get observation key))) expected))

(defn- prepare! [job]
  (require 'aguafria.zig)
  (let [source-only (requiring-resolve 'aguafria.zig.runtime/*source-only-registration?*)
        compile-only (requiring-resolve 'aguafria.zig.runtime/*compile-only?*)]
    (with-bindings {source-only true compile-only true}
      (doseq [n (:namespaces job)] (require n))))
  (let [tests (into [] (keep (fn [{:keys [call]}]
                               (when (= :test (get-in (meta (find-var call)) [:aguafria/declaration :kind])) call)))
                    (:scenarios job))
        report ((requiring-resolve 'aguafria.zig/precompile!)
                (merge {:analyze (:namespaces job) :tests tests
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
  (let [{:keys [call args expect]} (:scenario job)
        _ (require (symbol (namespace call)))
        v (or (find-var call) (throw (ex-info "Unknown scenario callable" {:call call})))
        _ (when-not (ifn? @v) (throw (ex-info "Scenario Var is not callable" {:call call})))
        output (java.io.StringWriter.) errors (java.io.StringWriter.)
        journal-file (io/file (.getParentFile (io/file (:result-file job))) "native-events.ednlog")
        journal! (fn [event]
                   (locking events
                     (spit journal-file (str (pr-str {:stage @stage :event (:event event)}) "\n") :append true)))
        cache-reporter #(when (#{:compiled :compile-failed :disk-cache-hit} (:event %))
                          (swap! events conj %) (journal! %))
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
        observation (assoc (select-keys result [:value :status :error-name])
                           :stdout (str output) :stderr (str errors))
        forbidden (vec (filter #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) @events))
        evaluation-attempts (vec (filter #(= :evaluate (:stage %)) @attempts))
        accepted? (and (not= :failed (:status result))
                       (matches? expect observation)
                       (or (= :on-demand (:compilation job))
                           (and (empty? forbidden) (empty? evaluation-attempts))))]
    {:status (cond (not accepted?) :failed
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
                 (when (and (= :prepared (:compilation job)) (= :evaluate @stage))
                   (throw (ex-info "Strict prepared consumer attempted compilation"
                                   {:aguafria/phase :check-compiler-forbidden :command command}))))
        _ (alter-var-root #'work/*collector* (constantly collector))
        _ (alter-var-root #'work/*compiler-command-policy* (constantly policy))
        result
        (binding [work/*collector* collector work/*compiler-command-policy* policy]
          (try
            (case (:phase job)
              :prepare (do (reset! stage :prepare) (prepare! job))
              :consume (consume! job stage attempts events))
            (catch Throwable e
              {:status :failed :accepted? false :failure (Throwable->map e)})))
        result (assoc result :compiler-attempts @attempts :compiler-work (work/report collector))]
    (spit (:result-file job) (pr-str (receipt-data result)))
    (shutdown-agents)))
