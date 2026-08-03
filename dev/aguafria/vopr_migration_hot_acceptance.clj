(ns aguafria.vopr-migration-hot-acceptance
  "Opt-in acceptance for breaking callable adoption and real VOPR state migration."
  (:require [aguafria.zig :as az]
            [aguafria.zig.host :as host]
            [aguafria.zig.runtime :as runtime]
            [clojure.walk :as walk]))

(def ^:private vopr-module 'tigerbeetle.src.vopr)

(defn- named-symbol?
  [value expected]
  (and (symbol? value) (= expected (name value))))

(defn- call?
  [value expected]
  (and (seq? value) (named-symbol? (first value) expected)))

(defn- registered-declaration
  [module declaration-name]
  (or (some #(when (= declaration-name (str (:name %))) %)
            (:definitions (runtime/module-info module)))
      (throw (ex-info "Converted VOPR declaration was not registered"
                      {:module module :declaration declaration-name}))))

(defn- changed-full-core
  [declaration]
  (runtime/declaration-info
   (-> declaration
       (update :args conj {:name 'aguafria_migration_generation
                           :type :u32})
       (update :body #(vec (cons '(assert (<= aguafria_migration_generation 1))
                                 %))))))

(defn- changed-state
  [declaration]
  (runtime/declaration-info
   (assoc declaration
          :type 'AguafriaLogPerformanceState
          :value '(aguafria.zig/object
                   [[:enabled false] [:migration_generation 0]]))))

(defn- rewrite-main
  [declaration]
  (let [full-core-calls
        (count (filter #(call? % "full_core")
                       (tree-seq coll? seq (:body declaration))))
        state-writes
        (count
         (filter #(and (call? % "set!")
                       (named-symbol? (second %) "log_performance_mode"))
                 (tree-seq coll? seq (:body declaration))))]
    (when-not (= [2 1] [full-core-calls state-writes])
      (throw (ex-info "VOPR main no longer has the expected migration sites"
                      {:full-core-calls full-core-calls
                       :state-writes state-writes})))
    (runtime/declaration-info
     (update
      declaration :body
      #(walk/postwalk
        (fn [value]
          (cond
            (and (call? value "full_core") (= 3 (count value)))
            (apply list
                   (concat value
                           ['(aguafria.zig/field
                              log_performance_mode migration_generation)]))

            (and (call? value "set!")
                 (= 3 (count value))
                 (named-symbol? (second value) "log_performance_mode"))
            (list (first value)
                  '(aguafria.zig/field log_performance_mode enabled)
                  (nth value 2))

            :else value))
        %)))))

(defn- rewrite-log-override
  [declaration]
  (let [conditions
        (count
         (filter #(and (call? % "if")
                       (named-symbol? (second %) "log_performance_mode"))
                 (tree-seq coll? seq (:body declaration))))]
    (when-not (= 1 conditions)
      (throw (ex-info "VOPR log_override no longer has the expected state read"
                      {:conditions conditions})))
    (runtime/declaration-info
     (update
      declaration :body
      #(walk/postwalk
        (fn [value]
          (if (and (call? value "if")
                   (named-symbol? (second value) "log_performance_mode"))
            (apply list
                   (first value)
                   '(aguafria.zig/field log_performance_mode enabled)
                   (drop 2 value))
            value))
        %)))))

