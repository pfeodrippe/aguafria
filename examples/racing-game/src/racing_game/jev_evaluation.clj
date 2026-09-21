(ns racing-game.jev-evaluation
  "Evaluation only: paired native Granite/Jev decisions, never training data.
  Load with the :jev-evaluation alias. Credentials are read only inside run-jev!."
  (:require [aguafria.zig :as az]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [racing-game.dataset :as dataset]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.native-evaluation :as native]
            [racing-game.model-bakeoff :as bakeoff]
            [racing-game.simulation :as simulation]
            [racing-game.train-action-head :as driver]
            [racing-game.train-team-head :as team]
            [racing-game.worker :as worker])
  (:import [java.lang.foreign Arena MemorySegment]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]
           [java.util Random]))

(def output-directory "build/jev-evaluation")
(def seed 20260920)
(def concurrency 4)

(defn write-edn! [name data]
  (let [file (io/file output-directory name)]
    (io/make-parents file)
    (spit file (pr-str data))
    (str file)))

(defn read-edn [name]
  (edn/read-string (slurp (io/file output-directory name))))

(defn driver-scenario [o]
  (-> o
      (assoc :target-distance (:target_distance o)
             :target-lane (:target_lane o)
             :tactical-status (:tactical_status o))))

(defn driver-case [id suite observation acceptable]
  {:id id :suite suite :kind :driver :observation observation
   :state (driver/observation-text (driver-scenario observation))
   :acceptable (vec (sort acceptable))
   :expert (driver/expert-action (driver-scenario observation))})

(defn fresh-driver-cases []
  (let [random (Random. seed)]
    (vec
     (for [item (range 7) status (range 4) persona (range 3)]
       (let [racer (.nextInt random 20)
             gap (.nextInt random 10)
             o {:valid true :racer racer :rank (inc (.nextInt random 20))
                :target (if (= gap 9) racer (mod (inc racer) 20))
                :persona persona :item item :target_distance gap
                :target_lane (.nextInt random 3) :tactical_status status
                :urgent (.nextBoolean random) :lap (.nextInt random 3)
                :progress (.nextDouble random)
                :speed (+ 0.04 (* 0.06 (.nextDouble random)))}]
         (driver-case (str "fresh-driver-" item "-" status "-" persona)
                      :fresh-driver o (dataset/acceptable-actions o)))))))

(defn team-case [id suite scenario]
  {:id id :suite suite :kind :team
   :state (team/team-text scenario)
   :acceptable [(:action scenario)] :expert (:action scenario)})

(defn fresh-team-cases []
  (let [random (Random. (+ seed 1))]
    (loop [buckets {0 [] 1 [] 2 []}]
      (if (every? #(= 20 (count %)) (vals buckets))
        (vec (mapcat (fn [action]
                       (map-indexed #(team-case (str "fresh-team-" action "-" %1)
                                                :fresh-team %2)
                                    (get buckets action))) (range 3)))
        (let [entry (team/random-scenario random)
              action (:action entry)]
          (recur (if (< (count (get buckets action)) 20)
                   (update buckets action conj entry) buckets)))))))

(defn rollout-cases! []
  (simulation/configure-countdown! 0)
  (simulation/set-items-enabled! true)
  (simulation/set-human-controlled! false)
  (vec
   (for [race-seed [21 22 23]
         tick [240 960 2400 4800]
         racer [0 7]]
     (do
       (simulation/set-race-seed! race-seed)
       (simulation/reset!)
       (simulation/step-many! tick)
       (let [o (az/value (simulation/current-observation racer))
             acceptable (dataset/acceptable-actions o)
             row {:source :native-race :seed race-seed :tick tick :racer racer
                  :observation o :acceptable-actions acceptable}]
         (assoc (driver-case (str "rollout-" race-seed "-" tick "-" racer)
                             :native-rollout o acceptable)
                :row row))))))

(defn policy-cases []
  (vec (concat
        (map #(driver-case (name (:name %)) :human-driver
                           (:observation %) (:acceptable-actions %))
             (dataset/golden-cases))
        (fresh-driver-cases)
        (map-indexed #(team-case (str "golden-team-" %1) :golden-team %2)
                     (take 15 (team/golden-scenarios)))
        (fresh-team-cases))))

