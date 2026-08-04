(ns simple-game.physics-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.physics :as physics]))

(use-fixtures
  :each
  (fn [test]
    (az/await! 'simple-game.physics)
    (physics/shutdown!)
    (try
      (test)
      (finally
        (physics/shutdown!)))))

(deftest real-box3d-particle-lifecycle-test
  (testing "a click-sized burst is simulated in 3D and retires from the pool"
    (is (true? (physics/initialize!)))
    (physics/emit! 3)
    (is (= 6 (physics/active-count)))
    (let [before (az/value (physics/particle-view 0))]
      (dotimes [_ 5]
        (physics/step! 0.1))
      (let [after (az/value (physics/particle-view 0))]
        (is (true? (:active after)))
        (is (not= (select-keys before [:x :y :z])
                  (select-keys after [:x :y :z])))))
    (dotimes [_ 18]
      (physics/step! 0.1))
    (is (zero? (physics/active-count)))
    (is (pos? (:box3d_bytes (az/value (physics/snapshot)))))))
