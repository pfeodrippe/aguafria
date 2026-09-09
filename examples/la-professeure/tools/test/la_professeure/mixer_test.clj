(ns la-professeure.mixer-test
  (:require [clojure.test :refer [deftest is]] [clojure.java.io :as io]
            [aguafria.zig :as az] [aguafria.keyword :as ak]
            [la-professeure.tools.mixer :as mixer]
            [la-professeure.tools.takes :as takes]
            [la-professeure.takes-test :as fixtures]))

(az/defvar output [:array 2048 :f32] ak/undefined)
(az/defn block! :- :void [[frames :u32]]
  (when (<= frames 1024) (mixer/process! (ak/& output) frames)))
(az/defn sample-at :- :f32 [[index :usize]] (az/index output index))
(defn left [n] (mapv #(double (sample-at (* 2 %))) (range n)))
(defn approx [a b] (and (= (count a) (count b)) (every? #(< (abs %) 0.00001) (map - a b))))

(defn loop-value [] (let [v (mixer/loop-state)] (try (az/value v) (finally (az/close! v)))))

(deftest sample-accurate-loop-regions
  (let [dir (fixtures/directory)
        path (takes/write-wav! (io/file dir "loop.wav") (fixtures/samples (map #(/ % 10.0) (range 1 9))))
        expected [0.3 0.4 0.5 0.3 0.4 0.5 0.3 0.4 0.5 0.3 0.4]]
    (try
      (mixer/reset!) (is (mixer/add-file! path))
      (mixer/configure-clip! 0 0 1.0 0.0 0 false false)
      (is (mixer/set-loop! 2 5 true))
      (is (= {:from 2 :to 5 :enabled true} (loop-value)))
      (is (false? (mixer/set-loop! 3 3 true)))
      (is (false? (mixer/set-loop! 0 9 true)))
      (is (false? (mixer/set-loop! 0 4294967296 true)))
      (is (= {:from 2 :to 5 :enabled true} (loop-value)))
      (mixer/play!) (block! 11)
      (is (approx expected (left 11))) (is (= 4 (mixer/cursor-frame)))
      (mixer/seek! 2)
      (let [parts (reduce (fn [result size] (block! size) (into result (left size))) [] [1 4 6])]
        (is (approx expected parts)))
      (mixer/pause!) (mixer/seek! 7) (block! 5)
      (is (= 7 (mixer/cursor-frame))) (is (every? zero? (left 5)))
      (mixer/play!) (block! 1) (is (approx [0.3] (left 1)))
      (is (mixer/set-loop! 4 5 true)) (block! 4)
      (is (approx [0.5 0.5 0.5 0.5] (left 4))) (is (= 4 (mixer/cursor-frame)))
      (is (mixer/set-loop! 0 0 false))
      (mixer/seek! 6) (block! 4) (is (approx [0.7 0.8 0 0] (left 4)))
      (is (false? (mixer/playing?)))
      (mixer/set-loop! 0 8 true)
      (mixer/configure-clip! 0 2 1.0 0.0 0 false false)
      (is (false? (:enabled (loop-value))))
      (finally (mixer/reset!)))))

(deftest shared-sample-clock-and-overlap
  (let [dir (fixtures/directory)
        a (takes/write-wav! (io/file dir "a.wav") (fixtures/samples (repeat 8 0.2)))
        b (takes/write-wav! (io/file dir "b.wav") (fixtures/samples (repeat 8 0.3)))]
    (mixer/reset!)
    (is (mixer/add-file! a)) (is (mixer/add-file! b))
    (is (mixer/configure-clip! 0 0 1.0 0.0 0 false false))
    (is (mixer/configure-clip! 1 2 1.0 0.0 0 false false))
    (mixer/play!) (block! 10)
    (is (approx [0.2 0.2 0.5 0.5 0.5 0.5 0.5 0.5 0.3 0.3] (left 10)))
    (is (= 10 (mixer/cursor-frame))) (is (false? (mixer/playing?)))
    ;; End-of-stream cannot cancel a later seek; desired Play still belongs to control.
    (mixer/seek! 0) (block! 1)
    (is (approx [0.2] (left 1))) (is (mixer/playing?))
    (mixer/seek! 0) (mixer/play!) (block! 3)
    (let [first-block (left 3)]
      (block! 7)
      (is (approx [0.2 0.2 0.5 0.5 0.5 0.5 0.5 0.5 0.3 0.3] (into first-block (left 7)))))
    (mixer/seek! 0) (mixer/play!) (block! 3) (mixer/pause!) (block! 4)
    (is (= 3 (mixer/cursor-frame))) (is (= [0.0 0.0 0.0 0.0] (left 4)))
    (mixer/seek! 7) (block! 1) (is (= 7 (mixer/cursor-frame)))
    (mixer/play!) (block! 6)
    (is (approx [0.5 0.3 0.3 0 0 0] (left 6)))
    (is (= 10 (mixer/cursor-frame)))
    (mixer/reset!)))

(deftest fades-pan-mute-solo-and-bounds
  (let [dir (fixtures/directory)
        a (takes/write-wav! (io/file dir "a.wav") (fixtures/samples (repeat 8 0.8)))
        bad (takes/write-wav! (io/file dir "nan.wav") (fixtures/samples [Float/NaN]))]
    (mixer/reset!) (is (mixer/add-file! a)) (is (mixer/add-file! a))
    (is (false? (mixer/add-file! bad))) (is (= 2 (az/value mixer/clip-count)))
    (is (false? (mixer/add-file! (str a "\u0000ignored"))))
    (is (false? (mixer/configure-clip! 2 0 1.0 0.0 0 false false)))
    (is (false? (mixer/configure-clip! 0 0 Float/NaN 0.0 0 false false)))
    (mixer/configure-clip! 0 0 1.0 -1.0 2 false true)
    (mixer/configure-clip! 1 0 1.0 0.0 0 false false)
    (mixer/play!) (block! 8)
    (is (approx [0 0.4 0.8 0.8 0.8 0.8 0.4 0] (left 8)))
    (is (every? zero? (map #(sample-at (inc (* 2 %))) (range 8))))
    (mixer/configure-clip! 0 0 1.0 0.0 0 true false)
    (mixer/seek! 0) (mixer/play!) (block! 2) (is (approx [0.8 0.8] (left 2)))
    (mixer/configure-clip! 0 0 1.0 0.0 0 false false)
    (mixer/seek! 0) (mixer/play!) (block! 2) (is (= [1.0 1.0] (left 2)))
    (is (= 1 (az/value mixer/clipped)))
    (az/set-value! mixer/used 5760000)
    (is (false? (mixer/add-file! a))) (is (= 2 (az/value mixer/clip-count)))
    (mixer/reset!)))
