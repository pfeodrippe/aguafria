(ns racing-game.core
  "REPL dashboard for the same native state used by the live Vulkan window."
  (:require [aguafria.zig :as az]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.desktop :as desktop]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol]
            [racing-game.render :as render]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]
            [racing-game.worker :as worker])
  (:import [java.lang.foreign Arena]))

(defonce ^:private headless-owned? (atom false))

(defn- utf8-preview
  [bytes length]
  (String. (byte-array (map #(unchecked-byte (int %))
                            (take length bytes)))
           java.nio.charset.StandardCharsets/UTF_8))

(def ^:private item-names
  [:none :bolt :trap :boost :shield :pulse :surge])

(def ^:private item-codes
  (zipmap item-names (range)))

(def ^:private item-action-codes
  {:hold simulation/action-hold
   :use simulation/action-use})

(def ^:private persona-names
  [:cautious :balanced :bold])

(def ^:private lane-names
  [:left :same-lane :right])

(def ^:private tactical-status-names
  [:clear :hazard-near :stunned :shielded])

(def ^:private team-names
  [:aurora :vortex :atlas :nova])

(def ^:private radio-messages
  [:radio-quiet
   :tires-losing-grip
   :pit-stop-confirmed
   :box-occupied-hold
   :boxing-now
   :fresh-tires-rejoining
   :collision-damage-reported
   :repair-stop-confirmed
   :repairs-complete-rejoining
   :stay-out])

(defn- category-value
  [prompt position]
  (- (int (.charAt ^String prompt position)) (int \A)))

(defn- decode-observation-prompt
  [entry]
  (let [prompt (:prompt entry)]
    (when (and (string? prompt) (>= (count prompt) 8))
      (let [target (:target entry)
        combined (category-value prompt 1)
        rank-lap (category-value prompt 2)
        item-urgency (category-value prompt 3)
        progress-bin (category-value prompt 4)
        speed-bin (category-value prompt 5)
        target-distance (category-value prompt 6)
        status-lane (category-value prompt 7)
        tactical-status (quot status-lane 3)
        target-lane (mod status-lane 3)
        lane-name (nth [:left :same-lane :right] target-lane :unknown)
        status-name (nth [:clear :hazard-near :stunned :shielded]
                         tactical-status :unknown)
        part (fn [position field value meaning]
               {:position position
                :character (str (.charAt ^String prompt position))
                :field field
                :value value
                :meaning meaning})]
    [(part 0 :wire-format (:observation_schema entry)
           (str "wire-format marker; the next seven characters describe "
                "target/persona, rank/lap, item/urgency, progress, speed, "
                "opponent distance, and local tactics"))
     (part 1 :target-persona
           {:target target :persona (- combined (* target 3))}
           (format "target racer %d with persona %d"
                   target (- combined (* target 3))))
     (part 2 :rank-lap
           {:rank (inc (quot rank-lap 3)) :lap (mod rank-lap 3)}
           (format "rank %d of 8 on lap %d" (:rank entry) (:lap entry)))
     (part 3 :item-urgency
           {:item (quot item-urgency 2) :urgent (odd? item-urgency)}
           (format "%s; %s"
                   (str "held item "
                        (name (get item-names (:item entry) :unknown)))
                   (if (:urgent entry)
                     "decide immediately"
                     "normal decision cadence")))
     (part 4 :progress-bin progress-bin
           (format "%.0f%% to %.0f%% of the lap"
                   (* 10.0 progress-bin) (* 10.0 (inc progress-bin))))
     (part 5 :speed-bin speed-bin
           (format "%.2f to %.2f normalized track lengths per second"
                   (/ speed-bin 100.0) (/ (inc speed-bin) 100.0)))
     (part 6 :target-distance-bin target-distance
           (if (= target-distance 9)
             "selected opponent is at least 9% of a lap ahead, or none is ahead"
             (format "selected opponent is approximately %d%%–%d%% of a lap ahead"
                     target-distance (inc target-distance))))
        (part 7 :tactical-status
              {:status status-name :target-lane lane-name}
              (format "local status %s; selected opponent is %s"
                      (name status-name) (name lane-name)))]))))

(defn decision-outcome
  "Causal one-second result aligned with a recorded decision."
  ([racer-id]
   (decision-outcome racer-id 0))
  ([racer-id offset]
   (az/value (telemetry/outcome-at racer-id offset))))

