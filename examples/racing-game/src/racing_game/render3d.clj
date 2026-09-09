(ns racing-game.render3d
  "Native orthographic geometry, world-space material attributes and Vulkan depth."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [aguafria-examples-native.mesh :as mesh]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.geometry :as geometry]
            [racing-game.simulation :as sim]
            [racing-game.physics :as physics]
            [racing-game.vehicle-spec :as spec]
            [racing-game.track-barriers :as barriers]
            [racing-game.track :as track]))

(az/defstruct Vec3 {:layout :extern} [[:x :f32] [:y :f32] [:z :f32]])

(az/defstruct CameraSnapshot {:layout :extern}
  [[:following :bool] [:front_pack :bool] [:racer :u8]
   [:zoom :f32] [:x :f32] [:y :f32] [:z :f32]])

(az/defvar previous-poses [:array sim/racer-count [:array 5 physics/BodyState]] ak/undefined)

(az/defvar current-poses [:array sim/racer-count [:array 5 physics/BodyState]] ak/undefined)

(az/defvar presentation-ready false)

(az/defvar presentation-tick :u64 0)

(az/defvar presentation-alpha :f32 1.0)

(az/defn reset-presentation!
  "Discard displayed history on the frame thread when starting a new world."
  :- :void []
  (set! presentation-ready false)
  (set! presentation-alpha 1.0))

(az/defn interpolate-pose
  "Presentation only: linear position and shortest-arc normalized quaternion.
  Both snapshots are measured Box3D poses. No physical body is changed."
  :- physics/BodyState [[previous physics/BodyState] [current physics/BodyState] [phase :f32]]
  (let [a (ak/max 0.0 (ak/min 1.0 phase))
        b (- 1.0 a)
        dot (+ (* (az/field previous qx) (az/field current qx))
               (* (az/field previous qy) (az/field current qy))
               (* (az/field previous qz) (az/field current qz))
               (* (az/field previous qw) (az/field current qw)))
        signed-a (if (< dot 0.0) (- a) a)
        qx (+ (* b (az/field previous qx)) (* signed-a (az/field current qx)))
        qy (+ (* b (az/field previous qy)) (* signed-a (az/field current qy)))
        qz (+ (* b (az/field previous qz)) (* signed-a (az/field current qz)))
        qw (+ (* b (az/field previous qw)) (* signed-a (az/field current qw)))
        length (ak/max 0.00000001 (ak/sqrt (+ (* qx qx) (* qy qy) (* qz qz) (* qw qw))))
        ^:var result current]
    (set! (az/field result x) (+ (* b (az/field previous x)) (* a (az/field current x))))
    (set! (az/field result y) (+ (* b (az/field previous y)) (* a (az/field current y))))
    (set! (az/field result z) (+ (* b (az/field previous z)) (* a (az/field current z))))
    (set! (az/field result qx) (/ qx length))
    (set! (az/field result qy) (/ qy length))
    (set! (az/field result qz) (/ qz length))
    (set! (az/field result qw) (/ qw length))
    result))

(az/defn capture-presentation!
  "After each fixed physics tick, retain its pose and the preceding pose.
  Reset/cold attachment initializes both sides, never blends an old race in."
  :- :void []
  (let [tick (az/field (sim/snapshot) tick)
        reset (or (ak/! presentation-ready) (< tick presentation-tick))]
    (when presentation-ready (set! previous-poses current-poses))
    (dotimes [i sim/racer-count]
      (dotimes [part 5]
        (set! (az/index (az/index current-poses i) part) (sim/vehicle-pose i part))))
    (when reset (set! previous-poses current-poses))
    (set! presentation-tick tick)
    (set! presentation-ready true)))

(az/defn set-presentation-phase! :- :void [[phase :f32]]
  (set! presentation-alpha (ak/max 0.0 (ak/min 1.0 phase))))

(az/defn presentation-pose :- physics/BodyState [[racer :usize] [part :usize]]
  (if presentation-ready
    (interpolate-pose (az/index (az/index previous-poses racer) part)
                      (az/index (az/index current-poses racer) part) presentation-alpha)
    (sim/vehicle-pose racer part)))

(az/defn presentation-view
  "Measured race metadata, with only displayed position/heading interpolated."
  :- sim/RacerView [[racer :u8]]
  (let [pose (presentation-pose racer 0)
        ^:var result (sim/racer-view racer)]
    (set! (az/field result x) (* 0.001 (az/field pose x)))
    (set! (az/field result y) (* 0.001 (az/field pose y)))
    (set! (az/field result heading)
          (math/atan2 (* 2.0 (+ (* (az/field pose qw) (az/field pose qz))
                                (* (az/field pose qx) (az/field pose qy))))
                     (- 1.0 (* 2.0 (+ (* (az/field pose qy) (az/field pose qy))
                                      (* (az/field pose qz) (az/field pose qz)))))))
    result))

