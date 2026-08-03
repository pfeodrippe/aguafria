(ns aguafria.vopr-type-hot-acceptance
  "Opt-in, long-running acceptance for a live converted TigerBeetle type edit."
  (:require [aguafria.zig :as az]
            [aguafria.zig.host :as host]
            [aguafria.zig.runtime :as runtime]
            [clojure.walk :as walk]))

(def ^:private workload-module
  'tigerbeetle.src.state-machine.workload)

(def ^:private vopr-module 'tigerbeetle.src.vopr)

(defn- add-defaulted-probe-field
  [body]
  (walk/postwalk
   (fn [value]
     (if (and (seq? value)
              (symbol? (first value))
              (= "container" (name (first value)))
              (some (fn [member]
                      (and (seq? member)
                           (symbol? (first member))
                           (= "field-decl" (name (first member)))
                           (= 'pending_timeout_mean (second member))))
                    (drop 2 value))
              (not-any? (fn [member]
                          (and (seq? member)
                               (symbol? (first member))
                               (= "field-decl" (name (first member)))
                               (= 'aguafria_hot_reload_probe
                                  (second member))))
                        (drop 2 value)))
       (let [[operator options & members] value
             field? (fn [member]
                      (and (seq? member)
                           (symbol? (first member))
                           (= "field-decl" (name (first member)))))
             fields (take-while field? members)
             declarations (drop (count fields) members)]
         (apply list
                (concat [operator options]
                        fields
                        [(list 'aguafria.zig/field-decl
                               'aguafria_hot_reload_probe :u64 0)]
                        declarations)))
       value))
   body))

(defn- running?
  [handle]
  (contains? #{:starting :running} (:status (host/info handle))))

(defn -main
  [& arguments]
  (let [requests (or (first arguments) "2000")
        seed (or (second arguments) "1")
        old-config (az/configuration)]
    (try
      (az/configure! {:async? true})
      (require vopr-module :reload)
      (az/await! vopr-module)
      (let [options-var (ns-resolve workload-module 'OptionsType)
            original (:aguafria/declaration (meta options-var))
            registered-before
            (some #(when (= "OptionsType" (str (:name %))) %)
                  (:definitions (runtime/module-info workload-module)))
            changed
            (runtime/declaration-info
             (update original :body add-defaulted-probe-field))
            _ (when (= (:schema-fingerprint original)
                       (:schema-fingerprint changed))
                (throw
                 (ex-info "The acceptance edit did not change OptionsType's schema"
                          {:original-schema (:schema-fingerprint original)
                           :changed-schema (:schema-fingerprint changed)
                           :probe-field-count
                           (count
                            (filter
                             #(= 'aguafria_hot_reload_probe %)
                             (tree-seq coll? seq (:body changed))))})))
            handle
            (host/start! (ns-resolve vopr-module 'main)
                         ["--performance"
                          (str "--requests-max=" requests)
                          seed]
                         {:argv0 "vopr"})
            pid (.pid (java.lang.ProcessHandle/current))]
        (when-not (running? handle)
          (throw (ex-info "Converted VOPR did not enter its native run"
                          {:host (host/info handle)})))
        (runtime/register-declaration! changed)
        (az/await! workload-module)
        (let [status-after-publication (host/info handle)
              type-versions (az/type-versions options-var)
              type-statuses (mapv :status type-versions)
              active-type (some #(when (:active? %) %) type-versions)
              retained-original?
              (boolean
               (some #(and (= :retained (:status %))
                           (= (:schema-fingerprint original)
                              (:schema-fingerprint %)))
                     type-versions))
              registered-after
              (some #(when (= "OptionsType" (str (:name %))) %)
                    (:definitions (runtime/module-info workload-module)))
              diagnostic
              {:type-statuses type-statuses
               :type-versions type-versions
               :var-schema (:schema-fingerprint original)
               :registered-before-schema
               (:schema-fingerprint registered-before)
               :changed-schema (:schema-fingerprint changed)
               :registered-after-schema
               (:schema-fingerprint registered-after)}]
          (when-not (:active? status-after-publication)
            (throw
             (ex-info "VOPR exited before the type generation published"
                      {:host status-after-publication
                       :diagnostic diagnostic})))
          (when-not (and retained-original?
                         (= :breaking (:status active-type))
                         (not= (:schema-fingerprint original)
                               (:schema-fingerprint active-type)))
            (throw
             (ex-info "OptionsType did not retain its breaking generation"
                      diagnostic)))
          (when-not (:dispatch-frozen? status-after-publication)
            (throw
             (ex-info "The old VOPR host was not pinned across the type break"
                      {:host status-after-publication
                       :diagnostic diagnostic})))
          (let [result (host/await! handle)
                report
                {:pid pid
                 :host-id (:id handle)
                 :same-pid? (= pid (.pid (java.lang.ProcessHandle/current)))
                 :status-after-publication
                 (select-keys status-after-publication
                              [:id :status :active? :started-at-ms
                               :dispatch-frozen?
                               :dispatch-frozen-reason])
                 :type-statuses type-statuses
                 :result result
                 :failed-build-count
                 (get-in (az/stats) [:summary :failed-build-count])}]
            (prn report)
            (when-not (and (zero? (:exit-code result))
                           (zero? (:failed-build-count report)))
              (throw (ex-info "Live converted VOPR acceptance failed" report))))))
      (finally
        (az/configure! old-config)
        (shutdown-agents)))))