(defn- readable-entry
  [entry outcome include-raw?]
  (let [entry (assoc entry :outcome outcome)]
    (if include-raw?
      (-> entry
          (assoc :prompt (utf8-preview (:prompt_bytes entry)
                                      (:prompt_byte_count entry))
                 :response (utf8-preview (:response_bytes entry)
                                         (:response_byte_count entry)))
          (dissoc :prompt_bytes :response_bytes))
      (dissoc entry
              :prompt_bytes :response_bytes :prompt_byte_count
              :response_byte_count :prompt_truncated :response_truncated
              :input_tokens :output_tokens :input_token_count
              :output_token_count :best_token :sampler_state
              :observation_schema :action_schema :validation_code
              :tokenizer_version :quantization_version :quantization_format
              :training_data_fingerprint :training_data_sha256
              :model_fingerprint :action_head_fingerprint
              :action_head_training_revision :race_epoch :enqueue_tick
              :install_tick))))

(defn decision-log
  "Newest complete observation-to-intent event for one racer. Encoded prompts,
  responses, token IDs, and sampler state are returned only when
  `{:include-raw? true}` is passed."
  ([racer-id]
   (decision-log racer-id {}))
  ([racer-id {:keys [include-raw?] :or {include-raw? false}}]
   (readable-entry (az/value (telemetry/latest racer-id))
                   (decision-outcome racer-id)
                   include-raw?)))

(defn decision-logs
  "Newest-first bounded cognition history for one racer. Raw encoded model
  protocol is opt-in through `{:include-raw? true}`."
  ([racer-id]
   (decision-logs racer-id 16 {}))
  ([racer-id limit]
   (decision-logs racer-id limit {}))
  ([racer-id limit {:keys [include-raw?] :or {include-raw? false}}]
   (let [available (min (long (telemetry/decision-count racer-id))
                        (long telemetry/entries-per-racer)
                        (long limit))]
     (mapv (fn [offset]
             (let [entry (az/value (telemetry/entry-at racer-id offset))]
               (readable-entry entry (decision-outcome racer-id offset)
                               include-raw?)))
           (range available)))))

(defn capture-replay
  "Capture the currently retained accepted intents in deterministic install order."
  []
  (->> (range 8)
       (mapcat #(decision-logs % telemetry/entries-per-racer
                               {:include-raw? true}))
       (filter (every-pred :valid :accepted))
       (sort-by (juxt :install_tick :racer_id :revision))
       (mapv #(select-keys %
                           [:racer_id :rank :lap :item :action :target :urgent
                            :observation_schema :action_schema :revision
                            :install_tick :lane_target :target_speed
                            :model_fingerprint :action_head_fingerprint
                            :action_head_training_revision
                            :tokenizer_version :quantization_version
                            :quantization_format
                            :training_data_fingerprint
                            :training_data_sha256]))))

(defn load-replay!
  "Reset into native replay mode and load previously captured validated intents."
  [entries]
  (simulation/clear-replay!)
  (doseq [entry entries]
    (when-not (and (= protocol/model-fingerprint (:model_fingerprint entry))
                   (= protocol/action-head-fingerprint
                      (:action_head_fingerprint entry))
                   (= protocol/action-head-training-revision
                      (:action_head_training_revision entry))
                   (= protocol/tokenizer-version (:tokenizer_version entry))
                   (= protocol/quantization-version
                      (:quantization_version entry))
                   (= protocol/quantization-format
                      (:quantization_format entry))
                   (= protocol/training-data-fingerprint
                      (:training_data_fingerprint entry)))
      (throw (ex-info "Replay provenance is incompatible with this runtime"
                      {:entry (select-keys entry
                                           [:model_fingerprint
                                            :action_head_fingerprint
                                            :action_head_training_revision
                                            :tokenizer_version
                                            :quantization_version
                                            :quantization_format
                                            :training_data_fingerprint])
                       :runtime {:model_fingerprint protocol/model-fingerprint
                                 :action_head_fingerprint
                                 protocol/action-head-fingerprint
                                 :action_head_training_revision
                                 protocol/action-head-training-revision
                                 :tokenizer_version protocol/tokenizer-version
                                 :quantization_version
                                 protocol/quantization-version
                                 :quantization_format
                                 protocol/quantization-format
                                 :training_data_fingerprint
                                 protocol/training-data-fingerprint}})))
    (with-open [intent
                (simulation/ReplayIntent
                 {:valid true
                  :accepted true
                  :urgent (boolean (:urgent entry))
                  :racer (:racer_id entry)
                  :rank (:rank entry)
                  :lap (:lap entry)
                  :item (:item entry)
                  :action (:action entry)
                  :target (:target entry)
                  :observation_schema (:observation_schema entry)
                  :action_schema (:action_schema entry)
                  :reserved 0
                  :revision (:revision entry)
                  :install_tick (:install_tick entry)
                  :lane_target (:lane_target entry)
                  :target_speed (:target_speed entry)})]
      (when-not (simulation/append-replay-intent! intent)
        (throw (ex-info "Native replay rejected an intent"
                        {:entry entry
                         :replay (az/value (simulation/replay-summary))})))))
  (when-not (simulation/start-replay!)
    (throw (ex-info "Cannot start an empty replay" {})))
  (az/value (simulation/replay-summary)))

