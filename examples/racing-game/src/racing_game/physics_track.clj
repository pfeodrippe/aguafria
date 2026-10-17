(ns racing-game.physics-track
  "Static collision surface sampled from the authored three-dimensional circuit.\n  Mesh ownership is explicit: destroy the world's bodies before its mesh data."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [racing-game.circuit :as circuit]
            [racing-game.track :as track]
            [racing-game.physics :as physics]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]))

(a/defconst segments :usize track/surface-segments)

(a/defn create! [:* b3/b3MeshData]
  "Create one welded, edge-aware asphalt/shoulder mesh on a static body.
  Returns owned mesh data, which must outlive the world. Metres throughout." [[world b3/b3WorldId]]
  (let [^:var vertices (mem/zeroes (a/type [:array (* (+ segments 1) track/surface-columns) b3/b3Vec3]))
        ^:var indices (mem/zeroes (a/type [:array (* segments 30) :i32]))
        ^:var materials (mem/zeroes (a/type [:array (* segments 10) :u8]))
        ^:var triangles (usize 0)
        ^:var definition (mem/zeroes (a/type b3/b3MeshDef))
        ^:var body-definition (b3/b3DefaultBodyDef)
        ^:var shape (b3/b3DefaultShapeDef)
        ^:var surface-materials (mem/zeroes (a/type [:array 2 b3/b3SurfaceMaterial]))]
    (dotimes [i (+ segments 1)]
      (let [progress (/ (as (floatFromInt i) :f32)
                        (as (floatFromInt segments) :f32))]
        (dotimes [lane track/surface-columns]
          (let [p (track/surface-point progress lane)]
            (= (a/index vertices (+ (* i track/surface-columns) lane))
               (b3/b3Vec3 {:x (a/field p x) :y (a/field p y) :z (a/field p z)}))))))
    (dotimes [i segments]
      (dotimes [lane (- track/surface-columns 1)]
        (let [a (as (intCast (+ (* i track/surface-columns) lane)) :i32)
              stride (as (intCast track/surface-columns) :i32)
              pa (/ (as (floatFromInt i) :f32) (as (floatFromInt segments) :f32))
              pb (/ (as (floatFromInt (+ i 1)) :f32) (as (floatFromInt segments) :f32))
              material (if (track/surface-asphalt? lane) (as 1 :u8) (as 0 :u8))]
          ;; At a taper, one half of a quad can legitimately collapse. Do not
          ;; feed zero-area triangles to Box3D or overlay pit/road meshes.
          (when (> (- (track/surface-boundary pb (+ lane 1)) (track/surface-boundary pb lane)) 0.001)
            (= (a/index indices (* triangles 3)) a)
            (= (a/index indices (+ (* triangles 3) 1)) (+ a stride))
            (= (a/index indices (+ (* triangles 3) 2)) (+ a stride 1))
            (= (a/index materials triangles) material)
            (= triangles (+ triangles 1)))
          (when (> (- (track/surface-boundary pa (+ lane 1)) (track/surface-boundary pa lane)) 0.001)
            (= (a/index indices (* triangles 3)) a)
            (= (a/index indices (+ (* triangles 3) 1)) (+ a stride 1))
            (= (a/index indices (+ (* triangles 3) 2)) (+ a 1))
            (= (a/index materials triangles) material)
            (= triangles (+ triangles 1))))))
    (= (a/field definition vertices) (ak/& vertices))
    (= (a/field definition indices) (ak/& indices))
    (= (a/field definition materialIndices) (ak/& materials))
    (= (a/field definition vertexCount) (intCast (a/field vertices len)))
    (= (a/field definition triangleCount) (intCast triangles))
    (= (a/field definition weldVertices) true)
    (= (a/field definition weldTolerance) 0.001)
    (= (a/field definition identifyEdges) true)
    (= (a/field definition useMedianSplit) true)
    (= (a/field (a/index surface-materials 0) friction) 0.4)
    (= (a/field (a/index surface-materials 1) friction) 1.0)
    (= (a/field shape materials) (ak/& surface-materials))
    (= (a/field shape materialCount) 2)
    (let [mesh (a/unwrap (b3/b3CreateMesh (ak/& definition) null 0))
          body (b3/b3CreateBody world (ak/& body-definition))]
      (= :_ (b3/b3CreateMeshShape body (ak/& shape) mesh (b3/b3Vec3 {:x 1.0 :y 1.0 :z 1.0})))
      (physics/mark-tire-surface! body)
      mesh)))

(a/defn destroy! :void [[mesh [:* b3/b3MeshData]]]
  (b3/b3DestroyMesh mesh))
