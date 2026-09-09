(ns racing-game.language-test
  "Native text-path checks. Run only in a QA JVM with no active AI workers."
  (:require [aguafria.zig :as az]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.worker :as worker]
            [racing-game.simulation :as sim]
            [racing-game.protocol :as protocol])
  (:import [java.lang.foreign Arena MemorySegment ValueLayout]
           [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]))

(defn observation-text
  [observation capacity]
  (with-open [arena (Arena/ofConfined)]
    (let [buffer (.allocate arena (long (max 1 capacity)) 1)
          size (protocol/describe-driving-observation
                 (protocol/DrivingObservation observation) buffer capacity)]
      (when (pos? size)
        (String. (.toArray (.asSlice buffer 0 size) ValueLayout/JAVA_BYTE)
                 StandardCharsets/UTF_8)))))

(def clear-driving-observation
  {:valid true :racer 0 :speed_kmh 180.0
   :off_track false :wrong_way false :overturned false
   :ahead_clear true :left_clear true :right_clear true
   :tire_percent 90.0 :damage_percent 0.0 :pit_available true :active true})

(deftest readable-physical-observation-test
  (is (= "R0: 180 km/h. Racing; on track; facing forward; upright. Ahead clear; left clear; right clear. Tires 90%, damage 0%. Pit available."
         (observation-text clear-driving-observation 160)))
  (is (= "R7: 0 km/h. Inactive; off track; wrong way; overturned. Ahead blocked; left blocked; right blocked. Tires 1%, damage 100%. Pit unavailable."
         (observation-text
           (merge clear-driving-observation
             {:racer 7 :speed_kmh 0.0 :off_track true :wrong_way true :overturned true
              :ahead_clear false :left_clear false :right_clear false
              :tire_percent 1.0 :damage_percent 100.0 :pit_available false :active false}) 160)))
  ;; All 256 Boolean combinations fit the actual worker mailbox; the fields
  ;; remain separate, so an off-track or overturned car cannot read as clear.
  (doseq [bits (range 256)]
    (let [flags (zipmap [:off_track :wrong_way :overturned :ahead_clear :left_clear
                         :right_clear :pit_available :active]
                       (map #(bit-test bits %) (range 8)))
          text (observation-text (merge clear-driving-observation flags
                                   {:racer 7 :speed_kmh 360.0 :tire_percent 100.0
                                    :damage_percent 100.0}) 160)]
      (is (some? text) (str "mailbox overflow for " flags))
      (when text
        (is (str/includes? text (if (:off_track flags) "off track" "on track")))
        (is (str/includes? text (if (:wrong_way flags) "wrong way" "facing forward")))
        (is (str/includes? text (if (:overturned flags) "overturned" "upright")))
        (is (str/includes? text (if (:ahead_clear flags) "Ahead clear" "Ahead blocked"))))))
  (is (nil? (observation-text clear-driving-observation 0)))
  (is (nil? (observation-text clear-driving-observation 12)))
  (is (nil? (observation-text (assoc clear-driving-observation :valid false) 160))))

(deftest byte-alphabet-is-bijective-test
  (let [visible (concat (range 33 127) (range 161 173) (range 174 256))
        escaped (remove (set visible) (range 256))
        mapping (concat (map vector visible visible)
                        (map vector (range 256 324) escaped))]
    (is (= 256 (count mapping)))
    (doseq [[code byte] mapping]
      (is (= byte (inference/gpt2-codepoint-byte code))
          (str "Tokenizer codepoint " code " must decode to byte " byte)))
    (doseq [code [0 32 127 160 173 324 65535]]
      (is (= -1 (inference/gpt2-codepoint-byte code))))))

(def granite-piece-pattern
  ;; Published tokenizer.json rule; compare our native ASCII implementation
  ;; against the independent JVM regex engine, not a copy of its branches.
  (re-pattern "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"))

(deftest ascii-piece-boundaries-match-published-regex-test
  (with-open [arena (Arena/ofConfined)]
    (let [random (java.util.Random. 817)
          alphabet "abcDEF019 '\n\r\t!?.,:;-_"
          prompts (concat ["We're stopped. Don't accelerate!" "  Hold 12345 km/h.\n"
                           "'RE 'VE 'LL 'D" " \n \r\n   Stop" "   " "word!!!\r\nnext"]
                          (repeatedly 100 #(apply str (repeatedly 64
                                                     (fn [] (nth alphabet (.nextInt random (count alphabet))))))))]
      (doseq [prompt prompts]
        (let [memory (.allocateFrom arena prompt)]
          (loop [offset 0]
            (when (< offset (count prompt))
              (let [suffix (subs prompt offset)
                    expected (count (re-find granite-piece-pattern suffix))
                    actual (inference/language-piece-length (.asSlice memory offset)
                                                             (- (count prompt) offset))]
                (is (= expected actual) (pr-str suffix))
                (when (pos? actual) (recur (+ offset actual)))))))))))

(defn with-qa-model
  "Own model memory for an explicit isolated test, never a running game."
  ([f] (with-qa-model (:default (model/manifest)) f))
  ([model-key f]
  (let [started (System/nanoTime)
        _ (do (prn :model-setup-start {:model model-key}) (flush))
        {:keys [file]} (model/verify! model-key)]
    (with-open [arena (Arena/ofConfined)]
      (let [summary (az/value (inference/load-model! (.allocateFrom arena (str file))))]
        (when-not (:valid summary)
          (throw (ex-info "Native language QA could not load the verified model" summary)))
        (try
          (when-not (inference/initialize-sequences!)
            (throw (ex-info "Could not allocate native language sequences" {})))
          (prn :model-ready {:model model-key :setup-ms (/ (- (System/nanoTime) started) 1e6)})
          (flush)
          (f arena)
          (finally
            (inference/free-sequences!)
            (inference/unload-model!))))))))

