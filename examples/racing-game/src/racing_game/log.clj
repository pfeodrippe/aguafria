(ns racing-game.log
  "Persistent, human-readable exports of the native cognition ring buffers."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [racing-game.core :as game]
            [racing-game.model :as model]
            [racing-game.telemetry :as telemetry]))

(def item-names
  {0 "no item"
   1 "bolt"
   2 "trap"
   3 "boost"
   4 "shield"
   5 "pulse"
   6 "surge"})

(def default-per-racer-limit
  "One completed decision per racer keeps the normal artifact glanceable."
  1)

(defn- ordinal
  [rank]
  (case rank
    1 "1st"
    2 "2nd"
    3 "3rd"
    (str rank "th")))

(defn- lane-phrase
  [lane]
  (case lane
    :left "on the left"
    :same-lane "in the same lane"
    :right "on the right"
    "at an unknown relative position"))

(defn- chosen-lane-phrase
  [lane]
  (case lane
    :left "move left"
    :center "stay centered"
    :right "move right"
    "use an unknown lane"))

(defn- pace-phrase
  [pace]
  (case pace
    :steady "steady pace"
    :attack "attack pace"
    :maximum "maximum pace"
    "use an unknown pace"))

(defn- status-phrase
  [status]
  (case status
    :clear "clear"
    :hazard-near "hazard nearby"
    :stunned "recovering from a hit"
    :shielded "shield active"
    "unknown local condition"))

