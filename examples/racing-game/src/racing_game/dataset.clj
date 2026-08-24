(ns racing-game.dataset
  "Deterministic, renderer-free decision corpus generation and tactical gates."
  (:refer-clojure :exclude [generate])
  (:require [aguafria.zig :as az]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [racing-game.core :as core]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation]
            [racing-game.train-action-head :as train-action-head])
  (:import [java.lang.foreign Arena]
           [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def default-options
  {:seeds (range 16)
   :ticks-per-seed 6000
   :sample-every 8
   :limit 5000
   :include-item-anchors? true
   :require-complete-coverage? true})

(def observation-fields
  [:valid :racer :rank :target :persona :item :target_distance :target_lane
   :tactical_status :urgent :lap :progress :speed])

(def action-specs
  {0 {:symbol "A" :lane :left :pace :steady :item :hold}
   1 {:symbol "B" :lane :center :pace :steady :item :hold}
   2 {:symbol "C" :lane :right :pace :steady :item :hold}
   3 {:symbol "D" :lane :left :pace :attack :item :hold}
   4 {:symbol "E" :lane :center :pace :attack :item :use}
   5 {:symbol "F" :lane :right :pace :attack :item :use}
   6 {:symbol "G" :lane :left :pace :maximum :item :use}
   7 {:symbol "H" :lane :center :pace :maximum :item :use}})

(def item-names
  {0 :none
   1 :bolt
   2 :trap
   3 :boost
   4 :shield
   5 :pulse
   6 :surge})

(def required-policy-capabilities
  "Player-visible behaviors that the golden policy gate must exercise."
  #{:aim :attack :decline-item :defend :hazard-response :hold-item
    :item-judgment :maximum-pace :persona :routine-driving :stun-recovery
    :urgent-reaction :use-item})

(defn default-file
  []
  (io/file (model/project-root) "build/training/r3-native-snapshots.edn"))

(defn golden-file
  []
  (or (some-> "training/golden-actions.edn" io/resource io/file)
      (io/file (model/project-root) "resources/training/golden-actions.edn")))

(defn golden-cases
  "Load the human-authored situations and their sets of acceptable actions."
  []
  (-> (golden-file) slurp edn/read-string :cases))

(defn- training-scenario
  [observation]
  {:target (:target observation)
   :persona (:persona observation)
   :rank (:rank observation)
   :lap (:lap observation)
   :item (:item observation)
   :progress (:progress observation)
   :speed (:speed observation)
   :target-distance (:target_distance observation)
   :target-lane (:target_lane observation)
   :tactical-status (:tactical_status observation)
   :urgent (:urgent observation)})