(defn prepare! []
  (az/await! 'racing-game.simulation)
  (az/await! 'racing-game.inference)
  (when (:started (az/value (worker/summary)))
    (throw (ex-info "Use an isolated REPL with stopped game workers" {})))
  (let [cases (into (policy-cases) (rollout-cases!))
        manifest {:created (str (Instant/now)) :seed seed
                  :git-head (str/trim (:out (shell/sh "git" "rev-parse" "HEAD")))
                  :compiler-note "Current local compiler checkout, including benchmark compatibility fixes."
                  :model (select-keys (model/model-entry) [:filename :sha256])
                  :driver-head (select-keys (model/action-head-entry) [:filename :sha256])
                  :team-head (select-keys (model/team-head-entry) [:filename :sha256])
                  :selection-warning (:selection (model/manifest))
                  :case-count (count cases) :suites (frequencies (map :suite cases))
                  :selection :argmax :concurrency concurrency}]
    (write-edn! "cases.edn" cases)
    (write-edn! "manifest.edn" manifest)
    manifest))

(defn run-native!
  ([] (native/run-native!))
  ([cases output-name] (native/run-native! cases output-name)))

(def driver-instructions
  (str "Choose the best next tactical macro for this arcade racing driver. "
       "Honor cautious/balanced/bold personality. Recovering means stunned: favor steady control, save items. "
       "A gap of 9 means no actionable rival. Bolt targets a nearby rival; pulse disrupts nearby traffic; "
       "trap leaves a rear hazard; shield protects against an immediate threat; boost and surge increase pace/progress. "
       "Avoid wasting defensive or targeted items when there is no threat or target. "
       "Use boost/surge for maximum pace. Native physics validates the lane and ignores item use if inventory is empty. "
       "Choose a lateral evasion or attacking escape for urgency/hazards. "
       "The state contains only the driver's permitted observation, not the full track."))

(def driver-criteria
  {"A" "Left lane; steady pace; hold item."
   "B" "Center lane; steady pace; hold item."
   "C" "Right lane; steady pace; hold item."
   "D" "Left lane; attack pace; hold item."
   "E" "Center lane; attack pace; use item."
   "F" "Right lane; attack pace; use item."
   "G" "Left lane; maximum pace; use item."
   "H" "Center lane; maximum pace; use item."})

(def team-instructions
  (str "Choose the pit-wall instruction. Only call a driver whose pit state is out. "
       "If the box is occupied, stay out. Service is needed when tire <=46% or damage >=60%. "
       "If both eligible drivers need service, prioritize the larger urgency (100-tire)+1.35*damage; "
       "break ties in favor of the better (smaller) rank, then A. Otherwise stay out. "
       "Choose A or B by their labels, not their numeric car IDs."))

(defn request-body [case model-name]
  {:model model-name :state (:state case)
   :questions {:action {:type "choice"
                        :instructions (if (= :team (:kind case)) team-instructions driver-instructions)
                        :criteria (if (= :team (:kind case))
                                    {"A" "Stay out; do not call either driver."
                                     "B" "Call driver A into the pit."
                                     "C" "Call driver B into the pit."}
                                    driver-criteria)}}})

(defn- credential [file]
  ;; Never evaluate .jev as shell code, put its contents in argv, or return it.
  (let [raw (str/trim (slurp file))
        value (if-let [[_ v] (re-matches #"(?s)(?:export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=\s*(.+)" raw)] v raw)
        value (str/replace (str/trim value) #"^[\"']|[\"']$" "")]
    (when (or (str/blank? value) (re-find #"\s" value))
      (throw (ex-info "Credential file must contain one raw key or KEY=value" {})))
    value))

(defn- jev-one [^HttpClient client key model-name case]
  (let [started (System/nanoTime)]
    (try
      (let [request (-> (HttpRequest/newBuilder (URI/create "https://api.typesafe.ai/v1/systemone"))
                        (.timeout (Duration/ofSeconds 30))
                        (.header "Authorization" (str "Bearer " key))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString
                                (json/write-str (request-body case model-name))))
                        (.build))
            response (.send client request (HttpResponse$BodyHandlers/ofString))
            status (.statusCode response)
            elapsed (/ (- (System/nanoTime) started) 1e6)]
        (if (= status 200)
          (let [body (json/read-str (str/replace (.body response) key "[REDACTED]") :key-fn keyword)
                answer (get-in body [:answers :action])
                choice (:choice answer)
                action (get {"A" 0 "B" 1 "C" 2 "D" 3 "E" 4 "F" 5 "G" 6 "H" 7} choice)
                valid (and (some? action) (<= action (if (= :team (:kind case)) 2 7)))
                usage (into {} (filter (fn [[_ v]] (number? v)) (:usage body)))]
            {:id (:id case) :suite (:suite case) :status status
             :model (:model body) :action action :valid (boolean valid)
             :confidence (:confidence answer) :probabilities (:probabilities answer)
             :usage usage :latency-ms elapsed})
          {:id (:id case) :suite (:suite case) :valid false
           :status status :latency-ms elapsed}))
      (catch Exception _
        ;; Exceptions and response bodies may contain request information.
        {:id (:id case) :suite (:suite case) :valid false
         :error :request-failed :latency-ms (/ (- (System/nanoTime) started) 1e6)}))))

(defn run-jev!
  ([] (run-jev! "../../.jev"))
  ([key-file]
   (run-jev! key-file (read-edn "cases.edn") "jev.edn"))
  ([key-file cases output-name]
   (let [key (credential key-file)
         client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))
         warmup (jev-one client key "jev-latest" (first cases))]
     (when-not (:valid warmup)
       (write-edn! "jev-error.edn" warmup)
       (throw (ex-info "Jev warmup failed; no further requests sent" (select-keys warmup [:status :error]))))
     (let [model-name (:model warmup)
           _ (write-edn! "jev-prompt.edn" {:driver-instructions driver-instructions
                                          :driver-criteria driver-criteria
                                          :team-instructions team-instructions
                                          :model model-name})
           results
           (vec
            (mapcat
             (fn [batch]
               (let [started (System/nanoTime)
                     jobs (mapv #(future (jev-one client key model-name %)) batch)
                     rows (mapv deref jobs)]
                 (when (some #(contains? #{401 402 403 429 529} (:status %)) rows)
                   (write-edn! "jev-error.edn" rows)
                   (throw (ex-info "Jev authorization, credit, or rate limit; stopping bounded evaluation"
                                   {:statuses (mapv :status rows)})))
                 (println "Jev completed" (:id (last batch)))
                 ;; Bound submission to 800 requests/minute, outside measured latency.
                 (Thread/sleep (long (max 0 (- 300 (/ (- (System/nanoTime) started) 1e6)))))
                 rows))
             (partition-all concurrency cases)))]
       (write-edn! output-name {:warmups [warmup] :model model-name :results results})
       {:completed (count results) :model model-name
        :usage (apply merge-with + (:usage warmup) (map :usage results))}))))

(defn score [cases results]
  (let [by-id (into {} (map (juxt :id identity) results))
        rows (mapv (fn [c]
                     (let [r (get by-id (:id c))]
                       (assoc r :id (:id c)
                              :accepted (boolean (and (:valid r)
                                                      (some #{(:action r)} (:acceptable c))))
                              :acceptable (:acceptable c)))) cases)]
    {:cases (count cases) :accepted (count (filter :accepted rows))
     :invalid (count (remove :valid rows))
     :accuracy (/ (count (filter :accepted rows)) (double (max 1 (count rows))))
     :latency-ms (bakeoff/distribution (keep :latency-ms rows))
     :failures (filterv (complement :accepted) rows)}))

(defn summarize! []
  (let [cases (read-edn "cases.edn")
        granite (:results (read-edn "granite.edn"))
        jev (:results (read-edn "jev.edn"))
        expert (mapv #(assoc (select-keys % [:id :suite]) :action (:expert %) :valid true) cases)
        report {:created (str (Instant/now)) :manifest (read-edn "manifest.edn")
                :jev-model (:model (read-edn "jev.edn"))
                :suites (into (sorted-map)
                              (for [[suite entries] (group-by :suite cases)]
                                [suite {:deterministic (score entries expert)
                                        :granite (score entries granite)
                                        :jev (score entries jev)}]))
                :jev-usage (apply merge-with + (map :usage jev))}]
    (write-edn! "summary.edn" report)
    (spit (io/file output-directory "summary.json") (json/write-str report))
    report))

(defn run-rollouts! []
  (let [cases (filter #(= :native-rollout (:suite %)) (read-edn "cases.edn"))
        granite (into {} (map (juxt :id identity) (:results (read-edn "granite.edn"))))
        jev (into {} (map (juxt :id identity) (:results (read-edn "jev.edn"))))
        results
        (mapv (fn [c]
                (let [g (get granite (:id c)) j (get jev (:id c))
                      actions (distinct (concat (:acceptable c) [(:expert c) (:action g) (:action j)]))
                      outcomes (into {} (for [a actions :when (some? a)]
                                          [a (dataset/rollout-action! (:row c) a)]))]
                  (println "Rollout completed" (:id c))
                  {:id (:id c) :deterministic (get outcomes (:expert c))
                   :granite (get outcomes (:action g))
                   :jev (get outcomes (:action j)) :outcomes outcomes})) cases)]
    (write-edn! "rollouts.edn" results)
    (spit (io/file output-directory "rollouts.json") (json/write-str results))
    {:completed (count results)}))

(defn -main [& [key-file]]
  (try
    (println "Preparing paired cases" (prepare!))
    (println "Native evaluation" (run-native!))
    (println "Jev evaluation" (run-jev! (or key-file "../../.jev")))
    (summarize!)
    (println "Matched rollouts" (run-rollouts!))
    (println "Results saved under" output-directory)
    (finally (shutdown-agents))))
