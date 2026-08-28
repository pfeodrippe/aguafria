(ns racing-game.native-test
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [racing-game.assets :as assets]
            [racing-game.core :as core]
            [racing-game.dataset :as dataset]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol]
            [racing-game.log :as race-log]
            [racing-game.monitor :as monitor]
            [racing-game.render :as render]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]
            [racing-game.track :as track]
            [racing-game.train-action-head :as train-action-head]
            [racing-game.train-team-head :as train-team-head]
            [racing-game.tournament :as tournament]
            [racing-game.worker :as worker])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

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

(defn native-bytes
  [^java.lang.foreign.Arena arena values]
  (let [segment (.allocate arena (count values) 1)]
    (doseq [[index value] (map-indexed vector values)]
      (.setAtIndex segment java.lang.foreign.ValueLayout/JAVA_BYTE
                   index (unchecked-byte value)))
    segment))

(defn little-endian-bytes
  [value width]
  (mapv #(bit-and 0xff (bit-shift-right value (* 8 %))) (range width)))

(defn unsupported-layout-gguf
  []
  (let [descriptor
        (vec
         (concat
          [71 71 85 70]
          (little-endian-bytes 3 4)
          (little-endian-bytes 1 8)
          (little-endian-bytes 0 8)
          (little-endian-bytes 1 8)
          [120]
          (little-endian-bytes 1 4)
          (little-endian-bytes 1 8)
          (little-endian-bytes 99 4)
          (little-endian-bytes 0 8)))]
    (into descriptor (repeat (- 64 (count descriptor)) 0))))

(defn read-floats
  [segment count]
  (mapv #(.getAtIndex segment java.lang.foreign.ValueLayout/JAVA_FLOAT %)
        (range count)))

(defn load-native-replay-file
  [file]
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (az/value
     (simulation/load-replay-file! (.allocateFrom arena (str file))))))

(defn await-worker-result
  [racer-id timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [result (az/value (worker/result-for racer-id 0))]
        (cond
          (:valid result) result
          (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
          :else (throw (ex-info "Native worker result timed out"
                                {:racer racer-id
                                 :worker (core/worker-status)})))))))

(defn await-workers-idle
  [timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [summary (core/worker-status)]
        (cond
          (zero? (:pending summary)) summary
          (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
          :else (throw (ex-info "Native workers did not become idle"
                                {:worker summary})))))))

(deftest human-readable-decision-explanation-test
  (let [trace {:racer 0
               :revision 17
               :source :llm
               :observation {:rank 3 :lap 1 :persona :balanced
                             :progress 0.42 :progress-bin 4
                             :speed 0.081 :speed-bin 8
                             :item 2 :target 4 :urgent true
                             :target-distance-bin 3 :target-lane :left
                             :tactical-status :hazard-near
                             :input-token-count 8
                             :model-step-count 2
                             :prompt (str "Driver 0, balanced. Rank 3/8; lap 1; "
                                          "progress 42%; speed 8. Item trap. "
                                          "Rival 4: gap 3, left. "
                                          "Track hazard nearby. Urgent.")
                             :input-tokens [50 35 32 32 32 32 35 33]}
               :intent {:lane :right :pace :attack :item :use :target 4
                        :token "F" :token-id 37}
               :validation {:accepted true :deadline :on-time}
               :timing-us {:total 481230 :tokens-per-second 16.645}
               :outcome {:resolved true :progress_gain 0.0697
                         :start_rank 3 :end_rank 2 :hits_dealt 1
                         :item_used true}}
        explanation (race-log/explain trace)
        fallback
        (-> trace
            (assoc :source :fallback
                   :validation {:accepted true :deadline :expired}
                   :outcome {:resolved false})
            (update :observation dissoc :prompt)
            (assoc-in [:observation :persona] :unknown)
            (assoc-in [:observation :target-distance-bin] nil)
            (assoc-in [:observation :target-lane] nil)
            (assoc-in [:observation :tactical-status] nil))
        fallback-explanation (race-log/explain fallback)]
    (is (str/includes? explanation
                       "Saw: Driver 0, balanced. Rank 3/8; lap 1"))
    (is (str/includes? explanation "Track hazard nearby. Urgent."))
    (is (str/includes? explanation
                       "Chose: move right · attack pace · use trap · target racer 4"))
    (is (str/includes? explanation
                       "Granite AI · 481.2 ms · 16.65 model steps/s · on time"))
    (is (str/includes? explanation
                       "Result after 1 second: +6.97% of a lap · rank 3rd to 2nd"))
    (is (not (str/includes? explanation "token ID")))
    (is (str/includes? (race-log/explain-protocol trace)
                       "Driver 0, balanced"))
    (is (str/includes? fallback-explanation
                       "safe fallback · Granite missed its deadline"))
    (is (str/includes? fallback-explanation
                       "State: 3rd of 8 · lap 2"))
    (is (str/includes? fallback-explanation "Fallback action:"))
    (is (not (str/includes? fallback-explanation "unknown")))
    (is (= "No model prompt or output exists for this fallback action."
           (race-log/explain-protocol fallback)))
    (let [cognition {:llm_entries 1
                     :accepted_entries 1
                     :rejected_entries 0
                     :resolved_outcomes 1
                     :average_tokens_per_second 16.645}
          normal (race-log/text-report [trace] cognition {})
          raw (race-log/text-report [trace] cognition {:include-raw? true})]
      (is (str/includes? normal "Technical protocol details are hidden."))
      (is (str/includes? normal "Driver 0, balanced"))
      (is (not (str/includes? normal "token ID")))
      (is (str/includes? raw "Raw protocol:"))
      (is (str/includes? raw "Driver 0, balanced"))
      (is (str/includes? raw "token ID 37"))
      (is (= {:controller
              "Racer 0 · decision 17 · Granite AI · 481.2 ms · 16.65 model steps/s · on time"
              :situation
              (second (str/split-lines explanation))
              :decision
              "Chose: move right · attack pace · use trap · target racer 4."
              :outcome
              "Result after 1 second: +6.97% of a lap · rank 3rd to 2nd · 1 hit · item used."}
             (race-log/explanation trace))))))

