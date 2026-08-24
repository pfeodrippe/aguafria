(ns racing-game.native-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing]]
            [racing-game.core :as core]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]
            [racing-game.tournament :as tournament]))

(defn close?
  [expected actual tolerance]
  (<= (Math/abs (- (double expected) (double actual))) tolerance))

(defn native-floats
  [^java.lang.foreign.Arena arena values]
  (let [segment (.allocate arena (* 4 (count values)) 4)]
    (doseq [[index value] (map-indexed vector values)]
      (.setAtIndex segment java.lang.foreign.ValueLayout/JAVA_FLOAT
                   index (float value)))
    segment))

(defn read-floats
  [segment count]
  (mapv #(.getAtIndex segment java.lang.foreign.ValueLayout/JAVA_FLOAT %)
        (range count)))

(deftest native-kernel-fixtures-test
  (az/await! 'racing-game.inference)
  (let [{:keys [q4_dot rms_first rms_last softmax_sum]}
        (az/value (inference/kernel-self-test))]
    (testing "Q4_0 scalar dequantization and dot product"
      (is (close? 32.0 q4_dot 1.0e-6)))
    (testing "RMSNorm reference values"
      (is (close? 0.36514837 rms_first 1.0e-5))
      (is (close? 1.4605935 rms_last 1.0e-5)))
    (testing "stable softmax remains normalized"
      (is (close? 1.0 softmax_sum 1.0e-6)))))

(deftest mamba-selective-step-fixture-test
  (az/await! 'racing-game.inference)
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [state (native-floats arena (map #(* 0.1 %) (range 1 13)))
          output (native-floats arena [0 0 0 0])
          hidden (native-floats arena [0.2 -0.3 0.4 0.5])
          dt (native-floats arena [-1.0 0.25])
          a (native-floats arena [-1.0 -2.0])
          b (native-floats arena [0.1 0.2 -0.15])
          c (native-floats arena [0.3 -0.25 0.2])
          d (native-floats arena [0.5 -0.75])
          bias (native-floats arena [0.1 -0.2])]
      (inference/mamba-selective-step!
       output state hidden dt a b c d bias 2 2 3)
      (is (every? true?
                  (map #(close? %1 %2 1.0e-6)
                       [0.125026441358 -0.063123499077
                        -0.269214023158 -0.329981912265]
                       (read-floats output 4))))
      (is (every? true?
                  (map #(close? %1 %2 1.0e-6)
                       [0.077918027757 0.155836055514 0.203050234546
                        0.274145184808 0.335005518829 0.441921625938
                        0.195099537825 0.247603802589 0.170785330710
                        0.273581770835 0.333270632079 0.231306072521]
                       (read-floats state 12)))))))

(deftest real-gguf-validation-test
  (when (.isFile (model/model-file))
    (model/verify!)
    (model/verify-action-head!)
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [path (.allocateFrom arena (str (model/model-file)))
            action-head-path
            (.allocateFrom arena (str (model/action-head-file)))
            summary (az/value (inference/load-model! path))
            output-norm (az/value (inference/tensor-info 0))
            token-embedding (az/value (inference/tensor-info 1))
            embedding-index (inference/find-tensor
                             (.allocateFrom arena "token_embd.weight"))
            ffn-down-index (inference/find-tensor
                            (.allocateFrom arena "blk.0.ffn_down.weight"))
            embedding-key (inference/find-metadata
                           (.allocateFrom arena
                                          "granitehybrid.embedding_length"))
            scale-key (inference/find-metadata
                       (.allocateFrom arena "granitehybrid.embedding_scale"))
            tokens-key (inference/find-metadata
                        (.allocateFrom arena "tokenizer.ggml.tokens"))
            prompt "R0 K8 L0 I3 T1\nA:"
            tokenized (az/value
                       (inference/tokenize-compact-ascii
                        (.allocateFrom arena prompt) (count prompt)))
            ffn-input (.allocate arena (* 4 2048) 4)]
        (dotimes [index 2048]
          (.set ffn-input java.lang.foreign.ValueLayout/JAVA_FLOAT
                (* 4 index) (float 1.0)))
        (is (:loaded summary))
        (is (:valid summary))
        (is (zero? (:error_code summary)))
        (is (= 3 (:version summary)))
        (is (= 402 (:tensor_count summary)))
        (is (= 47 (:metadata_count summary)))
        (is (= 233 (:f32_tensors summary)))
        (is (= 168 (:q4_0_tensors summary)))
        (is (= 1 (:q6_k_tensors summary)))
        (is (= 216073120 (:file_size summary)))
        (is (= [768 0 0 0] (:dimensions output-norm)))
        (is (= 0 (:ggml_type output-norm)))
        (is (= [768 100352 0 0] (:dimensions token-embedding)))
        (is (= 14 (:ggml_type token-embedding)))
        (is (= 1 embedding-index))
        (is (= "token_embd.weight"
               (apply str
                      (map #(char (inference/tensor-name-byte embedding-index %))
                           (range 17)))))
        (is (= 4096 (inference/find-tensor (.allocateFrom arena "missing"))))
        (is (= 13 embedding-key))
        (is (= 768 (inference/metadata-u32 embedding-key 0)))
        (is (= 12.0 (double (inference/metadata-f32 scale-key 0.0))))
        (is (= {:value_type 9 :element_type 8 :element_count 100352}
               (select-keys (az/value (inference/metadata-info tokens-key))
                            [:value_type :element_type :element_count])))
        (is (:valid tokenized))
        (is (false? (:truncated tokenized)))
        (is (= [49 15 220 42 23 220 43 15 220 40 18 220 51 16 198 32 25]
               (vec (take (:token_count tokenized) (:tokens tokenized)))))
        (is (pos? (:data_address output-norm)))
        (is (= 3072 (- (:data_address token-embedding)
                       (:data_address output-norm))))
        (is (close? 0.023958206 (inference/embedding-value 0 0) 1.0e-8))
        (is (close? -0.03443992 (inference/embedding-value 0 2) 1.0e-8))
        (is (close? -0.81628418
                    (inference/tensor-row-dot ffn-down-index 0 ffn-input)
                    1.0e-6))
        (is (close? 0.19770462
                    (inference/mamba-layer-zero-probe 0 0)
                    1.0e-6))
        (is (= [2 119 132 141 167 176 215 224 341 350 389]
               (mapv inference/layer-base-index
                     [0 9 10 11 13 14 17 18 27 28 31])))
        (is (= [false true true true true false]
               (mapv inference/attention-layer? [0 10 13 17 27 31])))
        (is (= [4 0 1 2 3 4]
               (mapv inference/attention-layer-slot [0 10 13 17 27 31])))
        (is (close? 0.49001826
                    (inference/attention-layer-probe 10 0 0)
                    2.0e-6))
        (is (close? 0.11414509
                    (inference/mamba-layer-zero-full-probe 0 0)
                    2.0e-6))
        (try
          (is (inference/initialize-sequences!))
          (let [racer-zero (az/value (inference/forward-token! 0 0))
                racer-one (az/value (inference/forward-token! 1 0))
                sequences (az/value (inference/sequence-summary))]
            (is (:valid racer-zero))
            (is (:valid racer-one))
            (is (= 36 (:best_token racer-zero) (:best_token racer-one)))
            (is (close? (:hidden_first racer-zero)
                        (:hidden_first racer-one) 1.0e-7))
            (is (close? 1112.6677 (:hidden_checksum racer-zero) 1.0e-3))
            (is (= [1 1 0 0 0 0 0 0] (:positions sequences)))
            (is (= 191463424 (:state_bytes sequences))))
          (finally
            (inference/free-sequences!)))
        (let [head (az/value (inference/load-action-head! action-head-path))
              action-prompts ["SAAAAFHA" "SBAAAFHA" "SCAAAFHA"
                              "SAAAAFHB" "SAAABFHA" "SAAACFHA"
                              "SAAADFHA" "SAAAEFHA"]
              features (.allocate arena (* 6144 Float/BYTES) Float/BYTES)]
          (is (:loaded head))
          (is (:valid head))
          (is (zero? (:error_code head)))
          (is (= 6144 (:input_count head)))
          (is (= 8 (:output_count head)))
          (is (= protocol/observation-schema-version
                 (:observation_schema head)))
          (is (= protocol/action-schema-version (:action_schema head)))
          (try
            (is (inference/initialize-sequences!))
            (doseq [[action prompt] (map-indexed vector action-prompts)]
              (let [report
                    (az/value
                     (inference/forward-compact-prompt!
                      0 (.allocateFrom arena prompt) 8 true))]
                (is (:valid report))
                (is (inference/copy-action-features! 0 features))
                (is (= 7 (:position report)))
                (is (every? #(Float/isFinite (float %))
                            (:candidate_logits report)))
                (is (= (+ 32 action) (:best_token report)))))
            (finally
              (inference/free-sequences!)
              (inference/unload-action-head!))))))))

