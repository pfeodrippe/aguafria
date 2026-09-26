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
            [field-lab.embedding :as embedding]
            [field-lab.contact-mesh :as contact]
            [field-lab.scene :as scene]))

(az/defvar framing-enabled :bool false)


(az/defvar embedded-enabled :bool false)

(az/defvar embedded-request :u8 0)

(az/defvar embeddings [:array 3 [:optional [:* embedding/Render]]] [null null null])

(az/defvar embedding-attempted [:array 3 :bool] [false false false])

(az/defvar embedding-revision :u32 0)

(az/defvar embedded-count :u32 0)

(az/defvar embedded-linear :u32 0)

(az/defvar embedded-rejected :u32 0)

(az/defvar stream-overflow :bool false)

(az/defvar maximum-inset :f64 0.0)

(az/defvar visibility-surfaces [:array 3 [:optional [:* contact/Surface]]] [null null null])

(az/defvar visibility-output [:c-pointer :u32] null)

(az/defvar visibility-capacity :usize 0)

(az/defvar visibility-tick :u32 0xffffffff)

(az/defvar visibility-modes :u8 0)

(az/defvar visibility-ready :bool false)

(az/defn request-preview! :void
  "Queue a display-only change on the render thread; never rebakes the source."
  [[enabled :bool]]
  (ak/atomicStore :u8 (ak/& embedded-request) (if enabled 2 1) :.release))

(az/defn clear-embeddings! :void
  []
  (dotimes [index 3]
    (when (ak/!= (az/index embeddings index) null)
      (embedding/destroy! (az/unwrap (az/index embeddings index))))
    (az/set-many!
      (az/index embeddings index) null
      (az/index embedding-attempted index) false))
  (dotimes [index 3]
    (when (ak/!= (az/index visibility-surfaces index) null)
      (contact/destroy! (az/unwrap (az/index visibility-surfaces index)))
      (set! (az/index visibility-surfaces index) null)))
  (az/set-many! embedded-count 0 embedded-linear 0 embedded-rejected 0 maximum-inset 0.0
                visibility-tick 0xffffffff visibility-ready false))

(az/defn prepare-embedding! [:optional [:* embedding/Render]]
  [[body :usize] [owned [:optional [:* cache/Cache]]]]
  (when (or (ak/! embedded-enabled) (ak/== owned null)) (ak/return null))
  (when (ak/! (az/index embedding-attempted body))
    (az/set-many!
      (az/index embedding-attempted body) true
      (az/index embeddings body) (embedding/inscribed-sphere! (az/unwrap owned))))
  (when (ak/== (az/index embeddings body) null) (ak/return null))
  (let [render (az/unwrap (az/index embeddings body))
        scripted (scene/scripted-scene)
        floor (if (ak/== scripted null) true (az/index (az/field (az/unwrap scripted) floor) body))
        method (embedding/update-for-floor! render (az/unwrap owned) scene/cursor floor)]
    (when (ak/== method 0)
      ;; Reject the display frame instead of pushing render points above the floor.
      (ak/+= embedded-rejected 1)
      (ak/return null))
    (when (ak/== method 2) (ak/+= embedded-linear 1))
    (az/set-many!
      embedded-count (+ embedded-count 1)
      maximum-inset (ak/max maximum-inset (az/field render reference-inset)))
    render))

(az/defn surface-face [:array 3 :u32]
  [[owned [:optional [:* cache/Cache]]] [render [:optional [:* embedding/Render]]] [index :usize]]
  (if (ak/!= render null)
    (az/index (az/field (az/unwrap render) faces) index)
    (if (ak/!= owned null) (az/index (az/field (az/unwrap owned) faces) index)
        (az/index mesh/faces index))))

(az/defn surface-point p/Vec3
  [[owned [:optional [:* cache/Cache]]] [render [:optional [:* embedding/Render]]]
   [body :usize] [index :usize]]
  (if (ak/!= render null)
    (az/index (az/field (az/unwrap render) positions) index)
    (if (ak/!= owned null) (cache/position (az/unwrap owned) scene/cursor index)
        (az/index (az/field (scene/soft-state (ak/intCast body)) positions) index))))

(az/defn set-visibility-target! :void
  "Borrow the renderer's completed-frame storage; invalidate publication until
  the selected surface stream has passed capacity and geometry checks."
  [[output [:c-pointer :u32]] [capacity :usize]]
  (when (or (ak/!= output visibility-output) (ak/!= capacity visibility-capacity))
    (set! visibility-tick 0xffffffff))
  (az/set-many! visibility-output output visibility-capacity capacity visibility-ready false)
  (when (and (ak/!= output null) (> capacity 0)) (set! (az/index output 0) 0)))

