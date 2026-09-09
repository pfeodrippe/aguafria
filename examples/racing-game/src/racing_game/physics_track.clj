(ns racing-game.physics-track
  "Static collision surface sampled from the authored three-dimensional circuit.
  Mesh ownership is explicit: destroy the world's bodies before its mesh data."
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [racing-game.circuit :as circuit]
            [racing-game.track :as track]
            [racing-game.physics :as physics]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]))

(az/defconst segments :usize track/surface-segments)

(az/defn create!
  "Create one welded, edge-aware asphalt/shoulder mesh on a static body.
  Returns owned mesh data, which must outlive the world. Metres throughout."
  :- [:* b3/b3MeshData] [[world b3/b3WorldId]]
  (let [^:var vertices (mem/zeroes (az/type [:array (* (+ segments 1) track/surface-columns) b3/b3Vec3]))
        ^:var indices (mem/zeroes (az/type [:array (* segments 30) :i32]))
        ^:var materials (mem/zeroes (az/type [:array (* segments 10) :u8]))
        ^{:var :usize} triangles 0
        ^:var definition (mem/zeroes (az/type b3/b3MeshDef))
        ^:var body-definition (b3/b3DefaultBodyDef)
        ^:var shape (b3/b3DefaultShapeDef)
        ^:var surface-materials (mem/zeroes (az/type [:array 2 b3/b3SurfaceMaterial]))]
    (dotimes [i (+ segments 1)]
      (let [progress (/ (ak/as :f32 (ak/floatFromInt i))
                        (ak/as :f32 (ak/floatFromInt segments)))]
        (dotimes [lane track/surface-columns]
          (let [p (track/surface-point progress lane)]
            (set! (az/index vertices (+ (* i track/surface-columns) lane))
                  (b3/b3Vec3 {:x (az/field p x) :y (az/field p y) :z (az/field p z)}))))))
    (dotimes [i segments]
      (dotimes [lane (- track/surface-columns 1)]
        (let [a (ak/as :i32 (ak/intCast (+ (* i track/surface-columns) lane)))
              stride (ak/as :i32 (ak/intCast track/surface-columns))
              pa (/ (ak/as :f32 (ak/floatFromInt i)) (ak/as :f32 (ak/floatFromInt segments)))
              pb (/ (ak/as :f32 (ak/floatFromInt (+ i 1))) (ak/as :f32 (ak/floatFromInt segments)))
              material (if (track/surface-asphalt? lane) (ak/as :u8 1) (ak/as :u8 0))]
          ;; At a taper, one half of a quad can legitimately collapse. Do not
          ;; feed zero-area triangles to Box3D or overlay pit/road meshes.
          (when (> (- (track/surface-boundary pb (+ lane 1)) (track/surface-boundary pb lane)) 0.001)
            (set! (az/index indices (* triangles 3)) a)
            (set! (az/index indices (+ (* triangles 3) 1)) (+ a stride))
            (set! (az/index indices (+ (* triangles 3) 2)) (+ a stride 1))
            (set! (az/index materials triangles) material)
            (set! triangles (+ triangles 1)))
          (when (> (- (track/surface-boundary pa (+ lane 1)) (track/surface-boundary pa lane)) 0.001)
            (set! (az/index indices (* triangles 3)) a)
            (set! (az/index indices (+ (* triangles 3) 1)) (+ a stride 1))
            (set! (az/index indices (+ (* triangles 3) 2)) (+ a 1))
            (set! (az/index materials triangles) material)
            (set! triangles (+ triangles 1))))))
    (set! (az/field definition vertices) (ak/& vertices))
    (set! (az/field definition indices) (ak/& indices))
    (set! (az/field definition materialIndices) (ak/& materials))
    (set! (az/field definition vertexCount) (ak/intCast (az/field vertices len)))
    (set! (az/field definition triangleCount) (ak/intCast triangles))
    (set! (az/field definition weldVertices) true)
    (set! (az/field definition weldTolerance) 0.001)
    (set! (az/field definition identifyEdges) true)
    (set! (az/field definition useMedianSplit) true)
    (set! (az/field (az/index surface-materials 0) friction) 0.4)
    (set! (az/field (az/index surface-materials 1) friction) 1.0)
    (set! (az/field shape materials) (ak/& surface-materials))
    (set! (az/field shape materialCount) 2)
    (let [mesh (az/unwrap (b3/b3CreateMesh (ak/& definition) ak/null 0))
          body (b3/b3CreateBody world (ak/& body-definition))]
      (set! _ (b3/b3CreateMeshShape body (ak/& shape) mesh (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0})))
      (physics/mark-tire-surface! body)
      mesh)))

(az/defn destroy! :- :void [[mesh [:* b3/b3MeshData]]]
  (b3/b3DestroyMesh mesh))