(deftest development-monitor-abi-and-privacy-test
  (az/await!)
  (try
    (monitor/set-raw-protocol-visible! false)
    (monitor/refresh!)
    (let [status (monitor/status)
          racer (first (:racers status))]
      (is (monitor/abi-valid?))
      (is (false? (:active status)))
      (is (false? (:overlay-installed status)))
      (is (false? (:raw-protocol-visible status)))
      (is (= 8 (count (:racers status))))
      (is (zero? (:input_token_count racer)))
      (is (every? zero? (:response racer)))
      (monitor/set-raw-protocol-visible! true)
      (is (monitor/raw-protocol-visible?)))
    (finally
      (monitor/set-raw-protocol-visible! false))))

(deftest deterministic-native-decision-corpus-test
  (let [options {:seeds [0 1]
                 :ticks-per-seed 240
                 :sample-every 8
                 :limit 128
                 :require-complete-coverage? false}
        first-corpus (dataset/generate options)
        second-corpus (dataset/generate options)
        expert (dataset/expert-evaluation)
        rollout-row (first (filter #(= :native-race (:source %))
                                   (:rows first-corpus)))
        rollout-action (:teacher-action rollout-row)
        first-rollout (dataset/rollout-action! rollout-row rollout-action)
        second-rollout (dataset/rollout-action! rollout-row rollout-action)
        rollout-report
        (dataset/rollout-evaluation! [rollout-row]
                                     (constantly rollout-action))]
    (is (= 128 (:count first-corpus)))
    (is (= (:sha256 first-corpus) (:sha256 second-corpus)))
    (is (= (:rows first-corpus) (:rows second-corpus)))
    (is (= #{:native-item-anchor :native-race}
           (set (map :source (:rows first-corpus)))))
    (is (= (set (range 7))
           (set (keys (get-in first-corpus [:coverage :items])))))
    (is (= 18 (:cases expert)))
    (is (= 18 (:accepted expert)))
    (is (= 1.0 (:accuracy expert)))
    (is (= rollout-action (:action first-rollout)))
    (is (= (select-keys first-rollout
                        [:progress-gain :rank-improvement :hits :contacts
                         :items-used :finished :before :after])
           (select-keys second-rollout
                        [:progress-gain :rank-improvement :hits :contacts
                         :items-used :finished :before :after])))
    (is (= 1 (:cases rollout-report)))
    (is (= 1 (:accepted rollout-report)))
    (is (= 1.0 (:accuracy rollout-report)))
    (is (<= 0.0 (:average-regret rollout-report)))
    (is (= rollout-action
           (get-in rollout-report [:results 0 :action])))))

(deftest native-model-golden-tactics-test
  (when (.isFile (model/model-file))
    (let [report (dataset/model-evaluation!)]
      (is (= 18 (:cases report)))
      (is (= 16 (:accepted report)))
      (is (= (/ 16.0 18.0) (:accuracy report)))
      (is (= #{:bolt-near :surge-midfield}
             (set (map :name (:failures report)))))
      (is (true? (get-in report [:policy :all-covered?])))
      (is (empty? (get-in report [:policy :missing])))
      (is (= #{:bolt :trap :boost :shield :pulse :surge}
             (set (keys (get-in report [:policy :items])))))
      (is (every? (fn [[_ {:keys [accepted]}]] (pos? accepted))
                  (get-in report [:policy :items]))))))

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

