(ns racing-game.render3d
  "Native orthographic geometry, world-space material attributes and Vulkan depth."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as a]
            [aguafria-examples-native.mesh :as mesh]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.geometry :as geometry]
            [racing-game.simulation :as sim]
            [racing-game.physics :as physics]
            [racing-game.vehicle-spec :as spec]
            [racing-game.track-barriers :as barriers]
            [racing-game.track :as track]))

(a/defstruct Vec3 {:layout :extern} [[:x :f32] [:y :f32] [:z :f32]])

(a/defstruct CameraSnapshot {:layout :extern}
  [[:following :bool] [:front_pack :bool] [:racer :u8]
   [:zoom :f32] [:x :f32] [:y :f32] [:z :f32]])

(a/defvar previous-poses [:array sim/racer-count [:array 5 physics/BodyState]] ak/undefined)

(a/defvar current-poses [:array sim/racer-count [:array 5 physics/BodyState]] ak/undefined)

(a/defvar presentation-ready false)

(a/defvar presentation-tick :u64 0)

(a/defvar presentation-alpha :f32 1.0)

(a/defn reset-presentation! :void
  "Discard displayed history on the frame thread when starting a new world." []
  (ak/= presentation-ready false)
  (ak/= presentation-alpha 1.0))

(a/defn interpolate-pose physics/BodyState
  "Presentation only: linear position and shortest-arc normalized quaternion.
  Both snapshots are measured Box3D poses. No physical body is changed." [[previous physics/BodyState] [current physics/BodyState] [phase :f32]]
  (let [a (ak/max 0.0 (ak/min 1.0 phase))
        b (- 1.0 a)
        dot (+ (* (a/field previous qx) (a/field current qx))
               (* (a/field previous qy) (a/field current qy))
               (* (a/field previous qz) (a/field current qz))
               (* (a/field previous qw) (a/field current qw)))
        signed-a (if (< dot 0.0) (- a) a)
        qx (+ (* b (a/field previous qx)) (* signed-a (a/field current qx)))
        qy (+ (* b (a/field previous qy)) (* signed-a (a/field current qy)))
        qz (+ (* b (a/field previous qz)) (* signed-a (a/field current qz)))
        qw (+ (* b (a/field previous qw)) (* signed-a (a/field current qw)))
        length (ak/max 0.00000001 (ak/sqrt (+ (* qx qx) (* qy qy) (* qz qz) (* qw qw))))
        ^:var result current]
    (ak/= (a/field result x) (+ (* b (a/field previous x)) (* a (a/field current x))))
    (ak/= (a/field result y) (+ (* b (a/field previous y)) (* a (a/field current y))))
    (ak/= (a/field result z) (+ (* b (a/field previous z)) (* a (a/field current z))))
    (ak/= (a/field result qx) (/ qx length))
    (ak/= (a/field result qy) (/ qy length))
    (ak/= (a/field result qz) (/ qz length))
    (ak/= (a/field result qw) (/ qw length))
    result))

(a/defn capture-presentation! :void
  "After each fixed physics tick, retain its pose and the preceding pose.
  Reset/cold attachment initializes both sides, never blends an old race in." []
  (let [tick (a/field (sim/snapshot) tick)
        reset (or (ak/! presentation-ready) (< tick presentation-tick))]
    (when presentation-ready (ak/= previous-poses current-poses))
    (dotimes [i sim/racer-count]
      (dotimes [part 5]
        (ak/= (a/index (a/index current-poses i) part) (sim/vehicle-pose i part))))
    (when reset (ak/= previous-poses current-poses))
    (ak/= presentation-tick tick)
    (ak/= presentation-ready true)))

(a/defn set-presentation-phase! :void [[phase :f32]]
  (ak/= presentation-alpha (ak/max 0.0 (ak/min 1.0 phase))))

(a/defn presentation-pose physics/BodyState [[racer :usize] [part :usize]]
  (if presentation-ready
    (interpolate-pose (a/index (a/index previous-poses racer) part)
                      (a/index (a/index current-poses racer) part) presentation-alpha)
    (sim/vehicle-pose racer part)))

(a/defn presentation-view sim/RacerView
  "Measured race metadata, with only displayed position/heading interpolated." [[racer :u8]]
  (let [pose (presentation-pose racer 0)
        ^:var result (sim/racer-view racer)]
    (ak/= (a/field result x) (* 0.001 (a/field pose x)))
    (ak/= (a/field result y) (* 0.001 (a/field pose y)))
    (ak/= (a/field result heading)
          (math/atan2 (* 2.0 (+ (* (a/field pose qw) (a/field pose qz))
                                (* (a/field pose qx) (a/field pose qy))))
                     (- 1.0 (* 2.0 (+ (* (a/field pose qy) (a/field pose qy))
                                      (* (a/field pose qz) (a/field pose qz)))))))
    result))

