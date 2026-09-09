(ns racing-game.track-barriers-test
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.physics-track :as terrain]
            [racing-game.track-barriers :as barriers]
            [racing-game.track :as track]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest blender-export-is-closed-and-nondegenerate-test
  (let [{:keys [vertices triangles units]} (edn/read-string
                                            (slurp (io/resource "geometry/barriers.edn")))
        edges (frequencies (mapcat (fn [[a b c]] (map #(vec (sort %)) [[a b] [b c] [c a]])) triangles))
        cross (fn [[x y z] [a b c]] [(- (* y c) (* z b)) (- (* z a) (* x c)) (- (* x b) (* y a))])
        dot (fn [a b] (reduce + (map * a b)))]
    (is (= :metres units))
    (is (every? #(= 2 %) (vals edges)) "Every mesh edge has two adjacent faces")
    (is (every? (fn [[ia ib ic]]
                  (let [a (vertices ia) b (vertices ib) c (vertices ic)
                        n (cross (mapv - b a) (mapv - c a))]
                    (> (dot n n) 1.0e-12))) triangles))
    (is (pos? (reduce + (map (fn [[a b c]] (dot (vertices a) (cross (vertices b) (vertices c)))) triangles)))
        "Closed barriers have outward winding, including their bottom faces")))

(az/defn impact-probe
  "Controlled crash initial conditions, not driving code. Velocity is assigned
  once at setup; all subsequent contact/rotation/deceleration is solved by Box3D."
  :- [:array 4 :f32] [[progress :f32] [side :f32] [speed :f32]]
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        wall (barriers/create! world)
        ;; Start on the roadway, inside the actual wall even where the
        ;; Blender-authored runoff narrows around an inside bend.
        p (circuit/at-distance (* progress 4309.0) (* side 4.0))
        body (physics/create-box world
               (b3/b3Pos {:x (az/field p x) :y (az/field p y) :z (+ (az/field p z) 0.6)})
               (b3/b3Vec3 {:x 2.5 :y 0.75 :z 0.2}) (az/field p heading) 700.0)
        ^{:var :i32} hits 0
        ^{:var :f32} maximum-lane 0.0
        ^{:var :f32} minimum-z 1000.0]
    (ak/defer (barriers/destroy! wall))
    (ak/defer (terrain/destroy! surface))
    (ak/defer (physics/destroy-world! world))
    (b3/b3Body_SetLinearVelocity body
      (b3/b3Vec3 {:x (* side speed (- (math/sin (az/field p heading))))
                   :y (* side speed (math/cos (az/field p heading))) :z 0.0}))
    (dotimes [_ physics/step-rate]
      (physics/step! world)
      (let [state (physics/body-state body)
            projection (track/project (* (az/field state x) 0.001) (* (az/field state y) 0.001))]
        (set! hits (+ hits (az/field (b3/b3World_GetContactEvents world) hitCount)))
        (set! maximum-lane (ak/max maximum-lane (* 50.0 (ak/abs (az/field projection lane)))))
        (set! minimum-z (ak/min minimum-z (az/field state z)))))
    (let [state (physics/body-state body)]
      (az/array-init [:array 4 :f32]
        [(ak/floatFromInt hits) maximum-lane minimum-z
         (ak/sqrt (+ (* (az/field state vx) (az/field state vx))
                      (* (az/field state vy) (az/field state vy))))]))))

(deftest rigid-body-cannot-pass-through-authored-containment-test
  (doseq [progress [0.0 0.25 0.5 0.82] side [-1.0 1.0]]
    (let [[hits lane minimum-z speed :as result] (az/value (impact-probe progress side 83.333))
          context (pr-str {:progress progress :side side :result result})]
      (is (pos? hits) context)
      (is (< lane 33.0) context)
      (is (> minimum-z (- circuit/minimum-elevation 2.0)) context)
      (is (< speed 40.0) context))))
