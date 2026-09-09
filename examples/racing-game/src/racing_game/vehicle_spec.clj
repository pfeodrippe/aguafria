(ns racing-game.vehicle-spec
  "Small Blender-authored physical dimensions, without importing render meshes."
  (:require [aguafria.zig :as az]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(let [data (edn/read-string (slurp (io/resource "geometry/wheel-axes.edn")))
      rows (mapv (fn [part]
                   (let [{:keys [center radius width]} (get data part)]
                     (when-not (and (= 3 (count center)) (every? number? center)
                                    (number? radius) (pos? radius)
                                    (number? width) (pos? width))
                       (throw (ex-info "Invalid Blender wheel dimensions" {:part part})))
                     (into center [radius width])))
                 [:wheel-front-left :wheel-front-right :wheel-rear-left :wheel-rear-right])]
  (eval `(az/defconst ~'wheel-geometry
           "Four rows: model-space axle X/Y/Z, rolling radius and tire width (metres)."
           [:array 4 [:array 5 :f32]] ~rows)))

(az/defconst chassis-origin-z
  "Model-space height of the chassis collider centre; subtract when posing art."
  :f32 0.66)
