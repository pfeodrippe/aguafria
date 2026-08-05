(ns simple-game.audio-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing]]
            [simple-game.audio :as audio]))

(deftest factory-event-tone-test
  (az/await! 'simple-game.audio)
  (testing "factory events map to distinct native feedback tones"
    (is (= 540.0 (double (audio/event-frequency 1))))
    (is (= 230.0 (double (audio/event-frequency 2))))
    (is (= 620.0 (double (audio/event-frequency 3))))
    (is (= 880.0 (double (audio/event-frequency 4))))))
