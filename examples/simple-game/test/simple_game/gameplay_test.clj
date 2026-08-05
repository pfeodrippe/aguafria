(ns simple-game.gameplay-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.gameplay :as gameplay]))

(use-fixtures
  :each
  (fn [tests]
    (az/await! 'simple-game.gameplay)
    (gameplay/reset! 12345)
    (tests)))

(deftest pure-gameplay-rules-test
  (testing "score and difficulty rules execute as native Zig"
    (is (= 225 (gameplay/score-for-hit 3 1)))
    (is (= 1 (gameplay/level-for-hits 0)))
    (is (= 2 (gameplay/level-for-hits 5)))
    (is (> (double (gameplay/radius-for-level 1))
           (double (gameplay/radius-for-level 8))))))

(deftest deterministic-session-test
  (testing "the same seed and actions produce the same exact checksum"
    (dotimes [_ 7]
      (gameplay/register-hit!)
      (gameplay/step! 0.25))
    (gameplay/register-miss!)
    (let [first-hash (gameplay/state-hash)
          first-state (az/value (gameplay/snapshot))]
      (gameplay/reset! 12345)
      (dotimes [_ 7]
        (gameplay/register-hit!)
        (gameplay/step! 0.25))
      (gameplay/register-miss!)
      (is (= first-hash (gameplay/state-hash)))
      (is (= (:score first-state) (:score (az/value (gameplay/snapshot))))))))

(deftest pause-and-game-over-test
  (testing "pause freezes time and three misses end the round"
    (gameplay/set-paused! true)
    (gameplay/step! 5.0)
    (is (= 30.0 (double (:time_remaining (az/value (gameplay/snapshot))))))
    (gameplay/set-paused! false)
    (dotimes [_ 3]
      (gameplay/register-miss!))
    (let [state (az/value (gameplay/snapshot))]
      (is (zero? (:lives state)))
      (is (true? (:game_over state))))))
