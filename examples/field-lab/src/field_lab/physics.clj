(ns field-lab.physics
  "Double precision SI sphere dynamics. No window or renderer dependency."
  (:require [aguafria.std]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.math :as math]))

(az/defstruct Vec3 {:layout :extern} [[:x :f64] [:y :f64] [:z :f64]])

(az/defn v
  :- Vec3
  [[x :f64] [y :f64] [z :f64]]
  (Vec3 {:x x :y y :z z}))

(az/defn add
  :- Vec3
  [[a Vec3] [b Vec3]]
  (v (+ (az/field a x) (az/field b x))
     (+ (az/field a y) (az/field b y))
     (+ (az/field a z) (az/field b z))))

(az/defn scale
  :- Vec3
  [[a Vec3] [s :f64]]
  (v (* (az/field a x) s) (* (az/field a y) s) (* (az/field a z) s)))

(az/defn dot
  :- :f64
  [[a Vec3] [b Vec3]]
  (+ (* (az/field a x) (az/field b x))
     (* (az/field a y) (az/field b y))
     (* (az/field a z) (az/field b z))))

(az/defn cross
  :- Vec3
  [[a Vec3] [b Vec3]]
  (v (- (* (az/field a y) (az/field b z)) (* (az/field a z) (az/field b y)))
     (- (* (az/field a z) (az/field b x)) (* (az/field a x) (az/field b z)))
     (- (* (az/field a x) (az/field b y)) (* (az/field a y) (az/field b x)))))

(az/defn length
  :- :f64
  [[a Vec3]]
  (ak/sqrt (dot a a)))

(az/defstruct Quaternion {:layout :extern} [[:x :f64] [:y :f64] [:z :f64] [:w :f64]])

(az/defstruct Config
  {:layout :extern}
  [[:radius :f64] [:mass :f64] [:height :f64] [:gravity :f64] [:restitution :f64]
   [:friction :f64] [:rolling :f64] [:vx :f64] [:vz :f64] [:spin :f64]])

(az/defstruct State
  {:layout :extern}
  [[:position Vec3] [:velocity Vec3] [:omega Vec3] [:orientation Quaternion] [:time :f64]
   [:impulse :f64] [:impacts :u32] [:supported :bool]])

(az/defn defaults
  :- Config
  []
  (Config {:radius 0.45
           :mass 0.62
           :height 3.5
           :gravity 9.81
           :restitution 0.78
           :friction 0.35
           :rolling 0.025
           :vx 1.1
           :vz 0.25
           :spin 1.5}))

(az/defn initial
  :- State
  [[c Config]]
  (State {:position (v -1.5 (+ (az/field c height) (az/field c radius)) -0.3)
          :velocity (v (az/field c vx) 0.0 (az/field c vz))
          :omega (v 0.0 (az/field c spin) 0.0)
          :orientation (Quaternion {:x 0.0 :y 0.0 :z 0.0 :w 1.0})
          :time 0.0
          :impulse 0.0
          :impacts 0
          :supported false}))

(az/defn inertia
  :- :f64
  [[c Config]]
  (* 0.4 (az/field c mass) (az/field c radius) (az/field c radius)))

(az/defn kinetic
  :- :f64
  [[s State] [c Config]]
  (+ (* 0.5 (az/field c mass) (dot (az/field s velocity) (az/field s velocity)))
     (* 0.5 (inertia c) (dot (az/field s omega) (az/field s omega)))))

(az/defn energy
  :- :f64
  [[s State] [c Config]]
  (+ (kinetic s c)
     (* (az/field c mass)
        (az/field c gravity)
        (- (az/field (az/field s position) y) (az/field c radius)))))

(az/defn rotate!
  :- :void
  [[s [:* State]] [dt :f64]]
  (let [w (az/field (az/deref s) omega)
        speed (length w)]
    (when (> speed 1.0e-12)
      (let [q (az/field (az/deref s) orientation)
            half (* 0.5 speed dt)
            a (scale w (/ (math/sin half) speed))
            b (math/cos half)
            qv (v (az/field q x) (az/field q y) (az/field q z))
            xyz (add (add (scale qv b) (scale a (az/field q w))) (cross a qv))
            qw (- (* b (az/field q w)) (dot a qv))
            norm (ak/sqrt (+ (dot xyz xyz) (* qw qw)))]
        (set! (az/field (az/deref s) orientation)
              (Quaternion {:x (/ (az/field xyz x) norm)
                           :y (/ (az/field xyz y) norm)
                           :z (/ (az/field xyz z) norm)
                           :w (/ qw norm)}))))))

