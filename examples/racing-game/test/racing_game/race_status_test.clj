(ns racing-game.race-status-test
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [racing-game.race-status :as status]
            [clojure.test :refer [deftest is]]))

(az/defn observe-probe :- status/Entry
  [[up :f32] [speed :f32] [ticks :u32] [finished :bool]]
  (let [^:var entry (mem/zeroes (az/type status/Entry))]
    (dotimes [i ticks]
      (set! entry (status/observe entry up speed (+ 1000 i) 2 0.4 finished)))
    entry))

(deftest sustained-physical-retirement-test
  (let [before (az/value (observe-probe -1.0 0.1 599 false))
        at-limit (observe-probe -1.0 0.1 600 false)
        retired (az/value at-limit)]
    (is (false? (:retired before)))
    (is (= 599 (:invalid_ticks before)))
    (is (:retired retired))
    (is (= 1 (:reason retired)))
    (is (= 1599 (:retired_tick retired)))
    (is (= 2 (:lap retired)))
    (is (< (abs (- 0.4 (:progress retired))) 1.0e-5))
    (is (= retired (az/value (status/observe at-limit 1.0 20.0 9999 9 0.9 false))))))

(deftest stops-rolls-and-finishers-are-not-automatic-dnfs-test
  (doseq [[up speed finished] [[1.0 0.0 false] [-1.0 15.0 false] [-1.0 0.0 true]]]
    (is (false? (:retired (az/value (observe-probe up speed 1200 finished)))))))

(deftest retirement-evidence-must-be-continuous-test
  (let [almost (observe-probe -1.0 0.1 599 false)
        recovered (status/observe almost 1.0 0.0 1600 2 0.4 false)
        next-roll (az/value (status/observe recovered -1.0 0.0 1601 2 0.4 false))]
    (is (= 0 (:invalid_ticks (az/value recovered))))
    (is (= 1 (:invalid_ticks next-roll)))
    (is (false? (:retired next-roll)))))

(deftest outside-world-is-not-a-finish-or-an-ordinary-flight-test
  (let [entry (observe-probe 1.0 0.0 0 false)
        lost (status/observe-world-position entry 1.0 80.0 -51.0 0.0 700 2 0.8 false)
        result (az/value lost)]
    (is (:retired result))
    (is (= 2 (:reason result)))
    (is (= 2 (:lap result)))
    (is (= 700 (:retired_tick result)))
    (is (= result (az/value (status/observe-world-position lost 1.0 0.0 10.0 0.0 900 9 0.1 false))))
    (doseq [z [-50.0 -49.0 0.0 60.0 200.0]]
      (is (false? (:retired (az/value
                             (status/observe-world-position entry 1.0 80.0 z 0.0 700 2 0.8 false))))))
    (is (false? (:retired (az/value
                           (status/observe-world-position entry 1.0 80.0 -100.0 0.0 700 3 0.0 true)))))))