(az/defconst colors [:array sim/racer-count [:array 3 :f32]]
  [[0.05 0.82 1.0] [1.0 0.25 0.66] [0.38 1.0 0.28] [1.0 0.42 0.08]
   [0.62 0.38 1.0] [1.0 0.90 0.22] [0.18 1.0 0.72] [1.0 0.30 0.30]
   [0.35 0.55 1.0] [1.0 0.65 0.82] [0.68 0.82 0.30] [0.96 0.70 0.40]
   [0.80 0.55 1.0] [0.70 0.94 1.0] [0.38 0.70 0.58] [0.90 0.48 0.50]
   [0.88 0.88 0.94] [0.58 0.60 0.82] [0.92 0.76 0.58] [0.66 0.76 0.76]])

(az/defvar camera-yaw :f32 0.15)

(az/defvar camera-pitch :f32 0.85)

(az/defvar camera-zoom :f32 1.30)

(az/defvar follow-camera true)

(az/defvar follow-front-pack true)

(az/defvar zoom-multiplier :f32 32.0)

(az/defvar camera-mode :u8 0)

(az/defvar camera-racer :u8 0)

(az/defvar camera-x :f32 0.0)

(az/defvar camera-y :f32 0.0)

(az/defvar camera-z :f32 0.0)

(az/defvar camera-initialized false)

(az/defn reset-camera! :- :void []
  (set! camera-initialized false))

(az/defn select-camera! :- :void [[racer :u8] [follow :bool]]
  (when (or (ak/!= camera-racer (mod racer (ak/as :u8 (ak/intCast sim/racer-count))))
            (ak/!= camera-mode (if follow (ak/as :u8 1) (ak/as :u8 4))))
    (reset-camera!))
  (set! camera-racer (mod racer (ak/as :u8 (ak/intCast sim/racer-count))))
  (set! follow-front-pack false)
  (set! follow-camera follow)
  (set! camera-mode (if follow 1 4)))

(az/defn follow-leaders! :- :void []
  (when (ak/!= camera-mode 0) (reset-camera!))
  (set! camera-mode 0)
  (set! follow-front-pack true)
  (set! follow-camera true))

(az/defn camera-preset!
  "Broadcast pack, low driver chase, panning trackside, fixed pit, or overview."
  :- :void [[mode :u8]]
  (reset-camera!)
  (set! camera-mode (mod mode 5))
  (set! follow-camera (< camera-mode 3))
  (set! follow-front-pack (ak/== camera-mode 0))
  (set! zoom-multiplier (cond (ak/== camera-mode 4) 1.0
                               (ak/== camera-mode 3) 16.0
                               (ak/== camera-mode 1) 40.0
                               (ak/== camera-mode 2) 32.0
                               :else 32.0)))

(az/defn zoom-by!
  "Multiply view magnification; 1x is the complete circuit, 32x the default.
  Wheel/trackpad and +/- cover 1x to 80x without changing physical scale."
  :- :void [[factor :f32]]
  (set! zoom-multiplier (ak/max 1.0 (ak/min 80.0 (* zoom-multiplier factor)))))

(az/defn update-camera!
  "Track the leader and nearby front runners, including across the lap seam."
  :- :void []
  (let [race (sim/snapshot)
        leader (presentation-view (az/field race leader))
        target (presentation-view (if follow-front-pack (az/field race leader) camera-racer))
        ^:var sx (az/field target x)
        ^:var sy (az/field target y)
        ^:var sz (* 0.001 (az/field (presentation-pose (az/field target id) 0) z))
        ^{:var :f32} count 1.0]
    (when (and follow-camera follow-front-pack)
      (dotimes [i sim/racer-count]
        (let [racer (presentation-view (ak/intCast i))
              dx (- (az/field racer x) (az/field leader x))
              dy (- (az/field racer y) (az/field leader y))]
          (when (and (ak/!= i (az/field race leader))
                     (ak/! (sim/retired? i))
                     (<= (az/field racer rank) 4)
                     ;; A close broadcast frame follows the actual nearby
                     ;; battle, not cars 120m away that pull it off the leader.
                     (< (+ (* dx dx) (* dy dy)) 0.0009))
            (set! sx (+ sx (az/field racer x)))
            (set! sy (+ sy (az/field racer y)))
            (set! sz (+ sz (* 0.001 (az/field (presentation-pose i 0) z))))
            (set! count (+ count 1.0))))))
    (set! camera-x (if follow-camera (/ sx count) 0.0))
    (set! camera-y (if follow-camera (/ sy count) 0.0))
    (set! camera-z (if follow-camera (/ sz count) 0.0))
    (set! camera-yaw 0.15)
    (set! camera-pitch 0.85)
    (when (ak/== camera-mode 1)
      (set! camera-yaw (- 1.5707963 (az/field target heading)))
      (set! camera-pitch 0.40))
    (when (ak/== camera-mode 2)
      ;; Fixed camera stations every eighth lap pan towards the leading car.
      (let [p (/ (ak/floor (* (az/field leader progress) 8.0)) 8.0)
            station (track/pose (+ p 0.035) 0.65)
            heading (math/atan2 (- (az/field leader y) (az/field station y))
                                (- (az/field leader x) (az/field station x)))]
        (set! camera-x (az/field leader x))
        (set! camera-y (az/field leader y))
        (set! camera-z (* 0.001 (az/field (presentation-pose (az/field leader id) 0) z)))
        (set! camera-yaw (- 1.5707963 heading))
        (set! camera-pitch 0.22)))
    (when (ak/== camera-mode 3)
      (let [pit (track/pit-pose 0.971 0.19)]
        (set! camera-x (az/field pit x))
        (set! camera-y (az/field pit y))
        (set! camera-z (track/elevation 0.971))
        (set! camera-yaw (- (az/field pit heading)))
        (set! camera-pitch 0.38)))
    (set! camera-zoom (* 1.30 zoom-multiplier))))