(a/defconst colors [:array sim/racer-count [:array 3 :f32]]
  [[0.05 0.82 1.0] [1.0 0.25 0.66] [0.38 1.0 0.28] [1.0 0.42 0.08]
   [0.62 0.38 1.0] [1.0 0.90 0.22] [0.18 1.0 0.72] [1.0 0.30 0.30]
   [0.35 0.55 1.0] [1.0 0.65 0.82] [0.68 0.82 0.30] [0.96 0.70 0.40]
   [0.80 0.55 1.0] [0.70 0.94 1.0] [0.38 0.70 0.58] [0.90 0.48 0.50]
   [0.88 0.88 0.94] [0.58 0.60 0.82] [0.92 0.76 0.58] [0.66 0.76 0.76]])

(a/defvar camera-yaw :f32 0.15)

(a/defvar camera-pitch :f32 0.85)

(a/defvar camera-zoom :f32 1.30)

(a/defvar follow-camera true)

(a/defvar follow-front-pack true)

(a/defvar zoom-multiplier :f32 32.0)

(a/defvar camera-mode :u8 0)

(a/defvar camera-racer :u8 0)

(a/defvar camera-x :f32 0.0)

(a/defvar camera-y :f32 0.0)

(a/defvar camera-z :f32 0.0)

(a/defvar camera-initialized false)

(a/defn reset-camera! :void []
  (ak/= camera-initialized false))

(a/defn select-camera! :void [[racer :u8] [follow :bool]]
  (when (or (ak/!= camera-racer (mod racer (ak/as (ak/intCast sim/racer-count) :u8)))
            (ak/!= camera-mode (if follow (ak/as 1 :u8) (ak/as 4 :u8))))
    (reset-camera!))
  (ak/= camera-racer (mod racer (ak/as (ak/intCast sim/racer-count) :u8)))
  (ak/= follow-front-pack false)
  (ak/= follow-camera follow)
  (ak/= camera-mode (if follow 1 4)))

(a/defn follow-leaders! :void []
  (when (ak/!= camera-mode 0) (reset-camera!))
  (ak/= camera-mode 0)
  (ak/= follow-front-pack true)
  (ak/= follow-camera true))

(a/defn camera-preset! :void
  "Broadcast pack, low driver chase, panning trackside, fixed pit, or overview." [[mode :u8]]
  (reset-camera!)
  (ak/= camera-mode (mod mode 5))
  (ak/= follow-camera (< camera-mode 3))
  (ak/= follow-front-pack (ak/== camera-mode 0))
  (ak/= zoom-multiplier (cond (ak/== camera-mode 4) 1.0
                               (ak/== camera-mode 3) 16.0
                               (ak/== camera-mode 1) 40.0
                               (ak/== camera-mode 2) 32.0
                               :else 32.0)))

(a/defn zoom-by! :void
  "Multiply view magnification; 1x is the complete circuit, 32x the default.
  Wheel/trackpad and +/- cover 1x to 80x without changing physical scale." [[factor :f32]]
  (ak/= zoom-multiplier (ak/max 1.0 (ak/min 80.0 (* zoom-multiplier factor)))))

(a/defn update-camera! :void
  "Track the leader and nearby front runners, including across the lap seam." []
  (let [race (sim/snapshot)
        leader (presentation-view (a/field race leader))
        target (presentation-view (if follow-front-pack (a/field race leader) camera-racer))
        ^:var sx (a/field target x)
        ^:var sy (a/field target y)
        ^:var sz (* 0.001 (a/field (presentation-pose (a/field target id) 0) z))
        ^:var count (ak/f32 1.0)]
    (when (and follow-camera follow-front-pack)
      (dotimes [i sim/racer-count]
        (let [racer (presentation-view (ak/intCast i))
              dx (- (a/field racer x) (a/field leader x))
              dy (- (a/field racer y) (a/field leader y))]
          (when (and (ak/!= i (a/field race leader))
                     (ak/! (sim/retired? i))
                     (<= (a/field racer rank) 4)
                     ;; A close broadcast frame follows the actual nearby
                     ;; battle, not cars 120m away that pull it off the leader.
                     (< (+ (* dx dx) (* dy dy)) 0.0009))
            (ak/= sx (+ sx (a/field racer x)))
            (ak/= sy (+ sy (a/field racer y)))
            (ak/= sz (+ sz (* 0.001 (a/field (presentation-pose i 0) z))))
            (ak/= count (+ count 1.0))))))
    (ak/= camera-x (if follow-camera (/ sx count) 0.0))
    (ak/= camera-y (if follow-camera (/ sy count) 0.0))
    (ak/= camera-z (if follow-camera (/ sz count) 0.0))
    (ak/= camera-yaw 0.15)
    (ak/= camera-pitch 0.85)
    (when (ak/== camera-mode 1)
      (ak/= camera-yaw (- 1.5707963 (a/field target heading)))
      (ak/= camera-pitch 0.40))
    (when (ak/== camera-mode 2)
      ;; Fixed camera stations every eighth lap pan towards the leading car.
      (let [p (/ (ak/floor (* (a/field leader progress) 8.0)) 8.0)
            station (track/pose (+ p 0.035) 0.65)
            heading (math/atan2 (- (a/field leader y) (a/field station y))
                                (- (a/field leader x) (a/field station x)))]
        (ak/= camera-x (a/field leader x))
        (ak/= camera-y (a/field leader y))
        (ak/= camera-z (* 0.001 (a/field (presentation-pose (a/field leader id) 0) z)))
        (ak/= camera-yaw (- 1.5707963 heading))
        (ak/= camera-pitch 0.22)))
    (when (ak/== camera-mode 3)
      (let [pit (track/pit-pose 0.971 0.19)]
        (ak/= camera-x (a/field pit x))
        (ak/= camera-y (a/field pit y))
        (ak/= camera-z (track/elevation 0.971))
        (ak/= camera-yaw (- (a/field pit heading)))
        (ak/= camera-pitch 0.38)))
    (ak/= camera-zoom (* 1.30 zoom-multiplier))))