(defn- capture-declaration
  [form]
  (let [captured (atom [])]
    (binding [*ns* (the-ns vopr-module)
              runtime/*registration-batch* captured]
      (eval form))
    (or (first @captured)
        (throw (ex-info "Aguafria form captured no declaration" {:form form})))))

(defn- migration-declaration
  []
  (capture-declaration
   '(az/defn aguafria_migrate_log_performance_mode
      :- :void
      [old_address :- :usize new_address :- :usize]
      (ak/const old_value [:*const :bool] (ak/ptrFromInt old_address))
      (ak/const new_value [:* AguafriaLogPerformanceState]
        (ak/ptrFromInt new_address))
      (set! (az/field (az/deref new_value) enabled) (az/deref old_value))
      (set! (az/field (az/deref new_value) migration_generation) 1))))

(defn- running?
  [handle]
  (contains? #{:starting :running} (:status (host/info handle))))

(defn- phase
  [error]
  (:aguafria/phase (ex-data error)))

(defn -main
  [& arguments]
  (let [requests (or (first arguments) "2000")
        seed (or (second arguments) "1")
        old-config (az/configuration)
        pid (.pid (java.lang.ProcessHandle/current))]
    (try
      (az/configure! {:async? true})
      (require vopr-module :reload)
      (az/await! vopr-module)
      (let [original-main (registered-declaration vopr-module "main")
            original-full-core (registered-declaration vopr-module "full_core")
            original-log-override
            (registered-declaration vopr-module "log_override")
            original-state
            (registered-declaration vopr-module "log_performance_mode")
            full-core-v2 (changed-full-core original-full-core)
            state-v2 (changed-state original-state)
            handle
            (host/start! (ns-resolve vopr-module 'main)
                         ["--performance" (str "--requests-max=" requests) seed]
                         {:argv0 "vopr"})]
        (when-not (running? handle)
          (throw (ex-info "Converted VOPR did not enter its native run"
                          {:host (host/info handle)})))

        ;; This new type is an ordinary declaration in the real converted VOPR
        ;; module. The running old host has no dependency on it yet.
        (binding [*ns* (the-ns vopr-module)]
          (eval
           '(az/defstruct AguafriaLogPerformanceState
              [[:enabled :bool] [:migration_generation :u32]])))
        (az/await! vopr-module)

        ;; Request the breaking state schema while VOPR is running. The native
        ;; compiler prepares it, refuses unsafe reinterpretation, and leaves
        ;; the old host/state capsule untouched until explicit migration.
        (runtime/register-declaration! state-v2)
        (let [migration-required
              (try
                (az/await! vopr-module)
                nil
                (catch clojure.lang.ExceptionInfo error error))]
          (when-not (= :zig-state-migration-required
                       (phase migration-required))
            (throw (ex-info "VOPR state edit did not stop for migration"
                            {:phase (phase migration-required)}
                            migration-required))))
        (when-not (running? handle)
          (throw (ex-info "VOPR exited during the pending state migration"
                          {:host (host/info handle)})))

        ;; Publish a genuinely breaking signature for a function used by main.
        ;; Existing main and the running host retain full_core@v1.
        (runtime/register-declaration! full-core-v2)
        (az/await! vopr-module)
        (let [versions (az/function-versions
                        (symbol (str vopr-module) "full_core"))]
          (when-not (and (= 2 (count versions))
                         (= 1 (count (filter :current? versions)))
                         (= 2 (count (set (map :abi-fingerprint versions)))))
            (throw (ex-info "full_core did not retain both ABI generations"
                            {:versions versions}))))
        (when-not (running? handle)
          (throw (ex-info "VOPR exited during the signature publication"
                          {:host (host/info handle)})))

        (let [first-result (host/await! handle)
              main-v2 (rewrite-main original-main)
              log-override-v2 (rewrite-log-override original-log-override)
              migration (migration-declaration)]
          ;; Apply the mutually dependent source edits as one quiescent source
          ;; snapshot, then authorize the exact old->new state schema edge.
          (runtime/register-batch!
           [main-v2 log-override-v2 migration]
           {:module vopr-module :compile? false :replace? false})
          (let [publication
                (az/migrate-state!
                 (symbol (str vopr-module) "log_performance_mode")
                 (symbol (str vopr-module)
                         "aguafria_migrate_log_performance_mode"))]
            (binding [*ns* (the-ns vopr-module)]
              (eval
               '(az/defn aguafria_log_state_generation :- :u32 []
                  (az/field log_performance_mode migration_generation)))
              (eval
               '(az/defn aguafria_log_state_enabled :- :bool []
                  (az/field log_performance_mode enabled))))
            (az/await! vopr-module)
            (let [generation
                  ((ns-resolve vopr-module 'aguafria_log_state_generation))
                  enabled? ((ns-resolve vopr-module
                                        'aguafria_log_state_enabled))
                  state-statuses
                  (mapv :status
                        (az/state-versions
                         (symbol (str vopr-module) "log_performance_mode")))
                  replacement (host/restart! handle)
                  second-result (host/await! replacement)
                  all-stats (az/stats)
                  failed-builds
                  (->> (:builds all-stats)
                       (filter #(= :failed (:status %)))
                       (mapv #(select-keys % [:module :generation :purpose
                                              :phase :error])))
                  report
                  {:pid pid
                   :same-pid? (= pid (.pid (java.lang.ProcessHandle/current)))
                   :first-host-id (:id handle)
                   :replacement-host-id (:id replacement)
                   :replacement-lineage
                   (:replaces-host-id (host/info replacement))
                   :first-result first-result
                   :second-result second-result
                   :function-versions
                   (let [versions
                         (az/function-versions
                          (symbol (str vopr-module) "full_core"))]
                     {:count (count versions)
                      :current-count (count (filter :current? versions))
                      :distinct-abi-count
                      (count (set (map :abi-fingerprint versions)))})
                   :state-statuses state-statuses
                   :state-value {:enabled enabled?
                                 :migration-generation generation}
                   :migration (select-keys (:migration publication)
                                           [:logical-id :from-schema :to-schema
                                            :function])
                   :migration-required-build-count
                   (get-in all-stats
                           [:summary :migration-required-build-count])
                   :failed-build-count
                   (get-in all-stats [:summary :failed-build-count])
                   :failed-builds failed-builds}]
              (prn report)
              (when-not (and (zero? (:exit-code first-result))
                             (zero? (:exit-code second-result))
                             (:same-pid? report)
                             (= (:first-host-id report)
                                (:replacement-lineage report))
                             (= {:count 2
                                 :current-count 1
                                 :distinct-abi-count 2}
                                (:function-versions report))
                             (= [:retained :migrated]
                                (:state-statuses report))
                             (= {:enabled true :migration-generation 1}
                                (:state-value report))
                             (zero? (:failed-build-count report)))
                (throw (ex-info "Live VOPR migration acceptance failed"
                                report)))))))
      (finally
        (az/configure! old-config)
        (shutdown-agents)))))
