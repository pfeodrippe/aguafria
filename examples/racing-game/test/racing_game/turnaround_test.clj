(ns racing-game.turnaround-test
  "Captured wrong-way contact; every transform is an initial condition only."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [aguafria-examples-native.box3d]
            [aguafria-examples-native.bindings.box3d :as b3]
            [racing-game.circuit :as circuit]
            [racing-game.physics :as physics]
            [racing-game.protocol :as protocol]
            [racing-game.physics-track :as terrain]
            [racing-game.track-barriers :as barriers]
            [racing-game.vehicle-driver :as driver]
            [racing-game.vehicle-turnaround :as turnaround]
            [clojure.test :refer [deftest is]]))

(az/defconst captured-poses [:array 2 [:array 5 [:array 7 :f32]]]
  [[[239.07066 100.60667 12.330057 0.010830929 -0.024548784 0.7238942 0.6893891] [237.99895 102.29502 12.238902 -0.291926 0.2979404 -0.6539227 -0.6311861] [239.9763 102.39069 12.280365 0.18615709 -0.19653536 0.6901038 0.67117524] [238.16727 98.883736 12.085426 -0.3201211 0.2928096 0.64930904 0.6246462] [240.14474 98.97973 12.123013 -0.71786517 0.68569946 -0.09361024 -0.07565011]] [[241.1403 105.060394 12.220322 0.9637829 0.26582584 0.013523881 -0.016625404] [243.131 105.088715 12.4703245 -0.43988946 -0.13522616 0.8551066 -0.23875518] [242.11319 106.78764 12.524979 0.8974655 0.2525304 0.34382737 -0.1121022] [240.19768 103.339226 12.329735 0.7301177 0.20983216 -0.62904036 0.16494523] [239.18176 105.03795 12.386536 0.81584334 0.21938118 0.5128923 -0.15235792]]])

(az/defconst shoulder-poses [:array 2 [:array 5 [:array 7 :f32]]]
  [[[236.51576 104.95712 12.487901 0.022861531 -0.015041484 0.04570522 0.9985801] [238.15266 106.1077 12.416257 0.10468561 0.9876084 0.01483992 0.1159758] [238.33517 104.13764 12.33113 0.088701814 0.47930133 -0.10428547 0.86690646] [234.75026 105.796074 12.334389 0.039767064 -0.9709303 -0.031420324 -0.23393528] [234.93213 103.82625 12.248111 -0.04760181 0.68253446 -0.018546827 -0.72906584]] [[241.17311 105.46368 12.227535 0.9989593 0.03757832 0.012053156 -0.022867471] [242.97003 104.59672 12.441915 -0.43879184 -0.039063368 0.8972387 -0.029976575] [242.8208 106.56844 12.531493 0.91918236 0.03390523 0.39043772 -0.038893823] [239.56331 104.34283 12.361019 0.8104603 0.03928843 -0.5844737 9.268892E-4] [239.41182 106.31381 12.448287 0.9248255 0.02792381 0.37775308 -0.03493922]]])

(az/defvar recovery-trace [:array 120 [:array 12 :f32]] ak/undefined)

(az/defn recovery-trace-at :- [:array 12 :f32] [[index :usize]]
  (az/index recovery-trace index))

(az/defn create-car :- physics/Vehicle [[world b3/b3WorldId] [index :usize] [shoulder? :bool]]
  (let [poses (if shoulder? shoulder-poses captured-poses)
        p (az/index (az/index poses index) 0)
        car (physics/create-vehicle world
              (b3/b3Pos {:x (az/index p 0) :y (az/index p 1) :z (az/index p 2)}) 0.0)]
    (dotimes [i 5]
      (let [pose (az/index (az/index poses index) i)
            body (if (ak/== i 0) (az/field car chassis) (az/index (az/field car wheels) (- i 1)))]
        (b3/b3Body_SetTransform body
          (b3/b3Pos {:x (az/index pose 0) :y (az/index pose 1) :z (az/index pose 2)})
          (b3/b3Quat {:v {:x (az/index pose 3) :y (az/index pose 4) :z (az/index pose 5)}
                       :s (az/index pose 6)}))))
    car))

