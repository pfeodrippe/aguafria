(ns field-lab.scene-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria-examples-native.bindings.flecs :as ecs]
            [field-lab.scene :as scene]
            [field-lab.physics :as p]))

(az/defn advance-many!
  :- :void
  [[steps :u32]]
  (dotimes [_ steps] (set! _ (scene/step!))))

(az/defn cache-size
  :- :u32
  []
  scene/count)

(az/defn graph-valid?
  :- :bool
  []
  (and (ecs/ecs_has_id scene/world
                       scene/solver
                       (ak/| ecs/ECS_PAIR (ak/<< scene/depends-on 32) scene/source))
       (ecs/ecs_has_id scene/world
                       scene/output
                       (ak/| ecs/ECS_PAIR (ak/<< scene/depends-on 32) scene/solver))))

(deftest flecs-storage-and-replay
  (scene/initialize!)
  (try (is (graph-valid?))
       (advance-many! 300)
       (let [expected (az/value (scene/sample))]
         (scene/seek! 100)
         (advance-many! 200)
         (is (= expected (az/value (scene/sample)))))
       (scene/set-experiment! true)
       (is (= 1 (cache-size)))
       (advance-many! 400)
       (let [bodies (:bodies (az/value (scene/sample)))]
         (is (= 3 (count bodies)))
         (is (> (reduce + (map :impacts bodies)) 0)))
       (scene/set-experiment! false)
       (advance-many! 14400)
       (is (= 14401 (cache-size)))
       (is (false? (scene/step!)))
       (scene/reset! (p/defaults))
       (is (= 1 (cache-size)))
       (is (zero? (:time (az/value (scene/state)))))
       (finally (scene/shutdown!))))

(deftest deformable-state-is-cached-and-replayed
  (scene/initialize!)
  (try
    (scene/set-material! true 10000.0)
    (scene/set-experiment! true)
    (advance-many! 230)
    (let [expected (az/value (scene/soft-sample))]
      (scene/seek! 200)
      (advance-many! 30)
      (is (= expected (az/value (scene/soft-sample))))
      (is (= 231 (cache-size))))
    (scene/set-material! false 10000.0)
    (is (= 1 (cache-size)))
    (finally
      (scene/shutdown!))))

(deftest baking-is-independent-of-display-chunks
  (scene/initialize!)
  (try
    (scene/set-material! true 10000.0)
    (scene/set-experiment! false)
    (is (true? (scene/bake-chunk! 230 230)))
    (let [expected (az/value (scene/soft-sample))]
      (scene/seek! 60)
      (scene/seek! 230)
      (is (= 231 (cache-size)))
      (is (= expected (az/value (scene/soft-sample))))
      (scene/reset! (p/defaults))
      (dotimes [_ 15] (scene/bake-chunk! 230 16))
      (is (= expected (az/value (scene/soft-sample))))
      (is (= 231 (cache-size))))
    (scene/set-material! false 10000.0)
    (finally
      (scene/shutdown!))))

(deftest continuum-material-and-bake-chunks
  (scene/initialize!)
  (try
    (scene/set-experiment! false)
    (scene/set-solver! 2 10000.0)
    (let [config (assoc (az/value (p/defaults)) :mass 20.0 :height 0.5 :vx 0.0 :vz 0.0 :spin 0.0)]
      (scene/reset! config)
      (is (= {:model 2 :young 10000.0 :poisson 0.4} (az/value (scene/solver-settings))))
      (is (scene/bake-chunk! 100 100))
      (let [expected (az/value (scene/soft-sample))]
        (scene/reset! config)
        (loop []
          (when-not (scene/bake-chunk! 100 7) (recur)))
        (is (= expected (az/value (scene/soft-sample))))
        (scene/seek! 30)
        (scene/seek! 100)
        (is (= expected (az/value (scene/soft-sample))))
        (is (= 101 (cache-size)))
        (is (false? (az/value scene/solver-failed)))))
    (finally
      (scene/set-experiment! false)
      (scene/set-solver! 0 10000.0)
      (scene/shutdown!))))