(az/defn damp
  "Exponential tracking with a time-based response, independent of frame rate."
  :- :f32 [[current :f32] [target :f32] [rate :f32] [seconds :f32]]
  (+ current (* (- target current) (- 1.0 (ak/exp (- (* rate (ak/max seconds 0.0))))))))

(az/defn damp-angle
  "Follow the shortest angular arc, including the -pi/pi seam."
  :- :f32 [[current :f32] [target :f32] [rate :f32] [seconds :f32]]
  (let [delta (- target current)
        shortest (math/atan2 (math/sin delta) (math/cos delta))]
    (damp current (+ current shortest) rate seconds)))

(az/defn advance-camera!
  "Once per rendered frame, track the phase-interpolated simulation target. Explicit
  camera cuts/reset snap once; ongoing position, heading and zoom are damped.
  This does not modify the authoritative vehicle pose."
  :- :void [[seconds :f32]]
  (let [x camera-x y camera-y z camera-z
        yaw camera-yaw pitch camera-pitch zoom camera-zoom]
    (update-camera!)
    (if camera-initialized
      (do
        (set! camera-x (damp x camera-x 14.0 seconds))
        (set! camera-y (damp y camera-y 14.0 seconds))
        (set! camera-z (damp z camera-z 14.0 seconds))
        (set! camera-yaw (damp-angle yaw camera-yaw 8.0 seconds))
        (set! camera-pitch (damp pitch camera-pitch 8.0 seconds))
        (set! camera-zoom (damp zoom camera-zoom 12.0 seconds)))
      (set! camera-initialized true))))

(az/defvar fit-x :f32 1.0)

(az/defn camera-snapshot :- CameraSnapshot []
  (CameraSnapshot {:following follow-camera :front_pack follow-front-pack
                   :racer camera-racer :zoom zoom-multiplier
                   :x camera-x :y camera-y :z camera-z}))

(az/defvar fit-y :f32 1.0)

(az/defvar projection-yaw :f32 -1000.0)

(az/defvar projection-pitch :f32 -1000.0)

(az/defvar projection-cos-yaw :f32 1.0)

(az/defvar projection-sin-yaw :f32 0.0)

(az/defvar projection-cos-pitch :f32 1.0)

(az/defvar projection-sin-pitch :f32 0.0)

(az/defn prepare-projection!
  "Share camera trigonometry across every vertex. Lazy angle checks also keep
  direct REPL projection calls correct after camera edits, outside a frame."
  :- :void []
  (when (ak/!= projection-yaw camera-yaw)
    (set! projection-cos-yaw (math/cos camera-yaw))
    (set! projection-sin-yaw (math/sin camera-yaw))
    (set! projection-yaw camera-yaw))
  (when (ak/!= projection-pitch camera-pitch)
    (set! projection-cos-pitch (math/cos camera-pitch))
    (set! projection-sin-pitch (math/sin camera-pitch))
    (set! projection-pitch camera-pitch)))