(az/defn prepare-visibility! [:optional [:* contact/Surface]]
  [[body :usize] [owned [:optional [:* cache/Cache]]] [render [:optional [:* embedding/Render]]]]
  (let [nodes (if (ak/!= render null) (az/field (az/field (az/unwrap render) positions) len)
                  (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) reference) len)
                      soft/particle-count))
        faces (if (ak/!= render null) (az/field (az/field (az/unwrap render) faces) len)
                  (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) faces) len)
                      soft/face-count))
        fresh (ak/== (az/index visibility-surfaces body) null)]
    (when fresh (set! (az/index visibility-surfaces body) (contact/create! nodes faces)))
    (let [result (az/unwrap (az/index visibility-surfaces body))]
      (dotimes [node nodes]
        (contact/set-point! result node (surface-point owned render body node)))
      (if fresh
        (do
          (dotimes [index faces]
            (let [face (surface-face owned render index)]
              (contact/set-face! result index (az/index face 0) (az/index face 1) (az/index face 2))))
          (when (ak/! (contact/build-hierarchy! result))
            (contact/destroy! result)
            (set! (az/index visibility-surfaces body) null)
            (ak/return null)))
        (contact/refit! result))
      result)))

(az/defn publish-visibility! :void
  [[active [:array 3 [:optional [:* embedding/Render]]]]]
  (when (or (ak/== visibility-output null) (< visibility-capacity 16)) (ak/return))
  (let [^:var modes (ak/u8 0)]
    (dotimes [body scene/body-count]
      (when (ak/!= (az/index active body) null)
        (set! modes (ak/| modes (ak/<< (ak/as 1 :u8) (ak/intCast body))))))
    (when (ak/!= modes visibility-modes)
      (dotimes [body 3]
        (when (ak/!= (az/index visibility-surfaces body) null)
          (contact/destroy! (az/unwrap (az/index visibility-surfaces body)))
          (set! (az/index visibility-surfaces body) null)))
      (az/set-many! visibility-modes modes visibility-tick 0xffffffff))
    (when (and (ak/== visibility-tick scene/cursor)
               (ak/== (az/index visibility-output 1) scene/body-count))
      (az/set-many! (az/index visibility-output 0) 0x5049544f visibility-ready true)
      (ak/return))
    ;; Failed partial publication must not retain the previous cache key.
    (set! visibility-tick 0xffffffff)
    (let [^:var written (ak/usize 16)]
      (dotimes [body scene/body-count]
        (let [owned (scene/mesh-cache-at body)
              hierarchy (prepare-visibility! body owned (az/index active body))]
          (when (ak/== hierarchy null) (ak/return))
          (let [words (contact/pack-hierarchy! (az/unwrap hierarchy)
                         (ak/& (az/index visibility-output written)) (- visibility-capacity written))]
            (when (ak/== words 0) (ak/return))
            (az/set-many!
              (az/index visibility-output (+ 4 body)) (ak/intCast written)
              written (+ written words)))))
      (az/set-many!
        (az/index visibility-output 1) (ak/intCast scene/body-count)
        (az/index visibility-output 2) scene/revision
        (az/index visibility-output 3) (ak/intCast scene/cursor)
        (az/index visibility-output 7) (ak/intCast written)
        (az/index visibility-output 0) 0x5049544f
        visibility-tick scene/cursor
        visibility-ready true))))

(az/defn camera-target p/Vec3
  []
  (let [^:var result (p/v 0.0 0.0 0.0)]
    (dotimes [i scene/body-count]
      (set! result (p/add result (az/field (scene/body-state (ak/intCast i)) position))))
    (set! result (p/scale result (/ 1.0 (ak/as (ak/floatFromInt scene/body-count) :f64))))
    (set! (az/field result y) (if framing-enabled (ak/max 0.05 (az/field result y)) 1.55))
    result))

(az/defn camera-distance :f64
  [[minimum :f64]]
  (let [target (camera-target)
        radius (az/field (scene/config) radius)
        ^:var extent (ak/f64 0.0)]
    (dotimes [i scene/body-count]
      (let [position (az/field (scene/body-state (ak/intCast i)) position)
            owned (scene/mesh-cache-at i)
            body-radius (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) config) radius) radius)]
        (if (and framing-enabled (ak/!= owned null))
          (let [item (az/unwrap owned)]
            (dotimes [node (az/field (az/field item reference) len)]
              (set! extent (ak/max extent
                                  (p/length (p/add (cache/position item scene/cursor node)
                                                   (p/scale target -1.0)))))))
          (set! extent (ak/max extent (+ body-radius (p/length (p/add position (p/scale target -1.0)))))))))
    (ak/max minimum (+ (if framing-enabled (ak/as 0.0 :f64) (ak/as 1.0 :f64))
                       (* 2.6 extent)))))

