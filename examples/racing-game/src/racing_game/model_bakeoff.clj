(ns racing-game.model-bakeoff
  "Reproducible model comparison through the game's Aguafria/Zig engine only.

  Candidate files are weights. Every tokenizer call, recurrent/attention
  kernel, state transition, prompt feature, and action score measured here is
  executed by racing-game.inference—the same native code used by the game."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.train-action-head :as driver]
            [racing-game.train-team-head :as team]
            [racing-game.worker :as worker])
  (:import [java.lang.foreign Arena ValueLayout]))

(def representative-prompt-gate-ms 15000.0)

(defn percentile
  [values probability]
  (when (seq values)
    (let [ordered (vec (sort values))
          index (-> (* probability (count ordered))
                    Math/ceil long dec (max 0) (min (dec (count ordered))))]
      (nth ordered index))))

(defn distribution
  [values]
  {:minimum (when (seq values) (apply min values))
   :p50 (percentile values 0.50)
   :p95 (percentile values 0.95)
   :maximum (when (seq values) (apply max values))})

(defn- native-prompt-result
  [slot {:keys [prompt action]}]
  (with-open [arena (Arena/ofConfined)]
    (let [bytes (byte-array (map unchecked-byte prompt))
          memory (.allocate arena (count bytes) 1)
          _ (.copyFrom memory
                       (java.lang.foreign.MemorySegment/ofArray bytes))
          tokenized (az/value
                     (inference/tokenize-compact-ascii memory (count bytes)))
          started (System/nanoTime)
          report (az/value
                  (inference/forward-compact-prompt!
                   slot memory (count bytes) true))
          elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
          predicted (- (:best_token report) 32)]
      {:valid (:valid report)
       :expected action
       :predicted predicted
       :correct (= action predicted)
       :prompt-bytes (count bytes)
       :input-tokens (:token_count tokenized)
       :latency-ms elapsed-ms
       :input-tokens-per-second
       (if (pos? elapsed-ms)
         (/ (* 1000.0 (:token_count tokenized)) elapsed-ms)
         0.0)})))

(defn- run-parallel-suite!
  [scenarios slot-offset slots]
  (->> scenarios
       (partition-all slots)
       (mapcat
        (fn [batch]
          (let [jobs
                (mapv (fn [slot scenario]
                        (future (native-prompt-result (+ slot-offset slot)
                                                      scenario)))
                      (range)
                      batch)]
            (mapv deref jobs))))
       vec))

(defn- read-native-features
  [segment input-count]
  (let [values (float-array input-count)]
    (dotimes [index input-count]
      (aset-float values index
                  (.getAtIndex segment ValueLayout/JAVA_FLOAT index)))
    values))

(defn- native-feature-result
  [slot input-count scenario]
  (with-open [arena (Arena/ofConfined)]
    (let [bytes (byte-array (map unchecked-byte (:prompt scenario)))
          memory (.allocate arena (count bytes) 1)
          features (.allocate arena (* input-count Float/BYTES) Float/BYTES)
          _ (.copyFrom memory
                       (java.lang.foreign.MemorySegment/ofArray bytes))
          started (System/nanoTime)
          report (az/value
                  (inference/forward-compact-prompt!
                   slot memory (count bytes) true))
          latency-ms (/ (- (System/nanoTime) started) 1000000.0)]
      (when-not (:valid report)
        (throw (ex-info "Native candidate feature extraction failed"
                        {:slot slot :scenario (dissoc scenario :prompt)
                         :report report})))
      (when-not (inference/copy-action-features! slot features)
        (throw (ex-info "Native candidate features were not published"
                        {:slot slot :input-count input-count})))
      (assoc scenario
             :features (read-native-features features input-count)
             :native-latency-ms latency-ms))))