(az/defn project
  "Project a real world point to Vulkan NDC, including monotonic depth."
  :- Vec3 [[x :f32] [y :f32] [z :f32]]
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

(az/defn material-vertex!
  :- :void
  [[out [:c-pointer mesh/GpuVertex]] [i :usize] [point Vec3] [color Vec3]
   [normal Vec3] [roughness :f32]]
  (let [p (project (az/field point x) (az/field point y) (az/field point z))]
    (set! (az/index out i)
          (mesh/GpuVertex {:x (az/field p x) :y (az/field p y) :z (az/field p z)
                           :r (az/field color x) :g (az/field color y)
                           :b (az/field color z)
                           :nx (az/field normal x) :ny (az/field normal y) :nz (az/field normal z)
                           :wx (az/field point x) :wy (az/field point y) :wz (az/field point z)
                           :roughness roughness
                           :vx (- (* projection-sin-yaw projection-cos-pitch))
                           :vy (- (* projection-cos-yaw projection-cos-pitch))
                           :vz projection-sin-pitch}))))

(az/defn vertex!
  :- :void
  [[out [:c-pointer mesh/GpuVertex]] [i :usize] [point Vec3] [color Vec3]]
  (material-vertex! out i point color (Vec3 {:x 0.0 :y 0.0 :z 1.0}) -1.0))

(az/defn ndc-triangle-visible?
  "Conservative trivial rejection. Keep triangles crossing the viewport even
  when all three vertices lie outside; reject only one shared outside plane."
  :- :bool [[a Vec3] [b Vec3] [c Vec3]]
  (ak/! (or (< (ak/max (az/field a x) (ak/max (az/field b x) (az/field c x))) -1.0)
             (> (ak/min (az/field a x) (ak/min (az/field b x) (az/field c x))) 1.0)
             (< (ak/max (az/field a y) (ak/max (az/field b y) (az/field c y))) -1.0)
             (> (ak/min (az/field a y) (ak/min (az/field b y) (az/field c y))) 1.0)
             (< (ak/max (az/field a z) (ak/max (az/field b z) (az/field c z))) 0.0)
             (> (ak/min (az/field a z) (ak/min (az/field b z) (az/field c z))) 1.0))))

(az/defn oriented-triangle!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [a Vec3] [b Vec3] [c Vec3] [color Vec3] [up-facing? :bool]]
  (if (or (> (+ n 3) mesh/frame-capacity)
           (ak/! (ndc-triangle-visible?
                   (project (az/field a x) (az/field a y) (az/field a z))
                   (project (az/field b x) (az/field b y) (az/field b z))
                   (project (az/field c x) (az/field c y) (az/field c z))))) n
    (let [ux (- (az/field b x) (az/field a x))
          uy (- (az/field b y) (az/field a y))
          uz (- (az/field b z) (az/field a z))
          vx (- (az/field c x) (az/field a x))
          vy (- (az/field c y) (az/field a y))
          vz (- (az/field c z) (az/field a z))
          nx (- (* uy vz) (* uz vy))
          ny (- (* uz vx) (* ux vz))
          nz (- (* ux vy) (* uy vx))
          length (ak/max 0.000000001 (ak/sqrt (+ (* nx nx) (* ny ny) (* nz nz))))
          sign (if (and up-facing? (< nz 0.0)) (ak/as :f32 -1.0) (ak/as :f32 1.0))
          normal (Vec3 {:x (* sign (/ nx length)) :y (* sign (/ ny length))
                        :z (* sign (/ nz length))})]
      (material-vertex! out n a color normal 0.95)
      (material-vertex! out (+ n 1) b color normal 0.95)
      (material-vertex! out (+ n 2) c color normal 0.95)
      (+ n 3))))

(az/defn triangle!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [a Vec3] [b Vec3] [c Vec3] [color Vec3]]
  (oriented-triangle! out n a b c color true))

(az/defn quad!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [a Vec3] [b Vec3] [c Vec3] [d Vec3] [color Vec3]]
  (triangle! out (triangle! out n a b c color) a c d color))

(az/defn ground-point :- Vec3 [[progress :f32] [lane :f32] [height :f32]]
  (let [p (track/pose progress lane)]
    (Vec3 {:x (az/field p x) :y (az/field p y) :z (+ (track/elevation progress) height)})))

(az/defn ribbon!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [pa :f32] [pb :f32] [la :f32] [lb :f32] [height :f32] [color Vec3]]
  (quad! out n (ground-point pa la height) (ground-point pb la height)
         (ground-point pb lb height) (ground-point pa lb height) color))

(az/defn pit-point :- Vec3 [[progress :f32] [lane :f32] [height :f32]]
  (let [p (track/pit-pose progress lane)]
    (Vec3 {:x (az/field p x) :y (az/field p y) :z (+ (track/elevation progress) height)})))

(az/defn pit-ribbon!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [pa :f32] [pb :f32] [la :f32] [lb :f32] [height :f32] [color Vec3]]
  (quad! out n (pit-point pa la height) (pit-point pb la height)
         (pit-point pb lb height) (pit-point pa lb height) color))

(az/defn surface-point :- Vec3 [[progress :f32] [column :usize]]
  (let [p (track/surface-point progress column)]
    (Vec3 {:x (* (az/field p x) 0.001) :y (* (az/field p y) 0.001)
           :z (* (az/field p z) 0.001)})))