(a/defn damp :f32
  "Exponential tracking with a time-based response, independent of frame rate." [[current :f32] [target :f32] [rate :f32] [seconds :f32]]
  (+ current (* (- target current) (- 1.0 (ak/exp (- (* rate (ak/max seconds 0.0))))))))

(a/defn damp-angle :f32
  "Follow the shortest angular arc, including the -pi/pi seam." [[current :f32] [target :f32] [rate :f32] [seconds :f32]]
  (let [delta (- target current)
        shortest (math/atan2 (math/sin delta) (math/cos delta))]
    (damp current (+ current shortest) rate seconds)))

(a/defn advance-camera! :void
  "Once per rendered frame, track the phase-interpolated simulation target. Explicit
  camera cuts/reset snap once; ongoing position, heading and zoom are damped.
  This does not modify the authoritative vehicle pose." [[seconds :f32]]
  (let [x camera-x y camera-y z camera-z
        yaw camera-yaw pitch camera-pitch zoom camera-zoom]
    (update-camera!)
    (if camera-initialized
      (do
        (ak/= camera-x (damp x camera-x 14.0 seconds))
        (ak/= camera-y (damp y camera-y 14.0 seconds))
        (ak/= camera-z (damp z camera-z 14.0 seconds))
        (ak/= camera-yaw (damp-angle yaw camera-yaw 8.0 seconds))
        (ak/= camera-pitch (damp pitch camera-pitch 8.0 seconds))
        (ak/= camera-zoom (damp zoom camera-zoom 12.0 seconds)))
      (ak/= camera-initialized true))))

(a/defvar fit-x :f32 1.0)

(a/defn camera-snapshot CameraSnapshot []
  (CameraSnapshot {:following follow-camera :front_pack follow-front-pack
                   :racer camera-racer :zoom zoom-multiplier
                   :x camera-x :y camera-y :z camera-z}))

(a/defvar fit-y :f32 1.0)

(a/defvar projection-yaw :f32 -1000.0)

(a/defvar projection-pitch :f32 -1000.0)

(a/defvar projection-cos-yaw :f32 1.0)

(a/defvar projection-sin-yaw :f32 0.0)

(a/defvar projection-cos-pitch :f32 1.0)

(a/defvar projection-sin-pitch :f32 0.0)

(a/defn prepare-projection! :void
  "Share camera trigonometry across every vertex. Lazy angle checks also keep
  direct REPL projection calls correct after camera edits, outside a frame." []
  (when (ak/!= projection-yaw camera-yaw)
    (ak/= projection-cos-yaw (math/cos camera-yaw))
    (ak/= projection-sin-yaw (math/sin camera-yaw))
    (ak/= projection-yaw camera-yaw))
  (when (ak/!= projection-pitch camera-pitch)
    (ak/= projection-cos-pitch (math/cos camera-pitch))
    (ak/= projection-sin-pitch (math/sin camera-pitch))
    (ak/= projection-pitch camera-pitch)))

(a/defn project Vec3
  "Project a real world point to Vulkan NDC, including monotonic depth." [[x :f32] [y :f32] [z :f32]]
  (prepare-projection!)
  (let [px (- x camera-x) py (- y camera-y) pz (- z camera-z)
        rx (- (* px projection-cos-yaw) (* py projection-sin-yaw))
        ry (+ (* px projection-sin-yaw) (* py projection-cos-yaw))]
    (Vec3 {:x (* rx camera-zoom fit-x)
           :y (+ 0.10 (* (- (* (- ry) projection-sin-pitch)
                              (* pz projection-cos-pitch))
                         camera-zoom fit-y))
           :z (+ 0.5 (* 0.22 (- (* ry projection-cos-pitch)
                                (* pz projection-sin-pitch))))})))

(a/defn material-vertex! :void
  [[out [:c-pointer mesh/GpuVertex]] [i :usize] [point Vec3] [color Vec3]
   [normal Vec3] [roughness :f32]]
  (let [p (project (a/field point x) (a/field point y) (a/field point z))]
    (ak/= (a/index out i)
          (mesh/GpuVertex {:x (a/field p x) :y (a/field p y) :z (a/field p z)
                           :r (a/field color x) :g (a/field color y)
                           :b (a/field color z)
                           :nx (a/field normal x) :ny (a/field normal y) :nz (a/field normal z)
                           :wx (a/field point x) :wy (a/field point y) :wz (a/field point z)
                           :roughness roughness
                           :vx (- (* projection-sin-yaw projection-cos-pitch))
                           :vy (- (* projection-cos-yaw projection-cos-pitch))
                           :vz projection-sin-pitch}))))

