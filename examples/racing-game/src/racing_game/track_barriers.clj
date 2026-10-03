(ns racing-game.track-barriers
  "Blender-authored containment. The renderer and collider share one mesh."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [racing-game.physics :as physics]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(let [{:keys [units vertices triangles]} (edn/read-string
                                          (slurp (io/resource "geometry/barriers.edn")))]
  (when-not (and (= :metres units) (seq vertices) (seq triangles)
                 (every? #(and (= 3 (count %))
                                (every? (fn [v] (and (number? v) (Double/isFinite (double v)))) %)) vertices)
                 (every? #(and (= 3 (count %))
                                (every? (fn [i] (and (integer? i) (<= 0 i) (< i (count vertices)))) %)) triangles))
    (throw (ex-info "Invalid Blender containment export" {})))
  (eval `(a/defconst ~'vertices [:array ~(count vertices) [:array 3 :f32]] ~vertices))
  (eval `(a/defconst ~'triangles [:array ~(count triangles) [:array 3 :i32]] ~triangles))
  (eval `(a/defconst ~'vertex-count :usize ~(count vertices)))
  (eval `(a/defconst ~'triangle-count :usize ~(count triangles))))

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
        (ak/= (a/index points i)
              (b3/b3Vec3 {:x (a/index v 0) :y (a/index v 1) :z (a/index v 2)}))))
    (ak/= (a/field definition vertices) (ak/& points))
    (ak/= (a/field definition indices) (ak/ptrCast (ak/& indices)))
    (ak/= (a/field definition vertexCount) (ak/intCast vertex-count))
    (ak/= (a/field definition triangleCount) (ak/intCast triangle-count))
    (ak/= (a/field definition weldVertices) true)
    (ak/= (a/field definition weldTolerance) 0.001)
    (ak/= (a/field definition identifyEdges) true)
    (ak/= (a/field definition useMedianSplit) true)
    (ak/= (a/field (a/field shape baseMaterial) friction) 0.45)
    (ak/= (a/field (a/field shape baseMaterial) restitution) 0.05)
    (ak/= (a/field shape enableHitEvents) true)
    (ak/= (a/field (a/field shape filter) categoryBits) physics/solid-category)
    (let [data (a/unwrap (b3/b3CreateMesh (ak/& definition) ak/null 0))
          body (b3/b3CreateBody world (ak/& body-definition))]
      (ak/= :_ (b3/b3CreateMeshShape body (ak/& shape) data
                  (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0})))
      data)))

(a/defn destroy! :void [[data [:* b3/b3MeshData]]]
  (b3/b3DestroyMesh data))