(az/defn turn-probe
  "Forward metres, max lane, min upright, final alignment, gear mask, speed,
  maximum speed when changing direction, and whether turnaround remains active."
  :- [:array 9 :f32] [[seconds :usize] [turn? :bool] [shoulder? :bool]]
  (let [world (physics/create-world -9.81)
        surface (terrain/create! world)
        containment (barriers/create! world)
        car (create-car world 0 shoulder?)
        other (create-car world 1 shoulder?)
        start (az/field (driver/follow car 8.0 -3.75) progress)
        ^:var state (mem/zeroes (az/type turnaround/State))
        ^:var bodies (mem/zeroes (az/type [:array protocol/racer-count physics/BodyState]))
        ^{:var :u8} gears 0
        ^{:var :i8} previous-gear 0
        ^{:var :f32} gear-change-speed 0.0
        ^{:var :f32} lane 0.0
        ^{:var :f32} up 1.0]
    (ak/defer (terrain/destroy! surface))
    (ak/defer (barriers/destroy! containment))
    (ak/defer (physics/destroy-world! world))
    (dotimes [tick (* seconds 120)]
      (let [normal (driver/follow car 8.0 -3.75)
            body (physics/body-state (az/field car chassis))]
        (set! (az/index bodies 0) body)
        (set! (az/index bodies 1) (physics/body-state (az/field other chassis)))
        (let [output (turnaround/step state normal body bodies 2 0 turn? world)
              gear (if (az/field (az/field output state) active)
                     (az/field (az/field output state) gear) (ak/as :i8 1))]
          (set! state (az/field output state))
          (when (and (< tick 14400) (ak/== (mod tick 120) 0))
            (let [sign (az/field state turn_sign)
                  limit (az/field state lane_limit)]
              (set! (az/index recovery-trace (ak/divTrunc tick 120))
                (az/array-init [:array 12 :f32]
                  [(/ (ak/as :f32 (ak/floatFromInt tick)) 120.0)
                   (az/field body x) (az/field body y) (turnaround/heading body)
                   (az/field normal lane) (ak/as :f32 (ak/floatFromInt (az/field state gear)))
                   sign limit
                   (ak/as :f32 (ak/floatFromInt (turnaround/clearance-reasons body bodies 2 0 1 sign world limit)))
                   (ak/as :f32 (ak/floatFromInt (turnaround/clearance-reasons body bodies 2 0 -1 sign world limit)))
                   (ak/as :f32 (ak/floatFromInt (turnaround/clearance-reasons body bodies 2 0 1 (- sign) world limit)))
                   (ak/as :f32 (ak/floatFromInt (turnaround/clearance-reasons body bodies 2 0 -1 (- sign) world limit)))]))))
          (when (and (ak/!= previous-gear 0) (ak/!= gear 0) (ak/!= gear previous-gear))
            (set! gear-change-speed (ak/max gear-change-speed (az/field normal speed))))
          (set! previous-gear gear)
          (set! gears (ak/| gears (ak/<< (ak/as :u8 1) (ak/intCast (+ gear 1)))))
          (set! lane (ak/max lane (ak/abs (az/field normal lane))))
          (set! up (ak/min up (- 1.0 (* 2.0 (+ (* (az/field body qx) (az/field body qx))
                                              (* (az/field body qy) (az/field body qy)))))))
          (dotimes [_ (ak/divTrunc physics/step-rate 120)]
            (physics/drive-in-gear! car (az/field (az/field output control) throttle)
              (az/field (az/field output control) brake) (az/field (az/field output control) steering) gear)
            (physics/drive! other 0.0 1.0 0.0)
            (physics/step! world)))))
    (let [final (driver/follow car 8.0 -3.75)
          body (physics/body-state (az/field car chassis))
          road (circuit/at-distance (* 4309.0 (az/field final progress)) 0.0)]
      (az/array-init [:array 9 :f32]
        [(* 4309.0 (- (az/field final progress) start)) lane up
         (math/cos (- (turnaround/heading body) (az/field road heading)))
         (ak/as :f32 (ak/floatFromInt gears)) (az/field final speed)
         gear-change-speed (if (az/field state active) (ak/as :f32 1.0) 0.0)
         (az/field final lane)]))))

(deftest captured-wrong-way-wreck-turnaround-test
  (let [[distance lane up alignment gears speed gear-change-speed active :as result]
        (az/value (turn-probe 90 true false))]
    (is (> distance 100.0) (pr-str result))
    (is (< lane 7.5) (pr-str result))
    (is (> up 0.9) (pr-str result))
    (is (> alignment 0.98) (pr-str result))
    (is (= 5.0 (double gears)) "Both forward and reverse must actually be used")
    (is (> speed 4.0) (pr-str result))
    (is (< gear-change-speed 0.05) "Brake to a stop before reversing direction")
    (is (zero? active) "Return control to the model's ordinary destination")))

