(ns aguafria.zig.benchmark
  "Persistent-JVM hot-publication measurement helpers.

  This namespace is intentionally separate from `aguafria.zig`: applications
  do not pay for workflow/reporting helpers unless they explicitly require
  them. Measurements register the same declaration descriptor produced by an
  `az/defn`/type macro, await the complete publication boundary, verify native
  behavior, and optionally restore the original descriptor."
  (:require [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]))

(defn- elapsed-ms
  [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1000000.0))

(defn- declaration-var
  [reference]
  (cond
    (var? reference) reference

    (and (symbol? reference) (namespace reference))
    (or (resolve reference)
        (when-let [target-ns (find-ns (symbol (namespace reference)))]
          (ns-resolve target-ns (symbol (name reference)))))

    :else nil))

(defn declaration
  "Return the registered declaration descriptor for a Var or qualified symbol."
  [reference]
  (let [target (declaration-var reference)
        descriptor (some-> target meta :aguafria/declaration)]
    (when-not descriptor
      (throw
       (ex-info "Hot-reload benchmark requires an Aguafria declaration Var"
                {:reference reference
                 :resolved target})))
    descriptor))

(defn plan
  "Return the exact read-only publication plan for a Var, symbol, or
  declaration descriptor. No Zig compiler or dylib loader is invoked."
  [reference]
  (runtime/publication-plan
   (if (map? reference) reference (declaration reference))))

(defn- new-builds
  [before after]
  (let [before-ids (into #{} (map (juxt :module :purpose :generation))
                         (:builds before))]
    (->> (:builds after)
         (remove #(contains? before-ids
                            [(:module %) (:purpose %) (:generation %)]))
         (sort-by (juxt :requested-at-ms :module :generation))
         vec)))