(az/defn flight!
  :- :void
  [[s [:* State]] [c Config] [dt :f64]]
  (let [a (v 0.0 (- (az/field c gravity)) 0.0)]
    (az/set-many!
      (az/field (az/deref s) position)
      (add (az/field (az/deref s) position)
           (add (scale (az/field (az/deref s) velocity) dt)
                (scale a (* 0.5 dt dt))))
      (az/field (az/deref s) velocity)
      (add (az/field (az/deref s) velocity)
           (scale a dt)))
    (rotate! s dt)))

(az/defn friction!
  :- :void
  [[s [:* State]] [c Config] [normal-impulse :f64]]
  (let [arm (v 0.0 (- (az/field c radius)) 0.0)
        slip0 (add (az/field (az/deref s) velocity) (cross (az/field (az/deref s) omega) arm))
        slip (v (az/field slip0 x) 0.0 (az/field slip0 z))
        speed (length slip)]
    (when (> speed 1.0e-12)
      (let [effective (+ (/ 1.0 (az/field c mass))
                         (/ (* (az/field c radius) (az/field c radius)) (inertia c)))
            magnitude (ak/min (/ speed effective) (* (az/field c friction) normal-impulse))
            j (scale slip (/ (- magnitude) speed))]
        (az/set-many!
          (az/field (az/deref s) velocity)
          (add (az/field (az/deref s) velocity)
               (scale j (/ 1.0 (az/field c mass))))
          (az/field (az/deref s) omega)
          (add (az/field (az/deref s) omega)
               (scale (cross arm j)
                      (/ 1.0 (inertia c)))))))))

(az/defn contact!
  :- :void
  [[s [:* State]] [c Config] [dt :f64]]
  (az/set-many!
    (az/field (az/field (az/deref s) position) y) (az/field c radius)
    (az/field (az/field (az/deref s) velocity) y) 0.0)
  (friction! s c (* (az/field c mass) (az/field c gravity) dt))
  (let [speed (length (az/field (az/deref s) velocity))
        loss (* (az/field c rolling) (az/field c gravity) dt)
        f (if (> speed 1.0e-12) (ak/max 0.0 (- 1.0 (/ loss speed))) 0.0)]
    (az/set-many!
      (az/field (az/deref s) velocity) (scale (az/field (az/deref s) velocity) f)
      (az/field (az/deref s) omega)
      (v (* (az/field (az/field (az/deref s) omega) x) f)
         (* (az/field (az/field (az/deref s) omega) y)
            (ak/exp (- (* (az/field c rolling) dt 5.0))))
         (* (az/field (az/field (az/deref s) omega) z) f))))
  (set! (az/field (az/deref s) position)
        (add (az/field (az/deref s) position) (scale (az/field (az/deref s) velocity) dt)))
  (rotate! s dt))

(az/defn advance
  "Event-resolved ballistic flight; finite impact loop and resting-contact branch."
  :- State
  [[input State] [c Config] [dt :f64]]
  (let [^:var s input
        ^{:var :f64} remaining dt
        ^{:var :u32} events 0]
    (set! (az/field s impulse) 0.0)
    (while (and (> remaining 1.0e-12) (< events 16))
      (if (az/field s supported)
        (do (contact! (ak/& s) c remaining) (set! remaining 0.0))
        (let [h (ak/max 0.0 (- (az/field (az/field s position) y) (az/field c radius)))
              vy (az/field (az/field s velocity) y)
              g (az/field c gravity)
              root (ak/sqrt (+ (* vy vy) (* 2.0 g h)))
              ;; Stable quadratic root, also valid when gravity is zero.
              hit (if (> g 1.0e-12)
                    (if (< vy 0.0) (/ (* 2.0 h) (ak/max 1.0e-30 (- root vy))) (/ (+ vy root) g))
                    (if (< vy -1.0e-12) (/ h (- vy)) 1.0e30))]
          (if (> hit remaining)
            (do (flight! (ak/& s) c remaining) (set! remaining 0.0))
            (do (flight! (ak/& s) c hit)
                (set! (az/field (az/field s position) y) (az/field c radius))
                (let [incoming (az/field (az/field s velocity) y)
                      bounce (* (- incoming) (az/field c restitution))
                      rest (< bounce 0.03)
                      j (* (az/field c mass) (- (if rest 0.0 bounce) incoming))]
                  (az/set-many!
                    (az/field (az/field s velocity) y) (if rest 0.0 bounce)
                    (az/field s supported) rest
                    (az/field s impulse) (+ (az/field s impulse) j)
                    (az/field s impacts) (+ (az/field s impacts) 1))
                  (friction! (ak/& s) c j))
                (az/set-many!
                  remaining (- remaining hit)
                  events (+ events 1)))))))
    ;; If an extreme input exhausts the event budget, consume the time at rest.
    (when (> remaining 1.0e-12)
      (set! (az/field s supported) true)
      (contact! (ak/& s) c remaining))
    (set! (az/field s time) (+ (az/field input time) dt))
    s))
