(ns field-lab.surface
  "Stream the measured deformable surface into the shared Vulkan mesh buffer."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria-examples-native.mesh :as gpu]
            [field-lab.physics :as p]
            [field-lab.soft-body :as soft]
            [field-lab.soft-mesh :as mesh]
            [field-lab.mesh-cache :as cache]
            [field-lab.scene :as scene]))

(az/defn camera-target
  :- p/Vec3
  []
  (let [^:var result (p/v 0.0 0.0 0.0)]
    (dotimes [i scene/body-count]
      (set! result (p/add result (az/field (scene/body-state (ak/intCast i)) position))))
    (set! result (p/scale result (/ 1.0 (ak/as :f64 (ak/floatFromInt scene/body-count)))))
    (set! (az/field result y) 1.55)
    result))

(az/defn camera-distance
  :- :f64
  [[minimum :f64]]
  (let [target (camera-target)
        radius (az/field (scene/config) radius)
        ^{:var :f64} extent 0.0]
    (dotimes [i scene/body-count]
      (let [position (az/field (scene/body-state (ak/intCast i)) position)
            owned (scene/mesh-cache-at i)
            body-radius (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) config) radius) radius)]
        (set! extent (ak/max extent (+ body-radius (p/length (p/add position (p/scale target -1.0))))))))
    (ak/max minimum (+ 1.0 (* 2.6 extent)))))

(az/defn emit!
  :- :u32
  [[output [:c-pointer gpu/GpuVertex]] [yaw :f64] [pitch :f64] [distance :f64]]
  (let [target (camera-target)
        backward
        (p/v (* (ak/sin yaw) (ak/cos pitch)) (ak/sin pitch) (* (ak/cos yaw) (ak/cos pitch)))
        eye (p/add target (p/scale backward distance))
        forward (p/scale backward -1.0)
        right (p/v (ak/cos yaw) 0.0 (- (ak/sin yaw)))
        up (p/cross right forward)
        ^{:var :u32} written 0]
    (dotimes [body-index scene/body-count]
      (let [owned (scene/mesh-cache-at body-index)
            body (scene/soft-state (ak/intCast body-index))
            ^:var local-normals (mem/zeroes (az/type [:array 43 p/Vec3]))
            ^{:zig/type [:c-pointer p/Vec3]} normals
            (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) normals) ptr)
                (ak/& (az/index local-normals 0)))
            nodes (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) reference) len) soft/particle-count)
            faces (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) faces) len) soft/face-count)]
        (dotimes [node nodes] (set! (az/index normals node) (p/v 0.0 0.0 0.0)))
        (dotimes [face-index faces]
          (let [face (if (ak/!= owned null) (az/index (az/field (az/unwrap owned) faces) face-index)
                         (az/index mesh/faces face-index))
                a (if (ak/!= owned null) (cache/position (az/unwrap owned) scene/cursor (az/index face 0))
                      (az/index (az/field body positions) (az/index face 0)))
                b (if (ak/!= owned null) (cache/position (az/unwrap owned) scene/cursor (az/index face 1))
                      (az/index (az/field body positions) (az/index face 1)))
                c (if (ak/!= owned null) (cache/position (az/unwrap owned) scene/cursor (az/index face 2))
                      (az/index (az/field body positions) (az/index face 2)))
                normal (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))]
            (dotimes [j 3]
              (let [index (az/index face j)]
                (set! (az/index normals index) (p/add (az/index normals index) normal))))))
        (dotimes [pass 2]
          (dotimes [face-index faces]
            (dotimes [j 3]
              (let [face (if (ak/!= owned null) (az/index (az/field (az/unwrap owned) faces) face-index)
                             (az/index mesh/faces face-index))
                    index (az/index face j)
                    measured (if (ak/!= owned null) (cache/position (az/unwrap owned) scene/cursor index)
                                 (az/index (az/field body positions) index))
                    projection (/ 11.998 (ak/max 0.001 (- 12.0 (az/field measured y))))
                    position (if (ak/== pass 0)
                               measured
                               (p/v (+ -3.0 (* (+ (az/field measured x) 3.0) projection))
                                    0.002
                                    (+ 4.0 (* (- (az/field measured z) 4.0) projection))))
                    normal (az/index normals index)
                    unit (p/scale normal (/ 1.0 (ak/max 1.0e-12 (p/length normal))))
                    local (if (ak/!= (scene/scripted-scene) null) (p/v 1.0 1.0 1.0)
                            (if (ak/!= owned null)
                            (p/scale (p/add (az/index (az/field (az/unwrap owned) reference) index)
                                             (p/scale (az/field (az/field (cache/frame-info (az/unwrap owned) 0) observation) center) -1.0))
                                     (/ 1.0 (az/field (scene/config) radius)))
                            (az/index mesh/points index)))
                    view (p/add position (p/scale eye -1.0))
                    depth (ak/max 0.01 (p/dot view forward))
                    film-x (/ (* 2.1 (p/dot view right)) depth)
                    film-y (/ (* 2.1 (p/dot view up)) depth)
                    pixel-x (+ 225.0 375.0 (* film-x 264.0))
                    pixel-y (+ 66.0 264.0 (* (- film-y) 264.0))]
                (set! (az/index output written)
                      (gpu/GpuVertex
                       {:x (ak/floatCast (- (/ pixel-x 640.0) 1.0))
                        :y (ak/floatCast (- (/ pixel-y 410.0) 1.0))
                        :z (ak/floatCast (- 1.0 (/ 0.05 depth)))
                        :r (if (ak/== pass 1) -1.0 (if (ak/== body-index 0) 0.78 (if (ak/== body-index 1) 0.035 0.37)))
                        :g (if (ak/== body-index 0) 0.27 (if (ak/== body-index 1) 0.48 0.14))
                        :b (if (ak/== body-index 0) 0.045 (if (ak/== body-index 1) 0.43 0.68))
                        :nx (ak/floatCast (az/field unit x))
                        :ny (ak/floatCast (az/field unit y))
                        :nz (ak/floatCast (az/field unit z))
                        :wx (ak/floatCast (az/field position x))
                        :wy (ak/floatCast (az/field position y))
                        :wz (ak/floatCast (az/field position z))
                        :roughness (ak/floatCast depth)
                        :vx (ak/floatCast (az/field local x))
                        :vy (ak/floatCast (az/field local y))
                        :vz (ak/floatCast (az/field local z))}))
                (set! written (+ written 1))))))))
    written))