(a/defn vertex! :void
  [[out [:c-pointer mesh/GpuVertex]] [i :usize] [point Vec3] [color Vec3]]
  (material-vertex! out i point color (Vec3 {:x 0.0 :y 0.0 :z 1.0}) -1.0))

(a/defn ndc-triangle-visible? :bool
  "Conservative trivial rejection. Keep triangles crossing the viewport even
  when all three vertices lie outside; reject only one shared outside plane." [[a Vec3] [b Vec3] [c Vec3]]
  (ak/! (or (< (ak/max (a/field a x) (ak/max (a/field b x) (a/field c x))) -1.0)
             (> (ak/min (a/field a x) (ak/min (a/field b x) (a/field c x))) 1.0)
             (< (ak/max (a/field a y) (ak/max (a/field b y) (a/field c y))) -1.0)
             (> (ak/min (a/field a y) (ak/min (a/field b y) (a/field c y))) 1.0)
             (< (ak/max (a/field a z) (ak/max (a/field b z) (a/field c z))) 0.0)
             (> (ak/min (a/field a z) (ak/min (a/field b z) (a/field c z))) 1.0))))

(a/defn oriented-triangle! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [a Vec3] [b Vec3] [c Vec3] [color Vec3] [up-facing? :bool]]
  (if (or (> (+ n 3) mesh/frame-capacity)
           (ak/! (ndc-triangle-visible?
                   (project (a/field a x) (a/field a y) (a/field a z))
                   (project (a/field b x) (a/field b y) (a/field b z))
                   (project (a/field c x) (a/field c y) (a/field c z))))) n
    (let [ux (- (a/field b x) (a/field a x))
          uy (- (a/field b y) (a/field a y))
          uz (- (a/field b z) (a/field a z))
          vx (- (a/field c x) (a/field a x))
          vy (- (a/field c y) (a/field a y))
          vz (- (a/field c z) (a/field a z))
          nx (- (* uy vz) (* uz vy))
          ny (- (* uz vx) (* ux vz))
          nz (- (* ux vy) (* uy vx))
          length (ak/max 0.000000001 (ak/sqrt (+ (* nx nx) (* ny ny) (* nz nz))))
          sign (if (and up-facing? (< nz 0.0)) (ak/as -1.0 :f32) (ak/as 1.0 :f32))
          normal (Vec3 {:x (* sign (/ nx length)) :y (* sign (/ ny length))
                        :z (* sign (/ nz length))})]
      (material-vertex! out n a color normal 0.95)
      (material-vertex! out (+ n 1) b color normal 0.95)
      (material-vertex! out (+ n 2) c color normal 0.95)
      (+ n 3))))

(a/defn triangle! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [a Vec3] [b Vec3] [c Vec3] [color Vec3]]
  (oriented-triangle! out n a b c color true))

(a/defn quad! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [a Vec3] [b Vec3] [c Vec3] [d Vec3] [color Vec3]]
  (triangle! out (triangle! out n a b c color) a c d color))

(a/defn ground-point Vec3 [[progress :f32] [lane :f32] [height :f32]]
  (let [p (track/pose progress lane)]
    (Vec3 {:x (a/field p x) :y (a/field p y) :z (+ (track/elevation progress) height)})))

(a/defn ribbon! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [pa :f32] [pb :f32] [la :f32] [lb :f32] [height :f32] [color Vec3]]
  (quad! out n (ground-point pa la height) (ground-point pb la height)
         (ground-point pb lb height) (ground-point pa lb height) color))

(a/defn pit-point Vec3 [[progress :f32] [lane :f32] [height :f32]]
  (let [p (track/pit-pose progress lane)]
    (Vec3 {:x (a/field p x) :y (a/field p y) :z (+ (track/elevation progress) height)})))

(a/defn pit-ribbon! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [pa :f32] [pb :f32] [la :f32] [lb :f32] [height :f32] [color Vec3]]
  (quad! out n (pit-point pa la height) (pit-point pb la height)
         (pit-point pb lb height) (pit-point pa lb height) color))

(a/defn surface-point Vec3 [[progress :f32] [column :usize]]
  (let [p (track/surface-point progress column)]
    (Vec3 {:x (* (a/field p x) 0.001) :y (* (a/field p y) 0.001)
           :z (* (a/field p z) 0.001)})))

