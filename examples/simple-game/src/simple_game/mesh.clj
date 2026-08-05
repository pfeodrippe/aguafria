(ns simple-game.mesh
  "Actual Kenney mesh loading and fixed-camera isometric 3D submission."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as std-math]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings.stdio :as stdio]
            [simple-game.factory :as factory]
            [simple-game.physics :as physics]))

(az/defstruct MeshHeader
  {:layout :extern}
  [[:magic :u32]
   [:vertices :u32]])

(az/defstruct MeshVertex
  "One real vertex imported from a Kenney GLB, with baked palette color."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   [:nx :f32]
   [:ny :f32]
   [:nz :f32]
   [:r :f32]
   [:g :f32]
   [:b :f32]])

(az/defstruct GpuVertex
  "Projected vertex consumed directly by the Vulkan triangle pipeline."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   [:r :f32]
   [:g :f32]
   [:b :f32]])

(az/defstruct MeshRange
  {:layout :extern}
  [[:first :u32]
   [:count :u32]
   [:ready :bool]])

(az/defstruct MeshSnapshot
  "Inspectable native mesh catalog and latest frame submission."
  {:layout :extern}
  [[:initialized :bool]
   [:models :u32]
   [:source_vertices :u32]
   [:frame_vertices :u32]
   [:capacity :u32]])

(az/defconst source-capacity :usize 65536)

(az/defconst frame-capacity :usize 131072)

(az/defconst model-count :usize 16)

(az/defconst model-house-a :usize 0)

(az/defconst model-house-b :usize 1)

(az/defconst model-house-c :usize 2)

(az/defconst model-palm :usize 3)

(az/defconst model-lamp :usize 4)

(az/defconst model-bench :usize 5)

(az/defconst model-plaza :usize 6)

(az/defconst model-road :usize 7)

(az/defconst model-cart-frame :usize 8)

(az/defconst model-cart-parasol :usize 9)

(az/defconst model-coconut :usize 10)

(az/defconst model-cart-display :usize 11)

(az/defconst model-customer-a :usize 12)

(az/defconst model-customer-b :usize 13)

(az/defconst model-conveyor :usize 14)

(az/defconst model-machine :usize 15)

(az/defvar initialized false)

(az/defvar source-vertices [:array 65536 MeshVertex]
  (std-mem/zeroes (az/type [:array 65536 MeshVertex])))

(az/defvar ranges [:array 16 MeshRange]
  (std-mem/zeroes (az/type [:array 16 MeshRange])))

(az/defvar source-count :usize 0)

(az/defvar latest-frame-count :u32 0)

(az/defvar particle-views [:array 64 physics/ParticleView]
  (std-mem/zeroes (az/type [:array 64 physics/ParticleView])))

(az/defn load-model!
  "Load one prepacked GLB triangle stream into bounded native storage."
  {:export false}
  :-
  :bool
  [[slot :usize]
   [path [:pointer {:size :c :const? true} :u8]]]
  (let [file (stdio/fopen path "rb")]
    (if (ak/== file null)
      false
      (let [^{:var true}
            header (MeshHeader {:magic 0 :vertices 0})
            header-read (stdio/fread (ak/& header) (ak/sizeOf MeshHeader) 1 file)
            count (ak/as :usize (ak/intCast (az/field header vertices)))
            valid (and (ak/== header-read 1)
                       (ak/== (az/field header magic) 0x314d4741)
                       (<= (+ source-count count) source-capacity))
            read-count
            (if valid
              (stdio/fread (ak/& (az/index source-vertices source-count))
                            (ak/sizeOf MeshVertex) count file)
              0)]
        (set! _ (stdio/fclose file))
        (if (ak/== read-count count)
          (do
            (set! (az/index ranges slot)
                  (MeshRange {:first (ak/intCast source-count)
                              :count (ak/intCast count)
                              :ready true}))
            (set! source-count (+ source-count count))
            true)
          false)))))