(defn decision-traces
  "Return decoded, oldest-first decisions for every racer. Prefer the newest
  decisions whose one-second result is available; retain a current unresolved
  decision only when that racer has no completed result yet."
  ([]
   (decision-traces default-per-racer-limit {}))
  ([per-racer-limit]
   (decision-traces per-racer-limit {}))
  ([per-racer-limit options]
   (->> (range 8)
        (mapcat (fn [racer]
                  (let [available (min (long telemetry/entries-per-racer)
                                       (long (telemetry/decision-count racer)))
                        retained (keep #(game/decision-trace racer % options)
                                       (range available))
                        completed (filter #(get-in % [:outcome :resolved])
                                          retained)]
                    (take (long per-racer-limit)
                          (if (seq completed) completed retained)))))
        (sort-by (juxt #(get-in % [:ticks :install]) :racer :revision))
        vec)))

(defn default-file
  []
  (io/file (model/project-root) "build/logs/decision-traces.edn"))

(defn default-text-file
  []
  (io/file (model/project-root) "build/logs/decision-traces.txt"))

(defn explain-protocol
  "Explain the reproducibility-oriented wire representation, separately."
  [trace]
  (let [{:keys [source observation intent]} trace]
    (if (and (= :llm source)
             (seq (:prompt observation))
             (some? (:token-id intent)))
      (str
       (format (str "The exact UTF-8 prompt %s became Granite token IDs %s "
                    "and ran through the native model in %d ordered steps. ")
               (pr-str (:prompt observation))
               (pr-str (vec (:input-tokens observation)))
               (:model-step-count observation))
       (format "The constrained output %s was Granite token ID %d."
               (pr-str (:token intent)) (:token-id intent)))
      (format "No model prompt or output exists for this %s action."
              (name source)))))

(defn explain
  "Summarize what one racer perceived, chose, and caused in plain English."
  [trace]
  (let [{:keys [racer revision source observation intent validation timing-us outcome]}
        trace
        resolved? (:resolved outcome)
        progress-bin (long (or (:progress-bin observation)
                               (Math/floor
                                (* 10.0 (double (:progress observation))))))
        speed-bin (long (or (:speed-bin observation)
                            (Math/floor
                             (* 100.0 (double (:speed observation))))))
        prompt (:prompt observation)
        held-item (get item-names (:item observation)
                       (str "unknown item " (:item observation)))
        item-choice
        (cond
          (and (= :use (:item intent)) (= 0 (:item observation)))
          "item requested, but inventory empty"

          (= :use (:item intent))
          (str "use " held-item)

          (= 0 (:item observation))
          "no item"

          :else
          (str "save " held-item))
        controller
        (case source
          :llm
          (format "Granite AI · %.1f ms · %.2f model steps/s · %s"
                  (/ (double (:total timing-us)) 1000.0)
                  (double (:tokens-per-second timing-us))
                  (case (:deadline validation)
                    :on-time "on time"
                    :expired "late"
                    "deadline unknown"))

          :replay "recorded replay · immediate"
          :human "human input · immediate"

          (if (= :expired (:deadline validation))
            "safe fallback · Granite missed its deadline"
            "safe fallback · Granite had not finished"))
        action-label
        (case source
          :llm "Chose"
          :replay "Replay action"
          :human "Human action"
          "Fallback action")]
    (str
     (format "Racer %d · decision %d · %s\n" racer revision controller)
     (if (seq prompt)
       (format "Saw: %s\n" prompt)
       (format (str "State: %s of 8 · lap %d · %d-%d%% through the lap · "
                    "speed %d-%d%% of a lap/s · inventory: %s · %s.\n")
               (ordinal (:rank observation)) (inc (:lap observation))
               (* 10 progress-bin) (* 10 (inc progress-bin))
               speed-bin (inc speed-bin) held-item
               (if (:urgent observation)
                 "urgent"
                 "routine")))
     (format "%s: %s · %s · %s · %s.\n"
             action-label
             (chosen-lane-phrase (:lane intent))
             (pace-phrase (:pace intent))
             item-choice
             (if (= racer (:target intent))
               "no target"
               (format "target racer %d" (:target intent))))
     (if resolved?
       (format (str "Result after 1 second: +%.2f%% of a lap · rank %s to %s · "
                    "%d %s · %s.")
               (* 100.0 (double (:progress_gain outcome)))
               (ordinal (:start_rank outcome)) (ordinal (:end_rank outcome))
               (:hits_dealt outcome)
               (if (= 1 (:hits_dealt outcome)) "hit" "hits")
               (if (:item_used outcome) "item used" "item not used"))
       "Result after 1 second: not measured yet."))))

(defn explanation
  "Return the four human lines as named data for EDN, an in-game monitor, or
  another UI. `explain` renders these same values without a second decoder."
  [trace]
  (let [[controller situation decision outcome]
        (str/split-lines (explain trace))]
    {:controller controller
     :situation situation
     :decision decision
     :outcome outcome}))

(defn- compact-trace
  [trace]
  (-> trace
      (update :observation dissoc :input-tokens)
      (update :intent dissoc :token :token-id)
      (update :validation dissoc :code)
      (dissoc :schemas :ticks :provenance :sampling)))

(defn text-report
  "Render the same compact report written to disk. Raw protocol is absent
  unless `:include-raw?` is explicitly true."
  [traces cognition {:keys [include-raw? per-racer-limit]
                     :or {include-raw? false
                          per-racer-limit default-per-racer-limit}}]
  (str
   "Aguafria Racing - native racer decision log\n"
   "============================================\n\n"
   "Each entry says what a racer saw, what it did, and what happened one "
   "simulated second later.\n"
   (when-not include-raw?
     (str "Technical protocol details are hidden. Export with "
          "{:include-raw? true} only when you need them.\n"))
   "\n"
   (format (str "Showing %d newest completed decisions (up to %d per racer; "
                "current state is used only when no result is complete).\n"
                "History: %d Granite decisions · %d accepted · %d rejected · "
                "%d measured outcomes · %.3f average model steps/s.\n\n")
           (count traces) per-racer-limit
           (:llm_entries cognition) (:accepted_entries cognition)
           (:rejected_entries cognition) (:resolved_outcomes cognition)
           (double (:average_tokens_per_second cognition)))
   (str/join "\n\n"
             (map #(if include-raw?
                     (str (explain %) "\nRaw protocol: "
                          (explain-protocol %))
                     (explain %))
                  traces))
   "\n"))

(defn- write-text!
  [file traces cognition include-raw? per-racer-limit]
  (let [output (io/file file)]
    (io/make-parents output)
    (spit output (text-report traces cognition
                              {:include-raw? include-raw?
                               :per-racer-limit per-racer-limit}))
    output))

(defn write!
  "Write compact human-readable text and EDN logs. `:per-racer-limit` bounds
  the newest retained decisions exported for each racer. Pass
  `{:include-raw? true}` only when exact prompts, tokens, and model provenance
  are wanted."
  ([]
   (write! {}))
  ([{:keys [edn-file text-file include-raw? per-racer-limit]
     :or {edn-file (default-file)
          text-file (default-text-file)
          include-raw? false
          per-racer-limit default-per-racer-limit}}]
   (let [output (io/file edn-file)
         traces (decision-traces per-racer-limit
                                  {:include-raw? include-raw?})
         cognition (game/cognition-status)
         document {:format :aguafria-racing/decision-traces-v1
                   :trace-count (count traces)
                   :per-racer-limit per-racer-limit
                   :cognition cognition
                   :worker (game/worker-status)
                   :raw-protocol? include-raw?
                   :traces (mapv #(cond-> (assoc (if include-raw?
                                                   %
                                                   (compact-trace %))
                                                 :meaning (explanation %))
                                    include-raw?
                                    (assoc :protocol-explanation
                                           (explain-protocol %)))
                                 traces)}]
     (io/make-parents output)
     (with-open [writer (io/writer output)]
       (binding [*out* writer
                 *print-length* nil
                 *print-level* nil]
         (pprint/pprint document)))
     {:edn output
      :text (write-text! text-file traces cognition include-raw?
                         per-racer-limit)
      :trace-count (count traces)})))