(deftest release-assets-and-bounded-parser-test
  (is (= (:corpus-sha256 (model/action-head-entry))
         (train-action-head/corpus-sha256
          (train-action-head/training-corpus))))
  (is (= (:corpus-sha256 (model/team-head-entry))
         (train-team-head/corpus-sha256
          (train-team-head/balanced-scenarios 72))))
  (is (= (mapv #(bit-and (int %) 0xff)
               (.parseHex (java.util.HexFormat/of)
                          (:sha256 (model/action-head-entry))))
         (az/value assets/expected-action-head-sha256)))
  (is (= (mapv #(bit-and (int %) 0xff)
               (.parseHex (java.util.HexFormat/of)
                          (:sha256 (model/team-head-entry))))
         (az/value assets/expected-team-head-sha256)))
  (let [release (model/verify-release-notices!)]
    (is (:verified? release))
    (is (= :apache-2.0 (:license release)))
    (is (= 2 (count (:files release))))
    (is (every? pos? (map :bytes (:files release)))))
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [malformed (native-bytes arena (repeat 32 65))
          parsed (az/value (inference/parse-gguf malformed 32))
          unsupported-bytes (unsupported-layout-gguf)
          unsupported
          (az/value
           (inference/parse-gguf
            (native-bytes arena unsupported-bytes)
            (count unsupported-bytes)))
          missing (az/value
                   (inference/load-model!
                    (.allocateFrom arena "/definitely/missing/racing.gguf")))]
      (is (false? (:valid parsed)))
      (is (= inference/parser-bad-magic (:error_code parsed)))
      (is (false? (:valid unsupported)))
      (is (= inference/parser-unsupported-type
             (:error_code unsupported)))
      (is (false? (:loaded missing)))
      (is (= inference/model-file-not-found (:error_code missing))))))