(az/defn initialize!
  "Load all selected Kenney models once; hot reload retains their native data."
  :-
  :bool
  []
  (when (ak/! initialized)
    (set! source-count 0)
    (set! ranges (std-mem/zeroes (az/type [:array 16 MeshRange])))
    (let [all-loaded
          (and
           (load-model! model-house-a "resources/kenney/packed/recife-house-a.agmesh")
           (load-model! model-house-b "resources/kenney/packed/recife-house-b.agmesh")
           (load-model! model-house-c "resources/kenney/packed/recife-house-c.agmesh")
           (load-model! model-palm "resources/kenney/packed/recife-palm.agmesh")
           (load-model! model-lamp "resources/kenney/packed/recife-lamp.agmesh")
           (load-model! model-bench "resources/kenney/packed/recife-bench.agmesh")
           (load-model! model-plaza "resources/kenney/packed/recife-plaza.agmesh")
           (load-model! model-road "resources/kenney/packed/recife-road.agmesh")
           (load-model! model-cart-frame "resources/kenney/packed/cart-frame.agmesh")
           (load-model! model-cart-parasol "resources/kenney/packed/cart-parasol.agmesh")
           (load-model! model-coconut "resources/kenney/packed/cart-coconut.agmesh")
           (load-model! model-cart-display "resources/kenney/packed/cart-display.agmesh")
           (load-model! model-customer-a "resources/kenney/packed/people-customer-a.agmesh")
           (load-model! model-customer-b "resources/kenney/packed/people-customer-b.agmesh")
           (load-model! model-conveyor "resources/kenney/packed/factory-conveyor.agmesh")
           (load-model! model-machine "resources/kenney/packed/factory-machine.agmesh"))]
      (set! initialized all-loaded)))
  initialized)

(az/defn reload!
  "Reload mechanically packed Kenney assets without restarting the JVM."
  :-
  :bool
  []
  (set! initialized false)
  (initialize!))

(az/defn write-instance!
  "Project one 3D Kenney mesh through a fixed orthographic isometric camera."
  {:export false}
  :-
  :usize
  [[output [:c-pointer GpuVertex]]
   [output-count :usize]
   [slot :usize]
   [world-x :f32]
   [world-z :f32]
   [rotation :f32]
   [scale :f32]
   [brightness :f32]]
  (let [range (az/index ranges slot)
        count (ak/as :usize (ak/intCast (az/field range count)))
        cosine (std-math/cos rotation)
        sine (std-math/sin rotation)]
    (if (or (ak/! (az/field range ready))
            (> (+ output-count count) frame-capacity))
      output-count
      (do
        (dotimes [offset count]
          (let [source (az/index source-vertices
                                 (+ (ak/as :usize
                                           (ak/intCast (az/field range first)))
                                    offset))
                local-x (* (az/field source x) scale)
                local-y (* (az/field source y) scale)
                local-z (* (az/field source z) scale)
                rotated-x (- (* local-x cosine) (* local-z sine))
                rotated-z (+ (* local-x sine) (* local-z cosine))
                x (+ world-x rotated-x)
                z (+ world-z rotated-z)
                ;; Fill the native viewport with the playable block.  These
                ;; values retain the orthographic camera while avoiding the
                ;; detached miniature-diorama look of the first composition.
                screen-x (+ 360.0 (* (- x z) 39.0))
                screen-y (- (+ 250.0 (* (+ x z) 18.0)) (* local-y 50.0))
                ^{:zig/type :f32}
                depth-bias (if (ak/== slot model-road) 0.004 0.0)
                camera-depth (- 0.50
                                (* 0.025 (+ x z))
                                (* 0.018 local-y)
                                depth-bias)
                normal-x (- (* (az/field source nx) cosine)
                            (* (az/field source nz) sine))
                normal-z (+ (* (az/field source nx) sine)
                            (* (az/field source nz) cosine))
                light (ak/max 0.28
                              (ak/min 1.0
                                      (+ 0.52
                                         (* 0.30 (az/field source ny))
                                         (* 0.10 normal-x)
                                         (* 0.08 normal-z))))]
            (set! (az/index output (+ output-count offset))
                  (GpuVertex
                   {:x (- (/ screen-x 360.0) 1.0)
                    ;; Vulkan's positive viewport height maps NDC -1 to the top.
                    :y (- (/ screen-y 270.0) 1.0)
                    :z (ak/max 0.02 (ak/min 0.98 camera-depth))
                    :r (ak/min 1.0 (* (az/field source r) light brightness))
                    :g (ak/min 1.0 (* (az/field source g) light brightness))
                    :b (ak/min 1.0 (* (az/field source b) light brightness))}))))
        (+ output-count count)))))

