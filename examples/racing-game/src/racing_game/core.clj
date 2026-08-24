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

(defn decision-outcome
  "Causal one-second result aligned with a recorded decision."
  ([racer-id]
   (decision-outcome racer-id 0))
  ([racer-id offset]
   (az/value (telemetry/outcome-at racer-id offset))))

(defn decision-log
  "Newest complete observation-to-intent event for one racer."
  [racer-id]
  (let [entry (az/value (telemetry/latest racer-id))]
    (-> entry
        (assoc :prompt (utf8-preview (:prompt_bytes entry)
                                    (:prompt_byte_count entry))
               :response (utf8-preview (:response_bytes entry)
                                       (:response_byte_count entry))
               :outcome (decision-outcome racer-id))
        (dissoc :prompt_bytes :response_bytes))))

(defn decision-logs
  "Newest-first bounded cognition history for one racer."
  ([racer-id]
   (decision-logs racer-id 16))
  ([racer-id limit]
   (let [available (min (long (telemetry/decision-count racer-id))
                        (long telemetry/entries-per-racer)
                        (long limit))]
     (mapv (fn [offset]
             (let [entry (az/value (telemetry/entry-at racer-id offset))]
               (-> entry
                   (assoc :prompt (utf8-preview (:prompt_bytes entry)
                                               (:prompt_byte_count entry))
                          :response (utf8-preview (:response_bytes entry)
                                                 (:response_byte_count entry))
                          :outcome (decision-outcome racer-id offset))
                   (dissoc :prompt_bytes :response_bytes))))
           (range available)))))

(defn capture-replay
  "Capture the currently retained accepted intents in deterministic install order."
  []
  (->> (range 8)
       (mapcat #(decision-logs % telemetry/entries-per-racer))
       (filter (every-pred :valid :accepted))
       (sort-by (juxt :install_tick :racer_id :revision))
       (mapv #(select-keys %
                           [:racer_id :rank :lap :item :action :target :urgent
                            :observation_schema :action_schema :revision
                            :install_tick :lane_target :target_speed
                            :model_fingerprint :action_head_fingerprint
                            :action_head_training_revision]))))

(defn load-replay!
  "Reset into native replay mode and load previously captured validated intents."
  [entries]
  (simulation/clear-replay!)
  (doseq [entry entries]
    (when-not (and (= protocol/model-fingerprint (:model_fingerprint entry))
                   (= protocol/action-head-fingerprint
                      (:action_head_fingerprint entry))
                   (= protocol/action-head-training-revision
                      (:action_head_training_revision entry)))
      (throw (ex-info "Replay provenance is incompatible with this runtime"
                      {:entry (select-keys entry
                                           [:model_fingerprint
                                            :action_head_fingerprint
                                            :action_head_training_revision])
                       :runtime {:model_fingerprint protocol/model-fingerprint
                                 :action_head_fingerprint
                                 protocol/action-head-fingerprint
                                 :action_head_training_revision
                                 protocol/action-head-training-revision}})))
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

(defn decision-trace
  "Explain one recorded structured decision without inventing chain-of-thought."
  ([racer-id]
   (decision-trace racer-id 0))
  ([racer-id offset]
   (let [entry (if (zero? offset)
                 (decision-log racer-id)
                 (nth (decision-logs racer-id (inc offset)) offset nil))
         response (:response entry)
         code (when (seq response)
                (- (int (first response)) (int \A)))]
     (when entry
       {:racer racer-id
        :revision (:revision entry)
        :source (case (:source entry)
                  1 :llm
                  2 :replay
                  :fallback)
        :schemas {:observation (:observation_schema entry)
                  :action (:action_schema entry)}
        :provenance {:model-fingerprint (:model_fingerprint entry)
                     :action-head-fingerprint (:action_head_fingerprint entry)
                     :action-head-training-revision
                     (:action_head_training_revision entry)}
        :ticks {:race-epoch (:race_epoch entry)
                :enqueue (:enqueue_tick entry)
                :install (:install_tick entry)}
        :observation {:rank (:rank entry)
                      :lap (:lap entry)
                      :item (:item entry)
                      :target (:target entry)
                      :progress (:progress entry)
                      :speed (:speed entry)
                      :urgent (:urgent entry)
                      :prompt (:prompt entry)
                      :input-tokens (take (:input_token_count entry)
                                          (:input_tokens entry))}
        :intent (if (and code (<= 0 code 7))
                  {:token response
                   :lane (nth [:left :center :right] (mod code 3))
                   :pace (nth [:steady :attack :maximum] (min 2 (quot code 3)))
                   :item (if (>= code 4) :use :hold)
                   :target (:target entry)
                   :lane-target (:lane_target entry)
                   :target-speed (:target_speed entry)}
                  {:token response :valid false})
        :validation {:accepted (:accepted entry)
                     :code (:validation_code entry)
                     :deadline (:deadline_status entry)}
        :sampling {:state (:sampler_state entry)}
        :outcome (:outcome entry)
        :timing-us {:queue (:queue_us entry)
                    :prefill (:prefill_us entry)
                    :decode (:decode_us entry)
                    :total (:total_us entry)
                    :tokens-per-second (:tokens_per_second entry)}}))))

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

(defn hazards
  "Inspect every currently active native projectile or trap."
  []
  (->> (range simulation/hazard-capacity)
       (mapv (comp az/value simulation/hazard-view))
       (filterv :active)))

(defn status
  []
  {:desktop (desktop/desktop-snapshot)
   :race (simulation/snapshot)
   :racers (racers)
   :cognition (cognition-status)
   :worker (worker-status)
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
  ;; D toggles the native cognition overlay; the same can be done from nREPL.
  (simulation/toggle-paused!)
  (render/toggle-debug-overlay!)

  ;; JVM-free release build:
  ;;   clojure -M:standalone
  ;;   ./build/standalone/racing-game
  )
