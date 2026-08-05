(ns simple-game.game-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.factory :as factory]
            [simple-game.game :as game]))

(use-fixtures
  :once
  (fn [tests]
    (az/await! 'simple-game.game)
    (game/shutdown)
    (try
      (tests)
      (finally
        (game/shutdown)))))

(deftest flecs-owner-test
  (testing "one native owner exposes the exact factory world to Clojure"
    (is (true? (game/initialize!)))
    (let [state (az/value (game/snapshot))]
      (is (true? (:initialized state)))
      (is (pos? (:world_address state)))
      (is (= 12 (:buildings state)))
      (is (= 0 (:houses_completed state)))))

  (testing "a platform tick drives construction through the shared pointer path"
    ;; Cell 8,8 projects to roughly 380,188 in the factory mesh camera.
    (is (= factory/building-empty
           (:building (az/value (factory/cell-view 8 8)))))
    (let [packet (az/value
                  (game/tick 380.0 188.0 720.0 540.0
                             0.01 true true))]
      (is (pos? (:frame packet)))
      (is (= factory/building-belt
             (:building (az/value (factory/cell-view 8 8))))))
    ;; Holding the button across another cell builds continuously; it does not
    ;; require a second press edge.
    (game/tick 399.0 197.0 720.0 540.0 0.01 true false)
    (is (= factory/building-belt
           (:building (az/value (factory/cell-view 9 8)))))
    (let [buildings (:buildings (az/value (factory/snapshot)))]
      (game/tick 380.0 188.0 720.0 540.0 0.01 false false)
      (is (= buildings (:buildings (az/value (factory/snapshot)))))))

  (testing "pause is the factory pause, not a detached presentation state"
    (is (true? (game/toggle-paused!)))
    (is (true? (:paused (az/value (factory/snapshot)))))
    (is (false? (game/toggle-paused!)))))
