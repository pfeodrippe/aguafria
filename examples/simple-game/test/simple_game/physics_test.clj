(ns simple-game.physics-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.physics :as physics]))

(use-fixtures
  :each
  (fn [tests]
    (az/await! 'simple-game.physics)
    (physics/shutdown!)
    (try
      (tests)
      (finally
        (physics/shutdown!)))))

(deftest factory-particle-burst-test
  (testing "a house event creates bounded real Box3D bodies at its grid cell"
    (is (true? (physics/initialize!)))
    (physics/emit! 15 12 4)
    (is (= 12 (physics/active-count)))
    (let [before (az/value (physics/particle-view 0))]
      (is (true? (:active before)))
      (is (= 2.0 (double (:x before))))
      (physics/step! 0.05)
      (let [after (az/value (physics/particle-view 0))]
        (is (not= (:y before) (:y after)))))
    (let [state (az/value (physics/snapshot))]
      (is (= 12 (:emitted state)))
      (is (pos? (:box3d_bytes state))))))