(a/defn road! :usize
  "The exact collision cross sections, with no overlapping grass/asphalt sheets." [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^:var next (ak/usize n)]
    (dotimes [i track/surface-segments]
      (let [pa (/ (ak/as (ak/floatFromInt i) :f32) (ak/as (ak/floatFromInt track/surface-segments) :f32))
            pb (/ (ak/as (ak/floatFromInt (+ i 1)) :f32) (ak/as (ak/floatFromInt track/surface-segments) :f32))]
        (dotimes [strip (- track/surface-columns 1)]
          (let [a (surface-point pa strip) b (surface-point pb strip)
                c (surface-point pb (+ strip 1)) d (surface-point pa (+ strip 1))
                color (if (track/surface-asphalt? strip)
                        (Vec3 {:x 0.13 :y 0.16 :z 0.19})
                        (Vec3 {:x 0.12 :y 0.22 :z 0.085}))]
            (when (> (- (track/surface-boundary pb (+ strip 1)) (track/surface-boundary pb strip)) 0.001)
              (ak/= next (triangle! out next a b c color)))
            (when (> (- (track/surface-boundary pa (+ strip 1)) (track/surface-boundary pa strip)) 0.001)
              (ak/= next (triangle! out next a c d color)))))
        (let [white (Vec3 {:x 0.8 :y 0.84 :z 0.80})]
          (ak/= next (ribbon! out next pa pb -0.13 -0.126 0.00003 white))
          ;; No kerb across the merge where pit asphalt meets main asphalt.
          (when (and (or (> (track/surface-boundary pa 3) 6.501) (<= (track/surface-boundary pa 4) 6.501))
                     (or (> (track/surface-boundary pb 3) 6.501) (<= (track/surface-boundary pb 4) 6.501)))
            (ak/= next (ribbon! out next pa pb 0.126 0.13 0.00003 white))))))
    (dotimes [team sim/team-count]
      (let [p (sim/pit-box-progress (ak/intCast team))
            white (Vec3 {:x 0.88 :y 0.9 :z 0.92})]
        (ak/= next (pit-ribbon! out next (- p 0.0012) (+ p 0.0012)
                               0.197 0.198 0.00005 white))
        (ak/= next (pit-ribbon! out next (- p 0.0012) (+ p 0.0012)
                               0.233 0.234 0.00005 white))
        (ak/= next (pit-ribbon! out next (- p 0.0012) (- p 0.00115)
                               0.197 0.234 0.00005 white))
        (ak/= next (pit-ribbon! out next (+ p 0.00115) (+ p 0.0012)
                               0.197 0.234 0.00005 white))))
    (dotimes [i 12]
      (let [lane (+ -0.13 (* (ak/as (ak/floatFromInt i) :f32) (/ 0.26 12.0)))
            light (ak/f32 (if (ak/== (mod i 2) 0) 0.90 0.02))]
        (ak/= next (ribbon! out next 0.0 0.0004 lane (+ lane (/ 0.26 12.0)) 0.00003
                           (Vec3 {:x light :y light :z light})))))
    next))

(a/defn rotate-body-vector Vec3 [[v Vec3] [state physics/BodyState]]
  (let [qx (a/field state qx) qy (a/field state qy) qz (a/field state qz)
        qw (a/field state qw) x (a/field v x) y (a/field v y) z (a/field v z)
        tx (* 2.0 (- (* qy z) (* qz y)))
        ty (* 2.0 (- (* qz x) (* qx z)))
        tz (* 2.0 (- (* qx y) (* qy x)))]
    (Vec3 {:x (+ x (* qw tx) (- (* qy tz) (* qz ty)))
           :y (+ y (* qw ty) (- (* qz tx) (* qx tz)))
           :z (+ z (* qw tz) (- (* qx ty) (* qy tx)))})))

(a/defn rigid-model! :usize
  "Render an authored mesh from its actual body position and full quaternion.
  Origin is the Blender-authored chassis/axle pivot, in metres."
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [state physics/BodyState] [origin Vec3] [tint Vec3]]
  (if (> (+ n (a/field vertices len)) mesh/frame-capacity) n
    (do
      (dotimes [i (a/field vertices len)]
        (let [v (a/index vertices i)
              point (rotate-body-vector
                      (Vec3 {:x (- (a/index v 0) (a/field origin x))
                             :y (- (a/index v 1) (a/field origin y))
                             :z (- (a/index v 2) (a/field origin z))}) state)
              normal (rotate-body-vector
                       (Vec3 {:x (a/index v 3) :y (a/index v 4) :z (a/index v 5)}) state)
              mix (a/index v 9)]
          (material-vertex! out (+ n i)
            (Vec3 {:x (* 0.001 (+ (a/field state x) (a/field point x)))
                   :y (* 0.001 (+ (a/field state y) (a/field point y)))
                   :z (* 0.001 (+ (a/field state z) (a/field point z)))})
            (Vec3 {:x (* (a/index v 6) (+ (- 1.0 mix) (* mix (a/field tint x))))
                   :y (* (a/index v 7) (+ (- 1.0 mix) (* mix (a/field tint y))))
                   :z (* (a/index v 8) (+ (- 1.0 mix) (* mix (a/field tint z))))})
            normal (if (< (+ (a/index v 6) (a/index v 7) (a/index v 8)) 0.30) 0.90 0.34))))
      (+ n (a/field vertices len)))))

