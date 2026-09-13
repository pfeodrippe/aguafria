;; Run in an ordinary Clojure program with pitoco/scripting on its classpath.
;; This file returns scene data; it does not start a solver or open a window.
(require '[pitoco.geometry :as geometry])

(let [box (geometry/box-mesh [2 2 2] [0.4 0.4 0.4])
      box (update box :points #(mapv (fn [point] (mapv + [-0.55 0.4 -0.2] point)) %))
      tetrahedron {:points [[0.15 0.4 -0.25]
                            [0.65 0.4 -0.25]
                            [0.15 0.9 -0.25]
                            [0.15 0.4 0.25]]
                   :cells [[0 1 2 3]]}
      tetrahedron (nth (iterate geometry/refine tetrahedron) 2)]
  {:format :pitoco/solid-scene-v1
   :title "Box and tetrahedron impact"
   :bake {:seconds 0.6 :maximum-step 0.00005}
   :bodies [{:id :soft-box
             :mesh box
             :material {:young-Pa 5000.0 :poisson-ratio 0.3}
             :density-kg-m3 180.0
             :gravity [0.0 -9.81 0.0]
             :floor? true
             :friction 0.3
             :initial-velocities (vec (repeat (count (:points box)) [0.6 0.0 0.0]))}
            {:id :stiffer-tetrahedron
             :mesh tetrahedron
             :material {:young-Pa 20000.0 :poisson-ratio 0.25}
             :density-kg-m3 300.0
             :gravity [0.0 -9.81 0.0]
             :floor? true
             :friction 0.4
             :initial-velocities (vec (repeat (count (:points tetrahedron)) [-0.6 0.0 0.0]))}]})
