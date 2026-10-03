(ns racing-game.lap-timing-integration-test
  "Owns a race world. Never load/run this fixture in the live game JVM."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [racing-game.simulation :as sim]
            [racing-game.worker :as worker]
            [clojure.test :refer [deftest is]]))

(a/defn clock-wiring-probe [:array 6 :u64]
  "Explicit clock/crossing fixture, not a claim that a physical lap was driven." []
  (sim/configure-countdown! 0)
  (sim/reset!)
  (ak/defer (sim/shutdown!))
  (sim/update-lap-timing!)
  (ak/= sim/simulation-tick 10800)
  (sim/complete-lap! (sim/racer-pointer 0))
  (sim/update-lap-timing!)
  (let [first-lap (sim/lap-timing-view 0)]
    (sim/reset!)
    (let [fresh (sim/lap-timing-view 0)]
      ;; A normal paused frame must leave the timer unstarted and unchanged.
      (ak/= sim/paused true)
      (sim/step!)
      (let [paused (sim/lap-timing-view 0)]
        (a/init [(a/field first-lap last_ticks) (a/field first-lap samples)
           (a/field fresh samples) (a/field fresh observed_tick)
           (if (a/field fresh started) (ak/as 1 :u64) 0)
           (if (a/field paused started) (ak/as 1 :u64) 0)] [:array 6 :u64])))))

(deftest sparse-timer-crossings-reset-and-paused-frame-test
  (worker/stop!)
  (is (= [10800 1 0 0 0 0] (a/value (clock-wiring-probe)))))