(a/defn posed-model! :usize
  "Animate an authored part around its axle, then transform into world space."
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [x :f32] [y :f32] [z :f32] [heading :f32] [scale :f32] [tint Vec3]
   [cx :f32] [cy :f32] [cz :f32] [roll :f32] [steer :f32]]
  (if (> (+ n (a/field vertices len)) mesh/frame-capacity) n
    (let [co (math/cos heading) si (math/sin heading)
          rc (math/cos roll) rs (math/sin roll)
          sc (math/cos steer) ss (math/sin steer)]
      (dotimes [i (a/field vertices len)]
        (let [v (a/index vertices i)
              dx (- (a/index v 0) cx) dy (- (a/index v 1) cy)
              dz (- (a/index v 2) cz)
              rx (+ (* dx rc) (* dz rs))
              rz (- (* dz rc) (* dx rs))
              vx (+ cx (- (* rx sc) (* dy ss)))
              vy (+ cy (* rx ss) (* dy sc))
              vz (+ cz rz)
              nrx (+ (* (a/index v 3) rc) (* (a/index v 5) rs))
              nrz (- (* (a/index v 5) rc) (* (a/index v 3) rs))
              nsx (- (* nrx sc) (* (a/index v 4) ss))
              nsy (+ (* nrx ss) (* (a/index v 4) sc))
              nx (- (* nsx co) (* nsy si))
              ny (+ (* nsx si) (* nsy co))
              mix (a/index v 9)]
          (material-vertex! out (+ n i)
                   (Vec3 {:x (+ x (* scale (- (* vx co) (* vy si))))
                          :y (+ y (* scale (+ (* vx si) (* vy co))))
                          :z (+ z (* scale vz))})
                   (Vec3 {:x (* (a/index v 6) (+ (- 1.0 mix) (* mix (a/field tint x))))
                          :y (* (a/index v 7) (+ (- 1.0 mix) (* mix (a/field tint y))))
                          :z (* (a/index v 8) (+ (- 1.0 mix) (* mix (a/field tint z))))})
                   (Vec3 {:x nx :y ny :z nrz})
                   (if (< (+ (a/index v 6) (a/index v 7) (a/index v 8)) 0.30)
                     0.90 0.34))))
      (+ n (a/field vertices len)))))

(a/defn model! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [x :f32] [y :f32] [z :f32] [heading :f32] [scale :f32] [tint Vec3]]
  (posed-model! out n vertices x y z heading scale tint 0.0 0.0 0.0 0.0 0.0))

(a/defn wheel-roll :f32
  "Radians from actual arc-length travel and Blender tire radius, not wall time." [[progress :f32] [lap :u16] [radius :f32]]
  (mod (/ (* (+ progress (ak/as (ak/floatFromInt lap) :f32)) 4309.0)
          (ak/max radius 0.01)) 6.2831855))

(a/defn racer-tint Vec3 [[id :usize]]
  (let [color (a/index colors (mod (ak/as id :usize) sim/racer-count))]
    (Vec3 {:x (a/index color 0) :y (a/index color 1) :z (a/index color 2)})))

(a/defn wheel! :usize
  "Wheel spin, steering and suspension all come from the independent wheel body."
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [axle [:array 4 :f32]] [racer sim/RacerView] [part :usize]]
  (rigid-model! out n vertices (presentation-pose (a/field racer id) part)
    (Vec3 {:x (a/index axle 0) :y (a/index axle 1) :z (a/index axle 2)})
    (racer-tint (a/field racer id))))

(a/defn shadow-model! :usize
  "Project upward voxel faces onto the planar road along the sunlight vector.
  This is a directional planar shadow, not scene-wide shadow mapping."
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [x :f32] [y :f32] [z :f32] [heading :f32] [scale :f32]]
  (let [^:var next (ak/usize n)
        co (math/cos heading) si (math/sin heading)]
    (dotimes [triangle (ak/divTrunc (a/field vertices len) 3)]
      (let [base (* triangle 3)]
        (when (and (> (a/index (a/index vertices base) 5) 0.5)
                   (<= (+ next 3) mesh/frame-capacity))
          (dotimes [corner 3]
            (let [v (a/index vertices (+ base corner))
                  height (* scale (a/index v 2))]
              (vertex! out (+ next corner)
                       (Vec3 {:x (+ x (* scale (- (* (a/index v 0) co)
                                                   (* (a/index v 1) si)))
                                    (* height 0.34642))
                              :y (+ y (* scale (+ (* (a/index v 0) si)
                                                   (* (a/index v 1) co)))
                                    (* height 0.46189))
                              :z (+ z 0.00006)})
                       (Vec3 {:x 0.035 :y 0.045 :z 0.060}))))
          (ak/= next (+ next 3)))))
    next))

(a/defn line! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [x :f32] [y :f32] [bx :f32] [by :f32] [z :f32] [width :f32] [color Vec3]]
  (let [dx (- bx x) dy (- by y)
        length (ak/max 0.00001 (math/sqrt (+ (* dx dx) (* dy dy))))
        nx (* (/ (- dy) length) width) ny (* (/ dx length) width)]
    (quad! out n (Vec3 {:x (+ x nx) :y (+ y ny) :z z})
           (Vec3 {:x (+ bx nx) :y (+ by ny) :z z})
           (Vec3 {:x (- bx nx) :y (- by ny) :z z})
           (Vec3 {:x (- x nx) :y (- y ny) :z z}) color)))

(a/defn ring! :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [x :f32] [y :f32] [z :f32] [radius :f32] [color Vec3]]
  (let [^:var next (ak/usize n)]
    (dotimes [i 24]
      (let [a (* (ak/as (ak/floatFromInt i) :f32) (/ 6.2831855 24.0))
            b (+ a (/ 6.2831855 24.0))]
        (ak/= next (line! out next (+ x (* radius (math/cos a)))
                         (+ y (* radius (math/sin a)))
                         (+ x (* radius (math/cos b))) (+ y (* radius (math/sin b)))
                         z 0.00008 color))))
    next))