(az/defn road!
  "The exact collision cross sections, with no overlapping grass/asphalt sheets."
  :- :usize [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^{:var :usize} next n]
    (dotimes [i track/surface-segments]
      (let [pa (/ (ak/as :f32 (ak/floatFromInt i)) (ak/as :f32 (ak/floatFromInt track/surface-segments)))
            pb (/ (ak/as :f32 (ak/floatFromInt (+ i 1))) (ak/as :f32 (ak/floatFromInt track/surface-segments)))]
        (dotimes [strip (- track/surface-columns 1)]
          (let [a (surface-point pa strip) b (surface-point pb strip)
                c (surface-point pb (+ strip 1)) d (surface-point pa (+ strip 1))
                color (if (track/surface-asphalt? strip)
                        (Vec3 {:x 0.13 :y 0.16 :z 0.19})
                        (Vec3 {:x 0.12 :y 0.22 :z 0.085}))]
            (when (> (- (track/surface-boundary pb (+ strip 1)) (track/surface-boundary pb strip)) 0.001)
              (set! next (triangle! out next a b c color)))
            (when (> (- (track/surface-boundary pa (+ strip 1)) (track/surface-boundary pa strip)) 0.001)
              (set! next (triangle! out next a c d color)))))
        (let [white (Vec3 {:x 0.8 :y 0.84 :z 0.80})]
          (set! next (ribbon! out next pa pb -0.13 -0.126 0.00003 white))
          ;; No kerb across the merge where pit asphalt meets main asphalt.
          (when (and (or (> (track/surface-boundary pa 3) 6.501) (<= (track/surface-boundary pa 4) 6.501))
                     (or (> (track/surface-boundary pb 3) 6.501) (<= (track/surface-boundary pb 4) 6.501)))
            (set! next (ribbon! out next pa pb 0.126 0.13 0.00003 white))))))
    (dotimes [team sim/team-count]
      (let [p (sim/pit-box-progress (ak/intCast team))
            white (Vec3 {:x 0.88 :y 0.9 :z 0.92})]
        (set! next (pit-ribbon! out next (- p 0.0012) (+ p 0.0012)
                               0.197 0.198 0.00005 white))
        (set! next (pit-ribbon! out next (- p 0.0012) (+ p 0.0012)
                               0.233 0.234 0.00005 white))
        (set! next (pit-ribbon! out next (- p 0.0012) (- p 0.00115)
                               0.197 0.234 0.00005 white))
        (set! next (pit-ribbon! out next (+ p 0.00115) (+ p 0.0012)
                               0.197 0.234 0.00005 white))))
    (dotimes [i 12]
      (let [lane (+ -0.13 (* (ak/as :f32 (ak/floatFromInt i)) (/ 0.26 12.0)))
            ^{:zig/type :f32} light (if (ak/== (mod i 2) 0) 0.90 0.02)]
        (set! next (ribbon! out next 0.0 0.0004 lane (+ lane (/ 0.26 12.0)) 0.00003
                           (Vec3 {:x light :y light :z light})))))
    next))

(az/defn rotate-body-vector :- Vec3 [[v Vec3] [state physics/BodyState]]
  (let [qx (az/field state qx) qy (az/field state qy) qz (az/field state qz)
        qw (az/field state qw) x (az/field v x) y (az/field v y) z (az/field v z)
        tx (* 2.0 (- (* qy z) (* qz y)))
        ty (* 2.0 (- (* qz x) (* qx z)))
        tz (* 2.0 (- (* qx y) (* qy x)))]
    (Vec3 {:x (+ x (* qw tx) (- (* qy tz) (* qz ty)))
           :y (+ y (* qw ty) (- (* qz tx) (* qx tz)))
           :z (+ z (* qw tz) (- (* qx ty) (* qy tx)))})))

(az/defn rigid-model!
  "Render an authored mesh from its actual body position and full quaternion.
  Origin is the Blender-authored chassis/axle pivot, in metres."
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [state physics/BodyState] [origin Vec3] [tint Vec3]]
  (if (> (+ n (az/field vertices len)) mesh/frame-capacity) n
    (do
      (dotimes [i (az/field vertices len)]
        (let [v (az/index vertices i)
              point (rotate-body-vector
                      (Vec3 {:x (- (az/index v 0) (az/field origin x))
                             :y (- (az/index v 1) (az/field origin y))
                             :z (- (az/index v 2) (az/field origin z))}) state)
              normal (rotate-body-vector
                       (Vec3 {:x (az/index v 3) :y (az/index v 4) :z (az/index v 5)}) state)
              mix (az/index v 9)]
          (material-vertex! out (+ n i)
            (Vec3 {:x (* 0.001 (+ (az/field state x) (az/field point x)))
                   :y (* 0.001 (+ (az/field state y) (az/field point y)))
                   :z (* 0.001 (+ (az/field state z) (az/field point z)))})
            (Vec3 {:x (* (az/index v 6) (+ (- 1.0 mix) (* mix (az/field tint x))))
                   :y (* (az/index v 7) (+ (- 1.0 mix) (* mix (az/field tint y))))
                   :z (* (az/index v 8) (+ (- 1.0 mix) (* mix (az/field tint z))))})
            normal (if (< (+ (az/index v 6) (az/index v 7) (az/index v 8)) 0.30) 0.90 0.34))))
      (+ n (az/field vertices len)))))