(defn replay-status
  []
  (az/value (simulation/replay-summary)))

(defn- without-raw-protocol
  [trace]
  (-> trace
      (update :observation dissoc :prompt :prompt-decoding :input-tokens)
      (update :intent dissoc :token :token-id)
      (dissoc :schemas :provenance :sampling)))

(defn decision-trace
  "Explain one recorded structured decision without inventing chain-of-thought.
  Raw protocol fields are opt-in through `{:include-raw? true}`."
  ([racer-id]
   (decision-trace racer-id 0 {}))
  ([racer-id offset]
   (decision-trace racer-id offset {}))
  ([racer-id offset {:keys [include-raw?] :or {include-raw? false}}]
   (let [entry (if (zero? offset)
                 (decision-log racer-id {:include-raw? true})
                 (nth (decision-logs racer-id (inc offset)
                                     {:include-raw? true})
                      offset nil))
         response (:response entry)
         code (when (seq response)
                (- (int (first response)) (int \A)))
         source (case (:source entry)
                  1 :llm
                  2 :replay
                  3 :human
                  :fallback)
         prompt-decoding (when entry (decode-observation-prompt entry))
         decoded (into {} (map (juxt :field :value)) prompt-decoding)
         trace
        (when entry
           {:racer racer-id
        :revision (:revision entry)
        :source source
        :schemas {:observation (:observation_schema entry)
                  :action (:action_schema entry)}
        :provenance {:model-fingerprint (:model_fingerprint entry)
                     :action-head-fingerprint (:action_head_fingerprint entry)
                     :action-head-training-revision
                     (:action_head_training_revision entry)
                     :tokenizer {:name :gpt2-base-byte
                                 :version (:tokenizer_version entry)}
                     :quantization {:name :granite-q4-0-mixed
                                    :version (:quantization_version entry)
                                    :format-code (:quantization_format entry)}
                     :training-data-fingerprint
                     (:training_data_fingerprint entry)
                     :training-data-sha256
                     (apply str
                            (map #(format "%02x" (bit-and 0xff (long %)))
                                 (:training_data_sha256 entry)))}
        :ticks {:race-epoch (:race_epoch entry)
                :enqueue (:enqueue_tick entry)
                :install (:install_tick entry)}
         :observation {:rank (:rank entry)
                      :lap (:lap entry)
                      :persona
                      (get persona-names
                           (get-in decoded [:target-persona :persona])
                           :unknown)
                      :item (:item entry)
                      :target (:target entry)
                      :progress (:progress entry)
                      :progress-bin (:progress-bin decoded)
                      :speed (:speed entry)
                      :speed-bin (:speed-bin decoded)
                      :urgent (:urgent entry)
                      :target-distance-bin (:target-distance-bin decoded)
                      :target-lane (get-in decoded [:tactical-status :target-lane])
                      :tactical-status (get-in decoded [:tactical-status :status])
                      :input-token-count (:input_token_count entry)
                      :model-step-count protocol/observation-model-step-count
                      :prompt (:prompt entry)
                      :prompt-decoding prompt-decoding
                      :input-tokens (take (:input_token_count entry)
                                          (:input_tokens entry))}
        :intent
        (cond
          (and code (<= 0 code 7))
          {:token response
           :token-id (+ 32 code)
           :lane (nth [:left :center :right] (mod code 3))
           :pace (nth [:steady :attack :maximum] (min 2 (quot code 3)))
           ;; Telemetry records the validated installed action. In particular,
           ;; an item-using macro with empty inventory is safely downgraded to
           ;; hold and must not be described as an attempted phantom use.
           :item (if (zero? (:action entry)) :hold :use)
           :target (:target entry)
           :lane-target (:lane_target entry)
           :target-speed (:target_speed entry)}

          (not= :llm source)
          {:lane (cond
                   (< (:lane_target entry) -0.025) :left
                   (> (:lane_target entry) 0.025) :right
                   :else :center)
           :pace (cond
                   (< (:target_speed entry) 0.076) :steady
                   (< (:target_speed entry) 0.084) :attack
                   :else :maximum)
           :item (if (zero? (:action entry)) :hold :use)
           :target (:target entry)
           :lane-target (:lane_target entry)
           :target-speed (:target_speed entry)}

          :else
          {:token response :valid false})
        :validation {:accepted (:accepted entry)
                     :code (:validation_code entry)
                     :deadline (case (:deadline_status entry)
                                 0 :on-time
                                 1 :expired
                                 :unknown)}
        :sampling {:state (:sampler_state entry)}
        :outcome (:outcome entry)
            :timing-us {:queue (:queue_us entry)
                        :prefill (:prefill_us entry)
                        :decode (:decode_us entry)
                        :total (:total_us entry)
                        :tokens-per-second (:tokens_per_second entry)}})]
     (if (or include-raw? (nil? trace))
       trace
       (without-raw-protocol trace)))))

