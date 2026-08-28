(ns racing-game.train-action-head
  "Offline supervised training for the tiny native A-H racing action head.

  Granite remains the feature extractor. This namespace generates deterministic
  expert-labelled R4 semantic observations, runs the real native model, learns a linear
  softmax head, evaluates a held-out split, and writes the fixed binary artifact
  consumed by racing-game.inference. It is never part of the release runtime."
  (:require [aguafria.zig :as az]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol])
  (:import [java.io FileInputStream FileOutputStream
            ObjectInputStream ObjectOutputStream]
           [java.math BigInteger]
           [java.lang.foreign Arena ValueLayout]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]
           [java.util Random]))

(def feature-size 6144)

(def action-count 8)

(def action-head-magic 0x48415241)

(defn feature-cache-file
  []
  (java.io.File. (model/project-root)
                 "build/training/r3-semantic-features.ser"))

(defn feature-cache-key
  []
  {:extractor-version 4
   :model-sha256 (:sha256 (model/model-entry))
   :observation-schema protocol/observation-schema-version
   :feature-size feature-size})

(defn persona-text
  [value]
  (case value 0 "cautious", 1 "balanced", "bold"))

(defn item-text
  [value]
  (case value
    1 "bolt" 2 "trap" 3 "boost" 4 "shield" 5 "pulse" 6 "surge"
    "none"))

(defn lane-text
  [value]
  (case value 0 "left", 2 "right", "same lane"))

(defn status-text
  [value]
  (case value
    1 "hazard nearby" 2 "recovering" 3 "shield active"
    "clear"))

(defn observation-text
  "Render the exact compact English observation used by the native worker."
  [{:keys [racer target persona rank lap item progress speed urgent
           target-distance target-lane tactical-status]
    :or {racer 0}}]
  (format (str "Driver %d, %s. Rank %d/8; lap %d; progress %d%%; speed %d. "
               "Item %s. Rival %d: gap %d, %s. Track %s. %s.")
          racer (persona-text persona) rank lap
          (long (min 99.0 (* (max 0.0 progress) 100.0)))
          (long (min 99.0 (* (max 0.0 speed) 100.0)))
          (item-text item) target target-distance (lane-text target-lane)
          (status-text tactical-status) (if urgent "Urgent" "Routine")))

(defn observation-bytes
  "Encode the exact native semantic prompt as bounded ASCII bytes."
  [scenario]
  (.getBytes ^String (observation-text scenario) "US-ASCII"))

(defn expert-action
  "Deterministic tactical teacher over only fields available to the racer.
  It chooses among the same eight legal actions as the native validator."
  [{:keys [persona rank item urgent tactical-status target-distance
           target-lane]}]
  (let [routine-action (case persona 0 1, 1 3, 7)
        inventory-hold-action (case persona 0 1, 3)]
    (cond
      ;; Stun recovery should never burn inventory or request maximum pace.
      (= tactical-status 2) 1

      ;; Boost and surge receive distinct maximum-pace macros.
      (= item 3) 6
      (= item 6) 7

      ;; Defensive inventory is valuable only when local perception reports a
      ;; threat. Clear-track shield hoarding is an intentional learned choice.
      (= item 4)
      (if (or urgent (= tactical-status 1)) 4 inventory-hold-action)

      ;; Distance bin 9 is the explicit no-actionable-target/far-target case.
      ;; Do not train the model to throw ranged inventory into empty space.
      (or (= item 1) (= item 5))
      (if (< target-distance 9) 4 inventory-hold-action)

      ;; A leader or dense nearby traffic can profitably leave a rear trap.
      (= item 2)
      (if (or (<= rank 3) (<= target-distance 3)) 5 inventory-hold-action)

      ;; A nearby native-validated hazard asks the model for a lateral evasive
      ;; intent. The direction remains local perception, never omniscient state.
      (= tactical-status 1) (if (= target-lane 2) 0 2)

      ;; An empty-handed surprise is the explicit attack macro.
      urgent 3

      ;; Stable intent maps personas to cautious center, balanced attack, or
      ;; bold maximum pace. Inventory validation turns the latter into hold
      ;; when no item exists, without losing its speed intent.
      :else routine-action)))