(az/defn posed-model!
  "Animate an authored part around its axle, then transform into world space."
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [x :f32] [y :f32] [z :f32] [heading :f32] [scale :f32] [tint Vec3]
   [cx :f32] [cy :f32] [cz :f32] [roll :f32] [steer :f32]]
  (if (> (+ n (az/field vertices len)) mesh/frame-capacity) n
    (let [co (math/cos heading) si (math/sin heading)
          rc (math/cos roll) rs (math/sin roll)
          sc (math/cos steer) ss (math/sin steer)]
      (dotimes [i (az/field vertices len)]
        (let [v (az/index vertices i)
              dx (- (az/index v 0) cx) dy (- (az/index v 1) cy)
              dz (- (az/index v 2) cz)
              rx (+ (* dx rc) (* dz rs))
              rz (- (* dz rc) (* dx rs))
              vx (+ cx (- (* rx sc) (* dy ss)))
              vy (+ cy (* rx ss) (* dy sc))
              vz (+ cz rz)
              nrx (+ (* (az/index v 3) rc) (* (az/index v 5) rs))
              nrz (- (* (az/index v 5) rc) (* (az/index v 3) rs))
              nsx (- (* nrx sc) (* (az/index v 4) ss))
              nsy (+ (* nrx ss) (* (az/index v 4) sc))
              nx (- (* nsx co) (* nsy si))
              ny (+ (* nsx si) (* nsy co))
              mix (az/index v 9)]
          (material-vertex! out (+ n i)
                   (Vec3 {:x (+ x (* scale (- (* vx co) (* vy si))))
                          :y (+ y (* scale (+ (* vx si) (* vy co))))
                          :z (+ z (* scale vz))})
                   (Vec3 {:x (* (az/index v 6) (+ (- 1.0 mix) (* mix (az/field tint x))))
                          :y (* (az/index v 7) (+ (- 1.0 mix) (* mix (az/field tint y))))
                          :z (* (az/index v 8) (+ (- 1.0 mix) (* mix (az/field tint z))))})
                   (Vec3 {:x nx :y ny :z nrz})
                   (if (< (+ (az/index v 6) (az/index v 7) (az/index v 8)) 0.30)
                     0.90 0.34))))
      (+ n (az/field vertices len)))))

(az/defn model!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [x :f32] [y :f32] [z :f32] [heading :f32] [scale :f32] [tint Vec3]]
  (posed-model! out n vertices x y z heading scale tint 0.0 0.0 0.0 0.0 0.0))

(az/defn wheel-roll
  "Radians from actual arc-length travel and Blender tire radius, not wall time."
  :- :f32 [[progress :f32] [lap :u16] [radius :f32]]
  (mod (/ (* (+ progress (ak/as :f32 (ak/floatFromInt lap))) 4309.0)
          (ak/max radius 0.01)) 6.2831855))

(az/defn wheel!
  "Wheel spin, steering and suspension all come from the independent wheel body."
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [axle [:array 4 :f32]] [racer sim/RacerView] [part :usize]]
  (rigid-model! out n vertices (presentation-pose (az/field racer id) part)
    (Vec3 {:x (az/index axle 0) :y (az/index axle 1) :z (az/index axle 2)})
    (racer-tint (az/field racer id))))

(az/defn shadow-model!
  "Project upward voxel faces onto the planar road along the sunlight vector.
  This is a directional planar shadow, not scene-wide shadow mapping."
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [vertices [:slice-const [:array 10 :f32]]]
   [x :f32] [y :f32] [z :f32] [heading :f32] [scale :f32]]
  (let [^{:var :usize} next n
        co (math/cos heading) si (math/sin heading)]
    (dotimes [triangle (ak/divTrunc (az/field vertices len) 3)]
      (let [base (* triangle 3)]
        (when (and (> (az/index (az/index vertices base) 5) 0.5)
                   (<= (+ next 3) mesh/frame-capacity))
          (dotimes [corner 3]
            (let [v (az/index vertices (+ base corner))
                  height (* scale (az/index v 2))]
              (vertex! out (+ next corner)
                       (Vec3 {:x (+ x (* scale (- (* (az/index v 0) co)
                                                   (* (az/index v 1) si)))
                                    (* height 0.34642))
                              :y (+ y (* scale (+ (* (az/index v 0) si)
                                                   (* (az/index v 1) co)))
                                    (* height 0.46189))
                              :z (+ z 0.00006)})
                       (Vec3 {:x 0.035 :y 0.045 :z 0.060}))))
          (set! next (+ next 3)))))
    next))

(az/defn racer-tint :- Vec3 [[id :usize]]
  (let [color (az/index colors (mod (ak/as :usize id) sim/racer-count))]
    (Vec3 {:x (az/index color 0) :y (az/index color 1) :z (az/index color 2)})))

(az/defn line!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [x :f32] [y :f32] [bx :f32] [by :f32] [z :f32] [width :f32] [color Vec3]]
  (let [dx (- bx x) dy (- by y)
        length (ak/max 0.00001 (math/sqrt (+ (* dx dx) (* dy dy))))
        nx (* (/ (- dy) length) width) ny (* (/ dx length) width)]
    (quad! out n (Vec3 {:x (+ x nx) :y (+ y ny) :z z})
           (Vec3 {:x (+ bx nx) :y (+ by ny) :z z})
           (Vec3 {:x (- bx nx) :y (- by ny) :z z})
           (Vec3 {:x (- x nx) :y (- y ny) :z z}) color)))

