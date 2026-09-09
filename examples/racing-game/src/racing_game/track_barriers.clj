(ns racing-game.track-barriers
  "Blender-authored containment. The renderer and collider share one mesh."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
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
  (eval `(az/defconst ~'vertices [:array ~(count vertices) [:array 3 :f32]] ~vertices))
  (eval `(az/defconst ~'triangles [:array ~(count triangles) [:array 3 :i32]] ~triangles))
  (eval `(az/defconst ~'vertex-count :usize ~(count vertices)))
  (eval `(az/defconst ~'triangle-count :usize ~(count triangles))))

(az/defn create!
  "Attach static, finite-height physical walls. No position/velocity overrides.
  Owned mesh data must outlive the world and then be explicitly destroyed."
  :- [:* b3/b3MeshData] [[world b3/b3WorldId]]
  (let [^:var points (mem/zeroes (az/type [:array vertex-count b3/b3Vec3]))
        ;; Box3D's weld/build API takes mutable indices; give it owned scratch
        ;; storage, never cast away const on the embedded Blender export.
        ^:var indices triangles
        ^:var definition (mem/zeroes (az/type b3/b3MeshDef))
        body-definition (b3/b3DefaultBodyDef)
        ^:var shape (b3/b3DefaultShapeDef)]
    (dotimes [i vertex-count]
      (let [v (az/index vertices i)]
        (set! (az/index points i)
              (b3/b3Vec3 {:x (az/index v 0) :y (az/index v 1) :z (az/index v 2)}))))
    (set! (az/field definition vertices) (ak/& points))
    (set! (az/field definition indices) (ak/ptrCast (ak/& indices)))
    (set! (az/field definition vertexCount) (ak/intCast vertex-count))
    (set! (az/field definition triangleCount) (ak/intCast triangle-count))
    (set! (az/field definition weldVertices) true)
    (set! (az/field definition weldTolerance) 0.001)
    (set! (az/field definition identifyEdges) true)
    (set! (az/field definition useMedianSplit) true)
    (set! (az/field (az/field shape baseMaterial) friction) 0.45)
    (set! (az/field (az/field shape baseMaterial) restitution) 0.05)
    (set! (az/field shape enableHitEvents) true)
    (set! (az/field (az/field shape filter) categoryBits) physics/solid-category)
    (let [data (az/unwrap (b3/b3CreateMesh (ak/& definition) ak/null 0))
          body (b3/b3CreateBody world (ak/& body-definition))]
      (set! _ (b3/b3CreateMeshShape body (ak/& shape) data
                  (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0})))
      data)))

(az/defn destroy! :- :void [[data [:* b3/b3MeshData]]]
  (b3/b3DestroyMesh data))