(a/defn diamond! :usize
  "Eight solid faces, not a screen-space diamond pretending to have depth."
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [x :f32] [y :f32] [z :f32] [radius :f32] [color Vec3]]
  (let [^:var next (ak/usize n)]
    (dotimes [i 4]
      (let [a (* (ak/as (ak/floatFromInt i) :f32) 1.5707963)
            b (+ a 1.5707963)
            p (Vec3 {:x (+ x (* radius (math/cos a)))
                     :y (+ y (* radius (math/sin a))) :z z})
            q (Vec3 {:x (+ x (* radius (math/cos b)))
                     :y (+ y (* radius (math/sin b))) :z z})]
        (ak/= next (triangle! out next p q (Vec3 {:x x :y y :z (+ z radius)}) color))
        (ak/= next (triangle! out next q p (Vec3 {:x x :y y :z (- z radius)})
                             (Vec3 {:x (* (a/field color x) 0.5)
                                    :y (* (a/field color y) 0.5)
                                    :z (* (a/field color z) 0.5)})))))
    next))

(a/defn effects! :usize [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^:var next (ak/usize n)]
    (dotimes [i 4]
      (let [progress (* (ak/as (ak/floatFromInt i) :f32) 0.25)
            p (track/pose progress 0.0)]
        (ak/= next (diamond! out next (a/field p x) (a/field p y)
                            (+ (track/elevation progress) 0.0015) 0.0008
                            (Vec3 {:x 1.0 :y 0.78 :z 0.08})))))
    (dotimes [i sim/hazard-capacity]
      (let [hazard (sim/hazard-view i)]
        (when (a/field hazard active)
          (let [x (a/field hazard x) y (a/field hazard y)
                z (+ (track/elevation (a/field hazard progress)) 0.0003)
                color (Vec3 {:x 1.0 :y 0.25 :z 0.05})]
            (if (ak/== (a/field hazard kind) sim/item-bolt)
              (ak/= next (diamond! out next x y z 0.0005 color))
              (do
                (ak/= next (line! out next (- x 0.0008) (- y 0.0008)
                                 (+ x 0.0008) (+ y 0.0008) z 0.0001 color))
                (ak/= next (line! out next (- x 0.0008) (+ y 0.0008)
                                 (+ x 0.0008) (- y 0.0008) z 0.0001 color))))))))
    next))

(a/defn intents! :usize [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^:var next (ak/usize n)]
    (dotimes [i sim/racer-count]
      (let [racer (presentation-view (ak/intCast i))
            goal (track/pose (mod (+ (a/field racer progress) 0.0045) 1.0)
                             (a/field racer lane_target))]
        (when (and (ak/! (a/field racer finished)) (ak/! (sim/retired? i)))
          (ak/= next (line! out next (a/field racer x) (a/field racer y)
                           (a/field goal x) (a/field goal y)
                           (+ (track/elevation (a/field racer progress)) 0.001) 0.00006 (racer-tint i))))))
    next))

(a/defn containment! :usize
  "Draw Blender's metre-space barrier triangles used by Box3D." [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^:var next (ak/usize n)
        color (Vec3 {:x 0.64 :y 0.68 :z 0.69})]
    (dotimes [i barriers/triangle-count]
      (let [indices (a/index barriers/triangles i)
            a (a/index barriers/vertices (ak/intCast (a/index indices 0)))
            b (a/index barriers/vertices (ak/intCast (a/index indices 1)))
            c (a/index barriers/vertices (ak/intCast (a/index indices 2)))]
        (ak/= next (oriented-triangle! out next
          (Vec3 {:x (* 0.001 (a/index a 0)) :y (* 0.001 (a/index a 1)) :z (* 0.001 (a/index a 2))})
          (Vec3 {:x (* 0.001 (a/index b 0)) :y (* 0.001 (a/index b 1)) :z (* 0.001 (a/index b 2))})
          (Vec3 {:x (* 0.001 (a/index c 0)) :y (* 0.001 (a/index c 1)) :z (* 0.001 (a/index c 2))})
          color false))))
    next))

(a/defn gpu-instance mesh/GpuInstance
  "Compact per-part state. No vertex iteration or pose approximation." [[id :usize] [part :usize] [origin Vec3]]
  (let [state (presentation-pose id part)
        racer (presentation-view (ak/intCast id))
        tint (racer-tint id)]
    (mesh/GpuInstance
      {:qx (a/field state qx) :qy (a/field state qy)
       :qz (a/field state qz) :qw (a/field state qw)
       :x (a/field state x) :y (a/field state y) :z (a/field state z) :scale 0.001
       :origin_x (a/field origin x) :origin_y (a/field origin y) :origin_z (a/field origin z)
       :shadow_z (+ (track/elevation (a/field racer progress)) 0.00008)
       :r (a/field tint x) :g (a/field tint y) :b (a/field tint z) :mode 0.0})))