(defn acceptable-actions
  "Return the deliberately broad tactical action set for one visible state.
  Continuous control and native validation still decide the exact trajectory."
  [observation]
  (let [{:keys [racer target persona item target_distance tactical_status
                urgent]}
        observation
        opponent-visible? (and (not= racer target) (< target_distance 9))]
    (cond
      ;; Stun recovery should not waste an item or demand maximum speed.
      (= tactical_status 2) #{0 1 2}

      ;; A shield is worth spending under an immediate local threat, but is
      ;; normally worth holding when the track is clear.
      (= item 4) (if (or urgent (= tactical_status 1))
                   #{4 5 6 7}
                   #{0 1 2 3})

      ;; A bolt or pulse needs an opponent close enough to be actionable.
      (or (= item 1) (= item 5))
      (if opponent-visible? #{4 5 6 7} #{0 1 2 3})

      ;; A rear trap is useful in traffic or from the front; otherwise either
      ;; waiting or deploying it is defensible.
      (= item 2) (if (or (<= (:rank observation) 3)
                         (<= target_distance 3))
                   #{4 5 6 7}
                   #{0 1 2 3 4 5})

      ;; Boost and surge are the maximum-pace macros.
      (or (= item 3) (= item 6)) #{6 7}

      ;; Without inventory, threat/urgency may justify an aggressive response;
      ;; routine states retain multiple safe lane choices.
      (or urgent (= tactical_status 1)) #{0 2 3 6 7}
      (= persona 0) #{0 1 2}
      (= persona 1) #{0 1 2 3}
      :else #{2 3 6 7})))

(defn- canonical-observation
  [observation]
  (select-keys observation observation-fields))

(defn- decision-row
  [source seed tick observation]
  (let [observation (canonical-observation observation)]
    {:source source
     :seed seed
     :tick tick
     :racer (:racer observation)
     :observation observation
     :acceptable-actions (acceptable-actions observation)
     :teacher-action
     (train-action-head/expert-action (training-scenario observation))}))

(defn- canonical-row
  [{:keys [source seed tick racer observation acceptable-actions
           teacher-action]}]
  [source seed tick racer
   (:rank observation) (:lap observation) (:progress observation)
   (:speed observation) (:target observation) (:persona observation)
   (:item observation) (:target_distance observation)
   (:target_lane observation) (:tactical_status observation)
   (:urgent observation) teacher-action (vec (sort acceptable-actions))])

(defn corpus-sha256
  "Hash ordered semantic rows independently of pretty-print whitespace."
  [rows]
  (let [bytes (.getBytes (pr-str (mapv canonical-row rows))
                         StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (format "%064x" (BigInteger. 1 digest))))

(defn coverage
  "Return compact frequencies for the dimensions the model is expected to see."
  [rows]
  (let [observations (map :observation rows)
        frequencies-for (fn [field]
                          (into (sorted-map)
                                (frequencies (map field observations))))]
    {:racers (frequencies-for :racer)
     :ranks (frequencies-for :rank)
     :laps (frequencies-for :lap)
     :personas (frequencies-for :persona)
     :items (frequencies-for :item)
     :target-distance (frequencies-for :target_distance)
     :target-lanes (frequencies-for :target_lane)
     :tactical-statuses (frequencies-for :tactical_status)
     :urgency (frequencies-for :urgent)
     :teacher-actions
     (into (sorted-map) (frequencies (map :teacher-action rows)))}))

(def required-coverage
  {:racers (set (range 8))
   :ranks (set (range 1 9))
   :laps #{0 1 2}
   :personas #{0 1 2}
   :items (set (range 7))
   :target-lanes #{0 1 2}
   :tactical-statuses #{0 1 2 3}
   :urgency #{false true}
   :teacher-actions (set (range 8))})

(defn validate-coverage!
  "Reject a corpus that silently omits a required gameplay dimension."
  [report]
  (let [missing
        (into {}
              (keep (fn [[dimension required]]
                      (let [actual (set (keys (get report dimension)))
                            absent (vec (sort (remove #(contains? actual %)
                                                      required)))]
                        (when (seq absent) [dimension absent]))))
              required-coverage)]
    (when (seq missing)
      (throw (ex-info "Native decision corpus is missing required coverage"
                      {:missing missing :coverage report})))
    report))

(defn- collect-seed!
  [seed {:keys [ticks-per-seed sample-every limit]} initial-rows]
  (simulation/set-race-seed! seed)
  (simulation/reset!)
  (loop [advanced 0
         rows initial-rows]
    (if (or (>= advanced ticks-per-seed) (>= (count rows) limit))
      rows
      (let [ticks (min sample-every (- ticks-per-seed advanced))]
        (simulation/step-many! ticks)
        (let [tick (:tick (az/value (simulation/snapshot)))
              room (- limit (count rows))
              samples (->> (core/observations)
                           (filter :valid)
                           (take room)
                           (mapv #(decision-row :native-race seed tick %)))]
          (recur (+ advanced ticks) (into rows samples)))))))

(defn- collect-item-anchors!
  "Construct every inventory state through native mutation and perception APIs."
  [limit]
  (simulation/set-race-seed! 0)
  (simulation/reset!)
  (->> (range 7)
       (mapv
        (fn [item]
          (when-not (simulation/configure-racer-state!
                     0 0.20 0.0 0.07 item false)
            (throw (ex-info "Native item anchor mutation failed" {:item item})))
          (decision-row :native-item-anchor 0 0 (core/observation 0))))
       (take limit)
       vec))

(defn generate
  "Generate exactly `:limit` real native observations without a renderer.
  Inference workers must be stopped so deterministic fallback evolution owns
  every seed. No JVM data is ever fed back into the running simulation."
  ([] (generate default-options))
  ([options]
   (az/await!)
   (let [{:keys [seeds limit include-item-anchors?
                 require-complete-coverage?] :as options}
         (merge default-options options)
         worker (core/worker-status)]
     (when (:started worker)
       (throw (ex-info "Dataset generation requires stopped inference workers"
                       {:worker worker})))
     (simulation/configure-countdown! 0)
     (simulation/set-items-enabled! true)
     (simulation/set-human-controlled! false)
     (let [initial-rows (if include-item-anchors?
                          (collect-item-anchors! limit)
                          [])
           rows
           (loop [remaining (seq seeds)
                  rows initial-rows]
             (if (or (>= (count rows) limit) (nil? remaining))
               rows
               (recur (next remaining)
                      (collect-seed! (first remaining) options rows))))]
       (when-not (= limit (count rows))
         (throw (ex-info "Not enough native states for the requested corpus"
                         {:requested limit :captured (count rows)
                          :options options})))
       (let [coverage (coverage rows)]
         (when require-complete-coverage?
           (validate-coverage! coverage))
         {:format :aguafria-racing/native-decision-corpus-v1
          :provenance {:observation-schema protocol/observation-schema-version
                       :action-schema protocol/action-schema-version
                       :generator :renderer-free-fallback-races}
          :options (update options :seeds vec)
          :count (count rows)
          :sha256 (corpus-sha256 rows)
          :coverage coverage
          :rows rows})))))

(defn write!
  "Generate and pretty-print a canonical corpus under ignored build output."
  ([] (write! {}))
  ([{:keys [file] :as options}]
   (let [file (io/file (or file (default-file)))
         document (generate (dissoc options :file))]
     (io/make-parents file)
     (with-open [writer (io/writer file)]
       (binding [*out* writer
                 *print-length* nil
                 *print-level* nil
                 pprint/*print-right-margin* 120]
         (pprint/pprint document)))
     {:file file
      :count (:count document)
      :sha256 (:sha256 document)
      :coverage (:coverage document)})))

(defn policy-capability-report
  "Summarize which named, player-visible behaviors and inventory contexts an
  evaluation exercised. This is deliberately semantic: no prompt bytes or
  token IDs are part of the report."
  [results]
  (let [by-capability
        (into
         (sorted-map)
         (for [capability (sort (set (mapcat :capabilities results)))]
           [capability
            {:cases (mapv :name (filter #(contains? (:capabilities %)
                                                    capability)
                                       results))
             :accepted (count (filter #(and (contains? (:capabilities %)
                                                       capability)
                                            (:accepted %))
                                      results))}]))
        covered (set (keys by-capability))
        missing (vec (sort (remove covered required-policy-capabilities)))
        item-results (filter #(not= :none (:item %)) results)
        by-item
        (into
         (sorted-map)
         (for [[item cases] (sort-by key (group-by :item item-results))]
           [item
            {:cases (mapv :name cases)
             :capabilities (set (mapcat :capabilities cases))
             :accepted (count (filter :accepted cases))
             :total (count cases)}]))]
    {:required required-policy-capabilities
     :covered covered
     :missing missing
     :all-covered? (empty? missing)
     :capabilities by-capability
     :items by-item}))

(defn evaluate
  "Score a predictor against human-authored acceptable action sets."
  [predictor]
  (let [cases (golden-cases)
        results
        (mapv
         (fn [{:keys [name capabilities observation acceptable-actions reason]}]
           (let [action (long (predictor observation))]
             {:name name
              :capabilities capabilities
              :item (get item-names (:item observation) :unknown)
              :action action
              :action-spec (get action-specs action)
              :acceptable-actions acceptable-actions
              :accepted (contains? acceptable-actions action)
              :reason reason}))
         cases)
        accepted (count (filter :accepted results))]
    {:cases (count results)
     :accepted accepted
     :accuracy (/ accepted (double (max 1 (count results))))
     :failures (filterv (complement :accepted) results)
     :policy (policy-capability-report results)
     :results results}))

(defn expert-evaluation
  "Evaluate the transparent teacher independently of the training split."
  []
  (evaluate #(train-action-head/expert-action (training-scenario %))))

(defn with-model-predictor
  "Load the pinned model/head once and call `f` with a real native predictor.

  The predictor accepts one semantic observation and returns its deterministic
  best A-H action code. It is valid only for the dynamic extent of `f`."
  [f]
  (let [worker (core/worker-status)]
    (when (:started worker)
      (throw (ex-info "Offline model evaluation requires stopped workers"
                      {:worker worker})))
    (model/verify-assets!)
    (az/await! 'racing-game.inference)
    (with-open [arena (Arena/ofConfined)]
      (let [loaded-model
            (az/value
             (inference/load-model!
              (.allocateFrom arena (str (model/model-file)))))
            loaded-head
            (az/value
             (inference/load-action-head!
              (.allocateFrom arena (str (model/action-head-file)))))]
        (when-not (:valid loaded-model)
          (throw (ex-info "Granite rejected the verified model"
                          {:model loaded-model})))
        (when-not (:valid loaded-head)
          (inference/unload-model!)
          (throw (ex-info "Granite rejected the verified action head"
                          {:action-head loaded-head})))
        (when-not (inference/initialize-sequences!)
          (inference/unload-action-head!)
          (inference/unload-model!)
          (throw (ex-info "Could not allocate evaluation sequence state" {})))
        (try
          (f
           (fn [observation]
             (let [prompt (train-action-head/observation-bytes
                           (training-scenario observation))
                   bytes (.allocate arena (alength prompt) 1)]
               (.copyFrom bytes
                          (java.lang.foreign.MemorySegment/ofArray prompt))
               (let [report
                     (az/value
                      (inference/forward-fused-observation!
                       0 bytes (alength prompt) true))]
                 (when-not (:valid report)
                   (throw (ex-info "Native model evaluation failed"
                                   {:observation observation
                                    :report report})))
                 (- (:best_token report) 32)))))
          (finally
            (inference/free-sequences!)
            (inference/unload-action-head!)
            (inference/unload-model!)))))))

(defn model-evaluation!
  "Run the checked-in head through the real native Granite graph and score its
  deterministic best A-H macro against the human acceptable-action subset."
  []
  (with-model-predictor evaluate))

(def rollout-defaults
  {:horizon-ticks 120
   :intent-refresh-ticks 16})

(defn- absolute-progress
  [racer]
  (+ (double (:lap racer)) (double (:progress racer))))

(defn- action-intent
  [action target held-item]
  (let [spec (get action-specs action)
        lane (:lane spec)
        pace (:pace spec)
        item-mode (:item spec)
        lane-target ({:left -0.075 :center 0.0 :right 0.075} lane)
        target-speed ({:steady 0.076 :attack 0.084 :maximum 0.092} pace)]
    {:lane-target lane-target
     :target-speed target-speed
     :item-action (if (and (= :use item-mode) (pos? held-item))
                    simulation/action-use
                    simulation/action-hold)
     :target target}))

(defn- recreate-corpus-state!
  [row]
  (when-not (= :native-race (:source row))
    (throw (ex-info "Short rollout requires a reproducible native-race row"
                    {:source (:source row) :seed (:seed row) :tick (:tick row)})))
  (simulation/configure-countdown! 0)
  (simulation/set-items-enabled! true)
  (simulation/set-human-controlled! false)
  (simulation/set-race-seed! (:seed row))
  (simulation/reset!)
  (simulation/step-many! (:tick row))
  (let [actual (core/observation (:racer row))
        expected (:observation row)
        fields [:racer :rank :target :persona :item :target_distance
                :target_lane :tactical_status :urgent :lap]
        expected-discrete (select-keys expected fields)
        actual-discrete (select-keys actual fields)]
    (when-not (= expected-discrete actual-discrete)
      (throw (ex-info "Native corpus state did not reproduce"
                      {:seed (:seed row)
                       :tick (:tick row)
                       :racer (:racer row)
                       :expected expected-discrete
                       :actual actual-discrete})))
    actual))

(defn rollout-action!
  "Recreate one corpus state and execute exactly one candidate A-H decision.

  The focal intent is refreshed before the ordinary scheduler can replace it;
  item use is permitted only on the first tick. Other racers continue through
  the normal transparent native controller, identically for every candidate."
  ([row action]
   (rollout-action! row action {}))
  ([row action options]
   (let [{:keys [horizon-ticks intent-refresh-ticks]}
         (merge rollout-defaults options)]
     (when-not (and (integer? action) (<= 0 action 7))
       (throw (ex-info "Rollout action must be an A-H code from 0 through 7"
                       {:action action})))
     (when-not (and (pos-int? horizon-ticks)
                    (pos-int? intent-refresh-ticks)
                    (< intent-refresh-ticks simulation/ordinary-thought-ticks))
       (throw (ex-info "Rollout horizon/refresh values are invalid"
                       {:horizon-ticks horizon-ticks
                        :intent-refresh-ticks intent-refresh-ticks
                        :ordinary-thought-ticks
                        simulation/ordinary-thought-ticks})))
     (let [observation (recreate-corpus-state! row)
           racer-id (:racer row)
           before-racer (az/value (simulation/racer-view racer-id))
           before-race (az/value (simulation/snapshot))
           intent (action-intent action (:target observation)
                                 (:item observation))]
       (simulation/configure-racer-intent!
        racer-id (:lane-target intent) (:target-speed intent)
        (:item-action intent) (:target intent))
       (simulation/step!)
       (loop [remaining (dec horizon-ticks)]
         (when (pos? remaining)
           (simulation/configure-racer-intent!
            racer-id (:lane-target intent) (:target-speed intent)
            simulation/action-hold (:target intent))
           (let [ticks (min remaining intent-refresh-ticks)]
             (simulation/step-many! ticks)
             (recur (- remaining ticks)))))
       (let [after-racer (az/value (simulation/racer-view racer-id))
             after-race (az/value (simulation/snapshot))]
         {:action action
          :action-spec (get action-specs action)
          :horizon-ticks horizon-ticks
          :progress-gain (- (absolute-progress after-racer)
                            (absolute-progress before-racer))
          :rank-improvement (- (:rank before-racer) (:rank after-racer))
          :hits (- (:hits after-race) (:hits before-race))
          :contacts (- (:contacts after-race) (:contacts before-race))
          :items-used (- (:items_used after-race) (:items_used before-race))
          :finished (boolean (:finished after-racer))
          :before (select-keys before-racer [:rank :lap :progress :speed :item])
          :after (select-keys after-racer [:rank :lap :progress :speed :item])})))))

(defn rollout-utility
  "Transparent short-horizon utility used only to quantify tactical regret.

  One rank gained is worth 5% lap progress, an unshielded hit 3%, and spending
  an item costs 0.5%. Raw outcome dimensions remain present in every report."
  [outcome]
  (+ (:progress-gain outcome)
     (* 0.05 (:rank-improvement outcome))
     (* 0.03 (:hits outcome))
     (* -0.005 (:items-used outcome))))

(defn rollout-evaluation!
  "Compare predicted decisions with acceptable alternatives through real native
  short rollouts reconstructed from deterministic corpus rows."
  ([rows predictor]
   (rollout-evaluation! rows predictor {}))
  ([rows predictor options]
   (let [results
         (mapv
          (fn [row]
            (let [action (long (predictor (:observation row)))
                  acceptable (:acceptable-actions row)
                  candidates (vec (sort (conj acceptable action)))
                  outcomes (into (sorted-map)
                                 (map (fn [candidate]
                                        [candidate
                                         (rollout-action! row candidate options)]))
                                 candidates)
                  chosen (get outcomes action)
                  best-acceptable
                  (apply max-key (comp rollout-utility val)
                         (select-keys outcomes acceptable))
                  best-utility (rollout-utility (val best-acceptable))
                  chosen-utility (rollout-utility chosen)]
              {:source (:source row)
               :seed (:seed row)
               :tick (:tick row)
               :racer (:racer row)
               :action action
               :accepted (contains? acceptable action)
               :acceptable-actions acceptable
               :chosen-outcome chosen
               :best-acceptable-action (key best-acceptable)
               :best-acceptable-outcome (val best-acceptable)
               :regret (max 0.0 (- best-utility chosen-utility))
               :outcomes outcomes}))
          rows)
         accepted (count (filter :accepted results))]
     {:cases (count results)
      :accepted accepted
      :accuracy (/ accepted (double (max 1 (count results))))
      :average-regret (/ (reduce + 0.0 (map :regret results))
                         (max 1 (count results)))
      :maximum-regret (reduce max 0.0 (map :regret results))
      :failures (filterv (complement :accepted) results)
      :results results})))

(defn- load-corpus
  [file]
  (let [file (io/file file)]
    (when-not (.isFile file)
      (throw (ex-info "Native decision corpus is missing; generate it first"
                      {:file file
                       :command "clojure -M:generate-decision-corpus"})))
    (let [document (-> file slurp edn/read-string)]
      (when-not (= :aguafria-racing/native-decision-corpus-v1
                   (:format document))
        (throw (ex-info "Unsupported native decision corpus format"
                        {:file file :format (:format document)})))
      document)))

(defn- diverse-rollout-rows
  [rows limit]
  (let [native (vec (filter #(= :native-race (:source %)) rows))
        dimensions (fn [row]
                     (let [observation (:observation row)]
                       [(:teacher-action row) (:item observation)
                        (:tactical_status observation) (:urgent observation)]))
        priority
        (:rows
         (reduce (fn [{:keys [seen] :as selected} row]
                   (let [key (dimensions row)]
                     (if (contains? seen key)
                       selected
                       (-> selected
                           (update :seen conj key)
                           (update :rows conj row)))))
                 {:seen #{} :rows []}
                 native))
        priority-set (set priority)]
    (->> (concat priority (remove priority-set native))
         (take limit)
         vec)))

(defn model-rollout-evaluation!
  "Evaluate the real Granite/head policy through deterministic native rollouts.

  By default this reads the generated 5,000-row corpus and chooses eight rows
  that prioritize distinct action/item/status/urgency dimensions. Pass `:rows`
  for an explicit sample or `:limit` for a larger bake-off."
  ([]
   (model-rollout-evaluation! {}))
  ([{:keys [file rows limit]
     :or {file (default-file) limit 8}
     :as options}]
   (when-not (pos-int? limit)
     (throw (ex-info "Rollout sample limit must be positive" {:limit limit})))
   (let [document (when-not rows (load-corpus file))
         available (filter #(= :native-race (:source %))
                           (or rows (:rows document)))
         selected (if rows
                    (vec (take limit available))
                    (diverse-rollout-rows (:rows document) limit))]
     (when-not (= limit (count selected))
       (throw (ex-info "Corpus does not contain enough reproducible rollout rows"
                       {:requested limit
                        :available (count available)
                        :selected (count selected)})))
     (with-model-predictor
       (fn [predictor]
         (assoc (rollout-evaluation!
                 selected predictor
                 (dissoc options :file :rows :limit))
                :sample {:rows (count selected)
                         :source (if rows :explicit :generated-corpus)
                         :corpus-sha256 (:sha256 document)}))))))

(defn -main
  [& _]
  (try
    (prn (write!))
    (finally
      (simulation/shutdown!)
      (shutdown-agents))))
