(ns racing-game.train-action-head
  "Offline supervised training for the tiny native A-H racing action head.

  Granite remains the feature extractor. This namespace generates deterministic
  expert-labelled R2 observations, runs the real native model, learns a linear
  softmax head, evaluates a held-out split, and writes the fixed binary artifact
  consumed by racing-game.inference. It is never part of the release runtime."
  (:require [aguafria.zig :as az]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol])
  (:import [java.io FileInputStream FileOutputStream
            ObjectInputStream ObjectOutputStream]
           [java.lang.foreign Arena ValueLayout]
           [java.nio ByteBuffer ByteOrder]
           [java.util Random]))

(def feature-size 6144)

(def action-count 8)

(def action-head-magic 0x48415241)

(defn feature-cache-file
  []
  (java.io.File. (model/project-root) "build/training/r2-features.ser"))

(defn feature-cache-key
  []
  {:extractor-version 1
   :model-sha256 (:sha256 (model/model-entry))
   :observation-schema protocol/observation-schema-version
   :feature-size feature-size})

(defn- category-byte
  [value]
  (+ 65 (min 25 value)))

(defn observation-bytes
  "Encode the exact native R2 positional prompt for one training scenario."
  [{:keys [target persona rank lap item progress speed urgent]}]
  (byte-array
   (map unchecked-byte
        [(+ 81 protocol/observation-schema-version)
         (category-byte (+ (* target 3) (min persona 2)))
         (category-byte (max 0 (dec rank)))
         (category-byte lap)
         (category-byte item)
         (category-byte (long (min 9.0 (* (max 0.0 progress) 10.0))))
         (category-byte (long (min 9.0 (* (max 0.0 speed) 100.0))))
         (category-byte (if urgent 1 0))])))

(defn expert-action
  "Deterministic tactical teacher over only fields available to the racer.
  It chooses among the same eight legal actions as the native validator."
  [{:keys [persona rank item progress speed urgent]}]
  (cond
    ;; The held object already names its role; the action selects a compatible
    ;; lane/pace/use mode. Explicit target selection remains a separate field.
    (= item 1) 4
    (= item 2) 5
    (= item 3) 6
    (= item 4) 7
    (= item 5) 4
    (= item 6) 6

    ;; An empty-handed surprise is the explicit urgent attack/evasion mode.
    urgent 3

    ;; Stable empty-handed intent follows the racer's persistent persona.
    :else persona))

(defn- random-scenario
  [^Random random]
  {:target (.nextInt random 8)
   :persona (.nextInt random 3)
   :rank (inc (.nextInt random 8))
   :lap (.nextInt random 3)
   :item (.nextInt random 7)
   :progress (/ (inc (.nextInt random 19)) 20.0)
   :speed (+ 0.04 (* 0.005 (.nextInt random 12)))
   :urgent (.nextBoolean random)})

(defn golden-scenarios
  "One human-authored, minimal observation for every legal action class."
  []
  (mapv
   (fn [scenario]
     (assoc scenario
            :action (expert-action scenario)
            :prompt (vec (observation-bytes scenario))))
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

(defn- read-features
  [segment]
  (let [values (float-array feature-size)]
    (dotimes [index feature-size]
      (aset-float values index
                  (.getAtIndex segment ValueLayout/JAVA_FLOAT index)))
    values))

(defn extract-features!
  "Run every scenario through the actual ReleaseFast Granite graph."
  [scenarios]
  (model/verify!)
  (az/await! 'racing-game.inference)
  (with-open [arena (Arena/ofConfined)]
    (let [path (.allocateFrom arena (str (model/model-file)))
          features (.allocate arena (* feature-size Float/BYTES) Float/BYTES)
          summary (az/value (inference/load-model! path))]
      (when-not (:valid summary)
        (throw (ex-info "Granite rejected its verified GGUF" {:summary summary})))
      (when-not (inference/initialize-sequences!)
        (throw (ex-info "Could not allocate the training sequence" {})))
      (try
        (mapv
         (fn [index scenario]
           (when (zero? (mod index 8))
             (println "Extracting Granite features" index "/" (count scenarios)))
           (let [prompt (byte-array (map unchecked-byte (:prompt scenario)))
                 bytes (.allocate arena (count prompt) 1)]
             (.copyFrom bytes (java.lang.foreign.MemorySegment/ofArray prompt))
             (let [report
                   (az/value
                    (inference/forward-compact-prompt!
                     0 bytes (count prompt) true))]
               (when-not (:valid report)
                 (throw (ex-info "Native feature extraction failed"
                                 {:index index :scenario scenario
                                  :report report})))
               (when-not (inference/copy-action-features! 0 features)
                 (throw (ex-info "Native action features were not published"
                                 {:index index})))
               (assoc scenario :features (read-features features)))))
         (range)
         scenarios)
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
  (let [scenarios (balanced-scenarios 18)
        examples (cached-features! scenarios)
        {:keys [train test]} (split-balanced examples 3)
        {:keys [weights biases]} (train-ridge train 2.0)
        train-evaluation (evaluation weights biases train)
        test-evaluation (evaluation weights biases test)
        train-accuracy (:accuracy train-evaluation)
        test-accuracy (:accuracy test-evaluation)
        artifact (write-head! weights biases (model/action-head-file))]
    (when (< train-accuracy 0.98)
      (throw (ex-info "Racing action head failed its training gate"
                      {:train-accuracy train-accuracy
                       :test-accuracy test-accuracy
                       :test-evaluation test-evaluation})))
    (when (< test-accuracy 0.80)
      (throw (ex-info "Racing action head failed its held-out quality gate"
                      {:train-accuracy train-accuracy
                       :test-accuracy test-accuracy
                       :test-evaluation test-evaluation})))
    (assoc artifact
           :examples (count examples)
           :train-examples (count train)
           :test-examples (count test)
           :train-accuracy train-accuracy
           :test-accuracy test-accuracy
           :test-per-action (:per-action test-evaluation))))

(defn -main
  [& _]
  (try
    (prn (train!))
    (finally
      (shutdown-agents))))
