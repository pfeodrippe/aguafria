(ns racing-game.lap-timing-test
  (:require [aguafria.std]
            [aguafria.zig :as az]
            [racing-game.lap-timing :as timing]
            [clojure.test :refer [deftest is]]))

(defn- start [complete?]
  (timing/Entry {:started false :complete_start complete? :terminal false
                 :lap 0 :samples 0 :start_tick 0 :observed_tick 0
                 :last_ticks 0 :best_ticks 0}))

(deftest clock-counts-real-laps-including-delays-test
  (let [grid (timing/observe (start true) 359 0 false false)
        green (timing/observe grid 360 0 true false)
        lap1 (timing/observe green (+ 360 10800) 1 true false)
        lap2 (timing/observe lap1 (+ 360 10800 14400) 2 true false)
        finish (timing/observe lap2 (+ 360 10800 14400 10200) 3 true true)]
    (is (false? (:started (az/value grid))))
    (is (= 90.0 (/ (:last_ticks (az/value lap1)) 120.0)))
    (is (= 120.0 (/ (:last_ticks (az/value lap2)) 120.0)))
    (is (= 85.0 (/ (:best_ticks (az/value finish)) 120.0)))
    (is (= 3 (:samples (az/value finish))))
    (is (= (az/value finish) (az/value (timing/observe finish 999999 3 true true))))))

(deftest live-attachment-does-not-invent-earlier-times-test
  (let [attached (timing/observe (start false) 10000 1 true false)
        crossing (timing/observe attached 11000 2 true false)
        full-lap (timing/observe crossing 21800 3 true false)]
    (is (zero? (:samples (az/value crossing))))
    (is (zero? (:last_ticks (az/value crossing))))
    (is (= 1 (:samples (az/value full-lap))))
    (is (= 10800 (:last_ticks (az/value full-lap))))))

(deftest pause-dnf-and-discontinuous-laps-test
  (let [green (timing/observe (start true) 0 0 true false)
        moving (timing/observe green 100 0 true false)
        paused (timing/observe moving 100 0 false false)
        retired (timing/observe paused 120 0 true true)
        jumped (timing/observe green 10800 2 true false)]
    (is (= (az/value moving) (az/value paused)))
    (is (zero? (:samples (az/value retired))))
    (is (= 120 (:observed_tick (az/value retired))))
    (is (= (az/value retired) (az/value (timing/observe retired 30000 1 true true))))
    (is (zero? (:samples (az/value jumped))))
    (is (false? (:complete_start (az/value jumped))))))