(az/defn ring!
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [x :f32] [y :f32] [z :f32] [radius :f32] [color Vec3]]
  (let [^{:var true :zig/type :usize} next n]
    (dotimes [i 24]
      (let [a (* (ak/as :f32 (ak/floatFromInt i)) (/ 6.2831855 24.0))
            b (+ a (/ 6.2831855 24.0))]
        (set! next (line! out next (+ x (* radius (math/cos a)))
                         (+ y (* radius (math/sin a)))
                         (+ x (* radius (math/cos b))) (+ y (* radius (math/sin b)))
                         z 0.00008 color))))
    next))

(az/defn diamond!
  "Eight solid faces, not a screen-space diamond pretending to have depth."
  :- :usize
  [[out [:c-pointer mesh/GpuVertex]] [n :usize]
   [x :f32] [y :f32] [z :f32] [radius :f32] [color Vec3]]
  (let [^{:var true :zig/type :usize} next n]
    (dotimes [i 4]
      (let [a (* (ak/as :f32 (ak/floatFromInt i)) 1.5707963)
            b (+ a 1.5707963)
            p (Vec3 {:x (+ x (* radius (math/cos a)))
                     :y (+ y (* radius (math/sin a))) :z z})
            q (Vec3 {:x (+ x (* radius (math/cos b)))
                     :y (+ y (* radius (math/sin b))) :z z})]
        (set! next (triangle! out next p q (Vec3 {:x x :y y :z (+ z radius)}) color))
        (set! next (triangle! out next q p (Vec3 {:x x :y y :z (- z radius)})
                             (Vec3 {:x (* (az/field color x) 0.5)
                                    :y (* (az/field color y) 0.5)
                                    :z (* (az/field color z) 0.5)})))))
    next))

(az/defn effects!
  :- :usize [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^{:var true :zig/type :usize} next n]
    (dotimes [i 4]
      (let [progress (* (ak/as :f32 (ak/floatFromInt i)) 0.25)
            p (track/pose progress 0.0)]
        (set! next (diamond! out next (az/field p x) (az/field p y)
                            (+ (track/elevation progress) 0.0015) 0.0008
                            (Vec3 {:x 1.0 :y 0.78 :z 0.08})))))
    (dotimes [i sim/hazard-capacity]
      (let [hazard (sim/hazard-view i)]
        (when (az/field hazard active)
          (let [x (az/field hazard x) y (az/field hazard y)
                z (+ (track/elevation (az/field hazard progress)) 0.0003)
                color (Vec3 {:x 1.0 :y 0.25 :z 0.05})]
            (if (ak/== (az/field hazard kind) sim/item-bolt)
              (set! next (diamond! out next x y z 0.0005 color))
              (do
                (set! next (line! out next (- x 0.0008) (- y 0.0008)
                                 (+ x 0.0008) (+ y 0.0008) z 0.0001 color))
                (set! next (line! out next (- x 0.0008) (+ y 0.0008)
                                 (+ x 0.0008) (- y 0.0008) z 0.0001 color))))))))
    next))

(az/defn intents!
  :- :usize [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^{:var true :zig/type :usize} next n]
    (dotimes [i sim/racer-count]
      (let [racer (presentation-view (ak/intCast i))
            goal (track/pose (mod (+ (az/field racer progress) 0.0045) 1.0)
                             (az/field racer lane_target))]
        (when (and (ak/! (az/field racer finished)) (ak/! (sim/retired? i)))
          (set! next (line! out next (az/field racer x) (az/field racer y)
                           (az/field goal x) (az/field goal y)
                           (+ (track/elevation (az/field racer progress)) 0.001) 0.00006 (racer-tint i))))))
    next))

(az/defn containment!
  "Draw Blender's metre-space barrier triangles used by Box3D."
  :- :usize [[out [:c-pointer mesh/GpuVertex]] [n :usize]]
  (let [^{:var :usize} next n
        color (Vec3 {:x 0.64 :y 0.68 :z 0.69})]
    (dotimes [i barriers/triangle-count]
      (let [indices (az/index barriers/triangles i)
            a (az/index barriers/vertices (ak/intCast (az/index indices 0)))
            b (az/index barriers/vertices (ak/intCast (az/index indices 1)))
            c (az/index barriers/vertices (ak/intCast (az/index indices 2)))]
        (set! next (oriented-triangle! out next
          (Vec3 {:x (* 0.001 (az/index a 0)) :y (* 0.001 (az/index a 1)) :z (* 0.001 (az/index a 2))})
          (Vec3 {:x (* 0.001 (az/index b 0)) :y (* 0.001 (az/index b 1)) :z (* 0.001 (az/index b 2))})
          (Vec3 {:x (* 0.001 (az/index c 0)) :y (* 0.001 (az/index c 1)) :z (* 0.001 (az/index c 2))})
          color false))))
    next))