(deftest aspect-safe-world-projection-test
  (az/await! 'racing-game.render)
  (let [wide (az/value (render/configure-world-scale! 1600 900))
        portrait (az/value (render/configure-world-scale! 900 1600))
        square (az/value (render/configure-world-scale! 900 900))]
    (is (close? (/ 900.0 1600.0) (:x wide) 1.0e-6))
    (is (close? 1.0 (:y wide) 1.0e-6))
    (is (close? 1.0 (:x portrait) 1.0e-6))
    (is (close? (/ 900.0 1600.0) (:y portrait) 1.0e-6))
    (is (= {:x 1.0 :y 1.0} square))
    (is (close? (* 1600.0 (:x wide)) (* 900.0 (:y wide)) 1.0e-4))
    (is (close? (* 900.0 (:x portrait))
                (* 1600.0 (:y portrait)) 1.0e-4))))

(deftest track-projection-and-fixed-tick-invariants-test
  (az/await! 'racing-game.track)
  (testing "the procedural track is closed and projects signed lanes"
    (is (close? 0.9 (track/wrap-progress -0.1) 1.0e-6))
    (is (close? 0.1 (track/wrap-progress 1.1) 1.0e-6))
    (let [start (az/value (track/pose 0.0 0.0))
          end (az/value (track/pose 1.0 0.0))]
      (is (close? (:x start) (:x end) 1.0e-5))
      (is (close? (:y start) (:y end) 1.0e-5)))
    (doseq [progress [0.0 0.07 0.25 0.49 0.75 0.93]
            lane [-0.075 0.0 0.075]]
      (let [{:keys [x y]} (az/value (track/pose progress lane))
            projection (az/value (track/project x y))
            difference (Math/abs (- (double progress)
                                    (double (:progress projection))))
            circular-error (min difference (- 1.0 difference))]
        (is (< circular-error 2.0e-4)
            (str "progress projection " progress " at lane " lane))
        (is (close? lane (:lane projection) 2.0e-4)
            (str "signed lane projection " progress " at lane " lane))
        (is (close? (* lane lane) (:distance_squared projection) 3.0e-5)
            (str "centerline distance " progress " at lane " lane)))))
  (testing "fixed-tick dynamics remain bounded, placed, and reproducible"
    (try
      (worker/stop!)
      (simulation/configure-countdown! 0)
      (simulation/set-human-controlled! false)
      (simulation/set-items-enabled! false)
      (simulation/set-race-seed! 73)
      (simulation/reset!)
      (simulation/step-many! 600)
      (let [racers (mapv #(az/value (simulation/racer-view %)) (range 8))
            physical-keys
            [:id :rank :lap :checkpoint :finished :item :shielded
             :progress :lane :speed :x :y :heading :finish_tick]
            snapshot-keys
            [:state :tick :race_seed :racers :finished :leader :leader_lap
             :leader_progress :items_used :hits :contacts :active_hazards
             :hazards_spawned]
            first-physical
            {:racers (mapv #(select-keys % physical-keys) racers)
             :snapshot (select-keys (az/value (simulation/snapshot))
                                    snapshot-keys)}
            placement (sort-by (juxt (comp - :lap) (comp - :progress) :id)
                               racers)]
        (is (= (range 1 9) (sort (map :rank racers))))
        (is (= (range 1 9) (map :rank placement)))
        (doseq [{:keys [progress lane speed x y heading] :as racer} racers]
          (is (every? #(Double/isFinite (double %))
                      [progress lane speed x y heading]))
          (is (<= 0.0 progress 1.0))
          (is (<= -0.075001 lane 0.075001))
          (is (<= 0.0 speed 0.16))
          (let [projected (az/value (track/project x y))
                difference (Math/abs (- (double progress)
                                        (double (:progress projected))))
                circular-error (min difference (- 1.0 difference))]
            (is (< circular-error 3.0e-4) (str "world progress " racer))
            (is (close? lane (:lane projected) 3.0e-4)
                (str "world lane " racer))))
        (simulation/reset!)
        (simulation/step-many! 600)
        (is (= first-physical
               {:racers
                (mapv #(select-keys
                        (az/value (simulation/racer-view %)) physical-keys)
                      (range 8))
                :snapshot
                (select-keys (az/value (simulation/snapshot)) snapshot-keys)})))
      (finally
        (simulation/set-items-enabled! true)
        (simulation/set-race-seed! 0)
        (simulation/reset!)))))

(deftest race-lifecycle-and-reference-control-test
  (az/await! 'racing-game.simulation)
  (try
    (simulation/configure-countdown! 5)
    (simulation/reset!)
    (let [initial (az/value (simulation/snapshot))
          progress (:progress (az/value (simulation/racer-view 0)))]
      (is (= simulation/race-state-countdown (:state initial)))
      (is (= 5 (:countdown_ticks initial)))
      (is (false? (:human_controlled initial)))
      (let [racers (into {} (map (juxt :id identity)) (core/racers))
            observation (core/observation 0)
            self (racers 0)
            target (racers (:target observation))]
        (is (:valid observation))
        (is (= (:rank self) (:rank observation)))
        (is (= (:lap self) (:lap observation)))
        (is (= (:item self) (:item observation)))
        (is (contains? #{:cautious :balanced :bold}
                       (:persona-name observation)))
        (is (contains? #{:left :same-lane :right}
                       (:target-lane-name observation)))
        (is (= :clear (:tactical-status-name observation)))
        (is (<= 0 (:target_distance observation) 9))
        (when-not (= 0 (:target observation))
          (is (> (+ (:lap target) (:progress target))
                 (+ (:lap self) (:progress self))))))
      (simulation/step-many! 4)
      (let [waiting (az/value (simulation/snapshot))]
        (is (= simulation/race-state-countdown (:state waiting)))
        (is (= 1 (:countdown_ticks waiting)))
        (is (close? progress
                    (:progress (az/value (simulation/racer-view 0)))
                    1.0e-8)))
      (simulation/step!)
      (is (= simulation/race-state-running
             (:state (az/value (simulation/snapshot)))))
      (is (pos? (:progress (az/value (simulation/racer-view 0))))))
    (is (= [0 1 2 3]
           (mapv simulation/checkpoint-for-progress
                 [0.0 0.25 0.50 0.75])))
    (is (simulation/racers-overlap? 0.10 0.0 0.105 0.02))
    (is (simulation/racers-overlap? 0.998 0.0 1.002 0.02))
    (is (false? (simulation/racers-overlap? 0.10 -0.075 0.105 0.075)))
    (is (false? (simulation/racers-overlap? 0.20 0.0 1.20 0.0)))
    (is (simulation/set-human-controlled! true))
    (simulation/set-human-input! 1.0 1.0 0.0 false)
    (let [before (:progress (az/value (simulation/racer-view 0)))]
      (simulation/step-many! 120)
      (let [racer (az/value (simulation/racer-view 0))
            input (az/value (simulation/human-control-snapshot))]
        (is (:enabled input))
        (is (= telemetry/source-human (:source racer)))
        (is (close? 0.075 (:lane_target racer) 1.0e-6))
        (is (close? 0.12 (:target_speed racer) 1.0e-6))
        (is (> (:progress racer) before))))
    (finally
      (simulation/set-human-controlled! false)
      (simulation/configure-countdown! 0)
      (simulation/reset!))))

(deftest combat-items-and-persona-scenarios-test
  (worker/stop!)
  (simulation/configure-countdown! 0)
  (simulation/set-items-enabled! false)
  (try
    (testing "every native item can be configured, used, and inspected"
      (doseq [item [simulation/item-bolt
                    simulation/item-trap
                    simulation/item-boost
                    simulation/item-shield
                    simulation/item-pulse
                    simulation/item-surge]]
        (simulation/reset!)
        (is (simulation/configure-racer-state!
             0 0.40 -0.075 0.05 item false))
        (is (simulation/configure-racer-state!
             1 0.44 0.075 0.05 simulation/item-none false))
        (is (simulation/configure-racer-intent!
             0 -0.075 0.08 simulation/action-use 1))
        (let [before (az/value (simulation/racer-view 0))]
          (simulation/step!)
          (let [after (az/value (simulation/racer-view 0))
                target (az/value (simulation/racer-view 1))
                race (az/value (simulation/snapshot))
                active-hazards
                (count (filter :active
                               (map #(az/value (simulation/hazard-view %))
                                    (range simulation/hazard-capacity))))]
            (is (= simulation/item-none (:item after)))
            (is (= 1 (:items_used race)))
            (case item
              1 (is (pos? active-hazards) "bolt creates a live projectile")
              2 (is (pos? active-hazards) "trap creates a live hazard")
              3 (is (> (:speed after) (:speed before)) "boost accelerates")
              4 (is (:shielded after) "shield protects the owner")
              5 (is (and (pos? (:hits race))
                         (< (:speed target) 0.05))
                    "pulse hits the nearby target")
              6 (is (> (- (:progress after) (:progress before)) 0.04)
                    "surge advances track progress"))))))
    (testing "hold leaves an owned item untouched"
      (simulation/reset!)
      (simulation/configure-racer-state!
       0 0.40 -0.075 0.05 simulation/item-bolt false)
      (simulation/configure-racer-intent!
       0 -0.075 0.08 simulation/action-hold 1)
      (simulation/step!)
      (is (= simulation/item-bolt
             (:item (az/value (simulation/racer-view 0)))))
      (is (zero? (:items_used (az/value (simulation/snapshot))))))
    (testing "personas produce distinct bounded intents from identical state"
      (simulation/reset!)
      (dotimes [racer-id 8]
        (simulation/configure-racer-state!
         racer-id 0.40 0.0 0.05 simulation/item-none false)
        (simulation/make-decision!
         racer-id false simulation/deadline-on-time))
      (let [views (mapv #(az/value (simulation/racer-view %)) (range 8))
            personas (mapv #(-> (simulation/current-observation %) az/value
                                :persona)
                           (range 8))]
        (is (= #{0 1 2} (set personas)))
        (is (<= 4 (count (set (map :target_speed views)))))
        (is (every? #(<= 0.0 % 0.16) (map :target_speed views)))))
    (finally
      (simulation/set-items-enabled! true)
      (simulation/reset!))))

(deftest teams-pits-radio-and-accident-damage-test
  (worker/stop!)
  (simulation/configure-countdown! 0)
  (simulation/set-items-enabled! false)
  (try
    (testing "four fixed teams own two drivers and one physical box each"
      (simulation/reset!)
      (let [teams (mapv #(az/value (simulation/team-view %)) (range 4))]
        (is (= [[0 1] [2 3] [4 5] [6 7]]
               (mapv (juxt :driver_a :driver_b) teams)))
        (is (every? #(= simulation/no-pit-occupant (:pit_occupant %)) teams))
        (is (every? true?
                    (map close? [0.845 0.877 0.909 0.941]
                         (map #(simulation/pit-box-progress %) (range 4))
                         (repeat 1.0e-6))))))

    (testing "drivers report wear and the strategist serializes teammate stops"
      (simulation/reset!)
      (simulation/configure-racer-state!
       0 0.81 -0.04 0.08 simulation/item-none false)
      (simulation/configure-racer-state!
       1 0.81 0.04 0.08 simulation/item-none false)
      (simulation/configure-racer-tires! 0 0.10)
      (simulation/configure-racer-tires! 1 0.12)
      (simulation/step!)
      (let [team (az/value (simulation/team-view 0))
            messages (mapv #(az/value (simulation/team-radio-entry 0 %))
                           (range (simulation/team-radio-history-count 0)))]
        (is (contains? #{0 1} (:pit_occupant team)))
        (is (some #(= simulation/radio-source-driver (:source %)) messages))
        (is (some #(= simulation/radio-source-strategist (:source %)) messages))
        (is (some #(contains? #{simulation/radio-pit-confirmed
                                simulation/radio-repair-confirmed}
                              (:code %))
                  messages)))
      (simulation/step-many! 3000)
      (let [team (az/value (simulation/team-view 0))
            racers (mapv #(az/value (simulation/racer-view %)) [0 1])]
        (is (<= 1 (:pit_stops team)))
        (is (<= 1 (reduce + (map :pit_stops racers))))
        (is (pos? (simulation/team-radio-history-count 0)))))

    (testing "overlap creates persistent damage, radio, and physical separation"
      (simulation/reset!)
      (simulation/configure-racer-state!
       0 0.40 0.0 0.12 simulation/item-none false)
      (simulation/configure-racer-state!
       1 0.40 0.0 0.01 simulation/item-none false)
      (simulation/step!)
      (let [a (az/value (simulation/racer-view 0))
            b (az/value (simulation/racer-view 1))
            dx (- (:x a) (:x b))
            dy (- (:y a) (:y b))
            race (az/value (simulation/snapshot))
            messages (mapv #(az/value (simulation/team-radio-entry 0 %))
                           (range (simulation/team-radio-history-count 0)))]
        (is (pos? (:accidents race)))
        (is (pos? (:damage a)))
        (is (pos? (:damage b)))
        (is (>= (+ (* dx dx) (* dy dy)) (* 0.060 0.060)))
        (is (some #(= simulation/radio-car-damaged (:code %)) messages))))
    (finally
      (simulation/set-items-enabled! true)
      (simulation/reset!))))

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
    (model/verify-team-head!)
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [path (.allocateFrom arena (str (model/model-file)))
            action-head-path
            (.allocateFrom arena (str (model/action-head-file)))
            team-head-path
            (.allocateFrom arena (str (model/team-head-file)))
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
        (is (= inference/model-storage-mapped
               (inference/model-storage-kind)))
        (is (= [122 219 61 87 101 173 18 184 59 28 8 169 7 70 216 146
                90 247 24 84 91 74 54 40 126 141 172 143 131 183 42 182]
               (vec (az/value (inference/loaded-model-sha256)))))
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
        (is (= [49 15 735 23 445 15 358 18 350 16 198 32 25]
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
            (is (= [1 1 0 0 0 0 0 0 0 0 0 0] (:positions sequences)))
            (is (= (az/value inference/sequence-total-bytes)
                   (:state_bytes sequences))))
          (finally
            (inference/free-sequences!)))
        (let [head (az/value (inference/load-action-head! action-head-path))
              team-head (az/value (inference/load-team-head! team-head-path))
              action-scenarios (train-action-head/golden-scenarios)
              team-scenarios (take 3 (train-team-head/golden-scenarios))
              features (.allocate arena (* 6144 Float/BYTES) Float/BYTES)]
          (is (:loaded head))
          (is (:valid head))
          (is (zero? (:error_code head)))
          (is (= 6144 (:input_count head)))
          (is (= 8 (:output_count head)))
          (is (= protocol/observation-schema-version
                 (:observation_schema head)))
          (is (= protocol/action-schema-version (:action_schema head)))
          (is (:loaded team-head))
          (is (:valid team-head))
          (is (= 3 (:output_count team-head)))
          (try
            (is (inference/initialize-sequences!))
            (doseq [{:keys [action] :as scenario} action-scenarios]
              (let [prompt (train-action-head/observation-text scenario)
                    tokenized
                    (az/value
                     (inference/tokenize-compact-ascii
                      (.allocateFrom arena prompt) (count prompt)))
                    report
                    (az/value
                     (inference/forward-compact-prompt!
                      0 (.allocateFrom arena prompt) (count prompt) true))]
                (is (:valid report))
                (is (inference/copy-action-features! 0 features))
                (is (= 8 (:candidate_count report)))
                (is (= (dec (:token_count tokenized)) (:position report)))
                (is (every? #(Float/isFinite (float %))
                            (:candidate_logits report)))
                (is (= (+ 32 action) (:best_token report)))))
            (doseq [{:keys [action] :as scenario} team-scenarios]
              (let [prompt (train-team-head/team-text scenario)
                    report
                    (az/value
                     (inference/forward-compact-prompt!
                      8 (.allocateFrom arena prompt) (count prompt) true))]
                (is (:valid report))
                (is (= 3 (:candidate_count report)))
                (is (= (+ 32 action) (:best_token report)))))
            (finally
              (inference/free-sequences!)
              (inference/unload-action-head!)
              (inference/unload-team-head!))))))))

(deftest bounded-worker-mailboxes-and-deadlines-test
  (when (.isFile (model/model-file))
    (core/start-headless!)
    (try
      (with-open [request-zero
                  (worker/InferenceRequest
                   {:valid true :actor_kind worker/actor-kind-driver :team 0
                    :racer 0 :rank 1 :lap 0 :item 0 :target 1
                    :persona 0 :target_distance 2 :target_lane 1
                    :tactical_status 0 :urgent false
                    :driver_a 0 :driver_b 0 :rank_a 0 :rank_b 0
                    :tire_a 0 :tire_b 0 :damage_a 0 :damage_b 0
                    :pit_a 0 :pit_b 0 :box_occupied false
                    :observation_schema protocol/observation-schema-version
                    :action_schema protocol/action-schema-version
                    :revision 1001 :race_epoch 777 :simulation_tick 20
                    :progress 0.2 :speed 0.07 :enqueue_seconds 0.0})
                  duplicate-zero
                  (worker/InferenceRequest
                   {:valid true :actor_kind worker/actor-kind-driver :team 0
                    :racer 0 :rank 1 :lap 0 :item 0 :target 1
                    :persona 0 :target_distance 2 :target_lane 1
                    :tactical_status 0 :urgent true
                    :driver_a 0 :driver_b 0 :rank_a 0 :rank_b 0
                    :tire_a 0 :tire_b 0 :damage_a 0 :damage_b 0
                    :pit_a 0 :pit_b 0 :box_occupied false
                    :observation_schema protocol/observation-schema-version
                    :action_schema protocol/action-schema-version
                    :revision 1002 :race_epoch 777 :simulation_tick 21
                    :progress 0.2 :speed 0.07 :enqueue_seconds 0.0})
                  request-one
                  (worker/InferenceRequest
                   {:valid true :actor_kind worker/actor-kind-driver :team 1
                    :racer 1 :rank 2 :lap 0 :item 3 :target 0
                    :persona 1 :target_distance 3 :target_lane 0
                    :tactical_status 1 :urgent true
                    :driver_a 0 :driver_b 0 :rank_a 0 :rank_b 0
                    :tire_a 0 :tire_b 0 :damage_a 0 :damage_b 0
                    :pit_a 0 :pit_b 0 :box_occupied false
                    :observation_schema protocol/observation-schema-version
                    :action_schema protocol/action-schema-version
                    :revision 1003 :race_epoch 777 :simulation_tick 22
                    :progress 0.18 :speed 0.08 :enqueue_seconds 0.0})]
        (is (worker/submit! request-zero))
        (is (false? (worker/submit! duplicate-zero)))
        (is (worker/submit! request-one))
        (is (= 1001 (:revision (await-worker-result 0 15000))))
        (is (= 1003 (:revision (await-worker-result 1 15000))))
        (let [summary (core/worker-status)]
          (is (= 12 (:threads summary)))
          (is (= [1 1 0 0 0 0 0 0 0 0 0 0] (:requests_by_actor summary)))
          (is (= [1 1 0 0 0 0 0 0 0 0 0 0] (:results_by_actor summary)))))
      (finally
        (core/stop-headless!)))

    ;; Restarting owns a fresh bounded mailbox set and fresh accounting.
    (core/start-headless!)
    (try
      (is (= [0 0 0 0 0 0 0 0 0 0 0 0]
             (:requests_by_actor (core/worker-status))))
      (simulation/reset!)
      ;; Advancing simulation time without waiting for inference deliberately
      ;; exercises all eight hard deadlines. Native simulation ticks are
      ;; intentionally advanced faster than the real model can answer.
      (simulation/step-many! 721)
      (let [expired (az/value (simulation/snapshot))
            cognition (core/cognition-status)]
        (is (= 8 (:deadline_misses expired)))
        (is (= 8 (:deadline_misses cognition)))
        (is (every? #(= 1 (:deadline_misses
                            (az/value (simulation/racer-view %))))
                    (range 8))))
      (let [workers (await-workers-idle 10000)]
        (is (= [1 1 1 1 1 1 1 1 0 0 0 0] (:requests_by_actor workers)))
        (is (= [1 1 1 1 1 1 1 1 0 0 0 0] (:results_by_actor workers))))
      (simulation/step!)
      (let [cognition (core/cognition-status)]
        (is (zero? (:llm_entries cognition)))
        (is (= 8 (:deadline_misses cognition)))
        (is (every? #(= telemetry/source-fallback
                        (:source (az/value (simulation/racer-view %))))
                    (range 8))))
      (finally
        (core/stop-headless!)))))

(deftest scheduler-boundaries-and-no-item-completion-test
  (is (= [40 48 60 60 80]
         (mapv simulation/cadence-ticks-for-pressure
               [0 5 7 0 0]
               [0 0 0 250000 333000])))
  (testing "only the exact constrained action for the observed target installs"
    (is (simulation/valid-worker-action? true 7 1 3 3 0.075 0.12 1 39))
    (is (false? (simulation/valid-worker-action? false 7 1 3 3 0.075 0.12 1 39)))
    (is (false? (simulation/valid-worker-action? true 8 1 3 3 0.075 0.12 1 40)))
    (is (false? (simulation/valid-worker-action? true 7 2 3 3 0.075 0.12 1 39)))
    (is (false? (simulation/valid-worker-action? true 7 1 8 8 0.075 0.12 1 39)))
    (is (false? (simulation/valid-worker-action? true 7 1 2 3 0.075 0.12 1 39)))
    (is (false? (simulation/valid-worker-action? true 7 1 3 3 0.076 0.12 1 39)))
    (is (false? (simulation/valid-worker-action? true 7 1 3 3 0.075 0.121 1 39)))
    (is (false? (simulation/valid-worker-action? true 7 1 3 3 0.075 0.12 2 39)))
    (is (false? (simulation/valid-worker-action? true 7 1 3 3 0.075 0.12 1 38))))
  (is (= 720 (simulation/decision-deadline-ticks false)))
  (is (= 600 (simulation/decision-deadline-ticks true)))
  (is (false? (simulation/decision-expired? 10 609 true)))
  (is (simulation/decision-expired? 10 610 true))
  (is (false? (simulation/decision-expired? 10 729 false)))
  (is (simulation/decision-expired? 10 730 false))
  (try
    (is (false? (simulation/set-items-enabled! false)))
    (simulation/reset!)
    (simulation/step-many! 8000)
    (let [race (az/value (simulation/snapshot))]
      (is (= simulation/race-state-finished (:state race)))
      (is (= 8 (:finished race)))
      (is (zero? (:items_used race)))
      (is (zero? (:hits race)))
      (is (zero? (:hazards_spawned race))))
    (finally
      (simulation/set-items-enabled! true)
      (simulation/reset!))))

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
        semantic-log (core/decision-log 0)
        raw-log (core/decision-log 0 {:include-raw? true})
        semantic-trace (core/decision-trace 0)
        raw-trace (core/decision-trace 0 0 {:include-raw? true})
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
    (is (pos? (:contacts snapshot)))
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
    (is (every? #(= protocol/tokenizer-version (:tokenizer_version %))
                latest))
    (is (every? #(= protocol/quantization-version
                    (:quantization_version %))
                latest))
    (is (every? #(= protocol/quantization-format
                    (:quantization_format %))
                latest))
    (is (every? #(= protocol/training-data-fingerprint
                    (:training_data_fingerprint %))
                latest))
    (is (every? #(= 32 (count (:training_data_sha256 %))) latest))
    (is (every? #(= [0x4a 0x3c 0x38 0xc8]
                    (subvec (:training_data_sha256 %) 0 4))
                latest))
    (is (nil? (:prompt semantic-log)))
    (is (nil? (:model_fingerprint semantic-log)))
    (is (nil? (:training_data_sha256 semantic-log)))
    (is (= protocol/model-fingerprint (:model_fingerprint raw-log)))
    (is (= 32 (count (:training_data_sha256 raw-log))))
    (is (nil? (:schemas semantic-trace)))
    (is (nil? (:provenance semantic-trace)))
    (is (= "4a3c38c8723716e10acdc990d240170adf246922ccacf905cbae56a465e37b4f"
           (get-in raw-trace [:provenance :training-data-sha256])))
    (is (every? #(<= (:enqueue_tick %) (:install_tick %)) latest))
    (is (every? pos? (map :race_epoch latest)))
    (is (every? :valid outcomes))
    (is (pos? (:resolved_outcomes cognition)))
    (is (pos? (:attributed_item_uses cognition)))
    (is (<= (:attributed_hits cognition) (:hits snapshot)))
    (is (pos? (:average_progress_gain cognition)))
    (is (every? #(= 64 (count (:input_tokens %))) latest))
    (is (every? #(= 16 (count (:output_tokens %))) latest))
    (simulation/step-many! 6800)
    (let [finish (az/value (simulation/snapshot))
          finished-racers
          (mapv #(az/value (simulation/racer-view %)) (range 8))]
      (is (= 8 (:finished finish)))
      (is (= simulation/race-state-finished (:state finish)))
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
         :urgent_decisions :invalid_decisions :items_used :hits :contacts
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
    (simulation/clear-replay!))
  (simulation/set-race-seed! 7)
  (let [native-parity
        (az/value
         (simulation/run-replay-parity! protocol/replay-golden-ticks))]
    (is (:valid native-parity))
    (is (= protocol/replay-golden-intent-count
           (:intent_count native-parity)))
    (is (= (:original_fingerprint native-parity)
           (:replay_fingerprint native-parity)))
    (is (= protocol/replay-golden-fingerprint
           (:original_fingerprint native-parity)))))

(deftest portable-replay-artifact-test
  (az/await!)
  (let [fixture (io/file (io/resource "replay/golden-r4.bin"))
        bytes (Files/readAllBytes (.toPath fixture))
        file-attributes (make-array FileAttribute 0)
        open-options (make-array OpenOption 0)
        truncated (Files/createTempFile "aguafria-replay-truncated-" ".bin"
                                        file-attributes)
        incompatible (Files/createTempFile "aguafria-replay-incompatible-" ".bin"
                                           file-attributes)]
    (try
      (let [loaded (load-native-replay-file fixture)]
        (is (:valid loaded))
        (is (= simulation/replay-file-ok (:error_code loaded)))
        (is (= protocol/replay-golden-intent-count (:intent_count loaded)))
        (is (= protocol/observation-schema-version
               (:observation_schema loaded)))
        (is (= protocol/action-schema-version (:action_schema loaded)))
        (is (= protocol/model-fingerprint (:model_fingerprint loaded)))
        (is (= protocol/action-head-fingerprint
               (:action_head_fingerprint loaded))))
      (Files/write truncated (java.util.Arrays/copyOf bytes 31) open-options)
      (let [loaded (load-native-replay-file (.toFile truncated))]
        (is (false? (:valid loaded)))
        (is (= simulation/replay-file-invalid-size (:error_code loaded))))
      (let [changed (aclone bytes)]
        (aset-byte changed 12 (byte (inc protocol/observation-schema-version)))
        (Files/write incompatible changed open-options))
      (let [loaded (load-native-replay-file (.toFile incompatible))]
        (is (false? (:valid loaded)))
        (is (= simulation/replay-file-incompatible (:error_code loaded))))
      (finally
        (Files/deleteIfExists truncated)
        (Files/deleteIfExists incompatible)))))

(deftest deterministic-native-tournament-test
  (is (= 1.0 (double (telemetry/outcome-window-seconds))))
  (let [report (tournament/run! {:seeds [0 1 2]
                                 :max-ticks 8000
                                 :chunk-ticks 300
                                 :mode :fallback})
        scoreboard (:scoreboard report)
        persona-evaluation (:persona-evaluation report)
        summary (tournament/report-summary report)
        comparison (tournament/compare-reports report report)]
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
    (is (= #{:cautious :balanced :bold}
           (set (keys (:personas persona-evaluation)))))
    (is (false? (:model-evidence? persona-evaluation)))
    (is (false? (:all-competent? persona-evaluation)))
    (is (false? (:differentiated? persona-evaluation)))
    (is (= protocol/model-fingerprint
           (get-in report [:provenance :model-fingerprint])))
    (is (= protocol/action-head-fingerprint
           (get-in report [:provenance :action-head-fingerprint])))
    (is (= protocol/tokenizer-version
           (get-in report [:provenance :tokenizer-version])))
    (is (= protocol/quantization-version
           (get-in report [:provenance :quantization-version])))
    (is (= protocol/training-data-fingerprint
           (get-in report [:provenance :training-data-fingerprint])))
    (is (= 3 (:complete-races summary)))
    (is (pos? (:average-finish-tick summary)))
    (is (pos? (:item-uses summary)))
    (is (pos? (:hits summary)))
    (is (zero? (:llm-decisions summary)))
    (is (pos? (:fallback-decisions summary)))
    (is (= 3 (:paired-races comparison)))
    (is (= 24 (:equal-finishes comparison)))
    (is (zero? (:faster-finishes comparison)))
    (is (zero? (:slower-finishes comparison)))
    (is (every? zero? (vals (:deltas comparison))))
    (is (every? #(and (zero? (:average-rank-improvement %))
                      (zero? (:average-finish-tick-delta %)))
                (:per-racer comparison)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tournament/compare-reports
                  report (assoc report :races (subvec (:races report) 1)))))))