(deftest eight-racer-native-race-test
  (az/await!)
  (simulation/reset!)
  (simulation/step-many! 1200)
  (let [snapshot (az/value (simulation/snapshot))
        racers (mapv #(az/value (simulation/racer-view %)) (range 8))
        cognition (az/value (telemetry/summary))
        recorded (reduce + (map #(min telemetry/entries-per-racer
                                     (telemetry/decision-count %))
                                (range 8)))
        latest (mapv #(az/value (telemetry/latest %)) (range 8))
        outcomes
        (mapcat (fn [racer-id]
                  (map #(az/value (telemetry/outcome-at racer-id %))
                       (range (min telemetry/entries-per-racer
                                   (telemetry/decision-count racer-id)))))
                (range 8))]
    (is (= 8 (:racers snapshot)))
    (is (= 8 (count racers)))
    (is (= (set (range 1 9)) (set (map :rank racers))))
    (is (pos? (:decisions snapshot)))
    (is (pos? (:items_used snapshot)))
    (is (<= (:hits snapshot) (* 7 (:items_used snapshot))))
    (is (pos? (:hazards_spawned snapshot)))
    (is (<= (:hazards_spawned snapshot) (:items_used snapshot)))
    (is (<= (:active_hazards snapshot) simulation/hazard-capacity))
    (is (zero? (:invalid_decisions snapshot)))
    (is (= recorded (:total_entries cognition)))
    (is (<= (:total_entries cognition)
            (* 8 telemetry/entries-per-racer)))
    (is (= (:total_entries cognition) (:fallback_entries cognition)))
    (is (zero? (:llm_entries cognition)))
    (is (= (:total_entries cognition) (:accepted_entries cognition)))
    (is (every? :valid latest))
    (is (every? zero? (map :source latest)))
    (is (every? #(= protocol/observation-schema-version
                    (:observation_schema %))
                latest))
    (is (every? #(= protocol/action-schema-version
                    (:action_schema %))
                latest))
    (is (every? #(= protocol/model-fingerprint
                    (:model_fingerprint %))
                latest))
    (is (every? #(= protocol/action-head-fingerprint
                    (:action_head_fingerprint %))
                latest))
    (is (every? #(= protocol/action-head-training-revision
                    (:action_head_training_revision %))
                latest))
    (is (every? #(<= (:enqueue_tick %) (:install_tick %)) latest))
    (is (every? pos? (map :race_epoch latest)))
    (is (every? :valid outcomes))
    (is (pos? (:resolved_outcomes cognition)))
    (is (pos? (:attributed_item_uses cognition)))
    (is (<= (:attributed_hits cognition) (:hits snapshot)))
    (is (pos? (:average_progress_gain cognition)))
    (is (every? #(= 64 (count (:input_tokens %))) latest))
    (is (every? #(= 16 (count (:output_tokens %))) latest))
    (simulation/step-many! 6000)
    (let [finish (az/value (simulation/snapshot))
          finished-racers
          (mapv #(az/value (simulation/racer-view %)) (range 8))]
      (is (= 8 (:finished finish)))
      (is (= (set (range 1 9)) (set (map :rank finished-racers))))
      (is (every? :finished finished-racers))
      (is (every? pos? (map :finish_tick finished-racers)))
      (is (apply <= (map :finish_tick (sort-by :rank finished-racers)))))))

(deftest deterministic-intent-replay-test
  (az/await!)
  (simulation/clear-replay!)
  (simulation/reset!)
  (simulation/step-many! 1200)
  (let [snapshot-keys
        [:tick :finished :leader :leader_lap :leader_progress :decisions
         :urgent_decisions :invalid_decisions :items_used :hits
         :active_hazards :hazards_spawned]
        racer-keys
        [:rank :lap :finished :item :shielded :item_action :progress :speed
         :lane_target :target_speed :target :decisions :finish_tick]
        original-snapshot
        (select-keys (az/value (simulation/snapshot)) snapshot-keys)
        original-racers
        (mapv #(select-keys (az/value (simulation/racer-view %)) racer-keys)
              (range 8))
        outcome-keys
        [:valid :resolved :item_used :racer_id :start_rank :end_rank
         :hits_dealt :revision :start_tick :resolved_tick
         :start_absolute_progress :progress_gain :rank_gain]
        original-outcomes
        (mapv (fn [racer-id]
                (mapv #(select-keys (az/value
                                     (telemetry/outcome-at racer-id %))
                                    outcome-keys)
                      (range (min telemetry/entries-per-racer
                                  (telemetry/decision-count racer-id)))))
              (range 8))
        replay (core/capture-replay)
        replay-count (count replay)]
    (is (<= 1 replay-count simulation/replay-capacity))
    (is (= {:active true :loaded replay-count
            :installed 0 :remaining replay-count}
           (core/load-replay! replay)))
    (simulation/step-many! 1200)
    (let [replayed-snapshot
          (select-keys (az/value (simulation/snapshot)) snapshot-keys)
          replayed-racers
          (mapv #(select-keys (az/value (simulation/racer-view %)) racer-keys)
                (range 8))
          replayed-outcomes
          (mapv (fn [racer-id]
                  (mapv #(select-keys (az/value
                                       (telemetry/outcome-at racer-id %))
                                      outcome-keys)
                        (range (min telemetry/entries-per-racer
                                    (telemetry/decision-count racer-id)))))
                (range 8))
          replay-status (core/replay-status)
          cognition (az/value (telemetry/summary))]
      (is (= original-snapshot replayed-snapshot))
      (is (= original-racers replayed-racers))
      (is (= original-outcomes replayed-outcomes))
      (is (= {:active true :loaded replay-count
              :installed replay-count :remaining 0}
             replay-status))
      (is (= replay-count (:replay_entries cognition)))
      (is (zero? (:llm_entries cognition)))
      (is (zero? (:fallback_entries cognition))))
    (simulation/stop-replay!)
    (simulation/clear-replay!)))

(deftest deterministic-native-tournament-test
  (is (= 1.0 (double (telemetry/outcome-window-seconds))))
  (let [report (tournament/run! {:seeds [0 1 2]
                                 :max-ticks 7200
                                 :chunk-ticks 300
                                 :mode :fallback})
        scoreboard (:scoreboard report)]
    (is (= 3 (:race-count report)))
    (is (= 3 (:complete-races report)))
    (is (= [0 1 2] (:seeds report)))
    (is (= 8 (count scoreboard)))
    (is (= (set (range 8)) (set (map :racer scoreboard))))
    (is (= (* 3 (reduce + (range 1 9)))
           (reduce + (map :points scoreboard))))
    (is (every? #(= 3 (:races %)) scoreboard))
    (is (every? pos? (map :decisions scoreboard)))
    (is (every? pos? (map :resolved-decisions scoreboard)))
    (is (every? pos? (map :item-uses scoreboard)))
    (is (= protocol/model-fingerprint
           (get-in report [:provenance :model-fingerprint])))
    (is (= protocol/action-head-fingerprint
           (get-in report [:provenance :action-head-fingerprint])))))
