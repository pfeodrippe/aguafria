(ns racing-game.train-team-head
  "Native-Granite feature training for the independent three-action pit wall.

  The labels, prompts, and linear head are team-specific. Feature extraction
  always runs through racing-game.inference, the exact Aguafria/Zig engine used
  by the live game; no external model runtime participates in this workflow."
  (:require [racing-game.model :as model]
            [racing-game.protocol :as protocol]
            [racing-game.train-action-head :as train])
  (:import [java.io FileInputStream FileOutputStream ObjectInputStream
            ObjectOutputStream]
           [java.math BigInteger]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]
           [java.util Random]))

(def feature-size 6144)

(def action-count 3)

(def team-head-magic 0x4d414554)

(defonce latest-examples (atom nil))

(defn feature-cache-file
  []
  (java.io.File. (model/project-root)
                 "build/training/r4-team-features.ser"))

(defn feature-cache-key
  []
  {:extractor-version 4
   :model-sha256 (:sha256 (model/model-entry))
   :observation-schema protocol/observation-schema-version
   :feature-size feature-size
   :actor-contract :two-consecutive-drivers-per-team-v1})

(defn pit-state-text
  [value]
  (case value 1 "called", 2 "servicing", 3 "exiting", "out"))

(defn team-text
  "Render the exact ordinary-English prompt used by the native worker."
  [{:keys [team driver-a driver-b rank-a rank-b tire-a tire-b damage-a
           damage-b pit-a pit-b box-occupied]}]
  (format (str "Team %d. A%d: rank %d/8, tire %d%% %s, damage %d%% %s, %s. "
               "B%d: rank %d/8, tire %d%% %s, damage %d%% %s, %s. Box %s.")
          team driver-a rank-a tire-a (if (<= tire-a 46) "worn" "usable")
          damage-a (if (>= damage-a 60) "repair" "sound")
          (pit-state-text pit-a)
          driver-b rank-b tire-b (if (<= tire-b 46) "worn" "usable")
          damage-b (if (>= damage-b 60) "repair" "sound")
          (pit-state-text pit-b)
          (if box-occupied "occupied" "free")))

(defn team-bytes
  [scenario]
  (.getBytes ^String (team-text scenario) "US-ASCII"))

(defn needs-pit?
  [tire damage pit-state]
  (and (zero? pit-state)
       (or (<= tire 46) (>= damage 60))))

(defn service-urgency
  [tire damage]
  (+ (- 100 tire) (* 1.35 damage)))

(defn expert-action
  "Human-readable pit policy: 0 stay out, 1 pit A, 2 pit B."
  [{:keys [rank-a rank-b tire-a tire-b damage-a damage-b pit-a pit-b
           box-occupied]}]
  (if box-occupied
    0
    (let [a? (needs-pit? tire-a damage-a pit-a)
          b? (needs-pit? tire-b damage-b pit-b)]
      (cond
        (and a? (not b?)) 1
        (and b? (not a?)) 2
        (and a? b?)
        (let [a-urgency (service-urgency tire-a damage-a)
              b-urgency (service-urgency tire-b damage-b)]
          (cond
            (> a-urgency b-urgency) 1
            (> b-urgency a-urgency) 2
            (<= rank-a rank-b) 1
            :else 2))
        :else 0))))

(def defaults
  {:team 0 :driver-a 0 :driver-b 1 :rank-a 2 :rank-b 6
   :tire-a 82 :tire-b 78 :damage-a 4 :damage-b 7
   :pit-a 0 :pit-b 0 :box-occupied false})

(defn scenario
  [overrides]
  (let [complete (merge defaults overrides)]
    (assoc complete
           :action (expert-action complete)
           :prompt (vec (team-bytes complete)))))