(az/defn gpu-instance
  "Compact per-part state. No vertex iteration or pose approximation."
  :- mesh/GpuInstance [[id :usize] [part :usize] [origin Vec3]]
  (let [state (presentation-pose id part)
        racer (presentation-view (ak/intCast id))
        tint (racer-tint id)]
    (mesh/GpuInstance
      {:qx (az/field state qx) :qy (az/field state qy)
       :qz (az/field state qz) :qw (az/field state qw)
       :x (az/field state x) :y (az/field state y) :z (az/field state z) :scale 0.001
       :origin_x (az/field origin x) :origin_y (az/field origin y) :origin_z (az/field origin z)
       :shadow_z (+ (track/elevation (az/field racer progress)) 0.00008)
       :r (az/field tint x) :g (az/field tint y) :b (az/field tint z) :mode 0.0})))

(az/defn draw-instanced-part!
  :- :bool [[slot :usize] [revision :u64]
             [vertices [:slice-const [:array 10 :f32]]]
             [part :usize] [origin Vec3] [camera mesh/InstanceCamera]]
  (let [^{:var [:array sim/racer-count mesh/GpuInstance]} instances ak/undefined]
    (dotimes [i sim/racer-count]
      (set! (az/index instances i) (gpu-instance i part origin)))
    (when (ak/! (renderer/draw-instances! slot revision vertices (ak/& instances) camera))
      (ak/return false))
    (dotimes [i sim/racer-count]
      (set! (az/field (az/index instances i) mode) 1.0))
    (renderer/draw-instances! slot revision vertices (ak/& instances) camera)))

(az/defn draw-racers!
  "Six immutable meshes, 20 independently posed instances of each. Wheels are
  still separate rigid bodies. Only transforms/tints/shadow planes are uploaded."
  :- :bool []
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
        (Vec3 {:x (az/index geometry/wheel-front-left-axle 0) :y (az/index geometry/wheel-front-left-axle 1)
               :z (az/index geometry/wheel-front-left-axle 2)}) camera)
      (draw-instanced-part! 3 geometry/wheel-front-right-revision (ak/& geometry/wheel-front-right-vertices) 2
        (Vec3 {:x (az/index geometry/wheel-front-right-axle 0) :y (az/index geometry/wheel-front-right-axle 1)
               :z (az/index geometry/wheel-front-right-axle 2)}) camera)
      (draw-instanced-part! 4 geometry/wheel-rear-left-revision (ak/& geometry/wheel-rear-left-vertices) 3
        (Vec3 {:x (az/index geometry/wheel-rear-left-axle 0) :y (az/index geometry/wheel-rear-left-axle 1)
               :z (az/index geometry/wheel-rear-left-axle 2)}) camera)
      (draw-instanced-part! 5 geometry/wheel-rear-right-revision (ak/& geometry/wheel-rear-right-vertices) 4
        (Vec3 {:x (az/index geometry/wheel-rear-right-axle 0) :y (az/index geometry/wheel-rear-right-axle 1)
               :z (az/index geometry/wheel-rear-right-axle 2)}) camera))))

(az/defn build-world-geometry!
  "Build the non-instanced world stream. Cars are separate GPU draws, so they
  no longer consume or overflow this CPU vertex buffer."
  :- :u32 [[out [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (let [w (ak/as :f32 (ak/floatFromInt (ak/max width 1)))
        h (ak/as :f32 (ak/floatFromInt (ak/max height 1)))]
    (set! fit-x (ak/min 1.0 (/ h w)))
    (set! fit-y (ak/min 1.0 (/ w h))))
  (let [human (sim/human-control-snapshot)
        ^{:var true :zig/type :usize} next (containment! out (road! out 0))]
    (dotimes [team sim/team-count]
      (let [p (track/pit-pose (sim/pit-box-progress (ak/intCast team)) 0.285)]
        (set! next (model! out next (ak/& geometry/garage-vertices)
                          (az/field p x) (az/field p y) (+ (track/elevation (sim/pit-box-progress (ak/intCast team))) 0.00003)
                          (az/field p heading) 0.003 (racer-tint (* team 2))))))
    (set! next (effects! out next))
    (dotimes [i sim/racer-count]
      (let [racer (presentation-view (ak/intCast i))
            x (az/field racer x) y (az/field racer y)
            z (+ (track/elevation (az/field racer progress)) 0.00008)]
        (when (az/field racer shielded)
          (set! next (ring! out next x y (+ z 0.0002) 0.0032 (racer-tint i))))
        (when (and (az/field human enabled) (ak/== i 0))
          (set! next (ring! out next x y (+ z 0.0002) 0.0035 (Vec3 {:x 1.0 :y 1.0 :z 1.0}))))))
    (ak/intCast next)))

(az/defn build-world!
  "FrameBuilder entry point: stream world geometry and issue bounded car draws
  inside the active Vulkan render pass. Publish these two paths together."
  :- :u32 [[out [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (let [count (build-world-geometry! out width height)]
    (when (ak/! (draw-racers!))
      (std-debug/panic "Unable to draw the bounded GPU car instances" []))
    count))
