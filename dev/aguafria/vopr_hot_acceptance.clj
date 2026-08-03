(ns aguafria.vopr-hot-acceptance
  "Opt-in acceptance for a compatible edit in a running converted VOPR."
  (:require [aguafria.zig :as az]
            [aguafria.zig.host :as host]
            [aguafria.zig.runtime :as runtime]
            [clojure.walk :as walk]))

(def ^:private vopr-module 'tigerbeetle.src.vopr)

(defn- named-symbol?
  [value expected]
  (and (symbol? value) (= expected (name value))))

(defn- replica-and-standby-sum?
  [value]
  (and (seq? value)
       (= 3 (count value))
       (named-symbol? (first value) "+")
       (named-symbol? (second value) "replica_count")
       (named-symbol? (nth value 2) "standby_count")))

(defn- registered-declaration
  [declaration-name]
  (or (some #(when (= declaration-name (str (:name %))) %)
            (:definitions (runtime/module-info vopr-module)))
      (throw (ex-info "Converted VOPR declaration was not registered"
                      {:module vopr-module
                       :declaration declaration-name}))))

(defn- changed-full-core
  [declaration]
  (let [matches (count (filter replica-and-standby-sum?
                               (tree-seq coll? seq (:body declaration))))
        replaced? (atom false)
        body
        (walk/postwalk
         (fn [value]
           (if (and (not @replaced?) (replica-and-standby-sum? value))
             (do
               (reset! replaced? true)
               (list 'aguafria_hot_reload_mark value))
             value))
         (:body declaration))]
    (when-not (= 2 matches)
      (throw (ex-info "VOPR full_core no longer has the expected sum sites"
                      {:module vopr-module :sum-site-count matches})))
    (when-not @replaced?
      (throw (ex-info "VOPR full_core was not rewired"
                      {:module vopr-module})))
    (runtime/declaration-info (assoc declaration :body body))))

(defn- current-function-version
  [function]
  (or (some #(when (:current? %) %) (az/function-versions function))
      (throw (ex-info "Aguafria function has no current native version"
                      {:function function
                       :versions (az/function-versions function)}))))

(defn- running?
  [handle]
  (contains? #{:starting :running} (:status (host/info handle))))

(defn- evaluate-and-await!
  [form]
  (binding [*ns* (the-ns vopr-module)]
    (eval form))
  (az/await! vopr-module))

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
      (let [full-core-symbol (symbol (str vopr-module) "full_core")
            original-full-core (registered-declaration "full_core")
            version-before (current-function-version full-core-symbol)
            changed (changed-full-core original-full-core)
            handle
            (host/start! (ns-resolve vopr-module 'main)
                         ["--performance" (str "--requests-max=" requests) seed]
                         {:argv0 "vopr"})]
        (when-not (running? handle)
          (throw (ex-info "Converted VOPR did not enter its native run"
                          {:host (host/info handle)})))

        ;; Define a genuinely new A while VOPR is already running. The probe is
        ;; stable native state, so it proves that the host later entered A from
        ;; the reevaluated existing B instead of merely publishing descriptors.
        (evaluate-and-await!
         '(az/defvar aguafria_hot_reload_probe_value :u64 0))
        (evaluate-and-await!
         '(az/defn aguafria_hot_reload_mark :- :u8
            [value :- :u8]
            (set! aguafria_hot_reload_probe_value 4242)
            value))
        (evaluate-and-await!
         '(az/defn aguafria_hot_reload_probe_read :- :u64 []
            aguafria_hot_reload_probe_value))
        (let [after-new-a (host/info handle)]
          (when-not (:active? after-new-a)
            (throw (ex-info "VOPR exited while the new live Var was publishing"
                            {:host after-new-a}))))

        ;; Redefine existing B with the same ABI. Its new implementation calls
        ;; A, and the already-running host receives the compatible dispatch
        ;; update without a restart.
        (runtime/register-declaration! changed)
        (az/await! vopr-module)
        (let [after-rewire (host/info handle)
              version-after (current-function-version full-core-symbol)]
          (when-not (:active? after-rewire)
            (throw (ex-info "VOPR exited during the compatible B publication"
                            {:host after-rewire})))
          (when-not (and (= (:abi-fingerprint version-before)
                            (:abi-fingerprint version-after))
                         (not= (:implementation-fingerprint version-before)
                               (:implementation-fingerprint version-after))
                         (not= (:implementation-generation version-before)
                               (:implementation-generation version-after)))
            (throw (ex-info "full_core did not publish a compatible new implementation"
                            {:before version-before :after version-after})))

          (let [result (host/await! handle)
                probe-value
                ((ns-resolve vopr-module 'aguafria_hot_reload_probe_read))
                all-stats (az/stats)
                report
                {:pid pid
                 :same-pid? (= pid (.pid (java.lang.ProcessHandle/current)))
                 :host-id (:id handle)
                 :host-after-publication
                 (select-keys after-rewire
                              [:id :status :active? :dispatch-frozen?])
                 :result result
                 :probe-value probe-value
                 :full-core
                 {:abi-compatible?
                  (= (:abi-fingerprint version-before)
                     (:abi-fingerprint version-after))
                  :generation-before (:implementation-generation version-before)
                  :generation-after (:implementation-generation version-after)}
                 :failed-build-count
                 (get-in all-stats [:summary :failed-build-count])}]
            (prn report)
            (when-not (and (zero? (:exit-code result))
                           (:same-pid? report)
                           (= 4242 probe-value)
                           (true? (get-in report [:full-core :abi-compatible?]))
                           (zero? (:failed-build-count report)))
              (throw (ex-info "Live compatible VOPR acceptance failed" report))))))
      (finally
        (az/configure! old-config)
        (shutdown-agents)))))