(defn cognition-status
  "Aggregate native LLM/fallback timing and validation counters."
  []
  (az/value (telemetry/summary)))

(defn worker-status
  "Inspect the native inference workers and their eight bounded mailboxes."
  []
  (az/value (worker/summary)))

(defn start-headless!
  "Load the pinned model and start the same native workers used by the desktop.
  This is useful for tests and nREPL exploration without opening a window."
  []
  (let [existing (worker-status)]
    (if (:started existing)
      {:model (az/value (inference/inference-summary))
       :action-head (az/value (inference/action-head-status))
       :worker existing
       :owned false}
      (do
        (model/verify-assets!)
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
              (throw (ex-info "Native racing model rejected its pinned GGUF"
                              {:model loaded-model})))
            (when-not (:valid loaded-head)
              (inference/unload-model!)
              (throw (ex-info "Native racing action head rejected its pinned artifact"
                              {:action-head loaded-head})))
            (when-not (worker/start!)
              (inference/unload-model!)
              (throw (ex-info "Native racing inference workers did not start" {})))
            (reset! headless-owned? true)
            {:model loaded-model
             :action-head loaded-head
             :worker (worker-status)
             :owned true}))))))

(defn step-until-llm!
  "Advance the 120 Hz simulation in real time until a new LLM decision is
  installed, proving that the frame path remains live while inference runs."
  ([] (step-until-llm! 5000))
  ([timeout-ms]
   (let [initial (:llm_entries (cognition-status))
         deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
     (loop []
       (simulation/step!)
       (let [cognition (cognition-status)]
         (cond
           (> (:llm_entries cognition) initial)
           {:race (az/value (simulation/snapshot))
            :racer (az/value (simulation/racer-view 0))
            :decision (decision-log 0)
            :cognition cognition
            :worker (worker-status)}

           (< (System/nanoTime) deadline)
           (do (Thread/sleep 8) (recur))

           :else
           (throw (ex-info "No native LLM decision was installed before the deadline"
                           {:timeout-ms timeout-ms
                            :worker (worker-status)
                            :cognition cognition}))))))))

(defn stop-headless!
  "Stop resources started by `start-headless!`. Desktop ownership is untouched."
  []
  (when (compare-and-set! headless-owned? true false)
    (worker/stop!)
    (simulation/shutdown!)
    (inference/unload-model!))
  :stopped)

(defn racers
  []
  (->> (range 8)
       (mapv (comp az/value simulation/racer-view))
       (sort-by :rank)
       vec))

(defn configure-racer!
  "Edit one live racer for an inspectable REPL scenario. Omitted fields retain
  their current native values; `:item` accepts a keyword or numeric code."
  [racer-id {:keys [progress lane speed item shielded]}]
  (let [current (az/value (simulation/racer-view racer-id))
        item-code (if (keyword? item)
                    (get item-codes item ::unknown)
                    (or item (:item current)))]
    (when (= ::unknown item-code)
      (throw (ex-info "Unknown racing item"
                      {:item item :available item-names})))
    (simulation/configure-racer-state!
     racer-id
     (float (or progress (:progress current)))
     (float (or lane (:lane current)))
     (float (or speed (:speed current)))
     item-code
     (if (nil? shielded) (:shielded current) (boolean shielded)))
    (az/value (simulation/racer-view racer-id))))

(defn configure-intent!
  "Install the same bounded intent fields consumed from model output."
  [racer-id {:keys [lane speed item-action target]
             :or {item-action :hold}}]
  (let [current (az/value (simulation/racer-view racer-id))
        action (get item-action-codes item-action ::unknown)]
    (when (= ::unknown action)
      (throw (ex-info "Unknown racing item action"
                      {:item-action item-action :available [:hold :use]})))
    (simulation/configure-racer-intent!
     racer-id
     (float (or lane (:lane_target current)))
     (float (or speed (:target_speed current)))
     action
     (or target (:target current)))
    (az/value (simulation/racer-view racer-id))))

(defn hazards
  "Inspect every currently active native projectile or trap."
  []
  (->> (range simulation/hazard-capacity)
       (mapv (comp az/value simulation/hazard-view))
       (filterv :active)))

(defn observation
  "Inspect the exact bounded semantic observation available to one racer now."
  [racer-id]
  (let [view (az/value (simulation/current-observation racer-id))]
    (cond-> view
      (:valid view)
      (assoc :item-name (get item-names (:item view) :unknown)
             :persona-name (get persona-names (:persona view) :unknown)
             :target-lane-name (get lane-names (:target_lane view) :unknown)
             :tactical-status-name
             (get tactical-status-names (:tactical_status view) :unknown)))))

(defn observations
  "Inspect all eight private racer observations without exposing more world
  state to any native agent."
  []
  (mapv observation (range 8)))

(defn teams
  "Inspect all four Flecs-owned teams and their two fixed drivers."
  []
  (mapv (fn [team-id]
          (assoc (az/value (simulation/team-view team-id))
                 :name (nth team-names team-id)))
        (range 4)))

(defn team-radio-history
  "Human-readable newest-first communication between one strategist AI and
  its two drivers. Raw wire prompts are deliberately absent."
  ([team-id]
   (team-radio-history team-id 32))
  ([team-id limit]
   (let [available (min (long (simulation/team-radio-history-count team-id))
                        (long limit))
         team-name (nth team-names team-id :unknown)]
     (mapv
      (fn [offset]
        (let [{:keys [source target code tire_condition damage latency_us]
               :as entry}
              (az/value (simulation/team-radio-entry team-id offset))
              driver? (= source simulation/radio-source-driver)]
          (assoc entry
                 :team-name team-name
                 :from (if driver? (keyword (str "racer-" target))
                           (keyword (str (name team-name) "-strategist")))
                 :to (if driver? (keyword (str (name team-name) "-strategist"))
                         (keyword (str "racer-" target)))
                 :message (nth radio-messages code :unknown)
                 :tire-percent (* 100.0 tire_condition)
                 :damage-percent (* 100.0 damage)
                 :latency-ms (/ latency_us 1000.0))))
      (range available)))))

(defn all-team-radio-history
  "Communication history for all four teams, keyed by team name."
  []
  (into (array-map)
        (map-indexed (fn [team-id team-name]
                       [team-name (team-radio-history team-id)]))
        team-names))

(defn status
  []
  {:desktop (desktop/desktop-snapshot)
   :race (simulation/snapshot)
   :racers (racers)
   :teams (teams)
   :team-radio (all-team-radio-history)
   :cognition (cognition-status)
   :worker (worker-status)
   :cadence (simulation/cadence-summary)
   :replay (replay-status)
   :protocol {:observation-schema protocol/observation-schema-version
              :action-schema protocol/action-schema-version
              :model-fingerprint protocol/model-fingerprint
              :action-head-fingerprint protocol/action-head-fingerprint
              :action-head-training-revision
              protocol/action-head-training-revision}
   :action-head (inference/action-head-status)
   :renderer (renderer/renderer-snapshot)
   :compilation {:inference (az/stats 'racing-game.inference)
                 :worker (az/stats 'racing-game.worker)
                 :simulation (az/stats 'racing-game.simulation)
                 :render (az/stats 'racing-game.render)}})

(comment
  ;; Development: from examples/racing-game run `clojure -M:desktop`, then
  ;; connect Calva/CIDER to the printed port. This is the live native race:
  (status)
  (racers)
  (hazards)
  (observation 0)
  (observations)
  (teams)
  (all-team-radio-history)
  (configure-racer! 0 {:item :bolt :progress 0.20})
  (configure-intent! 0 {:item-action :use :target 1})
  (simulation/step!)
  (simulation/racer-view 0)
  (cognition-status)
  (worker-status)
  (worker/set-sampling-temperature! 0.35)
  (decision-log 0)
  (decision-logs 0 8)
  (decision-trace 0)
  (def replay (capture-replay))
  (load-replay! replay)
  (simulation/step-many! 1200)
  (replay-status)

  ;; Windowless proof of model → worker → simulation → telemetry:
  (start-headless!)
  (step-until-llm!)
  (stop-headless!)

  ;; P pauses; R explicitly resets. Evaluating one az/defn publishes it into
  ;; this running race without recreating the Flecs world or Vulkan window.
  ;; F1 toggles the native cognition overlay; the same can be done from nREPL.
  (simulation/toggle-paused!)
  (render/toggle-debug-overlay!)

  ;; JVM-free release build:
  ;;   clojure -M:standalone
  ;;   ./build/standalone/racing-game
  )