(defn- extract-native-features!
  [label scenarios input-count]
  (->> (map-indexed vector scenarios)
       (partition-all 12)
       (mapcat
        (fn [batch]
          (println "Native candidate features" label (ffirst batch) "/"
                   (count scenarios))
          (let [jobs
                (mapv (fn [slot [_ scenario]]
                        (future
                          (native-feature-result slot input-count scenario)))
                      (range)
                      batch)]
            (mapv deref jobs))))
       vec))

(defn- summarize-suite
  [results]
  (let [valid (filter :valid results)
        correct (filter :correct valid)]
    {:examples (count results)
     :valid (count valid)
     :invalid (- (count results) (count valid))
     :correct (count correct)
     :accuracy (if (seq results)
                 (/ (count correct) (double (count results)))
                 0.0)
     :latency-ms (distribution (map :latency-ms valid))
     :input-tokens-per-second
     (distribution (map :input-tokens-per-second valid))
     :failures (mapv #(select-keys % [:expected :predicted :input-tokens])
                     (remove :correct valid))}))

(defn evaluate-shipping-model!
  "Run driver and team human golden cases with the shipped native heads."
  []
  (model/verify! :granite-350m-q4-0)
  (model/verify-action-head!)
  (model/verify-team-head!)
  (with-open [arena (Arena/ofConfined)]
    (let [loaded
          (az/value
           (inference/load-model!
            (.allocateFrom arena
                           (str (model/model-file :granite-350m-q4-0)))))]
      (when-not (:valid loaded)
        (throw (ex-info "Native engine rejected Granite H-350M"
                        {:summary loaded})))
      (try
        (let [driver-head
              (az/value
               (inference/load-action-head!
                (.allocateFrom arena (str (model/action-head-file)))))
              team-head
              (az/value
               (inference/load-team-head!
                (.allocateFrom arena (str (model/team-head-file)))))]
          (when-not (and (:valid driver-head) (:valid team-head))
            (throw (ex-info "Native model/head contract is invalid"
                            {:driver-head driver-head :team-head team-head})))
          (when-not (inference/initialize-sequences!)
            (throw (ex-info "Could not allocate native bake-off states" {})))
          (let [driver-scenarios
                (vec (concat (driver/golden-scenarios)
                             (driver/tactical-anchor-scenarios)))
                team-scenarios (team/golden-scenarios)
                driver-results (run-parallel-suite! driver-scenarios 0 8)
                team-results (run-parallel-suite! team-scenarios 8 4)
                profile (az/value (inference/model-profile-summary))]
            {:model :granite-350m-q4-0
             :engine :aguafria-zig-native
             :external-inference false
             :model-bytes (:file_size loaded)
             :sequence-state-bytes (:sequence_state_bytes profile)
             :held-out-driver-accuracy
             (:held-out-accuracy (model/action-head-entry))
             :held-out-team-accuracy
             (:held-out-accuracy (model/team-head-entry))
             :driver (summarize-suite driver-results)
             :team (summarize-suite team-results)}))
        (finally
          (inference/unload-model!))))))