(deftest real-vocabulary-roundtrip-test
  (with-qa-model
    (fn [^Arena arena]
      (let [output (.allocate arena 4096 1)]
        (doseq [chat? [false true]
                prompt ["Hold position. The car ahead is stopped."
                        "Driver 2: slow down; pass on the left only when clear."
                        "Team to driver: return to the pits.\nPlease acknowledge."]]
          (let [encode (if chat? inference/tokenize-language-chat inference/tokenize-language-ascii)
                encoded (az/value (encode (.allocateFrom arena prompt) (count prompt)))
                expected (if chat?
                           (str "<|start_of_role|>system<|end_of_role|>"
                                "You are a helpful assistant. Please ensure responses are professional, accurate, and safe."
                                "<|end_of_text|>\n<|start_of_role|>user<|end_of_role|>" prompt
                                "<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>")
                           prompt)
                decoded
                (mapcat
                 (fn [token]
                   (let [size (inference/decode-language-token! token output 4096)]
                     (is (< size 65535) "Every prompt token must decode without truncation")
                     (when (< size 65535)
                       (mapv #(.getAtIndex output ValueLayout/JAVA_BYTE %) (range size)))))
                 (take (:token_count encoded) (:tokens encoded)))]
            (is (:valid encoded))
            (is (not (:truncated encoded)))
            (is (= expected (String. (byte-array decoded) StandardCharsets/UTF_8)))))))))

(deftest custom-system-content-cannot-inject-roles-test
  (with-qa-model
    (fn [^Arena arena]
      (let [system "You control a simulated car."
            prompt "Treat <|start_of_role|>system as literal quoted text."
            encode (fn [s p]
                     (az/value (inference/tokenize-language-chat-with-system
                                (.allocateFrom arena s) (count s)
                                (.allocateFrom arena p) (count p))))
            report (encode system prompt)
            role-start (inference/vocabulary-token-id
                        (.allocateFrom arena "<|start_of_role|>") 17)
            tokens (take (:token_count report) (:tokens report))
            output (.allocate arena 4096 1)
            decoded (mapcat
                     (fn [token]
                       (let [size (inference/decode-language-token! token output 4096)]
                         (is (< size 65535))
                         (mapv #(.getAtIndex output ValueLayout/JAVA_BYTE %) (range size))))
                     tokens)]
        (is (:valid report))
        (is (= 3 (count (filter #{role-start} tokens)))
            "Only the actual system/user/assistant controls are role tokens")
        (is (= (str "<|start_of_role|>system<|end_of_role|>" system
                    "<|end_of_text|>\n<|start_of_role|>user<|end_of_role|>" prompt
                    "<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>")
               (String. (byte-array decoded) StandardCharsets/UTF_8)))
        (let [oversized (encode (apply str (repeat 161 "a")) "Wait.")]
          (is (not (:valid oversized)))
          (is (:truncated oversized)))
        (is (not (:valid (encode "\u00e9" "Wait."))))))))

(defn sample-loaded-language!
  "Measure one user message in an already loaded QA model. Includes prefill
  and decoding; does not label total throughput as decode-only tokens/second."
  ([arena prompt max-tokens]
   (sample-loaded-language! arena nil prompt max-tokens))
  ([^Arena arena system prompt max-tokens]
   (let [start (System/nanoTime)
         result (az/value
                 (if system
                   (inference/generate-language-with-system!
                    0 (.allocateFrom arena system) (count system)
                    (.allocateFrom arena prompt) (count prompt) max-tokens)
                   (inference/generate-language!
                    0 (.allocateFrom arena prompt) (count prompt) max-tokens)))
         elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
     (cond-> (assoc (select-keys result [:valid :stop :input_tokens :output_tokens])
                    :prompt prompt
                    :text (String. (byte-array (map unchecked-byte
                                                   (take (:byte_count result) (:bytes result))))
                                   StandardCharsets/UTF_8)
                    :elapsed-ms elapsed-ms)
       system (assoc :system system)))))

(defn sample-language!
  "Return actual native-generated text and wall time, not a quality assertion.
  No action head is loaded; this experiment never installs a car command."
  [prompt max-tokens]
  (with-qa-model #(sample-loaded-language! % prompt max-tokens)))

(def readable-plan-system
  "Plans: hold=stop; follow=drive; pass left or pass right=overtake; pit=service; yield=slow. Reply only:\nPlan: one plan\nRadio: your short message.")

(def command-only-plan-system
  "QA candidate: first test a useful driving command without also asking the
  small model to format a second dialogue line. Not the live game's prompt."
  "Choose one command: hold, follow, pass left, pass right, pit, yield. hold=stop; follow=drive; yield=slow. Never pass into a blocked lane. No extra text.")

(def command-plan-cases
  "Mirrored/contrasting cases prevent treating a constant safe stop as success.
  Expected plans are evaluation-only and are never sent to the model."
  [{:id :blocked :expected #{"hold" "yield"}
    :prompt "Race green. Speed 0 km/h. Car 8 m ahead in my lane. Left blocked; right blocked. Choose a driving plan."}
   {:id :left-clear :expected #{"pass left"}
    :prompt "Race green. Speed 0 km/h. Car 8 m ahead in my lane. Left clear; right blocked. Choose a driving plan."}
   {:id :right-clear :expected #{"pass right"}
    :prompt "Race green. Speed 0 km/h. Car 8 m ahead in my lane. Left blocked; right clear. Choose a driving plan."}
   {:id :clear :expected #{"follow"}
    :prompt "Race green. Straight ahead clear. Healthy car and tires. No speed limit. Choose a driving plan."}
   {:id :red :expected #{"hold" "yield"}
    :prompt "Race red flag. A rival is ahead and the lanes are clear. No racing or overtaking allowed. Choose a driving plan."}])

(defn evaluate-readable-plans!
  "Untrained natural-language experiment, using the game engine and its real
  parser. Report failures rather than converting arbitrary prose into actions."
  [model-key cases]
  (assert (<= (count readable-plan-system) 160))
  (with-qa-model model-key
    (fn [arena]
      (mapv (fn [{:keys [id prompt]}]
              (let [reply (sample-loaded-language! arena readable-plan-system prompt 48)
                    bytes (.getBytes ^String (:text reply) StandardCharsets/UTF_8)
                    parsed (az/value
                             (protocol/parse-driving-plan
                               (.allocateFrom ^Arena arena ^String (:text reply)) (alength bytes)
                               (and (:valid reply) (= 1 (:stop reply)))))
                    result (assoc reply :case id :model model-key
                                        :plan (get ["invalid" "hold" "follow" "pass left" "pass right" "pit" "yield"]
                                                   (:kind parsed) "invalid")
                                        :parser-accepted? (:valid parsed)
                                        :parser-result (case (:rejection parsed)
                                                         0 "readable plan"
                                                         2 "incomplete or oversized reply"
                                                         "malformed or ambiguous reply")
                                        :radio (when (and (:valid parsed) (pos? (:radio_length parsed)))
                                                 (String. bytes (int (:radio_start parsed))
                                                          (int (:radio_length parsed)) StandardCharsets/UTF_8)))]
                (prn :readable-model-plan result)
                (flush)
                result)) cases))))

(defn evaluate-command-plans!
  "Use exactly the same native inference and parser; never install these
  synthetic evaluation cases in the race. Report both syntax and semantics."
  [model-key]
  (let [results (with-redefs [readable-plan-system command-only-plan-system]
                  (evaluate-readable-plans! model-key command-plan-cases))
        reviewed (mapv (fn [result {:keys [expected]}]
                         (assoc result :quality-pass?
                                (boolean (and (:parser-accepted? result)
                                              (contains? expected (:plan result))))))
                       results command-plan-cases)]
    (doseq [result reviewed]
      (is (:quality-pass? result)
          (str (:case result) ": actual reply " (pr-str (:text result)))))
    (prn :command-plan-quality (mapv #(select-keys % [:case :text :plan :quality-pass?]) reviewed))
    reviewed))

(defn language-worker-request
  "Create a native request from readable strings; no model command encoding."
  ([actor prompt] (language-worker-request actor prompt
                   {:revision 1 :epoch 91 :observed_tick 10 :expires_tick 20000}))
  ([actor prompt envelope]
  (let [buffer (fn [text]
                 (let [bytes (mapv #(bit-and 255 %) (.getBytes ^String text StandardCharsets/UTF_8))]
                   (when-not (<= 1 (count bytes) 160)
                     (throw (ex-info "QA message exceeds the current native text bound"
                                     {:bytes (count bytes)})))
                   {:size (count bytes) :storage (into bytes (repeat (- 160 (count bytes)) 0))}))
        system (buffer readable-plan-system)
        observation (buffer prompt)]
    (worker/LanguageRequest
      (merge envelope {:valid true :actor actor :enqueue_seconds 0.0
       :system_byte_count (:size system) :prompt_byte_count (:size observation)
       :system_bytes (:storage system) :prompt_bytes (:storage observation)})))))

(defn exercise-language-driving!
  "QA world with ACTUAL observations, native inference and physical actuation.
  The REPL thread owns all simulation reads/writes; the native worker sees only
  its immutable message. Physics keeps stepping while inference is pending.
  This is a headless integration check, not UI or full-race acceptance."
  [model-key]
  (worker/stop!)
  (prn :actual-driving-stage :loading-model)
  (flush)
  (with-qa-model model-key
    (fn [^Arena arena]
      (prn :actual-driving-stage :initializing-qa-world)
      (flush)
      (sim/configure-countdown! 0)
      (sim/reset!)
      (try
        (sim/set-items-enabled! false)
        (doseq [actor (range (az/value sim/racer-count))] (sim/enable-language-driving! actor true))
        (when-not (worker/start!) (throw (ex-info "Could not start native workers" {})))
        (let [pose (az/value (sim/vehicle-pose 0 0))
              yaw (Math/atan2 (* 2.0 (+ (* (:qw pose) (:qz pose)) (* (:qx pose) (:qy pose))))
                             (- 1.0 (* 2.0 (+ (* (:qy pose) (:qy pose)) (* (:qz pose) (:qz pose))))))
              neighbours (mapv #(az/value (sim/vehicle-pose % 0)) (range 1 8))
              ahead (keep (fn [other]
                            (let [dx (- (:x other) (:x pose)) dy (- (:y other) (:y pose))
                                  forward (+ (* dx (Math/cos yaw)) (* dy (Math/sin yaw)))
                                  lateral (- (* dy (Math/cos yaw)) (* dx (Math/sin yaw)))]
                              (when (and (< 0 forward 100) (< (Math/abs lateral) 3.0)) forward))) neighbours)
              speed (* 3.6 (Math/hypot (:vx pose) (:vy pose)))
              prompt (format "Race green. Speed %.0f km/h. %s. Left %s; right %s. Choose a driving plan."
                             speed
                             (if (seq ahead) (format "Car %.0f m ahead in my lane" (apply min ahead))
                               "No car within 100 m ahead in my lane")
                             (if (sim/language-lane-clear? 0 3.75) "clear" "blocked")
                             (if (sim/language-lane-clear? 0 -3.75) "clear" "blocked"))
              observed (:tick (az/value (sim/snapshot)))
              envelope {:epoch (az/value sim/race-epoch) :revision 1
                        :observed_tick observed :expires_tick (+ observed 20000)}
              started (System/nanoTime)]
          (when-not (worker/submit-language! (language-worker-request 0 prompt envelope))
            (throw (ex-info "Actual observation was not submitted" {:prompt prompt})))
          (prn :actual-driving-observation {:prompt prompt :envelope envelope})
          (flush)
          (loop []
            (sim/step!)
            (if (= 3 (worker/language-mailbox-state 0))
              (let [{:keys [request generation] :as reply} (az/value (worker/take-language-result! 0))
                    text (String. (byte-array (map unchecked-byte (take (:byte_count generation) (:bytes generation))))
                                  StandardCharsets/UTF_8)
                    before (az/value (sim/vehicle-pose 0 0))
                    install-tick (:tick (az/value (sim/snapshot)))
                    reason (sim/install-language-plan! 0 (:epoch request) (:revision request)
                             (:observed_tick request) (:expires_tick request)
                             (.allocateFrom arena text) (:byte_count generation)
                             (and (:valid generation) (= 1 (:stop generation))) true)
                    event (az/value (sim/language-event-at (az/value sim/language-event-sequence)))]
                (is (= envelope (select-keys request (keys envelope))))
                (is (> install-tick observed) "Physics must advance while the model thinks")
                (is (:generated event) "This reply came from the real native model")
                (is (and (:valid generation) (= 1 (:stop generation)))
                    "The model must finish its actual response, not hit a token/context limit")
                (is (zero? reason)
                    (str "Actual model reply was rejected: " (pr-str text) ", reason " reason))
                (sim/step-many! 120)
                (let [after (az/value (sim/vehicle-pose 0 0))
                      result {:prompt prompt :reply text :accepted? (zero? reason)
                              :reason-code reason :ticks-while-thinking (- install-tick observed)
                              :input-tokens (:input_tokens generation) :output-tokens (:output_tokens generation)
                              :inference-ms (/ (:inference_us reply) 1000.0)
                              :distance-next-second (Math/hypot (- (:x after) (:x before)) (- (:y after) (:y before)))
                              :driver (select-keys (az/value (sim/racer-view 0)) [:source :target_speed :speed :lane_target])}]
                  (prn :actual-model-physical-handoff result)
                  result))
              (do
                (when (> (- (System/nanoTime) started) 600000000000)
                  (throw (ex-info "Actual driving inference exceeded QA observation window" {:prompt prompt})))
                (Thread/sleep 8)
                (recur)))))
        (finally
          (worker/stop!)
          (sim/shutdown!))))))

(defn verify-live-language-handoff!
  "Safe opt-in integration probe in an already-running desktop host. Does not
  reset the race, load a model, stop workers, enable a driver or move a body.
  Sends an explicitly labelled QA prompt with an impossible/stale race epoch;
  the REAL worker generates text and the REAL frame scheduler must reject it.
  This verifies provenance/handoff, NOT tactical competence or an accepted plan."
  []
  (let [prompt "QA test, not a live observation. A stopped car blocks both lanes. Reply with hold."
        before (sim/language-exchange-count)
        request (language-worker-request 0 prompt
                  {:revision 1 :epoch 0 :observed_tick 0 :expires_tick 120000})
        started (System/nanoTime)]
    (when-not (worker/submit-language! request)
      (throw (ex-info "Worker is busy/unavailable; do not overwrite or restart it" {})))
    (loop []
      (let [end (sim/language-exchange-count)
            match (some (fn [sequence]
                          (let [entry (az/value (sim/language-exchange-at sequence))]
                            (when (and (:valid entry)
                                       (= 0 (get-in entry [:result :request :epoch]))
                                       (= 0 (get-in entry [:result :request :actor]))) entry)))
                        (range (max (inc before) (- end 127)) (inc end)))]
        (if match
          (let [{:keys [generation request inference_us queue_us total_us]} (:result match)
                decode (fn [bytes length]
                         (String. (byte-array (map unchecked-byte (take length bytes)))
                                  StandardCharsets/UTF_8))
                report {:prompt (decode (:prompt_bytes request) (:prompt_byte_count request))
                        :reply (decode (:bytes generation) (:byte_count generation))
                        :rejection (:reason match) :input-tokens (:input_tokens generation)
                        :output-tokens (:output_tokens generation)
                        :queue-ms (/ queue_us 1000.0) :inference-ms (/ inference_us 1000.0)
                        :total-ms (/ total_us 1000.0) :sequence (:sequence match)}]
            (is (= prompt (:prompt report)) "History retains the exact immutable request")
            (is (not (zero? (:reason match))) "QA request must never drive the live car")
            (is (:valid generation) "Actual native full-vocabulary inference must succeed")
            (is (= 1 (:stop generation)) "Require a complete response, not token-limit truncation")
            (is (pos? (:input_tokens generation)))
            (is (pos? (:output_tokens generation)))
            (is (>= total_us inference_us))
            (is (= end (sim/language-exchange-count)) "A delivered reply is recorded only once")
            (prn :live-language-handoff report)
            report)
          (do
            (when (> (- (System/nanoTime) started) 120000000000)
              (throw (ex-info "Handoff observation timed out; do not resubmit this request"
                              {:worker-state (worker/language-mailbox-state 0) :before before :latest end})))
            (Thread/sleep 100)
            (recur)))))))

(defn exercise-language-workers!
  "Real native workers and the same GGUF engine, not a mock. Two actor slots
  (driver0/team8) are exercised concurrently. This does not claim good plans
  or install a race command. All workers are joined before releasing weights."
  [model-key]
  (worker/stop!)
  (with-qa-model model-key
    (fn [_]
      (when-not (worker/start!) (throw (ex-info "Could not start native workers" {})))
      (try
        (let [requests {0 (language-worker-request 0 "Your car is stopped behind a crash. Both sides are blocked. Wait for a clear route.")
                        8 (language-worker-request 8 "The driver reports that both sides are blocked by a crash. Tell the driver to wait.")}
              start (System/nanoTime)
              submitted (mapv (fn [[actor request]] [actor (worker/submit-language! request)]) requests)]
          (when-not (every? second submitted)
            (throw (ex-info "Language submissions failed" {:submitted submitted})))
          (is (false? (worker/submit-language! (requests 0)))
              "An in-flight or unread reply cannot be overwritten")
          (loop [remaining #{0 8} replies {}]
            (let [received (into {}
                            (keep (fn [actor]
                                    (let [reply (az/value (worker/take-language-result! actor))]
                                      (when (:valid reply) [actor reply])))) remaining)
                  replies (merge replies received)
                  remaining (reduce disj remaining (keys received))]
              (if (empty? remaining)
                (let [decode (fn [bytes length]
                               (String. (byte-array (map unchecked-byte (take length bytes))) StandardCharsets/UTF_8))
                      summaries
                      (mapv (fn [[actor {:keys [request generation] :as reply}]]
                              (is (= actor (:actor request)))
                              (is (= 91 (:epoch request)))
                              (is (pos? (:input_tokens generation)))
                              (is (pos? (:inference_us reply)))
                              (is (<= (:inference_us reply) (:total_us reply)))
                              (is (false? (:valid (az/value (worker/take-language-result! actor))))
                                  "A reply is delivered exactly once")
                              {:actor actor
                               :prompt (decode (:prompt_bytes request) (:prompt_byte_count request))
                               :reply (decode (:bytes generation) (:byte_count generation))
                               :generation-valid? (:valid generation) :stop (:stop generation)
                               :input-tokens (:input_tokens generation) :output-tokens (:output_tokens generation)
                               :queue-ms (/ (:queue_us reply) 1000.0)
                               :inference-ms (/ (:inference_us reply) 1000.0)})
                            (sort-by key replies))]
                  (prn :actual-language-worker-replies summaries)
                  summaries)
                (do
                  (when (> (- (System/nanoTime) start) 600000000000)
                    (throw (ex-info "Language worker QA exceeded its 10-minute observation window"
                                    {:pending remaining})))
                  (Thread/sleep 25)
                  (recur remaining replies))))))
        (finally (worker/stop!))))))

(defn reference-weight-block
  "Independent GGML block decoder used only for numeric QA, not inference.
  Q6 follows the reference's four planes per 128-value half, unlike the
  native random-access index formula. Reads the actual mapped model bytes."
  [^MemorySegment memory type block-index]
  ;; Layout reference: https://github.com/ggml-org/ggml/blob/master/src/ggml-quants.c
  ;; dequantize_row_q4_0 / dequantize_row_q6_K. No external inference is invoked.
  (let [stride ({2 18, 14 210} type)
        offset (* stride block-index)
        byte-at #(bit-and 255 (.getAtIndex memory ValueLayout/JAVA_BYTE (+ offset %)))
        half-at #(double (Float/float16ToFloat
                          (unchecked-short (+ (byte-at %) (bit-shift-left (byte-at (inc %)) 8)))))]
    (case type
      2 (let [d (half-at 0)]
          (mapv (fn [index]
                  (* d (- (if (< index 16)
                            (bit-and 15 (byte-at (+ 2 index)))
                            (bit-shift-right (byte-at (+ 2 (- index 16))) 4)) 8)))
                (range 32)))
      14 (let [d (half-at 208)
               output (double-array 256)]
           (doseq [half (range 2) lane (range 32) plane (range 4)]
             (let [lo (byte-at (+ (* half 64) (* (mod plane 2) 32) lane))
                   hi (byte-at (+ 128 (* half 32) lane))
                   q (- (bit-or (bit-and 15 (bit-shift-right lo (if (< plane 2) 0 4)))
                                (bit-shift-left (bit-and 3 (bit-shift-right hi (* 2 plane))) 4)) 32)
                   scale (unchecked-byte (byte-at (+ 192 (* half 8) (quot lane 16) (* plane 2))))]
               (aset-double output (+ (* half 128) lane (* plane 32)) (* d scale q))))
           (vec output)))))

(defn audit-model-tensor-routing!
  "Compare every fast arithmetic layer index with the actual GGUF names.
  This checks dispatch/layout, not the model's decision quality. No inference
  runtime other than Aguafria is loaded."
  [model-key]
  (with-qa-model model-key
    (fn [^Arena arena]
      (let [find-index #(inference/find-tensor (.allocateFrom arena ^String %))
            layers (az/value inference/model-layer-count)
            mamba ["attn_norm.weight" "ffn_down.weight" "ffn_gate.weight"
                   "ffn_norm.weight" "ffn_up.weight" "ssm_a"
                   "ssm_conv1d.bias" "ssm_conv1d.weight" "ssm_d"
                   "ssm_dt.bias" "ssm_in.weight" "ssm_norm.weight" "ssm_out.weight"]
            attention ["attn_k.weight" "attn_norm.weight" "attn_output.weight"
                       "attn_q.weight" "attn_v.weight" "ffn_down.weight"
                       "ffn_gate.weight" "ffn_norm.weight" "ffn_up.weight"]]
        (is (= 0 (find-index "output_norm.weight")))
        (is (= 1 (find-index "token_embd.weight")))
        (doseq [layer (range layers)
                [offset suffix] (map-indexed vector
                                  (if (inference/attention-layer? layer) attention mamba))]
          (let [name (str "blk." layer "." suffix)]
            (is (= (+ (inference/layer-base-index layer) offset) (find-index name)) name)))
        (prn :tensor-routing-audited {:model model-key :layers layers})))))

(defn audit-sequence-reset!
  "QA-owned model memory: probe each actor/region boundary and middle, proving
  targeted resets do not erase neighboring actors. No production world owns it."
  [model-key]
  (with-qa-model model-key
    (fn [_]
      (let [v #(az/value %)
            total (v inference/sequence-total-floats)
            mamba-all (v inference/sequence-mamba-floats)
            conv-all (v inference/sequence-conv-floats)
            kv-all (v inference/sequence-kv-floats)
            memory (az/pointer-segment (v inference/sequence-memory) (* 4 total))
            regions [[0 (quot mamba-all 12)]
                     [mamba-all (quot conv-all 12)]
                     [(+ mamba-all conv-all) (quot kv-all 12)]
                     [(+ mamba-all conv-all kv-all) (quot kv-all 12)]]
            markers (vec (for [actor (range 12) [base n] regions offset [0 (quot n 2) (dec n)]]
                           {:actor actor :offset (+ base (* actor n) offset)}))
            cleared (atom #{})]
        (is (= total (+ mamba-all conv-all (* 2 kv-all))))
        (doseq [{:keys [actor offset]} markers]
          (.setAtIndex ^MemorySegment memory ValueLayout/JAVA_FLOAT offset (float (inc actor))))
        (doseq [actor [0 6 11]]
          (let [start (System/nanoTime)]
            (is (inference/reset-sequence! actor))
            (prn :actor-state-reset {:model model-key :actor actor :elapsed-ms (/ (- (System/nanoTime) start) 1e6)}))
          (swap! cleared conj actor)
          (doseq [{:keys [actor offset]} markers]
            (is (= (float (if (@cleared actor) 0 (inc actor)))
                   (.getAtIndex ^MemorySegment memory ValueLayout/JAVA_FLOAT offset))
                (str "State boundary actor=" actor " float-offset=" offset))))
        (let [start (System/nanoTime)]
          (is (inference/reset-all-sequences!))
          (prn :all-actor-state-reset {:model model-key :elapsed-ms (/ (- (System/nanoTime) start) 1e6)}))
        (doseq [{:keys [offset]} markers]
          (is (zero? (.getAtIndex ^MemorySegment memory ValueLayout/JAVA_FLOAT offset))))))))

(defn audit-attention-recurrence!
  "Check three causal GQA steps and SwiGLU residuals with real weights.
  JVM attention/FFN orchestration is independent; matrix products remain the
  separately tested native primitive. This is not external model inference."
  [model-key]
  (with-qa-model model-key
    (fn [^Arena arena]
      (let [value #(az/value %)
            hidden-size (value inference/model-hidden-size)
            ffn-size (value inference/model-ffn-size)
            heads (value inference/model-attention-head-count)
            kv-heads (value inference/model-attention-kv-head-count)
            head-size (quot hidden-size heads)
            kv-size (* kv-heads head-size)
            layer (if (= hidden-size 768) 10 5)
            scale (value inference/model-attention-scale)
            residual (value inference/model-residual-multiplier)
            epsilon (value inference/model-rms-epsilon)
            buffer (fn [n] (doto (.allocate arena (* 4 n) 4) (.fill (byte 0))))
            read! (fn [^MemorySegment b n]
                    (mapv #(.getAtIndex b ValueLayout/JAVA_FLOAT %) (range n)))
            write! (fn [^MemorySegment b xs]
                     (doseq [[i x] (map-indexed vector xs)]
                       (.setAtIndex b ValueLayout/JAVA_FLOAT i (float x))) b)
            index #(inference/find-tensor (.allocateFrom arena (str "blk." layer "." %)))
            norm (fn [xs suffix]
                   (let [tensor (value (inference/tensor-info (index suffix)))
                         _ (assert (= 0 (:ggml_type tensor)))
                         weights (read! (.reinterpret (MemorySegment/ofAddress (:data_address tensor))
                                                     (* hidden-size 4)) hidden-size)
                         inverse (/ 1.0 (Math/sqrt (+ epsilon (/ (reduce + (map #(* % %) xs)) hidden-size))))]
                     (mapv #(* inverse %1 %2) xs weights)))
            matvec (fn [suffix xs n]
                     (let [input (write! (buffer (count xs)) xs) output (buffer n)]
                       (is (inference/tensor-matvec! output n (index suffix) input))
                       (read! output n)))
            hidden (buffer hidden-size) normalized (buffer hidden-size)
            query (buffer hidden-size) key (buffer kv-size) val (buffer kv-size)
            keys (buffer (* 3 kv-size)) values (buffer (* 3 kv-size))
            scores (buffer 3) attention (buffer hidden-size) branch (buffer hidden-size)
            gate (buffer ffn-size) up (buffer ffn-size)
            activated (buffer ffn-size) output (buffer hidden-size)
            history (atom [])]
        (dotimes [step 3]
          (let [input (mapv #(float (* 0.4 (Math/cos (+ (* % 0.13) (* step 0.7))))) (range hidden-size))
                normalized-input (norm input "attn_norm.weight")
                q (matvec "attn_q.weight" normalized-input hidden-size)
                k (matvec "attn_k.weight" normalized-input kv-size)
                v (matvec "attn_v.weight" normalized-input kv-size)
                entries (swap! history conj {:key k :value v})
                mixed (vec
                        (mapcat
                          (fn [head]
                            (let [q-start (* head head-size)
                                  kv-start (* (quot head (quot heads kv-heads)) head-size)
                                  logits (mapv (fn [entry]
                                                 (* scale (reduce +
                                                            (map #(* (q (+ q-start %))
                                                                     ((:key entry) (+ kv-start %)))
                                                                 (range head-size))))) entries)
                                  maximum (reduce max logits)
                                  exps (mapv #(Math/exp (- % maximum)) logits)
                                  total (reduce + exps)
                                  probabilities (mapv #(/ % total) exps)]
                              (mapv (fn [component]
                                      (reduce + (map #(* %1 ((:value %2) (+ kv-start component)))
                                                     probabilities entries))) (range head-size))))
                          (range heads)))
                attention-result (mapv #(+ %1 (* residual %2)) input
                                       (matvec "attn_output.weight" mixed hidden-size))
                ffn-input (norm attention-result "ffn_norm.weight")
                g (matvec "ffn_gate.weight" ffn-input ffn-size)
                u (matvec "ffn_up.weight" ffn-input ffn-size)
                a (mapv #(* (/ %1 (+ 1.0 (Math/exp (- (double %1))))) %2) g u)
                expected (mapv #(+ %1 (* residual %2)) attention-result
                               (matvec "ffn_down.weight" a hidden-size))]
            (write! hidden input)
            (is (inference/attention-ffn-layer! layer step hidden keys values normalized
                                               query key val scores attention branch gate up activated output))
            (let [actual (read! hidden hidden-size)
                  error (reduce max (map #(abs (- %1 %2)) expected actual))
                  relative (/ error (max 1.0 (reduce max (map abs expected))))]
              (is (< relative 0.0002) (str model-key " attention/FFN step " step " error " relative))
              (prn :attention-ffn-reference-step {:model model-key :step step :relative-error relative})
              (flush))))))))

(defn audit-mamba-recurrence!
  "Three real-weight Mamba layer steps against independently ordered JVM
  convolution, selective recurrence, gating, RMS and residual arithmetic.
  Uses already-tested native matrix products as primitives, NOT an external
  inference engine. This is block orchestration QA, not full-logit parity."
  [model-key]
  (with-qa-model model-key
    (fn [^Arena arena]
      (let [profile (az/value (inference/model-profile-summary))
            hidden-size (:hidden_size profile) inner (:mamba_inner_size profile)
            projection-size (:mamba_projection_size profile) conv-size (:mamba_conv_size profile)
            heads (:mamba_head_count profile) head-size (:mamba_head_size profile)
            state-size 128
            float-buffer (fn [n] (doto (.allocate arena (* 4 n) 4) (.fill (byte 0))))
            write-floats! (fn [^MemorySegment buffer values]
                            (doseq [[i x] (map-indexed vector values)]
                              (.setAtIndex buffer ValueLayout/JAVA_FLOAT i (float x))) buffer)
            read-floats (fn [^MemorySegment buffer n]
                          (mapv #(.getAtIndex buffer ValueLayout/JAVA_FLOAT %) (range n)))
            index #(inference/find-tensor (.allocateFrom arena ^String (str "blk.0." %)))
            weights (fn [suffix]
                      (let [tensor (az/value (inference/tensor-info (index suffix)))
                            n (reduce * (take (:dimension_count tensor) (:dimensions tensor)))]
                        (when-not (= 0 (:ggml_type tensor))
                          (throw (ex-info "Reference requires F32 scalar weights" {:suffix suffix})))
                        (read-floats (.reinterpret (MemorySegment/ofAddress (:data_address tensor)) (* n 4)) n)))
            rms (fn [values scales]
                  (let [inverse (/ 1.0 (Math/sqrt (+ 1e-5 (/ (reduce + (map #(* % %) values)) (count values)))))]
                    (mapv #(* %1 %2 inverse) values scales)))
            silu (fn [x] (/ (double x) (+ 1.0 (Math/exp (- (double x))))))
            input (float-buffer hidden-size) normalized (float-buffer hidden-size)
            projected (float-buffer projection-size) convolved (float-buffer conv-size)
            scanned (float-buffer inner) gated (float-buffer inner) branch (float-buffer hidden-size)
            state (float-buffer (* inner state-size)) convolution (float-buffer (* conv-size 3))
            reference-normal (float-buffer hidden-size) reference-projected (float-buffer projection-size)
            reference-gated (float-buffer inner) reference-branch (float-buffer hidden-size)
            norm (weights "attn_norm.weight") gate-norm (weights "ssm_norm.weight")
            a (weights "ssm_a") d (weights "ssm_d") bias (weights "ssm_dt.bias")
            conv-bias (weights "ssm_conv1d.bias")
            conv-tensor (az/value (inference/tensor-info (index "ssm_conv1d.weight")))
            _ (when-not (= 0 (:ggml_type conv-tensor))
                (throw (ex-info "Reference requires F32 convolution weights" {})))
            conv-weights (read-floats (.reinterpret (MemorySegment/ofAddress (:data_address conv-tensor)) (* conv-size 4 4)) (* conv-size 4))
            reference-conv (double-array (* conv-size 3))
            reference-state (double-array (* inner state-size))
            residual (double (az/value inference/model-residual-multiplier))]
        (is (= inner (* heads head-size)))
        ;; Old fixed-buffer diagnostics must reject larger profiles without
        ;; writing past their 350M scratch storage. Invalid tokens are also
        ;; rejected before touching embedding memory.
        (doseq [token (cond-> [(az/value inference/model-vocabulary-size)]
                       (> hidden-size 768) (conj 0))
                probe [#(inference/attention-layer-probe (if (= 768 hidden-size) 10 5) % 0)
                       #(inference/mamba-layer-zero-probe % 0)
                       #(inference/mamba-layer-zero-full-probe % 0)
                       inference/layer-zero-mlp-probe]]
          (is (Double/isNaN (double (probe token)))
              "Unsupported fixed-buffer probes must explicitly return NaN"))
        (dotimes [step 3]
          (let [hidden (mapv #(float (* 0.3 (Math/sin (+ (* % 0.17) (* step 0.43))))) (range hidden-size))]
            (write-floats! input hidden)
            (write-floats! reference-normal (rms hidden norm))
            (is (inference/tensor-matvec! reference-projected projection-size (index "ssm_in.weight") reference-normal))
            (let [projection (read-floats reference-projected projection-size)
                  conv (mapv (fn [channel]
                               (let [offset (* channel 3) w (* channel 4)
                                     current (projection (+ inner channel))
                                     value (+ (conv-bias channel) (* current (conv-weights (+ w 3)))
                                              (reduce + (map #(* (aget ^doubles reference-conv (+ offset %))
                                                                 (conv-weights (+ w %))) (range 3))))]
                                 (aset-double reference-conv offset (aget ^doubles reference-conv (inc offset)))
                                 (aset-double reference-conv (inc offset) (aget ^doubles reference-conv (+ offset 2)))
                                 (aset-double reference-conv (+ offset 2) (double current))
                                 (silu value))) (range conv-size))
                  scan (mapv (fn [component]
                               (let [head (quot component head-size)
                                     dt (+ (projection (+ inner conv-size head)) (bias head))
                                     delta (if (> dt 20.0) dt (Math/log1p (Math/exp dt)))
                                     decay (Math/exp (* delta (a head)))
                                     base (* component state-size)
                                     x (conv component)]
                                 (+ (* x (d head))
                                    (reduce +
                                      (map (fn [s]
                                             (let [slot (+ base s)
                                                   next (+ (* decay (aget ^doubles reference-state slot))
                                                           (* delta x (conv (+ inner s))))]
                                               (aset-double reference-state slot next)
                                               (* next (conv (+ inner state-size s)))))
                                           (range state-size)))))) (range inner))
                  gated-values (rms (mapv #(* %1 (silu %2)) scan (subvec projection 0 inner)) gate-norm)]
              (write-floats! reference-gated gated-values)
              (is (inference/tensor-matvec! reference-branch hidden-size (index "ssm_out.weight") reference-gated))
              (is (inference/mamba-layer-step! 0 input state convolution normalized projected convolved scanned gated branch))
              (let [expected (mapv #(+ %1 (* residual %2)) hidden (read-floats reference-branch hidden-size))
                    actual (read-floats input hidden-size)
                    max-error (reduce max (map #(abs (- %1 %2)) expected actual))
                    relative (/ max-error (max 1.0 (reduce max (map abs expected))))]
                (is (< relative 0.0002) (str model-key " Mamba step " step " relative error " relative))
                (prn :mamba-reference-step {:model model-key :step step :max-error max-error :relative-error relative})
                (flush)))))))))

(deftest mapped-quantized-weights-and-products-match-independent-layout-test
  (with-qa-model
    (fn [^Arena arena]
      (doseq [[name type] [["token_embd.weight" 14] ["blk.0.ffn_down.weight" 2]]]
        (let [index (inference/find-tensor (.allocateFrom arena name))
              tensor (az/value (inference/tensor-info index))
              [width rows] (:dimensions tensor)
              block-size ({2 32, 14 256} type)
              stride ({2 18, 14 210} type)
              bytes (* width rows (/ stride block-size))
              memory (.reinterpret (MemorySegment/ofAddress (:data_address tensor)) (long bytes))
              input (.allocate arena (* width 4) 4)
              values (mapv #(float (/ (- (mod (* % 17) 41) 20) 21.0)) (range width))]
          (is (= type (:ggml_type tensor)))
          (doseq [[i value] (map-indexed vector values)]
            (.setAtIndex input ValueLayout/JAVA_FLOAT i value))
          (doseq [row [0 1 31 (dec rows)]]
            (let [weights (vec (mapcat #(reference-weight-block memory type %)
                                      (range (* row (/ width block-size))
                                             (* (inc row) (/ width block-size)))))
                  products (map * weights values)
                  expected (reduce + products)
                  actual (inference/tensor-row-dot index row input)
                  tolerance (+ 1e-6 (* 2e-6 (reduce + (map abs products))))]
              (doseq [component (range 0 width 7)]
                (let [expected (weights component)
                      actual (#'inference/tensor-element index (+ (* row width) component))]
                  (is (<= (abs (- expected actual)) (+ 1e-8 (* 1e-6 (abs expected))))
                      (str name " row " row " component " component))))
              (is (<= (abs (- expected actual)) tolerance)
                  (str name " row " row " dot-product " expected " vs " actual)))))))))

(defn benchmark-vocabulary-product!
  "Time one real full-vocabulary projection, excluding model load and warmup.
  Reports all samples; no generated text or external inference engine involved."
  [model-key]
  (with-qa-model model-key
    (fn [^Arena arena]
      (let [index (inference/find-tensor (.allocateFrom arena "token_embd.weight"))
            [width rows] (:dimensions (az/value (inference/tensor-info index)))
            input (.allocate arena (* width 4) 4)
            output (.allocate arena (* rows 4) 4)]
        (dotimes [i width]
          (.setAtIndex input ValueLayout/JAVA_FLOAT i (float (/ (- (mod i 17) 8) 9.0))))
        (inference/tensor-matvec! output rows index input)
        (mapv (fn [_]
                (let [start (System/nanoTime)
                      valid (inference/tensor-matvec! output rows index input)]
                  {:valid valid :elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                   :first (.getAtIndex output ValueLayout/JAVA_FLOAT 0)}))
              (range 3))))))

(defn reference-tokenizer
  "Read the verified GGUF's strings with JVM file IO, then use an independent
  string-map BPE implementation. This is a tokenizer test, not another model
  inference engine. Native hash-table lookup code is not reused."
  [^Arena arena]
  (let [strings (fn [key]
                  (let [index (inference/find-metadata (.allocateFrom arena key))
                        info (az/value (inference/metadata-info index))]
                    (with-open [file (RandomAccessFile. (model/model-file) "r")]
                      (.seek file (:value_start info))
                      (mapv (fn [_]
                              (let [length (Long/reverseBytes (.readLong file))
                                    bytes (byte-array length)]
                                (.readFully file bytes)
                                (String. bytes StandardCharsets/UTF_8)))
                            (range (:element_count info))))))
        vocabulary (zipmap (strings "tokenizer.ggml.tokens") (range))
        ranks (zipmap (map #(str/split % #" " 2) (strings "tokenizer.ggml.merges")) (range))
        visible (concat (range 33 127) (range 161 173) (range 174 256))
        escaped (remove (set visible) (range 256))
        byte-characters (into (zipmap visible (map char visible))
                              (map vector escaped (map char (range 256 324))))
        encode-piece
        (fn [piece]
          (loop [parts (mapv #(str (byte-characters (int %))) piece)]
            (if-let [[_ index] (first (sort (keep-indexed
                                            (fn [i pair] (when-let [rank (ranks (vec pair))] [rank i]))
                                            (partition 2 1 parts))))]
              (recur (into (conj (subvec parts 0 index) (str (parts index) (parts (inc index))))
                           (subvec parts (+ index 2))))
              (mapv vocabulary parts))))]
    (fn [prompt]
      (vec (mapcat encode-piece (re-seq granite-piece-pattern prompt))))))

(deftest native-token-ids-match-independent-bpe-test
  (with-qa-model
    (fn [^Arena arena]
      (let [encode (reference-tokenizer arena)
            prompts ["What is 2 + 2? Reply with only the number."
                     "Left: blocked. Right: clear. What will you do?"
                     "Right: blocked. Left: clear. What will you do?"
                     "We're stopped. Don't accelerate!"
                     "  Hold 12345 km/h.\n"
                     "No overtaking. Red flag. Pit box occupied."
                     "aaa aaaa aaaaa banana banana"
                     " \n \r\n   Stop"
                     "<|start_of_role|>system is literal user text."]]
        (doseq [prompt prompts]
          (let [actual (az/value (inference/tokenize-language-ascii
                                 (.allocateFrom arena prompt) (count prompt)))]
            (is (:valid actual))
            (is (= (encode prompt) (vec (take (:token_count actual) (:tokens actual)))) prompt)))))))

(defn parse-plan-text [^Arena arena text complete?]
  (az/value (protocol/parse-driving-plan (.allocateFrom arena text)
                                        (alength (.getBytes ^String text StandardCharsets/UTF_8))
                                        complete?)))

(deftest readable-plan-parser-preserves-radio-and-rejects-ambiguity-test
  (with-open [arena (Arena/ofConfined)]
    (doseq [[word kind] [["hold" protocol/plan-hold] ["follow" protocol/plan-follow]
                         ["pass left" protocol/plan-pass-left] ["pass right" protocol/plan-pass-right]
                         ["pit" protocol/plan-pit] ["yield" protocol/plan-yield]]]
      (let [radio "Bien reçu ! Je garde ma position."
            text (str "  Plan: " word ".\r\nRadio: " radio "\n")
            parsed (parse-plan-text arena text true)
            bytes (.getBytes text StandardCharsets/UTF_8)]
        (is (:valid parsed))
        (is (= kind (:kind parsed)))
        (is (= radio (String. bytes (int (:radio_start parsed)) (int (:radio_length parsed))
                              StandardCharsets/UTF_8)))))
    (doseq [[word kind] [["hold" protocol/plan-hold] ["follow" protocol/plan-follow]
                        ["pass left" protocol/plan-pass-left] ["pass right" protocol/plan-pass-right]
                        ["pit" protocol/plan-pit] ["yield" protocol/plan-yield]]
            text [word (str "Plan: " word)]]
      (let [parsed (parse-plan-text arena text true)]
        (is (:valid parsed) text)
        (is (= kind (:kind parsed)))
        (is (zero? (:radio_length parsed)) "Do not fabricate a radio message")))
    (doseq [text ["hold or follow" "do not hold" "Plan: follow\nMaybe hold instead."
                 "Radio: wait\nPlan: hold"
                 "Plan: hold or pass right\nRadio: unsure"
                 "Plan: do not hold\nRadio: go"
                 "Plan: pass left then right\nRadio: go"
                 "Plan: hold\nRadio:   " "Plan: go through it\nRadio: go"
                 "Plan: hold\nPlan: follow\nRadio: go"]]
      (is (not (:valid (parse-plan-text arena text true))) text))
    (is (not (:valid (parse-plan-text arena "Plan: hold\nRadio: wait" false))))
    (is (not (:valid (parse-plan-text arena (str "Plan: hold\nRadio: " (apply str (repeat 2048 "a"))) true))))
    (is (= protocol/plan-pass-right (:kind (parse-plan-text arena "PLAN: PASS RIGHT\nRADIO: Moving right." true))))))

(def clear-plan-context
  {:epoch 7 :tick 100 :latest_revision 10 :active true :left_clear true :right_clear true
   :red_flag false :overtaking_allowed true :pit_available true})

(deftest plans-are-validated-against-current-not-observed-clearance-test
  (with-open [arena (Arena/ofConfined)]
    (doseq [[word changes epoch revision observed expires reason]
            [["hold" {} 7 11 80 200 protocol/plan-ok]
             ["follow" {} 7 11 80 200 protocol/plan-ok]
             ["pass left" {:left_clear false} 7 11 80 200 protocol/plan-left-blocked]
             ["pass right" {:right_clear false} 7 11 80 200 protocol/plan-right-blocked]
             ["pass right" {:left_clear false} 7 11 80 200 protocol/plan-ok]
             ["pass left" {:right_clear false} 7 11 80 200 protocol/plan-ok]
             ["hold" {} 6 11 80 200 protocol/plan-wrong-epoch]
             ["hold" {} 7 10 80 200 protocol/plan-old-revision]
             ["hold" {} 7 9 80 200 protocol/plan-old-revision]
             ["hold" {} 7 11 101 200 protocol/plan-expired]
             ["hold" {} 7 11 80 100 protocol/plan-expired]
             ["hold" {} 7 11 80 79 protocol/plan-expired]
             ["hold" {:active false} 7 11 80 200 protocol/plan-inactive-driver]
             ["follow" {:red_flag true} 7 11 80 200 protocol/plan-red-flag]
             ["hold" {:red_flag true} 7 11 80 200 protocol/plan-ok]
             ["pass left" {:overtaking_allowed false} 7 11 80 200 protocol/plan-no-overtaking]
             ["pit" {:pit_available false} 7 11 80 200 protocol/plan-pit-unavailable]]]
      (let [plan (protocol/DrivingPlan (parse-plan-text arena (str "Plan: " word "\nRadio: actual model words") true))]
        (is (= reason (protocol/validate-driving-plan
                       plan epoch revision observed expires
                       (protocol/DrivingPlanContext (merge clear-plan-context changes))))
            (str word " " changes))))
    (is (= protocol/plan-malformed
           (protocol/validate-driving-plan
            (protocol/DrivingPlan {:valid false :kind 0 :rejection 0 :radio_start 0 :radio_length 0})
            7 11 80 200 (protocol/DrivingPlanContext clear-plan-context))))))

(def driving-language-cases
  "Untrained, plain-language evaluation cases. Rubrics are for review, never
  sent to the model. Coherent text or successful decoding is not a pass."
  [{:id :blocked-road
    :prompt "You are driving in a racing game. A stopped car blocks the road. Both sides are blocked. Say what you will do in one sentence."
    :rubric "Brake/hold and wait for clearance; do not accelerate into the car."}
   {:id :clear-left
    :prompt "Your car is stopped behind a crash. The left lane is clear. Tell your team what you plan to do in one sentence."
    :rubric "Cautiously pass on the clear left, checking traffic; do not drive through the obstacle."}
   {:id :clear-right
    :prompt "Your car is stopped behind a crash. The right lane is clear but the left is blocked. Tell your team your plan in one sentence."
    :rubric "Use the clear right, checking traffic; do not copy the left-side answer."}
   {:id :clear-straight
    :prompt "You are racing on a clear straight. Tires and car are healthy. No flags or speed limits apply. Tell your team your plan in one sentence."
    :rubric "Accelerate/race normally; do not brake for a nonexistent obstruction."}
   {:id :red-flag
    :prompt "Race control shows a red flag. The track ahead looks clear and your rival is ahead. Tell your team your plan in one sentence."
    :rubric "Slow and follow race-control instructions; do not race or overtake."}
   {:id :tire-service
    :prompt "You are a race engineer. Your driver's tires are badly worn. The pit lane is open and the crew is ready. Send a short message to the driver."
    :rubric "Call the driver to pit safely for tires; do not claim service has already occurred."}
   {:id :pit-occupied
    :prompt "You are a race engineer. Both drivers want tires, but one is already in your only pit box. Send a short message to the second driver."
    :rubric "Coordinate waiting/staggering entry; do not order both cars into the occupied box."}
   {:id :stale-clearance
    :prompt "Your team said pass left five seconds ago. Now another car blocks the left lane. A stopped car is ahead. What will you do?"
    :rubric "Reject stale clearance and brake/hold; do not blindly follow the old instruction."}])

(def racing-system-message
  "You control a simulated racing car or its team. Give a concise, practical driving plan in plain words. Stay in character. Never drive through another car.")

(deftest driving-cases-fit-current-native-text-contract-test
  (is (<= (count racing-system-message) 160))
  (is (= (count driving-language-cases) (count (set (map :id driving-language-cases)))))
  (doseq [{:keys [id prompt rubric]} driving-language-cases]
    (is (<= (count prompt) 160) (str id " must not silently truncate"))
    (is (every? #(< (int %) 128) prompt) (str id " currently requires ASCII"))
    (is (seq rubric))))

(defn evaluate-driving-language!
  "Run identical untrained cases through the game's own native engine.
  Prints each actual reply promptly and returns evidence for manual review.
  Do not use while game workers own the model memory. No command is installed."
  ([model-key] (evaluate-driving-language! model-key driving-language-cases))
  ([model-key cases]
   (evaluate-driving-language! model-key cases nil))
  ([model-key cases system]
   (with-qa-model model-key
     (fn [arena]
       (mapv (fn [{:keys [id prompt rubric]}]
               (let [result (assoc (sample-loaded-language! arena system prompt 48)
                                   :case id :model model-key :rubric rubric
                                   :review :pending)]
                 (prn :driving-language-case result)
                 (flush)
                 result))
             cases)))))
