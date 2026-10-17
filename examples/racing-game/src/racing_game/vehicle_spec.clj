(ns racing-game.vehicle-spec
  "Small Blender-authored physical dimensions, without importing render meshes."
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.keyword :as k :refer :all]
            [clojure.core :as c]))

(let [data (edn/read-string (c/slurp (io/resource "geometry/wheel-axes.edn")))
      rows (c/mapv (c/fn [part]
                     (let [{:keys [center radius width]} (c/get data part)]
                       (when-not (c/and (c/= 3 (c/count center)) (c/every? c/number? center)
                                        (c/number? radius) (c/pos? radius)
                                        (c/number? width) (c/pos? width))
                         (throw (c/ex-info "Invalid Blender wheel dimensions" {:part part})))
                       (c/into center [radius width])))
                   [:wheel-front-left :wheel-front-right :wheel-rear-left :wheel-rear-right])]
  (c/eval `(a/defconst ~'wheel-geometry
             [:array 4 [:array 5 :f32]] "Four rows: model-space axle X/Y/Z, rolling radius and tire width (metres)."
             ~rows)))

(a/defconst chassis-origin-z
  :f32 "Model-space height of the chassis collider centre; subtract when posing art."
  0.66)