(defn- random-scenario
  [^Random random]
  {:target (.nextInt random 8)
   :persona (.nextInt random 3)
   :rank (inc (.nextInt random 8))
   :lap (.nextInt random 3)
   :item (.nextInt random 7)
   :progress (/ (inc (.nextInt random 19)) 20.0)
   :speed (+ 0.04 (* 0.005 (.nextInt random 12)))
   :target-distance (.nextInt random 10)
   :target-lane (.nextInt random 3)
   :tactical-status (.nextInt random 4)
   :urgent (.nextBoolean random)})

(defn golden-scenarios
  "Minimal deterministic seeds used before filling every balanced class."
  []
  (mapv
   (fn [scenario]
     (let [complete
           (merge {:target-distance 4 :target-lane 1 :tactical-status 0}
                  scenario)]
       (assoc complete
              :action (expert-action complete)
              :prompt (vec (observation-bytes complete)))))
   [{:target 0 :persona 0 :rank 1 :lap 0 :item 0
     :progress 0.5 :speed 0.07 :urgent false}
    {:target 0 :persona 1 :rank 1 :lap 0 :item 0
     :progress 0.5 :speed 0.07 :urgent false}
    {:target 0 :persona 2 :rank 1 :lap 0 :item 0
     :progress 0.5 :speed 0.07 :urgent false}
    {:target 0 :persona 0 :rank 1 :lap 0 :item 0
     :progress 0.5 :speed 0.07 :urgent true}
    {:target 0 :persona 0 :rank 1 :lap 0 :item 1
     :progress 0.5 :speed 0.07 :urgent false}
    {:target 0 :persona 0 :rank 1 :lap 0 :item 2
     :progress 0.5 :speed 0.07 :urgent false}
    {:target 0 :persona 0 :rank 1 :lap 0 :item 3
     :progress 0.5 :speed 0.07 :urgent false}
    {:target 0 :persona 0 :rank 1 :lap 0 :item 4
     :progress 0.5 :speed 0.07 :urgent false}]))

(defn tactical-anchor-scenarios
  "Rare, human-authored training anchors kept out of the held-out split."
  []
  (let [defaults {:target 1 :persona 1 :rank 4 :lap 1 :item 0
                  :progress 0.5 :speed 0.07 :urgent false
                  :target-distance 4 :target-lane 1 :tactical-status 0}]
    (mapv
     (fn [scenario]
       (let [complete (merge defaults scenario)]
         (assoc complete
                :action (expert-action complete)
                :prompt (vec (observation-bytes complete)))))
     [;; First renderer-free rollout failure: cautious, empty-handed, clear,
      ;; trailing, and non-urgent must remain a steady action.
      {:persona 0 :rank 8 :target 1 :lap 0 :item 0
       :progress 0.0025876672 :speed 0.041600544 :urgent false
       :target-distance 1 :target-lane 2 :tactical-status 0}
      {:persona 0 :rank 5 :target 2 :progress 0.35}
      {:persona 0 :rank 5 :target 1 :lap 0 :progress 0.35 :speed 0.07
       :target-distance 4 :target-lane 1}
      {:persona 0 :rank 2 :target 6 :progress 0.72 :speed 0.08}
      {:persona 1 :rank 4 :target 2 :target-lane 2}
      {:persona 2 :rank 7 :target 3 :target-lane 0 :lap 2}
      {:persona 0 :rank 8 :target 1 :urgent true :target-distance 1}
      {:persona 2 :rank 6 :target 4 :urgent true :target-distance 2}
      {:persona 1 :tactical-status 1 :target-lane 2 :urgent true}
      {:persona 2 :tactical-status 1 :target-lane 0 :urgent true}
      {:persona 0 :tactical-status 2 :urgent true :speed 0.02}
      {:persona 2 :tactical-status 2 :item 5 :urgent true :speed 0.01}
      ;; Second renderer-free rollout failure: recovery takes precedence over
      ;; spending a shield, even when the observation remains urgent.
      {:persona 1 :rank 5 :target 4 :lap 0 :item 4
       :progress 0.24234015 :speed 0.048488103 :urgent true
       :target-distance 1 :target-lane 1 :tactical-status 2}
      {:persona 0 :item 1 :target-distance 2 :target-lane 2}
      {:persona 2 :item 5 :target-distance 1 :target-lane 0 :urgent true}
      {:persona 0 :item 1 :target-distance 9 :target 7 :rank 1}
      {:persona 1 :item 1 :target-distance 9 :target 7 :rank 1 :lap 2}
      {:persona 0 :item 5 :target-distance 9 :target 7 :rank 1}
      {:persona 0 :item 5 :target-distance 9 :target 7 :rank 1 :lap 2
       :progress 0.75 :speed 0.08 :target-lane 1}
      {:persona 2 :item 5 :target-distance 9 :target 7 :rank 1 :lap 2}
      {:persona 1 :item 2 :rank 1 :target-distance 9}
      {:persona 0 :item 2 :rank 8 :target-distance 8}
      {:persona 0 :item 4 :tactical-status 1 :urgent true}
      {:persona 0 :item 4 :tactical-status 0 :urgent false}
      {:persona 2 :item 4 :tactical-status 0 :urgent false}
      {:persona 1 :item 3 :rank 5 :target-distance 4}
      {:persona 2 :item 3 :lap 2 :progress 0.93 :urgent true}
      {:persona 1 :item 6 :rank 5 :target-distance 2}
      {:persona 2 :item 6 :lap 2 :progress 0.97 :urgent true}])))