(az/defn write-tinted-instance!
  "Submit a Kenney mesh while tinting its baked palette for Recife façades."
  {:export false}
  :-
  :usize
  [[output [:c-pointer GpuVertex]]
   [output-count :usize]
   [slot :usize]
   [world-x :f32]
   [world-z :f32]
   [rotation :f32]
   [scale :f32]
   [brightness :f32]
   [tint-r :f32]
   [tint-g :f32]
   [tint-b :f32]]
  (let [next-count (write-instance! output output-count slot world-x world-z
                                    rotation scale brightness)]
    (dotimes [offset (- next-count output-count)]
      (let [vertex (ak/& (az/index output (+ output-count offset)))]
        (set! (az/field (az/deref vertex) r)
              (ak/min 1.0 (* (az/field (az/deref vertex) r) tint-r)))
        (set! (az/field (az/deref vertex) g)
              (ak/min 1.0 (* (az/field (az/deref vertex) g) tint-g)))
        (set! (az/field (az/deref vertex) b)
              (ak/min 1.0 (* (az/field (az/deref vertex) b) tint-b)))))
    next-count))

(az/defn write-particle!
  "Project one live Box3D particle using the Kenney coconut mesh."
  {:export false}
  :-
  :usize
  [[output [:c-pointer GpuVertex]]
   [output-count :usize]
   [particle physics/ParticleView]]
  (let [event-kind (az/field particle event_kind)
        next-count
        (write-tinted-instance!
         output output-count model-coconut
         (az/field particle x) (az/field particle z)
         (* (az/field particle age) 4.5)
         (+ 0.26 (* (az/field particle radius) 0.8))
         1.18
         (if (ak/== event-kind 4) 1.35 1.05)
         (if (ak/== event-kind 2) 0.62 1.0)
         (if (ak/== event-kind 1) 0.72 1.0))
        height (ak/max 0.0 (az/field particle y))]
    (dotimes [offset (- next-count output-count)]
      (let [vertex (ak/& (az/index output (+ output-count offset)))]
        (set! (az/field (az/deref vertex) y)
              (- (az/field (az/deref vertex) y)
                 (/ (* height 50.0) 270.0)))
        (set! (az/field (az/deref vertex) z)
              (ak/max 0.02
                      (- (az/field (az/deref vertex) z)
                         (* height 0.018))))))
    next-count))

(az/defn append-physics!
  "Append every active Box3D particle to the shared mapped frame batch."
  {:export false}
  :-
  :usize
  [[output [:c-pointer GpuVertex]]
   [output-count :usize]]
  (let [active-count
        (physics/fill-active-views! (ak/& (az/index particle-views 0)))
        ^{:var true :zig/type :usize} count output-count]
    (dotimes [slot active-count]
      (set! count
            (write-particle! output count (az/index particle-views slot))))
    count))

