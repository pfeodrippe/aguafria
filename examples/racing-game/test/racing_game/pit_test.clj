(ns racing-game.pit-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is]]
            [racing-game.simulation :as sim]
            [racing-game.track :as track]
            [racing-game.worker :as worker]))

(defn distance [a b]
  (Math/hypot (- (:x b) (:x a)) (- (:y b) (:y a))))

(deftest straight-pit-layout-test
  (let [boxes (mapv #(az/value (track/pit-pose (sim/pit-box-progress %) 0.215))
                    (range 4))]
    (let [[a b c] boxes]
      (is (< (abs (- (* (- (:x b) (:x a)) (- (:y c) (:y a)))
                       (* (- (:y b) (:y a)) (- (:x c) (:x a))))) 1.0e-7)))
    ;; Metre-sized garages are 10.5m wide; world coordinates are km.
    (is (every? #(> (distance (first %) (second %)) 0.0105)
                (partition 2 1 boxes))))
  (doseq [p [0.94 0.055] lane [-0.04 0.0 0.17]]
    (is (< (distance (az/value (track/pose p lane))
                     (az/value (track/pit-pose p lane))) 1.0e-6))))

(deftest pit-motion-does-not-teleport-test
  (worker/stop!)
  (sim/configure-countdown! 0)
  (sim/set-items-enabled! false)
  (try
    (sim/reset!)
    (sim/configure-racer-state! 0 0.9401 -0.04 0.08 sim/item-none false)
    (sim/configure-racer-tires! 0 0.10)
    (is (sim/call-driver-to-pit! 0 0))
    ;; Step only this pit car: no concurrent race/reset or accidental contacts
    ;; can obscure whether the pit controller itself produces displacement.
    (let [samples (loop [i 0 result []]
                    (let [v (az/value (sim/racer-view 0))]
                      (if (or (= i 6000)
                              (and (pos? (:pit_stops v))
                                   (= sim/pit-state-track (:pit_state v))))
                        (conj result v)
                        (do (sim/step-pit! 0)
                            (recur (inc i) (conj result v))))))
          stopped (filter #(= sim/pit-state-servicing (:pit_state %)) samples)
          moves (map #(distance (first %) (second %)) (partition 2 1 samples))]
      (is (seq stopped))
      (is (every? #(zero? (:speed %)) stopped))
      (is (every? #(< (distance (first stopped) %) 1.0e-6) stopped))
      (is (< (apply max moves) 0.001) "No pit step can teleport a car by a metre")
      (is (= 1 (:pit_stops (last samples))))
      (is (= sim/pit-state-track (:pit_state (last samples)))))
    (finally (sim/set-items-enabled! true) (sim/reset!))))
