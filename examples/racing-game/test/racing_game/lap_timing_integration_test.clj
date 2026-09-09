(ns racing-game.lap-timing-integration-test
  "Owns a race world. Never load/run this fixture in the live game JVM."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [racing-game.simulation :as sim]
            [racing-game.worker :as worker]
            [clojure.test :refer [deftest is]]))

(az/defn clock-wiring-probe
  "Explicit clock/crossing fixture, not a claim that a physical lap was driven."
  :- [:array 6 :u64] []
  (sim/configure-countdown! 0)
  (sim/reset!)
  (ak/defer (sim/shutdown!))
  (sim/update-lap-timing!)
  (set! sim/simulation-tick 10800)
  (sim/complete-lap! (sim/racer-pointer 0))
  (sim/update-lap-timing!)
  (let [first-lap (sim/lap-timing-view 0)]
    (sim/reset!)
    (let [fresh (sim/lap-timing-view 0)]
      ;; A normal paused frame must leave the timer unstarted and unchanged.
      (set! sim/paused true)
      (sim/step!)
      (let [paused (sim/lap-timing-view 0)]
        (az/array-init [:array 6 :u64]
          [(az/field first-lap last_ticks) (az/field first-lap samples)
           (az/field fresh samples) (az/field fresh observed_tick)
           (if (az/field fresh started) (ak/as :u64 1) 0)
           (if (az/field paused started) (ak/as :u64 1) 0)])))))

(deftest sparse-timer-crossings-reset-and-paused-frame-test
  (worker/stop!)
  (is (= [10800 1 0 0 0 0] (az/value (clock-wiring-probe)))))
