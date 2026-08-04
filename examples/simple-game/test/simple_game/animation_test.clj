(ns simple-game.animation-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [simple-game.animation :as animation]
            [simple-game.build :as build]))

(use-fixtures
  :once
  (fn [tests]
    (build/prepare-sprite-assets!)
    (az/await! 'simple-game.animation)
    (try
      (tests)
      (finally
        (animation/shutdown!)))))

(deftest frame-selection-test
  (testing "looping and one-shot clips select deterministic native frames"
    (is (= 0 (animation/frame-at-time 0.0 8.0 6 true)))
    (is (= 1 (animation/frame-at-time 0.13 8.0 6 true)))
    (is (= 0 (animation/frame-at-time 0.75 8.0 6 true)))
    (is (= 5 (animation/frame-at-time 20.0 8.0 6 false)))))

(deftest spritesheet-and-frame-list-test
  (testing "one spritesheet and six separate sprite files load through one API"
    (let [assets (build/prepare-sprite-assets!)]
      (is (#{:built :cached} (get-in assets [:sheet :status])))
      (is (#{:built :cached} (get-in assets [:sprites :status])))
      (is (= :spritesheet (get-in assets [:sheet :source-kind])))
      (is (= :sprites (get-in assets [:sprites :source-kind])))
      (is (= 6 (count (:frame-paths assets)))))

    (animation/shutdown!)
    (is (true? (animation/initialize!)))
    (let [sheet (az/value (animation/snapshot))
          frame (az/value (animation/current-frame-view))
          span (az/value (animation/span-at (:first_span frame)))]
      (is (= 6 (:frames sheet)))
      (is (= [48 48] [(:width sheet) (:height sheet)]))
      (is (pos? (:spans sheet)))
      (is (pos? (:span_count frame)))
      (is (= 255 (:alpha span))))

    (is (true? (animation/use-generated-sprite-list!)))
    (let [sprites (az/value (animation/snapshot))]
      (is (= 6 (:frames sprites)))
      (is (= [48 48] [(:width sprites) (:height sprites)]))
      (is (pos? (:spans sprites))))))

(deftest live-player-controls-test
  (testing "playback can be changed and inspected without reloading the game"
    (animation/restart!)
    (animation/set-fps! 10.0)
    (animation/tick! 0.21)
    (is (= 2 (animation/current-frame)))
    (animation/set-playing! false)
    (animation/tick! 1.0)
    (is (= 2 (animation/current-frame)))
    (let [snapshot (az/value (animation/snapshot))]
      (is (= 10.0 (double (:fps snapshot))))
      (is (false? (:playing snapshot))))))
