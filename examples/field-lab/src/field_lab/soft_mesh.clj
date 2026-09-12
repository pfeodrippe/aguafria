(ns field-lab.soft-mesh
  "Generate a small tetrahedral sphere using ordinary Clojure data and functions."
  (:require [aguafria.zig :as az]
            [field-lab.physics :as p]
            [pitoco.geometry :as geometry]))

(def normalize geometry/normalize)

(def cross geometry/cross)

(def dot geometry/dot)

(def make-mesh geometry/make-seed-sphere)

(def mesh (make-mesh))

(defmacro emit-mesh!
  "Emit immutable typed Zig arrays from the generated Clojure mesh."
  []
  (let [{:keys [points triangles volumes volume mass-fractions edges lengths total-length]} mesh
        declarations [['points [:array (count points) 'p/Vec3]
                       (mapv (fn [[x y z]] (list 'p/Vec3 {:x x :y y :z z})) points)]
                      ['faces [:array (count triangles) [:array 3 :u32]] triangles]
                      ['rest-volumes [:array (count volumes) :f64] volumes]
                      ['unit-volume :f64 volume]
                      ['mass-fractions [:array (count points) :f64] mass-fractions]
                      ['edges [:array (count edges) [:array 2 :u32]] edges]
                      ['rest-lengths [:array (count lengths) :f64] lengths]
                      ['unit-edge-length :f64 total-length]]]
    (cons 'do (map (fn [[name type value]] (list 'az/defconst name type value)) declarations))))

(emit-mesh!)