(defn balanced-scenarios
  "Return a reproducible, prompt-unique, class-balanced corpus."
  [per-action]
  (let [random (Random. 0xA6A4F21)
        buckets (object-array action-count)
        golden (golden-scenarios)]
    (dotimes [action action-count]
      (aset buckets action (transient [])))
    (doseq [scenario golden]
      (let [action (:action scenario)]
        (aset buckets action (conj! (aget buckets action) scenario))))
    (loop [seen (set (map :prompt golden))]
      (if (every? #(>= (count %) per-action) buckets)
        (vec (mapcat persistent! buckets))
        (let [scenario (random-scenario random)
              prompt (vec (observation-bytes scenario))
              action (expert-action scenario)]
          (if (or (contains? seen prompt)
                  (>= (count (aget buckets action)) per-action))
            (recur seen)
            (do
              (aset buckets action
                    (conj! (aget buckets action)
                           (assoc scenario :action action :prompt prompt)))
              (recur (conj seen prompt)))))))))

(defn training-corpus
  "Balanced corpus followed by unique tactical anchors used only for fitting."
  []
  (let [balanced (balanced-scenarios 48)
        seen (set (map :prompt balanced))]
    (into balanced (remove #(contains? seen (:prompt %))
                           (tactical-anchor-scenarios)))))

(defn corpus-sha256
  "Hash the exact ordered prompt/action bytes used to train the action head."
  [scenarios]
  (let [payload
        (byte-array
         (map unchecked-byte
              (mapcat #(conj (:prompt %) (:action %)) scenarios)))
        digest (.digest (MessageDigest/getInstance "SHA-256") payload)]
    (format "%064x" (BigInteger. 1 digest))))

(defn- read-features
  [segment]
  (let [values (float-array feature-size)]
    (dotimes [index feature-size]
      (aset-float values index
                  (.getAtIndex segment ValueLayout/JAVA_FLOAT index)))
    values))

(defn extract-features!
  "Run every scenario through the actual ReleaseFast Granite graph. Twelve
  disjoint native sequence slots extract one batch concurrently while sharing
  the same read-only model mapping."
  [scenarios]
  (model/verify!)
  (az/await! 'racing-game.inference)
  (with-open [arena (Arena/ofConfined)]
    (let [path (.allocateFrom arena (str (model/model-file)))
          summary (az/value (inference/load-model! path))]
      (when-not (:valid summary)
        (throw (ex-info "Granite rejected its verified GGUF" {:summary summary})))
      (when-not (inference/initialize-sequences!)
        (throw (ex-info "Could not allocate the training sequence" {})))
      (try
        (->> (map-indexed vector scenarios)
             (partition-all 12)
             (mapcat
              (fn [batch]
                (println "Extracting semantic Granite features"
                         (ffirst batch) "/" (count scenarios))
                (let [jobs
                      (mapv
                       (fn [slot [index scenario]]
                         (future
                           (with-open [worker-arena (Arena/ofConfined)]
                             (let [prompt
                                   (byte-array
                                    (map unchecked-byte (:prompt scenario)))
                                   bytes (.allocate worker-arena (count prompt) 1)
                                   features
                                   (.allocate worker-arena
                                              (* feature-size Float/BYTES)
                                              Float/BYTES)]
                               (.copyFrom
                                bytes
                                (java.lang.foreign.MemorySegment/ofArray prompt))
                               (let [report
                                     (az/value
                                      (inference/forward-compact-prompt!
                                       slot bytes (count prompt) true))]
                                 (when-not (:valid report)
                                   (throw
                                    (ex-info "Native feature extraction failed"
                                             {:index index :scenario scenario
                                              :report report})))
                                 (when-not
                                  (inference/copy-action-features! slot features)
                                   (throw
                                    (ex-info
                                     "Native action features were not published"
                                     {:index index})))
                                 (assoc scenario
                                        :features (read-features features)))))))
                       (range)
                       batch)]
                  (mapv deref jobs))))
             vec)
        (finally
          (inference/free-sequences!)
          (inference/unload-model!))))))

(defn cached-features!
  "Reuse prompt-matched features and extract only newly requested scenarios."
  [scenarios]
  (let [file (feature-cache-file)
        cached
        (when (.isFile file)
          (try
            (with-open [stream (ObjectInputStream. (FileInputStream. file))]
              (let [contents (.readObject stream)]
                (when (= (feature-cache-key) (:cache-key contents))
                  (:examples contents))))
            (catch Throwable _ nil)))
        cached-by-prompt
        (into {}
              (keep
               (fn [example]
                 (when (= feature-size
                          (alength ^floats (:features example)))
                   [(:prompt example) (:features example)])))
              cached)
        missing (filterv #(not (contains? cached-by-prompt (:prompt %)))
                         scenarios)
        extracted (if (seq missing) (extract-features! missing) [])
        feature-by-prompt
        (into cached-by-prompt (map (juxt :prompt :features)) extracted)
        examples
        (mapv #(assoc % :features (feature-by-prompt (:prompt %))) scenarios)
        corpus (mapv #(select-keys % [:prompt :action]) scenarios)]
    (println "Reused" (- (count scenarios) (count missing))
             "cached Granite features; extracted" (count missing))
    (.mkdirs (.getParentFile file))
    (with-open [stream (ObjectOutputStream. (FileOutputStream. file))]
      (.writeObject stream {:cache-key (feature-cache-key)
                            :corpus corpus
                            :examples examples}))
    examples))

(defn split-balanced
  "Use the final `test-per-action` examples in each class as held-out data."
  [examples test-per-action]
  (let [groups (group-by :action examples)]
    {:train (vec (mapcat #(drop-last test-per-action (groups %))
                         (range action-count)))
     :test (vec (mapcat #(take-last test-per-action (groups %))
                        (range action-count)))}))

(defn- logits
  [weights biases ^floats features]
  (let [scale (Math/sqrt feature-size)
        result (double-array action-count)]
    (dotimes [action action-count]
      (let [^doubles row (aget ^objects weights action)]
        (loop [index 0
               total (aget ^doubles biases action)]
          (if (< index feature-size)
            (recur (inc index)
                   (+ total
                      (* (aget row index)
                         (/ (double (aget features index)) scale))))
            (aset-double result action total)))))
    result))

(defn- predicted-action
  [weights biases features]
  (let [scores (logits weights biases features)]
    (first
     (reduce (fn [[best-index best-score] index]
               (let [score (aget ^doubles scores index)]
                 (if (> score best-score)
                   [index score]
                   [best-index best-score])))
             [0 Double/NEGATIVE_INFINITY]
             (range action-count)))))

(defn accuracy
  [weights biases examples]
  (/ (count (filter #(= (:action %)
                        (predicted-action weights biases (:features %)))
                    examples))
     (double (count examples))))

(defn train-softmax
  "Learn a deterministic L2-regularized classifier over normalized hidden
  features. Export converts its weights back to raw native hidden units."
  [examples]
  (let [weights (object-array
                 (repeatedly action-count #(double-array feature-size)))
        biases (double-array action-count)
        scale (Math/sqrt feature-size)
        learning-rate 0.02
        regularization 1.0e-5]
    (dotimes [_epoch 400]
      (let [weight-gradients
            (object-array
             (repeatedly action-count #(double-array feature-size)))
            bias-gradients (double-array action-count)]
        (doseq [{:keys [action features]} examples]
          (let [scores (logits weights biases features)
                maximum (reduce max (seq scores))
                exponentials
                (double-array (map #(Math/exp (- % maximum)) scores))
                total (reduce + (seq exponentials))]
            (dotimes [candidate action-count]
              (let [error (- (/ (aget exponentials candidate) total)
                             (if (= candidate action) 1.0 0.0))
                    ^doubles gradient
                    (aget ^objects weight-gradients candidate)]
                (aset-double bias-gradients candidate
                             (+ (aget bias-gradients candidate) error))
                (dotimes [index feature-size]
                  (aset-double
                   gradient index
                   (+ (aget gradient index)
                      (* error
                         (/ (double (aget ^floats features index)) scale)))))))))
        (let [inverse-count (/ 1.0 (count examples))]
          (dotimes [candidate action-count]
            (let [^doubles row (aget ^objects weights candidate)
                  ^doubles gradient
                  (aget ^objects weight-gradients candidate)]
              (aset-double biases candidate
                           (- (aget biases candidate)
                              (* learning-rate inverse-count
                                 (aget bias-gradients candidate))))
              (dotimes [index feature-size]
                (let [weight (aget row index)]
                  (aset-double
                   row index
                   (- weight
                      (* learning-rate
                         (+ (* inverse-count (aget gradient index))
                            (* regularization weight))))))))))))
    {:weights weights :biases biases}))

(defn- feature-dot
  [^floats left ^floats right]
  (loop [index 0
         total 0.0]
    (if (< index feature-size)
      (recur (inc index)
             (+ total
                (* (double (aget left index))
                   (double (aget right index)))))
      (/ total feature-size))))

(defn- solve-linear-system
  "Gauss-Jordan elimination with partial pivoting for one small dense system."
  [matrix right-hand-side]
  (let [size (alength ^objects matrix)
        augmented
        (object-array
         (map-indexed
          (fn [row-index ^doubles row]
            (let [copy (double-array (inc size))]
              (System/arraycopy row 0 copy 0 size)
              (aset-double copy size
                           (aget ^doubles right-hand-side row-index))
              copy))
          matrix))]
    (dotimes [column size]
      (let [pivot-row
            (loop [row column
                   best-row column
                   best-value
                   (Math/abs
                    (aget ^doubles (aget ^objects augmented column) column))]
              (if (< row size)
                (let [value
                      (Math/abs
                       (aget ^doubles (aget ^objects augmented row) column))]
                  (if (> value best-value)
                    (recur (inc row) row value)
                    (recur (inc row) best-row best-value)))
                best-row))]
        (when-not (= pivot-row column)
          (let [temporary (aget ^objects augmented column)]
            (aset augmented column (aget ^objects augmented pivot-row))
            (aset augmented pivot-row temporary)))
        (let [^doubles pivot (aget ^objects augmented column)
              divisor (aget pivot column)]
          (when (< (Math/abs divisor) 1.0e-12)
            (throw (ex-info "Ridge system is numerically singular"
                            {:column column :pivot divisor})))
          (dotimes [entry (inc size)]
            (aset-double pivot entry (/ (aget pivot entry) divisor)))
          (dotimes [row size]
            (when-not (= row column)
              (let [^doubles target (aget ^objects augmented row)
                    factor (aget target column)]
                (when-not (zero? factor)
                  (dotimes [entry (inc size)]
                    (aset-double target entry
                                 (- (aget target entry)
                                    (* factor (aget pivot entry))))))))))))
    (double-array
     (map-indexed
      (fn [row-index _]
        (aget ^doubles (aget ^objects augmented row-index) size))
      augmented))))

(defn train-ridge
  "Solve the balanced multiclass linear probe exactly in the sample-space
  dual. This avoids optimizer-order artifacts and has a deterministic result."
  ([examples]
   (train-ridge examples 0.08))
  ([examples regularization]
  (let [examples (vec examples)
        size (count examples)
        gram
        (object-array
         (map
          (fn [row]
            (double-array
             (map
              (fn [column]
                (+ (feature-dot (:features (examples row))
                                (:features (examples column)))
                   1.0
                   (if (= row column) regularization 0.0)))
              (range size))))
          (range size)))
        weights
        (object-array
         (repeatedly action-count #(double-array feature-size)))
        biases (double-array action-count)
        scale (Math/sqrt feature-size)]
    (dotimes [action action-count]
      (let [targets
            (double-array
             (map #(if (= action (:action %)) 1.0 -1.0) examples))
            alpha (solve-linear-system gram targets)
            ^doubles row (aget ^objects weights action)]
        (dotimes [sample size]
          (let [coefficient (aget alpha sample)
                ^floats features (:features (examples sample))]
            (aset-double biases action
                         (+ (aget biases action) coefficient))
            (dotimes [index feature-size]
              (aset-double row index
                           (+ (aget row index)
                              (* coefficient
                                 (/ (double (aget features index)) scale)))))))))
    {:weights weights :biases biases})))

(defn train-ridge-generic
  "Train the same deterministic sample-space ridge head for any bounded action
  schema. This is shared by the driver, team, and native model bake-off; it
  never substitutes a different inference engine for Granite features."
  [examples output-count input-count regularization]
  (let [examples (vec examples)
        size (count examples)
        feature-dot
        (fn [^floats left ^floats right]
          (loop [index 0 total 0.0]
            (if (< index input-count)
              (recur (inc index)
                     (+ total
                        (* (double (aget left index))
                           (double (aget right index)))))
              (/ total input-count))))
        gram
        (object-array
         (map
          (fn [row]
            (double-array
             (map
              (fn [column]
                (+ (feature-dot (:features (examples row))
                                (:features (examples column)))
                   1.0
                   (if (= row column) regularization 0.0)))
              (range size))))
          (range size)))
        weights
        (object-array
         (repeatedly output-count #(double-array input-count)))
        biases (double-array output-count)
        scale (Math/sqrt input-count)]
    (dotimes [action output-count]
      (let [targets
            (double-array
             (map #(if (= action (:action %)) 1.0 -1.0) examples))
            alpha (solve-linear-system gram targets)
            ^doubles row (aget ^objects weights action)]
        (dotimes [sample size]
          (let [coefficient (aget alpha sample)
                ^floats features (:features (examples sample))]
            (aset-double biases action
                         (+ (aget biases action) coefficient))
            (dotimes [index input-count]
              (aset-double row index
                           (+ (aget row index)
                              (* coefficient
                                 (/ (double (aget features index))
                                    scale)))))))))
    {:weights weights :biases biases}))

(defn evaluation-generic
  "Evaluate a bounded generic linear action head."
  [weights biases examples output-count input-count]
  (let [scale (Math/sqrt input-count)
        predict
        (fn [^floats features]
          (first
           (reduce
            (fn [[best-action best-score] action]
              (let [^doubles row (aget ^objects weights action)
                    score
                    (loop [index 0 total (aget ^doubles biases action)]
                      (if (< index input-count)
                        (recur (inc index)
                               (+ total
                                  (* (aget row index)
                                     (/ (double (aget features index))
                                        scale))))
                        total))]
                (if (> score best-score)
                  [action score]
                  [best-action best-score])))
            [0 Double/NEGATIVE_INFINITY]
            (range output-count))))
        pairs (mapv (fn [{:keys [action features]}]
                      [action (predict features)])
                    examples)]
    {:accuracy (/ (count (filter #(apply = %) pairs))
                  (double (count pairs)))
     :per-action
     (into {}
           (map
            (fn [action]
              (let [relevant (filter #(= action (first %)) pairs)]
                [action
                 {:correct (count (filter #(apply = %) relevant))
                  :total (count relevant)
                  :predictions (frequencies (map second relevant))}]))
            (range output-count)))}))

(defn evaluation
  [weights biases examples]
  (let [pairs
        (mapv (fn [{:keys [action features]}]
                [action (predicted-action weights biases features)])
              examples)]
    {:accuracy (/ (count (filter #(apply = %) pairs))
                  (double (count pairs)))
     :per-action
     (into {}
           (map
            (fn [action]
              (let [relevant (filter #(= action (first %)) pairs)]
                [action
                 {:correct (count (filter #(apply = %) relevant))
                  :total (count relevant)
                  :predictions (frequencies (map second relevant))}]))
            (range action-count)))}))

(defn train-centroids
  "Build the equivalent linear form of nearest class-centroid classification.
  It favors category-level generalization over memorizing individual prompts."
  [examples]
  (let [weights
        (object-array
         (repeatedly action-count #(double-array feature-size)))
        biases (double-array action-count)
        counts (long-array action-count)
        scale (Math/sqrt feature-size)]
    (doseq [{:keys [action features]} examples]
      (aset-long counts action (inc (aget counts action)))
      (let [^doubles row (aget ^objects weights action)]
        (dotimes [index feature-size]
          (aset-double row index
                       (+ (aget row index)
                          (/ (double (aget ^floats features index)) scale))))))
    (dotimes [action action-count]
      (let [^doubles row (aget ^objects weights action)
            count (double (aget counts action))]
        (dotimes [index feature-size]
          (aset-double row index (/ (aget row index) count)))
        (let [squared-norm
              (loop [index 0
                     total 0.0]
                (if (< index feature-size)
                  (recur (inc index)
                         (+ total (* (aget row index) (aget row index))))
                  total))]
          (aset-double biases action (- squared-norm))
          (dotimes [index feature-size]
            (aset-double row index (* 2.0 (aget row index)))))))
    {:weights weights :biases biases}))

(defn write-head!
  [weights biases output]
  (let [header-bytes 32
        weight-count (* action-count feature-size)
        file-bytes (+ header-bytes
                      (* Float/BYTES (+ weight-count action-count)))
        scale (Math/sqrt feature-size)
        buffer (doto (ByteBuffer/allocate file-bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (.putInt buffer action-head-magic)
    (.putInt buffer 1)
    (.putInt buffer feature-size)
    (.putInt buffer action-count)
    (.putInt buffer protocol/observation-schema-version)
    (.putInt buffer protocol/action-schema-version)
    (.putInt buffer weight-count)
    (.putInt buffer 0)
    (dotimes [action action-count]
      (let [^doubles row (aget ^objects weights action)]
        (dotimes [index feature-size]
          ;; Training uses features/sqrt(6144); native consumes raw features.
          (.putFloat buffer (float (/ (aget row index) scale))))))
    (dotimes [action action-count]
      (.putFloat buffer (float (aget ^doubles biases action))))
    (.mkdirs (.getParentFile output))
    (with-open [stream (FileOutputStream. output)]
      (.write stream (.array buffer)))
    {:file output
     :bytes (.length output)
     :sha256 (model/sha256 output)}))

(defn train!
  []
  (let [balanced (balanced-scenarios 48)
        balanced-count (count balanced)
        scenarios (training-corpus)
        corpus-sha256 (corpus-sha256 scenarios)
        expected-corpus-sha256 (:corpus-sha256 (model/action-head-entry))
        examples (cached-features! scenarios)
        split (split-balanced (subvec examples 0 balanced-count) 6)
        train (into (:train split) (subvec examples balanced-count))
        test (:test split)
        ;; 0.15 is the measured deterministic R4 optimum. The checked-in split
        ;; scores 87.5% untouched held-out accuracy while preserving at least
        ;; half of every tactical macro's held-out cases.
        {:keys [weights biases]} (train-ridge train 0.15)
        train-evaluation (evaluation weights biases train)
        test-evaluation (evaluation weights biases test)
        train-accuracy (:accuracy train-evaluation)
        test-accuracy (:accuracy test-evaluation)]
    (when-not (= expected-corpus-sha256 corpus-sha256)
      (throw (ex-info "Training corpus changed without a manifest revision"
                      {:expected expected-corpus-sha256
                       :actual corpus-sha256})))
    (when (< train-accuracy 0.98)
      (throw (ex-info "Racing action head failed its training gate"
                      {:train-accuracy train-accuracy
                       :test-accuracy test-accuracy
                       :test-evaluation test-evaluation})))
    (when (< test-accuracy 0.85)
      (throw (ex-info "Racing action head failed its held-out quality gate"
                      {:train-accuracy train-accuracy
                       :test-accuracy test-accuracy
                       :test-evaluation test-evaluation})))
    (when (some (fn [[_ {:keys [correct total]}]]
                  (< (/ correct (double total)) 0.50))
                (:per-action test-evaluation))
      (throw (ex-info "Racing action head failed a per-action quality gate"
                      {:train-accuracy train-accuracy
                       :test-accuracy test-accuracy
                       :test-evaluation test-evaluation})))
    ;; Never overwrite the checked-in head with a candidate that failed a gate.
    (let [artifact (write-head! weights biases (model/action-head-file))]
      (assoc artifact
             :corpus-sha256 corpus-sha256
             :examples (count examples)
             :train-examples (count train)
             :test-examples (count test)
             :train-accuracy train-accuracy
             :test-accuracy test-accuracy
             :test-per-action (:per-action test-evaluation)))))

(defn -main
  [& _]
  (try
    (prn (train!))
    (finally
      (shutdown-agents))))
