(ns la-professeure.jvm-preparation-test
  "Safe JVM checks for game adapters: no windows, devices, playback or file writes."
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [clojure.test :refer [deftest is run-tests]]
            [la-professeure.gpu :as gpu]
            [la-professeure.recording-tool :as recording]
            [la-professeure.scene :as scene]
            [la-professeure.tools.mixer :as mixer]
            [la-professeure.tools.recorder :as recorder]
            [la-professeure.tools.studio :as studio]))

(defn- read-and-close [value]
  (try
    (az/value value)
    (finally (az/close! value))))

(defn- text-hash [text]
  (reduce (fn [hash byte]
            (mod (*' (.xor (biginteger hash) (biginteger (bit-and 255 byte)))
                     1099511628211N)
                 18446744073709551616N))
          14695981039346656037N
          (.getBytes ^String text "UTF-8")))

(deftest pure-game-functions
  (doseq [text ["" "hello" "jello" "bonjour ☔"]]
    (is (= (text-hash text) (read-and-close (recording/text-hash text))) text))
  (doseq [clusters [["a" "b"] ["é" "x"] ["👩🏽‍💻" "!"] ["🇧🇷" "🇨🇦" "x"]]]
    (let [text (apply str clusters)
          boundaries (vec (reductions + 0 (map #(alength (.getBytes ^String % "UTF-8")) clusters)))
          length (last boundaries)]
      (doseq [position boundaries]
        (is (= (or (last (filter #(< % position) boundaries)) 0)
               (read-and-close (studio/text-boundary text position true))))
        (is (= (or (first (filter #(> % position) boundaries)) length)
               (read-and-close (studio/text-boundary text position false)))))))
  (doseq [[result expected] [[0 false] [-1000001004 true] [1000001003 true] [-1 false]]]
    (is (= expected (read-and-close (gpu/resize-result? result)))))
  (doseq [[seconds fps expected] [[0.0 4.0 0] [1.5 4.0 6] [2.25 4.0 1]
                                  [-1.0 4.0 0] [4.0 -1.0 0]]]
    (is (= expected (read-and-close (scene/animation-frame seconds fps))))))

(deftest initial-audio-state
  (is (= {:from 0 :to 0 :enabled false} (read-and-close (mixer/loop-state))))
  (is (= 0 (read-and-close (mixer/cursor-frame))))
  (is (false? (read-and-close (mixer/playing?))))
  (is (= 0 (read-and-close (recorder/frames-recorded))))
  (is (= 0 (read-and-close (recorder/level))))
  (is (false? (read-and-close (recorder/done?)))))

(deftest studio-c-member-calls-from-the-jvm
  ;; These are the two C calls used by name-display-text!, with ordinary JVM
  ;; evaluation of its operands. Only the locally allocated scratch is mutated.
  (doseq [[text expected] [["é" "é"] ["Café" "Café"]]]
    (with-open [text-value (k/as text [:slice-const :u8])
                scratch (k/var k/undefined [:array 513 :i32])
                options (k/| (:UTF8PROC_COMPOSE studio/text-api)
                             (:UTF8PROC_STABLE studio/text-api))
                count ((:utf8proc_decompose studio/text-api)
                       (:ptr text-value) (k/intCast (:len text-value))
                       (k/ptrCast (k/& scratch)) 512 options)]
      (is (<= 0 (az/value count) 512))
      (when (<= 0 (az/value count) 512)
        (with-open [length ((:utf8proc_reencode studio/text-api)
                            (k/ptrCast (k/& scratch)) count options)]
          (is (<= 0 (az/value length) (* 513 4)))
          (when (<= 0 (az/value length) (* 513 4))
            (with-open [bytes (k/as (k/ptrCast (k/& scratch)) [:c-pointer :u8])
                        result (az/slice bytes 0 (k/as (k/intCast length) :usize))]
              (is (= expected (az/value result))))))))))

(defn -main [& _]
  (let [result (run-tests 'la-professeure.jvm-preparation-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