(a/defn draw-instanced-part! :bool [[slot :usize] [revision :u64]
             [vertices [:slice-const [:array 10 :f32]]]
             [part :usize] [origin Vec3] [camera mesh/InstanceCamera]]
  (let [^:var instances (ak/as ak/undefined [:array sim/racer-count mesh/GpuInstance])]
    (dotimes [i sim/racer-count]
      (ak/= (a/index instances i) (gpu-instance i part origin)))
    (when (ak/! (renderer/draw-instances! slot revision vertices (ak/& instances) camera))
      (ak/return false))
    (dotimes [i sim/racer-count]
      (ak/= (a/field (a/index instances i) mode) 1.0))
    (renderer/draw-instances! slot revision vertices (ak/& instances) camera)))

(a/defn draw-racers! :bool
  "Six immutable meshes, 20 independently posed instances of each. Wheels are
  still separate rigid bodies. Only transforms/tints/shadow planes are uploaded." []
  (prepare-projection!)
  (let [camera (mesh/InstanceCamera
                 {:x camera-x :y camera-y :z camera-z :zoom camera-zoom
                  :cos_yaw projection-cos-yaw :sin_yaw projection-sin-yaw
                  :cos_pitch projection-cos-pitch :sin_pitch projection-sin-pitch
                  :fit_x fit-x :fit_y fit-y :reserved0 0.0 :reserved1 0.0})
        origin (Vec3 {:x 0.0 :y 0.0 :z spec/chassis-origin-z})]
    (and
      (draw-instanced-part! 0 geometry/body-revision (ak/& geometry/body-vertices) 0 origin camera)
      (draw-instanced-part! 1 geometry/driver-revision (ak/& geometry/driver-vertices) 0 origin camera)
      (draw-instanced-part! 2 geometry/wheel-front-left-revision (ak/& geometry/wheel-front-left-vertices) 1
        (Vec3 {:x (a/index geometry/wheel-front-left-axle 0) :y (a/index geometry/wheel-front-left-axle 1)
               :z (a/index geometry/wheel-front-left-axle 2)}) camera)
      (draw-instanced-part! 3 geometry/wheel-front-right-revision (ak/& geometry/wheel-front-right-vertices) 2
        (Vec3 {:x (a/index geometry/wheel-front-right-axle 0) :y (a/index geometry/wheel-front-right-axle 1)
               :z (a/index geometry/wheel-front-right-axle 2)}) camera)
      (draw-instanced-part! 4 geometry/wheel-rear-left-revision (ak/& geometry/wheel-rear-left-vertices) 3
        (Vec3 {:x (a/index geometry/wheel-rear-left-axle 0) :y (a/index geometry/wheel-rear-left-axle 1)
               :z (a/index geometry/wheel-rear-left-axle 2)}) camera)
      (draw-instanced-part! 5 geometry/wheel-rear-right-revision (ak/& geometry/wheel-rear-right-vertices) 4
        (Vec3 {:x (a/index geometry/wheel-rear-right-axle 0) :y (a/index geometry/wheel-rear-right-axle 1)
               :z (a/index geometry/wheel-rear-right-axle 2)}) camera))))

(a/defn build-world-geometry! :u32
  "Build the non-instanced world stream. Cars are separate GPU draws, so they
  no longer consume or overflow this CPU vertex buffer." [[out [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (let [w (ak/as (ak/floatFromInt (ak/max width 1)) :f32)
        h (ak/as (ak/floatFromInt (ak/max height 1)) :f32)]
    (ak/= fit-x (ak/min 1.0 (/ h w)))
    (ak/= fit-y (ak/min 1.0 (/ w h))))
  (let [human (sim/human-control-snapshot)
        ^:var next (ak/usize (containment! out (road! out 0)))]
    (dotimes [team sim/team-count]
      (let [p (track/pit-pose (sim/pit-box-progress (ak/intCast team)) 0.285)]
        (ak/= next (model! out next (ak/& geometry/garage-vertices)
                          (a/field p x) (a/field p y) (+ (track/elevation (sim/pit-box-progress (ak/intCast team))) 0.00003)
                          (a/field p heading) 0.003 (racer-tint (* team 2))))))
    (ak/= next (effects! out next))
    (dotimes [i sim/racer-count]
      (let [racer (presentation-view (ak/intCast i))
            x (a/field racer x) y (a/field racer y)
            z (+ (track/elevation (a/field racer progress)) 0.00008)]
        (when (a/field racer shielded)
          (ak/= next (ring! out next x y (+ z 0.0002) 0.0032 (racer-tint i))))
        (when (and (a/field human enabled) (ak/== i 0))
          (ak/= next (ring! out next x y (+ z 0.0002) 0.0035 (Vec3 {:x 1.0 :y 1.0 :z 1.0}))))))
    (ak/intCast next)))

(a/defn build-world! :u32
  "FrameBuilder entry point: stream world geometry and issue bounded car draws
  inside the active Vulkan render pass. Publish these two paths together." [[out [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (let [count (build-world-geometry! out width height)]
    (when (ak/! (draw-racers!))
      (std-debug/panic "Unable to draw the bounded GPU car instances" []))
    count))
