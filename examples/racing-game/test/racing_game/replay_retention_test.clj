(ns racing-game.replay-retention-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation]
            [racing-game.telemetry :as telemetry]))

(a/defn capture-retained-history! [:array 5 :usize]
  [[entries :usize]]
  (telemetry/reset!)
  (k/for [racer (a/range 0 telemetry/racer-count)]
    (k/for [index (a/range 0 entries)]
      (let [entry (k/var (mem/zeroes telemetry/DecisionLog))]
        (a/merge! entry
                  {:valid true
                   :accepted true
                   :racer_id (k/intCast racer)
                   :rank (k/intCast (k/+ racer 1))
                   :observation_schema protocol/observation-schema-version
                   :action_schema protocol/action-schema-version
                   :revision (k/intCast (k/+ index 1))
                   :install_tick (k/intCast index)
                   :target_speed 0.08})
        (telemetry/record! entry))))
  (let [summary (simulation/capture-retained-replay!)
        count (k/as (:loaded summary) :usize)]
    (if (k/== count 0)
      (a/array [0 0 0 0 0] :usize)
      (let [first-intent (a/get simulation/replay-intents 0)
            last-intent (a/get simulation/replay-intents (k/- count 1))]
        (a/array [count
                  (k/intCast (:install_tick first-intent))
                  (k/intCast (:racer first-intent))
                  (k/intCast (:install_tick last-intent))
                  (k/intCast (:racer last-intent))]
                 :usize)))))

(deftest native-replay-captures-the-complete-retained-ring
  (try
    (doseq [[entries expected] [[0 [0 0 0 0 0]]
                                [1 [20 0 0 0 19]]
                                [64 [1280 0 0 63 19]]
                                [65 [1280 1 0 64 19]]]]
      (with-open [captured (capture-retained-history! entries)]
        (is (= expected (a/value captured)) (str entries " decisions per racer"))))
    (finally
      (simulation/clear-replay!)
      (telemetry/reset!))))

(deftest replay-applies-recorded-decisions-before-the-first-physics-step
  (try
    (with-open [parity (simulation/run-replay-parity! 1)]
      (let [report (a/value parity)]
        (is (pos? (:intent_count report)))
        (is (:valid report))
        (is (= (:original_fingerprint report) (:replay_fingerprint report)))))
    (finally
      (simulation/shutdown!))))
