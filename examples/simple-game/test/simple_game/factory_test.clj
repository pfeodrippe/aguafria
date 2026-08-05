(ns simple-game.factory-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.factory :as factory]
            [simple-game.game :as game]))

(use-fixtures
  :each
  (fn [tests]
    (doseq [module '[simple-game.factory simple-game.game simple-game.hud]]
      (az/await! module))
    (game/shutdown)
    (try
      (game/initialize!)
      (tests)
      (finally
        (game/shutdown)))))

(deftest flecs-factory-world-test
  (testing "factory entities share the game world and sparse runtime has an address"
    (let [state (az/value (factory/snapshot))]
      (is (true? (:initialized state)))
      (is (= 12 (:buildings state)))
      (is (= 9 (:belts state)))
      (is (= 1 (:houses_started state)))
      (is (pos? (:stable_sample_address state)))
      (is (true? (factory/verify-stable-runtime! 4 12)))
      (is (= (factory/stable-runtime-address 4 12)
             (:stable_sample_address (az/value (factory/snapshot)))))
      (is (pos? (factory/observed-events)))))

  (testing "construction and demolition update Flecs and dense occupancy together"
    (is (true? (factory/place! 8 8 factory/building-coco-house
                              factory/direction-south)))
    (is (= factory/building-coco-house
           (:building (az/value (factory/cell-view 8 8)))))
    (is (true? (factory/remove! 8 8)))
    (is (= factory/building-empty
           (:building (az/value (factory/cell-view 8 8)))))))

(deftest coco-house-production-test
  (testing "fixed native steps turn coconuts into panels and complete a house"
    (let [before (:houses_completed (az/value (factory/snapshot)))]
      (dotimes [_ 3600]
        (factory/step! 0.008333333))
      (let [after (az/value (factory/snapshot))]
        (is (> (:simulation_ticks after) 3000))
        (is (pos? (:coconuts_harvested after)))
        (is (pos? (:panels_produced after)))
        (is (> (:houses_completed after) before))
        (is (= factory/panels-per-house
               (:inventory (az/value (factory/cell-view 15 12)))))))))

(deftest splitter-routing-test
  (testing "one splitter alternates panels into two independent house sites"
    (let [before (:houses_completed (az/value (factory/snapshot)))
          east factory/direction-east]
      (is (every? true?
                  [(factory/place! 4 15 factory/building-extractor east)
                   (factory/place! 5 15 factory/building-belt east)
                   (factory/place! 6 15 factory/building-assembler east)
                   (factory/place! 7 15 factory/building-belt east)
                   (factory/place! 8 15 factory/building-splitter east)
                   (factory/place! 8 14 factory/building-coco-house east)
                   (factory/place! 8 16 factory/building-coco-house east)]))
      (dotimes [_ 3600]
        (factory/step! factory/fixed-step))
      (is (= factory/panels-per-house
             (:inventory (az/value (factory/cell-view 8 14)))))
      (is (= factory/panels-per-house
             (:inventory (az/value (factory/cell-view 8 16)))))
      (is (<= (+ before 2)
              (:houses_completed (az/value (factory/snapshot))))))))

(deftest projection-roundtrip-test
  (testing "isometric projection and picking agree on cell centers"
    (doseq [[x y] [[0 0] [4 12] [18 12] [31 23]]]
      (let [world-x (* (- x 11.0) 0.5)
            world-z (* (- y 12.0) 0.5)
            screen-x (+ 360.0 (* (- world-x world-z) 39.0))
            screen-y (+ 250.0 (* (+ world-x world-z) 18.0)
                          1.0)
            picked (az/value (factory/screen-to-cell screen-x screen-y))]
        (is (true? (:valid picked)))
        (is (= [x y] [(:x picked) (:y picked)]))))))
