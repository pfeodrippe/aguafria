(ns simple-game.game-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.game :as game]))

(use-fixtures
  :once
  (fn [tests]
    (az/await! 'simple-game.game)
    (try
      (tests)
      (finally
        (game/shutdown)))))

(deftest deterministic-game-transitions-test
  (testing "pure gameplay functions execute as real Zig calls"
    (is (true? (game/circle-contains? 360.0 270.0 360.0 270.0 90.0)))
    (is (false? (game/circle-contains? 500.0 270.0 360.0 270.0 90.0)))
    (is (< (Math/abs (- 3.0 (double (game/animated-phase 2.0 0.25 true))))
           0.0001))
    (is (= 2.0 (double (game/animated-phase 2.0 0.25 false))))
    (is (= [0 1 2 3 4 0 1]
           (mapv game/shader-for-count (range 7))))))

(deftest real-flecs-world-test
  (testing "click! and the Flecs input system share one counter transition"
    (game/shutdown)
    (is (true? (game/initialize!)))
    (is (= 0 (:counter (az/value (game/snapshot)))))
    (is (= 1 (game/click!)))
    (let [packet (az/value
                  (game/tick 360.0 270.0 720.0 540.0
                             0.01 true true))]
      (is (true? (:hovered packet)))
      (is (= 2 (:counter packet)))
      (is (= 2 (:shader_index packet))))
    (let [packet (az/value
                  (game/tick 0.0 0.0 720.0 540.0
                             0.01 false false))]
      (is (false? (:hovered packet)))
      (is (= 2 (:counter packet)))
      (is (= 2 (:shader_index packet))))))
