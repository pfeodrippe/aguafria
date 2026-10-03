(ns racing-game.hot-reload-test
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [racing-game.core]
            [racing-game.simulation :as simulation])
  (:import [java.io PushbackReader]
           [java.lang ProcessHandle]))

(defn- step-form []
  (with-open [reader (PushbackReader.
                      (io/reader (io/resource "racing_game/simulation.clj")))]
    (loop []
      (let [form (read {:eof nil} reader)]
        (cond
          (nil? form) (throw (ex-info "Simulation step was not found" {}))
          (and (seq? form) (= 'step! (second form))) form
          :else (recur))))))

(defn- publish! [form]
  (binding [*ns* (the-ns 'racing-game.simulation)]
    (eval form))
  (az/await! 'racing-game.simulation))

(deftest native-simulation-retains-world-through-live-edits
  (when (az/value simulation/initialized)
    (throw (ex-info "Run this check in its own nREPL, not a running race" {})))
  (let [original (step-form)
        increment '(ak/= simulation-tick (+ simulation-tick 1))
        pid (.pid (ProcessHandle/current))]
    (try
      (let [initial (az/value (simulation/snapshot))
            world (:world_address initial)
            tick (:tick initial)]
        (is (true? (:initialized initial)))
        (is (= 20 (:racers initial)))
        (is (pos? world))
        (simulation/step-many! 1)
        (is (= (inc tick) (:tick (az/value (simulation/snapshot)))))
        (doseq [[amount expected] [[2 (+ tick 3)] [3 (+ tick 6)]]]
          (publish! (walk/postwalk-replace
                     {increment (list 'ak/= 'simulation-tick
                                      (list '+ 'simulation-tick amount))}
                     original))
          (simulation/step-many! 1)
          (let [state (az/value (simulation/snapshot))]
            (is (= expected (:tick state)))
            (is (= world (:world_address state)))
            (is (= 20 (:racers state)))
            (is (= pid (.pid (ProcessHandle/current))))))
        (publish! original)
        (simulation/step-many! 1)
        (let [state (az/value (simulation/snapshot))]
          (is (= (+ tick 7) (:tick state)))
          (is (= world (:world_address state)))))
      (finally
        (try (publish! original)
             (finally (simulation/shutdown!)))))
    (is (false? (az/value simulation/initialized)))))
