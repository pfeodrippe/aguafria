(ns field-lab.mesh-cache
  "Owned, mesh-sized FEM playback buffers. Build privately, then publish once."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [field-lab.physics :as p]
            [field-lab.fem :as fem]
            [field-lab.nonlinear-fem :as dynamics]))

(az/defstruct Frame {:layout :extern}
  [[:observation dynamics/Observables] [:time :f64] [:height :f64]
   [:volume-ratio :f64]])

(az/defstruct Cache
  [[:reference [:slice p/Vec3]] [:cells [:slice [:array 4 :u32]]]
   [:faces [:slice [:array 3 :u32]]] [:normals [:slice p/Vec3]]
   [:positions [:slice p/Vec3]] [:velocities [:slice p/Vec3]]
   [:frames [:slice Frame]] [:count :u32]
   [:config p/Config] [:young :f64] [:poisson :f64]])

(az/defn create!
  :- [:* Cache]
  [[nodes :usize] [cells :usize] [faces :usize] [frames :usize]
   [config p/Config] [young :f64] [poisson :f64]]
  ;; Limit storage before multiplying dimensions or allocating native memory.
  (debug/assert (and (> nodes 0) (<= nodes 20000) (> cells 0) (<= cells 160000)
                     (> faces 0) (<= faces 80000) (> frames 0) (<= frames 14401)
                     (<= (* nodes frames) 8000000)))
  (let [cache (catch ((az/field heap/page_allocator create) Cache)
                (debug/panic "Unable to allocate FEM playback cache" []))]
    (set! (az/deref cache)
          (Cache {:reference (fem/allocate p/Vec3 nodes)
                  :cells (fem/allocate (az/type [:array 4 :u32]) cells)
                  :faces (fem/allocate (az/type [:array 3 :u32]) faces)
                  :normals (fem/allocate p/Vec3 nodes)
                  :positions (fem/allocate p/Vec3 (* nodes frames))
                  :velocities (fem/allocate p/Vec3 (* nodes frames))
                  :frames (fem/allocate Frame frames) :count 0
                  :config config :young young :poisson poisson}))
    cache))

(az/defn destroy!
  :- :void
  [[cache [:* Cache]]]
  ((az/field heap/page_allocator free) (az/field cache reference))
  ((az/field heap/page_allocator free) (az/field cache cells))
  ((az/field heap/page_allocator free) (az/field cache faces))
  ((az/field heap/page_allocator free) (az/field cache normals))
  ((az/field heap/page_allocator free) (az/field cache positions))
  ((az/field heap/page_allocator free) (az/field cache velocities))
  ((az/field heap/page_allocator free) (az/field cache frames))
  ((az/field heap/page_allocator destroy) cache))

(az/defn set-face!
  :- :void
  [[cache [:* Cache]] [index :usize] [a :u32] [b :u32] [c :u32]]
  (set! (az/index (az/field cache faces) index) (az/array-init [:array 3 :u32] [a b c])))

(az/defn record!
  "Copy an accepted numerical state; no playback tick aliases the live solver."
  :- :void
  [[cache [:* Cache]] [state [:* dynamics/Dynamics]] [time :f64]]
  (let [model (az/field state mesh)
        nodes (az/field (az/field cache reference) len)
        tick (az/field cache count)
        observation (dynamics/evaluate! state)
        ^{:var :f64} lower 1.0e30
        ^{:var :f64} upper -1.0e30
        ^{:var :f64} rest-volume 0.0
        ^{:var :f64} volume 0.0]
    (debug/assert (< tick (az/field (az/field cache frames) len)))
    (debug/assert (ak/== nodes (az/field (az/field model positions) len)))
    (dotimes [node nodes]
      (let [point (dynamics/position state node)
            offset (+ (* tick nodes) node)]
        (az/set-many!
          (az/index (az/field cache positions) offset) point
          (az/index (az/field cache velocities) offset) (dynamics/particle-velocity state node)
          lower (ak/min lower (az/field point y))
          upper (ak/max upper (az/field point y)))
        (when (ak/== tick 0)
          (set! (az/index (az/field cache reference) node)
                (az/index (az/field model positions) node)))))
    (dotimes [index (az/field (az/field model elements) len)]
      (let [element (az/index (az/field model elements) index)
            a (dynamics/position state (az/index (az/field element nodes) 0))
            b (p/add (dynamics/position state (az/index (az/field element nodes) 1)) (p/scale a -1.0))
            c (p/add (dynamics/position state (az/index (az/field element nodes) 2)) (p/scale a -1.0))
            d (p/add (dynamics/position state (az/index (az/field element nodes) 3)) (p/scale a -1.0))]
        (ak/+= rest-volume (az/field element volume))
        (ak/+= volume (/ (ak/abs (p/dot b (p/cross c d))) 6.0))
        (when (ak/== tick 0)
          (dotimes [local 4]
            (set! (az/index (az/index (az/field cache cells) index) local)
                  (ak/intCast (az/index (az/field element nodes) local)))))))
    (az/set-many!
      (az/index (az/field cache frames) tick)
      (Frame {:observation observation :time time :height (- upper lower)
              :volume-ratio (/ volume rest-volume)})
      (az/field cache count) (+ tick 1))))

(az/defn position
  :- p/Vec3
  [[cache [:* Cache]] [tick :u32] [node :usize]]
  (az/index (az/field cache positions) (+ (* tick (az/field (az/field cache reference) len)) node)))

(az/defn velocity
  :- p/Vec3
  [[cache [:* Cache]] [tick :u32] [node :usize]]
  (az/index (az/field cache velocities) (+ (* tick (az/field (az/field cache reference) len)) node)))

(az/defn frame-info
  :- Frame
  [[cache [:* Cache]] [index :u32]]
  (az/index (az/field cache frames) index))

(az/defn reference-height
  "Reference y extent; a height change is not a material strain tensor."
  :- :f64
  [[owned [:* Cache]]]
  (let [^{:var :f64} lower 1.0e30
        ^{:var :f64} upper -1.0e30]
    (dotimes [node (az/field (az/field owned reference) len)]
      (let [y (az/field (az/index (az/field owned reference) node) y)]
        (az/set-many! lower (ak/min lower y) upper (ak/max upper y))))
    (- upper lower)))
