(ns racing-game.track-barriers
  "Blender-authored containment. The renderer and collider share one mesh."
  (:refer-clojure :only [])
  (:require [aguafria.std]
            [aguafria.keyword :as ak :refer :all]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [racing-game.physics :as physics]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.core :as c]))

(let [{:keys [units vertices triangles]} (edn/read-string
                                          (c/slurp (io/resource "geometry/barriers.edn")))]
  (when-not (c/and (c/= :metres units) (c/seq vertices) (c/seq triangles)
                   (c/every? #(c/and (c/= 3 (c/count %))
                                     (c/every? (c/fn [v] (c/and (c/number? v) (Double/isFinite (c/double v)))) %)) vertices)
                   (c/every? #(c/and (c/= 3 (c/count %))
                                     (c/every? (c/fn [i] (c/and (c/integer? i) (c/<= 0 i) (c/< i (c/count vertices)))) %)) triangles))
    (throw (c/ex-info "Invalid Blender containment export" {})))
  (c/eval `(a/defconst ~'vertices [:array ~(clojure.core/count vertices) [:array 3 :f32]] ~vertices))
  (c/eval `(a/defconst ~'triangles [:array ~(clojure.core/count triangles) [:array 3 :i32]] ~triangles))
  (c/eval `(a/defconst ~'vertex-count :usize ~(clojure.core/count vertices)))
  (c/eval `(a/defconst ~'triangle-count :usize ~(clojure.core/count triangles))))

(a/defn create! [:* b3/b3MeshData]
  "Attach static, finite-height physical walls. No position/velocity overrides.
  Owned mesh data must outlive the world and then be explicitly destroyed." [[world b3/b3WorldId]]
  (let [^:var points (mem/zeroes (a/type [:array vertex-count b3/b3Vec3]))
        ;; Box3D's weld/build API takes mutable indices; give it owned scratch
        ;; storage, never cast away const on the embedded Blender export.
        ^:var indices triangles
        ^:var definition (mem/zeroes (a/type b3/b3MeshDef))
        body-definition (b3/b3DefaultBodyDef)
        ^:var shape (b3/b3DefaultShapeDef)]
    (dotimes [i vertex-count]
      (let [v (a/index vertices i)]
        (= (a/index points i)
           (b3/b3Vec3 {:x (a/index v 0) :y (a/index v 1) :z (a/index v 2)}))))
    (= (a/field definition vertices) (ak/& points))
    (= (a/field definition indices) (ptrCast (ak/& indices)))
    (= (a/field definition vertexCount) (intCast vertex-count))
    (= (a/field definition triangleCount) (intCast triangle-count))
    (= (a/field definition weldVertices) true)
    (= (a/field definition weldTolerance) 0.001)
    (= (a/field definition identifyEdges) true)
    (= (a/field definition useMedianSplit) true)
    (= (a/field (a/field shape baseMaterial) friction) 0.45)
    (= (a/field (a/field shape baseMaterial) restitution) 0.05)
    (= (a/field shape enableHitEvents) true)
    (= (a/field (a/field shape filter) categoryBits) physics/solid-category)
    (let [data (a/unwrap (b3/b3CreateMesh (ak/& definition) null 0))
          body (b3/b3CreateBody world (ak/& body-definition))]
      (= :_ (b3/b3CreateMeshShape body (ak/& shape) data
                                  (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0})))
      data)))

(a/defn destroy! :void [[data [:* b3/b3MeshData]]]
  (b3/b3DestroyMesh data))