(deftest captured-shoulder-turnaround-with-containment-test
  (let [[distance lane up alignment gears speed change-speed active final-lane :as result]
        (az/value (turn-probe 90 true true))]
    (is (> distance 100.0) (pr-str result))
    (is (< lane 12.0) (pr-str result))
    (is (> up 0.9) (pr-str result))
    (is (> alignment 0.98) (pr-str result))
    (is (pos? (bit-and 4 (long gears))) "Resume forward motor propulsion")
    (is (> speed 4.0) (pr-str result))
    (is (< change-speed 0.05) (pr-str result))
    (is (zero? active) (pr-str result))
    (is (< (abs final-lane) 4.5) "Return from the shoulder to the racing corridor")))

(az/defn disabled-probe :- turnaround/Output [[speed :f32] [active :bool]]
  (let [p (circuit/at-distance 0.0 0.0)
        yaw (+ (az/field p heading) 3.14159265)
        ^:var body (mem/zeroes (az/type physics/BodyState))
        normal (driver/Control {:throttle 0.7 :brake 0.0 :steering 0.1
                                 :progress 0.0 :lane 0.0 :speed speed})
        state (turnaround/State {:active active :gear -1 :turn_sign 1.0 :lane_limit 7.0})]
    (set! (az/field body x) (az/field p x))
    (set! (az/field body y) (az/field p y))
    (set! (az/field body qw) (math/cos (* yaw 0.5)))
    (set! (az/field body qz) (math/sin (* yaw 0.5)))
    (turnaround/step state normal body
      (mem/zeroes (az/type [:array protocol/racer-count physics/BodyState])) 0 0 false
      (mem/zeroes (az/type b3/b3WorldId)))))

(deftest disabled-turnaround-does-not-drive-test
  (let [moving (az/value (disabled-probe 1.0 true))
        stopped (az/value (disabled-probe 0.0 true))
        inactive (az/value (disabled-probe 1.0 false))]
    (is (true? (get-in moving [:state :active])))
    (is (= -1 (get-in moving [:state :gear])))
    (is (= 0.0 (get-in moving [:control :throttle])))
    (is (= 1.0 (get-in moving [:control :brake])))
    (is (false? (get-in stopped [:state :active])))
    (is (= 0 (get-in stopped [:state :gear])))
    (is (false? (get-in inactive [:state :active])))
    (is (< (abs (- 0.7 (get-in inactive [:control :throttle]))) 1.0e-6))))

(defn- planar-body
  [attributes]
  (merge {:x 0.0 :y 0.0 :z 0.0 :vx 0.0 :vy 0.0 :vz 0.0
          :qx 0.0 :qy 0.0 :qz 0.0 :qw 1.0 :wx 0.0 :wy 0.0 :wz 0.0}
         attributes))

(deftest oriented-car-footprint-test
  (doseq [other [(planar-body {:x 5.5})
                 (planar-body {:y 3.34})
                 (planar-body {:x 4.42 :qz (Math/sqrt 0.5) :qw (Math/sqrt 0.5)})]]
    (is (< (abs (- 0.4 (turnaround/footprint-separation 0.0 0.0 0.0 other))) 1.0e-5)))
  (is (neg? (turnaround/footprint-separation 0.0 0.0 0.0 (planar-body {:x 5.0 :y 2.8}))))
  (is (neg? (turnaround/footprint-separation 0.0 0.0 0.0
               (planar-body {:x 3.8 :y 3.8 :qz (Math/sin (/ Math/PI 8.0))
                             :qw (Math/cos (/ Math/PI 8.0))})))))

(deftest fast-crossing-traffic-test
  (let [self (planar-body {})]
    ;; Both endpoints are clear, but the fast car crosses the recovery path.
    (is (false? (turnaround/moving-traffic-clear? self
                  (planar-body {:x 30.0 :y -10.0 :vx -60.0 :vy 20.0}) 1.0 0.0 1.0)))
    (is (true? (turnaround/moving-traffic-clear? self
                 (planar-body {:x 12.0 :vx 20.0}) 1.0 0.0 1.0)))
    (is (true? (turnaround/moving-traffic-clear? self
                 (planar-body {:y 8.0 :vx 1.0}) 1.0 0.0 1.0)))
    (is (false? (turnaround/moving-traffic-clear? self
                  (planar-body {:x 2.0}) 1.0 0.0 1.0)))))

(deftest disabled-trace-has-no-invalid-arc-test
  (is (every? #(Double/isFinite (double %)) (az/value (turn-probe 1 false true))))
  (is (= [32.0 32.0 32.0 32.0]
         (mapv double (subvec (az/value (recovery-trace-at 0)) 8 12)))))
