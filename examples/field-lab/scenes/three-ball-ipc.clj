;; Ordinary Clojure scene data for an offline native FEM bake.
;; These are illustrative homogeneous soft solids, not calibrated sports balls.
(require '[pitoco.geometry :as geometry])

(let [radius 0.15
      sources [[:left [-0.33 0.17 0.0] [0.7 0.0 0.0]]
               [:middle [0.0 0.165 0.0] [0.0 0.0 0.0]]
               [:right [0.33 0.18 0.015] [-0.7 0.0 0.0]]]]
  {:format :pitoco/solid-scene-v1
   :title "Three soft solids — FEM and frictional IPC"
   :bake {:seconds 0.3
          :maximum-step 0.0005
          :contact-method :ipc
          :clearance 0.0001
          :barrier-pressure 1000.0}
   :bodies
   (mapv (fn [[id center velocity]]
           (let [mesh (geometry/sphere {:radius radius :center center :refinement 1})]
             {:id id
              :mesh mesh
              :material {:young-Pa 10000.0 :poisson-ratio 0.4}
              :density-kg-m3 1100.0
              :gravity [0.0 -9.81 0.0]
              :floor? true
              :friction 0.3
              :initial-velocities (vec (repeat (count (:points mesh)) velocity))}))
         sources)})