(defn evaluate-candidate-latency!
  "Run real native candidate tokens before spending hours fitting a head.
  Candidates slower than the per-token gate are rejected transparently; the
  report never invents proxy quality from another runtime."
  [model-key]
  (model/verify! model-key)
  (with-open [arena (Arena/ofConfined)]
    (let [path (.allocateFrom arena (str (model/model-file model-key)))
          load-started (System/nanoTime)
          loaded (az/value (inference/load-model! path))
          load-ms (/ (- (System/nanoTime) load-started) 1000000.0)]
      (when-not (:valid loaded)
        (throw (ex-info "Native engine rejected candidate"
                        {:model model-key :summary loaded})))
      (try
        (when-not (inference/initialize-sequences!)
          (throw (ex-info "Could not allocate candidate state"
                          {:model model-key})))
        (let [scenario (first (driver/golden-scenarios))
              bytes (byte-array (map unchecked-byte (:prompt scenario)))
              memory (.allocate arena (count bytes) 1)
              _ (.copyFrom memory
                           (java.lang.foreign.MemorySegment/ofArray bytes))
              tokenized (az/value
                         (inference/tokenize-compact-ascii memory (count bytes)))
              tokens (take 8 (:tokens tokenized))
              _ (inference/reset-sequence! 0)
              token-results
              (mapv
               (fn [token]
                 (let [started (System/nanoTime)
                       report (az/value (inference/forward-token! 0 token))]
                   {:valid (:valid report)
                    :latency-ms (/ (- (System/nanoTime) started) 1000000.0)}))
               tokens)
              per-token (map :latency-ms token-results)
              median-token-ms (percentile per-token 0.50)
              estimated-prompt-ms (* median-token-ms (:token_count tokenized))
              rejected? (> estimated-prompt-ms representative-prompt-gate-ms)
              profile (az/value (inference/model-profile-summary))]
          {:model model-key
           :engine :aguafria-zig-native
           :external-inference false
           :model-bytes (:file_size loaded)
           :sequence-state-bytes (:sequence_state_bytes profile)
           :load-ms load-ms
           :measured-native-tokens (count token-results)
           :native-token-latency-ms (distribution per-token)
           :representative-prompt-tokens (:token_count tokenized)
           :estimated-representative-prompt-ms estimated-prompt-ms
           :representative-prompt-gate-ms representative-prompt-gate-ms
           :latency-gate (if rejected? :rejected :passed)
           :quality-status
           (if rejected?
             :not-run-after-native-latency-rejection
             :requires-native-head-training)})
        (finally
          (inference/unload-model!))))))

(defn evaluate-candidate-quality!
  "Fit candidate-specific heads over the exact native hidden width and score
  the same untouched balanced splits as the shipping model. Candidate heads
  remain bake-off artifacts unless a later explicit promotion selects them."
  [model-key]
  (model/verify! model-key)
  (with-open [arena (Arena/ofConfined)]
    (let [loaded
          (az/value
           (inference/load-model!
            (.allocateFrom arena (str (model/model-file model-key)))))]
      (when-not (:valid loaded)
        (throw (ex-info "Native engine rejected quality candidate"
                        {:model model-key :summary loaded})))
      (try
        (when-not (inference/initialize-sequences!)
          (throw (ex-info "Could not allocate candidate quality states"
                          {:model model-key})))
        (let [profile (az/value (inference/model-profile-summary))
              input-count (* 8 (:hidden_size profile))
              driver-scenarios (driver/training-corpus)
              driver-balanced-count
              (count (driver/balanced-scenarios 48))
              team-scenarios (team/balanced-scenarios 36)
              driver-examples
              (extract-native-features! :driver driver-scenarios input-count)
              team-examples
              (extract-native-features! :team team-scenarios input-count)
              driver-split
              (driver/split-balanced
               (subvec driver-examples 0 driver-balanced-count) 6)
              driver-train
              (into (:train driver-split)
                    (subvec driver-examples driver-balanced-count))
              driver-test (:test driver-split)
              team-split (team/split-balanced team-examples 6)
              driver-head
              (driver/train-ridge-generic driver-train 8 input-count 0.15)
              team-head
              (driver/train-ridge-generic (:train team-split)
                                          3 input-count 3.0)
              driver-train-evaluation
              (driver/evaluation-generic
               (:weights driver-head) (:biases driver-head)
               driver-train 8 input-count)
              driver-test-evaluation
              (driver/evaluation-generic
               (:weights driver-head) (:biases driver-head)
               driver-test 8 input-count)
              team-train-evaluation
              (driver/evaluation-generic
               (:weights team-head) (:biases team-head)
               (:train team-split) 3 input-count)
              team-test-evaluation
              (driver/evaluation-generic
               (:weights team-head) (:biases team-head)
               (:test team-split) 3 input-count)]
          {:model model-key
           :engine :aguafria-zig-native
           :external-inference false
           :quality-status :measured
           :input-count input-count
           :driver
           {:training-examples (count driver-train)
            :test-examples (count driver-test)
            :training driver-train-evaluation
            :held-out driver-test-evaluation
            :feature-latency-ms
            (distribution (map :native-latency-ms driver-examples))}
           :team
           {:training-examples (count (:train team-split))
            :test-examples (count (:test team-split))
            :training team-train-evaluation
            :held-out team-test-evaluation
            :feature-latency-ms
            (distribution (map :native-latency-ms team-examples))}})
        (finally
          (inference/unload-model!))))))