(defn golden-scenarios
  "Explicit team decisions covering normal, emergency, contention, and box
  ownership cases. These situations are also used by the native bake-off."
  []
  (vec
   (mapcat
    (fn [team]
      (let [driver-a (* team 2)
            driver-b (inc driver-a)
            identity {:team team :driver-a driver-a :driver-b driver-b}]
        (map (comp scenario #(merge identity %))
             [{}
              {:rank-a 8 :rank-b 7 :tire-a 17 :tire-b 99
               :damage-a 0 :damage-b 0}
              {:rank-a 7 :rank-b 8 :tire-a 99 :tire-b 17
               :damage-a 0 :damage-b 0}
              {:rank-a 1 :rank-b 8 :damage-a 72 :damage-b 8}
              {:rank-a 8 :rank-b 1 :damage-a 8 :damage-b 72}
              {:tire-a 20 :damage-a 80 :tire-b 15 :damage-b 10}
              {:tire-a 20 :damage-a 10 :tire-b 20 :damage-b 80}
              {:tire-a 20 :tire-b 20 :rank-a 1 :rank-b 5}
              {:tire-a 20 :tire-b 20 :rank-a 7 :rank-b 2}
              {:tire-a 10 :damage-a 90 :box-occupied true}
              {:tire-b 10 :damage-b 90 :box-occupied true}
              {:tire-a 20 :pit-a 1}
              {:tire-b 20 :pit-b 2}
              {:tire-a 47 :damage-a 59 :tire-b 47 :damage-b 59}
              {:tire-a 100 :damage-a 0 :tire-b 100 :damage-b 0}])))
    (range 4))))

(defn random-scenario
  [^Random random]
  (let [team (.nextInt random 4)
        complete
        {:team team
         :driver-a (* team 2)
         :driver-b (inc (* team 2))
         :rank-a (inc (.nextInt random 8))
         :rank-b (inc (.nextInt random 8))
         :tire-a (.nextInt random 101)
         :tire-b (.nextInt random 101)
         :damage-a (.nextInt random 101)
         :damage-b (.nextInt random 101)
         :pit-a (.nextInt random 4)
         :pit-b (.nextInt random 4)
         :box-occupied (< (.nextDouble random) 0.20)}]
    (scenario complete)))

(defn balanced-scenarios
  [per-action]
  (let [random (Random. 0x7EA41234)
        buckets (object-array action-count)
        golden (golden-scenarios)]
    (dotimes [action action-count]
      (aset buckets action (transient [])))
    (doseq [entry golden]
      (aset buckets (:action entry)
            (conj! (aget buckets (:action entry)) entry)))
    (loop [seen (set (map :prompt golden))]
      (if (every? #(>= (count %) per-action) buckets)
        (vec (mapcat persistent! buckets))
        (let [entry (random-scenario random)
              action (:action entry)]
          (if (or (contains? seen (:prompt entry))
                  (>= (count (aget buckets action)) per-action))
            (recur seen)
            (do
              (aset buckets action (conj! (aget buckets action) entry))
              (recur (conj seen (:prompt entry))))))))))

(defn corpus-sha256
  [scenarios]
  (let [payload
        (byte-array
         (map unchecked-byte
              (mapcat #(conj (:prompt %) (:action %)) scenarios)))
        digest (.digest (MessageDigest/getInstance "SHA-256") payload)]
    (format "%064x" (BigInteger. 1 digest))))

(defn split-balanced
  [examples test-per-action]
  (let [groups (group-by :action examples)]
    {:train (vec (mapcat #(drop-last test-per-action (groups %))
                         (range action-count)))
     :test (vec (mapcat #(take-last test-per-action (groups %))
                        (range action-count)))}))

(defn cached-features!
  "Reuse exact native features by semantic prompt and extract only new cases."
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
              (keep (fn [example]
                      (when (= feature-size
                               (alength ^floats (:features example)))
                        [(:prompt example) (:features example)])))
              cached)
        missing (filterv #(not (contains? cached-by-prompt (:prompt %)))
                         scenarios)
        extracted (if (seq missing) (train/extract-features! missing) [])
        feature-by-prompt
        (into cached-by-prompt (map (juxt :prompt :features)) extracted)
        examples
        (mapv #(assoc % :features (feature-by-prompt (:prompt %))) scenarios)]
    (println "Reused" (- (count scenarios) (count missing))
             "team features; extracted" (count missing))
    (.mkdirs (.getParentFile file))
    (with-open [stream (ObjectOutputStream. (FileOutputStream. file))]
      (.writeObject stream {:cache-key (feature-cache-key)
                            :examples examples}))
    examples))

(defn write-head!
  [weights biases output]
  (let [header-bytes 32
        weight-count (* action-count feature-size)
        file-bytes (+ header-bytes
                      (* Float/BYTES (+ weight-count action-count)))
        scale (Math/sqrt feature-size)
        buffer (doto (ByteBuffer/allocate file-bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (.putInt buffer team-head-magic)
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
          (.putFloat buffer (float (/ (aget row index) scale))))))
    (dotimes [action action-count]
      (.putFloat buffer (float (aget ^doubles biases action))))
    (.mkdirs (.getParentFile output))
    (with-open [stream (FileOutputStream. output)]
      (.write stream (.array buffer)))
    {:file output :bytes (.length output) :sha256 (model/sha256 output)}))

(defn train!
  []
  (let [scenarios (balanced-scenarios 72)
        corpus-sha (corpus-sha256 scenarios)
        expected-corpus (:corpus-sha256 (model/team-head-entry))
        examples (cached-features! scenarios)
        _ (reset! latest-examples examples)
        {:keys [train test]} (split-balanced examples 12)
        {:keys [weights biases]}
        (train/train-ridge-generic train action-count feature-size 3.0)
        train-evaluation
        (train/evaluation-generic weights biases train action-count feature-size)
        test-evaluation
        (train/evaluation-generic weights biases test action-count feature-size)]
    (when (and expected-corpus (not= "pending" expected-corpus)
               (not= expected-corpus corpus-sha))
      (throw (ex-info "Team corpus changed without a manifest revision"
                      {:expected expected-corpus :actual corpus-sha})))
    (when (< (:accuracy train-evaluation) 0.95)
      (throw (ex-info "Team head failed its training gate"
                      {:evaluation train-evaluation})))
    (when (< (:accuracy test-evaluation) 0.90)
      (throw (ex-info "Team head failed its held-out gate"
                      {:evaluation test-evaluation})))
    (let [artifact (write-head! weights biases (model/team-head-file))]
      (assoc artifact
             :corpus-sha256 corpus-sha
             :examples (count examples)
             :train-examples (count train)
             :test-examples (count test)
             :train-accuracy (:accuracy train-evaluation)
             :test-accuracy (:accuracy test-evaluation)
             :test-per-action (:per-action test-evaluation)))))

(defn -main
  [& _]
  (try
    (prn (train!))
    (finally
      (shutdown-agents))))
