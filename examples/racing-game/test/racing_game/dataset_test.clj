(ns racing-game.dataset-test
  (:require [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]
            [racing-game.dataset :as dataset]))

(deftest coverage-reads-native-values-only-when-requested
  (doseq [racer-count [3 20]]
    (let [reads (atom 0)]
      (with-redefs [a/value (fn [_] (swap! reads inc) racer-count)]
        (is (= (set (range racer-count)) (:racers (dataset/required-coverage))))
        (is (= 1 @reads))))))