(defn compact-text
  [report]
  (with-out-str
    (println "Aguafria native racing-model bake-off")
    (println "External inference: disabled")
    (doseq [entry (:models report)]
      (println)
      (println (name (:model entry)))
      (if-let [golden-accuracy (get-in entry [:driver :accuracy])]
        (do
          (println "  driver golden accuracy:" golden-accuracy)
          (println "  driver held-out accuracy:"
                   (:held-out-driver-accuracy entry))
          (println "  team golden accuracy:" (get-in entry [:team :accuracy]))
          (println "  team held-out accuracy:"
                   (:held-out-team-accuracy entry))
          (println "  driver input tok/s p50:"
                   (get-in entry [:driver :input-tokens-per-second :p50]))
          (println "  team input tok/s p50:"
                   (get-in entry [:team :input-tokens-per-second :p50])))
        (if-let [held-out (get-in entry [:driver :held-out :accuracy])]
          (do
            (println "  native latency gate:" (name (:latency-gate entry)))
            (println "  driver held-out accuracy:" held-out)
            (println "  team held-out accuracy:"
                     (get-in entry [:team :held-out :accuracy]))
            (println "  full driver prompt p50 ms:"
                     (get-in entry [:driver :feature-latency-ms :p50]))
            (println "  full team prompt p50 ms:"
                     (get-in entry [:team :feature-latency-ms :p50])))
          (do
            (println "  native latency gate:" (name (:latency-gate entry)))
            (println "  token latency p50 ms:"
                     (get-in entry [:native-token-latency-ms :p50]))
            (println "  estimated prompt ms:"
                     (:estimated-representative-prompt-ms entry))))))))

(defn run!
  "Evaluate the shipping model and first architecture-compatible challenger."
  []
  (worker/stop!)
  (let [shipping (evaluate-shipping-model!)
        candidate-latency
        (evaluate-candidate-latency! :granite-1b-q4-0)
        candidate-quality
        (when (= :passed (:latency-gate candidate-latency))
          (evaluate-candidate-quality! :granite-1b-q4-0))
        candidate
        (if candidate-quality
          (let [actual-prompt-p50
                (max (get-in candidate-quality
                             [:driver :feature-latency-ms :p50])
                     (get-in candidate-quality
                             [:team :feature-latency-ms :p50]))]
            (merge candidate-latency
                   candidate-quality
                   {:actual-prompt-p50-ms actual-prompt-p50
                    :latency-gate
                    (if (> actual-prompt-p50 representative-prompt-gate-ms)
                      :rejected
                      :passed)}))
          candidate-latency)
        report
        {:format :aguafria-racing-model-bakeoff-v1
         :engine :aguafria-zig-native
         :external-inference false
         :selected :granite-350m-q4-0
         :selection-reason
         :better-held-out-racing-quality-lower-latency-memory-and-bytes
         :models [shipping candidate]}
        output (io/file (model/project-root) "build/model-bakeoff/latest.edn")]
    (.mkdirs (.getParentFile output))
    (spit output (with-out-str (pprint/pprint report)))
    {:report report
     :text (compact-text report)
     :file output}))

(defn -main
  [& _]
  (let [{:keys [report text file]} (run!)]
    (print text)
    (println)
    (println "EDN:" (.getCanonicalPath file))
    (pprint/pprint report)
    (shutdown-agents)))