(az/defn build-coco-factory-frame!
  "Render the authoritative coco-house grid from its native simulation cells."
  :-
  :u32
  [[output [:c-pointer GpuVertex]]]
  (if (ak/! (initialize!))
    0
    (let [state (factory/snapshot)
          ^{:var true :zig/type :usize} count 0]
      ;; Every visible tile is the same cell that owns native simulation state.
      (dotimes [row 9]
        (dotimes [column 19]
          (let [grid-x (+ 2 (ak/as :i32 (ak/intCast column)))
                grid-y (+ 8 (ak/as :i32 (ak/intCast row)))
                world-x (* (- (ak/as :f32 (ak/floatFromInt grid-x)) 11.0) 0.5)
                world-z (* (- (ak/as :f32 (ak/floatFromInt grid-y)) 12.0) 0.5)
                view (factory/cell-view grid-x grid-y)
                kind (az/field view building)
                direction (az/field view direction)
                rotation (* (ak/as :f32 (ak/floatFromInt direction)) 1.5707963)
                selected (and (ak/== grid-x (az/field state selected_x))
                              (ak/== grid-y (az/field state selected_y)))]
            (set! count
                  (write-tinted-instance!
                   output count model-plaza world-x world-z 0.0 0.51
                   (if selected 1.20
                     (if (ak/== (mod (+ row column) 2) 0) 0.92 0.84))
                   (if selected 1.38 0.72)
                   (if selected 1.20 0.79)
                   (if selected 0.54 0.70)))

            (cond
              (ak/== kind factory/building-belt)
              (set! count
                    (write-instance! output count model-conveyor
                                     world-x world-z rotation 0.48 0.92))

              (ak/== kind factory/building-extractor)
              (set! count
                    (write-tinted-instance! output count model-machine
                                            world-x world-z rotation 0.42 1.02
                                            0.62 1.14 0.72))

              (ak/== kind factory/building-assembler)
              (set! count
                    (write-tinted-instance! output count model-machine
                                            world-x world-z rotation 0.46 1.08
                                            1.34 0.86 0.42))

              (ak/== kind factory/building-storage)
              (set! count
                    (write-instance! output count model-cart-display
                                     world-x world-z rotation 0.50 0.94))

              (ak/== kind factory/building-splitter)
              (set! count
                    (write-tinted-instance! output count model-conveyor
                                            world-x world-z rotation 0.60 1.0
                                            0.78 0.72 1.18))

              (ak/== kind factory/building-coco-house)
              (if (>= (az/field view inventory) (factory/house-panel-recipe))
                (set! count
                      (write-tinted-instance! output count model-house-c
                                              world-x world-z 0.0 0.65 1.12
                                              0.82 1.28 0.86))
                (set! count
                      (write-tinted-instance! output count model-house-a
                                              world-x world-z 0.0 0.54 1.10
                                              1.40 0.68 0.30))))

            (when (ak/!= (az/field view item_kind) factory/item-none)
              (let [progress (az/field view item_progress)
                    offset-x (cond
                               (ak/== direction factory/direction-east) progress
                               (ak/== direction factory/direction-west) (- 0.0 progress)
                               :else 0.0)
                    offset-z (cond
                               (ak/== direction factory/direction-south) progress
                               (ak/== direction factory/direction-north) (- 0.0 progress)
                               :else 0.0)
                    item-x (+ world-x (* offset-x 0.5))
                    item-z (+ world-z (* offset-z 0.5))]
                (if (ak/== (az/field view item_kind) factory/item-coco-panel)
                  (set! count
                        (write-tinted-instance! output count model-bench
                                                item-x item-z rotation 0.24 1.18
                                                1.28 0.74 0.34))
                  (set! count
                        (write-instance! output count model-coconut
                                         item-x item-z rotation 0.72 1.18))))))))
      (set! count (append-physics! output count))
      (set! latest-frame-count (ak/intCast count))
      latest-frame-count)))

(az/defn snapshot
  :-
  MeshSnapshot
  []
  (let [^{:var true :zig/type :u32} ready 0]
    (dotimes [slot model-count]
      (when (az/field (az/index ranges slot) ready)
        (set! ready (+ ready 1))))
    (MeshSnapshot {:initialized initialized
                   :models ready
                   :source_vertices (ak/intCast source-count)
                   :frame_vertices latest-frame-count
                   :capacity (ak/intCast frame-capacity)})))