(defn- build-summary
  [builds]
  {:build-count (count builds)
   :module-count (count (set (map :module builds)))
   :modules (->> builds (map :module) distinct sort vec)
   :compiled-declaration-count
   (reduce + 0 (map #(or (:compiled-declaration-count %)
                         (count (:declarations %)))
                    builds))
   :cache-hit-count (count (filter :cached? builds))
   :cache-miss-count (count (filter #(false? (:cached? %)) builds))
   :artifact-bytes (reduce + 0 (keep :library-size-bytes builds))
   :native-build-ms
   (reduce + 0 (keep #(or (:native-duration-ms %) (:duration-ms %)) builds))
   :planning-ms (reduce + 0 (keep :planning-duration-ms builds))
   :dependency-preparation-ms
   (reduce + 0 (keep :dependency-preparation-duration-ms builds))
   :compiler-ms (reduce + 0 (keep :compiler-duration-ms builds))
   :dynamic-load-ms (reduce + 0 (keep :dynamic-load-duration-ms builds))
   :dispatch-publication-ms
   (reduce + 0 (keep :publication-duration-ms builds))
   :propagation-ms (reduce + 0 (keep :propagation-duration-ms builds))
   :queue-wait-ms
   (reduce + 0
           (keep (fn [{:keys [requested-at-ms started-at-ms]}]
                   (when (and requested-at-ms started-at-ms)
                     (- started-at-ms requested-at-ms)))
                 builds))})

(defn- artifact-freshness
  [builds]
  (cond
    (some #(false? (:cached? %)) builds) :fresh
    (and (seq builds) (every? #(true? (:cached? %)) builds)) :cache-backed
    (empty? builds) :no-native-build
    :else :unknown))

(defn measure-publication!
  "Register one complete declaration and measure until its new native behavior
  is observable.

  Options:

  - `:declaration` — declaration descriptor to register.
  - `:verify` — zero-argument function called after publication.
  - `:label`, `:project`, `:complexity` — plain report dimensions.

  Returns EDN containing registration latency, total observable latency, all
  builds caused by the edit, and the verification value. Compiler failures are
  rethrown with the partial benchmark dimensions in `ex-data`."
  [{:keys [declaration verify label project complexity]}]
  (when-not (map? declaration)
    (throw (ex-info ":declaration must be a descriptor map"
                    {:value declaration})))
  (let [module (str (:module declaration))
        compiler-profile
        (select-keys (az/configuration)
                     [:zig :optimize :development-debug-info
                      :development-panic :target :cpu])
        before (az/stats)
        started-ns (System/nanoTime)
        publication-plan-capture (atom nil)]
    (try
      (let [registration
            (binding [runtime/*publication-plan-capture*
                      publication-plan-capture]
              (runtime/register-declaration! declaration))
            registration-ms (elapsed-ms started-ns)
            plan-ms (:planning-duration-ms registration)
            publication (az/await! module)
            publication-ms (elapsed-ms started-ns)
            verification-started-ns (System/nanoTime)
            verification (when verify (verify))
            verification-ms (elapsed-ms verification-started-ns)
            observable-ms (elapsed-ms started-ns)
            after (az/stats)
            builds (new-builds before after)
            publication-plan (some-> @publication-plan-capture force)
            artifact-freshness (artifact-freshness builds)]
        (merge
         {:label label
          :project project
          :complexity complexity
          :compiler-profile compiler-profile
          :host {:os (System/getProperty "os.name")
                 :architecture (System/getProperty "os.arch")
                 :java-version (System/getProperty "java.version")}
          :module module
          :declaration (str (:name declaration))
          :kind (:kind declaration)
          :registration-ms registration-ms
          :plan-ms plan-ms
          :publication-ms publication-ms
          :observable-ms observable-ms
          :verification-ms verification-ms
          :verification verification
          :registration registration
          :publication publication
          :plan publication-plan
          :affected (or (:affected publication)
                        (:last-dependent-publication publication))
          :artifact-freshness artifact-freshness
          :artifact-hashes (->> builds (keep :hash) distinct sort vec)
          :builds builds}
         (build-summary builds)))
      (catch Throwable error
        (throw
         (ex-info (or (ex-message error) "Hot-reload benchmark failed")
                  (merge {:aguafria/phase :hot-reload-benchmark
                          :label label
                          :project project
                          :complexity complexity
                          :module module
                          :elapsed-ms (elapsed-ms started-ns)}
                         (ex-data error))
                  error))))))

(defn measure-edit!
  "Measure an edited descriptor and, by default, a cached restoration.

  `:var` is an Aguafria Var/qualified symbol. `:edit` receives its original
  descriptor and must return the changed descriptor. `:verify-change` and
  `:verify-restore` run after their respective publication boundaries.
  Restoration happens in `finally` even when changed verification fails.

  Set `:restore? false` only when the caller explicitly owns restoration."
  [{:keys [var edit verify-change verify-restore restore?]
    :or {restore? true}
    :as options}]
  (when-not (ifn? edit)
    (throw (ex-info ":edit must be a function" {:value edit})))
  (let [original (declaration var)
        changed (runtime/declaration-info (edit original))
        dimensions (select-keys options [:label :project :complexity])
        change-result (atom nil)
        restore-result (atom nil)
        change-error (atom nil)]
    (when (= (select-keys (runtime/declaration-info original)
                          [:abi-fingerprint :schema-fingerprint
                           :implementation-fingerprint])
             (select-keys changed
                          [:abi-fingerprint :schema-fingerprint
                           :implementation-fingerprint]))
      (throw (ex-info "Benchmark edit did not change implementation identity"
                      {:var var :label (:label options)})))
    (try
      (try
        (reset! change-result
                (measure-publication!
                 (merge dimensions
                        {:declaration changed
                         :verify verify-change})))
        @change-result
        (catch Throwable error
          (reset! change-error error)
          (throw error)))
      (finally
        (when restore?
          (try
            (reset! restore-result
                    (measure-publication!
                     (merge dimensions
                            {:label (str (:label options) " / restore")
                             :declaration original
                             :verify verify-restore})))
            (catch Throwable restore-error
              (if-let [original-error @change-error]
                (.addSuppressed ^Throwable original-error restore-error)
                (throw restore-error)))))))
    {:change @change-result
     :restore @restore-result}))

(defonce ^:private fresh-edit-sequence (atom 0))

(defn- fresh-edit-context
  [sample-index attempt]
  (let [id (random-uuid)]
    {:sample-index sample-index
     :sample-number (inc sample-index)
     :attempt attempt
     :fresh-id (str id)
     :fresh-value (bit-and Integer/MAX_VALUE (hash id))
     :sequence (swap! fresh-edit-sequence inc)
     :generated-at-ms (System/currentTimeMillis)}))

(def ^:private distribution-fields
  [:registration-ms :plan-ms :publication-ms :observable-ms :verification-ms
   :queue-wait-ms :planning-ms :dependency-preparation-ms :compiler-ms
   :dynamic-load-ms :dispatch-publication-ms :propagation-ms
   :native-build-ms :artifact-bytes :compiled-declaration-count])

(defn- percentile
  [numbers probability]
  (when (seq numbers)
    (let [numbers (vec (sort numbers))
          index (-> (* probability (count numbers))
                    Math/ceil
                    long
                    dec
                    (max 0)
                    (min (dec (count numbers))))]
      (nth numbers index))))

(defn- sample-distribution
  [samples]
  {:sample-count (count samples)
   :p50 (into {}
              (keep (fn [field]
                      (when-let [value
                                 (percentile (keep field samples) 0.50)]
                        [field value])))
              distribution-fields)
   :p95 (into {}
              (keep (fn [field]
                      (when-let [value
                                 (percentile (keep field samples) 0.95)]
                        [field value])))
              distribution-fields)})

(defn measure-fresh-edits!
  "Measure behaviorally distinct edits without counting artifact restores.

  `:edit` receives the original descriptor and a fresh context containing
  `:fresh-id`, `:fresh-value`, `:sample-number`, and `:attempt`. It must embed
  that context into the returned declaration's behavior or source identity.
  Aguafria rejects a repeated implementation fingerprint before compilation.

  `:verify-change` receives the same context after native publication. Cached
  attempts remain visible under `:excluded-attempts` but are retried and never
  enter the fresh p50/p95 distribution. The original declaration is restored
  once after the complete series, rather than between samples.

  Options include `:samples` (default 5), `:max-attempts-per-sample` (default
  8), `:require-fresh-artifact?` (default true), and the ordinary benchmark
  dimensions `:label`, `:project`, and `:complexity`."
  [{:keys [var edit verify-change verify-restore restore? samples
           max-attempts-per-sample require-fresh-artifact?]
    :or {restore? true
         samples 5
         max-attempts-per-sample 8
         require-fresh-artifact? true}
    :as options}]
  (when-not (ifn? edit)
    (throw (ex-info ":edit must be a two-argument function"
                    {:value edit})))
  (when-not (and (integer? samples) (pos? samples))
    (throw (ex-info ":samples must be a positive integer"
                    {:value samples})))
  (when-not (and (integer? max-attempts-per-sample)
                 (pos? max-attempts-per-sample))
    (throw (ex-info ":max-attempts-per-sample must be a positive integer"
                    {:value max-attempts-per-sample})))
  (let [original (declaration var)
        original (runtime/declaration-info original)
        dimensions (select-keys options [:label :project :complexity])
        seen-fingerprints
        (atom #{(:implementation-fingerprint original)})
        accepted (atom [])
        excluded (atom [])
        restore-result (atom nil)
        primary-error (atom nil)]
    (try
      (try
        (doseq [sample-index (range samples)]
          (loop [attempt 1]
            (when (> attempt max-attempts-per-sample)
              (throw
               (ex-info "Could not produce a fresh native artifact"
                        {:aguafria/phase :hot-reload-fresh-edit
                         :sample-number (inc sample-index)
                         :max-attempts max-attempts-per-sample
                         :excluded-attempts (count @excluded)})))
            (let [context (fresh-edit-context sample-index attempt)
                  changed (runtime/declaration-info
                           (edit original context))
                  fingerprint (:implementation-fingerprint changed)]
              (when (or (nil? fingerprint)
                        (contains? @seen-fingerprints fingerprint))
                (throw
                 (ex-info
                  "Fresh benchmark edit repeated its implementation identity"
                  {:aguafria/phase :hot-reload-fresh-edit
                   :sample-number (inc sample-index)
                   :attempt attempt
                   :fresh-context context
                   :implementation-fingerprint fingerprint})))
              (swap! seen-fingerprints conj fingerprint)
              (let [measurement
                    (measure-publication!
                     (merge dimensions
                            {:label (str (:label options)
                                         " / fresh " (inc sample-index))
                             :declaration changed
                             :verify (when verify-change
                                       #(verify-change context))}))
                    measurement
                    (assoc measurement
                           :fresh-context context
                           :implementation-fingerprint fingerprint)]
                (if (and require-fresh-artifact?
                         (not= :fresh (:artifact-freshness measurement)))
                  (do
                    (swap! excluded conj measurement)
                    (recur (inc attempt)))
                  (swap! accepted conj measurement))))))
        (catch Throwable error
          (reset! primary-error error)))
      (finally
        (when restore?
          (try
            (reset! restore-result
                    (measure-publication!
                     (merge dimensions
                            {:label (str (:label options) " / restore")
                             :declaration original
                             :verify verify-restore})))
            (catch Throwable restore-error
              (if-let [error @primary-error]
                (.addSuppressed ^Throwable error restore-error)
                (throw restore-error)))))))
    (when-let [error @primary-error]
      (throw error))
    {:samples @accepted
     :excluded-attempts @excluded
     :excluded-attempt-count (count @excluded)
     :distribution (sample-distribution @accepted)
     :restore @restore-result}))

(defn summary
  "Return a compact, stable view of one `measure-edit!` result."
  [result]
  (letfn [(affected-summary [affected]
            (when affected
              (let [publications (:publications affected)]
                (cond->
                 (select-keys affected
                              [:status :root :root-component-recompiled?
                               :component-count :module-count
                               :skipped-component-count
                               :parallel-frontier-count
                               :parallel-component-count])
                  (seq publications)
                  (assoc :modules
                         (->> publications
                              (mapcat :modules)
                              distinct
                              sort
                              vec))))))
          (phase [measurement]
            (when measurement
              (let [publication-plan (:plan measurement)
                    preferred (:preferred-slice publication-plan)]
                (assoc
                 (select-keys measurement
                              [:label :project :complexity :module :declaration
                               :fresh-context :implementation-fingerprint
                               :registration-ms :plan-ms :publication-ms
                               :observable-ms :verification-ms
                               :build-count :module-count
                               :modules :compiled-declaration-count
                               :cache-hit-count :cache-miss-count
                               :artifact-freshness :artifact-hashes
                               :artifact-bytes :compiler-profile :host
                               :native-build-ms :queue-wait-ms :planning-ms
                               :dependency-preparation-ms :compiler-ms
                               :dynamic-load-ms :dispatch-publication-ms
                               :propagation-ms :verification])
                 :plan
                 (when publication-plan
                   (-> (select-keys publication-plan
                                    [:reason :preferred-slice
                                     :registered-declaration-count
                                     :propagation-impact])
                       (assoc :primary-declaration-count
                              (get-in publication-plan
                                      [:primary :declaration-count])
                              :fallback-declaration-count
                              (get-in publication-plan
                                      [:fallback :declaration-count])
                              :dependency-modules
                              (get-in publication-plan
                                      [preferred :dependency-modules]))))
                 :affected
                 (affected-summary (:affected measurement))))))]
    (if (contains? result :samples)
      {:samples (mapv phase (:samples result))
       :excluded-attempt-count (:excluded-attempt-count result)
       :excluded-attempts (mapv phase (:excluded-attempts result))
       :distribution (:distribution result)
       :restore (phase (:restore result))}
      {:change (phase (:change result))
       :restore (phase (:restore result))})))