(az/defn emit-bounded! :u32
  "Preflight the complete stream before writing into caller-owned storage."
  [[output [:c-pointer gpu/GpuVertex]] [capacity :usize] [yaw :f64] [pitch :f64] [distance :f64]]
  (let [request (ak/atomicRmw :u8 (ak/& embedded-request) :.Xchg 0 :.acq_rel)]
    (when (ak/!= request 0) (set! embedded-enabled (ak/== request 2))))
  (when (ak/!= embedding-revision scene/revision)
    (clear-embeddings!)
    (set! embedding-revision scene/revision))
  (az/set-many! embedded-count 0 embedded-linear 0 embedded-rejected 0 maximum-inset 0.0 stream-overflow false)
  (let [^:var active (az/init [null null null] [:array 3 [:optional [:* embedding/Render]]])
        ^:var required (ak/usize 0)]
    (dotimes [body scene/body-count]
      (let [owned (scene/mesh-cache-at body)
            render (prepare-embedding! body owned)
            faces (if (ak/!= render null) (az/field (az/field (az/unwrap render) faces) len)
                      (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) faces) len) soft/face-count))]
        (az/set-many! (az/index active body) render required (+ required (* 6 faces)))))
    (when (> required capacity)
      (set! stream-overflow true)
      (ak/return 0))
    (publish-visibility! active)
  (let [target (camera-target)
        backward
        (p/v (* (ak/sin yaw) (ak/cos pitch)) (ak/sin pitch) (* (ak/cos yaw) (ak/cos pitch)))
        eye (p/add target (p/scale backward distance))
        forward (p/scale backward -1.0)
        right (p/v (ak/cos yaw) 0.0 (- (ak/sin yaw)))
        up (p/cross right forward)
        ^:var written (ak/u32 0)]
    (dotimes [body-index scene/body-count]
      (let [owned (scene/mesh-cache-at body-index)
            render (az/index active body-index)
            ^:var local-normals (mem/zeroes (az/type [:array 43 p/Vec3]))
            normals
            (ak/as (if (ak/!= render null) (az/field (az/field (az/unwrap render) normals) ptr)
                (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) normals) ptr)
                    (ak/& (az/index local-normals 0)))) [:c-pointer p/Vec3])
            nodes (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) reference) len) soft/particle-count)
            faces (if (ak/!= render null) (az/field (az/field (az/unwrap render) faces) len)
                      (if (ak/!= owned null) (az/field (az/field (az/unwrap owned) faces) len) soft/face-count))]
        (when (ak/== render null)
          (dotimes [node nodes] (set! (az/index normals node) (p/v 0.0 0.0 0.0)))
          (dotimes [face-index faces]
            (let [face (surface-face owned render face-index)
                  a (surface-point owned render body-index (az/index face 0))
                  b (surface-point owned render body-index (az/index face 1))
                  c (surface-point owned render body-index (az/index face 2))
                  normal (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))]
              (dotimes [j 3]
                (let [index (az/index face j)]
                  (set! (az/index normals index) (p/add (az/index normals index) normal)))))))
        (dotimes [pass 2]
          (dotimes [face-index faces]
            (dotimes [j 3]
              (let [face (surface-face owned render face-index)
                    index (az/index face j)
                    measured (surface-point owned render body-index index)
                    projection (/ 11.998 (ak/max 0.001 (- 12.0 (az/field measured y))))
                    position (if (ak/== pass 0)
                               measured
                               (p/v (+ -3.0 (* (+ (az/field measured x) 3.0) projection))
                                    0.002
                                    (+ 4.0 (* (- (az/field measured z) 4.0) projection))))
                    normal (az/index normals index)
                    unit (p/scale normal (/ 1.0 (ak/max 1.0e-12 (p/length normal))))
                    local (if (or (ak/!= render null) (ak/!= (scene/scripted-scene) null)) (p/v 1.0 1.0 1.0)
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
    written)))

(az/defn emit! :u32
  "Compatibility entry for the shared renderer's fixed 524,288-vertex stream.
  Other callers must supply their actual allocation size to emit-bounded!."
  [[output [:c-pointer gpu/GpuVertex]] [yaw :f64] [pitch :f64] [distance :f64]]
  (emit-bounded! output 524288 yaw pitch distance))
