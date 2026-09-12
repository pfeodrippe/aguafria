(ns field-lab.physics-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [field-lab.physics :as native]
            [aguafria.zig :as az]))

(defn defaults [] (az/value (native/defaults)))

(defn initial [config] (az/value (native/initial config)))

(defn advance [state config dt] (az/value (native/advance state config dt)))

(defn close? [a b tolerance] (< (abs (- a b)) tolerance))

(defn trajectory
  [config dt steps]
  (take (inc steps) (iterate #(advance % config dt) (initial config))))

(deftest ballistic-flight-matches-closed-form
  (let [c (defaults)
        s (nth (trajectory c (/ 1.0 240) 120) 120)]
    (is (close? (get-in s [:position :y])
                (- (+ (:radius c) (:height c)) (* 0.5 (:gravity c) 0.25))
                1.0e-10))
    (is (close? (get-in s [:velocity :y]) (* -0.5 (:gravity c)) 1.0e-10))
    (is (close? (get-in s [:position :x]) (+ -1.5 (* 0.5 (:vx c))) 1.0e-10))))

(deftest first-impact-and-rebound
  (let [c (assoc (defaults)
                 :vx 0.0
                 :vz 0.0
                 :spin 0.0)
        impact-time (Math/sqrt (/ (* 2 (:height c)) (:gravity c)))
        incoming (Math/sqrt (* 2 (:gravity c) (:height c)))
        rebound (* (:restitution c) incoming)
        s (advance (initial c) c (+ impact-time 0.01))]
    (is (= 1 (:impacts s)))
    (is (close? (get-in s [:velocity :y]) (- rebound (* (:gravity c) 0.01)) 1.0e-10))
    (is (close? (:impulse s) (* (:mass c) (+ 1 (:restitution c)) incoming) 1.0e-10))
    (let [apex (advance (initial c) c (+ impact-time (/ rebound (:gravity c))))]
      (is (close? (- (get-in apex [:position :y]) (:radius c))
                  (* (:height c) (:restitution c) (:restitution c))
                  1.0e-9)))))

(deftest conservative-flight-and-elastic-impacts
  (let [c (assoc (defaults)
                 :restitution 1.0
                 :friction 0.0
                 :rolling 0.0)
        states (vec (trajectory c (/ 1.0 240) 2400))
        initial-energy (native/energy (first states) c)]
    (is (> (:impacts (peek states)) 4))
    (is (every? #(close? (native/energy % c) initial-energy 1.0e-8) states))))

(deftest contact-is-dissipative-and-nonpenetrating
  (let [c (defaults)
        states (vec (trajectory c (/ 1.0 240) 7200))
        energies (mapv #(native/energy % c) states)]
    (is (every? #(>= (get-in % [:position :y]) (- (:radius c) 1.0e-10)) states))
    (is (every? (fn [[a b]] (<= b (+ a 1.0e-8))) (partition 2 1 energies)))
    (is (:supported (peek states)))
    (is (< (native/length (:velocity (peek states))) 1.0e-8))
    (is (every? (fn [s]
                  (let [q (vals (:orientation s))]
                    (close? (reduce + (map #(* % %) q)) 1.0 1.0e-10)))
                states))))

(deftest friction-transfers-linear-momentum-to-spin
  (let [c (assoc (defaults)
                 :restitution 0.0
                 :rolling 0.0
                 :spin 0.0
                 :vz 0.0)
        s (advance (initial c) c 1.0)
        vx (get-in s [:velocity :x])
        wz (get-in s [:omega :z])]
    (is (:supported s))
    ;; Solid sphere reaches rolling with v = (5/7) v_initial.
    (is (close? vx (* (/ 5.0 7) (:vx c)) 1.0e-9))
    (is (close? (+ vx (* (:radius c) wz)) 0.0 1.0e-9))))

(deftest step-partition-invariance-for-ballistic-collision
  (let [c (assoc (defaults)
                 :friction 0.0
                 :rolling 0.0)
        a (last (trajectory c (/ 1.0 120) 360))
        b (last (trajectory c (/ 1.0 480) 1440))]
    (is (= (:impacts a) (:impacts b)))
    (doseq [axis [:x :y :z]]
      (is (close? (get-in a [:position axis]) (get-in b [:position axis]) 1.0e-9)))))

(deftest zero-gravity-and-high-speed-ccd
  (let [c (assoc (defaults)
                 :gravity 0.0
                 :friction 0.0
                 :rolling 0.0)
        initial (initial c)
        s (advance initial c 2.0)]
    (is (close? (get-in initial [:position :y]) (get-in s [:position :y]) 1.0e-12))
    (let [fast (assoc-in initial [:velocity :y] -1000.0)
          hit (advance fast c 0.01)]
      (is (= 1 (:impacts hit)))
      (is (>= (get-in hit [:position :y]) (:radius c)))
      (is (close? (get-in hit [:velocity :y]) 780.0 1.0e-10)))))

(defn -main
  [& _]
  (let [result (run-tests 'field-lab.physics-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
